package com.xxl.job.admin.scheduler.alarm.impl;

import com.xxl.job.admin.config.WebhookProperties;
import com.xxl.job.admin.model.XxlJobInfo;
import com.xxl.job.admin.model.XxlJobLog;
import com.xxl.job.admin.scheduler.alarm.JobAlarm;
import com.xxl.tool.core.StringTool;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Webhook Job Alarm
 * Sends standard JSON formatted alarm messages via HTTP POST
 * 
 * @author xxl-job
 */
@Component
public class WebhookJobAlarm implements JobAlarm {
    private static final Logger logger = LoggerFactory.getLogger(WebhookJobAlarm.class);

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));

    @Resource
    private WebhookProperties webhookProperties;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean doAlarm(XxlJobInfo info, XxlJobLog jobLog) {
        if (!webhookProperties.isEnabled()) {
            return true; // Webhook disabled, consider success
        }

        if (StringTool.isBlank(webhookProperties.getUrls())) {
            logger.warn("Webhook URLs not configured");
            return false;
        }

        // Build alarm message
        Map<String, Object> alarmMessage = buildAlarmMessage(info, jobLog);

        // Send to all configured webhook URLs
        String[] urls = webhookProperties.getUrls().split(",");
        boolean allSuccess = true;

        for (String url : urls) {
            url = url.trim();
            if (StringTool.isBlank(url)) {
                continue;
            }

            boolean success = sendWebhook(url, alarmMessage);
            if (!success) {
                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /**
     * Build standard JSON alarm message
     */
    private Map<String, Object> buildAlarmMessage(XxlJobInfo info, XxlJobLog jobLog) {
        Map<String, Object> message = new HashMap<>();

        // Alarm type
        message.put("alarmType", "JOB_FAIL");
        message.put("timestamp", ISO_FORMATTER.format(Instant.now()));

        // Job info
        Map<String, Object> jobInfo = new HashMap<>();
        jobInfo.put("jobId", info.getId());
        jobInfo.put("jobDesc", info.getJobDesc());
        jobInfo.put("jobGroup", info.getJobGroup());
        jobInfo.put("author", info.getAuthor());
        jobInfo.put("alarmEmail", info.getAlarmEmail());
        message.put("jobInfo", jobInfo);

        // Log info
        Map<String, Object> logInfo = new HashMap<>();
        logInfo.put("logId", jobLog.getId());
        logInfo.put("executorAddress", jobLog.getExecutorAddress());
        logInfo.put("executorHandler", jobLog.getExecutorHandler());
        logInfo.put("executorParam", jobLog.getExecutorParam());

        if (jobLog.getTriggerTime() != null) {
            logInfo.put("triggerTime", ISO_FORMATTER.format(jobLog.getTriggerTime().toInstant()));
        }
        logInfo.put("triggerCode", jobLog.getTriggerCode());
        logInfo.put("triggerMsg", jobLog.getTriggerMsg());

        if (jobLog.getHandleTime() != null) {
            logInfo.put("handleTime", ISO_FORMATTER.format(jobLog.getHandleTime().toInstant()));
        }
        logInfo.put("handleCode", jobLog.getHandleCode());
        logInfo.put("handleMsg", jobLog.getHandleMsg());

        message.put("logInfo", logInfo);

        return message;
    }

    /**
     * Send webhook with retry logic
     */
    private boolean sendWebhook(String url, Map<String, Object> message) {
        int maxAttempts = webhookProperties.getRetry().isEnabled() ? webhookProperties.getRetry().getMaxAttempts() : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("Webhook sent successfully to: {}", url);
                    return true;
                }

                logger.warn("Webhook failed with status {}: {}", response.getStatusCode(), url);

            } catch (Exception e) {
                logger.error("Webhook send failed (attempt {}/{}): {}", attempt, maxAttempts, url, e);

                // Retry with delay
                if (attempt < maxAttempts && webhookProperties.getRetry().isEnabled()) {
                    try {
                        Thread.sleep(webhookProperties.getRetry().getDelay());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        return false;
    }
}
