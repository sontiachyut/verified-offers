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
@Profile({"local-demo", "postgres-local"})
@RequestMapping("/api/v1")
class OfferApi {
    private final OfferCatalog catalog;
    private final ApiAccess access;
    OfferApi(OfferCatalog catalog, ApiAccess access) { this.catalog = catalog; this.access = access; }

    @PutMapping("/offers")
    Offer ingest(@RequestBody Offer offer) { access.write(offer.tenantId(), offer.merchantId()); return catalog.ingest(offer); }

    @GetMapping("/offers/{tenantId}/{merchantId}/{offerId}")
    Offer get(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable String offerId) {
        access.read(tenantId);
        return catalog.get(tenantId, merchantId, offerId);
    }

    @PostMapping("/verifications")
    Catalog.Verification verify(@RequestBody Catalog.Claim claim) { access.read(claim.tenantId()); return catalog.verify(claim); }
}
