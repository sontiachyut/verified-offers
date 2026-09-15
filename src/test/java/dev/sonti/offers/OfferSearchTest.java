package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OfferSearchTest {
    private final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Catalog catalog = new Catalog(clock, Duration.ofMinutes(5), 100);
    private Offer offer(long version, long price, int stock, boolean deleted, Instant time) {
        return new Offer("demo", "merchant", "keyboard", version, "Mechanical keyboard", price, "USD", stock, time, deleted);
    }
    @Test void exactFreshSnapshotCarriesAsOfEvidence() {
        Offer offer = offer(1, 999, 4, false, now);
        catalog.ingest(offer);
        var result = new OfferSearch((tenant, q, n) -> List.of(offer), catalog, clock).search("demo", "keyboard", 1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().verification().outcome()).isEqualTo(Catalog.Outcome.VERIFIED);
        assertThat(result.results().getFirst().verification().verifiedAt()).isEqualTo(now);
        assertThat(result.results().getFirst().indexVersion()).isEqualTo(1);
    }
    @Test void changedVersionIsExcludedEvenWhenPriceStillMatches() {
        Offer old = offer(1, 999, 4, false, now);
        catalog.ingest(offer(2, 999, 4, false, now));
        assertThat(new OfferSearch((t, q, n) -> List.of(old), catalog, clock)
                .search("demo", "keyboard", 10).results()).isEmpty();
    }
    @Test void missingStaleUnavailableAndDeletedNeverAppear() {
        for (Offer offer : List.of(offer(1, 999, 4, false, now.minusSeconds(300)),
                offer(2, 999, 0, false, now), offer(3, 999, 4, true, now))) {
            catalog.ingest(offer);
            assertThat(new OfferSearch((t, q, n) -> List.of(offer), catalog, clock)
                    .search("demo", "keyboard", 10).results()).isEmpty();
        }
        assertThat(new OfferSearch((t, q, n) -> List.of(offer(1, 999, 4, false, now)),
                new Catalog(clock, Duration.ofMinutes(5), 1), clock).search("demo", "keyboard", 1).results()).isEmpty();
    }
    @Test void invalidQueriesNeverReachIndexAndTenantMismatchFailsClosed() {
        var search = new OfferSearch((t, q, n) -> { throw new AssertionError("Unexpected index call"); }, catalog, clock);
        assertThatIllegalArgumentException().isThrownBy(() -> search.search("demo", " ", 10));
        assertThatIllegalArgumentException().isThrownBy(() -> search.search("demo", "x".repeat(201), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> search.search("demo", "x", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> search.search("demo", "x", 51));
        assertThatThrownBy(() -> new OfferSearch((t, q, n) -> List.of(offer(1, 999, 4, false, now)), catalog, clock)
                .search("other", "keyboard", 1)).isInstanceOf(DomainException.class);
    }
}
