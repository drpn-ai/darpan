package darpan.facade.reconciliation

import darpan.reconciliation.automation.AutomationExecutionSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

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

        List collapsed = AutomationRowSupport.collapseExecutionHistory([first, retryOfFirst, otherWindow, windowless])
        assertEquals([first, otherWindow, windowless], collapsed)
    }

    @Test
    void collapseExecutionHistoryKeepsAllWindowlessRows() {
        // Rows with no child window have no logical key and must never dedupe each other.
        Map a = [automationId: "A1", statusEnumId: "X"]
        Map b = [automationId: "A1", statusEnumId: "Y"]
        assertEquals([a, b], AutomationRowSupport.collapseExecutionHistory([a, b]))
        assertEquals([], AutomationRowSupport.collapseExecutionHistory(null))
    }

    @Test
    void logicalExecutionWindowKeyIsStableAcrossTimestampRepresentations() {
        Timestamp start = ts("2026-05-01T00:00:00Z")
        Map viaTimestamp = [automationId: "A1", childWindowStartDate: start, childWindowEndDate: null]
        Map viaDate = [automationId: "A1", childWindowStartDate: new Date(start.time), childWindowEndDate: null]
        assertEquals(AutomationRowSupport.logicalExecutionWindowKey(viaTimestamp),
                AutomationRowSupport.logicalExecutionWindowKey(viaDate))
        assertNull(AutomationRowSupport.logicalExecutionWindowKey([automationId: "A1"]))
    }

    // ─── schedule summary ────────────────────────────────────────────────────────

    @Test
    void buildScheduleSummaryDistinguishesCronDurationAndUnscheduled() {
        assertEquals("Not scheduled", AutomationRowSupport.buildScheduleSummary([:]))
        assertEquals("P1D", AutomationRowSupport.buildScheduleSummary([scheduleExpr: "P1D"]))
        assertEquals("Cron: 0 0 2 * * ?", AutomationRowSupport.buildScheduleSummary([scheduleExpr: "0 0 2 * * ?"]))
    }

    // ─── next fire time (timezone regression H15.3 area, pure input path) ────────

    @Test
    void resolveNextFireTimeReturnsNullWithoutSchedule() {
        assertNull(AutomationFacadeSupport.resolveNextFireTime([:], ts("2026-05-01T10:00:00Z")))
        assertNull(AutomationFacadeSupport.resolveNextFireTime([scheduleExpr: "  "], ts("2026-05-01T10:00:00Z")))
    }

    @Test
    void theNextFireSavedIsTheOneTheScannerComputesInTheAutomationsZone() {
        // 10:00Z is 15:30 IST: the next 02:00 IST is May 2, 20:30Z on May 1. Read in UTC it would be 02:00Z May 2.
        Timestamp now = ts("2026-05-01T10:00:00Z")
        Map input = [scheduleExpr: "0 0 2 * * ?", windowTimeZone: "Asia/Kolkata"]
        Timestamp saved = AutomationFacadeSupport.resolveNextFireTime(input, now)
        assertEquals(ts("2026-05-01T20:30:00Z"), saved)
        assertEquals(AutomationExecutionSupport.resolveNextScheduledFireTime(input, null, now), saved,
                "save and the scanner must agree on the next fire")
    }

    // ─── source-type gate widening (AUT_SRC_DB, MACH database-source epic Task 2) ─────
    // validateSources/validateDatabaseSource only touch ec.message for a database-typed source
    // (no entity lookups on this branch — those live behind validateApiSource), so a minimal
    // Expando + MessageStub ec double is enough here, mirroring the no-Moqui-bootstrap pattern in
    // AutomationFacadeSupportExtractServiceAllowlistTests.groovy.

    private static Object stubEc(MessageStub message) {
        return new Expando(message: message)
    }

    private static List<Map<String, Object>> databaseSourceFixture(Map<String, Object> file1Overrides = [:],
            Map<String, Object> file2Overrides = [:]) {
        Map<String, Object> file1 = [fileSide: "FILE_1", sourceTypeEnumId: "AUT_SRC_DB", systemEnumId: "OMS",
                                      fileTypeEnumId: "DftCsv", databaseSourceQueryId: "TEST_DB_QUERY"] + file1Overrides
        Map<String, Object> file2 = [fileSide: "FILE_2", sourceTypeEnumId: "AUT_SRC_DB", systemEnumId: "SHOPIFY",
                                      fileTypeEnumId: "DftCsv", databaseSourceQueryId: "TEST_DB_QUERY"] + file2Overrides
        return [file1, file2]
    }

    @Test
    void acceptsDatabaseSourceOnApiInputMode() {
        MessageStub message = new MessageStub()
        def ec = stubEc(message)
        AutomationFacadeSupport.validateSources(ec, "AUT_IN_API_RANGE", databaseSourceFixture(), null)
        assertFalse(message.hasError(), "Unexpected errors: ${message.errors}")
    }

    @Test
    void rejectsDatabaseSourceMissingQueryId() {
        MessageStub message = new MessageStub()
        def ec = stubEc(message)
        List<Map<String, Object>> sources = databaseSourceFixture([databaseSourceQueryId: null])
        AutomationFacadeSupport.validateSources(ec, "AUT_IN_API_RANGE", sources, null)
        assertTrue(message.hasError())
        assertTrue(message.errors.any { it.contains("databaseSourceQueryId") }, "Unexpected errors: ${message.errors}")
    }

    @Test
    void applyApiSourceMetadataDefaultsPassesThroughDatabaseSourceUnchanged() {
        // DB sources carry no secrets on the source row (credentials live in DatabaseSourceConfig), so
        // the API-only sanitize/enrichment branch must leave a database-typed row untouched.
        Map<String, Object> dbSource = databaseSourceFixture().first()
        List<Map<String, Object>> result = AutomationFacadeSupport.applyApiSourceMetadataDefaults(null, [dbSource])
        assertEquals([dbSource], result)
    }

    // reconciliationOrdersSystemOffersRulesBoardPillsAtAll and reconciliationOrdersPillsAreLimitedTo-
    // ProjectedFields moved to SourceSystemConnectorSupportSmokeTests.groovy (Task 5, Plan 2):
    // fieldOptionsForSystem/primaryIdOptionsForSystem gained an ec parameter and now read the
    // SourceSystemConnectorField registry, which needs a real, seed-backed ExecutionContext that
    // this file's ec-free fixtures do not provide.

    // returnsSystemOffersTheChannelPill and returnsPillsNameOnlyTopLevelRecordKeys (DAR-BE-018 Task 6,
    // design §5) moved to SourceSystemConnectorSupportSmokeTests.groovy (Task 5, Plan 2) for the same
    // reason as the reconciliationOrders pill tests above — fieldOptionsForSystem now reads the
    // registry and needs a real ec.

    static class MessageStub {
        List<String> errors = []
        void addError(Object error) { errors.add(error?.toString()) }
        boolean hasError() { return !errors.isEmpty() }
    }
}
