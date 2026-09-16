package dev.sonti.offers;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        if (java.util.Arrays.stream(args).anyMatch(arg -> arg.startsWith("--online="))) {
            int exit = OnlineRebuildCommand.run(args);
            if (exit != 0) System.exit(exit);
            return;
        }
        if (java.util.Arrays.stream(args).anyMatch(arg -> arg.startsWith("--rebuild="))) {
            int exit = RebuildCommand.run(args);
            if (exit != 0) System.exit(exit);
            return;
        }
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner localDemoGuard(Environment environment) {
        return args -> {
            if (environment.matchesProfiles("local-demo") == environment.matchesProfiles("postgres-local")) {
                throw new IllegalStateException("Select exactly one of local-demo or postgres-local. Neither is for public deployment.");
            }
        };
    }
}
