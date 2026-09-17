package dev.sonti.offers;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.security.web.SecurityFilterChain;
import static org.assertj.core.api.Assertions.*;

class NonWebSecurityTest {
    @Test void operatorCommandsDoNotCreateHttpSecurityOrRequireIssuer() {
        try (var context = new SpringApplication(Application.class).run("--spring.profiles.active=local-demo",
                "--spring.main.web-application-type=none", "--offers.security.enabled=true",
                "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBean(OfferCatalog.class)).isNotNull();
        }
    }
}
