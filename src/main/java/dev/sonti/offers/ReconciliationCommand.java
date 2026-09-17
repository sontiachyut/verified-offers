package dev.sonti.offers;

import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit OS/database-authorized administration. No HTTP surface or automatic work loop. */
final class ReconciliationCommand {
    static Map<String, String> parse(String[] arguments) {
        var values = new HashMap<String, String>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (!argument.startsWith("--") || equals < 3) throw new IllegalArgumentException("Named options required.");
            if (values.putIfAbsent(argument.substring(2, equals), argument.substring(equals + 1)) != null)
                throw new IllegalArgumentException("Repeated option.");
        }
        String action = values.get("reconcile");
        var expected = "prepare".equals(action) ? Set.of("reconcile", "topic", "partition", "offset", "tenant", "merchant", "offer", "operator", "reason")
                : "step".equals(action) || "status".equals(action) ? Set.of("reconcile", "id") : Set.<String>of();
        if (expected.isEmpty() || !values.keySet().equals(expected)) throw new IllegalArgumentException("Exact action options required.");
        if (action.equals("prepare")) {
            new IndexReconciliation.Position(values.get("topic"), Integer.parseInt(values.get("partition")), Long.parseLong(values.get("offset")));
            for (String key : List.of("tenant", "merchant", "offer", "operator", "reason")) Input.identifier(values.get(key));
        } else UUID.fromString(values.get("id"));
        return Map.copyOf(values);
    }
    static int run(String[] arguments) {
        try {
            var options = parse(arguments); String action = options.get("reconcile");
            try (var context = new SpringApplication(Application.class).run("--spring.profiles.active=postgres-local",
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                    "--offers.publisher.enabled=false", "--offers.indexer.enabled=false", "--offers.feeds.enabled=false",
                    "--offers.feeds.worker-enabled=false", "--offers.search.enabled=" + action.equals("step"))) {
                var sql = context.getBean(JdbcTemplate.class); var json = context.getBean(JsonMapper.class);
                var store = new IndexReconciliation(sql, new TransactionTemplate(context.getBean(PlatformTransactionManager.class)), json);
                var result = switch (action) {
                    case "prepare" -> store.prepare(new IndexReconciliation.Position(options.get("topic"), Integer.parseInt(options.get("partition")), Long.parseLong(options.get("offset"))),
                            options.get("tenant"), options.get("merchant"), options.get("offer"), options.get("operator"), options.get("reason"));
                    case "status" -> store.get(UUID.fromString(options.get("id")));
                    case "step" -> {
                        var index = context.getBean(OpenSearchIndex.class);
                        yield store.step(UUID.fromString(options.get("id")), index::project, new IndexingGate(sql, index.alias()));
                    }
                    default -> throw new IllegalArgumentException("Unknown action.");
                };
                System.out.println(json.writeValueAsString(result)); return 0;
            }
        } catch (Exception failure) {
            System.err.println("Reconciliation failed. Inspect saved intent and dependency health; no automatic retry scheduled.");
            return 2;
        }
    }
}
