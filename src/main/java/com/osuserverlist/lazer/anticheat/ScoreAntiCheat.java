package com.osuserverlist.lazer.anticheat;

import com.osuserverlist.lazer.models.BeatmapRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight anticheat system to validate and reject blatantly impossible scores
 * (similar to bancho.jar / private osu server checks).
 */
public class ScoreAntiCheat {
    private static final Logger logger = LoggerFactory.getLogger(ScoreAntiCheat.class);

    public static class ValidationResult {
        private final boolean valid;
        private final String reason;

        public ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Validates incoming score parameters for sanity.
     */
    public static ValidationResult validateScore(
            int userId,
            BeatmapRecord beatmap,
            int rulesetId,
            long totalScore,
            long totalScoreWithoutMods,
            float accuracy,
            int maxCombo,
            float pp,
            boolean passed,
            int modsBitmask,
            int n300, int n100, int n50, int nmiss, int ngeki, int nkatu,
            int timeElapsed
    ) {
        // 1. Ruleset validity (supports 0..3 standard, 4..7 relax, 8..11 autopilot)
        if (rulesetId < 0 || rulesetId > 11) {
            return ValidationResult.fail("Invalid ruleset ID: " + rulesetId);
        }

        // 2. Negative values check
        if (totalScore < 0 || totalScoreWithoutMods < 0) {
            return ValidationResult.fail("Negative score value: total=" + totalScore + ", withoutMods=" + totalScoreWithoutMods);
        }

        if (maxCombo < 0) {
            return ValidationResult.fail("Negative max combo: " + maxCombo);
        }

        if (accuracy < 0.0f || accuracy > 1.0005f) {
            return ValidationResult.fail("Invalid accuracy value: " + accuracy);
        }

        if (pp < 0.0f || pp > 3500.0f) {
            return ValidationResult.fail("Invalid PP value: " + pp);
        }

        if (n300 < 0 || n100 < 0 || n50 < 0 || nmiss < 0 || ngeki < 0 || nkatu < 0) {
            return ValidationResult.fail("Negative hitcount statistics");
        }

        // 3. Mod conflicts check
        // EZ (2) + HR (16)
        if ((modsBitmask & 2) != 0 && (modsBitmask & 16) != 0) {
            return ValidationResult.fail("Conflicting mods: Easy (EZ) and HardRock (HR)");
        }
        // DT (64) + HT (256)
        if ((modsBitmask & 64) != 0 && (modsBitmask & 256) != 0) {
            return ValidationResult.fail("Conflicting mods: DoubleTime (DT) and HalfTime (HT)");
        }
        // NF (1) + SD (32) / PF (16384)
        if ((modsBitmask & 1) != 0 && ((modsBitmask & 32) != 0 || (modsBitmask & 16384) != 0)) {
            return ValidationResult.fail("Conflicting mods: NoFail (NF) and SuddenDeath/Perfect (SD/PF)");
        }
        // RX (128) + AP (8192)
        if ((modsBitmask & 128) != 0 && (modsBitmask & 8192) != 0) {
            return ValidationResult.fail("Conflicting mods: Relax (RX) and Autopilot (AP)");
        }

        // 4. Hitcount sanity on passed scores
        int totalHits = n300 + n100 + n50 + nmiss;
        if (passed) {
            if (totalHits == 0 && (ngeki + nkatu == 0)) {
                return ValidationResult.fail("Passed score cannot have 0 total hits");
            }
        }

        // 5. Beatmap sanity checks (if beatmap metadata is available)
        if (beatmap != null) {
            // Combo check against beatmap max combo
            if (beatmap.maxCombo > 0) {
                int maxAllowedCombo = (int) Math.max(beatmap.maxCombo + 25, beatmap.maxCombo * 1.15);
                if (maxCombo > maxAllowedCombo) {
                    return ValidationResult.fail("Impossible combo " + maxCombo + " on beatmap with max combo " + beatmap.maxCombo);
                }

                // Total hits sanity check
                int maxAllowedHits = Math.max(beatmap.maxCombo * 4 + 100, 500);
                if (totalHits > maxAllowedHits) {
                    return ValidationResult.fail("Impossible total hits " + totalHits + " on beatmap with max combo " + beatmap.maxCombo);
                }
            }

            // Speedhack / duration check
            if (passed && beatmap.totalLength > 10 && timeElapsed > 0) {
                double speed = 1.0;
                if ((modsBitmask & 64) != 0 || (modsBitmask & 512) != 0) {
                    speed = 1.5;
                } else if ((modsBitmask & 256) != 0) {
                    speed = 0.75;
                }

                long expectedMinMs = (long) ((beatmap.totalLength / speed) * 1000);
                long minimumAllowedMs = (long) (expectedMinMs * 0.40);

                if (timeElapsed < minimumAllowedMs) {
                    return ValidationResult.fail("Speedhack detected: play time " + timeElapsed + "ms is impossibly short for map length " + beatmap.totalLength + "s");
                }
            }
        }

        return ValidationResult.ok();
    }
}
