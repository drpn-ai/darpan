package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-320 phase 1b: what one side's read cost and covered, as the audit records it on its source read. */
class AuditReadStatsTests {

    @Test
    void aSideRecordsItsConnectionWindowCallsAndLookups() {
        Timestamp from = Timestamp.valueOf("2026-11-12 08:00:00")
        Timestamp thru = Timestamp.valueOf("2026-11-16 08:00:00")
        Map result = [recordCount: 812, readWindowFrom: from, readWindowThru: thru,
                      requestMetadata: [shopifyCalls: [graphql: 14L, bulkStarts: 1L]]]
        Map reconcile = [file1Label: "SHOPIFY", file2Label: "OMS",
                         verificationSides: [SHOPIFY: [candidates: 3, lookedUp: 3, found: 2]]]
        Map stats = AuditAutomationSupport.readStats("FILE_1", result, [sideConfigIds: [FILE_1: "SHOP_US"], reconcileResult: reconcile])

        assertEquals("SHOP_US", stats.sourceConfigId)
        assertEquals(from, stats.windowFromDate)
        assertEquals(thru, stats.windowThruDate)
        assertEquals('{"graphql":14,"bulkStarts":1}', stats.callsJson)
        assertEquals('{"candidates":3,"lookedUp":3,"found":2}', stats.lookupJson)
    }

    @Test
    void unknownFactsAreLeftOutNotGuessed() {
        Map stats = AuditAutomationSupport.readStats("FILE_2", [recordCount: 5], [reconcileResult: [file2Label: "OMS"]])
        assertTrue(stats.isEmpty(), "no connection, window, calls or lookup: nothing recorded (${stats})")
        assertTrue(AuditAutomationSupport.readStats("FILE_1", null, null).isEmpty())
    }
}
