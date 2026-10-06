package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.models.UserStatistics;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class UserResponseBuilder {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    public static Map<String, Object> buildUserResponse(User user, UserStatistics stats, int mode, ServerConfig config) {
        return buildUserResponse(user, stats, mode, config, 0, new com.osuserverlist.lazer.database.DatabaseManager.ScoreCounts(), false);
    }

    public static Map<String, Object> buildUserResponse(User user, UserStatistics stats, int mode, ServerConfig config, int followerCount) {
        return buildUserResponse(user, stats, mode, config, followerCount, new com.osuserverlist.lazer.database.DatabaseManager.ScoreCounts(), false);
    }

    public static Map<String, Object> buildUserResponse(User user, UserStatistics stats, int mode, ServerConfig config, int followerCount, com.osuserverlist.lazer.database.DatabaseManager.ScoreCounts scoreCounts) {
        return buildUserResponse(user, stats, mode, config, followerCount, scoreCounts, false);
    }

    public static Map<String, Object> buildUserResponse(User user, UserStatistics stats, int mode, ServerConfig config, int followerCount, com.osuserverlist.lazer.database.DatabaseManager.ScoreCounts scoreCounts, boolean isOnline) {
        Map<String, Object> root = new LinkedHashMap<>();

        root.put("id", user.id);
        root.put("username", user.name);
        String countryCode = (user.country != null && !user.country.isBlank()) ? user.country.toUpperCase() : "XX";
        root.put("country_code", countryCode);

        Map<String, Object> country = new LinkedHashMap<>();
        country.put("code", countryCode);
        country.put("name", countryCode);
        root.put("country", country);

        String avatarUrl = getAvatarUrl(user.id, config);
        root.put("avatar_url", avatarUrl);
        root.put("custom_avatar_url", avatarUrl);

        String coverUrl = getCoverUrl(user.customBanner, config);

        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("custom_url", coverUrl);
        cover.put("url", coverUrl);
        cover.put("id", null);
        root.put("cover", cover);
        root.put("cover_url", coverUrl);

        boolean isSupporter = user.donorEnd > (System.currentTimeMillis() / 1000);
        boolean isAdmin = (user.privileges & 32) != 0;

        root.put("is_active", true);
        root.put("is_bot", false);
        root.put("is_deleted", false);
        root.put("is_online", isOnline);
        root.put("is_supporter", isSupporter);
        root.put("support_level", isSupporter ? 1 : 0);
        root.put("is_admin", isAdmin);
        root.put("pm_friends_only", false);
        root.put("profile_colour", null);

        long creationSec = user.creationTime > 0 ? user.creationTime : (System.currentTimeMillis() / 1000);
        String joinDate = ISO_FORMATTER.format(Instant.ofEpochSecond(creationSec));
        root.put("join_date", joinDate);

        if (isOnline) {
            root.put("last_visit", ISO_FORMATTER.format(Instant.now()));
        } else if (user.latestActivity > 0) {
            root.put("last_visit", ISO_FORMATTER.format(Instant.ofEpochSecond(user.latestActivity)));
        } else if (user.creationTime > 0) {
            root.put("last_visit", ISO_FORMATTER.format(Instant.ofEpochSecond(user.creationTime)));
        } else {
            root.put("last_visit", null);
        }

        root.put("max_blocks", 50);
        root.put("max_friends", 250);
        root.put("follower_count", followerCount);
        root.put("mapping_follower_count", 0);
        root.put("playmode", modeToString(mode));

        // Profile sections order in osu!(lazer)
        root.put("profile_order", java.util.List.of("me", "top_ranks", "historical", "beatmaps", "recent_activity", "medals", "kudosu"));

        // Score & section counters for subsections in ProfileOverlay
        root.put("scores_best_count", scoreCounts.best);
        root.put("scores_first_count", scoreCounts.firsts);
        root.put("scores_recent_count", scoreCounts.recent);
        root.put("scores_pinned_count", scoreCounts.pinned);

        root.put("beatmap_playcounts_count", 0);
        root.put("favourite_beatmapset_count", 0);
        root.put("graveyard_beatmapset_count", 0);
        root.put("loved_beatmapset_count", 0);
        root.put("ranked_beatmapset_count", 0);
        root.put("pending_beatmapset_count", 0);
        root.put("guest_beatmapset_count", 0);
        root.put("nominated_beatmapset_count", 0);
        root.put("comments_count", 0);
        root.put("post_count", 0);
        root.put("kudosu", Map.of("total", 0, "available", 0));
        root.put("monthly_playcounts", Collections.emptyList());
        root.put("replays_watched_counts", Collections.emptyList());

        Map<String, Object> rankHistory = new LinkedHashMap<>();
        rankHistory.put("mode", modeToString(mode));
        rankHistory.put("data", Collections.emptyList());
        root.put("rank_history", rankHistory);

        // Statistics
        Map<String, Object> statsMap = new LinkedHashMap<>();
        Map<String, Object> levelMap = new LinkedHashMap<>();
        levelMap.put("current", stats.level);
        levelMap.put("progress", (int) Math.round(stats.levelProgress));
        statsMap.put("level", levelMap);

        statsMap.put("global_rank", stats.globalRank);
        statsMap.put("country_rank", stats.countryRank);
        statsMap.put("pp", stats.pp);
        double hitAccuracy = stats.accuracy <= 1.0f ? (stats.accuracy * 100.0) : stats.accuracy;
        statsMap.put("hit_accuracy", hitAccuracy);
        statsMap.put("play_count", stats.plays);
        statsMap.put("play_time", stats.playTime);
        statsMap.put("total_score", stats.totalScore);
        statsMap.put("ranked_score", stats.rankedScore);
        statsMap.put("total_hits", stats.totalHits);
        statsMap.put("maximum_combo", stats.maxCombo);
        statsMap.put("replays_watched_by_others", stats.replayViews);
        statsMap.put("is_ranked", true);

        Map<String, Object> grades = new LinkedHashMap<>();
        grades.put("ss", stats.xCount);
        grades.put("ssh", stats.xhCount);
        grades.put("s", stats.sCount);
        grades.put("sh", stats.shCount);
        grades.put("a", stats.aCount);
        statsMap.put("grade_counts", grades);

        root.put("statistics", statsMap);

        if (stats.globalRank != null && stats.globalRank > 0) {
            Map<String, Object> globalRankObj = new LinkedHashMap<>();
            globalRankObj.put("rank", stats.globalRank);
            globalRankObj.put("ruleset_id", mode);
            root.put("global_rank", globalRankObj);
            root.put("rank", stats.globalRank);
        } else {
            root.put("global_rank", null);
            root.put("rank", null);
        }

        root.put("session_verification_method", null);
        root.put("score_processing_notice_url", "");
        root.put("previous_usernames", Collections.emptyList());

        // Custom badges & Staff group
        java.util.List<Map<String, Object>> badges = new java.util.ArrayList<>();
        if (user.customBadgeName != null && !user.customBadgeName.isBlank()) {
            Map<String, Object> badge = new LinkedHashMap<>();
            badge.put("description", user.customBadgeName);
            String iconUrl = user.customBadgeIcon != null ? user.customBadgeIcon.trim() : "";
            if (!iconUrl.startsWith("http://") && !iconUrl.startsWith("https://") && !iconUrl.isBlank()) {
                iconUrl = "https://" + config.domain + (iconUrl.startsWith("/") ? "" : "/") + iconUrl;
            }
            badge.put("image_url", iconUrl);
            badge.put("image@2x_url", iconUrl);
            badge.put("url", "");
            badge.put("awarded_at", joinDate);
            badges.add(badge);
        }
        root.put("badges", badges);

        java.util.List<Map<String, Object>> groups = new java.util.ArrayList<>();
        if (isAdmin) {
            Map<String, Object> staffGroup = new LinkedHashMap<>();
            staffGroup.put("id", 1);
            staffGroup.put("identifier", "staff");
            staffGroup.put("name", "Staff");
            staffGroup.put("short_name", "STAFF");
            staffGroup.put("colour", "#ff66aa");
            staffGroup.put("has_listing", false);
            staffGroup.put("has_playmodes", false);
            groups.add(staffGroup);
        }
        root.put("groups", groups);

        root.put("server_name", config.serverName);
        root.put("server_version", config.serverVersion);

        return root;
    }

    public static Map<String, Object> formatScore(com.osuserverlist.lazer.models.ScoreRecord sc, ServerConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", sc.id);
        map.put("user_id", sc.userId);
        map.put("beatmap_id", sc.mapId);
        map.put("ruleset_id", sc.mode);
        long displayScore = sc.lazerScore > 0 ? sc.lazerScore : sc.score;
        map.put("total_score", displayScore);
        map.put("total_score_without_mods", displayScore);
        map.put("legacy_total_score", sc.score);
        map.put("accuracy", sc.acc > 1.0f ? (double) sc.acc / 100.0 : (double) sc.acc);
        map.put("pp", (double) sc.pp);
        map.put("max_combo", sc.maxCombo);
        map.put("rank", sc.grade != null && !sc.grade.isBlank() ? sc.grade.toUpperCase() : "D");
        map.put("passed", !"F".equalsIgnoreCase(sc.grade));

        Map<String, Object> userObj = new LinkedHashMap<>();
        userObj.put("id", sc.userId);
        userObj.put("username", sc.username != null && !sc.username.isBlank() ? sc.username : "User " + sc.userId);
        userObj.put("country_code", sc.country != null && !sc.country.isBlank() ? sc.country.toUpperCase() : "XX");
        userObj.put("avatar_url", getAvatarUrl(sc.userId, config));
        userObj.put("cover_url", getCoverUrl(null, config));
        map.put("user", userObj);

        // Stable scores (lazerScore == 0) always get the Classic mod prepended,
        // matching official osu! server behaviour where all legacy scores carry CL.
        boolean isStableScore = sc.lazerScore == 0;
        map.put("mods", parseMods(sc.mods, isStableScore));

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("great", sc.n300);
        statistics.put("ok", sc.n100);
        statistics.put("meh", sc.n50);
        statistics.put("miss", sc.nmiss);
        statistics.put("perfect", sc.ngeki);
        statistics.put("good", sc.nkatu);
        map.put("statistics", statistics);

        Map<String, Object> maxStats = new LinkedHashMap<>();
        int mapMax = sc.mapMaxCombo > 0 ? sc.mapMaxCombo : (sc.n300 + sc.n100 + sc.n50 + sc.nmiss);
        if (mapMax > 0) {
            maxStats.put("great", mapMax);
        }
        map.put("maximum_statistics", maxStats);

        String endedAt = ISO_FORMATTER.format(Instant.ofEpochSecond(sc.playTimeEpochSec));
        map.put("started_at", endedAt);
        map.put("ended_at", endedAt);
        map.put("created_at", endedAt);
        map.put("has_replay", false);
        boolean isRanked = sc.mapStatus > 0;
        map.put("ranked", isRanked);
        map.put("preserve", sc.status == 2 || sc.pp > 0);
        map.put("processed", true);

        Map<String, Object> bm = new LinkedHashMap<>();
        bm.put("id", sc.mapId);
        bm.put("beatmapset_id", sc.setId);
        bm.put("version", sc.version);
        bm.put("difficulty_rating", (double) sc.diff);
        bm.put("status", BeatmapHandler.statusToString(sc.mapStatus));
        bm.put("total_length", sc.totalLength);
        bm.put("bpm", (double) sc.bpm);
        bm.put("cs", (double) sc.cs);
        bm.put("ar", (double) sc.ar);
        bm.put("drain", (double) sc.hp);
        bm.put("accuracy", (double) sc.od);
        bm.put("max_combo", sc.mapMaxCombo > 0 ? sc.mapMaxCombo : (mapMax > 0 ? mapMax : null));
        bm.put("checksum", sc.mapMd5 != null ? sc.mapMd5 : "");
        bm.put("mode_int", sc.mode % 4);

        Map<String, Object> bms = new LinkedHashMap<>();
        bms.put("id", sc.setId);
        bms.put("artist", sc.artist);
        bms.put("title", sc.title);
        bms.put("creator", sc.creator);
        bms.put("status", BeatmapHandler.statusToString(sc.mapStatus));

        Map<String, Object> covers = new LinkedHashMap<>();
        String baseCover = "https://assets.ppy.sh/beatmaps/" + sc.setId + "/covers/";
        covers.put("cover", baseCover + "cover.jpg");
        covers.put("card", baseCover + "card.jpg");
        covers.put("list", baseCover + "list.jpg");
        covers.put("slimcover", baseCover + "slimcover.jpg");
        bms.put("covers", covers);

        bm.put("beatmapset", bms);
        map.put("beatmap", bm);
        map.put("beatmapset", bms);

        return map;
    }

    /**
     * Converts a legacy mod bitmask to the list format expected by the Lazer API.
     * When {@code addClassic} is true, prepends a Classic (CL) mod entry — this is
     * used for scores submitted from osu!stable to match official osu! server behaviour.
     */
    public static java.util.List<Map<String, Object>> parseMods(int mods, boolean addClassic) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        // Classic mod is always first, matching official osu! API ordering.
        if (addClassic) list.add(Map.of("acronym", "CL"));
        if ((mods & 1) != 0) list.add(Map.of("acronym", "NF"));
        if ((mods & 2) != 0) list.add(Map.of("acronym", "EZ"));
        if ((mods & 4) != 0) list.add(Map.of("acronym", "TD"));
        if ((mods & 8) != 0) list.add(Map.of("acronym", "HD"));
        if ((mods & 16) != 0) list.add(Map.of("acronym", "HR"));
        if ((mods & 32) != 0) list.add(Map.of("acronym", "SD"));
        if ((mods & 512) != 0) list.add(Map.of("acronym", "NC"));
        else if ((mods & 64) != 0) list.add(Map.of("acronym", "DT"));
        if ((mods & 128) != 0) list.add(Map.of("acronym", "RX"));
        if ((mods & 256) != 0) list.add(Map.of("acronym", "HT"));
        if ((mods & 1024) != 0) list.add(Map.of("acronym", "FL"));
        if ((mods & 4096) != 0) list.add(Map.of("acronym", "SO"));
        if ((mods & 8192) != 0) list.add(Map.of("acronym", "AP"));
        if ((mods & 16384) != 0) list.add(Map.of("acronym", "PF"));
        return list;
    }

    /** Convenience overload — no Classic mod injection (used for Lazer scores). */
    public static java.util.List<Map<String, Object>> parseMods(int mods) {
        return parseMods(mods, false);
    }

    public static int parseMode(String ruleset) {
        if (ruleset == null) return 0;
        return switch (ruleset.toLowerCase().trim()) {
            case "taiko" -> 1;
            case "fruits", "catch" -> 2;
            case "mania" -> 3;
            case "rx", "relax", "osu!rx", "rx!std" -> 4;
            case "rx!taiko", "taiko!rx" -> 5;
            case "rx!catch", "rx!fruits", "catch!rx" -> 6;
            case "rx!mania", "mania!rx" -> 7;
            case "ap", "autopilot", "osu!ap", "ap!std" -> 8;
            default -> 0;
        };
    }

    public static String modeToString(int mode) {
        return switch (mode) {
            case 1 -> "taiko";
            case 2 -> "fruits";
            case 3 -> "mania";
            case 4 -> "rx";
            case 5 -> "rx!taiko";
            case 6 -> "rx!fruits";
            case 7 -> "rx!mania";
            case 8 -> "ap";
            default -> "osu";
        };
    }

    public static int getEffectiveMode(int baseRulesetId, int modsBitmask) {
        int modeVn = baseRulesetId % 4;
        boolean hasAutopilot = (modsBitmask & 8192) != 0; // AP / Relax2
        boolean hasRelax = (modsBitmask & 128) != 0; // RX / Relax

        if (hasAutopilot && modeVn == 0) {
            return 8; // AP!std
        } else if (hasRelax) {
            return modeVn + 4; // 4: rx!std, 5: rx!taiko, 6: rx!catch, 7: rx!mania
        }
        return modeVn;
    }

    public static long convertStandardisedToClassic(int rulesetId, long standardisedScore, int objectCount) {
        if (objectCount <= 0) {
            objectCount = 100;
        }
        int baseMode = rulesetId % 4;
        return switch (baseMode) {
            case 0 -> Math.round((Math.pow(objectCount, 2) * 32.57 + 100000.0) * (double) standardisedScore / 1000000.0);
            case 1 -> Math.round(((double) objectCount * 1109.0 + 100000.0) * (double) standardisedScore / 1000000.0);
            case 2 -> Math.round(Math.pow((double) standardisedScore / 1000000.0 * (double) objectCount, 2) * 21.62 + (double) standardisedScore / 10.0);
            default -> standardisedScore;
        };
    }

    public static int modsToInt(java.util.List<?> modsList) {
        if (modsList == null || modsList.isEmpty()) return 0;
        int bitmask = 0;
        for (Object item : modsList) {
            String acronym = "";
            if (item instanceof Map<?, ?> m) {
                Object ac = m.get("acronym");
                if (ac != null) acronym = ac.toString();
            } else if (item instanceof String s) {
                acronym = s;
            }
            acronym = acronym.toUpperCase();
            switch (acronym) {
                case "NF" -> bitmask |= 1;
                case "EZ" -> bitmask |= 2;
                case "TD" -> bitmask |= 4;
                case "HD" -> bitmask |= 8;
                case "HR" -> bitmask |= 16;
                case "SD" -> bitmask |= 32;
                case "DT" -> bitmask |= 64;
                case "RX" -> bitmask |= 128;
                case "HT" -> bitmask |= 256;
                case "NC" -> bitmask |= (512 | 64);
                case "FL" -> bitmask |= 1024;
                case "AT" -> bitmask |= 2048;
                case "SO" -> bitmask |= 4096;
                case "AP" -> bitmask |= 8192;
                case "PF" -> bitmask |= (16384 | 32);
                case "4K" -> bitmask |= 32768;
                case "5K" -> bitmask |= 65536;
                case "6K" -> bitmask |= 131072;
                case "7K" -> bitmask |= 262144;
                case "8K" -> bitmask |= 524288;
                case "FI" -> bitmask |= 1048576;
                case "RD" -> bitmask |= 2097152;
                case "CN" -> bitmask |= 4194304;
                case "TP" -> bitmask |= 8388608;
                case "9K" -> bitmask |= 16777216;
                case "CO" -> bitmask |= 33554432;
                case "1K" -> bitmask |= 67108864;
                case "3K" -> bitmask |= 134217728;
                case "2K" -> bitmask |= 268435456;
                case "SV2" -> bitmask |= 536870912;
                case "MR" -> bitmask |= 1073741824;
            }
        }
        return bitmask;
    }

    public static boolean isRankedMod(String acronym, int rulesetId) {
        if (acronym == null || acronym.isBlank()) return true;
        String ac = acronym.toUpperCase().trim();
        int baseMode = rulesetId % 4;
        return switch (ac) {
            case "NM", "NF", "EZ", "TD", "HD", "HR", "SD", "DT", "HT", "NC", "FL", "SO", "PF", "CL", "RX", "AP" -> true;
            case "FI", "1K", "2K", "3K", "4K", "5K", "6K", "7K", "8K", "9K" -> baseMode == 3;
            case "MR" -> baseMode == 3;
            default -> false;
        };
    }

    public static boolean isScoreRankedForPp(java.util.List<?> modsList, int modsBitmask, int rulesetId) {
        // Genuinely unranked mods: AT(2048), RD(2097152), CN(4194304), TP(8388608), SV2(536870912)
        int unrankedBitmask = 2048 | 2097152 | 4194304 | 8388608 | 536870912;
        if ((modsBitmask & unrankedBitmask) != 0) {
            return false;
        }

        int baseMode = rulesetId % 4;
        // Mirror is only ranked in mania
        if ((modsBitmask & 1073741824) != 0 && baseMode != 3) {
            return false;
        }

        if (modsList != null && !modsList.isEmpty()) {
            for (Object item : modsList) {
                String acronym = "";
                if (item instanceof Map<?, ?> m) {
                    Object ac = m.get("acronym");
                    if (ac != null) acronym = ac.toString();
                    Object rankedObj = m.get("ranked");
                    if (rankedObj instanceof Boolean b && !b) {
                        return false;
                    }
                } else if (item instanceof String s) {
                    acronym = s;
                }
                if (!isRankedMod(acronym, rulesetId)) {
                    return false;
                }
            }
        }

        return true;
    }

    public static String getAvatarUrl(int userId, ServerConfig config) {
        boolean isLocal = config.domain.startsWith("localhost") || config.domain.startsWith("127.0.0.1");
        return isLocal
                ? String.format("http://%s/a/%d", config.domain, userId)
                : String.format("https://a.%s/%d", config.domain, userId);
    }

    public static String getCoverUrl(String customBanner, ServerConfig config) {
        if (customBanner != null && !customBanner.isBlank()) {
            String banner = customBanner.trim();
            boolean isLocal = config.domain.startsWith("localhost") || config.domain.startsWith("127.0.0.1");
            String scheme = isLocal ? "http://" : "https://";
            if (banner.startsWith("http://") || banner.startsWith("https://")) {
                return banner;
            } else if (banner.startsWith("/")) {
                return scheme + config.domain + banner;
            } else {
                return scheme + config.domain + "/api/v1/banner/" + banner;
            }
        }
        return "https://assets.ppy.sh/user-profile-covers/default.jpg";
    }

    public static Map<String, Object> formatRankingUser(User user, UserStatistics stats, int mode, int rank, ServerConfig config) {
        Map<String, Object> item = new LinkedHashMap<>();

        Map<String, Object> userObj = new LinkedHashMap<>();
        userObj.put("id", user.id);
        userObj.put("username", user.name);
        String countryCode = (user.country != null && !user.country.isBlank()) ? user.country.toUpperCase() : "XX";
        userObj.put("country_code", countryCode);

        Map<String, Object> country = new LinkedHashMap<>();
        country.put("code", countryCode);
        country.put("name", countryCode);
        userObj.put("country", country);

        String avatarUrl = getAvatarUrl(user.id, config);
        userObj.put("avatar_url", avatarUrl);
        userObj.put("custom_avatar_url", avatarUrl);

        String coverUrl = getCoverUrl(user.customBanner, config);
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("custom_url", coverUrl);
        cover.put("url", coverUrl);
        cover.put("id", null);
        userObj.put("cover", cover);
        userObj.put("cover_url", coverUrl);

        boolean isSupporter = user.donorEnd > (System.currentTimeMillis() / 1000);
        userObj.put("is_active", true);
        userObj.put("is_bot", false);
        userObj.put("is_deleted", false);
        userObj.put("is_online", true);
        userObj.put("is_supporter", isSupporter);
        userObj.put("pm_friends_only", false);
        userObj.put("profile_colour", null);
        userObj.put("default_group", "default");

        item.put("user", userObj);

        item.put("global_rank", rank);
        item.put("country_rank", stats.countryRank != null && stats.countryRank > 0 ? stats.countryRank : rank);
        item.put("pp", stats.pp);
        item.put("ranked_score", stats.rankedScore);
        item.put("total_score", stats.totalScore);
        double hitAccuracy = stats.accuracy <= 1.0f ? (stats.accuracy * 100.0) : stats.accuracy;
        item.put("hit_accuracy", hitAccuracy);
        item.put("play_count", stats.plays);
        item.put("play_time", stats.playTime);
        item.put("total_hits", stats.totalHits);
        item.put("maximum_combo", stats.maxCombo);
        item.put("replays_watched_by_others", stats.replayViews);
        item.put("is_ranked", true);

        Map<String, Object> levelMap = new LinkedHashMap<>();
        levelMap.put("current", stats.level);
        levelMap.put("progress", (int) Math.round(stats.levelProgress));
        item.put("level", levelMap);

        Map<String, Object> grades = new LinkedHashMap<>();
        grades.put("ss", stats.xCount);
        grades.put("ssh", stats.xhCount);
        grades.put("s", stats.sCount);
        grades.put("sh", stats.shCount);
        grades.put("a", stats.aCount);
        item.put("grade_counts", grades);

        return item;
    }
}
