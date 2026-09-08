package dev.sonti.offers;

public interface OfferCatalog {
    Offer ingest(Offer offer);
    Offer get(String tenantId, String merchantId, String offerId);
    Catalog.Verification verify(Catalog.Claim claim);
}
