package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Verification pass over a ruleset diff document: rows reported missing in a lookup-capable side are
 * rechecked against that side's primary datastore; rows the lookup confirms present (bulk-export
 * index-skew false positives) are removed with the summary counts adjusted and an audit note appended.
 *
 * The fixtures reproduce the {@code ReconciliationServices.writeDiffDatasetOutput} line-oriented
 * format byte-for-byte — the support streams that format instead of parsing the whole document
 * (diff files reach GB scale; see the OutputDescriptorSupport OOM fix).
 */
class MissingDiffVerificationSupportTests {

    @TempDir
    File tempDir

    private static final String FILE1 = "OMS"
    private static final String FILE2 = "SHOPIFY"

    private static Map missingRow(String id, String presentIn, String missingIn) {
        return [
                diffType      : "missing_in_${missingIn}".toString(),
                compareScopeId: "SCOPE_1",
                objectType    : "ORDERS",
                primaryId     : id,
                presentIn     : presentIn,
                missingIn     : missingIn,
                data          : JsonOutput.toJson([orderId: id, orderName: "#GOR${id}".toString()]),
                message       : "Present in ${presentIn}, missing in ${missingIn}".toString(),
        ]
    }

    private static Map ruleRow(String id) {
        return [
                diffType      : "rule_diff",
                compareScopeId: "SCOPE_1",
                objectType    : "ORDERS",
                primaryId     : id,
                field         : "grandTotal",
                file1Value    : "10.00",
                file2Value    : "12.00",
                ruleId        : "rule_1",
                message       : "grandTotal mismatch",
        ]
    }

