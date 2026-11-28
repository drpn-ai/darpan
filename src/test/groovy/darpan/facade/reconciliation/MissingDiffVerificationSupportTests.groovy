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
    /** Enough records checked on each side that every fixture here is far under the 5% share gate. */
    /** R1: the lookups, counts and gates are keyed by the compare's own side, never by a label two sides can share. */
    private static final String SIDE1 = "FILE_1"
    private static final String SIDE2 = "FILE_2"
    private static final Map PLENTY = [(SIDE1): 1_000_000, (SIDE2): 1_000_000]

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
    private File writeDiffDocument(List<Map> rows, Map summaryOverride = null, Map labels = [file1Label: FILE1, file2Label: FILE2]) {
        File file = new File(tempDir, "ruleset-diff-${System.nanoTime()}.json")
        Map metadata = labels + [reconciliation: "RULESET"]
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids ->
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids ->
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
    void anAuditSideAboveTheShareGateIsNotLookedUp() {
        // DAR-BE-320 (Aditi): a lookup is a last resort, for the 1-5% of records that need a follow-up.
        // DAR-BE-351 (Aditi: "The 5% rule is mainly for audits. Since they do large extracts"): audit windows only.
        // 3 of 10 checked records missing is 30%: the read or the pairing is wrong, so nothing is looked up.
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
        ])
        String before = file.getText("UTF-8")
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                auditRun         : true,
                sideCheckedCounts: [(SIDE2): 10],
                sideLookups      : [(SIDE2): { List<String> ids -> dispatchCalls++; return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertFalse((Boolean) result.rewritten)
        assertEquals(0, dispatchCalls)
        assertEquals(before, file.getText("UTF-8"))
        assertTrue(((List) result.warnings).any { it.toString().contains("3 of 10") && it.toString().contains("5%") },
                "the warning names the share and the gate: ${result.warnings}")
        assertEquals([candidates: 3, checkedAgainst: 10L, maxSharePercent: 5L, lookedUp: 0, found: 0, skipped: "OVER_SHARE"],
                ((Map) result.sideOutcomes).get(SIDE2), "the audit's read report sees why nothing was looked up")
    }

    @Test
    void anyOtherRunLooksUpEveryMissingRowEvenAboveTheShareGate() {
        // DAR-BE-351 (Aditi: "It is important that doubts be cleared if the initial extract can't confirm. Follow-up is
        // needed"). A daily run's 3 of 10 (30%) are all looked up; the share is said as a note, so a run whose lookups
        // clear every row stays clean.
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
        ])
        List<String> looked = []
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                sideCheckedCounts: [(SIDE2): 10],
                sideLookups      : [(SIDE2): { List<String> ids -> looked.addAll(ids); [ok: true, foundIds: ids - ["1003"], missingIds: ["1003"], errors: []] }],
        ])

        assertEquals(["1001", "1002", "1003"], looked)
        assertEquals(2, result.removedCount)
        assertEquals(1, result.confirmedMissingCount)
        assertEquals([], result.warnings, "followed up, so nothing to warn about")
        assertTrue((result.auditNote as String).contains("3 of 10") && (result.auditNote as String).contains("5%"),
                "the read's coverage is still said: ${result.auditNote}")
        assertEquals(["1003"], ((List) parseDocument(file).differences).collect { it.primaryId })
    }

    @Test
    void aSideAtExactlyTheShareGateIsLookedUp() {
        File file = writeDiffDocument((1..5).collect { missingRow("10${it}".toString(), FILE1, FILE2) })
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                sideCheckedCounts: [(SIDE2): 100],
                sideLookups      : [(SIDE2): { List<String> ids -> dispatchCalls++; return [ok: true, foundIds: [], missingIds: ids, errors: []] }],
        ])

        assertTrue((Boolean) result.performed)
        assertEquals(1, dispatchCalls)
        assertEquals(5, result.confirmedMissingCount)
    }

    @Test
    void aSideWithNoCheckedCountIsNotLookedUp() {
        // Fail closed: without the number of records checked there is no share, so no lookup.
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2)])
        int dispatchCalls = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile   : file,
                file1Label : FILE1,
                file2Label : FILE2,
                sideLookups: [(SIDE2): { List<String> ids -> dispatchCalls++; [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertEquals(0, dispatchCalls)
        assertTrue(((List) result.warnings).any { it.toString().contains("how many records") }, "${result.warnings}")
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
                sideCheckedCounts: PLENTY,
                // only FILE2 is lookup-capable; the sole missing row is missing in FILE1
                sideLookups: [(SIDE2): { List<String> ids -> dispatchCalls++; return [ok: true, foundIds: [], missingIds: ids, errors: []] }],
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids -> return [ok: true, foundIds: [], missingIds: ids, errors: []] }],
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
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
                sideCheckedCounts: PLENTY,
                sideLookups: [
                        (SIDE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] },
                        (SIDE1): { List<String> ids -> return [ok: true, foundIds: ["2001"], missingIds: ["2002"], errors: []] },
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids -> return [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        // generic rows carry the id in "id", not "primaryId"
        assertEquals(1, result.removedCount)
        Map document = parseDocument(file)
        assertEquals([], document.differences)
        assertEquals(0, ((Map) document.summary).totalDifferences)
    }

    @Test
    void eachSideIsGatedByItsOwnShare() {
        // The two sides are different systems: a side over its own gate contributes nothing and must
        // never disable a sibling that is under its own. A side may carry its own gate (sideMaxShares).
        File file = writeDiffDocument([
                missingRow("1001", FILE1, FILE2),
                missingRow("1002", FILE1, FILE2),
                missingRow("1003", FILE1, FILE2),
                missingRow("2001", FILE2, FILE1),
        ])
        List<String> file1Checked = []
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                sideCheckedCounts: [(SIDE2): 40, (SIDE1): 100],
                sideMaxShares    : [(SIDE1): 2],
                auditRun         : true,
                sideLookups      : [
                        (SIDE2): { List<String> ids -> [ok: true, foundIds: ids, missingIds: [], errors: []] },
                        (SIDE1): { List<String> ids -> file1Checked.addAll(ids); [ok: true, foundIds: ids, missingIds: [], errors: []] },
                ],
        ])

        // FILE2: 3 of 40 = 7.5%, over the default 5%, contributes nothing...
        assertTrue(((List) result.warnings).any { it.toString().contains("3 of 40") })
        // ...FILE1: 1 of 100 = 1%, under its own 2%, is still verified and removed.
        assertEquals(["2001"], file1Checked)
        assertEquals(1, result.removedCount)
        assertTrue((Boolean) result.rewritten)
        assertEquals([candidates: 1, checkedAgainst: 100L, maxSharePercent: 2L, lookedUp: 1, found: 1],
                ((Map) result.sideOutcomes).get(SIDE1))
        assertEquals("OVER_SHARE", ((Map) ((Map) result.sideOutcomes).get(SIDE2)).skipped)
        assertEquals(["1001", "1002", "1003"] as Set,
                (((List) parseDocument(file).differences).collect { it.primaryId } as Set))
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
                sideCheckedCounts: PLENTY,
                sideLookups: [(SIDE2): { List<String> ids ->
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

    /**
     * DAR-OPS-029 S20 (QA): two sides whose labels read as one token (a same-system run: "Shopify" vs "Shopify") leave a
     * row's missingIn unable to say which side it is missing from, and the label-keyed lookups collapse to one. The
     * pass used to send rows missing in one side to the other side's lookup, where they exist by definition, and
     * remove them: real differences hidden. It now refuses and says why.
     */
    /**
     * R1 (OC96): two sides of one system share a label, but each row's diffType names its side, so each side is verified
     * against its own lookup. Before, the rows were attributed by label, the run was skipped (OC63), and its missing rows
     * went unverified. A row whose side nothing can settle is never looked up, never removed.
     */
    @Test
    void sidesThatShareALabelAreEachVerifiedAgainstTheirOwnLookup() {
        Map inSide1 = missingRow("1001", "Shopify", "Shopify") + [diffType: "MISSING_IN_FILE_1"]
        Map inSide2 = missingRow("2001", "Shopify", "Shopify") + [diffType: "MISSING_IN_FILE_2"]
        Map unsided = missingRow("3001", "Shopify", "Shopify")
        File file = writeDiffDocument([inSide1, inSide2, unsided], [
                totalDifferences            : 3,
                onlyInFile1Count            : 1,
                onlyInFile2Count            : 1,
                missingObjectDifferenceCount: 3,
                ruleDifferenceCount         : 0,
        ], [file1Label: "Shopify", file2Label: "shopify"])
        List<String> side1Asked = [], side2Asked = []
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : "Shopify",
                file2Label       : "shopify",
                sideCheckedCounts: PLENTY,
                sideLookups      : [
                        (SIDE1): { List<String> ids -> side1Asked.addAll(ids); [ok: true, foundIds: [], missingIds: ids, errors: []] },
                        (SIDE2): { List<String> ids -> side2Asked.addAll(ids); [ok: true, foundIds: ids, missingIds: [], errors: []] },
                ],
        ])

        assertEquals(["1001"], side1Asked, "side 1 is asked only about the row missing from side 1")
        assertEquals(["2001"], side2Asked, "side 2 is asked only about the row missing from side 2")
        assertTrue((Boolean) result.performed)
        assertEquals(1, result.removedCount)
        assertEquals(0, result.removedMissingInFile1)
        assertEquals(1, result.removedMissingInFile2)
        assertEquals(["1001", "3001"], ((List) parseDocument(file).differences).collect { it.primaryId },
                "the unsided row stays, never looked up")
    }

    /** OC96: a missing row that does not parse cannot be checked; the pass says so and leaves the document as it was. */
    @Test
    void aMissingRowThatDoesNotParseSkipsThePassAndLeavesTheDocument() {
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2), missingRow("1002", FILE1, FILE2)])
        file.text = file.getText("UTF-8").replace(JsonOutput.toJson(missingRow("1002", FILE1, FILE2)),
                "{\"missingIn\":\"SHOPIFY\",\"primaryId\":\"1002\",broken")
        String before = file.text
        int asked = 0
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                sideCheckedCounts: PLENTY,
                sideLookups      : [(SIDE2): { List<String> ids -> asked++; [ok: true, foundIds: ids, missingIds: [], errors: []] }],
        ])

        assertFalse((Boolean) result.performed)
        assertEquals(0, asked)
        assertEquals(before, file.text)
        assertTrue(((List) result.warnings).any { it.toString().contains("could not be read") }, "warnings: ${result.warnings}")
    }
    /**
     * QA refute (R1 regression): a repeated key's rows carry missingIn "" and are never missing rows. A run with real missing
     * rows and duplicate rows under an id the lookup finds removes only the missing row, and its counts match the pass before
     * R1 exactly: one row less, nothing taken from the duplicate count.
     */
    @Test
    void duplicateRowsUnderAFoundIdAreLeftAndTheCountsMatchThePassBeforeR1() {
        Map dupInOms = [type: "DUPLICATE_IN_FILE_1", id: "1001", presentIn: FILE1, missingIn: "", data: "{}", note: "Duplicated in OMS"]
        Map pairedInShopify = [diffType: "DUPLICATE_IN_FILE_1_PAIRED", primaryId: "1001", presentIn: FILE2, missingIn: "", data: "{}"]
        File file = writeDiffDocument([missingRow("1001", FILE1, FILE2), missingRow("1002", FILE1, FILE2), dupInOms, dupInOms, pairedInShopify], [
                totalDifferences            : 5,
                onlyInFile1Count            : 2,
                onlyInFile2Count            : 0,
                missingObjectDifferenceCount: 2,
                duplicateDifferenceCount    : 3,
        ])
        Map result = MissingDiffVerificationSupport.verifyMissingDiffs([
                diffFile         : file,
                file1Label       : FILE1,
                file2Label       : FILE2,
                sideCheckedCounts: PLENTY,
                sideLookups      : [(SIDE1): { List<String> ids -> [ok: true, foundIds: ids, missingIds: [], errors: []] },
                                    (SIDE2): { List<String> ids -> [ok: true, foundIds: ["1001"], missingIds: ids - ["1001"], errors: []] }],
        ])

        assertEquals(1, result.removedCount)
        assertEquals(0, result.removedMissingInFile1)
        assertEquals(1, result.removedMissingInFile2)
        Map document = parseDocument(file)
        assertEquals(["1002", "1001", "1001", "1001"], ((List) document.differences).collect { it.primaryId ?: it.id })
        Map summary = (Map) document.summary
        assertEquals(4, summary.totalDifferences)
        assertEquals(1, summary.onlyInFile1Count)
        assertEquals(1, summary.missingObjectDifferenceCount)
        assertEquals(3, summary.duplicateDifferenceCount)
    }
}
