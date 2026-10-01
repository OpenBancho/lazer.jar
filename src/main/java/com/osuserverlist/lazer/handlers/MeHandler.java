package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public class MeHandler implements Handler {
    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public MeHandler(AuthService authService, DatabaseManager databaseManager, ServerConfig config) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            sendUnauthorized(ctx, "Missing or invalid Authorization header.");
            return;
        }

        String token = authHeader.substring(7).trim();
        TokenStore.TokenData tokenData = authService.resolveToken(token);
        if (tokenData == null) {
            sendUnauthorized(ctx, "Invalid or expired access token.");
            return;
        }

        User user = databaseManager.findUserById(tokenData.userId);
        if (user == null) {
            sendUnauthorized(ctx, "User not found.");
            return;
        }

        String rulesetParam = ctx.pathParamMap().get("ruleset");
        int mode = user.preferredMode;
        if (rulesetParam != null && !rulesetParam.isBlank()) {
            mode = UserResponseBuilder.parseMode(rulesetParam);
        }

        UserStatistics stats = databaseManager.findUserStats(user.id, mode);
        if (stats == null) {
            stats = new UserStatistics();
            stats.id = user.id;
            stats.mode = mode;
        }

        int followerCount = databaseManager.findFollowerCount(user.id);
        DatabaseManager.ScoreCounts scoreCounts = databaseManager.findScoreCounts(user.id, mode);
        Map<String, Object> resp = UserResponseBuilder.buildUserResponse(user, stats, mode, config, followerCount, scoreCounts);
        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    private void sendUnauthorized(Context ctx, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "unauthorized");
        error.put("message", message);
        ctx.status(401);
        ctx.contentType("application/json");
        ctx.json(error);
    }
}
