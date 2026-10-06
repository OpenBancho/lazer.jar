package com.osuserverlist.lazer.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class BeatmapHandler {
    private static final Logger logger = LoggerFactory.getLogger(BeatmapHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DatabaseManager databaseManager;
    private final ServerConfig config;
    private final HttpClient httpClient;

    public BeatmapHandler(DatabaseManager databaseManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    public void handleSearchBeatmapsets(@NotNull Context ctx) {
        Map<String, List<String>> queryParams = ctx.queryParamMap();
        List<String> mirrorParams = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;
            String val = values.get(0);

            if ("s".equalsIgnoreCase(key)) {
                // Convert lazer category name to catboy status IDs
                switch (val.toLowerCase(Locale.ROOT)) {
                    case "leaderboard" -> mirrorParams.add("status=1,2,3,4");
                    case "ranked" -> mirrorParams.add("status=1,2");
                    case "qualified" -> mirrorParams.add("status=3");
                    case "loved" -> mirrorParams.add("status=4");
                    case "pending" -> mirrorParams.add("status=0");
                    case "wip" -> mirrorParams.add("status=-1");
                    case "graveyard" -> mirrorParams.add("status=-2");
                    case "any" -> {} // no status filter
                    default -> mirrorParams.add("status=" + URLEncoder.encode(val, StandardCharsets.UTF_8));
                }
            } else if ("cursor_string".equalsIgnoreCase(key)) {
                mirrorParams.add("offset=" + URLEncoder.encode(val, StandardCharsets.UTF_8));
            } else {
                for (String v : values) {
                    mirrorParams.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8));
                }
            }
        }

        String mirrorQueryString = String.join("&", mirrorParams);
        String mirrorUrl = config.directSearchV2 + (!mirrorQueryString.isBlank() ? "?" + mirrorQueryString : "");

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(mirrorUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "lazer.jar/" + config.serverVersion)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode rootNode = JSON.readTree(response.body());
                if (rootNode.isArray()) {
                    Map<String, Object> wrapped = new LinkedHashMap<>();
                    wrapped.put("beatmapsets", rootNode);
                    wrapped.put("total", rootNode.size() >= 50 ? 1000 : rootNode.size());
                    wrapped.put("cursor", null);
                    wrapped.put("cursor_string", null);
                    ctx.status(200).json(wrapped);
                    return;
                } else if (rootNode.isObject()) {
                    ctx.status(200);
                    ctx.contentType("application/json");
                    ctx.result(response.body());
                    return;
                }
            }
        } catch (Exception e) {
            logger.warn("Beatmap mirror search failed ({}): {}. Falling back to local database.", mirrorUrl, e.getMessage());
        }

        // Fallback: search local database maps
        String q = ctx.queryParam("q");
        String mParam = ctx.queryParam("m");
        int mode = -1;
        if (mParam != null && !mParam.isBlank()) {
            try {
                mode = Integer.parseInt(mParam.trim());
            } catch (NumberFormatException ignored) {}
        }

        int limit = 50;
        int offset = 0;
        String cursorStr = ctx.queryParam("cursor_string");
        if (cursorStr != null && !cursorStr.isBlank()) {
            try {
                offset = Integer.parseInt(cursorStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        List<Integer> setIds = databaseManager.findBeatmapsetIdsLocal(q, mode, Integer.MIN_VALUE, limit, offset);
        int total = databaseManager.countBeatmapsetsLocal(q, mode, Integer.MIN_VALUE);

        List<Map<String, Object>> beatmapsets = new ArrayList<>();
        for (int setId : setIds) {
            List<BeatmapRecord> maps = databaseManager.findBeatmapsBySetId(setId);
            if (!maps.isEmpty()) {
                beatmapsets.add(formatBeatmapset(setId, maps));
            }
        }

        Map<String, Object> fallbackResp = new LinkedHashMap<>();
        fallbackResp.put("beatmapsets", beatmapsets);
        fallbackResp.put("total", total);
        fallbackResp.put("cursor", null);
        fallbackResp.put("cursor_string", (offset + limit < total) ? String.valueOf(offset + limit) : null);

        ctx.status(200).json(fallbackResp);
    }

    public void handleDownloadBeatmapset(@NotNull Context ctx) {
        String setIdParam = ctx.pathParam("beatmapset_id");
        int setId;
        try {
            setId = Integer.parseInt(setIdParam);
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid beatmapset_id"));
            return;
        }

        boolean noVideo = "1".equals(ctx.queryParam("noVideo")) || "true".equalsIgnoreCase(ctx.queryParam("noVideo"));
        String dlUrl = config.directDownload + "/" + setId + (noVideo ? "?noVideo=1" : "");

        ctx.redirect(dlUrl);
    }

    public void handleLegacyDownload(@NotNull Context ctx) {
        String rawId = ctx.pathParam("id");
        boolean noVideo = rawId.endsWith("n") || rawId.endsWith("N");
        String setId = noVideo ? rawId.substring(0, rawId.length() - 1) : rawId;

        String dlUrl = config.directDownload + "/" + setId + (noVideo ? "?noVideo=1" : "");
        ctx.redirect(dlUrl);
    }

    public void handleLegacySearch(@NotNull Context ctx) {
        String queryString = ctx.queryString();
        String mirrorUrl = config.directSearch + (queryString != null && !queryString.isBlank() ? "?" + queryString : "");

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(mirrorUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "lazer.jar/" + config.serverVersion)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                ctx.status(200);
                ctx.contentType("text/plain");
                ctx.result(response.body());
                return;
            }
        } catch (Exception e) {
            logger.warn("Legacy search mirror failed ({}): {}", mirrorUrl, e.getMessage());
        }

        ctx.contentType("text/plain");
        ctx.result("0\n");
    }

    public void handleLegacySearchSet(@NotNull Context ctx) {
        String sParam = ctx.queryParam("s");
        String bParam = ctx.queryParam("b");
        String cParam = ctx.queryParam("c");

        BeatmapRecord bm = null;
        if (sParam != null && !sParam.isBlank()) {
            try {
                int setId = Integer.parseInt(sParam.trim());
                List<BeatmapRecord> maps = databaseManager.findBeatmapsBySetId(setId);
                if (!maps.isEmpty()) bm = maps.get(0);
            } catch (NumberFormatException ignored) {}
        } else if (bParam != null && !bParam.isBlank()) {
            try {
                int bmId = Integer.parseInt(bParam.trim());
                bm = databaseManager.findBeatmapById(bmId);
            } catch (NumberFormatException ignored) {}
        } else if (cParam != null && !cParam.isBlank()) {
            bm = databaseManager.findBeatmapByMd5(cParam.trim());
        }

        if (bm == null) {
            ctx.contentType("text/plain").result("");
            return;
        }

        String response = String.format(
                Locale.US,
                "%d.osz|%s|%s|%s|%d|%.1f|2026-01-01 00:00:00|%d|0|0|0|0|0",
                bm.setId,
                bm.artist.replace("|", "I"),
                bm.title.replace("|", "I"),
                bm.creator.replace("|", "I"),
                bm.status,
                bm.diff,
                bm.setId
        );

        ctx.contentType("text/plain").result(response);
    }

    public void handleGetBeatmapset(@NotNull Context ctx) {
        int beatmapsetId;
        try {
            beatmapsetId = Integer.parseInt(ctx.pathParam("beatmapset_id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid beatmapset_id"));
            return;
        }

        List<BeatmapRecord> maps = databaseManager.findBeatmapsBySetId(beatmapsetId);
        if (maps.isEmpty()) {
            // Attempt to fetch from osu! API or mirror if not in database
            databaseManager.fetchAndSaveBeatmapSet(beatmapsetId);
            maps = databaseManager.findBeatmapsBySetId(beatmapsetId);
        }

        if (maps.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Beatmapset not found"));
            return;
        }

        Map<String, Object> resp = formatBeatmapset(beatmapsetId, maps);
        ctx.status(200).json(resp);
    }

    public void handleLookupBeatmapset(@NotNull Context ctx) {
        String beatmapIdStr = ctx.queryParam("beatmap_id");
        if (beatmapIdStr != null && !beatmapIdStr.isBlank()) {
            try {
                int beatmapId = Integer.parseInt(beatmapIdStr.trim());
                BeatmapRecord bm = databaseManager.findBeatmapById(beatmapId);
                if (bm != null && bm.setId > 0) {
                    List<BeatmapRecord> maps = databaseManager.findBeatmapsBySetId(bm.setId);
                    ctx.status(200).json(formatBeatmapset(bm.setId, maps));
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        String checksum = ctx.queryParam("checksum");
        if (checksum != null && !checksum.isBlank()) {
            BeatmapRecord bm = databaseManager.findBeatmapByMd5(checksum.trim());
            if (bm != null && bm.setId > 0) {
                List<BeatmapRecord> maps = databaseManager.findBeatmapsBySetId(bm.setId);
                ctx.status(200).json(formatBeatmapset(bm.setId, maps));
                return;
            }
        }

        ctx.status(404).json(Map.of("error", "Beatmapset not found"));
    }

    public void handleGetBeatmaps(@NotNull Context ctx) {
        List<String> ids = ctx.queryParams("ids[]");
        if (ids.isEmpty()) {
            ids = ctx.queryParams("ids");
        }
        if (ids.isEmpty()) {
            String idParam = ctx.queryParam("id");
            if (idParam != null && !idParam.isBlank()) {
                ids = List.of(idParam);
            }
        }

        List<Map<String, Object>> beatmapsList = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (String s : ids) {
                try {
                    int bId = Integer.parseInt(s.trim());
                    BeatmapRecord bm = databaseManager.findBeatmapById(bId);
                    if (bm != null) {
                        beatmapsList.add(formatSingleBeatmap(bm));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        String checksum = ctx.queryParam("checksum");
        if (checksum != null && !checksum.isBlank()) {
            BeatmapRecord bm = databaseManager.findBeatmapByMd5(checksum.trim());
            if (bm != null) {
                beatmapsList.add(formatSingleBeatmap(bm));
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("beatmaps", beatmapsList);
        resp.put("cursor", null);
        resp.put("cursor_string", null);
        ctx.status(200).json(resp);
    }

    public void handleGetBeatmap(@NotNull Context ctx) {
        int beatmapId;
        try {
            beatmapId = Integer.parseInt(ctx.pathParam("beatmap_id"));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid beatmap_id"));
            return;
        }

        BeatmapRecord bm = databaseManager.findBeatmapById(beatmapId);
        if (bm == null) {
            ctx.status(404).json(Map.of("error", "Beatmap not found"));
            return;
        }

        ctx.status(200).json(formatSingleBeatmap(bm));
    }

    public void handleLookupBeatmap(@NotNull Context ctx) {
        String idStr = ctx.queryParam("id");
        if (idStr != null && !idStr.isBlank()) {
            try {
                int beatmapId = Integer.parseInt(idStr.trim());
                BeatmapRecord bm = databaseManager.findBeatmapById(beatmapId);
                if (bm != null) {
                    ctx.status(200).json(formatSingleBeatmap(bm));
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        String checksum = ctx.queryParam("checksum");
        if (checksum != null && !checksum.isBlank()) {
            BeatmapRecord bm = databaseManager.findBeatmapByMd5(checksum.trim());
            if (bm != null) {
                ctx.status(200).json(formatSingleBeatmap(bm));
                return;
            }
        }

        ctx.status(404).json(Map.of("error", "Beatmap not found"));
    }

    public Map<String, Object> formatBeatmapset(int setId, List<BeatmapRecord> maps) {
        BeatmapRecord main = maps.get(0);

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("id", setId);
        set.put("artist", main.artist);
        set.put("artist_unicode", main.artist);
        set.put("title", main.title);
        set.put("title_unicode", main.title);
        set.put("creator", main.creator);
        set.put("source", "");
        set.put("status", statusToString(main.status));
        set.put("bpm", (double) main.bpm);
        set.put("nsfw", false);
        set.put("spotlight", false);
        set.put("video", false);
        set.put("storyboard", false);
        set.put("has_favourited", false);
        set.put("play_count", 0);
        set.put("favourite_count", 0);
        set.put("submitted_date", "2026-01-01T00:00:00Z");
        set.put("ranked_date", "2026-01-01T00:00:00Z");
        set.put("last_updated", "2026-01-01T00:00:00Z");
        set.put("preview_url", "https://b.ppy.sh/preview/" + setId + ".mp3");

        Map<String, String> covers = new LinkedHashMap<>();
        covers.put("cover", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/cover.jpg");
        covers.put("cover@2x", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/cover@2x.jpg");
        covers.put("card", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/card.jpg");
        covers.put("card@2x", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/card@2x.jpg");
        covers.put("list", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/list.jpg");
        covers.put("list@2x", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/list@2x.jpg");
        covers.put("slimcover", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/slimcover.jpg");
        covers.put("slimcover@2x", "https://assets.ppy.sh/beatmaps/" + setId + "/covers/slimcover@2x.jpg");
        set.put("covers", covers);

        List<Map<String, Object>> beatmapsList = new ArrayList<>();
        for (BeatmapRecord bm : maps) {
            Map<String, Object> b = formatSingleBeatmapNoSet(bm);
            beatmapsList.add(b);
        }
        set.put("beatmaps", beatmapsList);

        return set;
    }

    public Map<String, Object> formatSingleBeatmap(BeatmapRecord bm) {
        Map<String, Object> b = formatSingleBeatmapNoSet(bm);
        List<BeatmapRecord> setMaps = databaseManager.findBeatmapsBySetId(bm.setId);
        if (setMaps.isEmpty()) {
            setMaps = List.of(bm);
        }
        b.put("beatmapset", formatBeatmapset(bm.setId, setMaps));
        return b;
    }

    public Map<String, Object> formatSingleBeatmapNoSet(BeatmapRecord bm) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", bm.id);
        b.put("beatmapset_id", bm.setId);
        b.put("status", statusToString(bm.status));
        b.put("version", bm.version);
        b.put("mode", modeToString(bm.mode));
        b.put("mode_int", bm.mode);
        b.put("difficulty_rating", (double) bm.diff);
        b.put("bpm", (double) bm.bpm);
        b.put("cs", (double) bm.cs);
        b.put("ar", (double) bm.ar);
        b.put("accuracy", (double) bm.od);
        b.put("drain", (double) bm.hp);
        b.put("total_length", bm.totalLength);
        b.put("hit_length", bm.totalLength);
        b.put("max_combo", bm.maxCombo);
        b.put("checksum", bm.md5);
        b.put("convert", false);
        b.put("url", "https://osu.ppy.sh/b/" + bm.id);
        return b;
    }

    public static String statusToString(int status) {
        return switch (status) {
            case 4 -> "loved";
            case 3 -> "qualified";
            case 2 -> "approved";
            case 1 -> "ranked";
            case 0 -> "pending";
            case -1 -> "wip";
            case -2 -> "graveyard";
            default -> (status > 0) ? "ranked" : "graveyard";
        };
    }

    public static String modeToString(int mode) {
        return switch (mode) {
            case 1 -> "taiko";
            case 2 -> "fruits";
            case 3 -> "mania";
            default -> "osu";
        };
    }
}
