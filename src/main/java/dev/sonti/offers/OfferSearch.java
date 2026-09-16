package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class OfferSearch {
    @FunctionalInterface public interface Candidates { List<Offer> find(String tenant, String query, int count); }
    public record Result(Offer offer, long indexVersion, Instant indexSourceUpdatedAt, Catalog.Verification verification) {}
    public record Results(List<Result> results) {}
    private final Candidates candidates;
    private final OfferCatalog catalog;
    private final Clock clock;

    public OfferSearch(Candidates candidates, OfferCatalog catalog, Clock clock) {
        this.candidates = candidates;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Results search(String tenant, String query, int limit) {
        Input.identifier(tenant);
        if (query == null || query.isBlank() || query.length() > 200 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid search bounds.");
        }
        var retrieved = candidates.find(tenant, query.strip(), limit * 5);
        return new Results(verified(tenant, retrieved).stream().limit(limit).toList());
    }

    List<Result> verified(String tenant, List<Offer> retrieved) {
        if (retrieved.size() > 250) throw new IllegalArgumentException("Too many search candidates.");
        var results = new ArrayList<Result>();
        for (var candidate : retrieved) {
            if (!tenant.equals(candidate.tenantId())) throw new DomainException(503, "Invalid search scope.");
        }
        var snapshots = new java.util.HashMap<List<String>, Offer>();
        for (var current : catalog.currentSnapshots(retrieved)) snapshots.put(identity(current), current);
        for (var candidate : retrieved) {
            Offer current = snapshots.get(identity(candidate));
            if (!candidate.equals(current)) continue;
            var claim = new Catalog.Claim(tenant, current.merchantId(), current.offerId(), candidate.priceMinor(), candidate.currency());
            var verification = VerificationPolicy.verify(current, claim, clock.instant(), Duration.ofMinutes(5));
            if (verification.outcome() != Catalog.Outcome.VERIFIED) continue;
            results.add(new Result(current, candidate.version(), candidate.sourceUpdatedAt(), verification));
        }
        return List.copyOf(results);
    }
    private static List<String> identity(Offer offer) {
        return List.of(offer.tenantId(), offer.merchantId(), offer.offerId());
    }
}
