package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.BeatmapRecord;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class BeatmapHandler {
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public BeatmapHandler(DatabaseManager databaseManager, ServerConfig config) {
        this.databaseManager = databaseManager;
        this.config = config;
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

    private Map<String, Object> formatBeatmapset(int setId, List<BeatmapRecord> maps) {
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

    private Map<String, Object> formatSingleBeatmap(BeatmapRecord bm) {
        Map<String, Object> b = formatSingleBeatmapNoSet(bm);
        List<BeatmapRecord> setMaps = databaseManager.findBeatmapsBySetId(bm.setId);
        if (setMaps.isEmpty()) {
            setMaps = List.of(bm);
        }
        b.put("beatmapset", formatBeatmapset(bm.setId, setMaps));
        return b;
    }

    private Map<String, Object> formatSingleBeatmapNoSet(BeatmapRecord bm) {
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

    private String statusToString(int status) {
        return switch (status) {
            case 1 -> "ranked";
            case 2 -> "approved";
            case 3 -> "qualified";
            case 4 -> "loved";
            default -> "ranked";
        };
    }

    private String modeToString(int mode) {
        return switch (mode) {
            case 1 -> "taiko";
            case 2 -> "fruits";
            case 3 -> "mania";
            default -> "osu";
        };
    }
}
