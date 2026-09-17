package dev.sonti.offers;

import java.util.*;

final class EvaluationMetrics {
    record Score(double ndcgAt10, double recallAt10) {}
    static Score score(List<String> ranking, Map<String, Integer> judgments) {
        if (new HashSet<>(ranking).size() != ranking.size() || judgments.values().stream().anyMatch(g -> g < 0 || g > 3))
            throw new IllegalArgumentException("Unique ranking and 0–3 grades required.");
        var actual = ranking.stream().limit(10).map(id -> judgments.getOrDefault(id, 0)).toList();
        var ideal = judgments.values().stream().sorted(Comparator.reverseOrder()).limit(10).toList();
        double denominator = dcg(ideal);
        long relevant = judgments.values().stream().filter(g -> g > 0).count();
        return new Score(denominator == 0 ? 0 : dcg(actual) / denominator,
                relevant == 0 ? 0 : (double) actual.stream().filter(g -> g > 0).count() / relevant);
    }
    private static double dcg(List<Integer> grades) {
        double sum = 0;
        for (int i = 0; i < grades.size(); i++) sum += (Math.pow(2, grades.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        return sum;
    }
}
