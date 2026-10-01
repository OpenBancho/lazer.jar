package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class UserHandler implements Handler {
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public UserHandler(DatabaseManager databaseManager, ServerConfig config) {
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
}
