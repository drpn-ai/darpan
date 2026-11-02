package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-180: a return on an order past Shopify's 60-day read limit is rebuilt from its event and keyed by
 * the EVENT id (DAR-BE-085), while HotWax keys the same return by the refund id. The compare therefore
 * reports both halves of one return as missing — measured on gorjana 2026-10 (run 102152): 10,978 rebuilt
 * rows, 10,818 of which pair 1:1 with a HotWax return on the same order + line. This pass pairs them in the
 * diff document itself, before any paid lookup sees them.
 *
 * Fixtures follow writeDiffDatasetOutput's line-oriented format (see ReturnPresenceVerificationSupportTests).
 * file1 = HotWax, file2 = Shopify, as on gorjana's RS_RETURNS_GRAPHQL.
 */
class RebuiltReturnPairingSupportTests {

    @TempDir
    File tempDir

    private static final String OMS_LABEL = "HotWax returns"
    private static final String SHOPIFY_LABEL = "Shopify returns"
    private static final long DAY = 86_400_000L
    private static final long T = 1_793_000_000_000L

    /** The Shopify rebuilt row, present in Shopify, missing in HotWax (file1). */
    private static Map rebuiltRow(String eventId, String orderId, String lineIds, long createdAt, Boolean readable = false) {
        Map data = [refundOrReturnId: eventId, refundOrReturnType: "RETURN", orderId: orderId,
                    createdAt: new Date(createdAt).toInstant().toString(), lineIds: lineIds]
        if (readable != null) data.orderReadable = readable
        return [diffType: "MISSING_IN_FILE_1", primaryId: eventId, presentIn: SHOPIFY_LABEL, missingIn: OMS_LABEL,
                data: JsonOutput.toJson(data), message: "Present in ${SHOPIFY_LABEL}, missing in ${OMS_LABEL}".toString()]
    }

    /** The HotWax return, present in HotWax, missing in Shopify (file2); items carry the Shopify line id. */
    private static Map omsRow(String externalId, String orderExternalId, List<String> lineIds, long entryDate,
                              String statusId = "RETURN_COMPLETED") {
        Map data = [returnId: "M${externalId}".toString(), externalId: externalId, orderExternalId: orderExternalId,
                    entryDate: entryDate, statusId: statusId,
                    items: lineIds.withIndex().collect { String id, int i ->
                        [returnItemSeqId: String.format("%05d", i + 1), orderItemExternalId: id, returnQuantity: 1] }]
        return [diffType: "MISSING_IN_FILE_2", primaryId: externalId, presentIn: OMS_LABEL, missingIn: SHOPIFY_LABEL,
                data: JsonOutput.toJson(data), message: "Present in ${OMS_LABEL}, missing in ${SHOPIFY_LABEL}".toString()]
    }

    private File diff(List<Map> rows) {
        File file = new File(tempDir, "diff-${System.nanoTime()}.json")
        int missingInOms = rows.count { it.missingIn == OMS_LABEL } as int
        int missingInShopify = rows.count { it.missingIn == SHOPIFY_LABEL } as int
        file.withWriter("UTF-8") { w ->
            w << "{\n"
            w << "\"metadata\":" + JsonOutput.toJson([file1Label: OMS_LABEL, file2Label: SHOPIFY_LABEL]) + ",\n"
            // onlyInFile1 = present only in file1 (HotWax) = missing in Shopify, and vice versa.
            w << "\"summary\":" + JsonOutput.toJson([totalDifferences: rows.size(), onlyInFile1Count: missingInShopify,
                    onlyInFile2Count: missingInOms, missingObjectDifferenceCount: rows.size()]) + ",\n"
            w << "\"validationErrors\":[],\n"
            w << "\"processingWarnings\":" + JsonOutput.toJson(["compare warning"]) + ",\n"
            w << "\"differences\":["
            rows.eachWithIndex { Map row, int i -> w << (i ? "," : "") << "\n" << JsonOutput.toJson(row) }
            w << "]\n}"
        }
        return file
    }

    private static Map pair(File file) {
        return RebuiltReturnPairingSupport.pairRebuiltReturns([diffFile: file, file1Label: OMS_LABEL, file2Label: SHOPIFY_LABEL,
                                                               omsSideLabel: OMS_LABEL, shopifySideLabel: SHOPIFY_LABEL])
    }

    private static Map doc(File file) { (Map) new JsonSlurper().parseText(file.getText("UTF-8")) }

    private static List<String> remainingIds(File file) { ((List<Map>) doc(file).differences).collect { it.primaryId as String } }

