package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Configuration
@Profile("local-demo")
class DemoConfiguration {
    @Bean
    Catalog catalog(Clock clock) {
        return new Catalog(clock, Duration.ofMinutes(5), 10_000);
    }
}

@RestController
@Profile("local-demo")
@RequestMapping("/api/v1")
class OfferApi {
    private final Catalog catalog;
    OfferApi(Catalog catalog) { this.catalog = catalog; }

    @PutMapping("/offers")
    Offer ingest(@RequestBody Offer offer) { return catalog.ingest(offer); }

    @GetMapping("/offers/{tenantId}/{merchantId}/{offerId}")
    Offer get(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable String offerId) {
        return catalog.get(tenantId, merchantId, offerId);
    }

    @PostMapping("/verifications")
    Catalog.Verification verify(@RequestBody Catalog.Claim claim) { return catalog.verify(claim); }
}
