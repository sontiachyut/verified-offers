package dev.sonti.offers;

import java.util.HashMap;
import java.util.function.LongSupplier;

/** Process-local token buckets; bounded state and monotonic time, not a distributed quota. */
final class TenantBudget {
    private record Bucket(double tokens, long touched) {}
    private final HashMap<String, Bucket> buckets = new HashMap<>();
    private final LongSupplier ticker;
    private final int maximumTenants;
    private final double burst, perSecond;
    TenantBudget(int maximumTenants, double burst, double perSecond, LongSupplier ticker) {
        if (maximumTenants < 1 || burst < 1 || perSecond <= 0) throw new IllegalArgumentException("Positive limits required.");
        this.maximumTenants = maximumTenants; this.burst = burst; this.perSecond = perSecond; this.ticker = ticker;
    }
    synchronized boolean acquire(String tenant) {
        long now = ticker.getAsLong();
        var bucket = buckets.get(tenant);
        if (bucket == null) {
            if (buckets.size() >= maximumTenants) buckets.entrySet().removeIf(e -> now - e.getValue().touched() >= 60_000_000_000L);
            if (buckets.size() >= maximumTenants) return false;
            bucket = new Bucket(burst, now);
        }
        double available = Math.min(burst, bucket.tokens() + Math.max(0, now - bucket.touched()) / 1_000_000_000d * perSecond);
        boolean admitted = available >= 1;
        buckets.put(tenant, new Bucket(admitted ? available - 1 : available, now));
        return admitted;
    }
}