    @Test
    void aRebuiltReturnAndItsHotWaxReturnOnTheSameOrderAndLineAreBothRemoved() {
        // gorjana 6872256348291-shaped: event 301523693011075, line 15542749593731, HotWax refund-keyed return.
        File file = diff([rebuiltRow("301523693011075", "6872256348291", "15542749593731", T),
                          omsRow("1113522602115", "6872256348291", ["15542749593731"], T - DAY)])

        Map result = pair(file)

        assertEquals(true, result.rewritten)
        assertEquals(2, result.removedCount)
        assertEquals(1, result.pairedCount)
        assertEquals(1, result.removedMissingInFile1, "the HotWax row: present only in file1")
        assertEquals(1, result.removedMissingInFile2, "the Shopify row: present only in file2")
        assertEquals([], remainingIds(file))
        Map summary = (Map) doc(file).summary
        assertEquals(0, summary.totalDifferences)
        assertEquals(0, summary.onlyInFile1Count)
        assertEquals(0, summary.onlyInFile2Count)
        assertTrue(((String) result.auditNote).startsWith(RebuiltReturnPairingSupport.AUDIT_NOTE_PREFIX), "${result.auditNote}")
        assertTrue(((List) doc(file).processingWarnings).contains(result.auditNote))
    }

    @Test
    void aDifferentLineOnTheSameOrderDoesNotPair() {
        File file = diff([rebuiltRow("1", "6000", "111", T), omsRow("A", "6000", ["222"], T)])

        Map result = pair(file)

        assertEquals(0, result.pairedCount)
        assertEquals(false, result.rewritten)
        assertEquals(["1", "A"], remainingIds(file).sort())
    }

    @Test
    void aReadableRowIsLeftForTheIdBasedPasses() {
        // Readable rows carry real refund/return ids; pairing them by line would hide a wrong-id mismatch.
        File file = diff([rebuiltRow("1", "6000", "111", T, null), rebuiltRow("2", "6001", "112", T, true),
                          omsRow("A", "6000", ["111"], T), omsRow("B", "6001", ["112"], T)])

        assertEquals(0, pair(file).pairedCount)
        assertEquals(4, remainingIds(file).size())
    }

    @Test
    void aHotWaxReturnEnteredMoreThanThirtyDaysBeforeTheEventDoesNotPair() {
        // Same rule as Pass 1.6's REBUILT_RETURN eligibility: an older HotWax return is a different return.
        File file = diff([rebuiltRow("1", "6000", "111", T), omsRow("A", "6000", ["111"], T - 31 * DAY)])

        assertEquals(0, pair(file).pairedCount)
    }

    @Test
    void aHotWaxReturnEnteredAfterTheEventPairs() {
        // HotWax lags Shopify; the return is entered after the Shopify event as a rule.
        File file = diff([rebuiltRow("1", "6000", "111", T), omsRow("A", "6000", ["111"], T + 2 * DAY)])

        assertEquals(1, pair(file).pairedCount)
    }

    @Test
    void oneHotWaxReturnLineAccountsForOneShopifyEventOldestFirst() {
        File file = diff([rebuiltRow("late", "6000", "111", T + DAY), rebuiltRow("early", "6000", "111", T),
                          omsRow("A", "6000", ["111"], T)])

        Map result = pair(file)

        assertEquals(1, result.pairedCount)
        assertEquals(["late"], remainingIds(file), "the second event on the same line is a real difference")
    }

    @Test
    void aTwoLineHotWaxReturnIsRemovedOnlyOnceBothItsLinesArePaired() {
        File both = diff([rebuiltRow("r1", "6000", "111", T), rebuiltRow("r2", "6000", "112", T + 60_000L),
                          omsRow("A", "6000", ["111", "112"], T)])
        assertEquals(2, pair(both).pairedCount)
        assertEquals([], remainingIds(both))

        File one = diff([rebuiltRow("r1", "6000", "111", T), omsRow("A", "6000", ["111", "112"], T)])
        Map result = pair(one)
        assertEquals(1, result.pairedCount)
        assertEquals(["A"], remainingIds(one), "line 112 of HotWax return A has no Shopify event yet")
    }

    @Test
    void aMultiLineEventNeedsEveryLineCovered() {
        File file = diff([rebuiltRow("1", "6000", "111,112", T), omsRow("A", "6000", ["111"], T)])

        assertEquals(0, pair(file).pairedCount, "partial coverage pairs nothing, as in Pass 1.6")
    }

    @Test
    void aCancelledHotWaxReturnDoesNotPair() {
        File file = diff([rebuiltRow("1", "6000", "111", T), omsRow("A", "6000", ["111"], T, "RETURN_CANCELLED")])

        assertEquals(0, pair(file).pairedCount)
    }

    @Test
    void nothingToPairLeavesTheFileUntouched() {
        File file = diff([omsRow("A", "6000", ["111"], T)])
        String before = file.getText("UTF-8")

        Map result = pair(file)

        assertEquals(true, result.performed)
        assertEquals(false, result.rewritten)
        assertEquals(before, file.getText("UTF-8"))
    }

    @Test
    void theAuditNoteIsClassifiedAsANoteNotAWarning() {
        // A pass's own "show your work" sentence must not turn an all-clear run into WITH ISSUES.
        File file = diff([rebuiltRow("1", "6000", "111", T), omsRow("A", "6000", ["111"], T)])
        String note = (String) pair(file).auditNote

        Map partitioned = darpan.reconciliation.notification.TenantNotificationSupport.partitionAuditNotes([note, "real warning"])

        assertTrue(((List) partitioned.auditNotes).contains(note), "${partitioned}")
        assertFalse(((List) partitioned.warnings).contains(note), "${partitioned}")
    }
}
