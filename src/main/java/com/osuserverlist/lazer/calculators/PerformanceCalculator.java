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
        return calculatePp(beatmapId, rulesetId, modsBitmask, maxCombo, accuracy, n300, n100, n50, nmiss, ngeki, nkatu,
                fallbackDiff, totalScore, null);
    }

    /**
     * @param apiMods the mods as the client sent them (acronym + settings, e.g. Rate Adjust's speed_change or
     *                Difficulty Adjust's overrides). When null only the bitmask is used.
     */
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
            long totalScore,
            List<?> apiMods
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

            ModsCollection mods = buildMods(modsBitmask, apiMods);

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

    private static final java.util.Map<String, java.util.Map<String, Object>> ATTRIBUTE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Difficulty attributes of a beatmap under a mod combination (star rating, max combo and, for osu!standard,
     * the skill breakdown). Returns null if the map file or the native calculator is unavailable.
     */
    public static java.util.Map<String, Object> difficultyAttributes(long beatmapId, int rulesetId, int modsBitmask, List<?> apiMods) {
        String key = beatmapId + ":" + rulesetId + ":" + modsBitmask + ":" + settingsKey(apiMods);
        java.util.Map<String, Object> cached = ATTRIBUTE_CACHE.get(key);
        if (cached != null) return cached;

        byte[] mapBytes = getOrDownloadMap(beatmapId);
        if (mapBytes == null || mapBytes.length == 0) return null;

        try {
            Beatmap beatmap = Beatmap.fromBytes(mapBytes);
            try {
                Ruleset ruleset = Ruleset.fromId(rulesetId % 4);
                var diffCalc = DifficultyCalculatorFactory.create(ruleset, beatmap);
                ModsCollection mods = buildMods(modsBitmask, apiMods);
                DifficultyAttributes attr = diffCalc.calculate(mods);
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("star_rating", attr.getStarRating());
                out.put("max_combo", attr.getMaxCombo());
                if (attr instanceof io.github.nanamochi.osu_native.wrapper.attributes.difficulty.OsuDifficultyAttributes o) {
                    out.put("aim_difficulty", o.getAimDifficulty());
                    out.put("aim_difficult_slider_count", o.getAimDifficultSliderCount());
                    out.put("speed_difficulty", o.getSpeedDifficulty());
                    out.put("speed_note_count", o.getSpeedNoteCount());
                    out.put("flashlight_difficulty", o.getFlashlightDifficulty());
                    out.put("slider_factor", o.getSliderFactor());
                    out.put("aim_difficult_strain_count", o.getAimDifficultStrainCount());
                    out.put("speed_difficult_strain_count", o.getSpeedDifficultStrainCount());
                    out.put("drain_rate", o.getDrainRate());
                    out.put("hit_circle_count", o.getHitCircleCount());
                    out.put("slider_count", o.getSliderCount());
                    out.put("spinner_count", o.getSpinnerCount());
                }
                if (ATTRIBUTE_CACHE.size() > 5000) ATTRIBUTE_CACHE.clear();
                ATTRIBUTE_CACHE.put(key, out);
                return out;
            } finally {
                beatmap.close();
            }
        } catch (Exception e) {
            logger.warn("Difficulty attribute calculation failed for map {}: {}", beatmapId, e.getMessage());
            return null;
        }
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

    /** Stable cache key for the settings part of a mod list. */
    private static String settingsKey(List<?> apiMods) {
        if (apiMods == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : apiMods) {
            if (o instanceof java.util.Map<?, ?> m && m.get("settings") instanceof java.util.Map<?, ?> st && !st.isEmpty()) {
                sb.append(m.get("acronym")).append(new java.util.TreeMap<>(st)).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * Builds the native mod collection. With API mods the settings travel along (Rate Adjust / Difficulty Adjust and
     * the settings of DT/HT/HR...); otherwise, or if none could be created, the legacy bitmask is used.
     */
    private static ModsCollection buildMods(int modsBitmask, List<?> apiMods) {
        ModsCollection mods = ModsCollection.create();
        boolean usedApi = false;
        if (apiMods != null) {
            for (Object o : apiMods) {
                String acronym = null;
                java.util.Map<?, ?> settings = null;
                if (o instanceof java.util.Map<?, ?> m) {
                    acronym = m.get("acronym") != null ? m.get("acronym").toString().toUpperCase() : null;
                    if (m.get("settings") instanceof java.util.Map<?, ?> st) settings = st;
                } else if (o instanceof String str) {
                    acronym = str.toUpperCase();
                }
                if (acronym == null || acronym.isBlank()) continue;
                try {
                    Mod mod = Mod.create(acronym);
                    if (settings != null) {
                        for (var e : settings.entrySet()) {
                            String name = String.valueOf(e.getKey());
                            Object v = e.getValue();
                            try {
                                if (v instanceof Boolean b) mod.setSettingBoolean(name, b);
                                else if (v instanceof Integer i) mod.setSettingsInteger(name, i);
                                else if (v instanceof Number n) mod.setSettingsFloat(name, n.floatValue());
                            } catch (Exception ex) {
                                logger.debug("Mod setting {}.{} rejected: {}", acronym, name, ex.getMessage());
                            }
                        }
                    }
                    mods.add(mod);
                    usedApi = true;
                } catch (Exception ex) {
                    logger.debug("Unknown mod {}: {}", acronym, ex.getMessage());
                }
            }
        }
        if (!usedApi) {
            for (String modStr : convertModsBitmask(modsBitmask)) {
                try {
                    mods.add(Mod.create(modStr));
                } catch (Exception ignored) {}
            }
        }
        return mods;
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
