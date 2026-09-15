package dev.sonti.offers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublisherPollerTest {
    @Test void databaseFailureDoesNotCancelLaterPolls() {
        var relay = mock(OutboxRelay.class);
        when(relay.publishNext()).thenThrow(new IllegalStateException("Database unavailable"))
                .thenReturn(OutboxRelay.Result.PUBLISHED);
        var registry = new SimpleMeterRegistry();
        try {
            var poller = new PublisherPoller(relay, registry);
            poller.run();
            poller.run();
            assertThat(registry.get("offers.publisher.polls").tag("result", "database_error").counter().count()).isEqualTo(1);
            assertThat(registry.get("offers.publisher.polls").tag("result", "published").counter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test void interruptedWorkerDoesNotClaimMoreWork() {
        var relay = mock(OutboxRelay.class);
        var registry = new SimpleMeterRegistry();
        try {
            Thread.currentThread().interrupt();
            try { new PublisherPoller(relay, registry).run(); }
            finally { Thread.interrupted(); }
            verifyNoInteractions(relay);
        } finally { registry.close(); }
    }
}
