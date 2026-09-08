package dev.sonti.offers;

import java.time.Duration;
import java.time.Instant;

final class VerificationPolicy {
    private VerificationPolicy() {}
    static Catalog.Verification verify(Offer offer, Catalog.Claim claim, Instant now, Duration freshness) {
        if (offer == null) return new Catalog.Verification(Catalog.Outcome.NOT_FOUND, null, null, now);
        Catalog.Outcome outcome;
        if (offer.deleted()) outcome = Catalog.Outcome.DELETED;
        else if (offer.sourceUpdatedAt().isAfter(now)
                || Duration.between(offer.sourceUpdatedAt(), now).compareTo(freshness) >= 0) outcome = Catalog.Outcome.STALE;
        else if (offer.priceMinor() != claim.priceMinor() || !offer.currency().equals(claim.currency())) outcome = Catalog.Outcome.MISMATCH;
        else if (offer.availableQuantity() == 0) outcome = Catalog.Outcome.UNAVAILABLE;
        else outcome = Catalog.Outcome.VERIFIED;
        return new Catalog.Verification(outcome, offer.version(), offer.sourceUpdatedAt(), now);
    }
}
