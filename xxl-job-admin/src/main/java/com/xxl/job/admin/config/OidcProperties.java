package com.xxl.job.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OIDC/OAuth2 Configuration Properties
 * 
 * @author xxl-job
 */
@Component
@ConfigurationProperties(prefix = "xxl.job.oidc")
public class OidcProperties {

    /**
     * Enable OIDC authentication
     */
    private boolean enabled = false;

    /**
     * Authorization endpoint URL
     */
    private String authorizationEndpoint;

    /**
     * Token endpoint URL
     */
    private String tokenEndpoint;

    /**
     * UserInfo endpoint URL
     */
    private String userInfoEndpoint;

    /**
     * OAuth2 Client ID
     */
    private String clientId;

    /**
     * OAuth2 Client Secret
     */
    private String clientSecret;

    /**
     * Redirect URI (callback URL)
     */
    private String redirectUri;

    /**
     * OAuth2 Scope
     */
    private String scope = "openid profile email";

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public void setAuthorizationEndpoint(String authorizationEndpoint) {
        this.authorizationEndpoint = authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getUserInfoEndpoint() {
        return userInfoEndpoint;
    }

    public void setUserInfoEndpoint(String userInfoEndpoint) {
        this.userInfoEndpoint = userInfoEndpoint;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}
