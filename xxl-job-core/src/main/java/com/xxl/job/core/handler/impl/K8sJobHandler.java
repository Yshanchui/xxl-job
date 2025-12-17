package com.xxl.job.core.handler.impl;

import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.IJobHandler;
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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes Job Handler
 * 
 * @author xxl-job
 */
public class K8sJobHandler extends IJobHandler {
    private static final Logger logger = LoggerFactory.getLogger(K8sJobHandler.class);

    private final K8sJobConfig config;

    public K8sJobHandler(K8sJobConfig config) {
        this.config = config;
    }

    @Override
    public void execute() throws Exception {
        // Parse job config from parameters
        String jobParam = XxlJobContext.getXxlJobContext().getJobParam();
        K8sJobConfig jobConfig = parseConfig(jobParam);

        if (jobConfig == null) {
            throw new RuntimeException("Invalid K8s job configuration");
        }

        // Initialize K8s client using Service Account
        ApiClient client = Config.defaultClient();
        Configuration.setDefaultApiClient(client);

        AppsV1Api appsApi = new AppsV1Api(client);
        BatchV1Api batchApi = new BatchV1Api(client);
        CoreV1Api coreApi = new CoreV1Api(client);

        try {
            // Get deployment to extract image
            V1Deployment deployment = appsApi.readNamespacedDeployment(
                    jobConfig.getDeployment(),
                    jobConfig.getNamespace(),
                    null);

            if (deployment == null || deployment.getSpec() == null ||
                    deployment.getSpec().getTemplate() == null ||
                    deployment.getSpec().getTemplate().getSpec() == null) {
                throw new RuntimeException("Deployment not found or invalid: " + jobConfig.getDeployment());
            }

            // Extract container spec from deployment
            List<V1Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
            if (containers == null || containers.isEmpty()) {
                throw new RuntimeException("No containers found in deployment");
            }

            V1Container sourceContainer = containers.get(0); // Use first container

            // Create Job
            String jobName = "xxl-job-" + XxlJobContext.getXxlJobContext().getJobId() +
                    "-" + System.currentTimeMillis();

            V1Job job = createJob(jobName, jobConfig, sourceContainer);

            logger.info("Creating K8s Job: {}", jobName);
            V1Job createdJob = batchApi.createNamespacedJob(jobConfig.getNamespace(), job, null, null, null, null);

            // Wait for job completion
            waitForJobCompletion(batchApi, coreApi, jobConfig.getNamespace(), jobName);

        } catch (ApiException e) {
            logger.error("K8s API error: {}", e.getResponseBody(), e);
            throw new RuntimeException("K8s Job execution failed: " + e.getMessage(), e);
        }
    }

    private K8sJobConfig parseConfig(String jobParam) {
        if (StringTool.isBlank(jobParam)) {
            return this.config;
        }

        try {
            return GsonTool.fromJson(jobParam, K8sJobConfig.class);
        } catch (Exception e) {
            logger.error("Failed to parse job config: {}", jobParam, e);
            return null;
        }
    }

    private V1Job createJob(String jobName, K8sJobConfig jobConfig, V1Container sourceContainer) {
        // Create container for job
        V1Container jobContainer = new V1Container()
                .name("job-container")
                .image(sourceContainer.getImage())
                .env(sourceContainer.getEnv());

        // Override command if specified
        if (jobConfig.getCommand() != null && jobConfig.getCommand().length > 0) {
            jobContainer.setCommand(List.of(jobConfig.getCommand()));
        }

        // Set args (support xxl-job parameters)
        if (jobConfig.getArgs() != null && jobConfig.getArgs().length > 0) {
            // Replace xxl-job placeholders
            String[] processedArgs = new String[jobConfig.getArgs().length];
            XxlJobContext context = XxlJobContext.getXxlJobContext();

            for (int i = 0; i < jobConfig.getArgs().length; i++) {
                String arg = jobConfig.getArgs()[i];
                arg = arg.replace("${jobParam}", context.getJobParam() != null ? context.getJobParam() : "");
                arg = arg.replace("${shardIndex}", String.valueOf(context.getShardIndex()));
                arg = arg.replace("${shardTotal}", String.valueOf(context.getShardTotal()));
                processedArgs[i] = arg;
            }

            jobContainer.setArgs(List.of(processedArgs));
        }

        // Create Pod spec
        V1PodSpec podSpec = new V1PodSpec()
                .containers(Collections.singletonList(jobContainer))
                .restartPolicy("Never");

        // Create Job spec
        V1JobSpec jobSpec = new V1JobSpec()
                .template(new V1PodTemplateSpec()
                        .metadata(new V1ObjectMeta()
                                .labels(Collections.singletonMap("xxl-job", "true")))
                        .spec(podSpec))
                .backoffLimit(jobConfig.getBackoffLimit() != null ? jobConfig.getBackoffLimit() : 3)
                .ttlSecondsAfterFinished(
                        jobConfig.getTtlSecondsAfterFinished() != null ? jobConfig.getTtlSecondsAfterFinished() : 3600);

        // Create Job
        return new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(new V1ObjectMeta()
                        .name(jobName)
                        .namespace(jobConfig.getNamespace()))
                .spec(jobSpec);
    }

    private void waitForJobCompletion(BatchV1Api batchApi, CoreV1Api coreApi,
            String namespace, String jobName) throws ApiException, IOException {
        int maxWaitMinutes = 30;
        int checkIntervalSeconds = 5;
        int maxChecks = (maxWaitMinutes * 60) / checkIntervalSeconds;

        for (int i = 0; i < maxChecks; i++) {
            V1Job job = batchApi.readNamespacedJobStatus(jobName, namespace, null);

            if (job.getStatus() != null) {
                Integer succeeded = job.getStatus().getSucceeded();
                Integer failed = job.getStatus().getFailed();

                if (succeeded != null && succeeded > 0) {
                    logger.info("Job completed successfully");
                    // Get pod logs
                    getPodLogs(coreApi, namespace, jobName);
                    return;
                }

                if (failed != null && failed > 0) {
                    // Get pod logs before failing
                    getPodLogs(coreApi, namespace, jobName);
                    throw new RuntimeException("Job failed");
                }
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

    private void getPodLogs(CoreV1Api coreApi, String namespace, String jobName) {
        try {
            // Find pod by job name
            V1PodList pods = coreApi.listNamespacedPod(
                    namespace,
                    null, // pretty
                    null, // allowWatchBookmarks
                    null, // _continue
                    null, // fieldSelector
                    "job-name=" + jobName, // labelSelector
                    null, // limit
                    null, // resourceVersion
                    null, // resourceVersionMatch
                    null, // sendInitialEvents
                    null, // timeoutSeconds
                    null // watch
            );

            if (pods.getItems() != null && !pods.getItems().isEmpty()) {
                V1Pod pod = pods.getItems().get(0);
                String podName = pod.getMetadata().getName();

                logger.info("Fetching logs from pod: {}", podName);

                // Get pod logs
                String logs = coreApi.readNamespacedPodLog(
                        podName,
                        namespace,
                        null, // container
                        null, // follow
                        null, // insecureSkipTLSVerifyBackend
                        null, // limitBytes
                        null, // pretty
                        null, // previous
                        null, // sinceSeconds
                        null, // tailLines
                        null // timestamps
                );

                // Write logs to xxl-job log
                if (!StringTool.isBlank(logs)) {
                    for (String line : logs.split("\n")) {
                        logger.info("[K8s Pod] {}", line);
                    }
                }
            }
        } catch (ApiException e) {
            logger.warn("Failed to fetch pod logs: {}", e.getMessage());
        }
    }
}
