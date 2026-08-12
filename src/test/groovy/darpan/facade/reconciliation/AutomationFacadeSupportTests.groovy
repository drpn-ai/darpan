package darpan.facade.reconciliation

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

    /**
     * DAR-BE-018. The reconciliationOrders connector is a second OMS orders source with its own
     * systemEnumId, and these three helpers key on systemEnumId EQUALITY rather than on the connector
     * registry. Without the new id they return null/empty, and the rules board silently offers no
     * field pills and no primary-id choices for a source the operator can otherwise configure —
     * a connector that validates and then cannot be given a join key.
     *
     * systemAliases does NOT cover this: aliases are read only by SourceSystemConnectorSupport.resolve
     * to find a row FROM an id, and have no effect on these comparisons.
     */
    @Test
    void reconciliationOrdersSystemOffersRulesBoardPillsAtAll() {
        assertNotNull(AutomationFacadeSupport.fieldOptionsForSystem("OMS_RECON_ORDERS"),
                "without pills the rules board cannot configure a source this connector can otherwise save")
        assertEquals(AutomationFacadeSupport.primaryIdOptionsForSystem("OMS"),
                AutomationFacadeSupport.primaryIdOptionsForSystem("OMS_RECON_ORDERS"),
                "the join key must be selectable from the same choices — all three are projected fields")
    }

    /**
     * The recon endpoint projects server-side to a fixed six-field set, so the two pills that exist
     * only because the LEGACY endpoint ships whole order documents (salesChannelEnumId,
     * productStoreId) must not be offered here. A rule drawn on a field the endpoint never returns
     * would validate, persist, and then exclude nothing — silently.
     */
    @Test
    void reconciliationOrdersPillsAreLimitedToProjectedFields() {
        List<String> reconFields = AutomationFacadeSupport.fieldOptionsForSystem("OMS_RECON_ORDERS")
                .collect { Map option -> (option.fieldPath as String).substring("\$.records[*].".length()) }
        List<String> legacyFields = AutomationFacadeSupport.fieldOptionsForSystem("OMS")
                .collect { Map option -> (option.fieldPath as String).substring("\$.records[*].".length()) }

        assertEquals(["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"], reconFields)
        assertFalse(reconFields.contains("salesChannelEnumId"))
        assertFalse(reconFields.contains("productStoreId"))
        // Still a strict subset of the legacy list, so the two cannot drift into disagreeing labels.
        assertTrue(legacyFields.containsAll(reconFields))
    }

    @Test
    void reconciliationOrdersSystemLabelsItsOwnEndpoint() {
        String label = AutomationFacadeSupport.endpointLabelForSystem(
                "OMS_RECON_ORDERS", "HOTWAX_ORDERS_API", null)
        assertEquals("Reconciliation Orders API", label,
                "the shared remoteId must not make this source display as the legacy Orders API")
    }

    static class MessageStub {
        List<String> errors = []
        void addError(Object error) { errors.add(error?.toString()) }
        boolean hasError() { return !errors.isEmpty() }
    }
}
