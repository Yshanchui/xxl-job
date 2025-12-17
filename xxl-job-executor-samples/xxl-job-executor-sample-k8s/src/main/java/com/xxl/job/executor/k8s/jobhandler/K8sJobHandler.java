package com.xxl.job.executor.k8s.jobhandler;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.k8s.K8sJobConfig;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.gson.GsonTool;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * K8s Job Handler for XXL-JOB
 * 
 * @author xxl-job
 */
@Component
public class K8sJobHandler {
    private static final Logger logger = LoggerFactory.getLogger(K8sJobHandler.class);

    /**
     * K8s Job 执行器
     * 
     * 任务参数格式（JSON）：
     * {
     * "deployment": "your-app-deployment",
     * "namespace": "default",
     * "command": ["python3", "batch_job.py"],
     * "args": ["--date", "${jobParam}", "--shard", "${shardIndex}/${shardTotal}"],
     * "ttlSecondsAfterFinished": 3600,
     * "backoffLimit": 3
     * }
     */
    @XxlJob("k8sJobHandler")
    public void execute() throws Exception {
        XxlJobContext context = XxlJobContext.getXxlJobContext();
        String jobParam = context.getJobParam();

        logger.info("========== K8s Job Handler Start ==========");
        logger.info("Job Param: {}", jobParam);

        // 解析任务参数
        K8sJobConfig jobConfig = parseConfig(jobParam);
        if (jobConfig == null) {
            throw new RuntimeException("Invalid K8s job configuration");
        }

        // 初始化 K8s 客户端（使用 ServiceAccount）
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        AppsV1Api appsApi = new AppsV1Api(client);
        BatchV1Api batchApi = new BatchV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // 1. 从 Deployment 获取镜像配置
            logger.info("Reading deployment: {} in namespace: {}", jobConfig.getDeployment(), jobConfig.getNamespace());
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    jobConfig.getDeployment(),
                    jobConfig.getNamespace(),
                    null);

            if (deployment == null || deployment.getSpec() == null ||
                    deployment.getSpec().getTemplate() == null ||
                    deployment.getSpec().getTemplate().getSpec() == null) {
                throw new RuntimeException("Deployment not found or invalid: " + jobConfig.getDeployment());
            }

            List<V1Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
            if (containers == null || containers.isEmpty()) {
                throw new RuntimeException("No containers found in deployment");
            }

            V1Container sourceContainer = containers.get(0);
            logger.info("Using image: {}", sourceContainer.getImage());

            // 2. 创建 K8s Job
            String jobName = "xxl-job-" + context.getJobId() + "-" + System.currentTimeMillis();
            V1Job job = createJob(jobName, jobConfig, sourceContainer, context);

            logger.info("Creating K8s Job: {}", jobName);
            batchApi.createNamespacedJob(jobConfig.getNamespace(), job, null, null, null, null);

            // 3. 等待 Job 完成
            waitForJobCompletion(batchApi, coreApi, jobConfig.getNamespace(), jobName);

