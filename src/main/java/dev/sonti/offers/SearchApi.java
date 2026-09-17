package dev.sonti.offers;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.search.enabled", havingValue = "true")
class SearchApi {
    private final SearchPages search;
    private final ApiAccess access;
    SearchApi(SearchPages search, ApiAccess access) { this.search = search; this.access = access; }
    @GetMapping("/api/v1/search")
    SearchPages.Page search(@RequestParam String tenantId, @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(required = false) String cursor) {
        access.read(tenantId);
        return search.search(tenantId, q, limit, cursor);
    }
    @DeleteMapping("/api/v1/search")
    java.util.Map<String, Boolean> close(@RequestParam String tenantId, @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit, @RequestParam String cursor) {
        access.read(tenantId);
        search.cancel(tenantId, q, limit, cursor);
        return java.util.Map.of("closed", true);
    }
}
