package dev.sonti.offers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.search.enabled", havingValue = "true")
class SearchApi {
    private final OfferSearch search;
    SearchApi(OfferSearch search) { this.search = search; }
    @GetMapping("/api/v1/search")
    OfferSearch.Results search(@RequestParam String tenantId, @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(required = false) String cursor) {
        if (cursor != null) throw new IllegalArgumentException("Cursor pagination is not implemented.");
        return search.search(tenantId, q, limit);
    }
}
