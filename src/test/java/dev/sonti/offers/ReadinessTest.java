package dev.sonti.offers;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReadinessTest {
    @Test void dependencyExceptionsAndFalseProbesFailClosedWithoutDetails() {
        var report = ReadinessApi.inspect(Map.of("postgres", () -> true, "search", () -> { throw new IllegalStateException("private-url"); }, "broker", () -> false));
        assertThat(report.status()).isEqualTo("NOT_READY");
        assertThat(report.dependencies()).containsEntry("postgres", "UP").containsEntry("search", "DOWN").containsEntry("broker", "DOWN");
        assertThat(report.toString()).doesNotContain("private-url");
        assertThat(ReadinessApi.inspect(Map.of("postgres", () -> true)).status()).isEqualTo("READY");
    }
}
