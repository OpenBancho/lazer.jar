package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RankingsHandler {
    private static final Logger logger = LoggerFactory.getLogger(RankingsHandler.class);
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public RankingsHandler(DatabaseManager databaseManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.config = config;
    }

    public void handleGetRankings(@NotNull Context ctx) {
        String rulesetParam = ctx.pathParam("ruleset");
        String typeParam = ctx.pathParam("type");

        int mode = UserResponseBuilder.parseMode(rulesetParam);

        if ("country".equalsIgnoreCase(typeParam)) {
            handleGetCountryRankings(ctx);
            return;
        }

        if ("spotlight".equalsIgnoreCase(typeParam) || "spotlights".equalsIgnoreCase(typeParam) || "charts".equalsIgnoreCase(typeParam)) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("cursor", null);
            resp.put("total", 0);
            resp.put("ranking", Collections.emptyList());
            resp.put("spotlight", null);
            ctx.status(200).json(resp);
            return;
        }

        int page = 1;
        String pageParam = ctx.queryParam("page");
        if (pageParam != null && !pageParam.isBlank()) {
            try {
                page = Math.max(Integer.parseInt(pageParam.trim()), 1);
            } catch (NumberFormatException ignored) {}
        }

        String countryParam = ctx.queryParam("country");
        int pageSize = 50;

        List<DatabaseManager.RankingUserRecord> records = databaseManager.findRankings(mode, typeParam, countryParam, page, pageSize);
        int total = databaseManager.countRankings(mode, countryParam);

        Map<String, Object> resp = new LinkedHashMap<>();
        if (page * pageSize < total) {
            Map<String, Object> cursor = new LinkedHashMap<>();
            cursor.put("page", page + 1);
            resp.put("cursor", cursor);
        } else {
            resp.put("cursor", null);
        }
        resp.put("total", total);

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            DatabaseManager.RankingUserRecord rec = records.get(i);
            int globalRank = (page - 1) * pageSize + i + 1;
            ranking.add(UserResponseBuilder.formatRankingUser(rec.user, rec.stats, mode, globalRank, config));
        }
        resp.put("ranking", ranking);

        ctx.status(200).json(resp);
    }

    public void handleGetCountryRankings(@NotNull Context ctx) {
        String rulesetParam = ctx.pathParam("ruleset");
        int mode = UserResponseBuilder.parseMode(rulesetParam);

        int page = 1;
        String pageParam = ctx.queryParam("page");
        if (pageParam != null && !pageParam.isBlank()) {
            try {
                page = Math.max(Integer.parseInt(pageParam.trim()), 1);
            } catch (NumberFormatException ignored) {}
        }

        int pageSize = 50;
        List<DatabaseManager.CountryRankingRecord> records = databaseManager.findCountryRankings(mode, page, pageSize);
        int total = databaseManager.countCountryRankings(mode);

        Map<String, Object> resp = new LinkedHashMap<>();
        if (page * pageSize < total) {
            Map<String, Object> cursor = new LinkedHashMap<>();
            cursor.put("page", page + 1);
            resp.put("cursor", cursor);
        } else {
            resp.put("cursor", null);
        }
        resp.put("total", total);

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (DatabaseManager.CountryRankingRecord c : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", c.code);
            item.put("active_users", c.activeUsers);
            item.put("play_count", c.playCount);
            item.put("ranked_score", c.rankedScore);
            item.put("performance", c.performance);
            Map<String, Object> countryObj = new LinkedHashMap<>();
            countryObj.put("code", c.code);
            countryObj.put("name", c.code);
            item.put("country", countryObj);
            ranking.add(item);
        }
        resp.put("ranking", ranking);

        ctx.status(200).json(resp);
    }

    public void handleGetKudosuRankings(@NotNull Context ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cursor", null);
        resp.put("total", 0);
        resp.put("ranking", Collections.emptyList());
        ctx.status(200).json(resp);
    }

    public void handleGetSpotlights(@NotNull Context ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("spotlights", Collections.emptyList());
        ctx.status(200).json(resp);
    }
}
