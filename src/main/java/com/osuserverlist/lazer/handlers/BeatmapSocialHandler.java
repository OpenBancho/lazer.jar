package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * User-driven beatmap metadata: set favourites, set ratings (1-10) and tag votes.
 * Tables are created on startup (shared MySQL, same approach as the anticheat tables).
 */
public class BeatmapSocialHandler {
    private static final Logger logger = LoggerFactory.getLogger(BeatmapSocialHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthService authService;
    private final DatabaseManager db;
    private final List<Map<String, Object>> tags = new ArrayList<>();
    private final Set<Integer> tagIds = new HashSet<>();

    public BeatmapSocialHandler(AuthService authService, DatabaseManager db) {
        this.authService = authService;
        this.db = db;
        createTables();
        loadTags();
    }

    private void createTables() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS beatmapset_favourites (user_id INT NOT NULL, beatmapset_id INT NOT NULL, "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (user_id, beatmapset_id), "
                        + "INDEX idx_fav_set (beatmapset_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS beatmapset_ratings (user_id INT NOT NULL, beatmapset_id INT NOT NULL, "
                        + "rating TINYINT NOT NULL, PRIMARY KEY (user_id, beatmapset_id), "
                        + "INDEX idx_rating_set (beatmapset_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS beatmap_tag_votes (user_id INT NOT NULL, beatmap_id INT NOT NULL, tag_id INT NOT NULL, "
                        + "PRIMARY KEY (user_id, beatmap_id, tag_id), INDEX idx_tag_map (beatmap_id)) DEFAULT CHARSET=utf8mb4"
        };
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : ddl) st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create beatmap social tables: {}", e.getMessage());
        }
    }

    private void loadTags() {
        try (InputStream in = getClass().getResourceAsStream("/beatmap_tags.json")) {
            if (in == null) {
                logger.warn("beatmap_tags.json not found, tag voting disabled");
                return;
            }
            for (JsonNode n : MAPPER.readTree(in)) {
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("id", n.get("id").asInt());
                tag.put("name", n.path("name").asText(""));
                tag.put("description", n.path("description").asText(""));
                tag.put("ruleset_id", n.hasNonNull("ruleset_id") ? n.get("ruleset_id").asInt() : null);
                tags.add(tag);
                tagIds.add(n.get("id").asInt());
            }
            logger.info("Loaded {} beatmap tags", tags.size());
        } catch (Exception e) {
            logger.warn("Could not load beatmap tags: {}", e.getMessage());
        }
    }

    private User authenticate(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        TokenStore.TokenData data = authService.resolveToken(header.substring(7).trim());
        return data == null ? null : db.findUserById(data.userId);
    }

    private boolean hasPlayedSet(Connection conn, int userId, int setId, boolean mustNotBeFailOrD) throws SQLException {
        String sql = "SELECT 1 FROM scores s JOIN maps m ON m.md5 = s.map_md5 WHERE s.userid = ? AND m.set_id = ? "
                + (mustNotBeFailOrD ? "AND s.grade NOT IN ('F','D') " : "AND s.grade != 'F' ") + "LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setInt(1, userId);
            st.setInt(2, setId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---- favourites ----

    /** POST /beatmapsets/{id}/favourites  (form: action=favourite|unfavourite) */
    public void handleFavourite(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        int setId = parseInt(ctx.pathParam("beatmapset_id"));
        String action = ctx.formParam("action");
        if (action == null) action = ctx.queryParam("action");
        if (setId <= 0 || !("favourite".equals(action) || "unfavourite".equals(action))) {
            ctx.status(422).json(Map.of("error", "action must be favourite or unfavourite"));
            return;
        }
        String sql = "favourite".equals(action)
                ? "INSERT IGNORE INTO beatmapset_favourites (user_id, beatmapset_id) VALUES (?, ?)"
                : "DELETE FROM beatmapset_favourites WHERE user_id = ? AND beatmapset_id = ?";
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setInt(1, user.id);
            st.setInt(2, setId);
            st.executeUpdate();
            ctx.status(200).json(Map.of());
        } catch (SQLException e) {
            logger.error("Favourite failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** GET /me/beatmapset-favourites */
    public void handleMyFavourites(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        ctx.json(Map.of("beatmapset_ids", favouriteSetIds(user.id, 0, 1000)));
    }

    public List<Integer> favouriteSetIds(int userId, int offset, int limit) {
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT beatmapset_id FROM beatmapset_favourites WHERE user_id = ? "
                     + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            st.setInt(1, userId);
            st.setInt(2, limit);
            st.setInt(3, offset);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            logger.error("Could not list favourites of {}: {}", userId, e.getMessage());
        }
        return ids;
    }

    // ---- ratings ----

    /** POST /beatmapsets/{id}/ratings  body {"rating": 1..10} */
    public void handleRate(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        int setId = parseInt(ctx.pathParam("beatmapset_id"));
        int rating = 0;
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            rating = body.path("rating").asInt(0);
        } catch (Exception ignored) {}
        if (setId <= 0 || rating < 1 || rating > 10) {
            ctx.status(422).json(Map.of("error", "rating must be between 1 and 10"));
            return;
        }
        try (Connection conn = db.getConnection()) {
            if (!hasPlayedSet(conn, user.id, setId, false)) {
                ctx.status(403).json(Map.of("error", "You need to play this beatmapset before rating it"));
                return;
            }
            try (PreparedStatement st = conn.prepareStatement("INSERT IGNORE INTO beatmapset_ratings (user_id, beatmapset_id, rating) VALUES (?, ?, ?)")) {
                st.setInt(1, user.id);
                st.setInt(2, setId);
                st.setInt(3, rating);
                if (st.executeUpdate() == 0) {
                    ctx.status(403).json(Map.of("error", "Beatmapset has already been rated"));
                    return;
                }
            }
            double avg = 0;
            try (PreparedStatement st = conn.prepareStatement("SELECT AVG(rating) FROM beatmapset_ratings WHERE beatmapset_id = ?")) {
                st.setInt(1, setId);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) avg = rs.getDouble(1);
                }
            }
            ctx.json(Map.of("updated_rating", avg));
        } catch (SQLException e) {
            logger.error("Rating failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    // ---- tags ----

    /** GET /tags */
    public void handleAllTags(@NotNull Context ctx) {
        ctx.json(Map.of("tags", tags));
    }

    /** PUT /beatmaps/{id}/tags/{tag_id} and DELETE (vote / unvote) */
    public void handleTagVote(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        int beatmapId = parseInt(ctx.pathParam("beatmap_id"));
        int tagId = parseInt(ctx.pathParam("tag_id"));
        if (beatmapId <= 0 || !tagIds.contains(tagId)) {
            ctx.status(404).json(Map.of("error", "Beatmap or tag not found"));
            return;
        }
        boolean vote = "PUT".equalsIgnoreCase(ctx.method().name());
        try (Connection conn = db.getConnection()) {
            var beatmap = db.findBeatmapById(beatmapId);
            if (beatmap == null) {
                ctx.status(404).json(Map.of("error", "Beatmap not found"));
                return;
            }
            if (vote) {
                // Only players with a clear (not F/D) play on the map may vote
                boolean played;
                try (PreparedStatement st = conn.prepareStatement("SELECT 1 FROM scores WHERE userid = ? AND map_md5 = ? AND grade NOT IN ('F','D') LIMIT 1")) {
                    st.setInt(1, user.id);
                    st.setString(2, beatmap.md5);
                    try (ResultSet rs = st.executeQuery()) {
                        played = rs.next();
                    }
                }
                if (!played) {
                    ctx.status(403).json(Map.of("error", "You need a passing score on this beatmap to vote"));
                    return;
                }
            }
            String sql = vote
                    ? "INSERT IGNORE INTO beatmap_tag_votes (user_id, beatmap_id, tag_id) VALUES (?, ?, ?)"
                    : "DELETE FROM beatmap_tag_votes WHERE user_id = ? AND beatmap_id = ? AND tag_id = ?";
            try (PreparedStatement st = conn.prepareStatement(sql)) {
                st.setInt(1, user.id);
                st.setInt(2, beatmapId);
                st.setInt(3, tagId);
                st.executeUpdate();
            }
            ctx.status(204);
        } catch (SQLException e) {
            logger.error("Tag vote failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }
}