    /** Mirrors ReconciliationServices.writeDiffDatasetOutput's writer exactly. */
    private File writeDiffDocument(List<Map> rows, Map summaryOverride = null) {
        File file = new File(tempDir, "ruleset-diff-${System.nanoTime()}.json")
        Map metadata = [file1Label: FILE1, file2Label: FILE2, reconciliation: "RULESET"]
        int missing1 = rows.count { it.missingIn == FILE1 } as int
        int missing2 = rows.count { it.missingIn == FILE2 } as int
        Map summary = summaryOverride ?: [
                totalDifferences: rows.size(),
                onlyInFile1Count: missing2,
                onlyInFile2Count: missing1,
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

    @Test
    void removesVerifiedPresentRowsAndAdjustsSummary() {
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("2001", FILE2, FILE1),
                ruleRow("3001"),
        ], [
                totalDifferences            : 4,
                onlyInFile1Count            : 2,
                onlyInFile2Count            : 1,
                missingObjectDifferenceCount: 3,
                ruleDifferenceCount         : 1,
        ])
        List<List<String>> dispatched = []
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids ->
                    dispatched.add(ids)
                    // 1001 exists in Shopify (bulk export skew); 1002 is truly missing
                    return [ok: true, foundIds: ["1001"], missingIds: ["1002"], errors: []]
                }],
        ])

        assertTrue((Boolean) result.performed)
        assertTrue((Boolean) result.rewritten)
        assertEquals([["1001", "1002"]], dispatched)
        assertEquals(2, result.checkedCount)
        assertEquals(1, result.removedCount)
        assertEquals(1, result.confirmedMissingCount)
        assertEquals(0, result.removedMissingInFile1)
        assertEquals(1, result.removedMissingInFile2)
        assertNotNull(result.auditNote)

        Map document = parseDocument(file)
        List differences = (List) document.differences
        assertEquals(["1002", "2001", "3001"], differences.collect { it.primaryId })
        assertEquals(3, ((Map) document.summary).totalDifferences)
        assertEquals(1, ((Map) document.summary).onlyInFile1Count)
        assertEquals(1, ((Map) document.summary).onlyInFile2Count)
        assertEquals(2, ((Map) document.summary).missingObjectDifferenceCount)
        assertEquals(1, ((Map) document.summary).ruleDifferenceCount)
        // compare-time warnings preserved, audit note appended to the artifact itself
        List processingWarnings = (List) document.processingWarnings
        assertEquals("compare warning", processingWarnings[0])
        assertTrue(processingWarnings[1].toString().contains("Verification pass"))
        // untouched header sections stay byte-identical in meaning
        assertEquals(FILE1, ((Map) document.metadata).file1Label)
        assertEquals([], document.validationErrors)
    }

    @Test
    void lookupFailureLeavesFileUntouchedAndWarns() {
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2)])
        String before = file.getText("UTF-8")
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids ->
                    return [ok: false, foundIds: [], missingIds: [], errors: ["boom"]]
                }],
        ])

        assertTrue((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(0, result.removedCount)
        assertEquals(before, file.getText("UTF-8"))
        assertTrue(((List) result.warnings).any { it.toString().contains("failed") })
    }

    @Test
    void sideOverCapIsSkippedWithWarning() {
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
        ])
        String before = file.getText("UTF-8")
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile    : file,
                file1Label  : FILE1,
                file2Label  : FILE2,
                maxLookupIds: 2,
                sideLookups : [(FILE2): { List<String> ids -> dispatchCalls++; return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(0, dispatchCalls)
        assertEquals(before, file.getText("UTF-8"))
        assertTrue(((List) result.warnings).any { it.toString().contains("cap") })
    }

    @Test
    void noVerifiableMissingRowsShortCircuits() {
        File file = writeDiffDocument([ruleRow("3001"), missingRow("2001", FILE2, FILE1)])
        String before = file.getText("UTF-8")
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                // only FILE2 is lookup-capable; the sole missing row is missing in FILE1
                sideLookups: [(FILE2): { List<String> ids -> dispatchCalls++; return [ok: true, foundIds: [], missingIds: ids, errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(0, dispatchCalls)
        assertEquals(before, file.getText("UTF-8"))
        assertNull(result.auditNote)
    }

    @Test
    void allConfirmedMissingLeavesFileUntouched() {
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2), missingRow("1002", FILE1, FILE2)])
        String before = file.getText("UTF-8")
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids -> return [ok: true, foundIds: [], missingIds: ids, errors: []] }],
        ])

        assertTrue((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(2, result.confirmedMissingCount)
        assertEquals(0, result.removedCount)
        assertEquals(before, file.getText("UTF-8"))
    }

    @Test
    void removingEveryRowLeavesValidEmptyDifferencesDocument() {
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2), missingRow("1002", FILE1, FILE2)])
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertEquals(2, result.removedCount)
        Map document = parseDocument(file)
        assertEquals([], document.differences)
        assertEquals(0, ((Map) document.summary).totalDifferences)
        assertEquals(0, ((Map) document.summary).onlyInFile1Count)
    }

    @Test
    void removingOnlyTheLastRowKeepsDocumentWellFormed() {
        File file = writeDiffDocument([ruleRow("3001"), missingRow("1002", FILE1, FILE2)])
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertEquals(1, result.removedCount)
        Map document = parseDocument(file)
        assertEquals(["3001"], ((List) document.differences).collect { it.primaryId })
    }

    @Test
    void bothSidesVerifiableAreProcessedIndependently() {
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("2001", FILE2, FILE1),
                missingRow("2002", FILE2, FILE1),
        ])
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [
                        (FILE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] },
                        (FILE1): { List<String> ids -> return [ok: true, foundIds: ["2001"], missingIds: ["2002"], errors: []] },
                ],
        ])

        assertEquals(3, result.checkedCount)
        assertEquals(2, result.removedCount)
        assertEquals(1, result.removedMissingInFile1)
        assertEquals(1, result.removedMissingInFile2)
        Map document = parseDocument(file)
        assertEquals(["2002"], ((List) document.differences).collect { it.primaryId })
        assertEquals(1, ((Map) document.summary).totalDifferences)
        // 2002 is missing in FILE1, i.e. present only in FILE2
        assertEquals(0, ((Map) document.summary).onlyInFile1Count)
        assertEquals(1, ((Map) document.summary).onlyInFile2Count)
    }

    @Test
    void genericDocumentWithoutProcessingWarningsHeaderIsHandled() {
        // The generic (non-ruleset) writer has no processingWarnings header line; the support must
        // not corrupt such a document if it ever sees one.
        File file = new File(tempDir, "generic-diff.json")
        Map row = [type: "missing_in_SHOPIFY", id: "1001", presentIn: FILE1, missingIn: FILE2,
                   data: JsonOutput.toJson([id: "1001"]), note: "Present in OMS, missing in SHOPIFY"]
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson([file1Label: FILE1, file2Label: FILE2]) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson([totalDifferences: 1, onlyInFile1Count: 1, onlyInFile2Count: 0]) + ",\n"
            writer << "\"validationErrors\":" + JsonOutput.toJson([]) + ",\n"
            writer << "\"differences\":["
            writer << "\n" << JsonOutput.toJson(row)
            writer << "]\n}"
        }
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        // generic rows carry the id in "id", not "primaryId"
        assertEquals(1, result.removedCount)
        Map document = parseDocument(file)
        assertEquals([], document.differences)
        assertEquals(0, ((Map) document.summary).totalDifferences)
    }

    @Test
    void eachSideIsCappedIndependentlyByItsOwnConnectorCap() {
        // The two sides of a run are different systems with different enumeration guarantees. The
        // returns pair's Shopify extract cannot be trusted for completeness at all (updated_at is not
        // reliably bumped), so a large gap there is the STEADY STATE, not evidence of a broken sync —
        // while for the orders pair a gap that large still means something is wrong. One shared cap
        // cannot express both, so an over-cap side must not disable a healthy sibling.
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
                missingRow("2001", FILE2, FILE1),
        ])
        List<String> file1Checked = []
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile        : file,
                file1Label      : FILE1,
                file2Label      : FILE2,
                sideMaxLookupIds: [(FILE2): 2, (FILE1): 10],
                sideLookups     : [
                        (FILE2): { List<String> ids -> [ok: true, foundIds: ids, missingIds: [], errors: []] },
                        (FILE1): { List<String> ids -> file1Checked.addAll(ids); [ok: true, foundIds: ids, missingIds: [], errors: []] },
                ],
        ])

        // FILE2 is over its own cap of 2 and contributes nothing...
        assertTrue(((List) result.warnings).any { it.toString().contains("cap") })
        // ...but FILE1 is under its cap of 10 and is still verified and removed.
        assertEquals(["2001"], file1Checked)
        assertEquals(1, result.removedCount)
        assertTrue((Boolean) result.rewritten)
        assertEquals(["1001", "1002", "1003"] as Set,
                (((List) parseDocument(file).differences).collect { it.primaryId } as Set))
    }

    @Test
    void aSideWithNoOwnCapFallsBackToTheSharedDefault() {
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2), missingRow("1002", FILE1, FILE2)])
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile        : file,
                file1Label      : FILE1,
                file2Label      : FILE2,
                maxLookupIds    : 1,
                sideMaxLookupIds: [:],
                sideLookups     : [(FILE2): { List<String> ids -> dispatchCalls++; [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertEquals(0, dispatchCalls)
        assertTrue(((List) result.warnings).any { it.toString().contains("cap") })
    }

    @Test
    void idsStrandedByAFailedChunkAreNotCountedAsConfirmedMissing() {
        // DAR-BE-036: partial credit means a lookup can now answer for SOME ids. The ones it could
        // not reach are UNKNOWN. Folding them into confirmedMissing would report a blind spot as
        // evidence of absence — the exact failure the pending-return probe caught in August.
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
        ], [
                totalDifferences            : 3,
                onlyInFile1Count            : 3,
                onlyInFile2Count            : 0,
                missingObjectDifferenceCount: 3,
                ruleDifferenceCount         : 0,
        ])
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(FILE2): { List<String> ids ->
                    return [ok           : true,
                            foundIds     : ["1001"],
                            missingIds   : ["1002"],
                            unresolvedIds: ["1003"],
                            errors       : ["Shopify GraphQL request failed with HTTP 429."]]
                }],
        ])

        assertTrue((Boolean) result.performed)
        assertEquals(1, result.removedCount)
        assertEquals(1, result.confirmedMissingCount, "1003 was never answered for and must not count as missing")
        assertEquals(1, result.unresolvedCount)

        List warnings = (List) result.warnings
        assertTrue(warnings.any { it.toString().contains("1 ") && it.toString().contains("could not be checked") },
                "the operator must be told part of the side went unchecked: ${warnings}")

        Map document = parseDocument(file)
        assertEquals(["1002", "1003"], ((List) document.differences).collect { it.primaryId })
    }
}
