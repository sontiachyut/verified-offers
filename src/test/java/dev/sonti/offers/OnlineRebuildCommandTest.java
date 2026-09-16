package dev.sonti.offers;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OnlineRebuildCommandTest {
    @Test void requiresUpgradeAcknowledgementAndBindsEndpointOnlyAtBegin() {
        var begin = new String[]{"--online=begin", "--alias=offers-local", "--endpoint=http://127.0.0.1:9201",
                "--bootstrap-servers=localhost:9094", "--ack-upgraded=true"};
        assertThat(OnlineRebuildCommand.parse(begin).action()).isEqualTo("begin");
        assertThat(OnlineRebuildCommand.parse(new String[]{"--online=step", "--run=" + UUID.randomUUID(),
                "--bootstrap-servers=127.0.0.1:9094"}).action()).isEqualTo("step");
        assertThat(OnlineRebuildCommand.parse(new String[]{"--online=abort", "--run=" + UUID.randomUUID()}).servers()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> OnlineRebuildCommand.parse(java.util.Arrays.copyOf(begin, 4)));
    }
    @Test void rejectsRemoteDuplicateAndWorkerOverrideArguments() {
        String id = UUID.randomUUID().toString();
        for (String[] args : new String[][]{
                {"--online=step", "--run=" + id, "--bootstrap-servers=example.com:9092"},
                {"--online=step", "--run=" + id, "--bootstrap-servers=localhost:99999"},
                {"--online=status", "--run=" + id, "--online=status"},
                {"--online=status", "--run=" + id, "--offers.indexer.enabled=true"},
                {"--online=abort", "--run=" + id, "--endpoint=http://127.0.0.1:9201"},
                {"--online=rollback", "--run=" + id}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> OnlineRebuildCommand.parse(args));
        }
    }
}
