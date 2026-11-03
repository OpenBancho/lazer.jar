package com.osuserverlist.lazer.multiplayer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.MultiplayerRoomData.Room;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Daily challenge: staff schedule one beatmap per UTC day (table {@code daily_challenges}); a playlist room of
 * category {@code daily_challenge} is created for it, valid until two minutes before midnight UTC.
 * The room is what the lazer client shows in its daily challenge panel.
 */
public class DailyChallengeService {
    private static final Logger logger = LoggerFactory.getLogger(DailyChallengeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STAFF_MASK = (1 << 12) | (1 << 13) | (1 << 14);

    private final DatabaseManager db;
    private final List<String> staffKeys;
    private final MultiplayerManager multiplayerManager;
    private final AuthService authService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daily-challenge");
        t.setDaemon(true);
        return t;
    });

    private volatile LocalDate roomDay = null;

    /** Whoever wants to hear which room is today's challenge (the metadata hub, which tells the game clients). */
    private volatile java.util.function.Consumer<Long> listener;
    /** The room last passed to the listener, so it is only told about changes. */
    private volatile Long published;
    private volatile boolean publishedOnce = false;
    private volatile long roomId = -1;

    public DailyChallengeService(DatabaseManager db, MultiplayerManager multiplayerManager, AuthService authService,
                                 com.osuserverlist.lazer.config.ServerConfig config) {
        this.staffKeys = java.util.Arrays.stream(config.staffApiKeys.split(","))
                .map(String::trim).filter(k -> k.length() >= 24).toList();
        this.db = db;
        this.multiplayerManager = multiplayerManager;
        this.authService = authService;
        createTable();
        multiplayerManager.setScorePersister(this::persistScore);
    }

    public void setListener(java.util.function.Consumer<Long> listener) {
        this.listener = listener;
    }

    /** Tells the listener which room is live now (null: none), but only when that is different from last time. */
    private synchronized void publish() {
        Room live = roomId >= 0 ? multiplayerManager.getRoom(roomId) : null;
        Long target = (live != null && !live.isEnded()) ? Long.valueOf(roomId) : null;

        if (publishedOnce && java.util.Objects.equals(target, published)) return;

        publishedOnce = true;
        published = target;

        java.util.function.Consumer<Long> l = listener;

        if (l != null) {
            l.accept(target);
        }
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ensureTodayRoom();
            } catch (Exception e) {
                logger.warn("Daily challenge check failed: {}", e.getMessage());
            }
        }, 2, 300, TimeUnit.SECONDS);
    }

    private void createScoreTable() {
        // One row per score submitted to a daily challenge room, as the client was shown it. A room (and its in-memory leaderboard) is gone
        // after a restart, or when the day's challenge is replaced; the day's scores are not.
        String sql = "CREATE TABLE IF NOT EXISTS daily_challenge_scores (score_id BIGINT NOT NULL PRIMARY KEY, day DATE NOT NULL, "
                + "beatmap_id INT NOT NULL, user_id INT NOT NULL, total_score BIGINT NOT NULL, body MEDIUMTEXT NOT NULL, "
                + "INDEX idx_daily_scores_day (day, beatmap_id)) DEFAULT CHARSET=utf8mb4";
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create daily_challenge_scores table: {}", e.getMessage());
        }
    }

    private void persistScore(Room room, long playlistItemId, Map<String, Object> scoreMap, int userId) {
        long scoreId = scoreMap.get("id") instanceof Number n ? n.longValue() : 0;
        int beatmapId = scoreMap.get("beatmap_id") instanceof Number n ? n.intValue() : 0;
        long totalScore = scoreMap.get("total_score") instanceof Number n ? n.longValue() : 0;
        LocalDate day = java.time.Instant.ofEpochMilli(room.startsAt).atZone(ZoneOffset.UTC).toLocalDate();

        if (scoreId <= 0 || beatmapId <= 0) return;

        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("REPLACE INTO daily_challenge_scores (score_id, day, beatmap_id, user_id, total_score, body) VALUES (?,?,?,?,?,?)")) {
            st.setLong(1, scoreId);
            st.setDate(2, Date.valueOf(day));
            st.setInt(3, beatmapId);
            st.setInt(4, userId);
            st.setLong(5, totalScore);
            st.setString(6, MAPPER.writeValueAsString(scoreMap));
            st.executeUpdate();
        } catch (Exception e) {
            logger.warn("Could not store daily challenge score {}: {}", scoreId, e.getMessage());
        }
    }

    /** Puts the day's earlier scores on the beatmap of the challenge back into a freshly created room. */
    @SuppressWarnings("unchecked")
    private void restoreScores(Room room, LocalDate day, int beatmapId) {
        if (room.playlist.isEmpty()) return;

        long itemId = room.playlist.get(0).id;
        int restored = 0;

        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT user_id, body FROM daily_challenge_scores WHERE day = ? AND beatmap_id = ? ORDER BY score_id")) {
            st.setDate(1, Date.valueOf(day));
            st.setInt(2, beatmapId);

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> score = MAPPER.readValue(rs.getString("body"), Map.class);

                    // The room and its playlist item are new; the score is the same.
                    score.put("room_id", room.roomId);
                    score.put("playlist_item_id", itemId);

                    multiplayerManager.restoreScore(room.roomId, itemId, score, rs.getInt("user_id"),
                            score.get("total_score") instanceof Number n ? n.longValue() : 0,
                            score.get("accuracy") instanceof Number n ? n.doubleValue() : 0,
                            score.get("max_combo") instanceof Number n ? n.intValue() : 0,
                            score.get("pp") instanceof Number n ? n.floatValue() : 0f);
                    restored++;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not restore the daily challenge scores of {}: {}", day, e.getMessage());
        }

        if (restored > 0) {
            logger.info("Restored {} score(s) of {} into daily challenge room {}", restored, day, room.roomId);
        }
    }

    private void createTable() {
        createScoreTable();
        String sql = "CREATE TABLE IF NOT EXISTS daily_challenges (day DATE NOT NULL PRIMARY KEY, beatmap_id INT NOT NULL, "
                + "ruleset_id INT NOT NULL DEFAULT 0, required_mods TEXT NULL, allowed_mods TEXT NULL) DEFAULT CHARSET=utf8mb4";
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create daily_challenges table: {}", e.getMessage());
        }
    }

    private record Entry(LocalDate day, int beatmapId, int rulesetId, List<Map<String, Object>> required, List<Map<String, Object>> allowed) {}

    private Entry load(LocalDate day) {
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT * FROM daily_challenges WHERE day = ?")) {
            st.setDate(1, Date.valueOf(day));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return null;
                return new Entry(day, rs.getInt("beatmap_id"), rs.getInt("ruleset_id"),
                        parseMods(rs.getString("required_mods")), parseMods(rs.getString("allowed_mods")));
            }
        } catch (SQLException e) {
            logger.error("Could not load daily challenge for {}: {}", day, e.getMessage());
            return null;
        }
    }

    private static List<Map<String, Object>> parseMods(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Creates today's room if a challenge is scheduled and no live room exists for it yet. */
    public synchronized Room ensureTodayRoom() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(roomDay)) {
            Room existing = multiplayerManager.getRoom(roomId);
            if (existing != null && !existing.isEnded()) return existing;
        }
        Entry entry = load(today);
        if (entry == null) {
            publish();
            return null;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long minutes = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC)).toMinutes() - 2;
        if (minutes < 1) {
            publish();
            return null;
        }

        var item = new com.osuserverlist.lazer.models.MultiplayerRoomData.PlaylistItem();
        item.beatmapId = entry.beatmapId();
        item.rulesetId = entry.rulesetId();
        item.requiredMods.addAll(entry.required());
        item.allowedMods.addAll(entry.allowed());
        var beatmap = db.findBeatmapById(entry.beatmapId());
        if (beatmap != null && beatmap.md5 != null) item.beatmapChecksum = beatmap.md5;

        // Host 0: the room belongs to the server, nobody is auto-joined. matchType 0 = playlist.
        Room room = multiplayerManager.createRoom(0, today.toString(), "", 0, 0, (int) minutes, null, List.of(item), "daily_challenge");
        restoreScores(room, today, entry.beatmapId());
        roomDay = today;
        roomId = room.roomId;
        logger.info("Created daily challenge room {} for {} (beatmap {}, ruleset {})", room.roomId, today, entry.beatmapId(), entry.rulesetId());
        publish();
        return room;
    }

    /** True when the bearer token is one of the configured service keys (the website acting for a staff member). */
    private boolean isServiceKey(String token) {
        byte[] given = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean match = false;

        for (String key : staffKeys) {
            // every key is compared, in constant time, so the answer does not depend on which one matched
            match |= java.security.MessageDigest.isEqual(given, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        return match;
    }

    /** @return the staff user, or a stand-in for a service key; null (answer already sent) when not allowed. */
    private User requireStaff(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return null;
        }
        if (isServiceKey(header.substring(7).trim())) {
            User service = new User();
            service.id = 0;
            service.name = "service";
            service.privileges = STAFF_MASK;
            return service;
        }
        TokenStore.TokenData data = authService.resolveToken(header.substring(7).trim());
        User user = data == null ? null : db.findUserById(data.userId);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return null;
        }
        if ((user.privileges & STAFF_MASK) == 0) {
            ctx.status(403).json(Map.of("error", "Staff only"));
            return null;
        }
        return user;
    }

    /** GET /api/v2/daily-challenge -- today's challenge and its room. */
    public void handleGet(@NotNull Context ctx) {
        Room room = ensureTodayRoom();
        Entry entry = load(LocalDate.now(ZoneOffset.UTC));
        if (entry == null) {
            ctx.status(404).json(Map.of("error", "No daily challenge today"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", entry.day().toString());
        out.put("beatmap_id", entry.beatmapId());
        out.put("ruleset_id", entry.rulesetId());
        out.put("required_mods", entry.required());
        out.put("allowed_mods", entry.allowed());
        out.put("room_id", room != null ? room.roomId : null);
        out.put("ends_at", room != null && room.endsAt != null ? java.time.Instant.ofEpochMilli(room.endsAt).toString() : null);
        ctx.json(out);
    }

    /**
     * PUT /api/v2/daily-challenge (staff) -- schedules a challenge.
     * Body: {"date":"2026-11-04" (default today), "beatmap_id":1, "ruleset_id":0, "required_mods":[], "allowed_mods":[]}
     */
    @SuppressWarnings("unchecked")
    public void handleSet(@NotNull Context ctx) {
        if (requireStaff(ctx) == null) return;
        Map<String, Object> body;
        try {
            body = MAPPER.readValue(ctx.body(), Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Malformed body"));
            return;
        }
        if (!(body.get("beatmap_id") instanceof Number beatmap)) {
            ctx.status(422).json(Map.of("error", "beatmap_id is required"));
            return;
        }
        LocalDate day;
        try {
            day = body.get("date") != null ? LocalDate.parse(body.get("date").toString()) : LocalDate.now(ZoneOffset.UTC);
        } catch (Exception e) {
            ctx.status(422).json(Map.of("error", "date must be YYYY-MM-DD"));
            return;
        }
        int ruleset = body.get("ruleset_id") instanceof Number n ? n.intValue() : 0;
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("REPLACE INTO daily_challenges (day, beatmap_id, ruleset_id, required_mods, allowed_mods) VALUES (?, ?, ?, ?, ?)")) {
            st.setDate(1, Date.valueOf(day));
            st.setInt(2, beatmap.intValue());
            st.setInt(3, ruleset);
            st.setString(4, MAPPER.writeValueAsString(body.getOrDefault("required_mods", List.of())));
            st.setString(5, MAPPER.writeValueAsString(body.getOrDefault("allowed_mods", List.of())));
            st.executeUpdate();
        } catch (Exception e) {
            logger.error("Could not save daily challenge: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
            return;
        }
        if (day.equals(LocalDate.now(ZoneOffset.UTC))) {
            closeTodayRoom();
            ensureTodayRoom();
        }
        ctx.json(Map.of("date", day.toString(), "beatmap_id", beatmap.intValue(), "ruleset_id", ruleset));
    }

    /** Ends the live room of today, so a replaced or removed challenge does not keep running. */
    private synchronized void closeTodayRoom() {
        if (roomId < 0) return;

        Room old = multiplayerManager.getRoom(roomId);

        if (old != null) {
            old.endsAt = System.currentTimeMillis();
            old.state = com.osuserverlist.lazer.models.MultiplayerRoomData.RoomState.CLOSED;
        }

        roomDay = null;
        roomId = -1;
        publish();
    }

    /** DELETE /api/v2/daily-challenge?date=YYYY-MM-DD (staff) */
    public void handleDelete(@NotNull Context ctx) {
        if (requireStaff(ctx) == null) return;

        LocalDate day;
        try {
            day = LocalDate.parse(String.valueOf(ctx.queryParam("date")));
        } catch (Exception e) {
            ctx.status(422).json(Map.of("error", "date must be YYYY-MM-DD"));
            return;
        }

        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("DELETE FROM daily_challenges WHERE day = ?")) {
            st.setDate(1, Date.valueOf(day));
            int removed = st.executeUpdate();

            if (removed > 0 && day.equals(LocalDate.now(ZoneOffset.UTC))) closeTodayRoom();

            ctx.json(Map.of("removed", removed));
        } catch (SQLException e) {
            logger.error("Could not delete daily challenge: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** GET /api/v2/daily-challenge/schedule (staff) -- yesterday, today and everything scheduled after, with map details. */
    public void handleSchedule(@NotNull Context ctx) {
        if (requireStaff(ctx) == null) return;

        List<Map<String, Object>> out = new ArrayList<>();

        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT * FROM daily_challenges WHERE day >= DATE_SUB(CURDATE(), INTERVAL 1 DAY) ORDER BY day LIMIT 120");
             ResultSet rs = st.executeQuery()) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                LocalDate day = rs.getDate("day").toLocalDate();
                int beatmapId = rs.getInt("beatmap_id");

                row.put("date", day.toString());
                row.put("is_today", day.equals(today));
                row.put("beatmap_id", beatmapId);
                row.put("ruleset_id", rs.getInt("ruleset_id"));
                row.put("required_mods", parseMods(rs.getString("required_mods")));
                row.put("allowed_mods", parseMods(rs.getString("allowed_mods")));

                var bm = db.findBeatmapById(beatmapId);

                if (bm != null) {
                    row.put("set_id", bm.setId);
                    row.put("artist", bm.artist);
                    row.put("title", bm.title);
                    row.put("version", bm.version);
                    row.put("creator", bm.creator);
                    row.put("stars", (double) bm.diff);
                    row.put("length", bm.totalLength);
                }

                out.add(row);
            }
        } catch (SQLException e) {
            logger.error("Could not read the daily challenge schedule: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
            return;
        }

        Room live = roomId >= 0 ? multiplayerManager.getRoom(roomId) : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", LocalDate.now(ZoneOffset.UTC).toString());
        body.put("room_live", live != null && !live.isEnded());
        body.put("challenges", out);
        ctx.json(body);
    }
}
