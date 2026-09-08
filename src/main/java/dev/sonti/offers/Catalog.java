package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bounded, volatile reference adapter. Synchronization is NOT distributed concurrency control. */
public final class Catalog implements OfferCatalog {
    private record Key(String tenantId, String merchantId, String offerId) {
        Key {
            Input.identifier(tenantId);
            Input.identifier(merchantId);
            Input.identifier(offerId);
        }
    }
    public enum Outcome { NOT_FOUND, DELETED, STALE, MISMATCH, UNAVAILABLE, VERIFIED }
    public record Claim(String tenantId, String merchantId, String offerId, long priceMinor, String currency) {
        public Claim {
            new Key(tenantId, merchantId, offerId);
            if (priceMinor < 0 || !"USD".equals(currency)) throw new IllegalArgumentException("Invalid claim.");
        }
    }
    public record Verification(Outcome outcome, Long sourceVersion, Instant sourceUpdatedAt, Instant verifiedAt) {}

    private final Map<Key, Offer> offers = new HashMap<>();
    private final Clock clock;
    private final Duration freshness;
    private final int capacity;

    public Catalog(Clock clock, Duration freshness, int capacity) {
        if (clock == null || freshness == null || freshness.isNegative() || freshness.isZero() || capacity < 1) {
            throw new IllegalArgumentException("Invalid catalog configuration.");
        }
        this.clock = clock;
        this.freshness = freshness;
        this.capacity = capacity;
    }

    public synchronized Offer ingest(Offer offer) {
        if (offer.sourceUpdatedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Source timestamp is in the future.");
        }
        Key key = new Key(offer.tenantId(), offer.merchantId(), offer.offerId());
        Offer current = offers.get(key);
        if (current != null) {
            if (offer.version() < current.version()) throw new DomainException(409, "Stale source version.");
            if (offer.version() == current.version()) {
                if (!offer.equals(current)) throw new DomainException(409, "Same version has different facts.");
                return current;
            }
        } else if (offers.size() >= capacity) {
            throw new DomainException(503, "Local demo catalog capacity reached. Restart with synthetic fixtures.");
        }
        offers.put(key, offer);
        return offer;
    }

    public synchronized Offer get(String tenantId, String merchantId, String offerId) {
        Offer offer = offers.get(new Key(tenantId, merchantId, offerId));
        if (offer == null) throw new DomainException(404, "Offer not found.");
        return offer;
    }

    public synchronized Verification verify(Claim claim) {
        Offer offer = offers.get(new Key(claim.tenantId(), claim.merchantId(), claim.offerId()));
        return VerificationPolicy.verify(offer, claim, clock.instant(), freshness);
    }
}
