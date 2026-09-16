package dev.sonti.offers;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KafkaWindowTest {
    private KafkaWindow.Boundary boundary(String id, int partition, long beginning, long end) {
        return new KafkaWindow.Boundary(id, List.of(new KafkaWindow.Offset(partition, beginning, end)));
    }
    @Test void acceptsEmptyAndExactlyBoundedWindows() {
        var start = boundary("topic", 0, 0, 7);
        assertThatCode(() -> KafkaWindow.validateSeal(start, start)).doesNotThrowAnyException();
        assertThatCode(() -> KafkaWindow.validateSeal(start, boundary("topic", 0, 7, 10007))).doesNotThrowAnyException();
    }
    @Test void rejectsTopicPartitionRetentionTruncationAndBudgetChanges() {
        var start = boundary("topic", 0, 0, 7);
        for (var end : List.of(boundary("other", 0, 0, 8), boundary("topic", 1, 0, 8),
                boundary("topic", 0, 8, 9), boundary("topic", 0, 0, 6), boundary("topic", 0, 0, 10008))) {
            assertThatThrownBy(() -> KafkaWindow.validateSeal(start, end)).isInstanceOf(KafkaWindow.InvalidBoundary.class);
        }
    }
}
