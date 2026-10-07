package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.anticheat.ScoreAntiCheat;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.calculators.ScoreSimulator;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.MultiplayerRoomData.*;
import com.osuserverlist.lazer.models.ScoreRecord;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import com.osuserverlist.lazer.multiplayer.MultiplayerManager;
import com.osuserverlist.lazer.online.OnlineManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RoomHandler implements Handler {
    private static final Logger logger = LoggerFactory.getLogger(RoomHandler.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final MultiplayerManager multiplayerManager;
    private final OnlineManager onlineManager;
    private final ServerConfig config;
    private final com.osuserverlist.lazer.signalr.SpectatorHub spectatorHub;

    public RoomHandler(AuthService authService, DatabaseManager databaseManager,
                       MultiplayerManager multiplayerManager, OnlineManager onlineManager, ServerConfig config,
                       com.osuserverlist.lazer.signalr.SpectatorHub spectatorHub) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.multiplayerManager = multiplayerManager;
        this.onlineManager = onlineManager;
        this.config = config;
        this.spectatorHub = spectatorHub;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        handleGetRooms(ctx);
    }

    public void handleGetRooms(@NotNull Context ctx) {
        String mode = ctx.queryParam("mode"); // "open", "ended", "participated", "owned", "all"
        String category = ctx.queryParam("category"); // "realtime", "normal", "daily_challenge", "spotlight"
        String status = ctx.queryParam("status");

        if (mode == null || mode.isBlank()) mode = "open";
        if (category == null || category.isBlank()) category = "normal";

        int userId = resolveUserId(ctx);

        List<Room> rooms = multiplayerManager.getRooms(category, mode, status, userId);
        List<Map<String, Object>> resp = new ArrayList<>();
        for (Room r : rooms) {
            resp.add(formatRoom(r, userId, false));
        }

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleCreateRoom(@NotNull Context ctx) {
        int userId = resolveUserId(ctx);
        if (userId <= 0) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String name = "Multiplayer Room";
        String password = "";
        int matchType = 0; // 0 = Playlists, 1 = HeadToHead
        int queueMode = 0; // 0 = HostOnly
        Integer duration = 30;
        Integer maxAttempts = null;
        boolean autoSkip = false;
        long autoStartDurationMs = 0;
        String category = "normal";
        List<PlaylistItem> playlistItems = new ArrayList<>();

        try {
            JsonNode body = mapper.readTree(ctx.body());
            if (body != null) {
                if (body.has("name")) name = body.get("name").asText();
                if (body.has("password")) password = body.get("password").asText();
                if (body.has("category")) category = body.get("category").asText();
                if (body.has("type")) matchType = parseMatchType(body.get("type").asText());
                if (body.has("queue_mode")) queueMode = parseQueueMode(body.get("queue_mode").asText());
                if (body.has("duration") && !body.get("duration").isNull()) duration = body.get("duration").asInt();
                if (body.has("max_attempts") && !body.get("max_attempts").isNull()) maxAttempts = body.get("max_attempts").asInt();
                if (body.has("auto_skip")) autoSkip = body.get("auto_skip").asBoolean();
                if (body.has("auto_start_duration")) autoStartDurationMs = body.get("auto_start_duration").asLong() * 1000L;

                JsonNode playlistNode = body.get("playlist");
                if (playlistNode != null && playlistNode.isArray() && playlistNode.size() > 0) {
                    for (JsonNode itemNode : playlistNode) {
                        PlaylistItem item = new PlaylistItem();
                        if (itemNode.has("beatmap_id")) item.beatmapId = itemNode.get("beatmap_id").asInt();
                        if (itemNode.has("ruleset_id")) item.rulesetId = itemNode.get("ruleset_id").asInt();
                        if (itemNode.has("beatmap_checksum")) item.beatmapChecksum = itemNode.get("beatmap_checksum").asText();
                        if (itemNode.has("freestyle")) item.freestyle = itemNode.get("freestyle").asBoolean();
                        if (itemNode.has("required_mods") && itemNode.get("required_mods").isArray()) {
                            for (JsonNode m : itemNode.get("required_mods")) {
                                item.requiredMods.add(mapper.convertValue(m, Map.class));
                            }
                        }
                        if (itemNode.has("allowed_mods") && itemNode.get("allowed_mods").isArray()) {
                            for (JsonNode m : itemNode.get("allowed_mods")) {
                                item.allowedMods.add(mapper.convertValue(m, Map.class));
                            }
                        }
                        playlistItems.add(item);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse create room body: {}", e.getMessage());
        }

        Room room = multiplayerManager.createRoom(userId, name, password, matchType, queueMode,
                duration, maxAttempts, playlistItems, category);
        room.settings.autoSkip = autoSkip;
        room.settings.autoStartDurationMs = autoStartDurationMs;

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(formatRoom(room, userId, true));
    }

    public void handleGetRoom(@NotNull Context ctx) {
        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);
        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        int userId = resolveUserId(ctx);
        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(formatRoom(room, userId, true));
    }

    public void handleDeleteRoom(@NotNull Context ctx) {
        int userId = resolveUserId(ctx);
        if (userId <= 0) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);
        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        if (room.hostUserId != userId) {
            ctx.status(403).json(Map.of("error", "You cannot end this room"));
            return;
        }

        multiplayerManager.closeRoom(roomId, userId);
        ctx.status(200).json(Map.of("success", true));
    }

    public void handleJoinRoom(@NotNull Context ctx) {
        int authUserId = resolveUserId(ctx);
        if (authUserId <= 0) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);

        String password = ctx.queryParam("password");
        if (password == null) {
            try {
                JsonNode body = mapper.readTree(ctx.body());
                if (body != null && body.has("password")) password = body.get("password").asText();
            } catch (Exception ignored) {}
        }

        Room room = multiplayerManager.joinRoom(roomId, authUserId, password);
        if (room == null) {
            ctx.status(403).json(Map.of("error", "Cannot join room (incorrect password or room closed)"));
            return;
        }

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(formatRoom(room, authUserId, true));
    }

    public void handleLeaveRoom(@NotNull Context ctx) {
        int authUserId = resolveUserId(ctx);
        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);

        if (authUserId > 0) {
            multiplayerManager.leaveRoom(roomId, authUserId);
        }
        ctx.status(200).json(Map.of("success", true));
    }

    public void handleGetLeaderboard(@NotNull Context ctx) {
        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);
        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        int currentUserId = resolveUserId(ctx);
        List<RoomUserAttemptStats> list = multiplayerManager.getRoomLeaderboard(roomId);
        List<Map<String, Object>> leaderboardResp = new ArrayList<>();
        Map<String, Object> userScoreResp = null;

        for (int i = 0; i < list.size(); i++) {
            RoomUserAttemptStats stats = list.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", (long) stats.userId);
            entry.put("user_id", stats.userId);
            entry.put("room_id", roomId);
            entry.put("total_score", stats.totalScore);
            entry.put("total_attempts", stats.attempts);
            entry.put("attempts", stats.attempts);
            entry.put("accuracy", stats.totalAccuracy);
            entry.put("max_combo", stats.maxCombo);
            entry.put("pp", (double) stats.totalPp);
            entry.put("position", i + 1);

            User u = databaseManager.findUserById(stats.userId);
            if (u != null) {
                UserStatistics uStats = databaseManager.findUserStats(u.id, u.preferredMode);
                if (uStats == null) {
                    uStats = new UserStatistics();
                    uStats.id = u.id;
                }
                entry.put("user", UserResponseBuilder.buildUserResponse(u, uStats, u.preferredMode, config,
                        databaseManager.findFollowerCount(u.id),
                        databaseManager.findScoreCounts(u.id, u.preferredMode),
                        onlineManager.isUserOnline(u.id)));
            } else {
                entry.put("user", null);
            }

            leaderboardResp.add(entry);
            if (currentUserId > 0 && stats.userId == currentUserId) {
                userScoreResp = entry;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("leaderboard", leaderboardResp);
        resp.put("user_score", userScoreResp);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleGetRoomEvents(@NotNull Context ctx) {
        String idParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(idParam);
        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        int limit = 100;
        String limitParam = ctx.queryParam("limit");
        if (limitParam != null) {
            try { limit = Integer.parseInt(limitParam); } catch (NumberFormatException ignored) {}
        }
        Integer after = null;
        if (ctx.queryParam("after") != null) {
            try { after = Integer.parseInt(ctx.queryParam("after")); } catch (NumberFormatException ignored) {}
        }
        Integer before = null;
        if (ctx.queryParam("before") != null) {
            try { before = Integer.parseInt(ctx.queryParam("before")); } catch (NumberFormatException ignored) {}
        }

        List<RoomEvent> events = multiplayerManager.getRoomEvents(roomId, after, before, limit);
        Set<Integer> userIds = new LinkedHashSet<>();
        Set<Integer> beatmapIds = new LinkedHashSet<>();
        Map<Long, PlaylistItem> playlistItemMap = new LinkedHashMap<>();

        List<Map<String, Object>> eventResps = new ArrayList<>();
        long firstEventId = 0;
        long lastEventId = 0;

        for (RoomEvent ev : events) {
            Map<String, Object> evMap = new LinkedHashMap<>();
            evMap.put("id", ev.id);
            evMap.put("room_id", ev.roomId);
            evMap.put("user_id", ev.userId);
            evMap.put("playlist_item_id", ev.playlistItemId);
            evMap.put("type", ev.type);
            evMap.put("created_at", ISO_FORMATTER.format(Instant.ofEpochMilli(ev.createdAt)));
            evMap.put("details", ev.details);
            eventResps.add(evMap);

            if (ev.userId != null && ev.userId > 0) userIds.add(ev.userId);
            if (ev.playlistItemId != null && ev.playlistItemId > 0) {
                for (PlaylistItem pl : room.playlist) {
                    if (pl.id == ev.playlistItemId) {
                        playlistItemMap.put(pl.id, pl);
                        if (pl.beatmapId > 0) beatmapIds.add(pl.beatmapId);
                        break;
                    }
                }
            }

            if (firstEventId == 0 || ev.id < firstEventId) firstEventId = ev.id;
            if (ev.id > lastEventId) lastEventId = ev.id;
        }

        // Add room host and users
        if (room.hostUserId > 0) userIds.add(room.hostUserId);
        for (int uid : room.recentParticipants) userIds.add(uid);

        List<Map<String, Object>> userResps = new ArrayList<>();
        for (int uid : userIds) {
            User u = databaseManager.findUserById(uid);
            if (u != null) {
                UserStatistics uStats = databaseManager.findUserStats(u.id, u.preferredMode);
                if (uStats == null) {
                    uStats = new UserStatistics();
                    uStats.id = u.id;
                }
                userResps.add(UserResponseBuilder.buildUserResponse(u, uStats, u.preferredMode, config,
                        databaseManager.findFollowerCount(u.id),
                        databaseManager.findScoreCounts(u.id, u.preferredMode),
                        onlineManager.isUserOnline(u.id)));
            }
        }

        List<Map<String, Object>> beatmapResps = new ArrayList<>();
        List<Map<String, Object>> beatmapsetResps = new ArrayList<>();
        Set<Integer> setIds = new HashSet<>();

        for (int bId : beatmapIds) {
            BeatmapRecord br = databaseManager.findBeatmapById(bId);
            if (br != null) {
                beatmapResps.add(formatBeatmap(br));
                if (!setIds.contains(br.setId)) {
                    setIds.add(br.setId);
                    beatmapsetResps.add(formatBeatmapset(br));
                }
            }
        }

        List<Map<String, Object>> playlistItemResps = new ArrayList<>();
        for (PlaylistItem item : (playlistItemMap.isEmpty() ? room.playlist : playlistItemMap.values())) {
            playlistItemResps.add(formatPlaylistItem(item));
        }

        int currentUserId = resolveUserId(ctx);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("beatmaps", beatmapResps);
        resp.put("beatmapsets", beatmapsetResps);
        resp.put("current_playlist_item_id", room.settings.playlistItemId);
        resp.put("events", eventResps);
        resp.put("first_event_id", firstEventId);
        resp.put("last_event_id", lastEventId);
        resp.put("playlist_items", playlistItemResps);
        resp.put("room", formatRoom(room, currentUserId, true));
        resp.put("user", userResps);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleGetPlaylistScores(@NotNull Context ctx) {
        String roomIdParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(roomIdParam);
        String playlistIdParam = ctx.pathParam("playlist_id");
        long playlistItemId = parseLongSafe(playlistIdParam);

        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        int currentUserId = resolveUserId(ctx);
        List<Map<String, Object>> scores = multiplayerManager.getPlaylistScores(roomId, playlistItemId);
        Map<String, Object> userScore = (currentUserId > 0) ? multiplayerManager.getUserPlaylistScore(roomId, playlistItemId, currentUserId) : null;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scores", scores);
        resp.put("total", scores.size());
        resp.put("cursor", null);
        resp.put("params", Map.of("limit", 50, "sort", "score_desc"));
        resp.put("user_score", userScore);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    public void handleCreateScoreToken(@NotNull Context ctx) {
        int userId = resolveUserId(ctx);
        if (userId <= 0) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String roomIdParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(roomIdParam);
        String playlistIdParam = ctx.pathParam("playlist_id");
        long playlistItemId = parseLongSafe(playlistIdParam);

        Room room = multiplayerManager.getRoom(roomId);
        if (room == null) {
            ctx.status(404).json(Map.of("error", "Room not found"));
            return;
        }

        PlaylistItem item = null;
        for (PlaylistItem it : room.playlist) {
            if (it.id == playlistItemId) {
                item = it;
                break;
            }
        }
        if (item == null) {
            item = room.getCurrentPlaylistItem();
        }

        int beatmapId = (item != null && item.beatmapId > 0) ? item.beatmapId : 1;
        int rulesetId = (item != null) ? item.rulesetId : 0;
        String checksum = (item != null && item.beatmapChecksum != null) ? item.beatmapChecksum : "";

        RoomScoreToken token = multiplayerManager.createScoreToken(roomId, playlistItemId, userId, beatmapId, rulesetId, checksum);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", token.id);
        resp.put("token", token.token);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(resp);
    }

    @SuppressWarnings("unchecked")
    public void handleSubmitScore(@NotNull Context ctx) {
        int userId = resolveUserId(ctx);
        if (userId <= 0) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        String roomIdParam = ctx.pathParam("room_id");
        long roomId = parseLongSafe(roomIdParam);
        String playlistIdParam = ctx.pathParam("playlist_id");
        long playlistItemId = parseLongSafe(playlistIdParam);
        String tokenParam = ctx.pathParam("token");
        long tokenId = parseLongSafe(tokenParam);

        RoomScoreToken token = multiplayerManager.consumeScoreToken(tokenId);
        if (token == null || token.userId != userId || token.roomId != roomId) {
            ctx.status(404).json(Map.of("error", "Score token not found or invalid"));
            return;
        }

        User user = databaseManager.findUserById(userId);
        if (user == null) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }

        Map<String, Object> body;
        try {
            body = mapper.readValue(ctx.body(), Map.class);
        } catch (Exception e) {
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
        int rulesetId = getInt(body, "ruleset_id", token.rulesetId);

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

        BeatmapRecord beatmap = databaseManager.findBeatmapById(token.beatmapId);
        if (beatmap == null && token.beatmapHash != null && !token.beatmapHash.isBlank()) {
            beatmap = databaseManager.findBeatmapByMd5(token.beatmapHash);
        }

        Map<String, Object> maximumStatistics = (Map<String, Object>) body.get("maximum_statistics");
        if (maximumStatistics == null || maximumStatistics.isEmpty()) {
            maximumStatistics = new LinkedHashMap<>();
            int mapMax = (beatmap != null && beatmap.maxCombo > 0) ? beatmap.maxCombo : (n300 + n100 + n50 + nmiss);
            if (mapMax > 0) {
                maximumStatistics.put("great", mapMax);
            }
        }

        int objectCount = 0;
        for (Map.Entry<String, Object> entry : maximumStatistics.entrySet()) {
            if (isBasicJudgement(entry.getKey()) && entry.getValue() instanceof Number n) {
                objectCount += n.intValue();
            }
        }
        if (objectCount == 0) {
            objectCount = n300 + n100 + n50 + nmiss;
        }

        int timeElapsed = (int) (System.currentTimeMillis() - token.createdAtEpochMs);
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
            logger.warn("Anticheat rejected multiplayer score: user={} map={} reason={}",
                    user.id, token.beatmapId, validation.getReason());
            ctx.status(400).json(Map.of("error", "Score rejected by anticheat: " + validation.getReason()));
            return;
        }

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

        if (sim.valid) {
            accuracy = sim.accuracy;
            rank = sim.grade;
            pp = sim.pp;
        }

        DatabaseManager.ScoreSubmitResult submitResult = databaseManager.submitScore(
                user.id,
                token.beatmapId,
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
                token.beatmapHash
        );

        long scoreId = (submitResult != null && submitResult.score != null) ? submitResult.score.id : System.currentTimeMillis();

        Map<String, Object> scoreMap = new LinkedHashMap<>();
        scoreMap.put("id", scoreId);

        Map<String, Object> userObj = new LinkedHashMap<>();
        userObj.put("id", user.id);
        userObj.put("username", user.name);
        userObj.put("country_code", user.country != null && !user.country.isBlank() ? user.country.toUpperCase() : "XX");
        userObj.put("avatar_url", UserResponseBuilder.getAvatarUrl(user.id, config));
        userObj.put("cover_url", UserResponseBuilder.getCoverUrl(user.customBanner, config));
        scoreMap.put("user", userObj);

        scoreMap.put("rank", rank);
        scoreMap.put("total_score", totalScore);
        scoreMap.put("legacy_total_score", totalScoreWithoutMods);
        scoreMap.put("accuracy", (double) accuracy);
        scoreMap.put("max_combo", maxCombo);
        scoreMap.put("mods", modsList);
        scoreMap.put("statistics", statistics != null ? statistics : Map.of());
        scoreMap.put("maximum_statistics", maximumStatistics);
        scoreMap.put("passed", passed);
        scoreMap.put("ended_at", ISO_FORMATTER.format(Instant.now()));
        scoreMap.put("position", (submitResult != null) ? submitResult.position : 1);
        scoreMap.put("pp", pp > 0 ? (double) pp : null);
        scoreMap.put("has_replay", false);
        scoreMap.put("ranked", beatmap != null && beatmap.status > 0);
        scoreMap.put("preserve", true);
        scoreMap.put("processed", true);
        scoreMap.put("ruleset_id", rulesetId);
        scoreMap.put("beatmap_id", token.beatmapId);
        scoreMap.put("playlist_item_id", playlistItemId);
        scoreMap.put("room_id", roomId);

        if (beatmap != null) {
            scoreMap.put("beatmap", formatBeatmap(beatmap));
        }

        multiplayerManager.recordScore(roomId, playlistItemId, scoreMap, user.id, totalScore, accuracy, maxCombo, pp);

        if (spectatorHub != null) {
            spectatorHub.notifyScoreProcessed(user.id, scoreId);
        }

        logger.info("Room {} score submitted for user {}: score={}, acc={}, grade={}", roomId, user.id, totalScore, accuracy, rank);

        ctx.status(200);
        ctx.contentType("application/json");
        ctx.json(scoreMap);
    }

    public Map<String, Object> formatRoom(Room room, int currentUserId, boolean includePlaylist) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", room.roomId);
        map.put("name", room.name);
        map.put("channel_id", room.channelId);

        // "realtime" is an internal-only category (it is what the osu!(lazer) multiplayer lounge
        // filters by), but it is NOT part of the public API enum. osu.Game.Online.Rooms.RoomCategory
        // only knows "normal"/"spotlight"/"featured_artist"/"daily_challenge" and is deserialised with
        // SnakeCaseStringEnumConverter, which throws on unknown values. A single unparsable room makes
        // the whole List<Room> response fail, so the lounge listing never reports results and spins forever.
        // osu-web / g0v0-server expose realtime rooms as "normal" for exactly this reason.
        map.put("category", toApiCategory(room.category));

        map.put("type", switch (room.settings.matchType) {
            case 0 -> "playlists";
            case 2 -> "team_versus";
            case 3 -> "matchmaking";
            default -> "head_to_head";
        });

        // Same story as the category: osu.Game.Online.Rooms.RoomStatus only has "idle" and "playing".
        // Whether a room has ended is derived by the client from "ends_at" (Room.HasEnded), never from
        // this field, so closed rooms must still be reported as "idle".
        map.put("status", switch (room.state) {
            case WAITING_FOR_LOAD, PLAYING -> "playing";
            default -> "idle";
        });

        map.put("queue_mode", switch (room.settings.queueMode) {
            case 1 -> "all_players";
            case 2 -> "all_players_round_robin";
            default -> "host_only";
        });

        map.put("auto_skip", room.settings.autoSkip);
        map.put("auto_start_duration", (int) (room.settings.autoStartDurationMs / 1000));
        map.put("has_password", room.settings.password != null && !room.settings.password.isBlank());
        map.put("max_participants", room.settings.maxParticipants);
        map.put("participant_count", room.users.size());
        map.put("duration", room.duration);
        map.put("max_attempts", room.maxAttempts);

        map.put("starts_at", ISO_FORMATTER.format(Instant.ofEpochMilli(room.startsAt)));
        map.put("ends_at", room.endsAt != null ? ISO_FORMATTER.format(Instant.ofEpochMilli(room.endsAt)) : null);

        // Host
        User hostUser = databaseManager.findUserById(room.hostUserId);
        if (hostUser != null) {
            UserStatistics stats = databaseManager.findUserStats(hostUser.id, hostUser.preferredMode);
            if (stats == null) {
                stats = new UserStatistics();
                stats.id = hostUser.id;
            }
            map.put("host", UserResponseBuilder.buildUserResponse(hostUser, stats, hostUser.preferredMode, config,
                    databaseManager.findFollowerCount(hostUser.id),
                    databaseManager.findScoreCounts(hostUser.id, hostUser.preferredMode),
                    onlineManager.isUserOnline(hostUser.id)));
        } else {
            map.put("host", null);
        }

        // Recent participants
        List<Map<String, Object>> participants = new ArrayList<>();
        for (int uid : room.recentParticipants) {
            User u = databaseManager.findUserById(uid);
            if (u != null) {
                UserStatistics stats = databaseManager.findUserStats(u.id, u.preferredMode);
                if (stats == null) {
                    stats = new UserStatistics();
                    stats.id = u.id;
                }
                participants.add(UserResponseBuilder.buildUserResponse(u, stats, u.preferredMode, config,
                        databaseManager.findFollowerCount(u.id),
                        databaseManager.findScoreCounts(u.id, u.preferredMode),
                        onlineManager.isUserOnline(u.id)));
            }
        }
        map.put("recent_participants", participants);

        // Playlist & current playlist item
        if (includePlaylist) {
            List<Map<String, Object>> playlistFormatted = new ArrayList<>();
            for (PlaylistItem item : room.playlist) {
                playlistFormatted.add(formatPlaylistItem(item));
            }
            map.put("playlist", playlistFormatted);
        }

        PlaylistItem cur = room.getCurrentPlaylistItem();
        map.put("current_playlist_item", cur != null ? formatPlaylistItem(cur) : null);

        // Difficulty range & item stats
        map.put("difficulty_range", room.getDifficultyRange());
        map.put("playlist_item_stats", room.getPlaylistItemStats());

        // Current user score / attempts (matching PlaylistAggregateScore in client and g0v0 behavior)
        if (includePlaylist && currentUserId > 0) {
            RoomUserAttemptStats userAttempts = multiplayerManager.getUserRoomAttempts(room.roomId, currentUserId);
            Map<String, Object> uScoreMap = new LinkedHashMap<>();
            List<Map<String, Object>> attemptsList = new ArrayList<>();
            if (userAttempts != null) {
                for (PlaylistItem item : room.playlist) {
                    Map<String, Object> itAttempt = new LinkedHashMap<>();
                    itAttempt.put("id", (int) item.id);
                    itAttempt.put("attempts", userAttempts.attempts);
                    itAttempt.put("passed", true);
                    attemptsList.add(itAttempt);
                }
            }
            uScoreMap.put("playlist_item_attempts", attemptsList);
            map.put("current_user_score", uScoreMap);
        } else {
            map.put("current_user_score", null);
        }

        return map;
    }

    private Map<String, Object> formatPlaylistItem(PlaylistItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id);
        map.put("owner_id", item.ownerId);
        map.put("beatmap_id", item.beatmapId);
        map.put("ruleset_id", item.rulesetId);
        map.put("required_mods", item.requiredMods);
        map.put("allowed_mods", item.allowedMods);
        map.put("expired", item.expired);
        map.put("playlist_order", item.playlistOrder);
        map.put("star_rating", item.starRating);
        map.put("freestyle", item.freestyle);

        BeatmapRecord br = databaseManager.findBeatmapById(item.beatmapId);
        if (br == null && item.beatmapChecksum != null && !item.beatmapChecksum.isBlank()) {
            br = databaseManager.findBeatmapByMd5(item.beatmapChecksum);
        }
        if (br != null) {
            map.put("beatmap", formatBeatmap(br));
            map.put("beatmap_checksum", br.md5 != null ? br.md5 : "");
        } else {
            map.put("beatmap", formatFallbackBeatmap(item));
            map.put("beatmap_checksum", item.beatmapChecksum != null ? item.beatmapChecksum : "");
        }

        return map;
    }

    private Map<String, Object> formatFallbackBeatmap(PlaylistItem item) {
        Map<String, Object> bm = new LinkedHashMap<>();
        int id = item.beatmapId > 0 ? item.beatmapId : 1;
        bm.put("id", id);
        bm.put("beatmapset_id", id);
        bm.put("version", "Normal");
        bm.put("difficulty_rating", item.starRating > 0 ? item.starRating : 1.0);
        bm.put("status", "ranked");
        bm.put("total_length", 120);
        bm.put("bpm", 120.0);
        bm.put("cs", 4.0);
        bm.put("ar", 8.0);
        bm.put("drain", 5.0);
        bm.put("accuracy", 7.0);
        bm.put("max_combo", 500);
        bm.put("checksum", item.beatmapChecksum != null ? item.beatmapChecksum : "");
        bm.put("mode_int", item.rulesetId % 4);

        Map<String, Object> bms = new LinkedHashMap<>();
        bms.put("id", id);
        bms.put("artist", "Unknown Artist");
        bms.put("title", "Multiplayer Map " + id);
        bms.put("creator", "Unknown");
        bms.put("status", "ranked");

        Map<String, Object> covers = new LinkedHashMap<>();
        String baseCover = "https://assets.ppy.sh/beatmaps/" + id + "/covers/";
        covers.put("cover", baseCover + "cover.jpg");
        covers.put("card", baseCover + "card.jpg");
        covers.put("list", baseCover + "list.jpg");
        covers.put("slimcover", baseCover + "slimcover.jpg");
        bms.put("covers", covers);

        bm.put("beatmapset", bms);
        return bm;
    }

    private Map<String, Object> formatBeatmap(BeatmapRecord br) {
        Map<String, Object> bm = new LinkedHashMap<>();
        bm.put("id", br.id);
        bm.put("beatmapset_id", br.setId);
        bm.put("version", br.version);
        bm.put("difficulty_rating", (double) br.diff);
        bm.put("status", BeatmapHandler.statusToString(br.status));
        bm.put("total_length", br.totalLength);
        bm.put("bpm", (double) br.bpm);
        bm.put("cs", (double) br.cs);
        bm.put("ar", (double) br.ar);
        bm.put("drain", (double) br.hp);
        bm.put("accuracy", (double) br.od);
        bm.put("max_combo", br.maxCombo);
        bm.put("checksum", br.md5 != null ? br.md5 : "");
        bm.put("mode_int", br.mode % 4);

        bm.put("beatmapset", formatBeatmapset(br));
        return bm;
    }

    private Map<String, Object> formatBeatmapset(BeatmapRecord br) {
        Map<String, Object> bms = new LinkedHashMap<>();
        bms.put("id", br.setId);
        bms.put("artist", br.artist);
        bms.put("title", br.title);
        bms.put("creator", br.creator);
        bms.put("status", BeatmapHandler.statusToString(br.status));

        Map<String, Object> covers = new LinkedHashMap<>();
        String baseCover = "https://assets.ppy.sh/beatmaps/" + br.setId + "/covers/";
        covers.put("cover", baseCover + "cover.jpg");
        covers.put("card", baseCover + "card.jpg");
        covers.put("list", baseCover + "list.jpg");
        covers.put("slimcover", baseCover + "slimcover.jpg");
        bms.put("covers", covers);
        return bms;
    }

    private int resolveUserId(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            TokenStore.TokenData tokenData = authService.resolveToken(authHeader.substring(7).trim());
            if (tokenData != null) {
                return tokenData.userId;
            }
        }
        return -1;
    }

    private static int parseMatchType(String type) {
        if (type == null) return 1;
        return switch (type.toLowerCase().trim()) {
            case "playlists" -> 0;
            case "team_versus", "teamversus" -> 2;
            case "matchmaking" -> 3;
            default -> 1; // HeadToHead
        };
    }

    private static int parseQueueMode(String qm) {
        if (qm == null) return 0;
        return switch (qm.toLowerCase().trim()) {
            case "all_players", "allplayers" -> 1;
            case "all_players_round_robin", "allplayersroundrobin" -> 2;
            default -> 0; // HostOnly
        };
    }

    /**
     * Mirrors g0v0-server / osu-web: the internal "realtime" category (used by the osu!(lazer)
     * multiplayer lounge for filtering) is never returned through the API as-is, because the client's
     * {@code RoomCategory} enum does not contain it. Any other category is passed through unchanged.
     */
    public static String toApiCategory(String category) {
        if (category == null || category.isBlank()) return "normal";
        return "realtime".equalsIgnoreCase(category.trim()) ? "normal" : category;
    }

    private static long parseLongSafe(String str) {
        if (str == null) return 0;
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long getLong(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return def;
    }

    private static float getFloat(Map<String, Object> map, String key, float def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.floatValue();
        return def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return def;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        if (val != null) return val.toString();
        return def;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean def) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        return def;
    }

    private static int getStat(Map<String, Object> stats, String... keys) {
        if (stats == null) return 0;
        for (String k : keys) {
            Object v = stats.get(k);
            if (v instanceof Number n) return n.intValue();
        }
        return 0;
    }

    private static boolean isBasicJudgement(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("great") || lower.contains("ok") || lower.contains("meh") ||
                lower.contains("perfect") || lower.contains("good") || lower.contains("miss") ||
                lower.contains("300") || lower.contains("100") || lower.contains("50");
    }
}
