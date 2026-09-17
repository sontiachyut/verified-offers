package dev.sonti.offers;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvaluationMetricsTest {
    @Test void idealMissingAndMisorderedRankingsHaveExpectedScores() {
        var labels = Map.of("a", 3, "b", 1);
        assertThat(EvaluationMetrics.score(List.of("a", "b"), labels)).isEqualTo(new EvaluationMetrics.Score(1, 1));
        assertThat(EvaluationMetrics.score(List.of("unknown"), labels)).isEqualTo(new EvaluationMetrics.Score(0, 0));
        assertThat(EvaluationMetrics.score(List.of("b", "a"), labels).ndcgAt10()).isBetween(0.69, 0.72);
        assertThat(EvaluationMetrics.score(List.of("a"), labels).recallAt10()).isEqualTo(0.5);
        assertThatThrownBy(() -> EvaluationMetrics.score(List.of("a", "a"), labels)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cutoffIsTenAndUnjudgedDocumentsAreNonrelevant() {
        var ranking = new ArrayList<String>(); for (int i = 0; i < 10; i++) ranking.add("noise" + i); ranking.add("a");
        assertThat(EvaluationMetrics.score(ranking, Map.of("a", 3))).isEqualTo(new EvaluationMetrics.Score(0, 0));
    }
}
