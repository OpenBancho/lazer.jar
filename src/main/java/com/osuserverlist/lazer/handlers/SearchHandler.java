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

public class SearchHandler implements Handler {
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;
    private final ServerConfig config;

    public SearchHandler(DatabaseManager databaseManager, OnlineManager onlineManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String query = ctx.queryParam("query");
        if (query == null) {
            query = ctx.queryParam("q");
        }
        if (query == null) {
            query = "";
        }

        int page = 1;
        try {
            String pageStr = ctx.queryParam("page");
            if (pageStr != null && !pageStr.isBlank()) {
                page = Math.max(1, Integer.parseInt(pageStr));
            }
        } catch (NumberFormatException ignored) {}

        int limit = 50;
        int offset = (page - 1) * limit;

        List<User> users = databaseManager.searchUsers(query, limit, offset);
        int total = databaseManager.countSearchUsers(query);

        List<Map<String, Object>> userData = new ArrayList<>();
        for (User user : users) {
            UserStatistics stats = databaseManager.findUserStats(user.id, user.preferredMode);
            if (stats == null) {
                stats = new UserStatistics();
                stats.id = user.id;
                stats.mode = user.preferredMode;
            }
            boolean isOnline = onlineManager.isUserOnline(user.id);
            int followers = databaseManager.findFollowerCount(user.id);
            DatabaseManager.ScoreCounts sc = databaseManager.findScoreCounts(user.id, user.preferredMode);
            userData.add(UserResponseBuilder.buildUserResponse(user, stats, user.preferredMode, config, followers, sc, isOnline));
        }

        Map<String, Object> userWrapper = new LinkedHashMap<>();
        userWrapper.put("data", userData);
        userWrapper.put("total", total);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("user", userWrapper);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }
}
