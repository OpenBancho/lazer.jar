package com.osuserverlist.lazer.calculators;

import io.github.nanamochi.osu_native.wrapper.attributes.difficulty.DifficultyAttributes;
import io.github.nanamochi.osu_native.wrapper.attributes.performance.PerformanceAttributes;
import io.github.nanamochi.osu_native.wrapper.factories.DifficultyCalculatorFactory;
import io.github.nanamochi.osu_native.wrapper.factories.PerformanceCalculatorFactory;
import io.github.nanamochi.osu_native.wrapper.objects.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PerformanceCalculator {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceCalculator.class);
    private static final Path MAPS_DIR = Path.of("data", "maps");
    private static final Path BANCHO_MAPS_DIR = Path.of("..", "bancho.jar", "data", "maps");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public static float calculatePp(
            long beatmapId,
            int rulesetId,
            int modsBitmask,
            int maxCombo,
            float accuracy,
            int n300,
            int n100,
            int n50,
            int nmiss,
            int ngeki,
            int nkatu,
            float fallbackDiff,
            long totalScore
    ) {
        if (!com.osuserverlist.lazer.handlers.UserResponseBuilder.isScoreRankedForPp(null, modsBitmask, rulesetId)) {
            return 0.0f;
        }

        if (beatmapId <= 0) {
            return fallbackPp(fallbackDiff, totalScore);
        }

        byte[] mapBytes = getOrDownloadMap(beatmapId);
        if (mapBytes == null || mapBytes.length == 0) {
            return fallbackPp(fallbackDiff, totalScore);
        }

        try {
            Beatmap beatmap = Beatmap.fromBytes(mapBytes);
            int baseRuleset = rulesetId % 4;
            Ruleset ruleset = Ruleset.fromId(baseRuleset);
            var ppCalc = PerformanceCalculatorFactory.create(ruleset);
            var diffCalc = DifficultyCalculatorFactory.create(ruleset, beatmap);

            ScoreInfo scoreInfo = new ScoreInfo();
            // Accuracy: ensure between 0.0 and 1.0
            float accVal = accuracy > 1.0f ? accuracy / 100.0f : accuracy;
            scoreInfo.setAccuracy(accVal);
            scoreInfo.setMaxCombo(maxCombo);
            scoreInfo.setCountMiss(nmiss);
            scoreInfo.setCountGreat(n300);
            scoreInfo.setCountOk(n100);
            scoreInfo.setCountMeh(n50);
            scoreInfo.setCountPerfect(ngeki);
            scoreInfo.setCountGood(nkatu);

            ModsCollection mods = ModsCollection.create();
            for (String modStr : convertModsBitmask(modsBitmask)) {
                try {
                    mods.add(Mod.create(modStr));
                } catch (Exception ignored) {}
            }

            DifficultyAttributes diffAttr = diffCalc.calculate(mods);
            PerformanceAttributes perfAttr = ppCalc.calculate(ruleset, beatmap, mods, scoreInfo, diffAttr);
            double totalPp = perfAttr.getTotal();
            beatmap.close();

            if (!Double.isNaN(totalPp) && !Double.isInfinite(totalPp) && totalPp > 0) {
                return (float) totalPp;
            }
        } catch (Exception e) {
            logger.warn("Native PP calculation failed for map {}: {}", beatmapId, e.getMessage());
        }

        return fallbackPp(fallbackDiff, totalScore);
    }

    private static byte[] getOrDownloadMap(long beatmapId) {
        try {
            // 1. Check local data/maps
            Path localPath = MAPS_DIR.resolve(beatmapId + ".osu");
            if (Files.exists(localPath)) {
                return Files.readAllBytes(localPath);
            }

            // 2. Check bancho.jar/data/maps
            Path banchoPath = BANCHO_MAPS_DIR.resolve(beatmapId + ".osu");
            if (Files.exists(banchoPath)) {
                return Files.readAllBytes(banchoPath);
            }

            // 3. Download from osu.ppy.sh or osu.direct
            String[] urls = {
                    "https://osu.ppy.sh/osu/" + beatmapId,
                    "https://osu.direct/api/osu/" + beatmapId
            };

            for (String url : urls) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(8))
                            .GET()
                            .build();
                    HttpResponse<byte[]> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                        byte[] bytes = resp.body();
                        // Cache asynchronously
                        try {
                            Files.createDirectories(MAPS_DIR);
                            Files.write(localPath, bytes);
                        } catch (IOException ignored) {}
                        return bytes;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.warn("Failed to get/download map {}: {}", beatmapId, e.getMessage());
        }
        return null;
    }

    private static List<String> convertModsBitmask(int bitmask) {
        List<String> list = new ArrayList<>();
        if ((bitmask & 1) != 0) list.add("NF");
        if ((bitmask & 2) != 0) list.add("EZ");
        if ((bitmask & 4) != 0) list.add("TD");
        if ((bitmask & 8) != 0) list.add("HD");
        if ((bitmask & 16) != 0) list.add("HR");
        if ((bitmask & 32) != 0) list.add("SD");
        if ((bitmask & 64) != 0) list.add("DT");
        if ((bitmask & 128) != 0) list.add("RX");
        if ((bitmask & 256) != 0) list.add("HT");
        if ((bitmask & 512) != 0) list.add("NC");
        if ((bitmask & 1024) != 0) list.add("FL");
        if ((bitmask & 2048) != 0) list.add("AT");
        if ((bitmask & 4096) != 0) list.add("SO");
        if ((bitmask & 8192) != 0) list.add("AP");
        if ((bitmask & 16384) != 0) list.add("PF");
        return list;
    }

    private static float fallbackPp(float starRating, long totalScore) {
        if (starRating <= 0 || totalScore <= 0) return 0.0f;
        float k = 4.0f;
        float clampedStars = Math.max(1.0f, Math.min(8.0f, starRating));
        float pmax = (float) (1.4 * Math.pow(starRating, 2.8));
        float b = (float) (0.95 - 0.33 * ((clampedStars - 1.0f) / 7.0f));
        float x = (float) (totalScore / 1000000.0);

        if (x < b) {
            return Math.max(0.0f, pmax * x);
        } else {
            float xNorm = (x - b) / (1.0f - b);
            float expPart = (float) ((Math.exp(k * xNorm) - 1.0) / (Math.exp(k) - 1.0));
            return Math.max(0.0f, pmax * (b + (1.0f - b) * expPart));
        }
    }
}
