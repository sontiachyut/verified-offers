package dev.sonti.offers;

final class FeedWorker {
    enum Result { IDLE, PROGRESSED, COMPLETED, RETRY, LEASE_LOST }
    private final FeedStore store;
    FeedWorker(FeedStore store) { this.store = store; }
    Result step() {
        var maybe = store.claim();
        if (maybe.isEmpty()) return Result.IDLE;
        var claim = maybe.get();
        try {
            for (int i = 0; i < 25 && !Thread.currentThread().isInterrupted(); i++) {
                if (!store.processOne(claim)) return Result.COMPLETED;
            }
            store.release(claim); return Result.PROGRESSED;
        } catch (FeedStore.LeaseLost lost) { return Result.LEASE_LOST; }
        catch (RuntimeException failure) { store.fail(claim); return Result.RETRY; }
    }
}
