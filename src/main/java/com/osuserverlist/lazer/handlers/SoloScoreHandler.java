package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.anticheat.ScoreAntiCheat;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.calculators.ScoreSimulator;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.ScoreRecord;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SoloScoreHandler {
    private static final Logger logger = LoggerFactory.getLogger(SoloScoreHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public static class ScoreTokenInfo {
        public final long id;
        public final int userId;
        public final int beatmapId;
        public final int rulesetId;
        public final String beatmapHash;
        public final long createdAtEpochMs;

        public ScoreTokenInfo(long id, int userId, int beatmapId, int rulesetId, String beatmapHash) {
            this.id = id;
            this.userId = userId;
            this.beatmapId = beatmapId;
            this.rulesetId = rulesetId;
            this.beatmapHash = beatmapHash;
            this.createdAtEpochMs = System.currentTimeMillis();
        }
    }

    private final Map<Long, ScoreTokenInfo> scoreTokens = new ConcurrentHashMap<>();

    public SoloScoreHandler(AuthService authService, DatabaseManager databaseManager, ServerConfig config) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.config = config;
    }

    private User authenticate(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        TokenStore.TokenData tokenData = authService.resolveToken(token);
        if (tokenData == null) {
            return null;
        }
        return databaseManager.findUserById(tokenData.userId);
    }

    public void handleCreateScoreToken(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        int beatmapId;
        try {
            beatmapId = Integer.parseInt(ctx.pathParam("beatmap_id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid beatmap_id"));
            return;
        }

        int rulesetId = 0;
        String rulesetParam = ctx.formParam("ruleset_id");
        if (rulesetParam == null || rulesetParam.isBlank()) {
            rulesetParam = ctx.queryParam("ruleset_id");
        }
        if (rulesetParam != null && !rulesetParam.isBlank()) {
            try {
                rulesetId = Integer.parseInt(rulesetParam);
            } catch (NumberFormatException ignored) {
                rulesetId = UserResponseBuilder.parseMode(rulesetParam);
            }
        }

        String beatmapHash = ctx.formParam("beatmap_hash");
        if (beatmapHash == null || beatmapHash.isBlank()) {
            beatmapHash = ctx.queryParam("beatmap_hash");
        }

        // Clean up expired tokens (> 2 hours old)
        long now = System.currentTimeMillis();
        scoreTokens.entrySet().removeIf(entry -> now - entry.getValue().createdAtEpochMs > 7200000L);

        long tokenId = Math.abs(RANDOM.nextLong() % 9000000000L) + 1000000000L;
        ScoreTokenInfo tokenInfo = new ScoreTokenInfo(tokenId, user.id, beatmapId, rulesetId, beatmapHash);
        scoreTokens.put(tokenId, tokenInfo);

        logger.info("Created score token {} for user {} on beatmap {} (mode: {})", tokenId, user.id, beatmapId, rulesetId);
        ctx.status(200).json(Map.of("id", tokenId));
    }

    @SuppressWarnings("unchecked")
    public void handleSubmitScore(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        int beatmapId;
        long token;
        try {
            beatmapId = Integer.parseInt(ctx.pathParam("beatmap_id"));
            token = Long.parseLong(ctx.pathParam("token"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid path parameters"));
            return;
        }

        ScoreTokenInfo tokenInfo = scoreTokens.remove(token);
        if (tokenInfo == null || tokenInfo.userId != user.id || tokenInfo.beatmapId != beatmapId) {
            logger.warn("Rejecting score submission: token {} invalid or mismatched (expected user {} map {})",
                    token, user.id, beatmapId);
            ctx.status(404).json(Map.of("error", "Score token not found or invalid"));
            return;
        }

        Map<String, Object> body;
        try {
            body = MAPPER.readValue(ctx.body(), Map.class);
        } catch (Exception e) {
            logger.error("Failed to parse score submission JSON: {}", e.getMessage());
            ctx.status(400).json(Map.of("error", "Malformed score submission body"));
            return;
        }

        long totalScore = getLong(body, "total_score", 0L);
        long totalScoreWithoutMods = getLong(body, "total_score_without_mods", totalScore);
        float accuracy = getFloat(body, "accuracy", 1.0f);
        int maxCombo = getInt(body, "max_combo", 0);
        float pp = getFloat(body, "pp", 0.0f);
        String rank = getString(body, "rank", "D");
        boolean passed = getBoolean(body, "passed", true);
        int rulesetId = getInt(body, "ruleset_id", tokenInfo.rulesetId);

        List<Object> modsList = (List<Object>) body.get("mods");
        if (modsList == null) modsList = Collections.emptyList();
        int modsBitmask = UserResponseBuilder.modsToInt(modsList);

        if (!UserResponseBuilder.isScoreRankedForPp(modsList, modsBitmask, rulesetId)) {
            pp = 0.0f;
        }

        Map<String, Object> statistics = (Map<String, Object>) body.get("statistics");
        int n300 = getStat(statistics, "great", "Great", "300");
        int n100 = getStat(statistics, "ok", "Ok", "100");
        int n50 = getStat(statistics, "meh", "Meh", "50");
        int nmiss = getStat(statistics, "miss", "Miss");
        int ngeki = getStat(statistics, "perfect", "Perfect");
        int nkatu = getStat(statistics, "good", "Good");

        BeatmapRecord beatmap = databaseManager.findBeatmapById(beatmapId);
        if (beatmap == null && tokenInfo.beatmapHash != null && !tokenInfo.beatmapHash.isBlank()) {
            beatmap = databaseManager.findBeatmapByMd5(tokenInfo.beatmapHash);
        }

        Map<String, Object> maximumStatistics = (Map<String, Object>) body.get("maximum_statistics");
        if (maximumStatistics == null || maximumStatistics.isEmpty()) {
            maximumStatistics = new LinkedHashMap<>();
            int mapMax = (beatmap != null && beatmap.maxCombo > 0) ? beatmap.maxCombo : (n300 + n100 + n50 + nmiss);
            if (mapMax > 0) {
                maximumStatistics.put("great", mapMax);
            }
        }

        // Object count for classic conversion from maximum_statistics or hit statistics
        int objectCount = 0;
        for (Map.Entry<String, Object> entry : maximumStatistics.entrySet()) {
            if (isBasicJudgement(entry.getKey())) {
                objectCount += ((Number) entry.getValue()).intValue();
            }
        }
        if (objectCount == 0) {
            objectCount = n300 + n100 + n50 + nmiss;
        }

        int timeElapsed = (int) (System.currentTimeMillis() - tokenInfo.createdAtEpochMs);
        if (body.containsKey("time_elapsed")) {
            timeElapsed = getInt(body, "time_elapsed", timeElapsed);
        }

        int effectiveMode = UserResponseBuilder.getEffectiveMode(rulesetId, modsBitmask);

        ScoreAntiCheat.ValidationResult validation = ScoreAntiCheat.validateScore(
                user.id,
                beatmap,
                effectiveMode,
                totalScore,
                totalScoreWithoutMods,
                accuracy,
                maxCombo,
                pp,
                passed,
                modsBitmask,
                n300, n100, n50, nmiss, ngeki, nkatu,
                timeElapsed
        );

        if (!validation.isValid()) {
            logger.warn("Anticheat rejected score submission: user={} ({}) map={} reason={}",
                    user.id, user.name, beatmapId, validation.getReason());
            ctx.status(400).json(Map.of("error", "Score rejected by anticheat: " + validation.getReason()));
            return;
        }

        // Server-side simulation: never trust client accuracy, grade or PP
        ScoreSimulator.SimulationResult sim = ScoreSimulator.simulateAndValidate(
                user.id,
                beatmap,
                effectiveMode,
                totalScore,
                accuracy,
                maxCombo,
                passed,
                modsBitmask,
                n300, n100, n50, nmiss, ngeki, nkatu,
                timeElapsed
        );

        if (!sim.valid) {
            logger.warn("Score rejected after simulation: user={} map={} reason={}",
                    user.id, beatmapId, sim.rejectionReason);
            ctx.status(400).json(Map.of("error", "Score rejected by simulation: " + sim.rejectionReason));
            return;
        }

        accuracy = sim.accuracy;
        rank = sim.grade;
        pp = sim.pp;

        DatabaseManager.ScoreSubmitResult submitResult = databaseManager.submitScore(
                user.id,
                beatmapId,
                effectiveMode,
                totalScore,
                totalScoreWithoutMods,
                accuracy,
                maxCombo,
                pp,
                rank,
                passed,
                modsBitmask,
                n300, n100, n50, nmiss, ngeki, nkatu,
                objectCount,
                timeElapsed,
                tokenInfo.beatmapHash
        );

        if (submitResult == null || submitResult.score == null) {
            ctx.status(500).json(Map.of("error", "Database error while submitting score"));
            return;
        }

        ScoreRecord sc = submitResult.score;

        // Response matches MultiplayerScore model in osu!(lazer)
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", sc.id);

        Map<String, Object> userObj = new LinkedHashMap<>();
        userObj.put("id", user.id);
        userObj.put("username", user.name);
        userObj.put("country_code", user.country != null && !user.country.isBlank() ? user.country.toUpperCase() : "XX");
        userObj.put("avatar_url", UserResponseBuilder.getAvatarUrl(user.id, config));
        userObj.put("cover_url", UserResponseBuilder.getCoverUrl(user.customBanner, config));
        response.put("user", userObj);

        response.put("rank", sc.grade);
        response.put("total_score", sc.lazerScore > 0 ? sc.lazerScore : sc.score);
        response.put("legacy_total_score", sc.score);
        response.put("accuracy", (double) sc.acc);
        response.put("max_combo", sc.maxCombo);
        response.put("mods", modsList);
        response.put("statistics", statistics);
        response.put("maximum_statistics", maximumStatistics);
        response.put("passed", !"F".equalsIgnoreCase(sc.grade));
        response.put("ended_at", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(sc.playTimeEpochSec)));
        response.put("position", submitResult.position);
        response.put("pp", sc.pp > 0 ? (double) sc.pp : null);
        response.put("has_replay", false);
        response.put("ranked", sc.mapStatus > 0);
        response.put("preserve", submitResult.isPersonalBest || sc.pp > 0);
        response.put("processed", true);
        response.put("ruleset_id", sc.mode % 4);
        response.put("beatmap_id", sc.mapId);

        Map<String, Object> bm = new LinkedHashMap<>();
        if (beatmap != null) {
            bm.put("id", beatmap.id);
            bm.put("beatmapset_id", beatmap.setId);
            bm.put("version", beatmap.version);
            bm.put("difficulty_rating", (double) beatmap.diff);
            bm.put("status", BeatmapHandler.statusToString(beatmap.status));
            bm.put("total_length", beatmap.totalLength);
            bm.put("bpm", (double) beatmap.bpm);
            bm.put("cs", (double) beatmap.cs);
            bm.put("ar", (double) beatmap.ar);
            bm.put("drain", (double) beatmap.hp);
            bm.put("accuracy", (double) beatmap.od);
            bm.put("max_combo", beatmap.maxCombo);
            bm.put("checksum", beatmap.md5);
            bm.put("mode_int", beatmap.mode);
        } else {
            bm.put("id", beatmapId);
            bm.put("max_combo", maxCombo);
        }
        response.put("beatmap", bm);

        logger.info("Score {} submitted successfully for user {} on beatmap {}: score={}, pp={}, rank={}",
                sc.id, user.id, beatmapId, sc.score, sc.pp, sc.grade);

        ctx.status(200).json(response);
    }

    public void handleGetBeatmapScores(@NotNull Context ctx) {
        int beatmapId;
        try {
            beatmapId = Integer.parseInt(ctx.pathParam("beatmap_id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid beatmap_id"));
            return;
        }

        String modeParam = ctx.queryParam("mode");
        int mode = 0;
        if (modeParam != null && !modeParam.isBlank()) {
            mode = UserResponseBuilder.parseMode(modeParam);
        }

        int limit = 50;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            try {
                limit = Math.min(Math.max(Integer.parseInt(limitParam), 1), 100);
            } catch (NumberFormatException ignored) {}
        }

        List<ScoreRecord> scores = databaseManager.findBeatmapLeaderboard(beatmapId, mode, limit);
        List<Map<String, Object>> scoreList = new ArrayList<>();
        for (ScoreRecord sc : scores) {
            scoreList.add(UserResponseBuilder.formatScore(sc, config));
        }

        Map<String, Object> userScoreMap = null;
        User user = authenticate(ctx);
        if (user != null) {
            ScoreRecord userSc = databaseManager.findUserBeatmapScore(beatmapId, user.id, mode);
            if (userSc != null) {
                // Find position
                int pos = 1;
                for (int i = 0; i < scores.size(); i++) {
                    if (scores.get(i).id == userSc.id) {
                        pos = i + 1;
                        break;
                    }
                }
                userScoreMap = new LinkedHashMap<>();
                userScoreMap.put("position", pos);
                userScoreMap.put("score", UserResponseBuilder.formatScore(userSc, config));
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scores", scoreList);
        resp.put("score_count", scoreList.size());
        resp.put("user_score", userScoreMap);
        ctx.status(200).json(resp);
    }

    private boolean isBasicJudgement(String key) {
        if (key == null) return false;
        String k = key.toLowerCase();
        return k.equals("great") || k.equals("ok") || k.equals("meh") ||
               k.equals("miss") || k.equals("perfect") || k.equals("good");
    }

    private int getStat(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.parseInt(v.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    private long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val != null) {
            try { return Long.parseLong(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private float getFloat(Map<String, Object> map, String key, float def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.floatValue();
        if (val != null) {
            try { return Float.parseFloat(val.toString()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return def;
    }

    private String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }
}
