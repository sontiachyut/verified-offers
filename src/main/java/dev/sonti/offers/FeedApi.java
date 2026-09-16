package dev.sonti.offers;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/feeds/{tenantId}/{merchantId}")
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.feeds.enabled", havingValue = "true")
class FeedApi {
    private final FeedStore store;
    private final FeedInput parser;
    private final Semaphore uploads = new Semaphore(4);
    FeedApi(FeedStore store, FeedInput parser) { this.store = store; this.parser = parser; }
    @PostMapping(consumes = "application/x-ndjson")
    ResponseEntity<FeedStore.Accepted> upload(@PathVariable String tenantId, @PathVariable String merchantId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Content-SHA256") String sha,
            @RequestHeader("X-Feed-Source") String source, HttpServletRequest request) throws IOException {
        Input.identifier(key); Input.identifier(source);
        if (request.getHeader("Content-Encoding") != null) throw new DomainException(415, "Encoded feeds are not supported.");
        if (!uploads.tryAcquire()) throw new DomainException(429, "Feed upload capacity reached.");
        try {
            var upload = parser.read(request.getInputStream(), request.getContentLengthLong(), sha, tenantId, merchantId);
            var accepted = store.submit(tenantId, merchantId, key, source, upload);
            return ResponseEntity.status(accepted.created() ? 202 : 200).cacheControl(CacheControl.noStore()).body(accepted);
        } finally { uploads.release(); }
    }
    @GetMapping
    FeedStore.Jobs jobs(@PathVariable String tenantId, @PathVariable String merchantId,
            @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
        return store.list(tenantId, merchantId, after, limit);
    }
    @GetMapping("/{id}")
    FeedStore.Job job(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable UUID id) {
        return store.get(tenantId, merchantId, id);
    }
    @GetMapping("/{id}/rows")
    FeedStore.Rows rows(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int after, @RequestParam(defaultValue = "100") int limit) {
        return store.rows(tenantId, merchantId, id, after, limit);
    }
    @GetMapping("/{id}/actions")
    List<FeedStore.Action> actions(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable UUID id) {
        return store.actions(tenantId, merchantId, id);
    }
    record Control(String reason) {}
    @PostMapping("/{id}/{action:retry|cancel}")
    FeedStore.Job control(@PathVariable String tenantId, @PathVariable String merchantId, @PathVariable UUID id,
            @PathVariable String action, @RequestBody Control body) {
        if (body == null) throw new IllegalArgumentException("Reason required.");
        return store.control(tenantId, merchantId, id, action.toUpperCase(java.util.Locale.ROOT), body.reason());
    }
}
