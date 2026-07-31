package darpan.facade.reconciliation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

/**
 * Exchange pair verify stage (spec 2026-07-30-exchange-order-reconciliation-design.md, Task 6):
 * reads the OMS extraction's exchange-manifest sidecar and point-checks each pair against both
 * sources of record via caller-supplied Closures, appending discrepancy rows to a diff document.
 *
 * The diffFile() fixture reproduces the {@code ReconciliationServices.writeDiffDatasetOutput}
 * line-oriented format that {@code MissingDiffVerificationSupport} streams and
 * {@code MissingDiffVerificationSupportTests} locks: a "summary" line carrying the document's
 * running total under the key "totalDifferences" (NOT "differenceCount" — that name only appears
 * in in-memory result maps elsewhere in the codebase; the on-disk summary object writers
 * (ReconciliationServices.writeDiffDatasetOutput, reconcileGenericFiles, pollSftpAndRunReconciliation)
 * all use "totalDifferences"), followed by a "differences":[ header and one JSON row per line ending
 * "," (more rows) or "]" (last row).
 */
class ExchangePairVerificationSupportTests {
    @TempDir Path tempDir

    static final long NOW = 1785999999999L        // > manifest orderDate + 3h grace
    static final long RECENT = NOW - 2 * 3600000L // 2 hours old -> pending (inside the 3h sync grace)

    private File manifestFile(List entries) {
        File f = tempDir.resolve("m.exchange-manifest.json").toFile()
        f.text = groovy.json.JsonOutput.toJson([manifest: entries, truncated: false, sourceFileName: "oms.json"])
        return f
    }

    private File diffFile() {
        File f = tempDir.resolve("diff.json").toFile()
        f.text = '{\n"summary":{"totalDifferences":1,"missingObjectDifferenceCount":1},\n' +
                '"differences":[\n' +
                '{"diffType":"MISSING_OBJECT","primaryId":"OTHER1","missingIn":"SHOPIFY","data":{}}]\n' +
                '}\n'
        return f
    }

    /** writeDiffDatasetOutput's zero-row shape: header and closing "]" collapse onto one line. */
    private File emptyDifferencesDiffFile() {
        File f = tempDir.resolve("empty-diff.json").toFile()
        f.text = '{\n"summary":{"totalDifferences":0},\n' +
                '"differences":[]\n' +
                '}\n'
        return f
    }

    /** No "differences":[ line anywhere — exercises appendDiffRows' has-no-differences-section guard. */
    private File diffFileWithoutDifferencesSection() {
        File f = tempDir.resolve("no-diffs-section.json").toFile()
        f.text = '{\n"summary":{"totalDifferences":0}\n}\n'
        return f
    }

    /** Full writeDiffDatasetOutput shape (metadata/summary/validationErrors/processingWarnings/
     *  differences) — the slimmer diffFile() fixture above has no "processingWarnings" line at all,
     *  so it can't exercise the audit-note injection into the artifact (review finding, Task 7). */
    private File diffFileWithProcessingWarnings() {
        File f = tempDir.resolve("diff-with-warnings.json").toFile()
        f.text = '{\n"metadata":{"file1Label":"OMS","file2Label":"SHOPIFY"},\n' +
                '"summary":{"totalDifferences":1,"missingObjectDifferenceCount":1},\n' +
                '"validationErrors":[],\n' +
                '"processingWarnings":["compare warning"],\n' +
                '"differences":[\n' +
                '{"diffType":"MISSING_OBJECT","primaryId":"OTHER1","missingIn":"SHOPIFY","data":{}}]\n' +
                '}\n'
        return f
    }

    private static Map entry(Map overrides = [:]) {
        [omsOrderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
         toOrderId: "M686331", grandTotal: 50.0, orderDate: 1785260782199L, statusId: "ORDER_COMPLETED"] + overrides
    }

