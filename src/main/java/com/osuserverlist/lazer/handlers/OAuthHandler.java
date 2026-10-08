package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class OAuthHandler implements Handler {
    private static final Logger logger = LoggerFactory.getLogger(OAuthHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthService authService;
    private final com.osuserverlist.lazer.telemetry.TelemetryManager telemetryManager;

    public OAuthHandler(AuthService authService, com.osuserverlist.lazer.telemetry.TelemetryManager telemetryManager) {
        this.authService = authService;
        this.telemetryManager = telemetryManager;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String grantType = ctx.formParam("grant_type");
        String username = ctx.formParam("username");
        String password = ctx.formParam("password");
        String refreshToken = ctx.formParam("refresh_token");
        String scope = ctx.formParam("scope");
        String fingerprint = ctx.formParam("fingerprint");
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = ctx.formParam("telemetry");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = ctx.header("X-Client-Fingerprint");
        }

        // Fallback to JSON body if not in form params
        if (grantType == null && ctx.body().startsWith("{")) {
            try {
                JsonNode json = MAPPER.readTree(ctx.body());
                if (json.has("grant_type")) grantType = json.get("grant_type").asText();
                if (json.has("username")) username = json.get("username").asText();
                if (json.has("password")) password = json.get("password").asText();
                if (json.has("refresh_token")) refreshToken = json.get("refresh_token").asText();
                if (json.has("scope")) scope = json.get("scope").asText();
                if (json.has("fingerprint") && (fingerprint == null || fingerprint.isBlank())) {
                    fingerprint = json.get("fingerprint").asText();
                }
                if (json.has("telemetry") && (fingerprint == null || fingerprint.isBlank())) {
                    fingerprint = json.get("telemetry").asText();
                }
            } catch (Exception ignored) {}
        }

        if (grantType == null || grantType.isBlank()) {
            grantType = "password";
        }

        String clientIp = ctx.header("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = ctx.ip();
        } else {
            clientIp = clientIp.split(",")[0].trim();
        }

        if ("password".equalsIgnoreCase(grantType)) {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                sendError(ctx, 400, "invalid_request", "Missing username or password.");
                return;
            }

            TokenStore.TokenPair pair = authService.authenticatePassword(username, password, scope);
            if (pair == null) {
                sendError(ctx, 401, "invalid_grant", "Invalid username or password.");
                return;
            }

            if (telemetryManager != null) {
                TokenStore.TokenData tokenData = authService.resolveToken(pair.accessToken);
                if (tokenData != null) {
                    telemetryManager.processTelemetryAsync(tokenData.userId, tokenData.username, clientIp, fingerprint);
                }
            }

            sendSuccess(ctx, pair);
            return;
        }

        if ("refresh_token".equalsIgnoreCase(grantType)) {
            if (refreshToken == null || refreshToken.isBlank()) {
                sendError(ctx, 400, "invalid_request", "Missing refresh_token.");
                return;
            }

            TokenStore.TokenPair pair = authService.authenticateRefresh(refreshToken);
            if (pair == null) {
                sendError(ctx, 401, "invalid_grant", "Invalid or expired refresh token.");
                return;
            }

            if (telemetryManager != null) {
                TokenStore.TokenData tokenData = authService.resolveToken(pair.accessToken);
                if (tokenData != null) {
                    telemetryManager.processTelemetryAsync(tokenData.userId, tokenData.username, clientIp, fingerprint);
                }
            }

            sendSuccess(ctx, pair);
            return;
        }

        sendError(ctx, 400, "unsupported_grant_type", "Grant type '" + grantType + "' is not supported.");
    }

    private void sendSuccess(Context ctx, TokenStore.TokenPair pair) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token_type", "Bearer");
        resp.put("expires_in", pair.expiresIn);
        resp.put("access_token", pair.accessToken);
        resp.put("refresh_token", pair.refreshToken);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    private void sendError(Context ctx, int status, String error, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("error", error);
        resp.put("message", message);
        resp.put("error_description", message);

        ctx.status(status);
        ctx.contentType("application/json");
        ctx.json(resp);
    }
}
