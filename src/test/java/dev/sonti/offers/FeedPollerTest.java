package dev.sonti.offers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedPollerTest {
    @Test void dependencyFailureDoesNotCancelFuturePollsOrLogPayloads() {
        var worker = mock(FeedWorker.class);
        when(worker.step()).thenThrow(new IllegalStateException("private-fixture"))
                .thenReturn(FeedWorker.Result.COMPLETED);
        var meters = new SimpleMeterRegistry();
        try {
            var poller = new FeedPoller(worker, meters); poller.run(); poller.run();
            assertThat(meters.get("offers.feeds.polls").tag("result", "database_error").counter().count()).isEqualTo(1);
            assertThat(meters.get("offers.feeds.polls").tag("result", "completed").counter().count()).isEqualTo(1);
        } finally { meters.close(); }
    }
    @Test void shutdownInterruptDoesNotClaimAnotherJob() {
        var worker = mock(FeedWorker.class);
        var meters = new SimpleMeterRegistry();
        try {
            Thread.currentThread().interrupt();
            try { new FeedPoller(worker, meters).run(); }
            finally { Thread.interrupted(); }
            verifyNoInteractions(worker);
        } finally { meters.close(); }
    }
}