    /** Real-world shape (live-verified): the ORIGINAL's grandTotal already equals Shopify's current
     *  total — the exchange order's own total rides alongside and must not be added to it. */
    private static Map omsPairOk() {
        [ok: true, errors: [], ordersByExternalId: ["6941645013123": [
            [omsOrderId: "M686331", externalId: "6941645013123", orderTypeId: "SALES_ORDER",
             statusId: "ORDER_COMPLETED", grandTotal: 185.71, orderDate: 1784227520000L, hasExchangeAssoc: false],
            [omsOrderId: "M750653", externalId: "6941645013123", orderTypeId: "SALES_ORDER",
             statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: 1785260782199L, hasExchangeAssoc: true]]]]
    }

    private static Map shopifyOk(BigDecimal amount = new BigDecimal("185.71"), boolean withExchange = true) {
        [ok: true, errors: [], statesByOrderId: ["6941645013123": [
            returnStatus: "RETURNED", currentTotalAmount: amount, currentTotalCurrency: "USD",
            exchanges: withExchange ? [[returnId: "gid://shopify/Return/25734480003", status: "CLOSED",
                    createdAt: "2026-07-23T17:07:40Z", exchangeLineItems: [[id: "x", quantity: 1, lineItems: []]]]] : []]]]
    }

