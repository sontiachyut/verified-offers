package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;

final class FeedPoller implements Runnable {
    private final FeedWorker worker;
    private final MeterRegistry meters;
    FeedPoller(FeedWorker worker, MeterRegistry meters) { this.worker = worker; this.meters = meters; }
    @Override public void run() {
        if (Thread.currentThread().isInterrupted()) return;
        String result;
        try { result = worker.step().name().toLowerCase(java.util.Locale.ROOT); }
        catch (RuntimeException failure) { result = "database_error"; }
        meters.counter("offers.feeds.polls", "result", result).increment();
    }
}
