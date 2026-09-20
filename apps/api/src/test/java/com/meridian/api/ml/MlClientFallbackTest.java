package com.meridian.api.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The heuristic that runs when the ML service is unreachable.
 *
 * <p>Worth pinning because it is the path taken during an outage, when nobody is watching: its
 * weights are carried over from the Node implementation so a PR scored during a degraded window
 * lands in the same tier it would have before the migration.
 */
class MlClientFallbackTest {

    private static Map<String, Object> features(Object... pairs) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            f.put((String) pairs[i], pairs[i + 1]);
        }
        return f;
    }

    @Test
    @DisplayName("a small, unremarkable PR scores low")
    void smallPrScoresLow() {
        MlScore score = MlClient.fallbackScore(features(
                "additions", 10, "deletions", 4, "changed_files", 2, "commit_msg_quality", 0.8));

        // 0.15 base + min(10/1000, 0.35)=0.01 + min(2/40, 0.20)=0.05 = 0.21
        assertThat(score.score().doubleValue()).isCloseTo(0.21, within(0.001));
        assertThat(score.tier()).isEqualTo("low");
    }

    @Test
    @DisplayName("sensitive-area flags each add 0.15")
    void sensitiveFlagsRaiseScore() {
        var base = MlClient.fallbackScore(features("additions", 0, "changed_files", 0, "commit_msg_quality", 0.8));
        var withAuth = MlClient.fallbackScore(features(
                "additions", 0, "changed_files", 0, "commit_msg_quality", 0.8, "touches_auth", 1));

        assertThat(withAuth.score().doubleValue() - base.score().doubleValue()).isCloseTo(0.15, within(0.001));
    }

    @Test
    @DisplayName("a large PR touching several sensitive areas reaches critical")
    void largeSensitivePrScoresCritical() {
        MlScore score = MlClient.fallbackScore(features(
                "additions", 2000, "deletions", 500, "changed_files", 60,
                "touches_auth", 1, "touches_billing", 1, "touches_infra", 1, "touches_secret", 1,
                "commit_msg_quality", 0.2));

        assertThat(score.score().doubleValue()).isEqualTo(0.98);
        assertThat(score.tier()).isEqualTo("critical");
    }

    @Test
    @DisplayName("score is always clamped to 0.05..0.98")
    void scoreIsClamped() {
        var floor = MlClient.fallbackScore(features("additions", 0, "changed_files", 0, "commit_msg_quality", 1.0));
        assertThat(floor.score().doubleValue()).isGreaterThanOrEqualTo(0.05);

        var ceiling = MlClient.fallbackScore(features(
                "additions", 999999, "changed_files", 9999,
                "touches_auth", 1, "touches_billing", 1, "touches_infra", 1, "touches_secret", 1));
        assertThat(ceiling.score().doubleValue()).isLessThanOrEqualTo(0.98);
    }

    @Test
    @DisplayName("tier boundaries match the ML service's own thresholds")
    void tierBoundaries() {
        assertThat(MlClient.tierFor(0.00)).isEqualTo("low");
        assertThat(MlClient.tierFor(0.349)).isEqualTo("low");
        assertThat(MlClient.tierFor(0.35)).isEqualTo("medium");
        assertThat(MlClient.tierFor(0.649)).isEqualTo("medium");
        assertThat(MlClient.tierFor(0.65)).isEqualTo("high");
        assertThat(MlClient.tierFor(0.849)).isEqualTo("high");
        assertThat(MlClient.tierFor(0.85)).isEqualTo("critical");
        assertThat(MlClient.tierFor(1.00)).isEqualTo("critical");
    }

    @Test
    @DisplayName("a fallback score is identifiable as one after the fact")
    void fallbackIsLabelled() {
        MlScore score = MlClient.fallbackScore(features("additions", 100));

        // Both of these are persisted, so an operator can tell which scores were produced during
        // an ML outage rather than by the model.
        assertThat(score.modelVersion()).isEqualTo("fallback-heuristic-0.1");
        assertThat(score.confidence()).isEqualTo("low");
        assertThat(score.contributions().json()).startsWith("[");
    }

    @Test
    @DisplayName("missing or non-numeric features degrade to zero rather than throwing")
    void toleratesMissingFeatures() {
        assertThat(MlClient.fallbackScore(Map.of()).score()).isNotNull();
        assertThat(MlClient.fallbackScore(features("additions", "not-a-number")).score()).isNotNull();
        assertThat(MlClient.fallbackScore(features("touches_auth", true)).score().doubleValue())
                .isGreaterThan(0.15);
    }
}
