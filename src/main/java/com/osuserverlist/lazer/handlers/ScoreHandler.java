package com.osuserverlist.lazer.handlers;

import com.osuserverlist.lazer.auth.AuthService;
import com.osuserverlist.lazer.auth.TokenStore;
import com.osuserverlist.lazer.config.ServerConfig;
import com.osuserverlist.lazer.database.DatabaseManager;
import com.osuserverlist.lazer.models.ScoreRecord;
import com.osuserverlist.lazer.models.User;
import com.osuserverlist.lazer.replay.ReplayStore;
import io.javalin.http.Context;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Score lookups and replay downloads:
 * GET /scores/{id}, /scores/{ruleset}/{id}, /scores/{id}/download,
 * GET /beatmaps/{id}/scores/users/{user_id} and /all.
 */
public class ScoreHandler {
    private final AuthService authService;
    private final DatabaseManager databaseManager;
    private final ServerConfig config;

    public ScoreHandler(AuthService authService, DatabaseManager databaseManager, ServerConfig config) {
        this.authService = authService;
        this.databaseManager = databaseManager;
        this.config = config;
    }

    private User authenticate(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        TokenStore.TokenData data = authService.resolveToken(header.substring(7).trim());
        return data == null ? null : databaseManager.findUserById(data.userId);
    }

    private static long parseId(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** GET /scores/{score} and /scores/{ruleset}/{score} */
    public void handleGetScore(@NotNull Context ctx) {
        long id = parseId(ctx, "score");
        if (id < 0) {
            ctx.status(400).json(Map.of("error", "Invalid score id"));
            return;
        }
        ScoreRecord sc = databaseManager.findScoreById(id);
        if (sc == null) {
            ctx.status(404).json(Map.of("error", "Score not found"));
            return;
        }
        ctx.json(UserResponseBuilder.formatScore(sc, config));
    }

    /** GET /scores/{score_id}/download -- the stored .osr of a score. */
    public void handleDownloadReplay(@NotNull Context ctx) throws IOException {
        long id = parseId(ctx, "score_id");
        if (id < 0) {
            ctx.status(400).json(Map.of("error", "Invalid score id"));
            return;
        }
        ScoreRecord sc = databaseManager.findScoreById(id);
        if (sc == null) {
            ctx.status(404).json(Map.of("error", "Score not found"));
            return;
        }
        Path file = ReplayStore.replayPath(id);
        if (!Files.isRegularFile(file)) {
            ctx.status(404).json(Map.of("error", "Replay file not found"));
            return;
        }

        User viewer = authenticate(ctx);
        if (viewer == null || viewer.id != sc.userId) {
            databaseManager.incrementReplayViews(sc.userId, sc.mode);
        }

        String name = (sc.username + " playing " + sc.artist + " - " + sc.title + " [" + sc.version + "] ("
                + java.time.LocalDate.ofEpochDay(sc.playTimeEpochSec / 86400) + ").osr");
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        ctx.header("Content-Type", "application/x-osu-replay");
        ctx.header("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded);
        // a replay stored by bancho.jar is the bare frame stream; the client needs a complete .osr
        ctx.result(ReplayStore.forDownload(sc, Files.readAllBytes(file)));
    }

    private int modeParam(Context ctx, int def) {
        String m = ctx.queryParam("mode");
        if (m == null || m.isBlank()) m = ctx.queryParam("ruleset");
        return m == null || m.isBlank() ? def : UserResponseBuilder.parseMode(m);
    }

    /** GET /beatmaps/{beatmap_id}/scores/users/{user_id} -- a user's best score on a map with its position. */
    public void handleUserBeatmapScore(@NotNull Context ctx) {
        int beatmapId = (int) parseId(ctx, "beatmap_id");
        int userId = (int) parseId(ctx, "user_id");
        if (beatmapId < 0 || userId < 0) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
            return;
        }
        int mode = modeParam(ctx, 0);
        ScoreRecord best = databaseManager.findUserBeatmapScore(beatmapId, userId, mode);
        if (best == null) {
            ctx.status(404).json(Map.of("error", "Score not found"));
            return;
        }
        int position = 1;
        List<ScoreRecord> board = databaseManager.findBeatmapLeaderboard(beatmapId, mode, 50);
        for (int i = 0; i < board.size(); i++) {
            if (board.get(i).id == best.id) {
                position = i + 1;
                break;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("position", position);
        resp.put("score", UserResponseBuilder.formatScore(best, config));
        ctx.json(resp);
    }

    /** GET /beatmaps/{beatmap_id}/scores/users/{user_id}/all */
    public void handleUserBeatmapScoresAll(@NotNull Context ctx) {
        int beatmapId = (int) parseId(ctx, "beatmap_id");
        int userId = (int) parseId(ctx, "user_id");
        if (beatmapId < 0 || userId < 0) {
            ctx.status(400).json(Map.of("error", "Invalid id"));
            return;
        }
        List<Map<String, Object>> scores = new ArrayList<>();
        for (ScoreRecord sc : databaseManager.findUserBeatmapScores(beatmapId, userId, modeParam(ctx, 0))) {
            scores.add(UserResponseBuilder.formatScore(sc, config));
        }
        ctx.json(Map.of("scores", scores));
    }

    /** PUT /score-pins/{score_id} */
    public void handlePin(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        long id = parseId(ctx, "score_id");
        if (id < 0 || !databaseManager.pinScore(user.id, id)) {
            ctx.status(404).json(Map.of("error", "Score not found"));
            return;
        }
        ctx.json(Map.of());
    }

    /** DELETE /score-pins/{score_id} */
    public void handleUnpin(@NotNull Context ctx) {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        long id = parseId(ctx, "score_id");
        if (id < 0 || !databaseManager.unpinScore(user.id, id)) {
            ctx.status(404).json(Map.of("error", "Pinned score not found"));
            return;
        }
        ctx.json(Map.of());
    }

    /** POST /score-pins/{score_id}/reorder with {"after_score_id": n} or {"before_score_id": n} */
    @SuppressWarnings("unchecked")
    public void handleReorderPin(@NotNull Context ctx) throws IOException {
        User user = authenticate(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        long id = parseId(ctx, "score_id");
        Map<String, Object> body = ctx.body().isBlank() ? Map.of() : new com.fasterxml.jackson.databind.ObjectMapper().readValue(ctx.body(), Map.class);
        Long after = body.get("after_score_id") instanceof Number n ? n.longValue() : null;
        Long before = body.get("before_score_id") instanceof Number n ? n.longValue() : null;
        if (id < 0 || !databaseManager.reorderPin(user.id, id, after, before)) {
            ctx.status(404).json(Map.of("error", "Pinned score not found"));
            return;
        }
        ctx.json(Map.of());
    }
}
