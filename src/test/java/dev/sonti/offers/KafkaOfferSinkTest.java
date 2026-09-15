package dev.sonti.offers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaOfferSinkTest {
    @Test void localEnqueueDoesNotCountAsBrokerAcknowledgement() throws Exception {
        @SuppressWarnings("unchecked") var producer = (Producer<String, String>) mock(Producer.class);
        var acknowledged = new CompletableFuture<RecordMetadata>();
        var enqueued = new CountDownLatch(1);
        when(producer.send(any())).thenAnswer(call -> { enqueued.countDown(); return acknowledged; });
        try (var sink = new KafkaOfferSink(producer); var executor = Executors.newSingleThreadExecutor()) {
            var event = new PostgresOutbox.Delivery(UUID.randomUUID(), UUID.randomUUID(), "tenant:merchant:item", "{}", 1);
            var sending = executor.submit(() -> { sink.publish(event); return true; });
            try {
                assertThat(enqueued.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(sending.isDone()).isFalse();
                acknowledged.complete(null);
                assertThat(sending.get(3, TimeUnit.SECONDS)).isTrue();
                verify(producer).send(new ProducerRecord<>(KafkaOfferSink.TOPIC, event.key(), event.payload()));
            } finally { acknowledged.completeExceptionally(new IllegalStateException("Test cleanup")); }
        }
    }

    @Test void asynchronousBrokerFailurePropagates() {
        @SuppressWarnings("unchecked") var producer = (Producer<String, String>) mock(Producer.class);
        when(producer.send(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Rejected")));
        try (var sink = new KafkaOfferSink(producer)) {
            assertThatThrownBy(() -> sink.publish(new PostgresOutbox.Delivery(UUID.randomUUID(), UUID.randomUUID(), "key", "{}", 1)))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        }
    }
}