    @Test
    void healthyPairAppendsNothing() {
        File diff = diffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk() }, omsPairLookup: { List ids -> omsPairOk() }])
        assertTrue(result.performed as boolean)
        assertEquals(0, result.appendedCount)
        assertEquals(1, result.checkedPairCount)
        assertTrue(diff.text.contains('"totalDifferences":1'))   // untouched
    }

    @Test
    void missingShopifyExchangeAppendsV1RowAndBumpsSummary() {
        File diff = diffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("185.71"), false) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(1, result.appendedCount)
        assertEquals(1, result.appendedByType.EXCHANGE_MISSING_IN_SHOPIFY)
        String text = diff.text
        assertTrue(text.contains('"diffType":"EXCHANGE_MISSING_IN_SHOPIFY"'))
        assertTrue(text.contains('"totalDifferences":2'))
        Map parsed = (Map) new JsonSlurper().parseText(text)   // document must stay valid JSON
        assertEquals(2, ((List) parsed.differences).size())
        assertEquals("6941645013123", ((Map) ((List) parsed.differences).last()).primaryId)
    }

    @Test
    void appendedRowsInjectAuditNoteIntoArtifactProcessingWarnings() {
        // Review finding (Task 7): appended rows must not show up in a reopened saved run with no
        // explanation — the audit note has to land in the artifact's own processingWarnings during
        // the same rewrite pass that adds the rows, mirroring MissingDiffVerificationSupport.
        File diff = diffFileWithProcessingWarnings()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("185.71"), false) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(1, result.appendedCount)
        assertNotNull(result.auditNote)
        assertTrue(result.auditNote.toString().contains("1 discrepancy row(s) appended"))

        Map parsed = (Map) new JsonSlurper().parseText(diff.text)   // document must stay valid JSON
        List processingWarnings = (List) parsed.processingWarnings
        assertEquals(2, processingWarnings.size())
        assertEquals("compare warning", processingWarnings[0])       // pre-existing warning preserved
        assertEquals(result.auditNote, processingWarnings[1])        // audit note appended, not replaced
        assertEquals(2, ((List) parsed.differences).size())
        assertEquals(2, ((Map) parsed.summary).totalDifferences)
    }

    @Test
    void unresolvedShopifyOrderAppendsNothingAndCountsUnresolved() {
        // ok:true but no entry for the externalId at all (deleted/archived/never-Shopify id) is
        // evidence-free, not evidence of a missing exchange — must not become a V1 row. Use a
        // fixture where the OMS pair has the original present so V3 does not also fire.
        File diff = diffFile()
        String before = diff.text
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> [ok: true, errors: [], statesByOrderId: [:]] },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(0, result.appendedCount)
        assertEquals(1, result.unresolvedShopifyCount)
        assertEquals(1, result.checkedPairCount)
        assertTrue((result.warnings as List).any { it.toString().contains("could not resolve") })
        assertEquals(before, diff.text)
    }

    @Test
    void amountMismatchBeyondToleranceAppendsV2Row() {
        // V2 compares the ORIGINAL order's total against Shopify's current total. Live data
        // (2026-07-31, 19/19 pairs) proved HotWax mutates the original to the current net state,
        // so summing the exchange order's total double-counts it by exactly that amount.
        File diff = diffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("100.00")) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(1, result.appendedByType.EXCHANGE_PAIR_AMOUNT_MISMATCH)
        assertTrue(diff.text.contains('"omsOriginalTotal":185.71'))
        assertTrue(diff.text.contains('"shopifyCurrentTotal":100.00') || diff.text.contains('"shopifyCurrentTotal":100.0'))
    }

    @Test
    void healthyEvenExchangeDoesNotFlagV2WhenOriginalMatchesShopifyCurrent() {
        // The real-world healthy shape: OMS original 185.71 == Shopify current 185.71, exchange
        // order carries its own 50.00. The old pair-sum comparand flagged 100% of these.
        Map omsPair = [ok: true, errors: [], ordersByExternalId: ["6941645013123": [
                [omsOrderId: "M686331", externalId: "6941645013123", orderTypeId: "SALES_ORDER",
                 statusId: "ORDER_COMPLETED", grandTotal: 185.71, orderDate: 1784227520000L, hasExchangeAssoc: false],
                [omsOrderId: "M750653", externalId: "6941645013123", orderTypeId: "SALES_ORDER",
                 statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: 1785260782199L, hasExchangeAssoc: true]]]]
        File diff = diffFile()
        String before = diff.text
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("185.71")) },
                omsPairLookup: { List ids -> omsPair }])
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
    }

    @Test
    void missingOriginalAppendsV3RowAndSkipsV2() {
        Map omsOnlyExchange = [ok: true, errors: [], ordersByExternalId: ["6941645013123":
                [[omsOrderId: "M750653", hasExchangeAssoc: true, grandTotal: 50.0, orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED"]]]]
        File diff = diffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk() }, omsPairLookup: { List ids -> omsOnlyExchange }])
        assertEquals([EXCHANGE_ORIGINAL_MISSING_IN_OMS: 1], result.appendedByType)
    }

    @Test
    void youngEntriesArePendingAndCancelledAreSkipped() {
        List entries = [entry([orderDate: RECENT]), entry([omsOrderId: "MC", externalId: "999", statusId: "ORDER_CANCELLED"])]
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile(entries), diffFile: diffFile(), nowMillis: NOW,
                shopifyLookup: { List ids -> fail("should not look up pending/cancelled") },
                omsPairLookup: { List ids -> fail("should not look up pending/cancelled") }])
        assertEquals(1, result.pendingCount)
        assertEquals(1, result.skippedCancelledCount)
        assertEquals(0, result.checkedPairCount)
        assertEquals(0, result.appendedCount)
        // The operator must see the queue even when nothing was checked — an all-pending run
        // previously surfaced no audit note at all and read as a clean pass.
        assertNotNull(result.auditNote)
        assertTrue(result.auditNote.toString().contains("1 pending"))
        assertTrue(result.auditNote.toString().contains("1 cancelled"))
    }

    @Test
    void graceWindowIsThreeHoursFromCreation() {
        // Product rule (2026-07-31): ignore exchanges within 3 hours of creation — they may still
        // be syncing into OMS. Anything older is due immediately: the OMS exchange order's creation
        // date IS the return-processing date (sync itself observed at ~38 minutes), so the old
        // 7-day wait was guarding against a lag that does not exist in this direction.
        long twoHours59 = NOW - (2 * 3600000L + 59 * 60000L)   // 2h59m old -> still pending
        long threeHours01 = NOW - (3 * 3600000L + 60000L)      // 3h01m old -> due for checking
        List entries = [entry([orderDate: twoHours59]),
                        entry([omsOrderId: "M2", externalId: "777", orderName: "EXC-2", orderDate: threeHours01])]
        List<String> lookedUp = []
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile(entries), diffFile: diffFile(), nowMillis: NOW,
                shopifyLookup: { List ids -> lookedUp.addAll(ids as List<String>); shopifyOkForIds(ids as List<String>) },
                omsPairLookup: { List ids -> omsPairOkFor(ids as List<String>) }])
        assertEquals(1, result.pendingCount)
        assertEquals(1, result.checkedPairCount)
        assertEquals(["777"], lookedUp)
    }

    @Test
    void lookupFailureIsInertWithWarnings() {
        File diff = diffFile()
        String before = diff.text
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> [ok: false, errors: ["HTTP 500"], statesByOrderId: [:]] },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertTrue(result.lookupFailed as boolean)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
        assertFalse((result.warnings as List).isEmpty())
    }

    @Test
    void missingManifestFileIsInert() {
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: tempDir.resolve("nope.json").toFile(), diffFile: diffFile(), nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk() }, omsPairLookup: { List ids -> omsPairOk() }])
        assertFalse(result.performed as boolean)
    }

    @Test
    void emptyDifferencesDocumentAcceptsAppendedRow() {
        File diff = emptyDifferencesDiffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("185.71"), false) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(1, result.appendedCount)
        String text = diff.text
        assertTrue(text.contains('"totalDifferences":1'))
        Map parsed = (Map) new JsonSlurper().parseText(text)   // document must stay valid JSON
        assertEquals(1, ((List) parsed.differences).size())
        assertEquals("6941645013123", ((Map) ((List) parsed.differences).first()).primaryId)
    }

    @Test
    void overCapPairsCheckTheFirstChunkAndDeferTheRest() {
        // Volume finding (2026-07-31, gorjana ~486 exchanges/day): skipping everything when the
        // pair count exceeds the cap meant busy tenants would never get ANY pair verified. The cap
        // is now a per-run latency bound: check the first maxPairs, count the rest as deferred, and
        // say so — never skip-all, never stall the interactive run.
        File diff = diffFile()
        String before = diff.text
        List<String> lookedUp = []
        List entries = [entry(), entry([omsOrderId: "M999", externalId: "8888888888888", orderName: "EXC-9"])]
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile(entries), diffFile: diff, nowMillis: NOW, maxPairs: 1,
                shopifyLookup: { List ids -> lookedUp.addAll(ids as List<String>); shopifyOkForIds(ids as List<String>) },
                omsPairLookup: { List ids -> omsPairOkFor(ids as List<String>) }])
        assertTrue(result.performed as boolean)
        assertEquals(1, result.checkedPairCount)
        assertEquals(1, result.deferredPairCount)
        assertEquals(["6941645013123"], lookedUp)   // manifest order: first pair in, second deferred
        assertEquals(0, result.appendedCount)       // the checked pair is healthy
        assertTrue((result.warnings as List).any { it.toString().contains("deferred") })
        assertTrue(result.auditNote.toString().contains("1 deferred"))
        assertEquals(before, diff.text)
    }

    @Test
    void malformedManifestJsonIsInertWithWarning() {
        File manifest = tempDir.resolve("bad.exchange-manifest.json").toFile()
        manifest.text = "{ not valid json"
        File diff = diffFile()
        String before = diff.text
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifest, diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> fail("should not look up on malformed manifest") },
                omsPairLookup: { List ids -> fail("should not look up on malformed manifest") }])
        assertFalse(result.performed as boolean)
        assertTrue((result.warnings as List).any { it.toString().contains("unreadable") })
        assertEquals(before, diff.text)
    }

    /**
     * No clean injection point exists to force an I/O failure mid-write, so this hits
     * appendDiffRows' "no differences section to append to" precondition instead — the same
     * IllegalStateException path a genuinely malformed/foreign diff document would trigger.
     * verifyExchangePairs must catch it, warn, and leave the file untouched with no rows counted.
     */
    /** 150 distinct externalIds so the entriesByExternalId map (150) stays under maxPairs (500)
     *  but must still be split across two omsPairLookup calls at the 100-id service cap. */
    private static List manifestOf(int count) {
        (1..count).collect { int i ->
            entry([omsOrderId: "M${1000 + i}".toString(), externalId: "EXT${100000 + i}".toString(),
                   orderName: "EXC-${i}".toString()])
        }
    }

    private static Map omsPairOkFor(List<String> ids) {
        [ok: true, errors: [], ordersByExternalId: ids.collectEntries { String id ->
            [(id): [[omsOrderId: "orig-${id}".toString(), externalId: id, orderTypeId: "SALES_ORDER",
                      statusId: "ORDER_COMPLETED", grandTotal: 20.0, orderDate: 1784227520000L, hasExchangeAssoc: false],
                     [omsOrderId: "exch-${id}".toString(), externalId: id, orderTypeId: "SALES_ORDER",
                      statusId: "ORDER_COMPLETED", grandTotal: 10.0, orderDate: 1785260782199L, hasExchangeAssoc: true]]]
        }]
    }

    private static Map shopifyOkForIds(List<String> ids) {
        [ok: true, errors: [], statesByOrderId: ids.collectEntries { String id ->
            [(id): [returnStatus: "RETURNED", currentTotalAmount: new BigDecimal("20.00"), currentTotalCurrency: "USD",
                     exchanges: [[returnId: "gid://shopify/Return/1", status: "CLOSED",
                             createdAt: "2026-07-23T17:07:40Z", exchangeLineItems: [[id: "x", quantity: 1, lineItems: []]]]]]]
        }]
    }

    @Test
    void largeManifestChunksOmsPairLookupToTheServiceCap() {
        // Finding: verifyExchangePairs sent ALL externalIds (up to maxPairs=500) in one
        // omsPairLookup.call(), but the OMS pair lookup service hard-rejects >100 ids per call —
        // silently no-oping verification for 101-500 pairs. 150 distinct pairs must now be split
        // into chunks of [100, 50], with every pair still checked.
        List entries = manifestOf(150)
        List<Integer> chunkSizes = []
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile(entries), diffFile: diffFile(), nowMillis: NOW, maxPairs: 500,
                shopifyLookup: { List ids -> shopifyOkForIds(ids as List<String>) },
                omsPairLookup: { List ids -> chunkSizes.add(ids.size()); omsPairOkFor(ids as List<String>) }])

        assertEquals([100, 50], chunkSizes)
        assertEquals(150, result.checkedPairCount)
        assertEquals(0, result.appendedCount)   // every pair is healthy: no rows appended
    }

    @Test
    void secondChunkFailureFailsTheWholeLookupAndLeavesDiffUntouched() {
        File diff = diffFile()
        String before = diff.text
        List entries = manifestOf(150)
        List<Integer> chunkSizes = []
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile(entries), diffFile: diff, nowMillis: NOW, maxPairs: 500,
                shopifyLookup: { List ids -> shopifyOkForIds(ids as List<String>) },
                omsPairLookup: { List ids ->
                    chunkSizes.add(ids.size())
                    if (chunkSizes.size() == 2) return [ok: false, errors: ["HTTP 500"], ordersByExternalId: [:]]
                    return omsPairOkFor(ids as List<String>)
                }])

        assertEquals([100, 50], chunkSizes)   // stopped after the failing (second) chunk, no third call
        assertTrue(result.lookupFailed as boolean)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)   // byte-identical: nothing appended
        assertFalse((result.warnings as List).isEmpty())
    }

    @Test
    void diffDocumentWithoutDifferencesSectionIsInertWithWarning() {
        File diff = diffFileWithoutDifferencesSection()
        String before = diff.text
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("185.71"), false) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(0, result.appendedCount)
        assertTrue((result.warnings as List).any { it.toString().contains("could not write diff rows") })
        assertEquals(before, diff.text)
    }
}
