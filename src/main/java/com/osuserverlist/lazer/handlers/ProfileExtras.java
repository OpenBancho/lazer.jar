package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Fills the parts of a full profile response that need extra data: the about-me page, beatmap counters,
 * monthly play counts and the rank history graph. Also serves PUT /users/{id}/page.
 *
 * The rank history lives in bancho.jar's {@code rank_history} table (shared database): bancho.jar snapshots it on a
 * schedule, and a profile build only adds today's row if it is missing, exactly as bancho.jar does.
 */
public class ProfileExtras {
    private static final Logger logger = LoggerFactory.getLogger(ProfileExtras.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGE_LENGTH = 60000;

    private static volatile ProfileExtras instance;

    private final DatabaseManager db;
    private final AuthService authService;
    private BeatmapSocialHandler social;

    public ProfileExtras(DatabaseManager db, AuthService authService) {
        this.db = db;
        this.authService = authService;
        createTables();
        instance = this;
    }

    public void setSocial(BeatmapSocialHandler social) {
        this.social = social;
    }

    /** Null-safe entry point used by the profile handlers. */
    public static void enrich(Map<String, Object> root, User user, UserStatistics stats, int mode) {
        ProfileExtras self = instance;
        if (self == null) return;
        try {
            self.doEnrich(root, user, stats, mode);
        } catch (Exception e) {
            logger.warn("Profile enrichment failed for user {}: {}", user.id, e.getMessage());
        }
    }

    private void createTables() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS user_pages (user_id INT NOT NULL PRIMARY KEY, raw MEDIUMTEXT NOT NULL, "
                        + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4",
                // Same table (and exact schema) bancho.jar's RankSnapshotTask maintains; shared database
                "CREATE TABLE IF NOT EXISTS `rank_history` (`userid` int NOT NULL, `mode` tinyint NOT NULL, `date` date NOT NULL, "
                        + "`rank` int NOT NULL, `pp` int NOT NULL DEFAULT '0', PRIMARY KEY (`userid`, `mode`, `date`), "
                        + "KEY `rank_history_lookup` (`userid`, `mode`, `date`)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4"
        };
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : ddl) st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create profile tables: {}", e.getMessage());
        }
    }

    private int scalar(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void doEnrich(Map<String, Object> root, User user, UserStatistics stats, int mode) throws SQLException {
        try (Connection conn = db.getConnection()) {
            // About-me page
            String raw = null;
            try (PreparedStatement st = conn.prepareStatement("SELECT raw FROM user_pages WHERE user_id = ?")) {
                st.setInt(1, user.id);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) raw = rs.getString(1);
                }
            }
            if (raw != null && !raw.isBlank()) {
                root.put("page", Map.of("html", toHtml(raw), "raw", raw));
            }

            // Counters
            root.put("favourite_beatmapset_count", scalar(conn, "SELECT COUNT(*) FROM favourites WHERE userid = ?", user.id));
            root.put("ranked_beatmapset_count", countSets(conn, user.name, "1, 2"));
            root.put("loved_beatmapset_count", countSets(conn, user.name, "4"));
            root.put("pending_beatmapset_count", countSets(conn, user.name, "0, -1, 3"));
            root.put("graveyard_beatmapset_count", countSets(conn, user.name, "-2"));
            root.put("beatmap_playcounts_count", scalar(conn, "SELECT COUNT(DISTINCT map_md5) FROM scores WHERE userid = ?", user.id));
            root.put("comments_count", scalar(conn, "SELECT COUNT(*) FROM lazer_comments WHERE user_id = ? AND deleted_at IS NULL", user.id));

            // Monthly play counts
            List<Map<String, Object>> monthly = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT DATE_FORMAT(play_time, '%Y-%m-01') AS m, COUNT(*) AS c FROM scores WHERE userid = ? AND mode = ? "
                            + "GROUP BY m ORDER BY m")) {
                st.setInt(1, user.id);
                st.setInt(2, mode);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) monthly.add(Map.of("start_date", rs.getString("m"), "count", rs.getInt("c")));
                }
            }
            root.put("monthly_playcounts", monthly);

            // Rank history: snapshot today's rank, return the last 90 days
            if (stats != null && stats.globalRank != null && stats.globalRank > 0) {
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT IGNORE INTO `rank_history` (`userid`, `mode`, `date`, `rank`, `pp`) VALUES (?, ?, CURDATE(), ?, ?)")) {
                    st.setInt(1, user.id);
                    st.setInt(2, mode);
                    st.setInt(3, stats.globalRank);
                    st.setInt(4, stats.pp);
                    st.executeUpdate();
                }
            }
            List<Integer> ranks = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT `date`, `rank` FROM `rank_history` WHERE `userid` = ? AND `mode` = ? AND `date` >= DATE_SUB(CURDATE(), INTERVAL 89 DAY) ORDER BY `date`")) {
                st.setInt(1, user.id);
                st.setInt(2, mode);
                try (ResultSet rs = st.executeQuery()) {
                    // The graph expects one value per day ending today; pad missing leading days with the first known rank
                    Map<LocalDate, Integer> byDay = new HashMap<>();
                    while (rs.next()) byDay.put(rs.getDate(1).toLocalDate(), rs.getInt(2));
                    if (!byDay.isEmpty()) {
                        LocalDate today = LocalDate.now();
                        Integer last = null;
                        for (int i = 89; i >= 0; i--) {
                            Integer v = byDay.get(today.minusDays(i));
                            if (v != null) last = v;
                            if (last == null) {
                                last = byDay.entrySet().stream().min(Map.Entry.comparingByKey()).get().getValue();
                            }
                            ranks.add(last);
                        }
                    }
                }
            }
            Object rh = root.get("rank_history");
            if (rh instanceof Map<?, ?> m) ((Map<String, Object>) m).put("data", ranks);
        }
    }

    private int countSets(Connection conn, String creator, String statuses) throws SQLException {
        return scalar(conn, "SELECT COUNT(DISTINCT set_id) FROM maps WHERE creator = ? AND status IN (" + statuses + ")", creator);
    }

    /** Minimal safe rendering of the about-me text: escaped, with line breaks and bare links. */
    static String toHtml(String raw) {
        String esc = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        esc = esc.replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\" rel=\"nofollow noopener\">$1</a>");
        return "<div class=\"bbcode\">" + esc.replace("\r\n", "\n").replace("\n", "<br>") + "</div>";
    }

    /** PUT /users/{user_id}/page  body {"body": "..."} (own profile only) */
    public void handlePutPage(@NotNull Context ctx) {
        String header = ctx.header("Authorization");
        TokenStore.TokenData data = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authService.resolveToken(header.substring(7).trim()) : null;
        User me = data == null ? null : db.findUserById(data.userId);
        if (me == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        if (!String.valueOf(me.id).equals(ctx.pathParam("user_id"))) {
            ctx.status(403).json(Map.of("error", "You can only edit your own page"));
            return;
        }
        String raw = null;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            if (body.hasNonNull("body")) raw = body.get("body").asText();
        } catch (Exception ignored) {}
        if (raw == null) raw = ctx.formParam("body");
        if (raw == null || raw.length() > MAX_PAGE_LENGTH) {
            ctx.status(422).json(Map.of("error", "Invalid page body"));
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("INSERT INTO user_pages (user_id, raw) VALUES (?, ?) ON DUPLICATE KEY UPDATE raw = VALUES(raw)")) {
            st.setInt(1, me.id);
            st.setString(2, raw);
            st.executeUpdate();
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
            return;
        }
        ctx.json(Map.of("html", toHtml(raw), "raw", raw));
    }
}
