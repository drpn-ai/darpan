package darpan.reconciliation.support

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertNotNull

/**
 * DAR-OPS-026: the other half of SharedMoquiHarnessSmokeTests (same checks; whichever runs second proves the reset).
 * Its @BeforeAll/@AfterAll come from an unmarked base class: shared mode must hold anyway (the harness reads the
 * running class from JUnit, and keys cleanup on the ec, not on the caller).
 */
@SharedMoqui(quietAtEnd = false)
class SharedMoquiHarnessSecondSmokeTests extends SharedMoquiHarnessInheritedLifecycle {
    @Test
    void dirtiesEveryKindOfStateTheNextClassMustNotSee() {
        SharedMoquiHarnessSmokeTests.dirty(ec)
    }
}

/** Unmarked base holding the lifecycle methods, as TwoFactorTestSupport does for its subclasses. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SharedMoquiHarnessInheritedLifecycle {
    protected ExecutionContext ec

    @BeforeAll
    void boot() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), 'shared-moqui-harness-2')
        SharedMoquiHarnessSmokeTests.assertCleanStart(ec, getClass().simpleName)
    }

    @AfterAll
    void shutDown() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
        // An inherited @AfterAll must not take the unmarked branch and destroy the shared ECF.
        assertNotNull(ReconciliationSmokeTestSupport.sharedEcf())
    }
}
