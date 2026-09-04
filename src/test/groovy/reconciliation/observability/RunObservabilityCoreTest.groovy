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
        // VERIFY sits BEFORE WRITE_OUTPUT: the compare stage materializes its own Spark output and
        // the verification passes recheck it, so the artifact WRITE_OUTPUT finalizes -- and the run
        // row it persists -- is the verified one. A timeline that wrote before verifying read as if
        // the results were settled while they were still being corrected.
        // One code per verification pass, in the order they execute. They shared a single VERIFY
        // code until a run that performed two of them showed two byte-identical timeline rows.
        assertEquals(5, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_MISSING))
        assertEquals(6, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_EXCHANGE))
        assertEquals(7, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_RETURNS))
        assertEquals(8, RunObservability.stageSequenceOf(RunObservability.STAGE_WRITE_OUTPUT))
        assertEquals(9, RunObservability.stageSequenceOf(RunObservability.STAGE_NOTIFY))
        // Runs recorded before the split stored the retired code; it keeps a sequence so their
        // stored rows still sort into the same slot rather than collapsing to 0.
        assertEquals(5, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY))
        assertEquals(0, RunObservability.stageSequenceOf("UNKNOWN_STAGE"))
    }
}
