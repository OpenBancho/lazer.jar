package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.ScoreRecord;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class UserScoresHandler implements Handler {
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public UserScoresHandler(DatabaseManager databaseManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String userIdParam = ctx.pathParam("user_id");
        if (userIdParam == null || userIdParam.isBlank()) {
            ctx.status(404).json(Map.of("error", "user_not_found"));
            return;
        }

        User user = null;
        if (userIdParam.matches("^\\d+$")) {
            user = databaseManager.findUserById(Integer.parseInt(userIdParam));
        } else {
            user = databaseManager.findUserByName(userIdParam.replaceFirst("^@", ""));
        }

        if (user == null) {
            ctx.status(404).json(Map.of("error", "user_not_found"));
            return;
        }

        String type = ctx.pathParam("type");
        if (type == null || type.isBlank()) {
            type = "best";
        }

        String modeParam = ctx.queryParam("mode");
        int mode = user.preferredMode;
        if (modeParam != null && !modeParam.isBlank()) {
            mode = UserResponseBuilder.parseMode(modeParam);
        }

        int limit = 50;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Integer.parseInt(limitParam.trim());
            } catch (NumberFormatException ignored) {}
        }

        int offset = 0;
        String offsetParam = ctx.queryParam("offset");
        if (offsetParam != null && !offsetParam.isBlank()) {
            try {
                offset = Integer.parseInt(offsetParam.trim());
            } catch (NumberFormatException ignored) {}
        }

        boolean includeFails = Boolean.parseBoolean(ctx.queryParam("include_fails")) || "1".equals(ctx.queryParam("include_fails"));

        List<ScoreRecord> scores = databaseManager.findUserScores(user.id, mode, type, includeFails, limit, offset);
        List<Map<String, Object>> result = scores.stream()
                .map(sc -> UserResponseBuilder.formatScore(sc, config))
                .collect(Collectors.toList());

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(result);
    }
}
