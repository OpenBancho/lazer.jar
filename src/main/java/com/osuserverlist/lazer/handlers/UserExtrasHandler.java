package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Profile pages of /users/{id}: recent activity, beatmapsets by type (favourite, most_played, ranked, loved,
 * pending, graveyard) and passed beatmaps.
 */
public class UserExtrasHandler {
    private static final Logger logger = LoggerFactory.getLogger(UserExtrasHandler.class);

    private final DatabaseManager db;
    private final BeatmapHandler beatmapHandler;
    private final BeatmapSocialHandler social;
    private final ServerConfig config;

    public UserExtrasHandler(DatabaseManager db, BeatmapHandler beatmapHandler, BeatmapSocialHandler social, ServerConfig config) {
        this.db = db;
        this.beatmapHandler = beatmapHandler;
        this.social = social;
        this.config = config;
    }

    private User resolveUser(Context ctx) {
        String key = ctx.pathParam("user_id");
        User user = key.matches("^\\d+$") ? db.findUserById(Integer.parseInt(key)) : db.findUserByName(key.replaceFirst("^@", ""));
        if (user == null) ctx.status(404).json(Map.of("error", "user_not_found"));
        return user;
    }

    private static int intQuery(Context ctx, String name, int def, int max) {
        try {
            String v = ctx.queryParam(name);
            if (v != null) return Math.max(0, Math.min(Integer.parseInt(v.trim()), max));
        } catch (NumberFormatException ignored) {}
        return def;
    }

    private Map<String, Object> setOf(int setId) {
        List<BeatmapRecord> maps = db.findBeatmapsBySetId(setId);
        return maps.isEmpty() ? null : beatmapHandler.formatBeatmapset(setId, maps);
    }

