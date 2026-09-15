package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/** A failed database poll must not cancel all future scheduled executions. */
public final class PublisherPoller implements Runnable {
    private final OutboxRelay relay;
    private final MeterRegistry meters;

    public PublisherPoller(OutboxRelay relay, MeterRegistry meters) {
        this.relay = relay;
        this.meters = meters;
    }

    @Override public void run() {
        if (Thread.currentThread().isInterrupted()) return;
        String result;
        try { result = relay.publishNext().name().toLowerCase(Locale.ROOT); }
        catch (RuntimeException databaseFailure) { result = "database_error"; }
        meters.counter("offers.publisher.polls", "result", result).increment();
    }
}
