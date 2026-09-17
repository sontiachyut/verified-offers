package dev.sonti.offers;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantBudgetTest {
    @Test void independentTenantBurstsRefillWithoutUnboundedAccumulation() {
        var time = new AtomicLong(); var budget = new TenantBudget(2, 2, 1, time::get);
        assertThat(budget.acquire("a")).isTrue(); assertThat(budget.acquire("a")).isTrue();
        assertThat(budget.acquire("a")).isFalse(); assertThat(budget.acquire("b")).isTrue();
        time.addAndGet(1_000_000_000); assertThat(budget.acquire("a")).isTrue();
        assertThat(budget.acquire("a")).isFalse();
        time.addAndGet(100_000_000_000L);
        assertThat(budget.acquire("a")).isTrue(); assertThat(budget.acquire("a")).isTrue();
        assertThat(budget.acquire("a")).isFalse();
    }
    @Test void capacityRejectsNewIdentityInsteadOfEvictingLiveLimits() {
        var time = new AtomicLong(); var budget = new TenantBudget(1, 1, 1, time::get);
        assertThat(budget.acquire("a")).isTrue(); assertThat(budget.acquire("b")).isFalse();
        assertThat(budget.acquire("a")).isFalse();
        time.addAndGet(60_000_000_000L); assertThat(budget.acquire("b")).isTrue();
    }
    @Test void concurrentAcquisitionCannotOverspend() throws Exception {
        var budget = new TenantBudget(1, 10, 1, () -> 0L);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 100).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> budget.acquire("a")).toList();
            long accepted = 0;
            for (var result : workers.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(10);
        }
    }
}
