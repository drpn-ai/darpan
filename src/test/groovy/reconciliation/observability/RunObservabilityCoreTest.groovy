package reconciliation.observability

import darpan.facade.reconciliation.RunObservability
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class RunObservabilityCoreTest {

    @Test
    void terminalAndActiveStatusesArePartitioned() {
        assertTrue(RunObservability.isActiveStatus(RunObservability.STATUS_RUNNING))
        assertTrue(RunObservability.isActiveStatus(RunObservability.STATUS_PENDING))
        assertFalse(RunObservability.isActiveStatus(RunObservability.STATUS_SUCCESS))

        assertTrue(RunObservability.isTerminalStatus(RunObservability.STATUS_SUCCESS))
        assertTrue(RunObservability.isTerminalStatus(RunObservability.STATUS_FAILED))
        assertTrue(RunObservability.isTerminalStatus(RunObservability.STATUS_NO_DATA))
        assertTrue(RunObservability.isTerminalStatus(RunObservability.STATUS_SKIP_DUP))
        assertFalse(RunObservability.isTerminalStatus(RunObservability.STATUS_RUNNING))
    }

    @Test
    void stageSequenceIsOrdered() {
        assertEquals(1, RunObservability.stageSequenceOf(RunObservability.STAGE_RESOLVE))
        assertEquals(4, RunObservability.stageSequenceOf(RunObservability.STAGE_COMPARE))
        // VERIFY sits after WRITE_OUTPUT: the verification pass rechecks (and may rewrite) the
        // written artifact, so it cannot precede it.
        assertEquals(6, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY))
        assertEquals(7, RunObservability.stageSequenceOf(RunObservability.STAGE_NOTIFY))
        assertEquals(0, RunObservability.stageSequenceOf("UNKNOWN_STAGE"))
    }
}
