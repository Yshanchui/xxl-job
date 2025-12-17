package com.xxl.job.admin.service;

import com.xxl.job.admin.config.OidcProperties;
import com.xxl.job.admin.mapper.XxlJobUserMapper;
import com.xxl.job.admin.model.XxlJobUser;
import com.xxl.sso.core.model.LoginInfo;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.id.UUIDTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * OIDC/OAuth2 Authentication Service
 * Implements standard Authorization Code Flow
 * 
 * @author xxl-job
 */
@Service
public class OidcAuthService {
    private static final Logger logger = LoggerFactory.getLogger(OidcAuthService.class);

    @Resource
    private OidcProperties oidcProperties;

    @Resource
    private XxlJobUserMapper xxlJobUserMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Generate OAuth2 authorization URL with CSRF protection
     * 
     * @param state CSRF protection state parameter
     * @return Authorization URL
     */
    public String generateAuthorizationUrl(String state) {
        if (!oidcProperties.isEnabled()) {
            throw new IllegalStateException("OIDC is not enabled");
        }

        try {
            StringBuilder url = new StringBuilder();
            url.append(oidcProperties.getAuthorizationEndpoint());
            url.append("?response_type=code");
            url.append("&client_id=")
                    .append(URLEncoder.encode(oidcProperties.getClientId(), StandardCharsets.UTF_8.toString()));
            url.append("&redirect_uri=")
                    .append(URLEncoder.encode(oidcProperties.getRedirectUri(), StandardCharsets.UTF_8.toString()));
            url.append("&scope=")
                    .append(URLEncoder.encode(oidcProperties.getScope(), StandardCharsets.UTF_8.toString()));
            url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8.toString()));

            return url.toString();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Failed to encode authorization URL", e);
        }
    }

    /**
     * Generate random state for CSRF protection
     */
    public String generateState() {
        return UUID.randomUUID().toString();
    }

    /**
     * Exchange authorization code for access token and create/update user
     * 
     * @param code          Authorization code from OIDC provider
     * @param state         State parameter for CSRF verification
     * @param expectedState Expected state value stored in session
     * @return LoginInfo for xxl-sso integration
     */
    public Response<LoginInfo> handleCallback(String code, String state, String expectedState) {
        if (!oidcProperties.isEnabled()) {
            return Response.ofFail("OIDC is not enabled");
        }

        // Verify state for CSRF protection
        if (StringTool.isBlank(state) || !state.equals(expectedState)) {
            logger.warn("OIDC callback state mismatch. Expected: {}, Got: {}", expectedState, state);
            return Response.ofFail("Invalid state parameter");
        }

        if (StringTool.isBlank(code)) {
            return Response.ofFail("Authorization code is required");
        }

        try {
            // Exchange code for access token
            Map<String, Object> tokenResponse = exchangeCodeForToken(code);
            if (tokenResponse == null) {
                return Response.ofFail("Failed to exchange authorization code");
            }

            String accessToken = (String) tokenResponse.get("access_token");
            if (StringTool.isBlank(accessToken)) {
                return Response.ofFail("Access token not found in response");
            }

            // Get user info
            Map<String, Object> userInfo = getUserInfo(accessToken);
            if (userInfo == null) {
                return Response.ofFail("Failed to get user information");
            }

            // Map OIDC user to local user
            LoginInfo loginInfo = mapUserInfoToLoginInfo(userInfo);
            if (loginInfo == null) {
                return Response.ofFail("Failed to map user information");
            }

            return Response.ofSuccess(loginInfo);

        } catch (Exception e) {
            logger.error("OIDC authentication failed", e);
            return Response.ofFail("Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Exchange authorization code for access token
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCodeForToken(String code) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // Add Basic Authentication if client credentials are required
            String auth = oidcProperties.getClientId() + ":" + oidcProperties.getClientSecret();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encodedAuth);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("code", code);
            params.add("redirect_uri", oidcProperties.getRedirectUri());
            params.add("client_id", oidcProperties.getClientId());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    oidcProperties.getTokenEndpoint(),
                    HttpMethod.POST,
                    request,
                    Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }

            logger.error("Token exchange failed with status: {}", response.getStatusCode());
            return null;

        } catch (Exception e) {
            logger.error("Failed to exchange code for token", e);
            return null;
        }
    }

    /**
     * Get user information from UserInfo endpoint
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getUserInfo(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    oidcProperties.getUserInfoEndpoint(),
                    HttpMethod.GET,
                    request,
                    Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }

            logger.error("UserInfo request failed with status: {}", response.getStatusCode());
            return null;

        } catch (Exception e) {
            logger.error("Failed to get user info", e);
            return null;
        }
    }

    /**
     * Map OIDC user info to LoginInfo and create/update local user
     */
    private LoginInfo mapUserInfoToLoginInfo(Map<String, Object> userInfo) {
        try {
            // Extract user information from OIDC response
            // Common OIDC claims: sub, preferred_username, email, name
            String oidcUserId = (String) userInfo.get("sub");
            String username = (String) userInfo.getOrDefault("preferred_username", userInfo.get("email"));

            if (StringTool.isBlank(username)) {
                username = oidcUserId; // fallback to sub claim
            }

            // Check if user exists
            XxlJobUser existingUser = xxlJobUserMapper.loadByUserName(username);

            if (existingUser == null) {
                // Create new user
                XxlJobUser newUser = new XxlJobUser();
                newUser.setUsername(username);
                newUser.setPassword(UUIDTool.getSimpleUUID()); // Random password, user won't use it
                newUser.setRole(0); // 0 = normal user
                newUser.setPermission(null); // No specific permissions initially

                xxlJobUserMapper.save(newUser);

                logger.info("Created new user from OIDC: {}", username);

                // Create LoginInfo
                return new LoginInfo(String.valueOf(newUser.getId()), UUIDTool.getSimpleUUID());
            } else {
                // User exists, create LoginInfo
                return new LoginInfo(String.valueOf(existingUser.getId()), UUIDTool.getSimpleUUID());
            }

        } catch (Exception e) {
            logger.error("Failed to map user info to LoginInfo", e);
            return null;
        }
    }

    /**
     * Check if OIDC is enabled
     */
    public boolean isOidcEnabled() {
        return oidcProperties.isEnabled();
    }
}
