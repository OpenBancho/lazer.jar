package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RelationshipsHandler implements Handler {
    private final AuthService authService;
    private final DatabaseManager databaseManager;

    public RelationshipsHandler(AuthService authService, DatabaseManager databaseManager) {
        this.authService = authService;
        this.databaseManager = databaseManager;
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

        if (ctx.path().endsWith("/friends")) {
            List<Integer> friends = databaseManager.findFriendUserIds(tokenData.userId);
            java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (int id : friends) {
                result.add(Map.of(
                        "target_id", id,
                        "relation_type", "friend",
                        "mutual", false
                ));
            }
            ctx.status(200).json(result);
            return;
        }

        if (ctx.path().endsWith("/blocks")) {
            List<Integer> blocks = databaseManager.findBlockedUserIds(tokenData.userId);
            java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (int id : blocks) {
                result.add(Map.of(
                        "target_id", id,
                        "relation_type", "block",
                        "mutual", false
                ));
            }
            ctx.status(200).json(result);
            return;
        }

        ctx.status(200).json(Collections.emptyList());
    }
}
