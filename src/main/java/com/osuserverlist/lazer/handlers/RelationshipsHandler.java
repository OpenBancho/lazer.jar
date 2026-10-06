package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RelationshipsHandler implements Handler {
    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final OnlineManager onlineManager;
    private final ServerConfig config;

    public RelationshipsHandler(AuthService authService, DatabaseManager databaseManager, OnlineManager onlineManager, ServerConfig config) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.onlineManager = onlineManager;
        this.config = config;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        String authHeader = ctx.header("Authorization");
        TokenStore.TokenData tokenData = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokenData = authService.resolveToken(authHeader.substring(7).trim());
        }

        if (tokenData == null) {
            ctx.status(200).json(Collections.emptyList());
            return;
        }

        onlineManager.markUserActive(tokenData.userId);

        if (ctx.path().endsWith("/friends")) {
            List<Integer> friends = databaseManager.findFriendUserIds(tokenData.userId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int id : friends) {
                User user = databaseManager.findUserById(id);
                Map<String, Object> targetMap = null;
                if (user != null) {
                    UserStatistics stats = databaseManager.findUserStats(user.id, user.preferredMode);
                    if (stats == null) {
                        stats = new UserStatistics();
                        stats.id = user.id;
                        stats.mode = user.preferredMode;
                    }
                    boolean isOnline = onlineManager.isUserOnline(user.id);
                    int followers = databaseManager.findFollowerCount(user.id);
                    DatabaseManager.ScoreCounts sc = databaseManager.findScoreCounts(user.id, user.preferredMode);
                    targetMap = UserResponseBuilder.buildUserResponse(user, stats, user.preferredMode, config, followers, sc, isOnline);
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("target_id", id);
                item.put("relation_type", "friend");
                item.put("mutual", false);
                item.put("target", targetMap);
                result.add(item);
            }
            ctx.status(200).json(result);
            return;
        }

        if (ctx.path().endsWith("/blocks")) {
            List<Integer> blocks = databaseManager.findBlockedUserIds(tokenData.userId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (int id : blocks) {
                User user = databaseManager.findUserById(id);
                Map<String, Object> targetMap = null;
                if (user != null) {
                    UserStatistics stats = databaseManager.findUserStats(user.id, user.preferredMode);
                    if (stats == null) {
                        stats = new UserStatistics();
                        stats.id = user.id;
                        stats.mode = user.preferredMode;
                    }
                    boolean isOnline = onlineManager.isUserOnline(user.id);
                    int followers = databaseManager.findFollowerCount(user.id);
                    DatabaseManager.ScoreCounts sc = databaseManager.findScoreCounts(user.id, user.preferredMode);
                    targetMap = UserResponseBuilder.buildUserResponse(user, stats, user.preferredMode, config, followers, sc, isOnline);
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("target_id", id);
                item.put("relation_type", "block");
                item.put("mutual", false);
                item.put("target", targetMap);
                result.add(item);
            }
            ctx.status(200).json(result);
            return;
        }

        ctx.status(200).json(Collections.emptyList());
    }
}
