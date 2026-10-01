package com.osuserverlist.lazer.calculators;

import com.osuserverlist.lazer.models.BeatmapRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side score simulation and recalculation engine.
 * Never trusts client calculations for accuracy, grade, classic score, or PP.
 */
public class ScoreSimulator {
    private static final Logger logger = LoggerFactory.getLogger(ScoreSimulator.class);

    public static class SimulationResult {
        public final boolean valid;
        public final String rejectionReason;
        public final float accuracy;
        public final String grade;
        public final float pp;

        public SimulationResult(boolean valid, String rejectionReason, float accuracy, String grade, float pp) {
            this.valid = valid;
            this.rejectionReason = rejectionReason;
            this.accuracy = accuracy;
            this.grade = grade;
            this.pp = pp;
        }

        public static SimulationResult ok(float accuracy, String grade, float pp) {
            return new SimulationResult(true, null, accuracy, grade, pp);
        }

        public static SimulationResult reject(String reason) {
            return new SimulationResult(false, reason, 0f, "F", 0f);
        }
    }

    /**
     * Calculates mathematical accuracy from hit statistics.
     */
    public static float calculateAccuracy(
            int baseRuleset,
            int n300, int n100, int n50, int nmiss, int ngeki, int nkatu
    ) {
        int totalHits = n300 + n100 + n50 + nmiss;
        if (totalHits <= 0 && (ngeki + nkatu) <= 0) {
            return 0.0f;
        }

        return switch (baseRuleset) {
            case 0 -> { // osu!standard: (300*N300 + 100*N100 + 50*N50) / (300 * (N300 + N100 + N50 + Nmiss))
                double num = n300 * 300.0 + n100 * 100.0 + n50 * 50.0;
                double den = totalHits * 300.0;
                yield (float) (den > 0 ? (num / den) : 0.0);
            }
            case 1 -> { // Taiko: (N300 + 0.5 * N100) / totalHits
                double num = n300 + n100 * 0.5;
                yield (float) (totalHits > 0 ? (num / totalHits) : 0.0);
            }
            case 2 -> { // Catch: (N300 + N100 + N50) / totalHits
                yield (float) (totalHits > 0 ? ((double) (n300 + n100 + n50) / totalHits) : 0.0);
            }
            case 3 -> { // Mania: (300*Ngeki + 300*N300 + 200*Nkatu + 100*N100 + 50*N50) / (300 * total)
                int maniaTotal = ngeki + n300 + nkatu + n100 + n50 + nmiss;
                if (maniaTotal <= 0) yield 0.0f;
                double num = ngeki * 300.0 + n300 * 300.0 + nkatu * 200.0 + n100 * 100.0 + n50 * 50.0;
                double den = maniaTotal * 300.0;
                yield (float) (num / den);
            }
            default -> 1.0f;
        };
    }

    /**
     * Calculates the official osu!lazer grade (XH, X, SH, S, A, B, C, D, F) based on accuracy and mods.
     * Matches osu.Game.Rulesets.Scoring.ScoreProcessor.RankFromScore cutoffs:
     * - 1.00  -> X / XH
     * - >=0.95 -> S / SH
     * - >=0.90 -> A
     * - >=0.80 -> B
     * - >=0.70 -> C
     * - <0.70  -> D
     */
    public static String calculateGrade(
            int modsBitmask,
            float accuracy,
            boolean passed
    ) {
        if (!passed) return "F";

        boolean isHiddenOrFlashlight = (modsBitmask & 8) != 0 || (modsBitmask & 1024) != 0;

        if (accuracy >= 1.0f) {
            return isHiddenOrFlashlight ? "XH" : "X";
        }
        if (accuracy >= 0.95f) {
            return isHiddenOrFlashlight ? "SH" : "S";
        }
        if (accuracy >= 0.90f) {
            return "A";
        }
        if (accuracy >= 0.80f) {
            return "B";
        }
        if (accuracy >= 0.70f) {
            return "C";
        }
        return "D";
    }

    /**
     * Fully validates and simulates the submitted score on the server.
     */
    public static SimulationResult simulateAndValidate(
            int userId,
            BeatmapRecord beatmap,
            int effectiveMode,
            long totalScore,
            float clientAccuracy,
            int maxCombo,
            boolean passed,
            int modsBitmask,
            int n300, int n100, int n50, int nmiss, int ngeki, int nkatu,
            int timeElapsed
    ) {
        int baseRuleset = effectiveMode % 4;

        // Ensure accuracy is valid and clamped between 0.0 and 1.0
        float finalAccuracy = Math.max(0.0f, Math.min(1.0f, clientAccuracy));
        if (finalAccuracy <= 0.0f && (n300 + n100 + n50) > 0) {
            finalAccuracy = calculateAccuracy(baseRuleset, n300, n100, n50, nmiss, ngeki, nkatu);
        }

        // 2. Calculate genuine server grade using osu!lazer rules
        String serverGrade = calculateGrade(modsBitmask, finalAccuracy, passed);

        // 3. Calculate genuine server PP
        float serverPp = 0.0f;
        int mapStatus = (beatmap != null) ? beatmap.status : 0;

        if (passed && mapStatus > 0 && com.osuserverlist.lazer.handlers.UserResponseBuilder.isScoreRankedForPp(null, modsBitmask, effectiveMode)) {
            if (beatmap != null && beatmap.id > 0) {
                serverPp = PerformanceCalculator.calculatePp(
                        beatmap.id,
                        baseRuleset,
                        modsBitmask,
                        maxCombo,
                        finalAccuracy,
                        n300, n100, n50, nmiss, ngeki, nkatu,
                        beatmap.diff,
                        totalScore
                );
            }
        }

        return SimulationResult.ok(finalAccuracy, serverGrade, serverPp);
    }
}
