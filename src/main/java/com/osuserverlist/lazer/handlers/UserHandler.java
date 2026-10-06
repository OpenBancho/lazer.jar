package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UserHandler implements Handler {
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;
    private final ServerConfig config;

    public UserHandler(DatabaseManager databaseManager, OnlineManager onlineManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String userIdParam = ctx.pathParamMap().get("user_id");
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

        boolean isOnline = onlineManager.isUserOnline(user.id);
        int followerCount = databaseManager.findFollowerCount(user.id);
        DatabaseManager.ScoreCounts scoreCounts = databaseManager.findScoreCounts(user.id, mode);
        Map<String, Object> resp = UserResponseBuilder.buildUserResponse(user, stats, mode, config, followerCount, scoreCounts, isOnline);
        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleLookup(@NotNull Context ctx) {
        List<String> idsParam = ctx.queryParams("ids[]");
        if (idsParam.isEmpty()) {
            idsParam = ctx.queryParams("ids");
        }
        if (idsParam.isEmpty() && ctx.queryParam("ids") != null) {
            idsParam = List.of(ctx.queryParam("ids").split(","));
        }

        int mode = -1;
        String rulesetParam = ctx.queryParam("ruleset_id");
        if (rulesetParam != null && !rulesetParam.isBlank()) {
            try {
                mode = Integer.parseInt(rulesetParam);
            } catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> usersList = new ArrayList<>();
        for (String idStr : idsParam) {
            try {
                int id = Integer.parseInt(idStr.trim());
                User user = databaseManager.findUserById(id);
                if (user != null) {
                    int userMode = (mode >= 0) ? mode : user.preferredMode;
                    UserStatistics stats = databaseManager.findUserStats(user.id, userMode);
                    if (stats == null) {
                        stats = new UserStatistics();
                        stats.id = user.id;
                        stats.mode = userMode;
                    }
                    boolean isOnline = onlineManager.isUserOnline(user.id);
                    int followers = databaseManager.findFollowerCount(user.id);
                    DatabaseManager.ScoreCounts sc = databaseManager.findScoreCounts(user.id, userMode);
                    usersList.add(UserResponseBuilder.buildUserResponse(user, stats, userMode, config, followers, sc, isOnline));
                }
            } catch (NumberFormatException ignored) {}
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("users", usersList);
        ctx.status(200).json(resp);
    }
}
