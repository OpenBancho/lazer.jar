package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives anti-cheat violation flags reported by the client (OpenWatcher) and stores them.
 * POST /api/v2/anticheat/report  {"code": "...", "reason": "...", "client_version": "..."}
 */
public class AnticheatReportHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatReportHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KNOWN_CODES = Set.of("image_tamper", "violation");
    private static final long MIN_INTERVAL_MS = 10_000L;

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final ConcurrentHashMap<Integer, Long> lastReportAt = new ConcurrentHashMap<>();

    public AnticheatReportHandler(AuthService authService, DatabaseManager databaseManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
    }

    public void handleReport(@NotNull Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        TokenStore.TokenData tokenData = authService.resolveToken(authHeader.substring(7).trim());
        User user = tokenData == null ? null : databaseManager.findUserById(tokenData.userId);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastReportAt.put(user.id, now);
        if (previous != null && now - previous < MIN_INTERVAL_MS) {
            ctx.status(429).json(Map.of("error", "Too many reports"));
            return;
        }

        String code;
        String reason;
        String clientVersion;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            code = body.path("code").asText("violation");
            reason = body.path("reason").asText("");
            clientVersion = body.path("client_version").asText("");
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }

        if (!KNOWN_CODES.contains(code)) code = "violation";
        reason = truncate(reason, 1024);
        clientVersion = truncate(clientVersion, 64);

        String realIp = ctx.header("X-Real-IP");
        String ip = truncate(realIp != null && !realIp.isBlank() ? realIp.trim() : ctx.ip(), 45);

        boolean saved = databaseManager.saveAnticheatFlag(user.id, user.name, ip, code, reason, clientVersion);
        logger.warn("ANTICHEAT FLAG [{}] user={} ({}) ip={} client={} reason={}", code, user.name, user.id, ip, clientVersion, reason);

        if (!saved) {
            ctx.status(500).json(Map.of("error", "Failed to store report"));
            return;
        }
        ctx.status(200).json(Map.of("status", "recorded"));
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
