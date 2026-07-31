package darpan.facade.reconciliation

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

/**
 * Exchange presence check (product contract 2026-07-31): every exchange created in Shopify within
 * the run window must have been imported into OMS. Confirmed absences become missing-from-OMS-side
 * rows that bump the artifact's side counts (the result page tiles), exactly like base compare
 * misses.
 *
 * The diffFile() fixture reproduces {@code ReconciliationServices.writeDiffDatasetOutput}'s
 * line-oriented format that {@code MissingDiffVerificationSupportTests} locks: a "summary" line
 * with "totalDifferences"/"onlyInFile1Count"/"onlyInFile2Count", a "differences":[ header, one
 * JSON row per line ending "," (more rows) or "]" (last row).
 */
class ExchangePairVerificationSupportTests {
    @TempDir Path tempDir

    static final long NOW = 1785999999999L
    static final long WINDOW_START = 1785196800000L   // 2026-07-28T00:00:00Z
    static final long WINDOW_END = 1785283200000L     // 2026-07-29T00:00:00Z
    static final long IN_WINDOW_MATURE = 1785232800000L   // 2026-07-28T10:00Z, far older than 3h vs NOW

    private File manifestFile(List entries) {
        File f = tempDir.resolve("m.exchange-manifest.json").toFile()
        f.text = groovy.json.JsonOutput.toJson([manifest: entries, truncated: false, sourceFileName: "oms.json"])
        return f
    }

    private File diffFile() {
        File f = tempDir.resolve("diff.json").toFile()
        f.text = '{\n"summary":{"totalDifferences":1,"onlyInFile1Count":0,"onlyInFile2Count":1,"missingObjectDifferenceCount":1},\n' +
                '"processingWarnings":["compare warning"],\n' +
                '"differences":[\n' +
                '{"diffType":"MISSING_OBJECT","primaryId":"OTHER1","missingIn":"SHOPIFY","data":{}}]\n' +
                '}\n'
        return f
    }

    private static Map manifestEntry(String externalId) {
        [omsOrderId: "M-${externalId}".toString(), externalId: externalId, orderName: "EXC-${externalId}".toString(),
         toOrderId: "O-${externalId}".toString(), grandTotal: 10.0, orderDate: IN_WINDOW_MATURE, statusId: "ORDER_COMPLETED"]
    }

    private static Map sweepExchange(String externalId, long returnCreatedAt = IN_WINDOW_MATURE) {
        [externalId: externalId, orderName: "#T${externalId}".toString(), returnId: "gid://shopify/Return/r-${externalId}".toString(),
         returnName: "R-${externalId}".toString(), returnStatus: "CLOSED", returnCreatedAtMillis: returnCreatedAt]
    }

    private static Map sweepOk(List exchanges, boolean truncated = false) {
        [ok: true, exchanges: exchanges, truncated: truncated, errors: []]
    }

    /** OMS point-lookup result: for each id, presence of the exchange order is what matters. */
    private static Map omsLookupWithExchange(List<String> ids) {
        [ok: true, errors: [], ordersByExternalId: ids.collectEntries { String id ->
            [(id): [[omsOrderId: "orig-${id}".toString(), externalId: id, hasExchangeAssoc: false],
                     [omsOrderId: "exch-${id}".toString(), externalId: id, hasExchangeAssoc: true]]]
        }]
    }

    private static Map omsLookupWithoutExchange(List<String> ids) {
        [ok: true, errors: [], ordersByExternalId: ids.collectEntries { String id ->
            [(id): [[omsOrderId: "orig-${id}".toString(), externalId: id, hasExchangeAssoc: false]]]
        }]
    }

    private Map runCheck(Map overrides) {
        Map args = [diffFile: diffFile(), nowMillis: NOW, windowStartMillis: WINDOW_START, windowEndMillis: WINDOW_END,
                    omsSideLabel: "HotWax", omsFileSide: "FILE_2",
                    shopifySweep: { long s, long e -> sweepOk([]) },
                    omsPairLookup: { List ids -> omsLookupWithExchange(ids as List<String>) }] + overrides
        return ExchangePairVerificationSupport.verifyExchangePairs(args)
    }

