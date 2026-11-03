package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.User;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Platform content (comments live in lazer_comments: bancho.jar's own `comments` table is the stable in-game one): news (/news), changelog (/changelog), wiki (/wiki/{locale}/{path}) and comments (/comments).
 * Reading is public; writing news/changelog/wiki is staff-only, comments need a logged-in user.
 */
public class ContentHandler {
    private static final Logger logger = LoggerFactory.getLogger(ContentHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int STAFF_MASK = (1 << 12) | (1 << 13) | (1 << 14);
    private static final Set<String> COMMENTABLE = Set.of("beatmapset", "build", "news_post", "score");

    private final AuthService authService;
    private final DatabaseManager db;
    private final ServerConfig config;

    public ContentHandler(AuthService authService, DatabaseManager db, ServerConfig config) {
        this.authService = authService;
        this.db = db;
        this.config = config;
        createTables();
    }

    private void createTables() {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS news_posts (id INT AUTO_INCREMENT PRIMARY KEY, slug VARCHAR(190) NOT NULL UNIQUE, "
                        + "title VARCHAR(255) NOT NULL, author VARCHAR(64) NOT NULL DEFAULT '', preview TEXT NULL, content MEDIUMTEXT NULL, "
                        + "first_image VARCHAR(512) NULL, published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS changelog_builds (id INT AUTO_INCREMENT PRIMARY KEY, version VARCHAR(64) NOT NULL, "
                        + "stream VARCHAR(64) NOT NULL DEFAULT 'stable', entries MEDIUMTEXT NULL, "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS wiki_pages (locale VARCHAR(8) NOT NULL, path VARCHAR(190) NOT NULL, title VARCHAR(255) NOT NULL, "
                        + "markdown MEDIUMTEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (locale, path)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS lazer_comments (id BIGINT AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT NULL, user_id INT NOT NULL, "
                        + "commentable_type VARCHAR(32) NOT NULL, commentable_id BIGINT NOT NULL, message TEXT NOT NULL, "
                        + "pinned TINYINT(1) NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "edited_at TIMESTAMP NULL, deleted_at TIMESTAMP NULL, INDEX idx_comment_target (commentable_type, commentable_id), "
                        + "INDEX idx_comment_parent (parent_id)) DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS lazer_comment_votes (comment_id BIGINT NOT NULL, user_id INT NOT NULL, "
                        + "PRIMARY KEY (comment_id, user_id)) DEFAULT CHARSET=utf8mb4"
        };
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : ddl) st.executeUpdate(sql);
        } catch (SQLException e) {
            logger.warn("Could not create content tables: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    private User authenticate(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        TokenStore.TokenData data = authService.resolveToken(header.substring(7).trim());
        return data == null ? null : db.findUserById(data.userId);
    }

    private User requireUser(Context ctx) {
        User user = authenticate(ctx);
        if (user == null) ctx.status(401).json(Map.of("error", "Unauthorized"));
        return user;
    }

    private User requireStaff(Context ctx) {
        User user = requireUser(ctx);
        if (user == null) return null;
        if ((user.privileges & STAFF_MASK) == 0) {
            ctx.status(403).json(Map.of("error", "Staff only"));
            return null;
        }
        return user;
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : DateTimeFormatter.ISO_INSTANT.format(ts.toInstant());
    }

    private static int intParam(Context ctx, String name, int def, int max) {
        try {
            String v = ctx.queryParam(name);
            if (v != null) return Math.max(1, Math.min(Integer.parseInt(v), max));
        } catch (NumberFormatException ignored) {}
        return def;
    }

    private JsonNode body(Context ctx) {
        try {
            return MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ---- news ----

    private Map<String, Object> newsRow(ResultSet rs, boolean full) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("author", rs.getString("author"));
        m.put("edit_url", null);
        m.put("first_image", rs.getString("first_image"));
        m.put("published_at", iso(rs.getTimestamp("published_at")));
        m.put("updated_at", iso(rs.getTimestamp("published_at")));
        m.put("slug", rs.getString("slug"));
        m.put("title", rs.getString("title"));
        m.put("preview", rs.getString("preview"));
        if (full) m.put("content", rs.getString("content"));
        return m;
    }

    /** GET /news */
    public void handleNewsList(@NotNull Context ctx) {
        int limit = intParam(ctx, "limit", 12, 50);
        List<Map<String, Object>> posts = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT * FROM news_posts ORDER BY published_at DESC LIMIT ?")) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) posts.add(newsRow(rs, false));
            }
        } catch (SQLException e) {
            logger.error("News list failed: {}", e.getMessage());
        }
        Map<String, Object> sidebar = new LinkedHashMap<>();
        sidebar.put("current_year", java.time.Year.now().getValue());
        sidebar.put("news_posts", posts);
        sidebar.put("years", List.of(java.time.Year.now().getValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("news_posts", posts);
        out.put("news_sidebar", sidebar);
        out.put("search", Map.of("limit", limit, "sort", "published_desc"));
        out.put("cursor_string", null);
        ctx.json(out);
    }

    /** GET /news/{news} -- by slug or numeric id */
    public void handleNewsGet(@NotNull Context ctx) {
        String key = ctx.pathParam("news");
        boolean byId = key.matches("\\d+") && !"id".equals(ctx.queryParam("key"));
        String sql = "SELECT * FROM news_posts WHERE " + (byId ? "id = ?" : "slug = ?") + " LIMIT 1";
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            if (byId) st.setInt(1, Integer.parseInt(key)); else st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    ctx.status(404).json(Map.of("error", "News post not found"));
                    return;
                }
                Map<String, Object> post = newsRow(rs, true);
                post.put("navigation", Map.of("newer", Map.of(), "older", Map.of()));
                ctx.json(post);
            }
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** PUT /news (staff): {slug,title,author,preview,content,first_image} */
    public void handleNewsPut(@NotNull Context ctx) {
        User staff = requireStaff(ctx);
        if (staff == null) return;
        JsonNode b = body(ctx);
        String slug = b.path("slug").asText("");
        String title = b.path("title").asText("");
        if (slug.isBlank() || title.isBlank()) {
            ctx.status(422).json(Map.of("error", "slug and title are required"));
            return;
        }
        String sql = "INSERT INTO news_posts (slug, title, author, preview, content, first_image) VALUES (?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE title = VALUES(title), author = VALUES(author), preview = VALUES(preview), "
                + "content = VALUES(content), first_image = VALUES(first_image)";
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, slug);
            st.setString(2, title);
            st.setString(3, b.path("author").asText(staff.name));
            st.setString(4, b.path("preview").asText(""));
            st.setString(5, b.path("content").asText(""));
            st.setString(6, b.hasNonNull("first_image") ? b.get("first_image").asText() : null);
            st.executeUpdate();
            ctx.json(Map.of("slug", slug));
        } catch (SQLException e) {
            logger.error("News save failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    // ---- changelog ----

    private Map<String, Object> buildRow(ResultSet rs) throws SQLException {
        String stream = rs.getString("stream");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("version", rs.getString("version"));
        m.put("display_version", rs.getString("version"));
        m.put("users", 0);
        m.put("created_at", iso(rs.getTimestamp("created_at")));
        m.put("update_stream", Map.of("id", Math.abs(stream.hashCode()), "name", stream, "display_name", stream, "is_featured", true));
        List<Object> entries = new ArrayList<>();
        try {
            String raw = rs.getString("entries");
            if (raw != null && !raw.isBlank()) entries = MAPPER.readValue(raw, List.class);
        } catch (Exception ignored) {}
        m.put("changelog_entries", entries);
        return m;
    }

    /** GET /changelog */
    public void handleChangelogList(@NotNull Context ctx) {
        int limit = intParam(ctx, "limit", 21, 100);
        List<Map<String, Object>> builds = new ArrayList<>();
        Set<String> streams = new LinkedHashSet<>();
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT * FROM changelog_builds ORDER BY created_at DESC, id DESC LIMIT ?")) {
            st.setInt(1, limit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    builds.add(buildRow(rs));
                    streams.add(rs.getString("stream"));
                }
            }
        } catch (SQLException e) {
            logger.error("Changelog failed: {}", e.getMessage());
        }
        List<Map<String, Object>> streamList = new ArrayList<>();
        for (String s : streams) {
            streamList.add(Map.of("id", Math.abs(s.hashCode()), "name", s, "display_name", s, "is_featured", true));
        }
        ctx.json(Map.of("builds", builds, "streams", streamList,
                "search", Map.of("limit", limit, "stream", ctx.queryParam("stream") == null ? "" : ctx.queryParam("stream"))));
    }

    /** GET /changelog/{stream}/{version} */
    public void handleChangelogBuild(@NotNull Context ctx) {
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT * FROM changelog_builds WHERE stream = ? AND version = ? LIMIT 1")) {
            st.setString(1, ctx.pathParam("stream"));
            st.setString(2, ctx.pathParam("version"));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    ctx.status(404).json(Map.of("error", "Build not found"));
                    return;
                }
                ctx.json(buildRow(rs));
            }
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** PUT /changelog (staff): {version, stream, entries:[{title,type,category,major}]} */
    public void handleChangelogPut(@NotNull Context ctx) {
        if (requireStaff(ctx) == null) return;
        JsonNode b = body(ctx);
        String version = b.path("version").asText("");
        if (version.isBlank()) {
            ctx.status(422).json(Map.of("error", "version is required"));
            return;
        }
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("INSERT INTO changelog_builds (version, stream, entries) VALUES (?,?,?)")) {
            st.setString(1, version);
            st.setString(2, b.path("stream").asText("stable"));
            st.setString(3, MAPPER.writeValueAsString(b.has("entries") ? b.get("entries") : List.of()));
            st.executeUpdate();
            ctx.json(Map.of("version", version));
        } catch (Exception e) {
            logger.error("Changelog save failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    // ---- wiki ----

    /** GET /wiki/{locale}/{path...} */
    public void handleWikiGet(@NotNull Context ctx) {
        String locale = ctx.pathParam("locale");
        String path = ctx.pathParam("path");
        try (Connection conn = db.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT * FROM wiki_pages WHERE path = ? ORDER BY locale = ? DESC, locale = 'en' DESC LIMIT 1")) {
            st.setString(1, path);
            st.setString(2, locale);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    ctx.status(404).json(Map.of("error", "Wiki page not found"));
                    return;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("available_locales", List.of(rs.getString("locale")));
                m.put("layout", "markdown_page");
                m.put("locale", rs.getString("locale"));
                m.put("markdown", rs.getString("markdown"));
                m.put("path", path);
                m.put("subtitle", null);
                m.put("tags", List.of());
                m.put("title", rs.getString("title"));
                ctx.json(m);
            }
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** PUT /wiki (staff): {locale, path, title, markdown} */
    public void handleWikiPut(@NotNull Context ctx) {
        if (requireStaff(ctx) == null) return;
        JsonNode b = body(ctx);
        String path = b.path("path").asText("");
        if (path.isBlank() || !b.hasNonNull("markdown")) {
            ctx.status(422).json(Map.of("error", "path and markdown are required"));
            return;
        }
        String sql = "INSERT INTO wiki_pages (locale, path, title, markdown) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE title = VALUES(title), markdown = VALUES(markdown)";
        try (Connection conn = db.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, b.path("locale").asText("en"));
            st.setString(2, path);
            st.setString(3, b.path("title").asText(path));
            st.setString(4, b.get("markdown").asText());
            st.executeUpdate();
            ctx.json(Map.of("path", path));
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    // ---- comments ----

    private Map<String, Object> commentRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean deleted = rs.getTimestamp("deleted_at") != null;
        m.put("id", rs.getLong("id"));
        m.put("parent_id", rs.getObject("parent_id"));
        m.put("user_id", rs.getInt("user_id"));
        m.put("pinned", rs.getBoolean("pinned"));
        m.put("replies_count", rs.getInt("replies_count"));
        m.put("votes_count", rs.getInt("votes_count"));
        m.put("commentable_type", rs.getString("commentable_type"));
        m.put("commentable_id", rs.getLong("commentable_id"));
        m.put("legacy_name", null);
        m.put("created_at", iso(rs.getTimestamp("created_at")));
        m.put("updated_at", iso(rs.getTimestamp("edited_at") != null ? rs.getTimestamp("edited_at") : rs.getTimestamp("created_at")));
        m.put("deleted_at", iso(rs.getTimestamp("deleted_at")));
        m.put("edited_at", iso(rs.getTimestamp("edited_at")));
        m.put("edited_by_id", null);
        m.put("message", deleted ? null : rs.getString("message"));
        m.put("message_html", deleted ? null : "<p>" + escapeHtml(rs.getString("message")) + "</p>");
        return m;
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static final String COMMENT_SELECT = "SELECT c.*, (SELECT COUNT(*) FROM lazer_comments r WHERE r.parent_id = c.id AND r.deleted_at IS NULL) AS replies_count, "
            + "(SELECT COUNT(*) FROM lazer_comment_votes v WHERE v.comment_id = c.id) AS votes_count FROM lazer_comments c ";

    private Map<String, Object> bundle(Connection conn, String where, List<Object> args, int viewerId, int limit, String sort) throws SQLException {
        String order = switch (sort == null ? "new" : sort) {
            case "old" -> "c.id ASC";
            case "top" -> "votes_count DESC, c.id DESC";
            default -> "c.id DESC";
        };
        List<Map<String, Object>> comments = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        Set<Integer> userIds = new LinkedHashSet<>();
        try (PreparedStatement st = conn.prepareStatement(COMMENT_SELECT + where + " ORDER BY " + order + " LIMIT ?")) {
            int i = 1;
            for (Object a : args) st.setObject(i++, a);
            st.setInt(i, limit + 1);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    comments.add(commentRow(rs));
                    ids.add(rs.getLong("id"));
                    userIds.add(rs.getInt("user_id"));
                }
            }
        }
        boolean hasMore = comments.size() > limit;
        if (hasMore) {
            comments = comments.subList(0, limit);
            ids = ids.subList(0, limit);
        }
        List<Long> votes = new ArrayList<>();
        if (viewerId > 0 && !ids.isEmpty()) {
            String in = String.join(",", Collections.nCopies(ids.size(), "?"));
            try (PreparedStatement st = conn.prepareStatement("SELECT comment_id FROM lazer_comment_votes WHERE user_id = ? AND comment_id IN (" + in + ")")) {
                st.setInt(1, viewerId);
                for (int i = 0; i < ids.size(); i++) st.setLong(i + 2, ids.get(i));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) votes.add(rs.getLong(1));
                }
            }
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (int uid : userIds) {
            User u = db.findUserById(uid);
            if (u == null) continue;
            Map<String, Object> um = new LinkedHashMap<>();
            um.put("id", u.id);
            um.put("username", u.name);
            um.put("country_code", u.country != null && !u.country.isBlank() ? u.country.toUpperCase() : "XX");
            um.put("avatar_url", UserResponseBuilder.getAvatarUrl(u.id, config));
            um.put("is_active", true);
            um.put("is_bot", false);
            um.put("is_deleted", false);
            um.put("is_online", false);
            um.put("is_supporter", false);
            users.add(um);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("comments", comments);
        out.put("included_comments", List.of());
        out.put("pinned_comments", List.of());
        out.put("user_votes", votes);
        out.put("users", users);
        out.put("has_more", hasMore);
        out.put("has_more_id", null);
        out.put("cursor", null);
        out.put("cursor_string", null);
        out.put("sort", sort == null ? "new" : sort);
        out.put("top_level_count", comments.size());
        out.put("total", comments.size());
        return out;
    }

    /** GET /comments?commentable_type=&commentable_id=&parent_id=&sort= */
    public void handleCommentsList(@NotNull Context ctx) {
        User viewer = authenticate(ctx);
        String type = ctx.queryParam("commentable_type");
        String idStr = ctx.queryParam("commentable_id");
        String parent = ctx.queryParam("parent_id");
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            where.append(" AND c.commentable_type = ?");
            args.add(type);
        }
        if (idStr != null && idStr.matches("\\d+")) {
            where.append(" AND c.commentable_id = ?");
            args.add(Long.parseLong(idStr));
        }
        if (parent != null && parent.matches("\\d+")) {
            where.append(" AND c.parent_id = ?");
            args.add(Long.parseLong(parent));
        } else {
            where.append(" AND c.parent_id IS NULL");
        }
        try (Connection conn = db.getConnection()) {
            ctx.json(bundle(conn, where.toString(), args, viewer == null ? 0 : viewer.id, 50, ctx.queryParam("sort")));
        } catch (SQLException e) {
            logger.error("Comments list failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    private String field(Context ctx, JsonNode json, String name) {
        String v = ctx.formParam("comment[" + name + "]");
        if (v == null) v = ctx.queryParam("comment[" + name + "]");
        if (v == null && json.path("comment").has(name)) v = json.path("comment").get(name).asText();
        if (v == null && json.has(name)) v = json.get(name).asText();
        return v;
    }

    /** POST /comments */
    public void handleCommentPost(@NotNull Context ctx) {
        User user = requireUser(ctx);
        if (user == null) return;
        JsonNode json = ctx.contentType() != null && ctx.contentType().contains("json") ? body(ctx) : MAPPER.createObjectNode();
        String type = field(ctx, json, "commentable_type");
        String idStr = field(ctx, json, "commentable_id");
        String message = field(ctx, json, "message");
        String parentStr = field(ctx, json, "parent_id");
        if (type == null || !COMMENTABLE.contains(type) || idStr == null || !idStr.matches("\\d+")
                || message == null || message.isBlank() || message.length() > 10000) {
            ctx.status(422).json(Map.of("error", "Invalid comment"));
            return;
        }
        if (user.silenceEnd > System.currentTimeMillis() / 1000) {
            ctx.status(403).json(Map.of("error", "You are silenced"));
            return;
        }
        try (Connection conn = db.getConnection()) {
            long newId;
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO lazer_comments (parent_id, user_id, commentable_type, commentable_id, message) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                if (parentStr != null && parentStr.matches("\\d+")) st.setLong(1, Long.parseLong(parentStr)); else st.setNull(1, Types.BIGINT);
                st.setInt(2, user.id);
                st.setString(3, type);
                st.setLong(4, Long.parseLong(idStr));
                st.setString(5, message.trim());
                st.executeUpdate();
                try (ResultSet keys = st.getGeneratedKeys()) {
                    keys.next();
                    newId = keys.getLong(1);
                }
            }
            ctx.json(bundle(conn, "WHERE c.id = ?", List.of(newId), user.id, 1, "new"));
        } catch (SQLException e) {
            logger.error("Comment post failed: {}", e.getMessage());
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    private long commentId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("comment"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** GET /comments/{comment} */
    public void handleCommentGet(@NotNull Context ctx) {
        User viewer = authenticate(ctx);
        try (Connection conn = db.getConnection()) {
            Map<String, Object> b = bundle(conn, "WHERE c.id = ?", List.of(commentId(ctx)), viewer == null ? 0 : viewer.id, 1, "new");
            if (((List<?>) b.get("comments")).isEmpty()) {
                ctx.status(404).json(Map.of("error", "Comment not found"));
                return;
            }
            ctx.json(b);
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    private boolean ownsOrStaff(Connection conn, User user, long commentId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT user_id FROM lazer_comments WHERE id = ? AND deleted_at IS NULL")) {
            st.setLong(1, commentId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && (rs.getInt(1) == user.id || (user.privileges & STAFF_MASK) != 0);
            }
        }
    }

    /** PUT /comments/{comment} (edit own comment) */
    public void handleCommentEdit(@NotNull Context ctx) {
        User user = requireUser(ctx);
        if (user == null) return;
        long id = commentId(ctx);
        JsonNode json = ctx.contentType() != null && ctx.contentType().contains("json") ? body(ctx) : MAPPER.createObjectNode();
        String message = field(ctx, json, "message");
        if (message == null || message.isBlank() || message.length() > 10000) {
            ctx.status(422).json(Map.of("error", "Invalid message"));
            return;
        }
        try (Connection conn = db.getConnection()) {
            if (!ownsOrStaff(conn, user, id)) {
                ctx.status(404).json(Map.of("error", "Comment not found"));
                return;
            }
            try (PreparedStatement st = conn.prepareStatement("UPDATE lazer_comments SET message = ?, edited_at = NOW() WHERE id = ?")) {
                st.setString(1, message.trim());
                st.setLong(2, id);
                st.executeUpdate();
            }
            ctx.json(bundle(conn, "WHERE c.id = ?", List.of(id), user.id, 1, "new"));
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** DELETE /comments/{comment} (soft delete) */
    public void handleCommentDelete(@NotNull Context ctx) {
        User user = requireUser(ctx);
        if (user == null) return;
        long id = commentId(ctx);
        try (Connection conn = db.getConnection()) {
            if (!ownsOrStaff(conn, user, id)) {
                ctx.status(404).json(Map.of("error", "Comment not found"));
                return;
            }
            try (PreparedStatement st = conn.prepareStatement("UPDATE lazer_comments SET deleted_at = NOW() WHERE id = ?")) {
                st.setLong(1, id);
                st.executeUpdate();
            }
            ctx.json(bundle(conn, "WHERE c.id = ?", List.of(id), user.id, 1, "new"));
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }

    /** POST /comments/{comment}/vote and DELETE (upvote / remove vote) */
    public void handleCommentVote(@NotNull Context ctx) {
        User user = requireUser(ctx);
        if (user == null) return;
        long id = commentId(ctx);
        boolean add = "POST".equalsIgnoreCase(ctx.method().name());
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement st = conn.prepareStatement(add
                    ? "INSERT IGNORE INTO lazer_comment_votes (comment_id, user_id) SELECT id, ? FROM lazer_comments WHERE id = ? AND deleted_at IS NULL"
                    : "DELETE FROM lazer_comment_votes WHERE user_id = ? AND comment_id = ?")) {
                st.setInt(1, user.id);
                st.setLong(2, id);
                st.executeUpdate();
            }
            ctx.json(bundle(conn, "WHERE c.id = ?", List.of(id), user.id, 1, "new"));
        } catch (SQLException e) {
            ctx.status(500).json(Map.of("error", "Database error"));
        }
    }
}
