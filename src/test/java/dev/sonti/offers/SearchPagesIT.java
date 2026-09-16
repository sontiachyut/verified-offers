package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class SearchPagesIT extends PostgresFixture {
    static final GenericContainer<?> search = SearchTestContainer.create();
    private final JsonMapper json = JsonMapper.builder().build();
    private OpenSearchIndex index;
    private String alias;
    private PostgresCatalog catalog;
    @BeforeAll static void startSearch() { search.start(); }
    @AfterAll static void stopSearch() { search.stop(); }
    @BeforeEach void prepare() {
        sql.execute("TRUNCATE offer_key,offer_head,offer_version,outbox CASCADE");
        alias = "pages-" + UUID.randomUUID();
        index = new OpenSearchIndex(endpoint(), alias, json); index.create();
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
    }
    @AfterEach void closeIndex() { index.close(); }
    private String endpoint() { return "http://127.0.0.1:" + search.getMappedPort(9200); }
    private Offer offer(String id, long version, boolean deleted) {
        return new Offer("demo", "merchant", id, version, "Mechanical keyboard", 999 + version, "USD", 5, Instant.now(), deleted);
    }
    private Offer ingest(String id, long version, boolean deleted) {
        Offer offer = catalog.ingest(offer(id, version, deleted)); index.project(offer); return offer;
    }
    private SearchPages pages() {
        return new SearchConfiguration().searchPages(index, new OfferSearch(index::candidates, catalog, Clock.systemUTC()), Clock.systemUTC(), json);
    }
    @Test void verifiedPagesRejectChangedAndDeletedFactsAfterAliasHandoff() {
        for (String id : List.of("a", "b", "c", "d", "e", "f", "g", "h")) ingest(id, 1, false);
        index.refresh();
        try (var pages = pages()) {
            var first = pages.search("demo", "keyboard", 2, null);
            assertThat(first.results().stream().map(r -> r.offer().offerId())).containsExactly("a", "b");
            ingest("c", 2, false); ingest("d", 2, true); ingest("aa", 1, false); index.refresh();
            UUID candidateId = UUID.randomUUID();
            try (var candidate = new OpenSearchIndex(endpoint(), "offers-build-" + candidateId, json)) {
                candidate.ensure(candidateId); candidate.project(List.of(offer("replacement", 1, false)));
                candidate.freeze(); index.promote(alias + "-v1", candidate, candidateId);
            }
            var second = pages.search("demo", "keyboard", 2, first.nextCursor());
            assertThat(second.results().stream().map(r -> r.offer().offerId())).containsExactly("e", "f");
            var third = pages.search("demo", "keyboard", 2, second.nextCursor());
            assertThat(third.results().stream().map(r -> r.offer().offerId())).containsExactly("g", "h");
            assertThat(third.nextCursor()).isNull();
            assertThat(third.results()).allMatch(r -> r.verification().outcome() == Catalog.Outcome.VERIFIED);
        }
    }
    @Test void continuationSurvivesTemporaryIndexOutageWithoutOpeningNewSnapshot() {
        for (String id : List.of("a", "b", "c")) ingest(id, 1, false);
        index.refresh();
        try (var pages = pages()) {
            String cursor = pages.search("demo", "keyboard", 1, null).nextCursor();
            search.getDockerClient().pauseContainerCmd(search.getContainerId()).exec();
            try {
                assertThatThrownBy(() -> pages.search("demo", "keyboard", 1, cursor))
                        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(503));
            } finally { search.getDockerClient().unpauseContainerCmd(search.getContainerId()).exec(); }
            ingest("aa", 1, false); index.refresh();
            assertThat(pages.search("demo", "keyboard", 1, cursor).results().getFirst().offer().offerId()).isEqualTo("b");
        }
    }
    @Test void packagedHttpCursorBindingContinuationCloseAndRestart() throws Exception {
        for (String id : List.of("a", "b", "c", "d", "e")) ingest(id, 1, false);
        index.refresh();
        String path = "/api/v1/search?tenantId=demo&q=keyboard&limit=1";
        String restartCursor;
        String[] options = {"--offers.search.enabled=true", "--offers.search.endpoint=" + endpoint(), "--offers.search.index=" + alias};
        try (var app = new RunningApplication(options)) {
            var first = app.request("GET", path, null, 200);
            String cursor = first.path("nextCursor").asString();
            assertThat(cursor).isNotBlank(); assertThat(first.path("expiresAt").asString()).isNotBlank();
            assertThat(first.path("results").get(0).path("offer").path("offerId").asString()).isEqualTo("a");
            app.request("GET", path.replace("tenantId=demo", "tenantId=other") + "&cursor=" + cursor, null, 400);
            app.request("GET", path.replace("limit=1", "limit=2") + "&cursor=" + cursor, null, 400);
            var second = app.request("GET", path + "&cursor=" + cursor, null, 200);
            assertThat(second.path("results").get(0).path("offer").path("offerId").asString()).isEqualTo("b");
            app.request("DELETE", path + "&cursor=" + cursor, null, 200);
            app.request("GET", path + "&cursor=" + cursor, null, 410);
            restartCursor = app.request("GET", path, null, 200).path("nextCursor").asString();
        }
        try (var restarted = new RunningApplication(options)) {
            restarted.request("GET", path + "&cursor=" + restartCursor, null, 410);
            assertThat(restarted.request("GET", path, null, 200).path("results").size()).isEqualTo(1);
        }
    }
    @Test void pitKeepsTieOrderingAcrossRefreshAndAliasReplacement() {
        var originals = new ArrayList<Offer>();
        for (String id : List.of("a", "b", "c", "d", "e", "f")) originals.add(ingest(id, 1, false));
        index.refresh();
        String pit = index.openPointInTime();
        try {
            var first = index.page(pit, "demo", "keyboard", 2, null);
            assertThat(first.stream().map(OpenSearchIndex.Hit::offer)).containsExactlyElementsOf(originals.subList(0, 2));
            ingest("c", 2, false); ingest("d", 2, true); ingest("aa", 1, false); index.refresh();
            UUID candidateId = UUID.randomUUID();
            try (var candidate = new OpenSearchIndex(endpoint(), "offers-build-" + candidateId, json)) {
                candidate.ensure(candidateId); candidate.project(List.of(offer("replacement", 1, false)));
                candidate.freeze(); index.promote(alias + "-v1", candidate, candidateId);
            }
            var remaining = new ArrayList<Offer>();
            var after = first.getLast().sort();
            for (int i = 0; i < 4; i++) {
                var page = index.page(pit, "demo", "keyboard", 2, after);
                remaining.addAll(page.stream().map(OpenSearchIndex.Hit::offer).toList());
                if (page.isEmpty()) break;
                after = page.getLast().sort();
            }
            assertThat(remaining).containsExactlyElementsOf(originals.subList(2, 6));
            assertThat(index.page(pit, "other", "keyboard", 2, null)).isEmpty();
        } finally { index.closePointsInTime(List.of(pit)); }
        assertThatThrownBy(() -> index.page(pit, "demo", "keyboard", 2, null)).isInstanceOf(DomainException.class);
    }
}
