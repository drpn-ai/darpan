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

    static final long NOW = 1785999999999L        // > manifest orderDate + 7d grace
    static final long RECENT = NOW - 86400000L    // 1 day old -> pending

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

    private static Map entry(Map overrides = [:]) {
        [omsOrderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
         toOrderId: "M686331", grandTotal: 50.0, orderDate: 1785260782199L, statusId: "ORDER_COMPLETED"] + overrides
    }

    private static Map omsPairOk() {
        [ok: true, errors: [], ordersByExternalId: ["6941645013123": [
            [omsOrderId: "M686331", externalId: "6941645013123", orderTypeId: "SALES_ORDER",
             statusId: "ORDER_COMPLETED", grandTotal: 135.71, orderDate: 1784227520000L, hasExchangeAssoc: false],
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
    void amountMismatchBeyondToleranceAppendsV2Row() {
        File diff = diffFile()
        Map result = ExchangePairVerificationSupport.verifyExchangePairs([
                manifestFile: manifestFile([entry()]), diffFile: diff, nowMillis: NOW,
                shopifyLookup: { List ids -> shopifyOk(new BigDecimal("100.00")) },
                omsPairLookup: { List ids -> omsPairOk() }])
        assertEquals(1, result.appendedByType.EXCHANGE_PAIR_AMOUNT_MISMATCH)
        assertTrue(diff.text.contains('"omsPairTotal":185.71'))
        assertTrue(diff.text.contains('"shopifyCurrentTotal":100.00') || diff.text.contains('"shopifyCurrentTotal":100.0'))
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
}
