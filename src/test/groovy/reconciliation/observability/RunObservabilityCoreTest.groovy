package reconciliation.observability

import darpan.facade.reconciliation.RunObservability
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RunObservabilityCoreTest {

    @Test
    void terminalAndActiveStatusesArePartitioned() {
        assertEquals([RunObservability.STATUS_PENDING, RunObservability.STATUS_RUNNING] as Set, RunObservability.ACTIVE_STATUSES)
        assertTrue(RunObservability.ACTIVE_STATUSES.intersect(RunObservability.TERMINAL_STATUSES).isEmpty(), "no status is both")
        [RunObservability.STATUS_SUCCESS, RunObservability.STATUS_FAILED, RunObservability.STATUS_NO_DATA, RunObservability.STATUS_SKIP_DUP,
         RunObservability.STATUS_CANCELLED, RunObservability.STATUS_NOT_RUN].each { String status ->
            assertTrue(RunObservability.isTerminalStatus(status), status)
        }
        assertFalse(RunObservability.isTerminalStatus(RunObservability.STATUS_RUNNING))
        assertFalse(RunObservability.isTerminalStatus(RunObservability.STATUS_PENDING))
    }

    /** S99-074: the shared extract page heartbeat writes nothing without a run to write against (an ad-hoc extract). */
    @Test
    void thePageHeartbeatIsAbsentWithoutARunId() {
        assertNull(RunObservability.throttledProgressListener(null, null, RunObservability.STAGE_EXTRACT_FILE1, 10))
        assertNull(RunObservability.throttledProgressListener(null, "  ", null, null), "a blank run id is no run id")
    }

    @Test
    void stageSequenceIsOrdered() {
        assertEquals(1, RunObservability.stageSequenceOf(RunObservability.STAGE_RESOLVE))
        // DAR-BE-156: an audit's re-check of its older findings reads both sides again by key, so it follows the
        // extracts and precedes the compare that judges what it appended.
        assertEquals(4, RunObservability.stageSequenceOf(RunObservability.STAGE_RECHECK))
        assertEquals(5, RunObservability.stageSequenceOf(RunObservability.STAGE_COMPARE))
        // VERIFY sits BEFORE WRITE_OUTPUT: the compare stage materializes its own Spark output and
        // the verification passes recheck it, so the artifact WRITE_OUTPUT finalizes -- and the run
        // row it persists -- is the verified one. A timeline that wrote before verifying read as if
        // the results were settled while they were still being corrected.
        // One code per verification pass, in the order they execute. They shared a single VERIFY
        // code until a run that performed two of them showed two byte-identical timeline rows.
        // DAR-BE-346: the free returns grading runs before the paid missing-diff lookup, so the
        // lookup's share gate counts only rows that grading could not explain.
        assertEquals(6, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_RETURNS))
        assertEquals(7, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_MISSING))
        assertEquals(8, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY_EXCHANGE))
        // DAR-UI-044: CONCLUDE reads the VERIFIED findings, so it follows the last verification pass,
        // and precedes WRITE_OUTPUT so the persisted artifact is the concluded one.
        assertEquals(9, RunObservability.stageSequenceOf(RunObservability.STAGE_CONCLUDE))
        assertEquals(10, RunObservability.stageSequenceOf(RunObservability.STAGE_WRITE_OUTPUT))
        assertEquals(11, RunObservability.stageSequenceOf(RunObservability.STAGE_NOTIFY))
        // Runs recorded before the split stored the retired code; it keeps a sequence so their
        // stored rows still sort into the same slot rather than collapsing to 0.
        assertEquals(6, RunObservability.stageSequenceOf(RunObservability.STAGE_VERIFY))
        assertEquals(0, RunObservability.stageSequenceOf("UNKNOWN_STAGE"))
    }

    @Test
    void errorMessagesAreCutToTheirColumn() {
        assertEquals(255, RunObservability.capErrorMessage("x" * 300).length())
        assertEquals("short", RunObservability.capErrorMessage("short"))
        assertNull(RunObservability.capErrorMessage(null))
    }
}
