package dev.sonti.offers;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ClaimEvaluationTest {
    @Test void reportsHeldOutErrorsWithoutTurningPredictionsIntoLabels() throws Exception {
        var json = JsonMapper.builder().build(); var extractor = new ClaimExtractor();
        var cases = new ArrayList<Map<String, Object>>();
        int proposed = 0, correct = 0, expected = 0, falseProposals = 0;
        for (var example : json.readTree(Path.of("evaluation/claims.json").toFile())) {
            if (!example.path("split").asString().equals("holdout")) continue;
            long started = System.nanoTime();
            var extraction = extractor.extract(example.path("text").asString());
            long elapsed = System.nanoTime() - started;
            Long actual = extraction.proposal() == null ? null : extraction.proposal().priceMinor();
            Long gold = example.path("priceMinor").isNull() ? null : example.path("priceMinor").asLong();
            if (gold != null) expected++;
            if (actual != null) { proposed++; if (actual.equals(gold)) correct++; else falseProposals++; }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", example.path("id").asString()); row.put("expectedMinor", gold); row.put("actualMinor", actual);
            row.put("reason", extraction.reason()); row.put("matches", Objects.equals(actual, gold)); row.put("elapsedNanos", elapsed);
            cases.add(row);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("baseline", "explicit-usd-regex-v1"); report.put("cases", cases);
        report.put("precision", proposed == 0 ? 0 : (double) correct / proposed);
        report.put("recall", expected == 0 ? 0 : (double) correct / expected);
        report.put("coverage", (double) proposed / cases.size()); report.put("falseProposals", falseProposals);
        report.put("paidModelCalls", 0); report.put("inferenceApiSpendUsd", 0);
        Files.createDirectories(Path.of("target/evaluation"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/evaluation/claims.json").toFile(), report);
        assertThat(cases).hasSize(20); // Quality is reported, not falsified with self-generated expected labels.
    }
}
