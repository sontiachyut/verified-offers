package dev.sonti.offers;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RebuildCommandTest {
    @Test void acceptsOnlyBoundedActionSpecificOptions() {
        assertThat(RebuildCommand.parse(new String[]{"--rebuild=create"}).maxOffers()).isEqualTo(100000);
        assertThat(RebuildCommand.parse(new String[]{"--rebuild=create", "--max-offers=20"}).maxOffers()).isEqualTo(20);
        UUID id = UUID.randomUUID();
        assertThat(RebuildCommand.parse(new String[]{"--rebuild=status", "--job=" + id}).job()).isEqualTo(id);
        assertThat(RebuildCommand.parse(new String[]{"--rebuild=step", "--job=" + id, "--endpoint=http://127.0.0.1:9201"}).action()).isEqualTo("step");
    }
    @Test void rejectsActivationFlagsDuplicatesMissingAndRemoteArguments() {
        UUID id = UUID.randomUUID();
        for (String[] args : new String[][] {
                {"--rebuild=promote"}, {"--rebuild=create", "--job=" + id}, {"--rebuild=step"},
                {"--rebuild=create", "--rebuild=create"}, {"--rebuild=create", "--max-offers=100001"},
                {"--rebuild=create", "--max-offers=0"}, {"--rebuild=create", "--offers.publisher.enabled=true"},
                {"--rebuild=create", "--spring.main.web-application-type=servlet"},
                {"--rebuild=step", "--job=" + id},
                {"--rebuild=step", "--job=" + id, "--endpoint=http://example.com:9200"},
                {"--rebuild=status", "--job=" + id, "--max-offers=10"},
                {"--rebuild=status", "--job=" + id, "--endpoint=http://127.0.0.1:9201"}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> RebuildCommand.parse(args));
        }
    }
}
