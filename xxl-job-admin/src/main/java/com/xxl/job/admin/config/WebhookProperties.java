package com.xxl.job.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Webhook Alarm Configuration Properties
 * 
 * @author xxl-job
 */
@Component
@ConfigurationProperties(prefix = "xxl.job.alarm.webhook")
public class WebhookProperties {

    /**
     * Enable webhook alarm
     */
    private boolean enabled = false;

    /**
     * Webhook URLs (comma separated)
     */
    private String urls;

    /**
     * Request timeout in milliseconds
     */
    private int timeout = 5000;

    /**
     * Retry configuration
     */
    private Retry retry = new Retry();

    public static class Retry {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private int delay = 1000; // milliseconds

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getDelay() {
            return delay;
        }

        public void setDelay(int delay) {
            this.delay = delay;
        }
    }

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrls() {
        return urls;
    }

    public void setUrls(String urls) {
        this.urls = urls;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }
}
