package dev.sonti.offers;

import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "offers.claims.enabled", havingValue = "true")
final class ClaimApi {
    record Request(String tenantId, String merchantId, String offerId, String text) {}
    record Response(ClaimExtractor.Extraction extraction, Catalog.Verification verification) {}
    private final ApiAccess access;
    private final OfferCatalog catalog;
    private final ClaimExtractor extractor = new ClaimExtractor();
    ClaimApi(ApiAccess access, OfferCatalog catalog) { this.access = access; this.catalog = catalog; }
    @PostMapping("/api/v1/claims/extract")
    Response extract(@RequestBody Request request) {
        Input.identifier(request.tenantId()); Input.identifier(request.merchantId()); Input.identifier(request.offerId());
        access.read(request.tenantId());
        var extraction = extractor.extract(request.text());
        var proposal = extraction.proposal();
        var verification = proposal == null ? null : catalog.verify(new Catalog.Claim(request.tenantId(), request.merchantId(),
                request.offerId(), proposal.priceMinor(), proposal.currency()));
        return new Response(extraction, verification);
    }
}
