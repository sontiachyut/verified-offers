package dev.sonti.offers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogTest {
    private final MutableClock clock = new MutableClock();
    private final Catalog catalog = new Catalog(clock, Duration.ofMinutes(5), 100);
    private Offer offer(long version, long price, int stock, boolean deleted) {
        return new Offer("tenant", "merchant", "item", version, "Headphones", price, "USD", stock, clock.instant(), deleted);
    }
    private Catalog.Claim claim(long price) { return new Catalog.Claim("tenant", "merchant", "item", price, "USD"); }

    @Test void exactCurrentFactsVerifyWithProvenance() {
        catalog.ingest(offer(1, 1000, 1, false));
        var result = catalog.verify(claim(1000));
        assertThat(result.outcome()).isEqualTo(Catalog.Outcome.VERIFIED);
        assertThat(result.sourceVersion()).isEqualTo(1);
        assertThat(result.sourceUpdatedAt()).isEqualTo(clock.instant());
    }
    @Test void identicalVersionReplays() {
        var first = offer(1, 1000, 1, false);
        assertThat(catalog.ingest(first)).isEqualTo(catalog.ingest(first));
    }
    @Test void conflictingSameVersionRejected() {
        catalog.ingest(offer(1, 1000, 1, false));
        assertThatThrownBy(() -> catalog.ingest(offer(1, 900, 1, false)))
                .isInstanceOf(DomainException.class).hasMessageContaining("different facts");
    }
    @Test void lowerVersionCannotOverwriteCurrent() {
        catalog.ingest(offer(2, 2000, 1, false));
        assertThatThrownBy(() -> catalog.ingest(offer(1, 1000, 1, false))).isInstanceOf(DomainException.class);
        assertThat(catalog.get("tenant", "merchant", "item").version()).isEqualTo(2);
    }
    @Test void priceMismatchIsNotVerified() {
        catalog.ingest(offer(1, 1000, 1, false));
        assertThat(catalog.verify(claim(900)).outcome()).isEqualTo(Catalog.Outcome.MISMATCH);
    }
    @Test void unavailableStockIsNotVerified() {
        catalog.ingest(offer(1, 1000, 0, false));
        assertThat(catalog.verify(claim(1000)).outcome()).isEqualTo(Catalog.Outcome.UNAVAILABLE);
    }
    @Test void ttlBoundaryIsStale() {
        catalog.ingest(offer(1, 1000, 1, false));
        clock.advance(Duration.ofSeconds(299));
        assertThat(catalog.verify(claim(1000)).outcome()).isEqualTo(Catalog.Outcome.VERIFIED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.verify(claim(1000)).outcome()).isEqualTo(Catalog.Outcome.STALE);
    }
    @Test void replayDoesNotRefreshSourceTime() {
        var original = offer(1, 1000, 1, false);
        catalog.ingest(original);
        clock.advance(Duration.ofMinutes(6));
        catalog.ingest(original);
        assertThat(catalog.verify(claim(1000)).outcome()).isEqualTo(Catalog.Outcome.STALE);
    }
    @Test void deleteCannotBeResurrectedByOlderEvent() {
        catalog.ingest(offer(2, 1000, 1, true));
        assertThatThrownBy(() -> catalog.ingest(offer(1, 1000, 1, false))).isInstanceOf(DomainException.class);
        assertThat(catalog.verify(claim(1000)).outcome()).isEqualTo(Catalog.Outcome.DELETED);
    }
    @Test void tenantAndMerchantBoundariesAreDistinct() {
        catalog.ingest(offer(1, 1000, 1, false));
        assertThat(catalog.verify(new Catalog.Claim("other", "merchant", "item", 1000, "USD")).outcome())
                .isEqualTo(Catalog.Outcome.NOT_FOUND);
        assertThat(catalog.verify(new Catalog.Claim("tenant", "other", "item", 1000, "USD")).outcome())
                .isEqualTo(Catalog.Outcome.NOT_FOUND);
    }
    @Test void futureSourceTimeRejected() {
        var future = new Offer("tenant", "merchant", "item", 1, "Title", 1, "USD", 1,
                clock.instant().plusSeconds(1), false);
        assertThatThrownBy(() -> catalog.ingest(future)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidFactsRejected() {
        assertThatThrownBy(() -> offer(0, 1000, 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer(1, -1, 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offer(1, 1, -1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Catalog.Claim("t", "m", "o", 1, "EUR")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownLookupReturns404() {
        assertThatThrownBy(() -> catalog.get("tenant", "merchant", "missing"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
    @Test void capacityDoesNotPreventExistingOfferUpdate() {
        var bounded = new Catalog(clock, Duration.ofMinutes(5), 1);
        bounded.ingest(offer(1, 1000, 1, false));
        bounded.ingest(offer(2, 1000, 1, false));
        assertThatThrownBy(() -> bounded.ingest(new Offer("other", "m", "o", 1, "T", 1, "USD", 1, clock.instant(), false)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(503));
    }
    @Test void concurrentVersionsConvergeToMaximumInReferenceAdapter() throws Exception {
        var tasks = new ArrayList<Callable<Void>>();
        for (int version = 1; version <= 100; version++) {
            var incoming = offer(version, version, 1, false);
            tasks.add(() -> {
                try { catalog.ingest(incoming); } catch (DomainException stale) { assertThat(stale.status()).isEqualTo(409); }
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(tasks)) future.get();
        }
        assertThat(catalog.get("tenant", "merchant", "item").version()).isEqualTo(100);
    }
}
