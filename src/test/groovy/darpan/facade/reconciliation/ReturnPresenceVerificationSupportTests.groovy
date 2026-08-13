package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
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
        // C2: the row is keyed by primaryId (not the old bare "externalId" field) — primaryId is
        // what OutputDescriptorSupport/DiffDetailClassifier/RULESET_CSV_COLUMNS all read.
        assertEquals("7777", ((Map) missing[0]).primaryId)
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
        // C2: the row is keyed by primaryId (not the old bare "refundId" field).
        assertEquals("5001", ((Map) missing[0]).primaryId)
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
    void everyAppendedDiffRowInTheWrittenArtifactCarriesAPrimaryId() {
        // C2: the prior test suite only ever asserted the returned Map's own keys, never the
        // artifact contract — every appended row actually landed on disk (and exported) as
        // "RETURN_MISSING_IN_SHOPIFY,,,,,,,,,," because primaryId was never set. This reads back
        // what actually landed in the diff FILE, the thing ReconciliationOutputSupport /
        // OutputDescriptorSupport / DiffDetailClassifier all consume downstream.
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
            diffFile.text = '{\n' +
                    '"summary":{"totalDifferences":0,"onlyInFile1Count":0,"onlyInFile2Count":0,"missingObjectDifferenceCount":0},\n' +
                    '"processingWarnings":[],\n' +
                    '"differences":[]\n' +
                    '}\n'

            ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                    omsFile         : omsFile,
                    shopifyFile     : shopifyFile,
                    diffFile        : diffFile,
                    nowMillis       : NOW,
                    omsSideLabel    : "HotWax OMS",
                    shopifySideLabel: "Shopify",
            ])

            Map written = (Map) new groovy.json.JsonSlurper().parse(diffFile)
            List differences = (List) written.differences
            assertEquals(2, differences.size())
            differences.each { Object row ->
                String primaryId = ((Map) row).primaryId as String
                assertNotNull(primaryId, "every appended row must carry a primaryId: ${row}")
                assertFalse(primaryId.trim().isEmpty(), "primaryId must not be blank: ${row}")
                assertNotNull(((Map) row).missingIn, "every appended row must carry a missingIn side label: ${row}")
            }
        } finally {
            omsFile.delete(); shopifyFile.delete(); diffFile.delete()
        }
    }

    @Test
    void anOmsReturnWithEpochMillisEntryDateInsideGraceIsPendingNotMissing() {
        // C3: real captured OMS REST output serializes timestamps as epoch-millis integers (verified
        // directly against runtime/datamanager/reconciliation-runs/krewe_uat/20260512-055125929/
        // oms-orders-1775001600000-1777593600000.json: entryDate = 1777030973104, an int, not an
        // ISO-8601 string). The shared omsReturn() fixture below now emits epoch millis for exactly
        // this reason — this test additionally pins the raw-Number AND all-digits-String forms.
        Map numberForm = verify(
                [[externalId: "7777", orderExternalId: "7025799037059", entryDate: RECENT, returnId: "R-7777"]],
                [shopifyOrder("7025799037059", [], [])])
        assertEquals(0, ((List) numberForm.missingInShopify).size(), "an epoch-millis Number entryDate inside the grace must not report missing")
        assertEquals(1, numberForm.pendingCount)

        Map stringForm = verify(
                [[externalId: "7778", orderExternalId: "7025799037059", entryDate: String.valueOf(RECENT), returnId: "R-7778"]],
                [shopifyOrder("7025799037059", [], [])])
        assertEquals(0, ((List) stringForm.missingInShopify).size(), "an epoch-millis all-digits String entryDate inside the grace must not report missing")
        assertEquals(1, stringForm.pendingCount)
    }

    @Test
    void anOmsReturnWithEpochMillisEntryDateOutsideGraceIsStillReportedMissing() {
        // The other half of C3: epoch-millis parsing must not accidentally make everything pending —
        // an OMS return older than the grace, expressed in epoch millis, must still be missing.
        Map result = verify(
                [[externalId: "7777", orderExternalId: "7025799037059", entryDate: OLD, returnId: "R-7777"]],
                [shopifyOrder("7025799037059", [], [])])

        assertEquals(1, ((List) result.missingInShopify).size())
        assertEquals(0, result.pendingCount)
    }

    @Test
    void aRecentRefundOnAnOldOrderIsPendingNotMissing() {
        // I1: the reverse grace must key off the REFUND's own createdAt (the upstream extractor's
        // refundsCreatedAt map, keyed by the same bare refund id), not the order's. An order that is
        // months old but whose refund was created moments ago must still read as pending.
        Map order = [orderId: "7025799037059", refundIds: ["5001"], returnIds: [],
                     createdAt: new Date(OLD).toInstant().toString(),
                     refundsCreatedAt: ["5001": new Date(RECENT).toInstant().toString()]]

        Map result = verify([], [order])

        assertEquals(0, ((List) result.missingInOms).size(), "a young refund on an old order must not be reported missing")
        assertEquals(1, result.pendingCount)
    }

    @Test
    void anOldRefundOnARecentOrderIsStillReportedMissing() {
        // The other half of I1: keying on the refund's own date must not accidentally suppress a
        // genuinely stale refund just because the order itself is recent.
        Map order = [orderId: "7025799037059", refundIds: ["5001"], returnIds: [],
                     createdAt: new Date(RECENT).toInstant().toString(),
                     refundsCreatedAt: ["5001": new Date(OLD).toInstant().toString()]]

        Map result = verify([], [order])

        assertEquals(1, ((List) result.missingInOms).size(), "a stale refund on a recent order must still be reported missing")
        assertEquals(0, result.pendingCount)
    }

    @Test
    void fallsBackToOrderCreatedAtWhenRefundsCreatedAtIsAbsent() {
        // Older extract shape without refundsCreatedAt at all: the order's own createdAt remains the
        // fallback clock, preserving pre-fix behavior for that case.
        Map order = [orderId: "7025799037059", refundIds: ["5001"], returnIds: [],
                     createdAt: new Date(RECENT).toInstant().toString()]

        Map result = verify([], [order])

        assertEquals(0, ((List) result.missingInOms).size())
        assertEquals(1, result.pendingCount)
    }

    @Test
    void matchesAGidFormOmsExternalIdAgainstABareShopifyRefundId() {
        // I5: the OMS side of the match must be GID-normalized too. The Shopify side already strips
        // GIDs down to the bare id; the OMS returns header format (bare vs GID) has never been
        // observed live (open half of OQ-8), so a GID-form externalId must still match.
        Map result = verify(
                [[externalId: "gid://shopify/Refund/5001", orderExternalId: "7025799037059",
                  entryDate: OLD, returnId: "R-5001"]],
                [shopifyOrder("7025799037059", ["5001"], [])])

        assertEquals(1, result.matchedCount, "a GID-form OMS externalId must still match the bare Shopify refund id")
        assertEquals(0, ((List) result.missingInShopify).size())
    }

    @Test
    void countsMalformedOmsAndShopifyRecordsWithoutFailingTheCheck() {
        // M2: a non-Map entry, or one missing its required id field, must be counted rather than
        // silently dropped — mirrors ExchangePairVerificationSupport's own warnings posture.
        Map result = verify(
                ["not-a-map", [orderExternalId: "7025799037059"] /* missing externalId */,
                 omsReturn("7777", "7025799037059", OLD)],
                [42, [refundIds: ["5001"]] /* missing orderId */,
                 shopifyOrder("7025799037059", [], [])])

        assertEquals(2, result.malformedOmsReturnCount)
        assertEquals(2, result.malformedShopifyOrderCount)
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
        // C3: real OMS REST output serializes entryDate as an epoch-millis integer, not an
        // ISO-8601 string (verified against a captured extract — see the class doc and
        // anOmsReturnWithEpochMillisEntryDateInsideGraceIsPendingNotMissing). The old ISO-string
        // fixture here let every grace/pending test pass without ever exercising the code path the
        // real endpoint actually uses; every caller of this helper now proves the epoch-millis path.
        return [externalId: externalId, orderExternalId: orderExternalId,
                entryDate: entryMillis, returnId: "R-${externalId}".toString()]
    }

    private static Map shopifyOrder(String orderId, List refundIds, List returnIds) {
        return [orderId: orderId, refundIds: refundIds, returnIds: returnIds,
                createdAt: new Date(OLD).toInstant().toString()]
    }
}
