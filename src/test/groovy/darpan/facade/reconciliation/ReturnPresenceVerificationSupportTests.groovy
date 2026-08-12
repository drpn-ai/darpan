package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The DAR-BE-018 return identity rule (design §4): refund id is the spine, return id is a
 * forward-only backup, every match is scoped to the owning order, neither-matches is missing, and
 * anything younger than the grace is pending rather than missing.
 */
class ReturnPresenceVerificationSupportTests {

    private static final long NOW = 1_800_000_000_000L
    private static final long OLD = NOW - 24L * 3600_000L      // well outside the 3h grace
    private static final long RECENT = NOW - 30L * 60_000L     // inside the grace

    @Test
    void matchesAnOmsReturnAgainstARefundIdOnItsOwnOrder() {
        Map result = verify(
                [omsReturn("5001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], [])])

        assertEquals(1, result.matchedCount)
        assertEquals(0, ((List) result.missingInShopify).size())
    }

    @Test
    void fallsBackToTheReturnIdWhenNoRefundMatches() {
        // The permanent ex-IN-PROGRESS minority: OMS stamped the Return id and never backfilled it.
        Map result = verify(
                [omsReturn("9001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])

        assertEquals(1, result.matchedCount)
        assertEquals(0, ((List) result.missingInShopify).size())
    }

    @Test
    void reportsMissingInShopifyWhenNeitherIdSetMatches() {
        Map result = verify(
                [omsReturn("7777", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])

        List missing = (List) result.missingInShopify
        assertEquals(1, missing.size())
        assertEquals("7777", ((Map) missing[0]).externalId)
    }

    @Test
    void neverMatchesAcrossOrders() {
        // The bare id space is shared across types and orders; only orderExternalId scoping keeps a
        // numeric collision from becoming a false match (design §2).
        Map result = verify(
                [omsReturn("5001", "ORDER_A", OLD)],
                [shopifyOrder("ORDER_A", [], []), shopifyOrder("ORDER_B", ["5001"], [])])

        assertEquals(0, result.matchedCount)
        assertEquals(1, ((List) result.missingInShopify).size())
    }

    @Test
    void holdsAYoungOneSidedReturnAsPendingNotMissing() {
        Map result = verify(
                [omsReturn("7777", "7025799037059", RECENT)],
                [shopifyOrder("7025799037059", [], [])])

        assertEquals(0, ((List) result.missingInShopify).size())
        assertEquals(1, result.pendingCount)
    }

    @Test
    void reportsAShopifyRefundWithNoOmsReturnAsMissingInOms() {
        Map result = verify(
                [],
                [shopifyOrder("7025799037059", ["5001"], [])])

        List missing = (List) result.missingInOms
        assertEquals(1, missing.size())
        assertEquals("5001", ((Map) missing[0]).refundId)
    }

    @Test
    void doesNotExpectARefundedShopifyReturnSeparatelyFromItsRefund() {
        // The Return object's event is represented by its refund. Expecting both would phantom-flag
        // every refunded RMA as missing-in-OMS (design §4).
        Map result = verify(
                [omsReturn("5001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])

        assertEquals(0, ((List) result.missingInOms).size())
    }

    @Test
    void suppressesReverseMissingWhenTheOrderAlreadyMatchedForward() {
        // The ex-IN-PROGRESS-then-refunded case: OMS keyed the return by its Return id, so the
        // refund id is nobody's externalId. The event IS captured — just under the other id.
        Map result = verify(
                [omsReturn("9001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])

        assertEquals(1, result.matchedCount)
        assertEquals(0, ((List) result.missingInOms).size())
    }

    @Test
    void auditNoteAlwaysShowsItsWorkEvenWhenEverythingMatched() {
        Map result = verify(
                [omsReturn("5001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], [])])

        String note = result.auditNote as String
        assertTrue(note.contains("1 matched"), "operator always gets a sentence: ${note}")
        assertTrue(note.contains("3h"), "the grace must be stated: ${note}")
    }

    @Test
    void auditNoteStatesTheSuppressionCaveatOnlyWhenASuppressionOccurred() {
        // Return-id fallback match (same fixture as the reverse-suppression test): the order
        // matches forward, so its reverse check is suppressed and the caveat must be disclosed.
        Map suppressed = verify(
                [omsReturn("9001", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])
        assertTrue(((String) suppressed.auditNote).contains("suppressed"),
                "a suppressed order must be disclosed: ${suppressed.auditNote}")

        // Neither id set matches: no forward match, so no order is suppressed and the caveat
        // would be noise.
        Map notSuppressed = verify(
                [omsReturn("7777", "7025799037059", OLD)],
                [shopifyOrder("7025799037059", ["5001"], ["9001"])])
        assertFalse(((String) notSuppressed.auditNote).contains("suppressed"),
                "no suppression occurred, the caveat must not appear: ${notSuppressed.auditNote}")
    }

    @Test
    void readsBothExtractFilesAndAppendsMissingRowsToTheDiffFile() {
        File omsFile = File.createTempFile("oms-returns-", ".json")
        File shopifyFile = File.createTempFile("shopify-return-refs-", ".json")
        File diffFile = File.createTempFile("diff-", ".json")
        try {
            omsFile.text = groovy.json.JsonOutput.toJson([records: [
                    omsReturn("7777", "7025799037059", OLD),
            ], metadata: [:]])
            shopifyFile.text = groovy.json.JsonOutput.toJson([records: [
                    shopifyOrder("7025799037059", ["5001"], []),
            ], metadata: [:]])
            // Mirrors ReconciliationServices.writeDiffDatasetOutput's line-oriented format (see
            // ExchangePairVerificationSupportTests.diffFile()/MissingDiffVerificationSupportTests
            // .writeDiffDocument): appendDiffRows scans line-by-line and only recognizes
            // "differences":[ when it opens its OWN line. A single-line envelope such as
            // '{"differences":[],...}' never matches that check, so the append would silently no-op
            // (caught by appendDiffRows's own try/catch as a warning, not a thrown failure) and this
            // test's assertions on appendedCount/diffFile.text would fail against the real class.
            diffFile.text = '{\n' +
                    '"summary":{"totalDifferences":0,"onlyInFile1Count":0,"onlyInFile2Count":0,"missingObjectDifferenceCount":0},\n' +
                    '"processingWarnings":[],\n' +
                    '"differences":[]\n' +
                    '}\n'

            Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                    omsFile    : omsFile,
                    shopifyFile: shopifyFile,
                    diffFile   : diffFile,
                    nowMillis  : NOW,
            ])

            assertTrue(result.performed as boolean)
            // One OMS return matching nothing, and one Shopify refund with no OMS counterpart.
            assertEquals(2, result.appendedCount)
            assertTrue(diffFile.text.contains("RETURN_MISSING_IN_SHOPIFY"))
            assertTrue(diffFile.text.contains("RETURN_MISSING_IN_OMS"))
        } finally {
            omsFile.delete(); shopifyFile.delete(); diffFile.delete()
        }
    }

    @Test
    void degradesToAWarningWhenAnExtractFileIsMissing() {
        // The compare already succeeded; a verification failure must not fail the whole run.
        File diffFile = File.createTempFile("diff-", ".json")
        try {
            diffFile.text = '{"differences":[],"processingWarnings":[]}'
            Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                    omsFile    : new File("/nonexistent/oms.json"),
                    shopifyFile: new File("/nonexistent/shopify.json"),
                    diffFile   : diffFile,
                    nowMillis  : NOW,
            ])

            assertEquals(false, result.performed)
            assertTrue(((List) result.warnings).size() > 0, "a skipped check must say so")
        } finally {
            diffFile.delete()
        }
    }

    private static Map verify(List omsReturns, List shopifyOrders) {
        return ReturnPresenceVerificationSupport.verifyReturnPresence([
                omsReturns   : omsReturns,
                shopifyOrders: shopifyOrders,
                nowMillis    : NOW,
        ])
    }

    private static Map omsReturn(String externalId, String orderExternalId, long entryMillis) {
        return [externalId: externalId, orderExternalId: orderExternalId,
                entryDate: new Date(entryMillis).toInstant().toString(), returnId: "R-${externalId}".toString()]
    }

    private static Map shopifyOrder(String orderId, List refundIds, List returnIds) {
        return [orderId: orderId, refundIds: refundIds, returnIds: returnIds,
                createdAt: new Date(OLD).toInstant().toString()]
    }
}