    private List<Integer> queryIds(String sql, Object... args) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            logger.error("User extras query failed: {}", e.getMessage());
        }
        return ids;
    }

    /** GET /users/{user_id}/beatmapsets/{type} */
    public void handleBeatmapsets(@NotNull Context ctx) {
        User user = resolveUser(ctx);
        if (user == null) return;
        String type = ctx.pathParam("type");
        int limit = intQuery(ctx, "limit", 6, 100);
        int offset = intQuery(ctx, "offset", 0, 100000);

        List<Object> out = new ArrayList<>();
        switch (type) {
            case "favourite" -> {
                for (int id : social.favouriteSetIds(user.id, offset, limit)) {
                    Map<String, Object> set = setOf(id);
                    if (set != null) out.add(set);
                }
            }
            case "most_played" -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                String sql = "SELECT m.id, COUNT(*) AS c FROM scores s JOIN maps m ON m.md5 = s.map_md5 WHERE s.userid = ? "
                        + "GROUP BY m.id ORDER BY c DESC LIMIT ? OFFSET ?";
                try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
                    st.setInt(1, user.id);
                    st.setInt(2, limit);
                    st.setInt(3, offset);
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            BeatmapRecord bm = db.findBeatmapById(rs.getInt(1));
                            if (bm == null) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("beatmap_id", bm.id);
                            row.put("count", rs.getInt(2));
                            row.put("beatmap", beatmapHandler.formatSingleBeatmapNoSet(bm));
                            row.put("beatmapset", setOf(bm.setId));
                            rows.add(row);
                        }
                    }
                } catch (SQLException e) {
                    logger.error("most_played failed: {}", e.getMessage());
                }
                out.addAll(rows);
            }
            case "ranked", "loved", "pending", "graveyard" -> {
                String statuses = switch (type) {
                    case "ranked" -> "1, 2";
                    case "loved" -> "4";
                    case "pending" -> "0, -1, 3";
                    default -> "-2";
                };
                List<Integer> ids = queryIds("SELECT set_id FROM maps WHERE creator = ? AND status IN (" + statuses + ") "
                        + "GROUP BY set_id ORDER BY set_id DESC LIMIT ? OFFSET ?", user.name, limit, offset);
                for (int id : ids) {
                    Map<String, Object> set = setOf(id);
                    if (set != null) out.add(set);
                }
            }
            default -> { /* guest, nominated: not tracked */ }
        }
        ctx.json(out);
    }

    /** GET /users/{user_id}/beatmaps-passed?beatmapset_ids[]=..&ruleset_id= */
    public void handleBeatmapsPassed(@NotNull Context ctx) {
        User user = resolveUser(ctx);
        if (user == null) return;
        List<String> raw = new ArrayList<>(ctx.queryParams("beatmapset_ids[]"));
        raw.addAll(ctx.queryParams("beatmapset_ids"));
        if (raw.size() > 50) {
            ctx.status(422).json(Map.of("error", "Too many beatmapset ids (max 50)"));
            return;
        }
        List<Integer> setIds = new ArrayList<>();
        for (String r : raw) {
            try {
                setIds.add(Integer.parseInt(r.trim()));
            } catch (NumberFormatException ignored) {}
        }
        if (setIds.isEmpty()) {
            ctx.json(Map.of("beatmaps_passed", List.of()));
            return;
        }
        String in = String.join(",", Collections.nCopies(setIds.size(), "?"));
        StringBuilder sql = new StringBuilder("SELECT DISTINCT m.id FROM scores s JOIN maps m ON m.md5 = s.map_md5 "
                + "WHERE s.userid = ? AND s.grade != 'F' AND m.set_id IN (" + in + ")");
        List<Object> args = new ArrayList<>();
        args.add(user.id);
        args.addAll(setIds);
        String ruleset = ctx.queryParam("ruleset_id");
        if (ruleset != null && ruleset.matches("\\d+")) {
            sql.append(" AND s.mode % 4 = ?");
            args.add(Integer.parseInt(ruleset) % 4);
        }
        List<Map<String, Object>> beatmaps = new ArrayList<>();
        for (int id : queryIds(sql.toString(), args.toArray())) {
            BeatmapRecord bm = db.findBeatmapById(id);
            if (bm != null) beatmaps.add(beatmapHandler.formatSingleBeatmapNoSet(bm));
        }
        ctx.json(Map.of("beatmaps_passed", beatmaps));
    }

    /** GET /users/{user_id}/recent_activity -- S/SS clears that sit on a leaderboard. */
    public void handleRecentActivity(@NotNull Context ctx) {
        User user = resolveUser(ctx);
        if (user == null) return;
        int limit = intQuery(ctx, "limit", 20, 50);
        int offset = intQuery(ctx, "offset", 0, 10000);
        String sql = """
                SELECT s.id, s.grade, s.mode, s.play_time, m.id AS map_id, m.artist, m.title, m.version,
                       (SELECT COUNT(*) + 1 FROM scores o WHERE o.map_md5 = s.map_md5 AND o.mode = s.mode
                          AND o.status = 2 AND o.grade != 'F' AND (o.pp > s.pp OR (o.pp = s.pp AND o.score > s.score))) AS board_rank
                FROM scores s JOIN maps m ON m.md5 = s.map_md5
                WHERE s.userid = ? AND s.status = 2 AND s.grade IN ('S','SH','X','XH')
                ORDER BY s.play_time DESC LIMIT ? OFFSET ?
                """;
        List<Map<String, Object>> events = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setInt(1, user.id);
            st.setInt(2, limit);
            st.setInt(3, offset);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    Timestamp ts = rs.getTimestamp("play_time");
                    e.put("id", rs.getLong("id"));
                    e.put("type", "rank");
                    e.put("created_at", ts == null ? null : DateTimeFormatter.ISO_INSTANT.format(ts.toInstant()));
                    e.put("scoreRank", rs.getString("grade"));
                    e.put("rank", rs.getInt("board_rank"));
                    e.put("mode", BeatmapHandler.modeToString(rs.getInt("mode") % 4));
                    e.put("beatmap", Map.of(
                            "title", rs.getString("artist") + " - " + rs.getString("title") + " [" + rs.getString("version") + "]",
                            "url", "/beatmaps/" + rs.getInt("map_id")));
                    e.put("user", Map.of("username", user.name, "url", "/users/" + user.id));
                    events.add(e);
                }
            }
        } catch (SQLException e) {
            logger.error("recent_activity failed: {}", e.getMessage());
        }
        ctx.json(events);
    }
}
