package com.xxl.job.core.k8s;

/**
 * Kubernetes Job Configuration
 * 
 * @author xxl-job
 */
public class K8sJobConfig {

    /**
     * Source deployment name to get image from
     */
    private String deployment;

    /**
     * Kubernetes namespace
     */
    private String namespace;

    /**
     * Command to execute (overrides container default command)
     */
    private String[] command;

    /**
     * Arguments for the command
     */
    private String[] args;

    /**
     * TTL seconds after job finished (auto cleanup)
     */
    private Integer ttlSecondsAfterFinished;

    /**
     * Backoff limit (retry count on failure)
     */
    private Integer backoffLimit;

    // Getters and Setters

    public String getDeployment() {
        return deployment;
    }

    public void setDeployment(String deployment) {
        this.deployment = deployment;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String[] getCommand() {
        return command;
    }

    public void setCommand(String[] command) {
        this.command = command;
    }

    public String[] getArgs() {
        return args;
    }

    public void setArgs(String[] args) {
        this.args = args;
    }

    public Integer getTtlSecondsAfterFinished() {
        return ttlSecondsAfterFinished;
    }

    public void setTtlSecondsAfterFinished(Integer ttlSecondsAfterFinished) {
        this.ttlSecondsAfterFinished = ttlSecondsAfterFinished;
    }

    public Integer getBackoffLimit() {
        return backoffLimit;
    }

    public void setBackoffLimit(Integer backoffLimit) {
        this.backoffLimit = backoffLimit;
    }
}
