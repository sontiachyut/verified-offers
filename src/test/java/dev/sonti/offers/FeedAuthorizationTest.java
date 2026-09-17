package dev.sonti.offers;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FeedAuthorizationTest {
    @Test void allFeedRoutesAuthorizeBeforeReadingOrMutatingStore() {
        var store = mock(FeedStore.class);
        var api = new FeedApi(store, new FeedInput(Clock.systemUTC()), new ApiAccess(true));
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> api.jobs("t", "m", null, 20)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> api.job("t", "m", id)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> api.rows("t", "m", id, 0, 100)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> api.actions("t", "m", id)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> api.control("t", "m", id, "cancel", new FeedApi.Control("test"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> api.upload("t", "m", "key", "sha", "source", null)).isInstanceOf(DomainException.class);
        verifyNoInteractions(store);
    }
}
