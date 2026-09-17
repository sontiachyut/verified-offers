package dev.sonti.offers;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedAdmissionTest {
    @Test void fourConcurrentUploadsBoundMemoryAndFifthIsRejectedWithoutReading() throws Exception {
        var store = mock(FeedStore.class);
        when(store.submit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(new FeedStore.Accepted(null, true));
        var api = new FeedApi(store, new FeedInput(Clock.systemUTC()), new ApiAccess(false));
        byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entered = new CountDownLatch(4); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 4; i++) {
                    var request = mock(HttpServletRequest.class);
                    when(request.getContentLengthLong()).thenReturn(-1L);
                    when(request.getInputStream()).thenReturn(new ServletInputStream() {
                        int position;
                        public boolean isFinished() { return position == bytes.length; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        public int read() throws java.io.IOException {
                            if (position == 0) {
                                entered.countDown();
                                try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new java.io.IOException("fixture interrupted"); }
                            }
                            return position < bytes.length ? bytes[position++] : -1;
                        }
                    });
                    futures.add(executor.submit(() -> api.upload("demo", "merchant", "fixture", FeedInput.sha256(bytes), "synthetic", request)));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var rejected = mock(HttpServletRequest.class);
                assertThatThrownBy(() -> api.upload("demo", "merchant", "fixture", FeedInput.sha256(bytes), "synthetic", rejected))
                        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(429));
                verify(rejected, never()).getInputStream();
            } finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            verify(store, times(4)).submit(anyString(), anyString(), anyString(), anyString(), any());
        }
    }
}
