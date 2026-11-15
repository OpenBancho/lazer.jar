package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.anticheat.CommandVerifier;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Set;

/**
 * POST /api/v2/anticheat/command
 *
 * Executes a command of the OpenWatcher anti-cheat server: RESTRICT, UNRESTRICT, FLAG, UNFLAG.
 *
 * The anti-cheat owns the restriction of a player; this server only carries it out. A command is accepted when
 *   - the caller presents one of the anti-cheat's API keys (AC_API_KEYS, same as /flag),
 *   - it carries a valid signature of the anti-cheat (AC_SIGNING_PUBLIC_KEY), which also covers its expiry,
 *   - its command_id has not been executed before (the anti-cheat retries until it gets an answer, so a command can arrive twice).
 *
 * Restricting clears the UNRESTRICT bit of the account (the same bit bancho.jar uses): rankings, chat and spectating of this server
 * already honour it. Like bancho.jar, the player leaves the leaderboards of stable (Redis) and the action is written to the staff log.
 *
 * Answers: 200 executed | duplicate, 400 malformed, 401 key, 403 signature, 404 unknown user, 410 expired, 503 not configured.
 * The anti-cheat treats every answer below 500 (except 429) as final and retries only 5xx.
 */
public class AnticheatCommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatCommandHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> TYPES = Set.of("RESTRICT", "UNRESTRICT", "FLAG", "UNFLAG");

    // bancho.jar keeps the leaderboards of stable here, one sorted set per mode (AdminActions.MODE_COUNT = 9)
    private static final String LEADERBOARD_KEY = "bjar:leaderboard:";
    private static final int MODE_COUNT = 9;

    private final boolean strictClient;
    private final DatabaseManager databaseManager;
    private final AnticheatFlagHandler keys;
    private final CommandVerifier verifier;
    private final JedisPool jedisPool;

    public AnticheatCommandHandler(ServerConfig config, DatabaseManager databaseManager, AnticheatFlagHandler keys, JedisPool jedisPool) {
        this.strictClient = config.strictClient;
        this.databaseManager = databaseManager;
        this.keys = keys;
        this.verifier = new CommandVerifier(config.strictClient ? config.acSigningPublicKey : "");
        this.jedisPool = jedisPool;

        logger.info(verifier.isEnabled()
                ? "Anti-cheat commands (restrict / unrestrict / flag / unflag) are enabled"
                : "AC_SIGNING_PUBLIC_KEY is not set: anti-cheat commands are refused");
    }

    public void handleCommand(@NotNull Context ctx) {
        if (!strictClient || !verifier.isEnabled()) {
            ctx.status(503).json(Map.of("error", "Anti-cheat commands are not configured on this server"));
            return;
        }

        if (!keys.isAuthorized(ctx)) {
            ctx.status(401).json(Map.of("error", "Invalid API key"));
            return;
        }

        JsonNode body;
        try {
            body = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }

        String commandId = body.path("command_id").asText("");
        String type = body.path("type").asText("");
        long userId = body.path("user_id").asLong(0);
        long issuedAt = body.path("issued_at").asLong(0);
        long expiresAt = body.path("expires_at").asLong(0);
        String signature = body.path("signature").asText("");

        // A field the anti-cheat left out was signed as the text "undefined" (a JavaScript template string).
        String reasonCode = text(body, "reason_code");
        String comment = text(body, "comment");
        String liftNotice = text(body, "lift_notice");

        if (commandId.isBlank() || commandId.length() > 64 || !TYPES.contains(type) || userId <= 0 || userId > Integer.MAX_VALUE || signature.isBlank()) {
            ctx.status(400).json(Map.of("error", "command_id, type, user_id and signature are required"));
            return;
        }

        String message = CommandVerifier.canonicalMessage(commandId, type, userId, issuedAt, expiresAt, reasonCode, comment, liftNotice);
        if (!verifier.verify(message, signature)) {
            logger.warn("Refused anti-cheat command {} ({} for user {}): the signature is not valid", commandId, type, userId);
            ctx.status(403).json(Map.of("error", "Invalid signature"));
            return;
        }

        if (expiresAt < System.currentTimeMillis()) {
            ctx.status(410).json(Map.of("error", "The command has expired"));
            return;
        }

        User user = databaseManager.findUserById((int) userId);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "Unknown user"));
            return;
        }

        // Claimed before it is carried out, so a retry that arrives while it runs is not executed twice.
        if (!databaseManager.claimAnticheatCommand(commandId, type, user.id)) {
            ctx.status(200).json(Map.of("status", "duplicate"));
            return;
        }

        try {
            execute(type, user, reasonCode, comment);
        } catch (Exception e) {
            logger.error("Anti-cheat command {} ({} for user {}) failed: {}", commandId, type, user.id, e.toString());
            databaseManager.releaseAnticheatCommand(commandId); // the anti-cheat retries a 500
            ctx.status(500).json(Map.of("error", "The command could not be executed"));
            return;
        }

        logger.info("Anti-cheat command {} executed: {} user {} ({}) by {}", commandId, type, user.id, user.name, body.path("issuer").asText("OpenWatcher"));
        ctx.status(200).json(Map.of("status", "executed"));
    }

    private void execute(String type, User user, String reasonCode, String comment) {
        switch (type) {
            case "RESTRICT" -> {
                if (!databaseManager.setRestricted(user.id, true)) throw new IllegalStateException("the account could not be updated");
                removeFromLeaderboards(user.id);
                databaseManager.writeStaffLog(0, user.id, "restrict", comment);
            }
            case "UNRESTRICT" -> {
                if (!databaseManager.setRestricted(user.id, false)) throw new IllegalStateException("the account could not be updated");
                restoreLeaderboards(user.id);
                databaseManager.writeStaffLog(0, user.id, "unrestrict", comment);
            }
            case "FLAG" -> {
                String summary = comment.isBlank() ? "Flag from the anti-cheat" : comment;
                if (!databaseManager.saveAnticheatFlag(user.id, user.name, truncate(reasonCode, 32), truncate(summary, 1024), "", "ac-command")) {
                    throw new IllegalStateException("the flag could not be stored");
                }
            }
            case "UNFLAG" -> databaseManager.deleteAnticheatFlags(user.id, truncate(reasonCode, 32));
            default -> throw new IllegalArgumentException(type);
        }
    }

    // ---- leaderboards of stable, as bancho.jar's AdminActions does them (a failure here is logged, the account itself is the authority)

    private void removeFromLeaderboards(int userId) {
        if (jedisPool == null) return;

        try (Jedis jedis = jedisPool.getResource()) {
            for (int mode = 0; mode < MODE_COUNT; mode++) {
                jedis.zrem(LEADERBOARD_KEY + mode, String.valueOf(userId));
            }
        } catch (Exception e) {
            logger.warn("Could not remove user {} from the leaderboards in Redis: {}", userId, e.getMessage());
        }
    }

    private void restoreLeaderboards(int userId) {
        if (jedisPool == null) return;

        try (Jedis jedis = jedisPool.getResource()) {
            for (Map.Entry<Integer, Double> entry : databaseManager.findPpByMode(userId).entrySet()) {
                if (entry.getValue() > 0) {
                    jedis.zadd(LEADERBOARD_KEY + entry.getKey(), entry.getValue(), String.valueOf(userId));
                }
            }
        } catch (Exception e) {
            logger.warn("Could not restore user {} on the leaderboards in Redis: {}", userId, e.getMessage());
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? "undefined" : node.asText();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
