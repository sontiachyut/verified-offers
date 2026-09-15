package dev.sonti.offers;

public interface OfferCatalog {
    Offer ingest(Offer offer);
    Offer get(String tenantId, String merchantId, String offerId);
    Catalog.Verification verify(Catalog.Claim claim);

    /** Bounded reference fallback; persistent adapters should batch this read. */
    default java.util.List<Offer> currentSnapshots(java.util.List<Offer> candidates) {
        if (candidates.size() > 250) throw new IllegalArgumentException("Too many candidates.");
        var current = new java.util.ArrayList<Offer>();
        for (var candidate : candidates) {
            try { current.add(get(candidate.tenantId(), candidate.merchantId(), candidate.offerId())); }
            catch (DomainException missing) { if (missing.status() != 404) throw missing; }
        }
        return java.util.List.copyOf(current);
    }
}
