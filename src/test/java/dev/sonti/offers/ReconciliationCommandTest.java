package dev.sonti.offers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReconciliationCommandTest {
    @Test void exactPrepareOptionsAndScopedIdentifiers() {
        assertThat(ReconciliationCommand.parse(new String[] {"--reconcile=prepare", "--topic=offers.v1", "--partition=0", "--offset=1",
                "--tenant=t", "--merchant=m", "--offer=o", "--operator=owner", "--reason=repaired"})).hasSize(9);
    }
    @Test void unknownOptionsCannotStartWorkersOrOverrideProfile() {
        for (String[] args : new String[][] {{"--reconcile=step"}, {"--reconcile=drop"},
                {"--reconcile=status", "--id=00000000-0000-0000-0000-000000000000", "--offers.indexer.enabled=true"},
                {"--reconcile=status", "--reconcile=step", "--id=00000000-0000-0000-0000-000000000000"}})
            assertThatThrownBy(() -> ReconciliationCommand.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }
}
