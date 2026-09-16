package dev.sonti.offers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Local one-shot operator command; never starts an HTTP server or background workers. */
final class RebuildCommand {
    record Options(String action, UUID job, String endpoint, int maxOffers) {}

    static Options parse(String[] args) {
        var values = new HashMap<String, String>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (separator < 3 || !arg.startsWith("--")) throw new IllegalArgumentException("Expected named options.");
            String name = arg.substring(2, separator);
            if (!List.of("rebuild", "job", "endpoint", "max-offers").contains(name)
                    || values.putIfAbsent(name, arg.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("Unknown or repeated option.");
            }
        }
        String action = values.get("rebuild");
        if (action == null || !List.of("create", "step", "status").contains(action)) throw new IllegalArgumentException("Invalid action.");
        UUID job = values.containsKey("job") ? UUID.fromString(values.get("job")) : null;
        String endpoint = values.get("endpoint");
        int maxOffers = Integer.parseInt(values.getOrDefault("max-offers", "100000"));
        if (maxOffers < 1 || maxOffers > 100000 || action.equals("create") != (job == null)
                || action.equals("step") != (endpoint != null)
                || (!action.equals("create") && values.containsKey("max-offers"))) {
            throw new IllegalArgumentException("Invalid action options.");
        }
        // Validate before opening a database context; no connection is made by this constructor.
        if (endpoint != null) try (var ignored = new OpenSearchIndex(endpoint, "validation", JsonMapper.builder().build())) { }
        return new Options(action, job, endpoint, maxOffers);
    }

    static int run(String[] args) {
        try {
            Options options = parse(args);
            var application = new SpringApplication(Application.class);
            try (var context = application.run("--spring.profiles.active=postgres-local",
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                    "--offers.publisher.enabled=false", "--offers.indexer.enabled=false", "--offers.search.enabled=false")) {
                var json = context.getBean(JsonMapper.class);
                var store = new RebuildStore(context.getBean(JdbcTemplate.class),
                        context.getBean(PlatformTransactionManager.class), json, options.maxOffers());
                RebuildStore.Job job;
                String result;
                if (options.action().equals("create")) { job = store.create(); result = "CREATED"; }
                else {
                    job = store.get(options.job());
                    result = "STATUS";
                    if (options.action().equals("step")) {
                        try (var index = new OpenSearchIndex(options.endpoint(), job.shadowAlias(), json)) {
                            result = new ShadowRebuild(store, index).step(job.id()).name();
                        }
                        job = store.get(job.id());
                    }
                }
                System.out.println(json.writeValueAsString(Map.of("result", result, "job", job,
                        "shadowAlias", job.shadowAlias(), "promotable", false)));
                if (result.equals("INVALID") || (options.action().equals("step") && job.state().equals("INVALID"))) return 2;
                if (List.of("RETRY", "LEASE_LOST").contains(result)
                        || (result.equals("BUSY_OR_TERMINAL") && !job.state().equals("SNAPSHOT_VALIDATED"))) return 3;
                return 0;
            }
        } catch (Exception failed) {
            System.err.println("Rebuild command failed. Check action/options, local dependencies and persisted job status. No automatic retry or promotion was performed.");
            return 2;
        }
    }
}
