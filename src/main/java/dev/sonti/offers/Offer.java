package dev.sonti.offers;

import java.time.Instant;

public record Offer(String tenantId, String merchantId, String offerId, long version,
                    String title, long priceMinor, String currency, int availableQuantity,
                    Instant sourceUpdatedAt, boolean deleted) {
    public Offer {
        Input.identifier(tenantId);
        Input.identifier(merchantId);
        Input.identifier(offerId);
        if (version < 1 || title == null || title.isBlank() || title.length() > 200
                || priceMinor < 0 || !"USD".equals(currency) || availableQuantity < 0
                || sourceUpdatedAt == null) {
            throw new IllegalArgumentException("Invalid offer facts.");
        }
        sourceUpdatedAt = sourceUpdatedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
