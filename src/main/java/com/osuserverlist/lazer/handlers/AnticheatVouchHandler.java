package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.anticheat.AnticheatVouchClient;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff routes that let the owner of this game server vouch for a player the anti-cheat flagged.
 *
 *   GET  /api/v2/anticheat/vouch/status?user_id=N   what the anti-cheat says (restriction kind, vouched, free slots)
 *   GET  /api/v2/anticheat/vouches                  the players this server vouches for
 *   POST /api/v2/anticheat/vouch         {"user_id": N, "reason": "..."}
 *   POST /api/v2/anticheat/vouch/revoke  {"user_id": N, "reason": "..."}
 *
 * The caller is a staff member of this server: an access token of an account that holds the MODERATOR, ADMINISTRATOR or DEVELOPER
 * privilege (checked against the database, not against the token). A vouch lifts a soft restriction and the pp limit for that player
 * on this server only; a restriction confirmed by OpenWatcher is answered with 409 and cannot be vouched for. The anti-cheat keeps
 * checking the player's game client on every score.
 */
public class AnticheatVouchHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnticheatVouchHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The Bancho privilege bits this ecosystem uses: MODERATOR = 1 << 12, ADMINISTRATOR = 1 << 13, DEVELOPER = 1 << 14.
    private static final int STAFF_MASK = (1 << 12) | (1 << 13) | (1 << 14);

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final AnticheatVouchClient client;

    public AnticheatVouchHandler(AuthService authService, DatabaseManager databaseManager, AnticheatVouchClient client) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.client = client;
    }

    public void handleStatus(@NotNull Context ctx) {
        User staff = requireStaff(ctx);
        if (staff == null) return;

        int userId;
        try {
            userId = Integer.parseInt(ctx.queryParam("user_id"));
        } catch (RuntimeException e) {
            ctx.status(400).json(Map.of("error", "A numeric user_id is required."));
            return;
        }

        if (!client.isConfigured()) {
            ctx.status(200).json(Map.of("configured", false));
            return;
        }

        forward(ctx, client.status(userId));
    }

    public void handleList(@NotNull Context ctx) {
        User staff = requireStaff(ctx);
        if (staff == null) return;

        if (!client.isConfigured()) {
            ctx.status(200).json(Map.of("configured", false, "vouches", List.of()));
            return;
        }

        forward(ctx, client.vouches());
    }

    public void handleVouch(@NotNull Context ctx) {
        handleChange(ctx, true);
    }

    public void handleRevoke(@NotNull Context ctx) {
        handleChange(ctx, false);
    }

    private void handleChange(Context ctx, boolean vouch) {
        User staff = requireStaff(ctx);
        if (staff == null) return;

        int userId;
        String reason;
        String agreement;
        int termsVersion;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            userId = body.path("user_id").asInt(0);
            reason = body.path("reason").asText("").trim();
            // The terms of vouching are the anti-cheat's: the word typed after reading them and the version shown travel with the
            // request, and the anti-cheat refuses (400 agreement_required / 409 terms_changed) a vouch that does not carry them.
            agreement = body.path("agreement").asText("").trim();
            termsVersion = body.path("terms_version").asInt(0);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON"));
            return;
        }

        if (userId <= 0) {
            ctx.status(400).json(Map.of("error", "A numeric user_id is required."));
            return;
        }

        if (vouch && reason.length() < 5) {
            ctx.status(400).json(Map.of("error", "A reason of at least 5 characters is required."));
            return;
        }

        // A staff member cannot vouch for themselves.
        if (vouch && userId == staff.id) {
            ctx.status(400).json(Map.of("error", "You cannot vouch for your own account."));
            return;
        }

        User target = databaseManager.findUserById(userId);
        if (target == null) {
            ctx.status(404).json(Map.of("error", "Unknown user"));
            return;
        }

        AnticheatVouchClient.Result result = vouch
                ? client.vouch(userId, reason, staff.name, agreement, termsVersion)
                : client.revoke(userId, reason.isEmpty() ? "No reason given" : reason, staff.name);

        if (result.ok()) {
            logger.info("ANTICHEAT {} user={} ({}) by={} ({}) reason={}", vouch ? "VOUCH" : "VOUCH REVOKED", target.name, target.id, staff.name, staff.id, reason);
        }

        forward(ctx, result);
    }

    /** The staff member behind the bearer token, or null after answering 401 / 403. */
    private User requireStaff(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            ctx.status(401).json(Map.of("error", "Missing or invalid Authorization header."));
            return null;
        }

        TokenStore.TokenData token = authService.resolveToken(header.substring(7).trim());
        if (token == null) {
            ctx.status(401).json(Map.of("error", "Invalid or expired access token."));
            return null;
        }

        // The privileges are read again from the database: a token issued before a demotion must not keep the access.
        User user = databaseManager.findUserById(token.userId);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "User not found."));
            return null;
        }

        if ((user.privileges & STAFF_MASK) == 0) {
            ctx.status(403).json(Map.of("error", "This is for staff only."));
            return null;
        }

        return user;
    }

    private static void forward(Context ctx, AnticheatVouchClient.Result result) {
        if (result.ok()) {
            ctx.status(200).json(result.body());
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", result.error());
        if (!result.code().isEmpty()) {
            body.put("code", result.code());
        }
        ctx.status(result.status() >= 400 ? result.status() : 502).json(body);
    }
}
