package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Beatmap packs: GET /beatmaps/packs[?type=], GET /beatmaps/packs/{tag}, GET /beatmaps/packs/{tag}/download
 * and the staff-only PUT /beatmaps/packs to create or replace a pack.
 * Categories follow osu-web: standard, featured_artist, tournament, loved, chart, theme, artist.
 */
public class BeatmapPackHandler {
    private static final Logger logger = LoggerFactory.getLogger(BeatmapPackHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STAFF_MASK = (1 << 12) | (1 << 13) | (1 << 14);
    private static final Set<String> TYPES = Set.of("standard", "featured_artist", "tournament", "loved", "chart", "theme", "artist");

    private final AuthService authService;
    private final DatabaseManager db;
    private final BeatmapHandler beatmapHandler;

    public BeatmapPackHandler(AuthService authService, DatabaseManager db, BeatmapHandler beatmapHandler) {
        this.authService = authService;
        this.db = db;
        this.beatmapHandler = beatmapHandler;
        createTables();
    }

    private void createTables() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS beatmap_packs (tag VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(255) NOT NULL, "
                        + "author VARCHAR(64) NOT NULL DEFAULT '', url VARCHAR(512) NOT NULL DEFAULT '', type VARCHAR(32) NOT NULL DEFAULT 'standard', "
                        + "ruleset_id TINYINT NULL, no_diff_reduction TINYINT(1) NOT NULL DEFAULT 0, "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS beatmap_pack_sets (tag VARCHAR(64) NOT NULL, set_id INT NOT NULL, "
                        + "PRIMARY KEY (tag, set_id)) DEFAULT CHARSET=utf8mb4"
        };
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : ddl) st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create beatmap pack tables: {}", e.getMessage());
        }
    }

    private Map<String, Object> packRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("author", rs.getString("author"));
        m.put("date", DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("created_at").toInstant()));
        m.put("name", rs.getString("name"));
        m.put("no_diff_reduction", rs.getBoolean("no_diff_reduction"));
        m.put("ruleset_id", rs.getObject("ruleset_id"));
        m.put("tag", rs.getString("tag"));
        m.put("url", rs.getString("url"));
        return m;
    }

    /** GET /beatmaps/packs */
    public void handleList(@NotNull Context ctx) {
        String type = ctx.queryParam("type");
        String sql = "SELECT * FROM beatmap_packs" + (type != null && TYPES.contains(type) ? " WHERE type = ?" : "") + " ORDER BY created_at DESC, tag DESC LIMIT 100";
        List<Map<String, Object>> packs = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            if (sql.contains("WHERE")) st.setString(1, type);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) packs.add(packRow(rs));
            }
        } catch (SQLException e) {
            logger.error("Pack list failed: {}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("beatmap_packs", packs);
        out.put("cursor_string", null);
        ctx.json(out);
    }

    /** GET /beatmaps/packs/{tag} */
    public void handleGet(@NotNull Context ctx) {
        String tag = ctx.pathParam("tag");
        try (Connection conn = db.getConnection()) {
            Map<String, Object> pack;
            try (PreparedStatement st = conn.prepareStatement("SELECT * FROM beatmap_packs WHERE tag = ?")) {
                st.setString(1, tag);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) {
                        ctx.status(404).json(Map.of("error", "Pack not found"));
                        return;
                    }
                    pack = packRow(rs);
                }
            }
            List<Map<String, Object>> sets = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement("SELECT set_id FROM beatmap_pack_sets WHERE tag = ? ORDER BY set_id")) {
                st.setString(1, tag);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int setId = rs.getInt(1);
                        List<BeatmapRecord> maps = db.findBeatmapsBySetId(setId);
                        if (!maps.isEmpty()) sets.add(beatmapHandler.formatBeatmapset(setId, maps));
                    }
                }
            }
            pack.put("beatmapsets", sets);
            ctx.json(pack);
        } catch (SQLException e) {
            logger.error("Pack get failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** GET /beatmaps/packs/{tag}/download -- redirects to the pack archive */
    public void handleDownload(@NotNull Context ctx) {
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT url FROM beatmap_packs WHERE tag = ?")) {
            st.setString(1, ctx.pathParam("tag"));
            try (ResultSet rs = st.executeQuery()) {
                String url = rs.next() ? rs.getString(1) : null;
                if (url == null || url.isBlank()) {
                    ctx.status(404).json(Map.of("error", "Pack archive not available"));
                    return;
                }
                ctx.redirect(url);
            }
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /**
     * PUT /beatmaps/packs (staff): {"tag":"S1","name":"Beatmap Pack #1","author":"x","url":"https://...","type":"standard",
     * "ruleset_id":0,"no_diff_reduction":false,"beatmapset_ids":[1,2,3]}
     */
    public void handlePut(@NotNull Context ctx) {
        String header = ctx.header("Authorization");
        TokenStore.TokenData data = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authService.resolveToken(header.substring(7).trim()) : null;
        User user = data == null ? null : db.findUserById(data.userId);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        if ((user.privileges & STAFF_MASK) == 0) {
            ctx.status(403).json(Map.of("error", "Staff only"));
            return;
        }
        JsonNode b;
        try {
            b = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Malformed body"));
            return;
        }
        String tag = b.path("tag").asText("");
        String name = b.path("name").asText("");
        String type = b.path("type").asText("standard");
        if (tag.isBlank() || name.isBlank() || !TYPES.contains(type)) {
            ctx.status(422).json(Map.of("error", "tag, name and a valid type are required"));
            return;
        }
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO beatmap_packs (tag, name, author, url, type, ruleset_id, no_diff_reduction) VALUES (?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE name = VALUES(name), author = VALUES(author), url = VALUES(url), type = VALUES(type), "
                            + "ruleset_id = VALUES(ruleset_id), no_diff_reduction = VALUES(no_diff_reduction)")) {
                st.setString(1, tag);
                st.setString(2, name);
                st.setString(3, b.path("author").asText(""));
                st.setString(4, b.path("url").asText(""));
                st.setString(5, type);
                if (b.hasNonNull("ruleset_id")) st.setInt(6, b.get("ruleset_id").asInt()); else st.setNull(6, Types.TINYINT);
                st.setBoolean(7, b.path("no_diff_reduction").asBoolean(false));
                st.executeUpdate();
            }
            if (b.has("beatmapset_ids")) {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM beatmap_pack_sets WHERE tag = ?");
                     PreparedStatement ins = conn.prepareStatement("INSERT IGNORE INTO beatmap_pack_sets (tag, set_id) VALUES (?, ?)")) {
                    del.setString(1, tag);
                    del.executeUpdate();
                    for (JsonNode id : b.get("beatmapset_ids")) {
                        ins.setString(1, tag);
                        ins.setInt(2, id.asInt());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            conn.commit();
            ctx.json(Map.of("tag", tag));
        } catch (SQLException e) {
            logger.error("Pack save failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }
}
