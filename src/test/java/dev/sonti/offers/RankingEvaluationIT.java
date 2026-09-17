package dev.sonti.offers;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Measures actual OpenSearch BM25; no source-verification or end-to-end latency claim. */
class RankingEvaluationIT {
    @Test void evaluateFrozenJudgmentsAgainstRealLexicalIndex() throws Exception {
        var json = JsonMapper.builder().build();
        try (var search = SearchTestContainer.create()) {
            search.start();
            try (var index = new OpenSearchIndex("http://127.0.0.1:" + search.getMappedPort(9200), "evaluation", json)) {
                index.create();
                var catalog = json.readTree(Path.of("evaluation/catalog.json").toFile());
                for (var row : catalog) index.project(new Offer("evaluation", "synthetic", row.path("id").asString(), 1,
                        row.path("title").asString(), 100, "USD", 1, Instant.now(), false));
                index.refresh();
                var rows = new ArrayList<Map<String, Object>>(); double ndcg = 0, recall = 0;
                for (var query : json.readTree(Path.of("evaluation/queries.json").toFile())) {
                    var labels = new HashMap<String, Integer>();
                    query.path("relevance").properties().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asInt()));
                    String pit = index.openPointInTime();
                    List<String> ranking;
                    try { ranking = index.page(pit, "evaluation", query.path("query").asString(), 10, null)
                            .stream().map(hit -> hit.offer().offerId()).toList(); }
                    finally { index.closePointsInTime(List.of(pit)); }
                    var score = EvaluationMetrics.score(ranking, labels);
                    ndcg += score.ndcgAt10(); recall += score.recallAt10();
                    rows.add(Map.of("id", query.path("id").asString(), "query", query.path("query").asString(), "ranking", ranking,
                            "ndcgAt10", score.ndcgAt10(), "recallAt10", score.recallAt10()));
                }
                var report = Map.of("baseline", "opensearch-3.8.0-title-bm25", "catalogSize", catalog.size(), "queries", rows,
                        "meanNdcgAt10", ndcg / rows.size(), "meanRecallAt10", recall / rows.size(), "paidModelCalls", 0);
                Files.createDirectories(Path.of("target/evaluation"));
                json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/evaluation/ranking.json").toFile(), report);
                assertThat(rows).hasSize(12);
                assertThat(ndcg / rows.size()).isBetween(0.0, 1.0);
            }
        }
    }
}
