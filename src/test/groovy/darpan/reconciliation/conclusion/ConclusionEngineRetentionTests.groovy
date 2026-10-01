package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test
import reconciliation.rule.RuleEngineSupport

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-063 review I4. Every bounded fire schedules a 60s watchdog whose closure holds the session.
 * A cancelled task must leave the queue at once: otherwise a run that concludes row by row keeps every
 * disposed session reachable for a minute — ~100k sessions on a 100k-row result.
 */
class ConclusionEngineRetentionTests {

    @Test
    void aFinishedFireLeavesNoWatchdogTaskBehind() {
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile([[sequenceNum: 10, conclusionEnumId: "C", label: "C",
                appliesToBucket: "MISSING_FROM_FILE_2", conditions: []]])
        try {
            int before = RuleEngineSupport.pendingWatchdogTasks()
            200.times { engine.conclude([[bucket: "MISSING_FROM_FILE_2", sides: [:]]]) }
            assertEquals(before, RuleEngineSupport.pendingWatchdogTasks(), "cancelled watchdog tasks must not pile up")
        } finally {
            engine.close()
        }
    }
}
