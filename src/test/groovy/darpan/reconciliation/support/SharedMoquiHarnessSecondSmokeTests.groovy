package darpan.reconciliation.support

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

/** DAR-OPS-026: the other half of SharedMoquiHarnessSmokeTests (same checks; whichever runs second proves the reset). */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedMoquiHarnessSecondSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), 'shared-moqui-harness-2')
        SharedMoquiHarnessSmokeTests.assertCleanStart(ec, 'SharedMoquiHarnessSecondSmokeTests')
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void dirtiesEveryKindOfStateTheNextClassMustNotSee() {
        SharedMoquiHarnessSmokeTests.dirty(ec)
    }
}
