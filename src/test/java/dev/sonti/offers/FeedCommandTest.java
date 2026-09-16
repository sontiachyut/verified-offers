package dev.sonti.offers;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FeedCommandTest {
    @Test void stepIsBoundedQueueActionAndStatusRequiresScope() {
        assertThat(FeedCommand.parse(new String[]{"--feed=step"}).action()).isEqualTo("step");
        assertThat(FeedCommand.parse(new String[]{"--feed=status", "--tenant=demo", "--merchant=merchant", "--job=" + UUID.randomUUID()}).tenant()).isEqualTo("demo");
    }
    @Test void rejectsOverridesIncompleteStatusAndDuplicateArguments() {
        for (String[] args : new String[][]{{"--feed=step", "--offers.feeds.worker-enabled=true"}, {"--feed=status"},
                {"--feed=step", "--feed=step"}, {"--feed=upload"}, {"--feed=step", "--tenant=demo"},
                {"--feed=step", "--rebuild=create"}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> FeedCommand.parse(args));
        }
    }
}
