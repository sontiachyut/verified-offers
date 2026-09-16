package dev.sonti.offers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.json.JsonMapper;

/** One explicit bounded queue step; never turns on web, background workers or external messaging. */
final class FeedCommand {
    record Options(String action, String tenant, String merchant, UUID job) {}
    static Options parse(String[] args) {
        var values = new HashMap<String, String>();
        for (String arg : args) {
            int split = arg.indexOf('=');
            if (!arg.startsWith("--") || split < 3) throw new IllegalArgumentException("Named options required.");
            String name = arg.substring(2, split);
            if (!List.of("feed", "tenant", "merchant", "job").contains(name) || values.putIfAbsent(name, arg.substring(split + 1)) != null) {
                throw new IllegalArgumentException("Unknown or repeated option.");
            }
        }
        String action = values.get("feed");
        if (!List.of("step", "status").contains(action == null ? "" : action)) throw new IllegalArgumentException("Invalid feed action.");
        if (action.equals("step")) {
            if (values.size() != 1) throw new IllegalArgumentException("Step processes the local queue; no scope overrides.");
            return new Options(action, null, null, null);
        }
        if (values.size() != 4) throw new IllegalArgumentException("Status scope required.");
        return new Options(action, Input.identifier(values.get("tenant")), Input.identifier(values.get("merchant")), UUID.fromString(values.get("job")));
    }
    static int run(String[] args) {
        try {
            var options = parse(args);
            try (var context = new SpringApplication(Application.class).run("--spring.profiles.active=postgres-local",
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                    "--offers.publisher.enabled=false", "--offers.indexer.enabled=false", "--offers.search.enabled=false",
                    "--offers.feeds.enabled=true", "--offers.feeds.worker-enabled=false")) {
                var json = context.getBean(JsonMapper.class);
                if (options.action().equals("status")) {
                    System.out.println(json.writeValueAsString(Map.of("job", context.getBean(FeedStore.class)
                            .get(options.tenant(), options.merchant(), options.job()))));
                    return 0;
                }
                var result = context.getBean(FeedWorker.class).step();
                System.out.println(json.writeValueAsString(Map.of("result", result)));
                return result == FeedWorker.Result.RETRY || result == FeedWorker.Result.LEASE_LOST ? 3 : 0;
            }
        } catch (Exception failure) {
            System.err.println("Feed command failed. Inspect scoped job status and local database health; no automatic retry was scheduled.");
            return 2;
        }
    }
}
