package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-018, reduced 2026-08-18 (returns-refund-grain-alignment plan, Task 3): with Shopify and OMS
 * now sitting at the same grain (one row per EVENT — refundOrReturnId matches OMS externalId directly), the
 * generic ruleset compare finds real missing-in-Shopify / missing-in-OMS rows on its own via an
 * ordinary join. This class no longer re-derives presence — it GRADES the rows the compare already
 * wrote to the diff document: a one-sided event younger than graceHours is pending (removed from the
 * diff), and a missing-in-OMS event that predates the run's reporting windowStartMillis is excluded
 * outright (it only exists because of the Shopify extractor's own lookback widening).
 *
 * The retired id-matching behaviour (byOrder index, refund-then-return fallback, matchedCount,
 * ordersMatchedForward/per-order forward-match suppression) had its own test coverage in the prior
 * revision of this file; none of it survives here because none of it survives in the class — the
 * generic compare's join replaced it entirely. See the class doc for the reasoning, including why the
 * per-order suppression specifically was retired rather than narrowed.
 *
 * Fixtures reproduce the {@code ReconciliationServices.writeDiffDatasetOutput} line-oriented format,
 * with each row's {@code data} column itself JSON-encoded a second time — exactly how
 * {@code CompareDatasetSupport.convertMissingDiffToRuleSetDiffDataset} writes a "missing" row's
 * present-side record (a {@code to_json(struct(col("*")))} column) — since that embedded record is
 * this class's only source for the grace/window date now.
 */
class ReturnPresenceVerificationSupportTests {

    @TempDir
    File tempDir

    private static final String OMS_LABEL = "HotWax OMS"
    private static final String SHOPIFY_LABEL = "Shopify"

    private static final long NOW = 1_800_000_000_000L
    private static final long OLD = NOW - 24L * 3600_000L      // well outside the 3h default grace
    private static final long RECENT = NOW - 30L * 60_000L     // inside the 3h default grace
    private static final String DEFAULT_ORDER_ID = "7025799037059"

    /**
     * Stands in for lookup#HotWaxOmsOrdersByExternalId, whose real contract is
     * [ok, ordersByExternalId, errors] with each value an order GROUP (a List of order Maps, each
     * carrying statusId) — see OmsRestSourceSupport.summarizePairOrder.
     */
    private static Closure lookupReturning(Map<String, Object> ordersByExternalId, boolean ok = true) {
        return { List ids ->
            [ok: ok, ordersByExternalId: ok ? ordersByExternalId.subMap(ids) : [:],
             errors: ok ? [] : ["OMS returned HTTP 503"]]
        }
    }

    private Map verifyWithLookup(File file, Closure cancelledOrderLookup, Integer maxOrderLookups = null) {
        Map args = [
                diffFile            : file,
                file1Label          : SHOPIFY_LABEL,
                file2Label          : OMS_LABEL,
                omsSideLabel        : OMS_LABEL,
                shopifySideLabel    : SHOPIFY_LABEL,
                nowMillis           : NOW,
                cancelledOrderLookup: cancelledOrderLookup,
        ]
        if (maxOrderLookups != null) args.maxOrderLookups = maxOrderLookups
        return ReturnPresenceVerificationSupport.verifyReturnPresenceForRun(args)
    }

    /** A "missing in Shopify" row: the OMS return is present, data carries the OMS record (entryDate). */
    private static Map missingInShopifyRow(String id, long entryDateMillis) {
        return [
                diffType : "MISSING_IN_FILE_2",
                primaryId: id,
                presentIn: OMS_LABEL,
                missingIn: SHOPIFY_LABEL,
                data     : JsonOutput.toJson([externalId: id, orderExternalId: "7025799037059",
                        entryDate: entryDateMillis, returnId: "R-${id}".toString()]),
                message  : "Present in ${OMS_LABEL}, missing in ${SHOPIFY_LABEL}".toString(),
        ]
    }

    /** A "missing in OMS" row: the Shopify event is present, data carries the Shopify record (createdAt). */
    private static Map missingInOmsRow(String id, long createdAtMillis, String refundOrReturnType = "REFUND",
                                       String orderId = DEFAULT_ORDER_ID) {
        return [
                diffType : "MISSING_IN_FILE_1",
                primaryId: id,
                presentIn: SHOPIFY_LABEL,
                missingIn: OMS_LABEL,
                data     : JsonOutput.toJson([refundOrReturnId: id, refundOrReturnType: refundOrReturnType, orderId: orderId,
                        createdAt: isoInstant(createdAtMillis)]),
                message  : "Present in ${SHOPIFY_LABEL}, missing in ${OMS_LABEL}".toString(),
        ]
    }

    private static String isoInstant(long millis) {
        return new Date(millis).toInstant().toString()
    }

    private File writeDiffDocument(List<Map> rows, Map summaryOverride = null) {
        File file = new File(tempDir, "returns-diff-${System.nanoTime()}.json")
        Map metadata = [file1Label: SHOPIFY_LABEL, file2Label: OMS_LABEL, reconciliation: "RULESET"]
        // file1 = Shopify, file2 = OMS: "only in file1" = present in Shopify only = missing in OMS;
        // "only in file2" = present in OMS only = missing in Shopify. Mirrors writeRuleSetOutput's own
        // onlyInFile1Count/onlyInFile2Count <-> missingInFile2Count/missingInFile1Count mapping.
        int missingInOmsCount = rows.count { it.missingIn == OMS_LABEL } as int
        int missingInShopifyCount = rows.count { it.missingIn == SHOPIFY_LABEL } as int
        Map summary = summaryOverride ?: [
                totalDifferences            : rows.size(),
                onlyInFile1Count            : missingInOmsCount,
                onlyInFile2Count            : missingInShopifyCount,
                missingObjectDifferenceCount: rows.size(),
        ]
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson(metadata) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson(summary) + ",\n"
            writer << "\"validationErrors\":" + JsonOutput.toJson([]) + ",\n"
            writer << "\"processingWarnings\":" + JsonOutput.toJson(["compare warning"]) + ",\n"
            writer << "\"differences\":["
            boolean first = true
            rows.each { Map row ->
                if (!first) writer << ","
                writer << "\n" << JsonOutput.toJson(row)
                first = false
            }
            writer << "]\n}"
        }
        return file
    }

    private static Map parseDocument(File file) {
        return (Map) new JsonSlurper().parseText(file.getText("UTF-8"))
    }

    private Map verify(File file, Long windowStartMillis = null) {
        return ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                diffFile         : file,
                file1Label       : SHOPIFY_LABEL,
                file2Label       : OMS_LABEL,
                omsSideLabel     : OMS_LABEL,
                shopifySideLabel : SHOPIFY_LABEL,
                nowMillis        : NOW,
                windowStartMillis: windowStartMillis,
        ])
    }

    // --- GRACE: the behaviour this task exists to keep ---

    @Test
    void aOneSidedReturnYoungerThanGraceIsNotReportedMissing() {
        File file = writeDiffDocument([missingInShopifyRow("7777", RECENT)])

        Map result = verify(file)

        assertTrue((Boolean) result.performed)
        assertTrue((Boolean) result.rewritten)
        assertEquals(1, result.pendingCount)
        assertEquals(1, result.removedCount)

        Map document = parseDocument(file)
        assertEquals([], ((List) document.differences))
        assertEquals(0, ((Map) document.summary).totalDifferences)
    }

    @Test
    void aOneSidedReturnOlderThanGraceIsStillReportedMissing() {
        File file = writeDiffDocument([missingInShopifyRow("7777", OLD)])

        Map result = verify(file)

        assertTrue((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(0, result.pendingCount)
        assertEquals(0, result.removedCount)

        Map document = parseDocument(file)
        assertEquals(["7777"], ((List) document.differences).collect { it.primaryId })
        assertEquals(1, ((Map) document.summary).totalDifferences)
    }

    @Test
    void aRecentMissingInOmsShopifyEventIsHeldAsPendingNotMissing() {
        // Same grace behaviour, other direction: the Shopify side is present, OMS hasn't caught up yet.
        File file = writeDiffDocument([missingInOmsRow("5001", RECENT)])

        Map result = verify(file)

        assertTrue((Boolean) result.rewritten)
        assertEquals(1, result.pendingCount)
        Map document = parseDocument(file)
        assertEquals([], (List) document.differences)
    }

    @Test
    void anOldMissingInOmsShopifyEventIsStillReportedMissing() {
        File file = writeDiffDocument([missingInOmsRow("5001", OLD)])

        Map result = verify(file)

        assertFalse((Boolean) result.rewritten)
        assertEquals(0, result.pendingCount)
        Map document = parseDocument(file)
        assertEquals(["5001"], ((List) document.differences).collect { it.primaryId })
    }

    @Test
    void gradesEachRowIndependentlyLeavingOldOnesAndRemovingYoungOnes() {
        File file = writeDiffDocument([
                missingInShopifyRow("7777", OLD),
                missingInShopifyRow("7778", RECENT),
                missingInOmsRow("5001", OLD),
                missingInOmsRow("5002", RECENT),
        ], [
                totalDifferences            : 4,
                onlyInFile1Count            : 2,
                onlyInFile2Count            : 2,
                missingObjectDifferenceCount: 4,
        ])

        Map result = verify(file)

        assertEquals(2, result.pendingCount)
        assertEquals(2, result.removedCount)
        Map document = parseDocument(file)
        assertEquals(["7777", "5001"] as Set, (((List) document.differences).collect { it.primaryId } as Set))
        assertEquals(2, ((Map) document.summary).totalDifferences)
        assertEquals(1, ((Map) document.summary).onlyInFile1Count)
        assertEquals(1, ((Map) document.summary).onlyInFile2Count)
        assertEquals(2, ((Map) document.summary).missingObjectDifferenceCount)
    }

    // --- WINDOW-START GATE: the other behaviour this task exists to keep ---

    @Test
    void aPreWindowMissingInOmsEventIsExcludedEvenThoughItIsOldEnoughToBeGraceEligible() {
        long windowStart = 1_800_000_000_000L
        long eventCreated = windowStart - 30 * 60_000L // before windowStart, and outside grace too

        File file = writeDiffDocument([missingInOmsRow("5001", eventCreated)])

        Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                diffFile         : file,
                file1Label       : SHOPIFY_LABEL,
                file2Label       : OMS_LABEL,
                omsSideLabel     : OMS_LABEL,
                shopifySideLabel : SHOPIFY_LABEL,
                nowMillis        : windowStart + 4 * 3600_000L,
                windowStartMillis: windowStart,
        ])

        assertTrue((Boolean) result.rewritten)
        assertEquals(1, result.removedCount)
        // Gated out before the grace check — not counted as pending.
        assertEquals(0, result.pendingCount)
        assertEquals(1, result.preWindowSuppressedCount)
        Map document = parseDocument(file)
        assertEquals([], (List) document.differences)
    }

    @Test
    void theWindowStartGateNeverAppliesToTheMissingInShopifyDirection() {
        // A pre-window OMS return has no lookback-driven artifact to correct for — only the
        // missing-in-OMS direction is gated. This one is old enough to be genuinely missing.
        long windowStart = 1_800_000_000_000L
        long entryDate = windowStart - 30 * 60_000L

        File file = writeDiffDocument([missingInShopifyRow("7777", entryDate)])

        Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                diffFile         : file,
                file1Label       : SHOPIFY_LABEL,
                file2Label       : OMS_LABEL,
                omsSideLabel     : OMS_LABEL,
                shopifySideLabel : SHOPIFY_LABEL,
                nowMillis        : windowStart + 4 * 3600_000L,
                windowStartMillis: windowStart,
        ])

        assertFalse((Boolean) result.rewritten)
        assertEquals(0, result.removedCount)
        Map document = parseDocument(file)
        assertEquals(["7777"], ((List) document.differences).collect { it.primaryId })
    }

    @Test
    void degradesToGraceOnlyBehaviourWhenNoWindowStartIsSupplied() {
        File file = writeDiffDocument([missingInOmsRow("5001", OLD)])

        Map result = verify(file, null)

        assertFalse((Boolean) result.rewritten)
        assertEquals(0, result.preWindowSuppressedCount)
        Map document = parseDocument(file)
        assertEquals(["5001"], ((List) document.differences).collect { it.primaryId })
    }

    // --- Non-returns rows and rule rows must never be touched ---

    @Test
    void leavesRuleDiffRowsAndOtherSidesUntouched() {
        Map ruleRow = [diffType: "rule_diff", primaryId: "3001", field: "grandTotal",
                       file1Value: "10.00", file2Value: "12.00", ruleId: "rule_1", message: "grandTotal mismatch"]
        File file = writeDiffDocument([missingInShopifyRow("7777", RECENT), ruleRow], [
                totalDifferences            : 2,
                onlyInFile1Count            : 0,
                onlyInFile2Count            : 1,
                missingObjectDifferenceCount: 1,
                ruleDifferenceCount         : 1,
        ])

        Map result = verify(file)

        assertTrue((Boolean) result.rewritten)
        Map document = parseDocument(file)
        assertEquals(["3001"], ((List) document.differences).collect { it.primaryId })
        assertEquals(1, ((Map) document.summary).totalDifferences)
        assertEquals(1, ((Map) document.summary).ruleDifferenceCount)
    }

    // --- Malformed / degraded-input posture ---

    @Test
    void aRowWithNoParseableOwnDateIsLeftAsReportedAndCountedMalformed() {
        Map row = [diffType: "MISSING_IN_FILE_2", primaryId: "7777", presentIn: OMS_LABEL, missingIn: SHOPIFY_LABEL,
                   data: JsonOutput.toJson([externalId: "7777", orderExternalId: "7025799037059"]) /* no entryDate */,
                   message: "Present in ${OMS_LABEL}, missing in ${SHOPIFY_LABEL}".toString()]
        File file = writeDiffDocument([row])

        Map result = verify(file)

        assertFalse((Boolean) result.rewritten)
        assertEquals(1, result.malformedCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("could not grade") })
        Map document = parseDocument(file)
        assertEquals(["7777"], ((List) document.differences).collect { it.primaryId })
    }

    @Test
    void degradesToAWarningWhenTheDiffFileIsMissing() {
        Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                diffFile        : new File(tempDir, "nonexistent.json"),
                file1Label      : SHOPIFY_LABEL,
                file2Label      : OMS_LABEL,
                omsSideLabel    : OMS_LABEL,
                shopifySideLabel: SHOPIFY_LABEL,
                nowMillis       : NOW,
        ])

        assertEquals(false, result.performed)
        assertTrue(((List) result.warnings).size() > 0, "a skipped check must say so")
    }

    // --- Audit note: always shows its work, window-start clause only when it applies ---

    @Test
    void auditNoteAlwaysShowsItsWorkEvenWhenNothingIsPending() {
        File file = writeDiffDocument([missingInShopifyRow("7777", OLD)])

        Map result = verify(file)

        String note = result.auditNote as String
        assertTrue(note.startsWith(ReturnPresenceVerificationSupport.AUDIT_NOTE_PREFIX), "note: ${note}")
        assertTrue(note.contains("0 pending"), "operator always gets a sentence: ${note}")
        assertTrue(note.contains("3h"), "the grace must be stated: ${note}")
        assertFalse(note.contains("pre-window"), "no window-start suppression occurred: ${note}")
    }

    @Test
    void auditNoteStatesThePreWindowClauseOnlyWhenItApplies() {
        long windowStart = 1_800_000_000_000L
        File file = writeDiffDocument([missingInOmsRow("5001", windowStart - 30 * 60_000L)])

        Map result = ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                diffFile         : file,
                file1Label       : SHOPIFY_LABEL,
                file2Label       : OMS_LABEL,
                omsSideLabel     : OMS_LABEL,
                shopifySideLabel : SHOPIFY_LABEL,
                nowMillis        : windowStart + 4 * 3600_000L,
                windowStartMillis: windowStart,
        ])

        String note = result.auditNote as String
        assertTrue(note.contains("pre-window"), "a window-start suppression must be disclosed: ${note}")
    }

    // --- Real captured OMS shape: entryDate as epoch-millis (Number) and as an all-digits String ---

    @Test
    void gradesAnEpochMillisNumberEntryDateTheSameAsAnIsoString() {
        File file = writeDiffDocument([missingInShopifyRow("7777", RECENT)])
        Map result = verify(file)
        assertTrue((Boolean) result.rewritten)
        assertEquals(1, result.pendingCount)
    }

    @Test
    void gradesAnAllDigitsStringEntryDateTheSameAsANumber() {
        Map row = [diffType: "MISSING_IN_FILE_2", primaryId: "7778", presentIn: OMS_LABEL, missingIn: SHOPIFY_LABEL,
                   data: JsonOutput.toJson([externalId: "7778", orderExternalId: "7025799037059",
                           entryDate: String.valueOf(RECENT), returnId: "R-7778"]),
                   message: "Present in ${OMS_LABEL}, missing in ${SHOPIFY_LABEL}".toString()]
        File file = writeDiffDocument([row])

        Map result = verify(file)

        assertTrue((Boolean) result.rewritten)
        assertEquals(1, result.pendingCount)
    }

    // --- CANCELLATION-REFUND SUPPRESSION (2026-08-18): a Shopify REFUND against an ORDER_CANCELLED
    // HotWax order can never have an OMS counterpart, because OMS books a cancellation, not a return.

    @Test
    void suppressesAMissingInOmsRefundWhoseOrderIsCancelled() {
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff,
                lookupReturning(["7120801431683": [[statusId: "ORDER_CANCELLED"]]]))

        assertEquals(1, result.cancelledRefundSuppressedCount)
        assertEquals(1, result.removedCount)
        assertEquals(1, result.removedMissingInFile2)
        // OLD is 24h back against a 3h grace: this row is suppressed by cancellation, NOT by grace.
        assertEquals(0, result.pendingCount)
        assertTrue((result.auditNote as String).contains("cancellation refund"))
        assertEquals([], (List) parseDocument(diff).differences)
    }

    @Test
    void keepsAMissingInOmsReturnEvenWhenItsOrderIsCancelled() {
        // Live data: 21 REFUND / 0 RETURN on cancelled orders. A qualifying RETURN means the assumption
        // broke; suppressing it would hide exactly the signal that tells us so.
        File diff = writeDiffDocument([missingInOmsRow("26947223683", OLD, "RETURN", "7120801431683")])

        Map result = verifyWithLookup(diff,
                lookupReturning(["7120801431683": [[statusId: "ORDER_CANCELLED"]]]))

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
        assertEquals(["26947223683"], ((List) parseDocument(diff).differences).collect { it.primaryId })
    }

    @Test
    void neverSuppressesTheMissingInShopifyDirection() {
        // The same rule applied to missing-in-Shopify explained 1 of 579 — indistinguishable from
        // control. The lookup must not even be consulted for that direction.
        File diff = writeDiffDocument([missingInShopifyRow("26949222531", OLD)])

        Map result = verifyWithLookup(diff, { List ids ->
            throw new IllegalStateException("the missing-in-Shopify direction must not be looked up")
        })

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
        assertEquals(["26949222531"], ((List) parseDocument(diff).differences).collect { it.primaryId })
    }

    @Test
    void suppressesWhenAnyRecordInTheOrderGroupIsCancelled() {
        // Real groups carry several records (1,093 status values across 579 orders in the live probe).
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff, lookupReturning(["7120801431683":
                [[statusId: "ORDER_COMPLETED"], [statusId: "ORDER_CANCELLED"]]]))

        assertEquals(1, result.cancelledRefundSuppressedCount)
    }

    @Test
    void leavesARefundAloneWhenNoRecordInTheGroupIsCancelled() {
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff, lookupReturning(["7120801431683":
                [[statusId: "ORDER_COMPLETED"], [statusId: "ORDER_APPROVED"]]]))

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
    }

    @Test
    void suppressesNothingWhenTheLookupReportsNotOk() {
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff,
                lookupReturning(["7120801431683": [[statusId: "ORDER_CANCELLED"]]], false))

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("Cancellation-refund lookup") })
        assertEquals(["954742210691"], ((List) parseDocument(diff).differences).collect { it.primaryId })
    }

    @Test
    void suppressesNothingWhenTheLookupThrows() {
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff, { List ids -> throw new RuntimeException("OMS unreachable") })

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("OMS unreachable") })
    }

    @Test
    void omitsTheCancellationSentenceWhenNothingWasSuppressed() {
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verifyWithLookup(diff,
                lookupReturning(["7120801431683": [[statusId: "ORDER_COMPLETED"]]]))

        assertFalse((result.auditNote as String).contains("cancellation refund"))
    }

    @Test
    void chunksOrderLookupsAtTheServiceCap() {
        // lookup#HotWaxOmsOrdersByExternalId enforces PAIR_LOOKUP_MAX_IDS = 100 and returns ok=false for
        // the WHOLE batch on any single failure, so oversized calls fail wholesale.
        List rows = (1..150).collect { int i ->
            missingInOmsRow("refund-${i}".toString(), OLD, "REFUND", "order-${i}".toString())
        }
        List<Integer> chunkSizes = []
        File diff = writeDiffDocument(rows)

        verifyWithLookup(diff, { List ids ->
            chunkSizes.add(ids.size()); [ok: true, ordersByExternalId: [:], errors: []]
        })

        assertEquals([100, 50], chunkSizes)
    }

    @Test
    void oneFailedChunkDoesNotDiscardASucceedingChunk() {
        List rows = (1..150).collect { int i ->
            missingInOmsRow("refund-${i}".toString(), OLD, "REFUND", "order-${i}".toString())
        }
        File diff = writeDiffDocument(rows)
        int calls = 0

        Map result = verifyWithLookup(diff, { List ids ->
            calls++
            if (calls == 1) return [ok: false, ordersByExternalId: [:], errors: ["boom"]]
            return [ok: true, ordersByExternalId: ids.collectEntries { [(it): [[statusId: "ORDER_CANCELLED"]]] },
                    errors: []]
        })

        assertEquals(50, result.cancelledRefundSuppressedCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("boom") })
        assertEquals(100, ((List) parseDocument(diff).differences).size())
    }

    @Test
    void skipsTheLookupEntirelyWhenTheCandidateSetExceedsTheCap() {
        // Past the cap the sync is broken rather than skewed, and point-checking it only hammers the
        // source API. Mirrors MissingDiffVerificationSupport's own maxLookupIds posture.
        List rows = (1..5).collect { int i ->
            missingInOmsRow("refund-${i}".toString(), OLD, "REFUND", "order-${i}".toString())
        }
        File diff = writeDiffDocument(rows)
        boolean called = false

        Map result = verifyWithLookup(diff, { List ids ->
            called = true; [ok: true, ordersByExternalId: [:], errors: []]
        }, 4)

        assertFalse(called, "the cap must be enforced before any lookup is dispatched")
        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("exceeds the 4-lookup cap") })
    }

    @Test
    void skipsTheLookupEntirelyWhenNoClosureIsSupplied() {
        // Absent closure = the connector declares no orderStateLookupServiceName. Behaviour must be
        // byte-identical to before this feature existed.
        File diff = writeDiffDocument([missingInOmsRow("954742210691", OLD, "REFUND", "7120801431683")])

        Map result = verify(diff)

        assertEquals(0, result.cancelledRefundSuppressedCount)
        assertEquals(0, result.removedCount)
        assertTrue(((List) result.warnings).isEmpty())
        assertEquals(["954742210691"], ((List) parseDocument(diff).differences).collect { it.primaryId })
    }
}
