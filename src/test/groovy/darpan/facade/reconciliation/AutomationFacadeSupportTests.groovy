package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * Characterization tests for the pure/branch-heavy helpers of AutomationFacadeSupport
 * (MACH P1: pin current behavior before the decomposition of the large facade files).
 * No ExecutionContext — every method under test here is ec-free.
 */
class AutomationFacadeSupportTests {

    private static Timestamp ts(String instantText) {
        return Timestamp.from(Instant.parse(instantText))
    }

    // ─── execution-history collapse (dedupe by logical child window) ─────────────

    @Test
    void collapseExecutionHistoryKeepsFirstRowPerLogicalWindow() {
        Map first = [automationId: "A1", childWindowStartDate: ts("2026-05-01T00:00:00Z"),
                     childWindowEndDate: ts("2026-05-02T00:00:00Z"), statusEnumId: "SUCCEEDED"]
        Map retryOfFirst = new LinkedHashMap(first) + [statusEnumId: "FAILED"]
        Map otherWindow = [automationId: "A1", childWindowStartDate: ts("2026-05-02T00:00:00Z"),
                           childWindowEndDate: ts("2026-05-03T00:00:00Z")]
        Map windowless = [automationId: "A1"]

        List collapsed = AutomationFacadeSupport.collapseExecutionHistory([first, retryOfFirst, otherWindow, windowless])
        assertEquals([first, otherWindow, windowless], collapsed)
    }

    @Test
    void collapseExecutionHistoryKeepsAllWindowlessRows() {
        // Rows with no child window have no logical key and must never dedupe each other.
        Map a = [automationId: "A1", statusEnumId: "X"]
        Map b = [automationId: "A1", statusEnumId: "Y"]
        assertEquals([a, b], AutomationFacadeSupport.collapseExecutionHistory([a, b]))
        assertEquals([], AutomationFacadeSupport.collapseExecutionHistory(null))
    }

    @Test
    void logicalExecutionWindowKeyIsStableAcrossTimestampRepresentations() {
        Timestamp start = ts("2026-05-01T00:00:00Z")
        Map viaTimestamp = [automationId: "A1", childWindowStartDate: start, childWindowEndDate: null]
        Map viaDate = [automationId: "A1", childWindowStartDate: new Date(start.time), childWindowEndDate: null]
        assertEquals(AutomationFacadeSupport.logicalExecutionWindowKey(viaTimestamp),
                AutomationFacadeSupport.logicalExecutionWindowKey(viaDate))
        assertNull(AutomationFacadeSupport.logicalExecutionWindowKey([automationId: "A1"]))
    }

    // ─── schedule summary ────────────────────────────────────────────────────────

    @Test
    void buildScheduleSummaryDistinguishesCronDurationAndUnscheduled() {
        assertEquals("Not scheduled", AutomationFacadeSupport.buildScheduleSummary([:]))
        assertEquals("P1D", AutomationFacadeSupport.buildScheduleSummary([scheduleExpr: "P1D"]))
        assertEquals("Cron: 0 0 2 * * ?", AutomationFacadeSupport.buildScheduleSummary([scheduleExpr: "0 0 2 * * ?"]))
    }

    // ─── next fire time (timezone regression H15.3 area, pure input path) ────────

    @Test
    void resolveNextFireTimeReturnsNullWithoutSchedule() {
        assertNull(AutomationFacadeSupport.resolveNextFireTime([:], ts("2026-05-01T10:00:00Z")))
        assertNull(AutomationFacadeSupport.resolveNextFireTime([scheduleExpr: "  "], ts("2026-05-01T10:00:00Z")))
    }
}
