package dev.sonti.offers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

final class OnlineRebuildCommand {
    record Options(String action, UUID run, String endpoint, String alias, String servers) {}
    static Options parse(String[] args) {
        var values = new HashMap<String, String>();
        for (String arg : args) {
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 3) throw new IllegalArgumentException("Expected named options.");
            String name = arg.substring(2, equals);
            if (!List.of("online", "run", "endpoint", "alias", "bootstrap-servers", "ack-upgraded").contains(name)
                    || values.putIfAbsent(name, arg.substring(equals + 1)) != null) throw new IllegalArgumentException("Unknown or repeated option.");
        }
        String action = values.get("online");
        if (action == null || !List.of("begin", "step", "status", "abort").contains(action)) throw new IllegalArgumentException("Invalid action.");
        boolean begin = action.equals("begin"), needsKafka = begin || action.equals("step");
        UUID run = values.containsKey("run") ? UUID.fromString(values.get("run")) : null;
        String endpoint = values.get("endpoint"), alias = values.get("alias"), servers = values.get("bootstrap-servers");
        if (begin != (run == null) || begin != (endpoint != null) || begin != (alias != null)
                || needsKafka != (servers != null) || (begin && !"true".equals(values.get("ack-upgraded")))
                || (!begin && values.containsKey("ack-upgraded"))) throw new IllegalArgumentException("Invalid action options.");
        if (servers != null && (!servers.matches("(127\\.0\\.0\\.1|localhost):[1-9][0-9]{0,4}")
                || Integer.parseInt(servers.substring(servers.lastIndexOf(':') + 1)) > 65535)) {
            throw new IllegalArgumentException("One loopback broker required.");
        }
        if (begin) {
            if (alias.startsWith("offers-build-")) throw new IllegalArgumentException("Private shadow alias is not a live route.");
            try (var ignored = new OpenSearchIndex(endpoint, alias, JsonMapper.builder().build())) { }
        }
        return new Options(action, run, endpoint, alias, servers);
    }
    static int run(String[] args) {
        try {
            Options options = parse(args);
            var application = new SpringApplication(Application.class);
            try (var context = application.run("--spring.profiles.active=postgres-local",
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                    "--offers.publisher.enabled=false", "--offers.indexer.enabled=false", "--offers.search.enabled=false")) {
                var json = context.getBean(JsonMapper.class);
                var store = new OnlineRebuildStore(context.getBean(JdbcTemplate.class), context.getBean(PlatformTransactionManager.class), json);
                try (var kafka = options.servers() == null ? null : new KafkaWindow(options.servers())) {
                    var coordinator = new OnlineRebuild(store, kafka, json);
                    OnlineRebuildStore.Run run;
                    String result;
                    if (options.action().equals("begin")) { run = coordinator.create(options.endpoint(), options.alias()); result = "CAPTURED"; }
                    else {
                        result = switch (options.action()) {
                            case "step" -> coordinator.step(options.run()).name();
                            case "abort" -> coordinator.abort(options.run()).name();
                            default -> "STATUS";
                        };
                        run = store.get(options.run());
                    }
                    var output = new LinkedHashMap<String, Object>();
                    output.put("result", result); output.put("run", run); output.put("cursors", store.cursors(run.id()));
                    output.put("candidate", run.candidate() == null ? null : store.snapshots.get(run.candidate()));
                    System.out.println(json.writeValueAsString(output));
                    if (result.equals("RETRY") || result.equals("WAITING")
                            || (result.equals("BUSY_OR_TERMINAL") && !List.of("ACTIVE", "ABORTED").contains(run.state()))) return 3;
                    return 0;
                }
            }
        } catch (Exception failure) {
            System.err.println("Coordinated rebuild failed. Inspect persisted run status; a pause or uncertain alias handoff may remain. Do not manually unpause or roll back.");
            return 2;
        }
    }
}
