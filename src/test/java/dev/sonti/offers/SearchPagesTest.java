package dev.sonti.offers;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SearchPagesTest {
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private final MutableClock clock = new MutableClock();
    private final Catalog catalog = new Catalog(clock, Duration.ofMinutes(5), 20000);
    private final FakeSource source = new FakeSource();
    private final SearchPages pages = new SearchPages(source,
            new OfferSearch((t, q, n) -> { throw new AssertionError("No live retrieval during PIT search"); }, catalog, clock),
            clock, JsonMapper.builder().build());
    private final class FakeSource implements SearchPages.Source {
        final List<OpenSearchIndex.Hit> hits = new ArrayList<>();
        int opens, reads, closes;
        boolean failOpen, failRead, failClose;
        public String open() { opens++; if (failOpen) throw new DomainException(503, "fixture"); return "pit-" + opens; }
        public List<OpenSearchIndex.Hit> read(String pit, String t, String q, int count, List<Object> after) {
            reads++; if (failRead) throw new DomainException(503, "fixture");
            int begin = after == null ? 0 : Integer.parseInt((String) after.get(2)) + 1;
            return List.copyOf(hits.subList(Math.min(begin, hits.size()), Math.min(begin + count, hits.size())));
        }
        public void close(List<String> pits) { closes += pits.size(); if (failClose) throw new DomainException(503, "fixture"); }
    }
    private void fixtures(int count, boolean eligible) {
        for (int i = 0; i < count; i++) {
            Offer offer = new Offer("demo", "merchant", String.valueOf(i), 1, "Keyboard", 999, "USD", 5, clock.instant(), false);
            source.hits.add(new OpenSearchIndex.Hit(offer, List.of(1.0, "merchant", String.valueOf(i))));
            if (eligible) catalog.ingest(offer);
        }
    }
    private void status(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable task) {
        assertThatThrownBy(task).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(expected));
    }
    @Test void doesNotSkipPrefetchedCandidatesAndRechecksEveryPage() {
        fixtures(12, true);
        var first = pages.search("demo", " keyboard ", 2, null);
        assertThat(first.results().stream().map(r -> r.offer().offerId())).containsExactly("0", "1");
        Offer old = source.hits.get(2).offer();
        catalog.ingest(new Offer("demo", "merchant", "2", 2, "Keyboard", 1999, "USD", 5, clock.instant(), false));
        var second = pages.search("demo", "keyboard", 2, first.nextCursor());
        assertThat(second.results().stream().map(r -> r.offer().offerId())).containsExactly("3", "4");
        assertThat(second.results()).noneMatch(r -> r.offer().equals(old));
        var ids = new ArrayList<String>(List.of("0", "1", "3", "4"));
        String next = second.nextCursor();
        while (next != null) {
            var page = pages.search("demo", "keyboard", 2, next);
            ids.addAll(page.results().stream().map(r -> r.offer().offerId()).toList()); next = page.nextCursor();
        }
        assertThat(ids).containsExactly("0", "1", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(source.opens).isEqualTo(1); assertThat(source.closes).isEqualTo(1);
        status(410, () -> pages.search("demo", "keyboard", 2, first.nextCursor()));
    }
    @Test void emptyVerifiedPageStillAdvancesAndReportsScanBudget() {
        fixtures(10001, false);
        var page = pages.search("demo", "keyboard", 50, null);
        assertThat(page.results()).isEmpty(); assertThat(page.nextCursor()).isNotNull();
        for (int i = 1; i < 40; i++) page = pages.search("demo", "keyboard", 50, page.nextCursor());
        assertThat(page.results()).isEmpty(); assertThat(page.nextCursor()).isNull();
        assertThat(page.scanLimitReached()).isTrue(); assertThat(source.reads).isEqualTo(40);
    }
    @Test void rejectsScopeTamperingAndExpiresWithoutRenewal() {
        fixtures(20, true);
        var first = pages.search("demo", "keyboard", 1, null); String token = first.nextCursor();
        assertThatIllegalArgumentException().isThrownBy(() -> pages.search("other", "keyboard", 1, token));
        assertThatIllegalArgumentException().isThrownBy(() -> pages.search("demo", "mouse", 1, token));
        assertThatIllegalArgumentException().isThrownBy(() -> pages.search("demo", "keyboard", 2, token));
        String altered = token.substring(0, token.indexOf('.') + 1) + (token.charAt(token.indexOf('.') + 1) == 'A' ? "B" : "A")
                + token.substring(token.indexOf('.') + 2);
        assertThatIllegalArgumentException().isThrownBy(() -> pages.search("demo", "keyboard", 1, altered));
        assertThatIllegalArgumentException().isThrownBy(() -> pages.search("demo", "keyboard", 1, "x".repeat(2049)));
        assertThat(source.reads).isEqualTo(1);
        clock.now = clock.now.plusSeconds(119);
        var second = pages.search("demo", "keyboard", 1, token);
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        clock.now = clock.now.plusSeconds(1);
        status(410, () -> pages.search("demo", "keyboard", 1, second.nextCursor()));
    }
    @Test void failedOpenAndCloseStillConsumeAdmissionUntilBackendExpiryGrace() {
        source.failOpen = true;
        for (int i = 0; i < 128; i++) status(503, () -> pages.search("demo", "keyboard", 1, null));
        status(429, () -> pages.search("demo", "keyboard", 1, null));
        assertThat(source.opens).isEqualTo(128);
        clock.now = clock.now.plusSeconds(130); source.failOpen = false; source.failClose = true;
        for (int i = 0; i < 128; i++) assertThat(pages.search("demo", "keyboard", 1, null).nextCursor()).isNull();
        status(429, () -> pages.search("demo", "keyboard", 1, null));
    }
    @Test void retryUsesSamePitAndCancelInvalidatesPriorCursors() {
        fixtures(12, true);
        String token = pages.search("demo", "keyboard", 1, null).nextCursor();
        source.failRead = true; status(503, () -> pages.search("demo", "keyboard", 1, token));
        source.failRead = false;
        assertThat(pages.search("demo", "keyboard", 1, token).results().getFirst().offer().offerId()).isEqualTo("1");
        assertThat(source.opens).isEqualTo(1);
        pages.cancel("demo", "keyboard", 1, token);
        status(410, () -> pages.search("demo", "keyboard", 1, token));
        assertThat(source.closes).isEqualTo(1);
    }
    @Test void restartLostCursorFailsClosedAndInitialFailureReleasesKnownPit() {
        fixtures(12, true);
        String token = pages.search("demo", "keyboard", 1, null).nextCursor();
        try (var restarted = new SearchPages(source, new OfferSearch((t,q,n) -> List.of(), catalog, clock), clock, JsonMapper.builder().build())) {
            status(410, () -> restarted.search("demo", "keyboard", 1, token));
        }
        source.failRead = true; status(503, () -> pages.search("demo", "keyboard", 1, null));
        assertThat(source.closes).isEqualTo(1);
        assertThat(source.opens).isEqualTo(2);
    }
}