    @Test
    void manifestMatchedExchangesNeedNoLookupAndAppendNothing() {
        File diff = diffFile()
        String before = diff.text
        List<String> lookedUp = []
        Map result = runCheck([diffFile: diff,
                manifestFile: manifestFile([manifestEntry("111"), manifestEntry("222")]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("111"), sweepExchange("222")]) },
                omsPairLookup: { List ids -> lookedUp.addAll(ids as List<String>); omsLookupWithExchange(ids as List<String>) }])
        assertTrue(result.performed as boolean)
        assertEquals(2, result.sweepExchangeCount)
        assertEquals(2, result.matchedCount)
        assertEquals(0, result.appendedCount)
        assertTrue(lookedUp.isEmpty(), "matched exchanges must not trigger lookups")
        assertEquals(before, diff.text)
        assertTrue(result.auditNote.toString().contains("2 matched in HotWax"))
    }

    @Test
    void unmatchedCandidateConfirmedPresentByLookupAppendsNothing() {
        // Window-boundary shape: return processed 23:50, OMS exchange order created after midnight —
        // absent from the manifest but findable by the point lookup, which escapes windows.
        File diff = diffFile()
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("333")]) },
                omsPairLookup: { List ids -> omsLookupWithExchange(ids as List<String>) }])
        assertEquals(1, result.confirmedPresentCount)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
    }

    @Test
    void confirmedAbsenceAppendsMissingFromOmsRowAndBumpsTiles() {
        File diff = diffFile()
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("444")]) },
                omsPairLookup: { List ids -> omsLookupWithoutExchange(ids as List<String>) }])
        assertEquals(1, result.appendedCount)

        Map parsed = (Map) new JsonSlurper().parseText(diff.text)   // document must stay valid JSON
        List differences = (List) parsed.differences
        assertEquals(2, differences.size())
        Map row = (Map) differences.last()
        assertEquals("EXCHANGE_MISSING_IN_OMS", row.diffType)
        assertEquals("444", row.primaryId)
        assertEquals("HotWax", row.missingIn)
        assertTrue(row.note.toString().contains("has no exchange order in HotWax"))
        // Tile counting: OMS is file2 here, so a missing exchange is present only in file1.
        Map summary = (Map) parsed.summary
        assertEquals(2, summary.totalDifferences)
        assertEquals(1, summary.onlyInFile1Count)
        assertEquals(1, summary.onlyInFile2Count)          // untouched
        assertEquals(2, summary.missingObjectDifferenceCount)
        // Audit note lands in the artifact's processingWarnings alongside the pre-existing warning.
        List processingWarnings = (List) parsed.processingWarnings
        assertEquals(2, processingWarnings.size())
        assertEquals("compare warning", processingWarnings[0])
        assertEquals(result.auditNote, processingWarnings[1])
    }

    @Test
    void omsAsFileOneBumpsTheOppositeSideCount() {
        File diff = diffFile()
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]), omsFileSide: "FILE_1", omsSideLabel: "OMS",
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("445")]) },
                omsPairLookup: { List ids -> omsLookupWithoutExchange(ids as List<String>) }])
        assertEquals(1, result.appendedCount)
        Map summary = (Map) ((Map) new JsonSlurper().parseText(diff.text)).summary
        assertEquals(2, summary.onlyInFile2Count)
        assertEquals(0, summary.onlyInFile1Count)
    }

    @Test
    void youngExchangesArePendingAndNeverLookedUp() {
        long twoHoursOld = NOW - 2 * 3600000L
        File diff = diffFile()
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("555", twoHoursOld)]) },
                omsPairLookup: { List ids -> fail("young exchanges must not be looked up") }])
        assertEquals(1, result.pendingCount)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
        assertTrue(result.auditNote.toString().contains("1 pending (younger than 3h)"))
    }

    @Test
    void repeatExchangeShortfallIsNotMaskedByAnEarlierImportedExchange() {
        // Order 900 has 2 Shopify exchanges; OMS has only 1 exchange order (the earlier one).
        // Per-order presence would call it present; per-exchange counting must flag the shortfall —
        // and the manifest shortcut must NOT settle repeat-exchange orders.
        File diff = diffFile()
        Map exchange = sweepExchange("900") + [exchangeReturnCount: 2]
        Map omsOneExchange = [ok: true, errors: [], ordersByExternalId: ["900": [
                [omsOrderId: "orig-900", externalId: "900", hasExchangeAssoc: false],
                [omsOrderId: "exch-900-1", externalId: "900", hasExchangeAssoc: true]]]]
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([manifestEntry("900")]),
                shopifySweep: { long s, long e -> sweepOk([exchange]) },
                omsPairLookup: { List ids -> omsOneExchange }])
        assertEquals(0, result.matchedCount)   // manifest shortcut refused for count > 1
        assertEquals(1, result.appendedCount)
        Map row = (Map) ((List) ((Map) new JsonSlurper().parseText(diff.text)).differences).last()
        assertTrue(row.note.toString().contains("2 Shopify exchange(s) but only 1 exchange order(s)"))
        assertEquals(2, ((Map) row.data).shopifyExchangeReturnCount)
        assertEquals(1, ((Map) row.data).omsExchangeOrderCount)
    }

    @Test
    void repeatExchangeFullyImportedCountsAsPresent() {
        Map exchange = sweepExchange("901") + [exchangeReturnCount: 2]
        Map omsTwoExchanges = [ok: true, errors: [], ordersByExternalId: ["901": [
                [omsOrderId: "orig-901", externalId: "901", hasExchangeAssoc: false],
                [omsOrderId: "exch-901-1", externalId: "901", hasExchangeAssoc: true],
                [omsOrderId: "exch-901-2", externalId: "901", hasExchangeAssoc: true]]]]
        Map result = runCheck([manifestFile: manifestFile([manifestEntry("901")]),
                shopifySweep: { long s, long e -> sweepOk([exchange]) },
                omsPairLookup: { List ids -> omsTwoExchanges }])
        assertEquals(1, result.confirmedPresentCount)
        assertEquals(0, result.appendedCount)
    }

    @Test
    void lookupCapDefersExcessCandidatesWithVisibleQueue() {
        File diff = diffFile()
        String before = diff.text
        List exchanges = (1..5).collect { sweepExchange("EXT${it}".toString()) }
        List<String> lookedUp = []
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]), maxLookups: 2,
                shopifySweep: { long s, long e -> sweepOk(exchanges) },
                omsPairLookup: { List ids -> lookedUp.addAll(ids as List<String>); omsLookupWithExchange(ids as List<String>) }])
        assertEquals(2, lookedUp.size())
        assertEquals(3, result.deferredLookupCount)
        assertEquals(before, diff.text)
        assertTrue(result.auditNote.toString().contains("3 deferred"))
    }

    @Test
    void sweepFailureIsInertWithWarnings() {
        File diff = diffFile()
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> [ok: false, exchanges: [], errors: ["HTTP 500"]] },
                omsPairLookup: { List ids -> fail("no lookups after a failed sweep") }])
        assertTrue(result.lookupFailed as boolean)
        assertFalse(result.performed as boolean)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
        assertFalse((result.warnings as List).isEmpty())
    }

    @Test
    void omsLookupFailureIsInertWithWarnings() {
        File diff = diffFile()
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("666")]) },
                omsPairLookup: { List ids -> [ok: false, errors: ["HTTP 500"], ordersByExternalId: [:]] }])
        assertTrue(result.lookupFailed as boolean)
        assertEquals(0, result.appendedCount)
        assertEquals(before, diff.text)
    }

    @Test
    void missingManifestMeansEmptyOmsSetNotInertness() {
        // An OMS window with zero exchange orders writes no sidecar — the presence check must still
        // run: Shopify exchanges with no OMS import are exactly what it exists to catch.
        File diff = diffFile()
        Map result = runCheck([diffFile: diff, manifestFile: tempDir.resolve("nope.json").toFile(),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("777")]) },
                omsPairLookup: { List ids -> omsLookupWithoutExchange(ids as List<String>) }])
        assertTrue(result.performed as boolean)
        assertEquals(1, result.appendedCount)
    }

    @Test
    void malformedManifestIsInertWithWarning() {
        File manifest = tempDir.resolve("bad.exchange-manifest.json").toFile()
        manifest.text = "{ not valid json"
        File diff = diffFile()
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifest,
                shopifySweep: { long s, long e -> fail("no sweep on unreadable manifest") },
                omsPairLookup: { List ids -> fail("no lookups on unreadable manifest") }])
        assertFalse(result.performed as boolean)
        assertTrue((result.warnings as List).any { it.toString().contains("unreadable") })
        assertEquals(before, diff.text)
    }

    @Test
    void truncatedSweepWarnsAndMarksPartialCoverage() {
        Map result = runCheck([manifestFile: manifestFile([manifestEntry("888")]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("888")], true) }])
        assertTrue(result.sweepTruncated as boolean)
        assertTrue(result.auditNote.toString().contains("Sweep truncated"))
        assertTrue((result.warnings as List).any { it.toString().contains("page cap") })
    }

    @Test
    void chunksCandidateLookupsToTheServiceCap() {
        List exchanges = (1..150).collect { sweepExchange("EXT${it}".toString()) }
        List<Integer> chunkSizes = []
        Map result = runCheck([manifestFile: manifestFile([]), maxLookups: 150,
                shopifySweep: { long s, long e -> sweepOk(exchanges) },
                omsPairLookup: { List ids -> chunkSizes.add(ids.size()); omsLookupWithExchange(ids as List<String>) }])
        assertEquals([100, 50], chunkSizes)
        assertEquals(150, result.confirmedPresentCount)
        assertEquals(0, result.appendedCount)
    }

    @Test
    void diffDocumentWithoutDifferencesSectionIsInertWithWarning() {
        File diff = tempDir.resolve("no-diffs-section.json").toFile()
        diff.text = '{\n"summary":{"totalDifferences":0}\n}\n'
        String before = diff.text
        Map result = runCheck([diffFile: diff, manifestFile: manifestFile([]),
                shopifySweep: { long s, long e -> sweepOk([sweepExchange("999")]) },
                omsPairLookup: { List ids -> omsLookupWithoutExchange(ids as List<String>) }])
        assertEquals(0, result.appendedCount)
        assertTrue((result.warnings as List).any { it.toString().contains("could not write diff rows") })
        assertEquals(before, diff.text)
    }
}