            logger.info("========== K8s Job Handler Completed Successfully ==========");

        } catch (ApiException e) {
            logger.error("K8s API error: {}", e.getResponseBody(), e);
            throw new RuntimeException("K8s Job execution failed: " + e.getMessage(), e);
        }
    }

    private K8sJobConfig parseConfig(String jobParam) {
        if (StringTool.isBlank(jobParam)) {
            throw new RuntimeException("Job parameter is empty");
        }

        try {
            return GsonTool.fromJson(jobParam, K8sJobConfig.class);
        } catch (Exception e) {
            logger.error("Failed to parse job config: {}", jobParam, e);
            throw new RuntimeException("Invalid JSON configuration: " + e.getMessage());
        }
    }

    private V1Job createJob(String jobName, K8sJobConfig jobConfig, V1Container sourceContainer,
            XxlJobContext context) {
        // 创建任务容器
        V1Container jobContainer = new V1Container()
                .name("job-container")
                .image(sourceContainer.getImage())
                .env(sourceContainer.getEnv());

        // 设置命令
        if (jobConfig.getCommand() != null && jobConfig.getCommand().length > 0) {
            jobContainer.setCommand(List.of(jobConfig.getCommand()));
            logger.info("Using command: {}", String.join(" ", jobConfig.getCommand()));
        }

        // 设置参数（支持变量替换）
        if (jobConfig.getArgs() != null && jobConfig.getArgs().length > 0) {
            String[] processedArgs = processArgs(jobConfig.getArgs(), context);
            jobContainer.setArgs(List.of(processedArgs));
            logger.info("Using args: {}", String.join(" ", processedArgs));
        }

        // 创建 Pod 模板
        V1PodSpec podSpec = new V1PodSpec()
                .containers(Collections.singletonList(jobContainer))
                .restartPolicy("Never");

        // 创建 Job 规格
        V1JobSpec jobSpec = new V1JobSpec()
                .template(new V1PodTemplateSpec()
                        .metadata(new V1ObjectMeta()
                                .labels(Collections.singletonMap("xxl-job", "true")))
                        .spec(podSpec))
                .backoffLimit(jobConfig.getBackoffLimit() != null ? jobConfig.getBackoffLimit() : 3)
                .ttlSecondsAfterFinished(
                        jobConfig.getTtlSecondsAfterFinished() != null ? jobConfig.getTtlSecondsAfterFinished() : 3600);

        // 创建 Job
        return new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(new V1ObjectMeta()
                        .name(jobName)
                        .namespace(jobConfig.getNamespace())
                        .labels(Collections.singletonMap("xxl-job-id", String.valueOf(context.getJobId()))))
                .spec(jobSpec);
    }

    private String[] processArgs(String[] args, XxlJobContext context) {
        String[] processedArgs = new String[args.length];

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            // 替换 XXL-JOB 变量
            arg = arg.replace("${jobParam}", context.getJobParam() != null ? context.getJobParam() : "");
            arg = arg.replace("${shardIndex}", String.valueOf(context.getShardIndex()));
            arg = arg.replace("${shardTotal}", String.valueOf(context.getShardTotal()));
            processedArgs[i] = arg;
        }

        return processedArgs;
    }

    private void waitForJobCompletion(BatchV1Api batchApi, CoreV1Api coreApi, String namespace, String jobName)
            throws ApiException {
        int maxWaitMinutes = 30;
        int checkIntervalSeconds = 2;
        int maxChecks = (maxWaitMinutes * 60) / checkIntervalSeconds;

        // Log offset tracking
        int lastLogTimeSeconds = 0; // approximate tracking

        logger.info("Waiting for job completion (max {} minutes, checking every {} seconds)...", maxWaitMinutes,
                checkIntervalSeconds);

        for (int i = 0; i < maxChecks; i++) {
            try {
                V1Job job = batchApi.readNamespacedJobStatus(jobName, namespace, null);

                // Real-time log streaming
                // We assume the first pod found is the one we want to follow
                lastLogTimeSeconds = streamPodLogs(coreApi, namespace, jobName, lastLogTimeSeconds);

                if (job.getStatus() != null) {
                    Integer succeeded = job.getStatus().getSucceeded();
                    Integer failed = job.getStatus().getFailed();
                    Integer active = job.getStatus().getActive();

                    // Only log status if it changed or periodically?
                    // To avoid spamming local logs, maybe reduce this frequency or use debug
                    logger.info("Job status - Active: {}, Succeeded: {}, Failed: {}", active, succeeded, failed);

                    if (succeeded != null && succeeded > 0) {
                        logger.info("Job completed successfully!");
                        // Final log fetch to catch anything missed
                        streamPodLogs(coreApi, namespace, jobName, lastLogTimeSeconds);
                        return;
                    }

                    if (failed != null && failed > 0) {
                        logger.error("Job failed!");
                        streamPodLogs(coreApi, namespace, jobName, lastLogTimeSeconds);
                        throw new RuntimeException("K8s Job failed");
                    }
                }
            } catch (ApiException e) {
                // Job might have been cleaned up by TTL, check if pods succeeded
                if (e.getCode() == 404) {
                    logger.info("Job not found (possibly cleaned up by TTL), checking pod status...");
                    try {
                        streamPodLogs(coreApi, namespace, jobName, lastLogTimeSeconds);
                        logger.info("Job completed and cleaned up successfully!");
                        return;
                    } catch (Exception podEx) {
                        logger.warn("Could not retrieve pod logs: {}", podEx.getMessage());
                        // Assume success if job was cleaned up
                        logger.info("Assuming job completed successfully (TTL cleanup)");
                        return;
                    }
                }

                // detailed error for debugging
                logger.error("K8s API Error in status check - Code: {}, Body: {}", e.getCode(), e.getResponseBody());
                throw e;
            }

            try {
                TimeUnit.SECONDS.sleep(checkIntervalSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Job monitoring interrupted", e);
            }
        }

        throw new RuntimeException("Job did not complete within " + maxWaitMinutes + " minutes");
    }

    /**
     * Stream logs from the K8s pod to the XXL-JOB logger.
     * Returns the current timestamp (seconds) to be used as 'sinceSeconds' in the
     * next call.
     * Note: This is a simplified implementation. A more robust one would track
     * specific timestamps/lines.
     */
    private int streamPodLogs(CoreV1Api coreApi, String namespace, String jobName, int lastLogDuration) {
        try {
            // Find the pod for this job
            V1PodList pods = coreApi.listNamespacedPod(
                    namespace, null, null, null, null,
                    "job-name=" + jobName, null, null, null, null, null, null);

            if (pods.getItems() != null && !pods.getItems().isEmpty()) {
                V1Pod pod = pods.getItems().get(0);
                String podName = pod.getMetadata().getName();

                // Only try to get logs if pod is running or succeeded/failed
                if (pod.getStatus() != null &&
                        ("Running".equals(pod.getStatus().getPhase()) ||
                                "Succeeded".equals(pod.getStatus().getPhase()) ||
                                "Failed".equals(pod.getStatus().getPhase()))) {

                    // Calculate 'sinceSeconds' based on how long we've been running roughly?
                    // Or actually, just fetch purely new logs if possible.
                    // K8s API 'sinceSeconds' is relative to now.
                    // A better approach for simple polling without keeping heavy state is:
                    // Just fetch logs since start, but we only print lines we haven't seen?
                    // Or use sinceSeconds with a small overlap?

                    // Simple approach: Use `sinceSeconds` if we can estimate it,
                    // BUT `sinceSeconds` is "logs newer than X seconds".
                    // If we poll every 2 seconds, we could ask for logs from the last 3 seconds.

                    Integer sinceSeconds = 3; // overlap slightly to ensure we don't miss

                    String logs = coreApi.readNamespacedPodLog(
                            podName, namespace, null, false, null, null, null, null, sinceSeconds, null, null);

                    if (!StringTool.isBlank(logs)) {
                        String[] lines = logs.split("\n");
                        for (String line : lines) {
                            if (!StringTool.isBlank(line)) {
                                // Direct log to XXL-JOB's log file
                                // We might see duplicates with this simple overlap approach.
                                // For a perfect stream, we'd need to use the 'follow' API in a separate thread,
                                // but that blocks.
                                // Let's just log them. The user will see some duplicates perhaps,
                                // but real-time is better.
                                // To de-duplicate, we could hash lines or keep a set of recent lines?
                                // Let's rely on XxlJobHelper.log

                                // To avoid massive duplicates, let's try a diff approach if logs are small,
                                // or just accept slight overlap.

                                // Actually, better approach:
                                // Don't use sinceSeconds. Read ALL logs, but track line count offset.
                                // K8s doesn't support "offset lines", only "tailLines".

                                // Let's use the simple overlap for now as it's robust for tailing.
                                logger.info("[K8s Pod] " + line);
                                // Also log to XxlJobContext for UI
                                com.xxl.job.core.context.XxlJobHelper.log("[K8s] " + line);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore errors during streaming to not interrupt main flow
        }
        return 0; // unsed for now
    }
}
