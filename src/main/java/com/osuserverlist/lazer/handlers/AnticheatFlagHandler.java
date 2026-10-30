package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.anticheat.AnticheatFlagWebhook;
import com.osuserverlist.lazer.anticheat.ScoreSignatureTracker;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives anti-cheat flags from the anti-cheat backend (ac.api.sancochat.fun).
 * The game client never talks to this endpoint; callers authenticate with an API key.
 *
 * POST /api/v2/anticheat/flag
 *   X-API-Key: &lt;key&gt;   (or Authorization: Bearer &lt;key&gt;)
 *   {"user_id": 123, "code": "image_tamper", "summary": "...", "hint": "..."}   summary and hint are optional
 */
public class AnticheatFlagHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatFlagHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEDUPE_MS = 60_000L;
    // A player without the anti-cheat would otherwise be flagged on every single score.
    private static final long NO_SIGNATURE_DEDUPE_MS = 10 * 60_000L;

    private record ApiKey(String name, byte[] sha256) {}

    private final DatabaseManager databaseManager;
    private final AnticheatFlagWebhook webhook;
    private final ScoreSignatureTracker signatureTracker;
    private final boolean strictClient;
    private final List<ApiKey> apiKeys = new ArrayList<>();
    private final ConcurrentHashMap<String, Long> lastFlagAt = new ConcurrentHashMap<>();

    public AnticheatFlagHandler(ServerConfig config, DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.strictClient = config.strictClient;
        this.webhook = new AnticheatFlagWebhook(config.acWebhookFlags);
        this.signatureTracker = new ScoreSignatureTracker(
                config.strictClient ? config.acSigningPublicKey : "",
                Math.max(10, config.acSignatureTimeoutSeconds) * 1000L,
                (userId, userName, code, summary, hint) -> raiseFlag(userId, userName, code, summary, hint, "ljar"));

        // AC_API_KEYS = name:key,name2:key2   (a bare value is used as both name and key)
        for (String entry : config.acApiKeys.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            int sep = entry.indexOf(':');
            String name = sep > 0 ? entry.substring(0, sep).trim() : "key" + (apiKeys.size() + 1);
            String key = sep > 0 ? entry.substring(sep + 1).trim() : entry;
            if (key.length() < 24) {
                logger.warn("Ignoring anti-cheat API key '{}': shorter than 24 characters", name);
                continue;
            }
            apiKeys.add(new ApiKey(name, sha256(key)));
        }

        if (apiKeys.isEmpty()) {
            logger.warn("AC_API_KEYS is empty: /api/v2/anticheat/flag is disabled");
        } else {
            logger.info("Anti-cheat flag endpoint enabled for {} API key(s)", apiKeys.size());
        }
    }

    public void handleFlag(@NotNull Context ctx) {
        if (!strictClient) {
            ctx.status(503).json(Map.of("error", "Anti-cheat is not used by this server (CLIENT_MODE=nonstrict)"));
            return;
        }

        if (apiKeys.isEmpty()) {
            ctx.status(503).json(Map.of("error", "Anti-cheat flag endpoint is not configured"));
            return;
        }

        ApiKey caller = authenticate(ctx);
        if (caller == null) {
            ctx.status(401).json(Map.of("error", "Invalid API key"));
            return;
        }

        int userId;
        String code;
        String summary;
        String hint;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            userId = body.path("user_id").asInt(0);
            code = body.path("code").asText("").trim();
            summary = body.path("summary").asText("").trim();
            hint = body.path("hint").asText("").trim();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }

        if (userId <= 0 || code.isEmpty()) {
            ctx.status(400).json(Map.of("error", "user_id and code are required"));
            return;
        }

        User user = databaseManager.findUserById(userId);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "Unknown user"));
            return;
        }

        code = truncate(code, 32);
        if (summary.isEmpty()) summary = describe(code);
        if (hint.isEmpty()) hint = hintFor(code);

        RaiseResult result = raiseFlag(user.id, user.name, code, summary, hint, caller.name());
        switch (result) {
            case RECORDED -> ctx.status(200).json(Map.of("status", "recorded"));
            case DUPLICATE -> ctx.status(200).json(Map.of("status", "duplicate"));
            default -> ctx.status(500).json(Map.of("error", "Failed to store flag"));
        }
    }

    /**
     * POST /api/v2/anticheat/signature
     *   {"token_id": 1234567890, "signature": "<base64 Ed25519 signature>"}
     * Sent by the anti-cheat server once it has validated a score. The signed message is
     * {@link ScoreSignatureTracker#canonicalMessage}.
     */
    public void handleSignature(@NotNull Context ctx) {
        if (!strictClient) {
            ctx.status(503).json(Map.of("error", "Anti-cheat is not used by this server (CLIENT_MODE=nonstrict)"));
            return;
        }

        if (!signatureTracker.isEnabled()) {
            ctx.status(503).json(Map.of("error", "Score signatures are not configured"));
            return;
        }

        if (apiKeys.isEmpty() || authenticate(ctx) == null) {
            ctx.status(401).json(Map.of("error", "Invalid API key"));
            return;
        }

        long tokenId;
        String signature;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            tokenId = body.path("token_id").asLong(0);
            signature = body.path("signature").asText("");
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }

        if (tokenId <= 0 || signature.isBlank()) {
            ctx.status(400).json(Map.of("error", "token_id and signature are required"));
            return;
        }

        var result = signatureTracker.signatureReceived(tokenId, signature);
        ctx.status(200).json(Map.of("status", result.name().toLowerCase()));
    }

    public ScoreSignatureTracker signatureTracker() {
        return signatureTracker;
    }

    private enum RaiseResult { RECORDED, DUPLICATE, FAILED }

    private RaiseResult raiseFlag(int userId, String userName, String code, String summary, String hint, String source) {
        long now = System.currentTimeMillis();
        long window = code.equals("no_signature") ? NO_SIGNATURE_DEDUPE_MS : DEDUPE_MS;
        String dedupeKey = userId + ":" + code;

        Long previous = lastFlagAt.get(dedupeKey);
        if (previous != null && now - previous < window) {
            return RaiseResult.DUPLICATE;
        }
        lastFlagAt.put(dedupeKey, now);

        summary = truncate(summary, 300);
        hint = truncate(hint, 300);

        boolean saved = databaseManager.saveAnticheatFlag(userId, userName, code, summary, hint, source);
        logger.warn("ANTICHEAT FLAG [{}] user={} ({}) via={} summary={}", code, userName, userId, source, summary);

        webhook.flag(userName, summary, hint);
        return saved ? RaiseResult.RECORDED : RaiseResult.FAILED;
    }

    private ApiKey authenticate(Context ctx) {
        String provided = ctx.header("X-API-Key");
        if (provided == null || provided.isBlank()) {
            String auth = ctx.header("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = auth.substring(7).trim();
            }
        }
        if (provided == null || provided.isBlank()) return null;

        byte[] hash = sha256(provided.trim());
        ApiKey match = null;
        for (ApiKey key : apiKeys) {
            // Constant-time comparison; no early exit so timing does not reveal which key matched.
            if (MessageDigest.isEqual(key.sha256(), hash)) match = key;
        }
        return match;
    }

    private static String describe(String code) {
        return switch (code) {
            case "image_tamper" -> "Game runtime memory was patched";
            case "cheat_signature" -> "Cheat memory-scanner signature was triggered";
            case "violation" -> "Anti-cheat violation detected";
            case "no_signature" -> "Score was not confirmed by the anti-cheat server";
            case "bad_signature" -> "Score signature does not match the submitted score";
            default -> "Anti-cheat flag (" + code + ")";
        };
    }

    private static String hintFor(String code) {
        return switch (code) {
            case "image_tamper" -> "Possible internal mod / injected cheat (e.g. Freedom)";
            case "cheat_signature" -> "Freedom-style internal mod";
            case "violation" -> "Debugger, injected assembly, speed hack or synthetic input";
            case "no_signature" -> "Client without anti-cheat, modified or outdated client, or the anti-cheat server is blocked";
            case "bad_signature" -> "Score modified after anti-cheat signing, or a forged signature";
            default -> "-";
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
