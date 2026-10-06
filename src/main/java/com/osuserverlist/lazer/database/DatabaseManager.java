package com.osuserverlist.lazer.database;

import com.osuserverlist.lazer.anticheat.ScoreAntiCheat;
import com.osuserverlist.lazer.calculators.ScoreSimulator;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.handlers.UserResponseBuilder;
import com.osuserverlist.lazer.models.BeatmapRecord;
import com.osuserverlist.lazer.models.ScoreRecord;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(1500))
            .build();
    private HikariDataSource dataSource;
    private ServerConfig config;

    // Fast in-memory caches
    private final java.util.Map<Integer, User> userIdCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, User> userNameCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, BeatmapRecord> beatmapIdCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, BeatmapRecord> beatmapMd5Cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, List<BeatmapRecord>> beatmapSetCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void init(ServerConfig config) {
        this.config = config;
        HikariConfig hikariConfig = new HikariConfig();
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=%s&characterEncoding=UTF-8",
                config.dbHost, config.dbPort, config.dbName, config.dbTimezone
        );
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.dbUser);
        hikariConfig.setPassword(config.dbPass);
        hikariConfig.setMaximumPoolSize(30);
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setConnectionTimeout(3000);
        hikariConfig.setIdleTimeout(60000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("LazerHikariPool");

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            logger.info("Connected to MySQL database [{}] at {}:{}", config.dbName, config.dbHost, config.dbPort);
            ensureLazerScoreColumn();
        } catch (Exception e) {
            logger.error("Failed to connect to MySQL database: {}", e.getMessage());
        }
    }

    private void ensureLazerScoreColumn() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "scores", "lazer_score")) {
                if (!rs.next()) {
                    logger.info("Adding 'lazer_score' column to scores table...");
                    stmt.executeUpdate("ALTER TABLE scores ADD COLUMN lazer_score BIGINT NOT NULL DEFAULT 0");
                    logger.info("'lazer_score' column added successfully.");
                }
            }
        } catch (Exception e) {
            logger.warn("Could not check/add lazer_score column: {}", e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DatabaseManager is not initialized or datasource is null");
        }
        return dataSource.getConnection();
    }

    public User findUserByName(String name) {
        if (name == null || name.isBlank()) return null;
        String safeName = name.toLowerCase().replaceAll(" ", "_");
        User cached = userNameCache.get(safeName);
        if (cached != null) return cached;

        String sql = "SELECT * FROM users WHERE name = ? OR safe_name = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, safeName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User u = mapUser(rs);
                    if (u != null) {
                        userIdCache.put(u.id, u);
                        userNameCache.put(safeName, u);
                    }
                    return u;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding user by name '{}': {}", name, e.getMessage());
        }
        return null;
    }

    public User findUserById(int id) {
        if (id <= 0) return null;
        User cached = userIdCache.get(id);
        if (cached != null) return cached;

        String sql = "SELECT * FROM users WHERE id = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User u = mapUser(rs);
                    if (u != null) {
                        userIdCache.put(u.id, u);
                        userNameCache.put(u.name.toLowerCase().replaceAll(" ", "_"), u);
                    }
                    return u;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding user by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    public void updateUserLatestActivity(int userId) {
        if (userId <= 0) return;
        long now = System.currentTimeMillis() / 1000;
        String sql = "UPDATE users SET latest_activity = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, now);
            stmt.setInt(2, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.debug("Error updating latest_activity for user {}: {}", userId, e.getMessage());
        }
    }

    public UserStatistics findUserStats(int userId, int mode) {
        String sql = """
                SELECT s.*,
                    (SELECT COUNT(*) + 1 FROM stats s2 JOIN users u ON u.id = s2.id WHERE s2.mode = s.mode AND s2.pp > s.pp AND (u.priv & 1) > 0) AS global_rank,
                    (SELECT COUNT(*) + 1 FROM stats s3 JOIN users u2 ON u2.id = s3.id WHERE s3.mode = s.mode AND s3.pp > s.pp AND (u2.priv & 1) > 0 AND LOWER(u2.country) = (SELECT LOWER(country) FROM users WHERE id = s.id)) AS country_rank
                FROM stats s WHERE s.id = ? AND s.mode = ? LIMIT 1
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, mode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    UserStatistics stats = new UserStatistics();
                    stats.id = rs.getInt("id");
                    stats.mode = rs.getInt("mode");
                    stats.totalScore = rs.getLong("tscore");
                    stats.rankedScore = rs.getLong("rscore");
                    stats.pp = rs.getInt("pp");
                    stats.plays = rs.getInt("plays");
                    stats.playTime = rs.getInt("playtime");
                    stats.accuracy = rs.getFloat("acc");
                    stats.maxCombo = rs.getInt("max_combo");
                    stats.totalHits = rs.getInt("total_hits");
                    stats.replayViews = rs.getInt("replay_views");
                    stats.xhCount = rs.getInt("xh_count");
                    stats.xCount = rs.getInt("x_count");
                    stats.shCount = rs.getInt("sh_count");
                    stats.sCount = rs.getInt("s_count");
                    stats.aCount = rs.getInt("a_count");

                    if (stats.pp > 0) {
                        stats.globalRank = rs.getInt("global_rank");
                        stats.countryRank = rs.getInt("country_rank");
                    } else {
                        stats.globalRank = null;
                        stats.countryRank = null;
                    }

                    stats.level = calculateLevel(stats.totalScore);
                    stats.levelProgress = calculateLevelProgress(stats.totalScore);
                    return stats;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding user stats for user {}: {}", userId, e.getMessage());
        }
        return null;
    }

    public static int calculateLevel(long totalScore) {
        int level = 1;
        while (level < 200 && totalScore >= requiredScore(level + 1)) {
            level++;
        }
        return level;
    }

    public static double calculateLevelProgress(long totalScore) {
        int level = calculateLevel(totalScore);
        if (level >= 200) return 100.0;
        double reached = requiredScore(level);
        double next = requiredScore(level + 1);
        double span = next - reached;
        if (span <= 0) return 0.0;
        double progress = (totalScore - reached) / span * 100.0;
        return Math.max(0.0, Math.min(100.0, progress));
    }

    private static double requiredScore(int level) {
        if (level <= 1) return 0.0;
        if (level <= 100) {
            return 5000.0 / 3.0 * (4.0 * Math.pow(level, 3) - 3.0 * Math.pow(level, 2) - level)
                    + 1.25 * Math.pow(1.8, level - 60);
        }
        return 26931190829.0 + 100000000000.0 * (level - 100);
    }

    public static class ScoreCounts {
        public int best = 0;
        public int recent = 0;
        public int firsts = 0;
        public int pinned = 0;
    }

    public ScoreCounts findScoreCounts(int userId, int mode) {
        ScoreCounts counts = new ScoreCounts();
        String sqlBest = "SELECT COUNT(*) FROM scores WHERE userid = ? AND mode = ? AND status = 2 AND grade != 'F'";
        String sqlRecent = "SELECT COUNT(*) FROM scores WHERE userid = ? AND mode = ? AND grade != 'F' AND play_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)";

        try (Connection conn = getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(sqlBest)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, mode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) counts.best = rs.getInt(1);
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement(sqlRecent)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, mode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) counts.recent = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.debug("Error finding score counts: {}", e.getMessage());
        }
        return counts;
    }

    public int findFollowerCount(int userId) {
        String sql = "SELECT COUNT(*) AS total FROM relationships WHERE user2 = ? AND type = 'friend'";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            logger.debug("Error finding followers for user {}: {}", userId, e.getMessage());
        }
        return 0;
    }

    public List<ScoreRecord> findUserScores(int userId, int mode, String type, boolean includeFails, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.*,
                       COALESCE(u.name, '') AS user_name, COALESCE(u.country, 'XX') AS user_country,
                       COALESCE(m.id, 0) AS map_id, COALESCE(m.set_id, 0) AS set_id,
                       COALESCE(m.status, 0) AS map_status,
                       COALESCE(m.artist, '') AS artist, COALESCE(m.title, '') AS title,
                       COALESCE(m.version, '') AS version, COALESCE(m.creator, '') AS creator,
                       COALESCE(m.diff, 0.0) AS diff, COALESCE(m.bpm, 0.0) AS bpm,
                       COALESCE(m.cs, 0.0) AS cs, COALESCE(m.ar, 0.0) AS ar,
                       COALESCE(m.od, 0.0) AS od, COALESCE(m.hp, 0.0) AS hp,
                       COALESCE(m.total_length, 0) AS total_length, COALESCE(m.max_combo, 0) AS map_max_combo
                FROM scores s
                LEFT JOIN users u ON u.id = s.userid
                LEFT JOIN maps m ON m.md5 = s.map_md5
                WHERE s.userid = ? AND s.mode = ?
                """);

        if ("firsts".equalsIgnoreCase(type)) {
            sql.append(" AND s.status = 2 AND s.grade != 'F' ORDER BY s.pp DESC, s.score DESC ");
        } else if ("pinned".equalsIgnoreCase(type)) {
            sql.append(" AND 1=0 ");
        } else if ("best".equalsIgnoreCase(type)) {
            sql.append(" AND s.status = 2 AND s.grade != 'F' ORDER BY s.pp DESC, s.score DESC ");
        } else if ("recent".equalsIgnoreCase(type)) {
            sql.append(" AND s.play_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR) ");
            if (!includeFails) {
                sql.append(" AND s.grade != 'F' ");
            }
            sql.append(" ORDER BY s.play_time DESC ");
        } else {
            if (!includeFails) {
                sql.append(" AND s.grade != 'F' ");
            }
            sql.append(" ORDER BY s.play_time DESC ");
        }
        sql.append(" LIMIT ? OFFSET ?");

        List<ScoreRecord> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setInt(1, userId);
            stmt.setInt(2, mode);
            stmt.setInt(3, Math.min(Math.max(limit, 1), 100));
            stmt.setInt(4, Math.max(offset, 0));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapScoreRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding scores for user {}: {}", userId, e.getMessage());
        }
        return list;
    }

    public ScoreRecord mapScoreRecord(ResultSet rs) throws SQLException {
        ScoreRecord sc = new ScoreRecord();
        sc.id = rs.getLong("id");
        sc.mapMd5 = rs.getString("map_md5");
        sc.score = rs.getLong("score");
        try {
            sc.lazerScore = rs.getLong("lazer_score");
        } catch (SQLException e) {
            sc.lazerScore = 0;
        }
        sc.pp = rs.getFloat("pp");
        sc.acc = rs.getFloat("acc");
        sc.maxCombo = rs.getInt("max_combo");
        sc.mods = rs.getInt("mods");
        sc.n300 = rs.getInt("n300");
        sc.n100 = rs.getInt("n100");
        sc.n50 = rs.getInt("n50");
        sc.nmiss = rs.getInt("nmiss");
        sc.ngeki = rs.getInt("ngeki");
        sc.nkatu = rs.getInt("nkatu");
        sc.grade = rs.getString("grade");
        sc.status = rs.getInt("status");
        sc.mode = rs.getInt("mode");

        Timestamp ts = rs.getTimestamp("play_time");
        sc.playTimeEpochSec = ts != null ? ts.getTime() / 1000 : System.currentTimeMillis() / 1000;
        sc.timeElapsed = rs.getInt("time_elapsed");
        sc.perfect = rs.getInt("perfect") == 1;
        sc.userId = rs.getInt("userid");

        try {
            sc.username = rs.getString("user_name");
            sc.country = rs.getString("user_country");
        } catch (SQLException ignored) {}

        try {
            sc.mapId = rs.getInt("map_id");
            sc.setId = rs.getInt("set_id");
            sc.mapStatus = rs.getInt("map_status");
            sc.artist = rs.getString("artist");
            sc.title = rs.getString("title");
            sc.version = rs.getString("version");
            sc.creator = rs.getString("creator");
            sc.diff = rs.getFloat("diff");
            sc.bpm = rs.getFloat("bpm");
            sc.cs = rs.getFloat("cs");
            sc.ar = rs.getFloat("ar");
            sc.od = rs.getFloat("od");
            sc.hp = rs.getFloat("hp");
            sc.totalLength = rs.getInt("total_length");
            sc.mapMaxCombo = rs.getInt("map_max_combo");
        } catch (SQLException ignored) {}

        return sc;
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.id = rs.getInt("id");
        user.name = rs.getString("name");
        user.safeName = rs.getString("safe_name");
        user.email = rs.getString("email");
        user.privileges = rs.getInt("priv");
        user.passwordHash = rs.getString("pw_bcrypt");
        user.country = rs.getString("country");
        user.silenceEnd = rs.getInt("silence_end");
        user.donorEnd = rs.getInt("donor_end");
        user.creationTime = rs.getInt("creation_time");
        user.latestActivity = rs.getInt("latest_activity");
        user.preferredMode = rs.getInt("preferred_mode");
        try {
            user.customBadgeName = rs.getString("custom_badge_name");
            user.customBadgeIcon = rs.getString("custom_badge_icon");
        } catch (SQLException ignored) {}
        try {
            user.customBanner = rs.getString("custom_banner");
        } catch (SQLException ignored) {}
        return user;
    }

    public java.util.List<Integer> findFriendUserIds(int userId) {
        String sql = "SELECT user2 FROM relationships WHERE user1 = ? AND type = 'friend'";
        java.util.List<Integer> list = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("user2"));
                }
            }
        } catch (SQLException e) {
            logger.debug("Error finding friends for user {}: {}", userId, e.getMessage());
        }
        return list;
    }

    public java.util.List<Integer> findBlockedUserIds(int userId) {
        String sql = "SELECT user2 FROM relationships WHERE user1 = ? AND type = 'block'";
        java.util.List<Integer> list = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("user2"));
                }
            }
        } catch (SQLException e) {
            logger.debug("Error finding blocks for user {}: {}", userId, e.getMessage());
        }
        return list;
    }

    public BeatmapRecord findBeatmapById(int id) {
        if (id <= 0) return null;
        BeatmapRecord cached = beatmapIdCache.get(id);
        if (cached != null) return cached;

        String sql = "SELECT * FROM maps WHERE id = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BeatmapRecord bm = mapBeatmapRecord(rs);
                    if (bm != null) {
                        beatmapIdCache.put(bm.id, bm);
                        if (bm.md5 != null) beatmapMd5Cache.put(bm.md5, bm);
                    }
                    return bm;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding beatmap by id {}: {}", id, e.getMessage());
        }
        return fetchAndSaveBeatmap(id, null);
    }

    public BeatmapRecord findBeatmapByMd5(String md5) {
        if (md5 == null || md5.isBlank()) return null;
        BeatmapRecord cached = beatmapMd5Cache.get(md5);
        if (cached != null) return cached;

        String sql = "SELECT * FROM maps WHERE md5 = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, md5);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    BeatmapRecord bm = mapBeatmapRecord(rs);
                    if (bm != null) {
                        beatmapMd5Cache.put(bm.md5, bm);
                        if (bm.id > 0) beatmapIdCache.put(bm.id, bm);
                    }
                    return bm;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding beatmap by md5 {}: {}", md5, e.getMessage());
        }
        return fetchAndSaveBeatmap(0, md5);
    }

    public List<BeatmapRecord> findBeatmapsBySetId(int setId) {
        if (setId <= 0) return Collections.emptyList();
        List<BeatmapRecord> cached = beatmapSetCache.get(setId);
        if (cached != null && !cached.isEmpty()) return cached;

        String sql = "SELECT * FROM maps WHERE set_id = ? ORDER BY diff ASC";
        List<BeatmapRecord> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, setId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BeatmapRecord bm = mapBeatmapRecord(rs);
                    list.add(bm);
                    beatmapIdCache.put(bm.id, bm);
                    if (bm.md5 != null) beatmapMd5Cache.put(bm.md5, bm);
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding beatmaps for set id {}: {}", setId, e.getMessage());
        }
        if (list.isEmpty()) {
            fetchAndSaveBeatmapSet(setId);
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, setId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        BeatmapRecord bm = mapBeatmapRecord(rs);
                        list.add(bm);
                        beatmapIdCache.put(bm.id, bm);
                        if (bm.md5 != null) beatmapMd5Cache.put(bm.md5, bm);
                    }
                }
            } catch (SQLException ignored) {}
        }
        if (!list.isEmpty()) {
            beatmapSetCache.put(setId, list);
        }
        return list;
    }

    public BeatmapRecord fetchAndSaveBeatmap(int id, String md5) {
        String apiKey = (config != null && config.osuApiKey != null && !config.osuApiKey.isBlank())
                ? config.osuApiKey : "3f8616cb3488e3ed1cec4a8fd3501cebeb506ee5";

        // Try fast mirror direct search / lookup first or official osu! API with 1.5s timeout
        String url = (id > 0)
                ? "https://osu.ppy.sh/api/get_beatmaps?k=" + apiKey + "&b=" + id
                : "https://osu.ppy.sh/api/get_beatmaps?k=" + apiKey + "&h=" + md5;

        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMillis(1500))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(resp.body());
                if (arr.isArray() && arr.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode m = arr.get(0);
                    return saveBeatmapJsonNode(m);
                }
            }
        } catch (Exception e) {
            logger.debug("Beatmap fetch timeout or unavailable (id={}, md5={}): {}", id, md5, e.getMessage());
        }
        return null;
    }

    public void fetchAndSaveBeatmapSet(int setId) {
        String apiKey = (config != null && config.osuApiKey != null && !config.osuApiKey.isBlank())
                ? config.osuApiKey : "3f8616cb3488e3ed1cec4a8fd3501cebeb506ee5";

        String url = "https://osu.ppy.sh/api/get_beatmaps?k=" + apiKey + "&s=" + setId;
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofMillis(2000))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(resp.body());
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode m : arr) {
                        saveBeatmapJsonNode(m);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Beatmapset fetch timeout or unavailable (setId={}): {}", setId, e.getMessage());
        }
    }

    private BeatmapRecord saveBeatmapJsonNode(com.fasterxml.jackson.databind.JsonNode m) {
        try {
            int bId = m.path("beatmap_id").asInt();
            long setId = m.path("beatmapset_id").asLong();
            int status = m.path("approved").asInt();
            String fileMd5 = m.path("file_md5").asText("");
            String artist = m.path("artist").asText("");
            String title = m.path("title").asText("");
            String version = m.path("version").asText("");
            String creator = m.path("creator").asText("");
            String filename = artist + " - " + title + " [" + version + "].osu";
            int totalLength = m.path("total_length").asInt();
            int maxCombo = m.path("max_combo").asInt(0);
            int mode = m.path("mode").asInt(0);
            float bpm = (float) m.path("bpm").asDouble(0.0);
            float cs = (float) m.path("diff_size").asDouble(0.0);
            float od = (float) m.path("diff_overall").asDouble(0.0);
            float ar = (float) m.path("diff_approach").asDouble(0.0);
            float hp = (float) m.path("diff_drain").asDouble(0.0);
            float diff = (float) m.path("difficultyrating").asDouble(0.0);

            String insertMap = """
                    INSERT INTO maps (
                        id, server, set_id, status, md5, artist, title, version, creator,
                        filename, last_update, total_length, max_combo, frozen, plays, passes,
                        mode, bpm, cs, ar, od, hp, diff
                    ) VALUES (
                        ?, 'osu!', ?, ?, ?, ?, ?, ?, ?,
                        ?, NOW(), ?, ?, 0, 0, 0,
                        ?, ?, ?, ?, ?, ?, ?
                    ) ON DUPLICATE KEY UPDATE md5 = VALUES(md5), set_id = VALUES(set_id), status = VALUES(status)
                    """;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(insertMap)) {
                stmt.setInt(1, bId);
                stmt.setLong(2, setId);
                stmt.setInt(3, status);
                stmt.setString(4, fileMd5);
                stmt.setString(5, artist);
                stmt.setString(6, title);
                stmt.setString(7, version);
                stmt.setString(8, creator);
                stmt.setString(9, filename);
                stmt.setInt(10, totalLength);
                stmt.setInt(11, maxCombo);
                stmt.setInt(12, mode);
                stmt.setFloat(13, bpm);
                stmt.setFloat(14, cs);
                stmt.setFloat(15, ar);
                stmt.setFloat(16, od);
                stmt.setFloat(17, hp);
                stmt.setFloat(18, diff);
                stmt.executeUpdate();
            }

            BeatmapRecord bm = new BeatmapRecord();
            bm.id = bId;
            bm.setId = (int) setId;
            bm.status = status;
            bm.md5 = fileMd5;
            bm.artist = artist;
            bm.title = title;
            bm.version = version;
            bm.creator = creator;
            bm.totalLength = totalLength;
            bm.maxCombo = maxCombo;
            bm.mode = mode;
            bm.bpm = bpm;
            bm.cs = cs;
            bm.od = od;
            bm.ar = ar;
            bm.hp = hp;
            bm.diff = diff;

            beatmapIdCache.put(bm.id, bm);
            if (bm.md5 != null) beatmapMd5Cache.put(bm.md5, bm);
            return bm;
        } catch (Exception e) {
            logger.warn("Failed to parse and save beatmap JSON: {}", e.getMessage());
            return null;
        }
    }

    private BeatmapRecord mapBeatmapRecord(ResultSet rs) throws SQLException {
        BeatmapRecord bm = new BeatmapRecord();
        bm.id = rs.getInt("id");
        bm.setId = rs.getInt("set_id");
        bm.status = rs.getInt("status");
        bm.md5 = rs.getString("md5");
        bm.artist = rs.getString("artist");
        bm.title = rs.getString("title");
        bm.version = rs.getString("version");
        bm.creator = rs.getString("creator");
        bm.totalLength = rs.getInt("total_length");
        bm.maxCombo = rs.getInt("max_combo");
        bm.mode = rs.getInt("mode");
        bm.bpm = rs.getFloat("bpm");
        bm.cs = rs.getFloat("cs");
        bm.ar = rs.getFloat("ar");
        bm.od = rs.getFloat("od");
        bm.hp = rs.getFloat("hp");
        bm.diff = rs.getFloat("diff");
        return bm;
    }

    public List<ScoreRecord> findBeatmapLeaderboard(int beatmapId, int mode, int limit) {
        BeatmapRecord beatmap = findBeatmapById(beatmapId);
        if (beatmap == null || beatmap.md5 == null || beatmap.md5.isBlank()) {
            return Collections.emptyList();
        }
        String sql = """
                SELECT s.*,
                       COALESCE(u.name, '') AS user_name, COALESCE(u.country, 'XX') AS user_country,
                       COALESCE(m.id, 0) AS map_id, COALESCE(m.set_id, 0) AS set_id,
                       COALESCE(m.status, 0) AS map_status,
                       COALESCE(m.artist, '') AS artist, COALESCE(m.title, '') AS title,
                       COALESCE(m.version, '') AS version, COALESCE(m.creator, '') AS creator,
                       COALESCE(m.diff, 0.0) AS diff, COALESCE(m.bpm, 0.0) AS bpm,
                       COALESCE(m.cs, 0.0) AS cs, COALESCE(m.ar, 0.0) AS ar,
                       COALESCE(m.od, 0.0) AS od, COALESCE(m.hp, 0.0) AS hp,
                       COALESCE(m.total_length, 0) AS total_length, COALESCE(m.max_combo, 0) AS map_max_combo
                FROM scores s
                JOIN users u ON u.id = s.userid
                LEFT JOIN maps m ON m.md5 = s.map_md5
                WHERE s.map_md5 = ? AND s.mode = ? AND s.status = 2 AND s.grade != 'F'
                ORDER BY s.pp DESC, s.score DESC
                LIMIT ?
                """;
        List<ScoreRecord> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, beatmap.md5);
            stmt.setInt(2, mode);
            stmt.setInt(3, Math.min(Math.max(limit, 1), 50));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapScoreRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding beatmap leaderboard for {}: {}", beatmapId, e.getMessage());
        }
        return list;
    }

    public ScoreRecord findUserBeatmapScore(int beatmapId, int userId, int mode) {
        BeatmapRecord beatmap = findBeatmapById(beatmapId);
        if (beatmap == null || beatmap.md5 == null || beatmap.md5.isBlank()) {
            return null;
        }
        String sql = """
                SELECT s.*,
                       COALESCE(u.name, '') AS user_name, COALESCE(u.country, 'XX') AS user_country,
                       COALESCE(m.id, 0) AS map_id, COALESCE(m.set_id, 0) AS set_id,
                       COALESCE(m.status, 0) AS map_status,
                       COALESCE(m.artist, '') AS artist, COALESCE(m.title, '') AS title,
                       COALESCE(m.version, '') AS version, COALESCE(m.creator, '') AS creator,
                       COALESCE(m.diff, 0.0) AS diff, COALESCE(m.bpm, 0.0) AS bpm,
                       COALESCE(m.cs, 0.0) AS cs, COALESCE(m.ar, 0.0) AS ar,
                       COALESCE(m.od, 0.0) AS od, COALESCE(m.hp, 0.0) AS hp,
                       COALESCE(m.total_length, 0) AS total_length, COALESCE(m.max_combo, 0) AS map_max_combo
                FROM scores s
                JOIN users u ON u.id = s.userid
                LEFT JOIN maps m ON m.md5 = s.map_md5
                WHERE s.userid = ? AND s.map_md5 = ? AND s.mode = ? AND s.status = 2 AND s.grade != 'F'
                ORDER BY s.pp DESC, s.score DESC
                LIMIT 1
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setString(2, beatmap.md5);
            stmt.setInt(3, mode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapScoreRecord(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding user beatmap score for user {} map {}: {}", userId, beatmapId, e.getMessage());
        }
        return null;
    }

    public static class ScoreSubmitResult {
        public ScoreRecord score;
        public int position;
        public boolean isPersonalBest;
    }

    public ScoreSubmitResult submitScore(
            int userId,
            int beatmapId,
            int rulesetId,
            long totalScore,
            long totalScoreWithoutMods,
            float accuracy,
            int maxCombo,
            float pp,
            String rank,
            boolean passed,
            int modsBitmask,
            int n300, int n100, int n50, int nmiss, int ngeki, int nkatu,
            int objectCount,
            int timeElapsed,
            String beatmapHash
    ) {
        int effectiveMode = UserResponseBuilder.getEffectiveMode(rulesetId, modsBitmask);
        int baseRuleset = effectiveMode % 4;

        BeatmapRecord beatmap = findBeatmapById(beatmapId);
        if (beatmap == null && beatmapHash != null && !beatmapHash.isBlank()) {
            beatmap = findBeatmapByMd5(beatmapHash);
        }

        ScoreAntiCheat.ValidationResult validation = ScoreAntiCheat.validateScore(
                userId,
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
            logger.warn("Rejecting impossible score in DatabaseManager for user {} on beatmap {}: {}",
                    userId, beatmapId, validation.getReason());
            return null;
        }

        String mapMd5 = (beatmap != null && beatmap.md5 != null && !beatmap.md5.isBlank())
                ? beatmap.md5
                : (beatmapHash != null ? beatmapHash : "");
        int mapStatus = (beatmap != null) ? beatmap.status : 0;
        int mapMaxCombo = (beatmap != null) ? beatmap.maxCombo : 0;

        // Server-side simulation: never trust client accuracy, grade or PP
        ScoreSimulator.SimulationResult sim = ScoreSimulator.simulateAndValidate(
                userId,
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
            logger.warn("Rejecting score in DatabaseManager after simulation for user {} on beatmap {}: {}",
                    userId, beatmapId, sim.rejectionReason);
            return null;
        }

        accuracy = sim.accuracy;
        rank = sim.grade;
        pp = sim.pp;

        long classicScore = UserResponseBuilder.convertStandardisedToClassic(baseRuleset, totalScore, objectCount);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Check existing best score
                long prevBestScore = 0;
                float prevBestPp = 0f;
                long prevBestId = -1;
                boolean hasPreviousBest = false;

                if (!mapMd5.isBlank()) {
                    String checkPbSql = "SELECT id, score, pp FROM scores WHERE userid = ? AND map_md5 = ? AND mode = ? AND status = 2 ORDER BY pp DESC, score DESC LIMIT 1";
                    try (PreparedStatement stmt = conn.prepareStatement(checkPbSql)) {
                        stmt.setInt(1, userId);
                        stmt.setString(2, mapMd5);
                        stmt.setInt(3, effectiveMode);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                hasPreviousBest = true;
                                prevBestId = rs.getLong("id");
                                prevBestScore = rs.getLong("score");
                                prevBestPp = rs.getFloat("pp");
                            }
                        }
                    }
                }

                boolean isPersonalBest = passed && (!hasPreviousBest || (mapStatus > 0 && pp > 0 ? pp > prevBestPp : classicScore > prevBestScore));
                int newStatus = (isPersonalBest && passed) ? 2 : 0;

                // 2. Demote previous personal best if new PB
                if (isPersonalBest && prevBestId != -1) {
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE scores SET status = 0 WHERE id = ?")) {
                        stmt.setLong(1, prevBestId);
                        stmt.executeUpdate();
                    }
                }

                // 3. Insert new score record
                long newScoreId = -1;
                String insertSql = """
                        INSERT INTO scores (
                            userid, map_md5, score, lazer_score, pp, acc, max_combo, mods,
                            n300, n100, n50, nmiss, ngeki, nkatu, grade, status, mode,
                            play_time, time_elapsed, client_flags, perfect, online_checksum
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            NOW(), ?, 0, ?, ?
                        )
                        """;
                String checksum = java.util.UUID.randomUUID().toString().replace("-", "");
                boolean isPerfect = passed && mapMaxCombo > 0 && maxCombo >= mapMaxCombo;

                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setInt(1, userId);
                    stmt.setString(2, mapMd5);
                    stmt.setLong(3, classicScore);
                    stmt.setLong(4, totalScore);
                    stmt.setFloat(5, pp);
                    stmt.setFloat(6, accuracy);
                    stmt.setInt(7, maxCombo);
                    stmt.setInt(8, modsBitmask);
                    stmt.setInt(9, n300);
                    stmt.setInt(10, n100);
                    stmt.setInt(11, n50);
                    stmt.setInt(12, nmiss);
                    stmt.setInt(13, ngeki);
                    stmt.setInt(14, nkatu);
                    stmt.setString(15, rank != null && !rank.isBlank() ? rank.toUpperCase() : "D");
                    stmt.setInt(16, newStatus);
                    stmt.setInt(17, effectiveMode);
                    stmt.setInt(18, timeElapsed);
                    stmt.setBoolean(19, isPerfect);
                    stmt.setString(20, checksum);
                    stmt.executeUpdate();

                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            newScoreId = rs.getLong(1);
                        }
                    }
                }

                // 4. Update stats for user
                int plays = 0, playtime = 0, totalHits = 0, userMaxCombo = 0, currentPp = 0;
                long tscore = 0, rscore = 0;
                float currentAcc = 0f;
                int xh = 0, x = 0, sh = 0, s = 0, a = 0;
                boolean statsExists = false;

                String selectStatsSql = "SELECT * FROM stats WHERE id = ? AND mode = ? LIMIT 1 FOR UPDATE";
                try (PreparedStatement stmt = conn.prepareStatement(selectStatsSql)) {
                    stmt.setInt(1, userId);
                    stmt.setInt(2, effectiveMode);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            statsExists = true;
                            tscore = rs.getLong("tscore");
                            rscore = rs.getLong("rscore");
                            currentPp = rs.getInt("pp");
                            plays = rs.getInt("plays");
                            playtime = rs.getInt("playtime");
                            currentAcc = rs.getFloat("acc");
                            userMaxCombo = rs.getInt("max_combo");
                            totalHits = rs.getInt("total_hits");
                            xh = rs.getInt("xh_count");
                            x = rs.getInt("x_count");
                            sh = rs.getInt("sh_count");
                            s = rs.getInt("s_count");
                            a = rs.getInt("a_count");
                        }
                    }
                }

                plays++;
                playtime += Math.max(timeElapsed / 1000, 1);
                totalHits += (n300 + n100 + n50);
                tscore += classicScore;

                if (passed) {
                    userMaxCombo = Math.max(userMaxCombo, maxCombo);
                    float accFraction = accuracy > 1.0f ? accuracy / 100.0f : accuracy;
                    currentAcc = currentAcc == 0 ? accFraction : (currentAcc + accFraction) / 2.0f;

                    if (isPersonalBest && rank != null) {
                        switch (rank.toUpperCase()) {
                            case "XH" -> xh++;
                            case "X" -> x++;
                            case "SH" -> sh++;
                            case "S" -> s++;
                            case "A" -> a++;
                        }
                    }

                    if (mapStatus > 0 && isPersonalBest) {
                        rscore += Math.max(0, classicScore - prevBestScore);
                    }
                }

                // Weighted PP calculation if ranked map
                int calculatedUserPp = currentPp;
                if (passed && mapStatus > 0 && isPersonalBest) {
                    String ppSql = """
                            SELECT SUM(pp * POW(0.95, rn - 1)) AS weighted_pp
                            FROM (
                                SELECT pp, ROW_NUMBER() OVER (ORDER BY pp DESC) AS rn
                                FROM (
                                    SELECT MAX(s.pp) AS pp
                                    FROM scores s
                                    JOIN maps m ON s.map_md5 = m.md5
                                    WHERE s.userid = ? AND s.mode = ? AND m.status >= 1 AND s.status = 2
                                    GROUP BY s.map_md5
                                ) best_scores
                            ) ranked
                            """;
                    try (PreparedStatement stmt = conn.prepareStatement(ppSql)) {
                        stmt.setInt(1, userId);
                        stmt.setInt(2, effectiveMode);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                double wpp = rs.getDouble("weighted_pp");
                                if (!rs.wasNull()) {
                                    calculatedUserPp = (int) Math.round(wpp);
                                }
                            }
                        }
                    }
                }

                if (statsExists) {
                    String updateStatsSql = """
                            UPDATE stats SET
                                tscore = ?, rscore = ?, pp = ?, plays = ?, playtime = ?,
                                acc = ?, max_combo = ?, total_hits = ?,
                                xh_count = ?, x_count = ?, sh_count = ?, s_count = ?, a_count = ?
                            WHERE id = ? AND mode = ?
                            """;
                    try (PreparedStatement stmt = conn.prepareStatement(updateStatsSql)) {
                        stmt.setLong(1, tscore);
                        stmt.setLong(2, rscore);
                        stmt.setInt(3, calculatedUserPp);
                        stmt.setInt(4, plays);
                        stmt.setInt(5, playtime);
                        stmt.setFloat(6, currentAcc);
                        stmt.setInt(7, userMaxCombo);
                        stmt.setInt(8, totalHits);
                        stmt.setInt(9, xh);
                        stmt.setInt(10, x);
                        stmt.setInt(11, sh);
                        stmt.setInt(12, s);
                        stmt.setInt(13, a);
                        stmt.setInt(14, userId);
                        stmt.setInt(15, effectiveMode);
                        stmt.executeUpdate();
                    }
                } else {
                    String insertStatsSql = """
                            INSERT INTO stats (
                                id, mode, tscore, rscore, pp, plays, playtime,
                                acc, max_combo, total_hits, xh_count, x_count, sh_count, s_count, a_count
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """;
                    try (PreparedStatement stmt = conn.prepareStatement(insertStatsSql)) {
                        stmt.setInt(1, userId);
                        stmt.setInt(2, effectiveMode);
                        stmt.setLong(3, tscore);
                        stmt.setLong(4, rscore);
                        stmt.setInt(5, calculatedUserPp);
                        stmt.setInt(6, plays);
                        stmt.setInt(7, playtime);
                        stmt.setFloat(8, currentAcc);
                        stmt.setInt(9, userMaxCombo);
                        stmt.setInt(10, totalHits);
                        stmt.setInt(11, xh);
                        stmt.setInt(12, x);
                        stmt.setInt(13, sh);
                        stmt.setInt(14, s);
                        stmt.setInt(15, a);
                        stmt.executeUpdate();
                    }
                }

                // 5. Update map play & pass counts
                if (beatmap != null && beatmap.id > 0) {
                    String mapUpdate = passed
                            ? "UPDATE maps SET plays = plays + 1, passes = passes + 1 WHERE id = ?"
                            : "UPDATE maps SET plays = plays + 1 WHERE id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(mapUpdate)) {
                        stmt.setInt(1, beatmap.id);
                        stmt.executeUpdate();
                    }
                }

                // 6. Calculate position on map leaderboard
                int position = 1;
                if (passed && !mapMd5.isBlank()) {
                    String rankSql = "SELECT COUNT(*) + 1 AS pos FROM scores WHERE map_md5 = ? AND mode = ? AND status = 2 AND score > ?";
                    try (PreparedStatement stmt = conn.prepareStatement(rankSql)) {
                        stmt.setString(1, mapMd5);
                        stmt.setInt(2, rulesetId);
                        stmt.setLong(3, classicScore);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                position = rs.getInt("pos");
                            }
                        }
                    }
                }

                conn.commit();

                // Prepare return score record
                ScoreRecord sc = new ScoreRecord();
                sc.id = newScoreId;
                sc.userId = userId;
                sc.mapMd5 = mapMd5;
                sc.score = classicScore;
                sc.lazerScore = totalScore;
                sc.pp = pp;
                sc.acc = accuracy;
                sc.maxCombo = maxCombo;
                sc.mods = modsBitmask;
                sc.n300 = n300;
                sc.n100 = n100;
                sc.n50 = n50;
                sc.nmiss = nmiss;
                sc.ngeki = ngeki;
                sc.nkatu = nkatu;
                sc.grade = rank;
                sc.status = newStatus;
                sc.mode = rulesetId;
                sc.playTimeEpochSec = System.currentTimeMillis() / 1000;
                sc.timeElapsed = timeElapsed;
                sc.perfect = isPerfect;

                User user = findUserById(userId);
                if (user != null) {
                    sc.username = user.name;
                    sc.country = user.country;
                }
                if (beatmap != null) {
                    sc.mapId = beatmap.id;
                    sc.setId = beatmap.setId;
                    sc.mapStatus = beatmap.status;
                    sc.artist = beatmap.artist;
                    sc.title = beatmap.title;
                    sc.version = beatmap.version;
                    sc.creator = beatmap.creator;
                    sc.diff = beatmap.diff;
                    sc.bpm = beatmap.bpm;
                    sc.cs = beatmap.cs;
                    sc.ar = beatmap.ar;
                    sc.od = beatmap.od;
                    sc.hp = beatmap.hp;
                    sc.totalLength = beatmap.totalLength;
                    sc.mapMaxCombo = beatmap.maxCombo;
                }

                ScoreSubmitResult result = new ScoreSubmitResult();
                result.score = sc;
                result.position = position;
                result.isPersonalBest = isPersonalBest;
                return result;

            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to submit score for user {} on beatmap {}: {}", userId, beatmapId, e.getMessage(), e);
            return null;
        }
    }

    public static class RankingUserRecord {
        public User user;
        public UserStatistics stats;
        public int globalRank;
    }

    public static class CountryRankingRecord {
        public String code;
        public long activeUsers;
        public long playCount;
        public long rankedScore;
        public long performance;
    }

    public List<RankingUserRecord> findRankings(int mode, String type, String country, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id AS user_id, u.name AS user_name, u.safe_name, u.country AS user_country,
                       u.donor_end, u.creation_time, u.latest_activity, u.custom_banner, u.priv,
                       s.tscore, s.rscore, s.pp, s.plays, s.playtime, s.acc, s.max_combo, s.total_hits,
                       s.xh_count, s.x_count, s.sh_count, s.s_count, s.a_count
                FROM stats s
                JOIN users u ON u.id = s.id
                WHERE s.mode = ? AND (u.priv & 1) = 1 AND (s.plays > 0 OR s.pp > 0 OR s.tscore > 0)
                """);

        boolean hasCountry = country != null && !country.isBlank();
        if (hasCountry) {
            sql.append(" AND UPPER(u.country) = UPPER(?) ");
        }

        if ("score".equalsIgnoreCase(type) || "ranked_score".equalsIgnoreCase(type)) {
            sql.append(" ORDER BY s.rscore DESC, s.tscore DESC ");
        } else if ("all".equalsIgnoreCase(type) || "total_score".equalsIgnoreCase(type)) {
            sql.append(" ORDER BY s.tscore DESC, s.rscore DESC ");
        } else {
            sql.append(" ORDER BY s.pp DESC, s.rscore DESC ");
        }

        sql.append(" LIMIT ? OFFSET ? ");

        int limit = Math.min(Math.max(pageSize, 1), 100);
        int offset = Math.max((page - 1) * limit, 0);

        List<RankingUserRecord> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int p = 1;
            stmt.setInt(p++, mode);
            if (hasCountry) {
                stmt.setString(p++, country.trim());
            }
            stmt.setInt(p++, limit);
            stmt.setInt(p++, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                int index = 0;
                while (rs.next()) {
                    RankingUserRecord rec = new RankingUserRecord();
                    User u = new User();
                    u.id = rs.getInt("user_id");
                    u.name = rs.getString("user_name");
                    u.safeName = rs.getString("safe_name");
                    u.country = rs.getString("user_country");
                    u.donorEnd = rs.getInt("donor_end");
                    u.creationTime = rs.getInt("creation_time");
                    u.latestActivity = rs.getInt("latest_activity");
                    u.customBanner = rs.getString("custom_banner");
                    u.privileges = rs.getInt("priv");

                    UserStatistics st = new UserStatistics();
                    st.id = u.id;
                    st.mode = mode;
                    st.totalScore = rs.getLong("tscore");
                    st.rankedScore = rs.getLong("rscore");
                    st.pp = rs.getInt("pp");
                    st.plays = rs.getInt("plays");
                    st.playTime = rs.getInt("playtime");
                    st.accuracy = rs.getFloat("acc");
                    st.maxCombo = rs.getInt("max_combo");
                    st.totalHits = rs.getInt("total_hits");
                    st.xhCount = rs.getInt("xh_count");
                    st.xCount = rs.getInt("x_count");
                    st.shCount = rs.getInt("sh_count");
                    st.sCount = rs.getInt("s_count");
                    st.aCount = rs.getInt("a_count");
                    st.level = calculateLevel(st.totalScore);
                    st.levelProgress = calculateLevelProgress(st.totalScore);

                    rec.user = u;
                    rec.stats = st;
                    rec.globalRank = offset + index + 1;
                    st.globalRank = rec.globalRank;
                    list.add(rec);
                    index++;
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding rankings for mode {}: {}", mode, e.getMessage());
        }
        return list;
    }

    public int countRankings(int mode, String country) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM stats s
                JOIN users u ON u.id = s.id
                WHERE s.mode = ? AND (u.priv & 1) = 1 AND (s.plays > 0 OR s.pp > 0 OR s.tscore > 0)
                """);
        boolean hasCountry = country != null && !country.isBlank();
        if (hasCountry) {
            sql.append(" AND UPPER(u.country) = UPPER(?) ");
        }

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            stmt.setInt(1, mode);
            if (hasCountry) {
                stmt.setString(2, country.trim());
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error counting rankings for mode {}: {}", mode, e.getMessage());
        }
        return 0;
    }

    public List<CountryRankingRecord> findCountryRankings(int mode, int page, int pageSize) {
        String sql = """
                SELECT UPPER(u.country) AS code,
                       COUNT(DISTINCT u.id) AS active_users,
                       COALESCE(SUM(s.plays), 0) AS play_count,
                       COALESCE(SUM(s.rscore), 0) AS ranked_score,
                       COALESCE(SUM(s.pp), 0) AS performance
                FROM users u
                JOIN stats s ON s.id = u.id AND s.mode = ?
                WHERE (u.priv & 1) = 1 AND (s.plays > 0 OR s.pp > 0 OR s.tscore > 0)
                  AND u.country IS NOT NULL AND u.country != '' AND u.country != 'XX'
                GROUP BY UPPER(u.country)
                ORDER BY performance DESC, ranked_score DESC
                LIMIT ? OFFSET ?
                """;

        int limit = Math.min(Math.max(pageSize, 1), 100);
        int offset = Math.max((page - 1) * limit, 0);

        List<CountryRankingRecord> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, mode);
            stmt.setInt(2, limit);
            stmt.setInt(3, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    CountryRankingRecord c = new CountryRankingRecord();
                    c.code = rs.getString("code");
                    c.activeUsers = rs.getLong("active_users");
                    c.playCount = rs.getLong("play_count");
                    c.rankedScore = rs.getLong("ranked_score");
                    c.performance = rs.getLong("performance");
                    list.add(c);
                }
            }
        } catch (SQLException e) {
            logger.error("Error finding country rankings for mode {}: {}", mode, e.getMessage());
        }
        return list;
    }

    public int countCountryRankings(int mode) {
        String sql = """
                SELECT COUNT(DISTINCT UPPER(u.country))
                FROM users u
                JOIN stats s ON s.id = u.id AND s.mode = ?
                WHERE (u.priv & 1) = 1 AND (s.plays > 0 OR s.pp > 0 OR s.tscore > 0)
                  AND u.country IS NOT NULL AND u.country != '' AND u.country != 'XX'
                """;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, mode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error counting country rankings for mode {}: {}", mode, e.getMessage());
        }
        return 0;
    }

    public List<Integer> findBeatmapsetIdsLocal(String query, int mode, int status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT set_id
                FROM maps
                WHERE set_id > 0
                """);
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            sql.append(" AND (artist LIKE ? OR title LIKE ? OR creator LIKE ? OR version LIKE ?) ");
        }
        if (mode >= 0) {
            sql.append(" AND mode = ? ");
        }
        if (status != Integer.MIN_VALUE && status != -999) {
            sql.append(" AND status = ? ");
        }
        sql.append(" GROUP BY set_id ORDER BY MAX(last_update) DESC LIMIT ? OFFSET ? ");

        List<Integer> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int p = 1;
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
            }
            if (mode >= 0) {
                stmt.setInt(p++, mode);
            }
            if (status != Integer.MIN_VALUE && status != -999) {
                stmt.setInt(p++, status);
            }
            stmt.setInt(p++, Math.min(Math.max(limit, 1), 100));
            stmt.setInt(p++, Math.max(offset, 0));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getInt("set_id"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error searching local beatmapsets: {}", e.getMessage());
        }
        return list;
    }

    public int countBeatmapsetsLocal(String query, int mode, int status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT set_id)
                FROM maps
                WHERE set_id > 0
                """);
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            sql.append(" AND (artist LIKE ? OR title LIKE ? OR creator LIKE ? OR version LIKE ?) ");
        }
        if (mode >= 0) {
            sql.append(" AND mode = ? ");
        }
        if (status != Integer.MIN_VALUE && status != -999) {
            sql.append(" AND status = ? ");
        }

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int p = 1;
            if (hasQuery) {
                String pattern = "%" + query.trim() + "%";
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
                stmt.setString(p++, pattern);
            }
            if (mode >= 0) {
                stmt.setInt(p++, mode);
            }
            if (status != Integer.MIN_VALUE && status != -999) {
                stmt.setInt(p++, status);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error counting local beatmapsets: {}", e.getMessage());
        }
        return 0;
    }

    public List<User> searchUsers(String query, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM users WHERE (priv & 1) = 1");
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            sql.append(" AND (name LIKE ? OR safe_name LIKE ?)");
        }
        sql.append(" ORDER BY latest_activity DESC, id ASC LIMIT ? OFFSET ?");

        List<User> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int p = 1;
            if (hasQuery) {
                String q = "%" + query.trim() + "%";
                stmt.setString(p++, q);
                stmt.setString(p++, q);
            }
            stmt.setInt(p++, Math.min(Math.max(limit, 1), 100));
            stmt.setInt(p++, Math.max(offset, 0));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapUser(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Error searching users with query '{}': {}", query, e.getMessage());
        }
        return list;
    }

    public int countSearchUsers(String query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM users WHERE (priv & 1) = 1");
        boolean hasQuery = query != null && !query.isBlank();
        if (hasQuery) {
            sql.append(" AND (name LIKE ? OR safe_name LIKE ?)");
        }
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            if (hasQuery) {
                String q = "%" + query.trim() + "%";
                stmt.setString(1, q);
                stmt.setString(2, q);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Error counting search users: {}", e.getMessage());
        }
        return 0;
    }

    public int countUsers() {
        String sql = "SELECT COUNT(*) FROM users WHERE (priv & 1) = 1";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Error counting users: {}", e.getMessage());
        }
        return 0;
    }

    public int countBeatmapsets() {
        String sql = "SELECT COUNT(DISTINCT set_id) FROM maps WHERE set_id > 0";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Error counting beatmapsets: {}", e.getMessage());
        }
        return 0;
    }

    public int countScores() {
        String sql = "SELECT COUNT(*) FROM scores";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            logger.error("Error counting scores: {}", e.getMessage());
        }
        return 0;
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
