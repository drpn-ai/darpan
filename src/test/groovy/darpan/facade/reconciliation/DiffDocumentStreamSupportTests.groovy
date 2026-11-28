package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-044. The streamed rewrite the conclude pass uses: rows are transformed one at a time and the
 * summary is rewritten AFTER every row has been seen, because it precedes the rows in the file.
 */
class DiffDocumentStreamSupportTests {

    @TempDir
    File tempDir

    private static Map row(String id) {
        return [diffType: "missing_in_NS", primaryId: id, presentIn: "OMS", missingIn: "NS",
                data: JsonOutput.toJson([orderId: id])]
    }

    /** Mirrors ReconciliationServices.writeDiffDatasetOutput's writer exactly. */
    private File writeDiffDocument(List<Map> rows) {
        return writeDiffDocument(rows, [file1Label: "OMS", file2Label: "NS"], [totalDifferences: rows.size()])
    }

    private File writeDiffDocument(List<Map> rows, Map metadata, Map summary) {
        File file = new File(tempDir, "ruleset-diff-${System.nanoTime()}.json")
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson(metadata) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson(summary) + ",\n"
            writer << "\"validationErrors\":" + JsonOutput.toJson([]) + ",\n"
            writer << "\"processingWarnings\":" + JsonOutput.toJson(["compare warning"]) + ",\n"
            writer << "\"differences\":["
            boolean first = true
            rows.each { Map r ->
                if (!first) writer << ","
                writer << "\n" << JsonOutput.toJson(r)
                first = false
            }
            writer << "]\n}"
        }
        return file
    }

    private static Map parse(File f) {
        return (Map) new JsonSlurper().parseText(f.getText("UTF-8"))
    }

    @Test
    void rewriteKeepsHeaderOrderAndReplacesSummaryAfterRows() {
        File f = writeDiffDocument([row("1"), row("2")])
        int seen = 0
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp",
                { Map r -> seen++; return r + [tag: "x"] },
                { Map s -> return s + [conclusions: [enabled: true, seenRows: seen]] })
        Map doc = parse(f)
        assertEquals(["metadata", "summary", "validationErrors", "processingWarnings", "differences"], doc.keySet() as List)
        assertEquals(2, doc.summary.conclusions.seenRows, "summary must be written after every row was seen")
        assertEquals(["x", "x"], doc.differences*.tag)
        assertEquals(["compare warning"], doc.processingWarnings)
    }

    @Test
    void droppingARowRemovesItAndLeavesNoTempFiles() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp",
                { Map r -> r.primaryId == "2" ? null : r }, { Map s -> s })
        assertEquals(["1", "3"], parse(f).differences*.primaryId)
        assertEquals([f.name] as Set, tempDir.list() as Set)
    }

    // OC71 (S03a wires it): the rewrite can change the document's top-level processingWarnings once every row is seen, so a
    // warning the conclude pass withdraws from the run's counts leaves the document too. Without the closure the line is
    // written back byte for byte.
    @Test
    void rewriteCanReplaceTheProcessingWarningsAfterEveryRow() {
        File f = writeDiffDocument([row("1"), row("2")])
        int seen = 0
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> seen++; return r }, { Map s -> s },
                { List warnings -> warnings.findAll { it != "compare warning" } + ["rows seen: ${seen}".toString()] })
        assertEquals(["rows seen: 2"], parse(f).processingWarnings)
    }

    @Test
    void withoutAWarningsClosureTheWarningsLineIsKept() {
        File f = writeDiffDocument([row("1")])
        String warningsLine = f.readLines("UTF-8").find { it.startsWith("\"processingWarnings\":") }
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r }, { Map s -> s })
        assertEquals(warningsLine, f.readLines("UTF-8").find { it.startsWith("\"processingWarnings\":") })
    }

    @Test
    void emptyDifferencesSurviveTheRewrite() {
        File f = writeDiffDocument([])
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r }, { Map s -> s + [touched: true] })
        Map doc = parse(f)
        assertEquals([], doc.differences)
        assertTrue(doc.summary.touched as boolean)
    }

    @Test
    void aSingleRowDocumentKeepsItsRow() {
        File f = writeDiffDocument([row("only")])
        DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r + [seen: true] }, { Map s -> s })
        Map doc = parse(f)
        assertEquals(["only"], doc.differences*.primaryId)
        assertTrue(doc.differences[0].seen as boolean)
    }

    @Test
    void forEachRowVisitsEveryRowWithoutTouchingTheFile() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        String before = f.text
        List<String> seen = []
        DiffDocumentStreamSupport.forEachRow(f) { Map r -> seen << (String) r.primaryId }
        assertEquals(["1", "2", "3"], seen)
        assertEquals(before, f.text)
    }

    @Test
    void forEachRowOnAnEmptyDocumentVisitsNothing() {
        List seen = []
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(writeDiffDocument([])) { Map r -> seen << r }
        assertEquals([], seen)
        // An empty differences section is a real, complete answer: nothing differs.
        assertTrue(scan.complete, scan.toString())
        assertEquals(0, scan.rows)
    }

    @Test
    void aWholeDocumentScansComplete() {
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(writeDiffDocument([row("1"), row("2")])) { Map r -> }
        assertEquals(2, scan.rows)
        assertEquals(0, scan.unparsed)
        assertTrue(scan.headerFound)
        assertTrue(scan.closed)
        assertTrue(scan.complete)
        assertEquals(null, scan.problem())
    }

    // OC47 (S11): a file cut off before its "differences" header used to yield zero rows silently, so an audit read
    // "0 findings" and closed findings that were still true.
    @Test
    void aDocumentCutBeforeItsDifferencesHeaderIsNotComplete() {
        File f = writeDiffDocument([row("1"), row("2")])
        f.text = f.getText("UTF-8").substring(0, f.getText("UTF-8").indexOf("\"differences\""))
        List seen = []
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(f) { Map r -> seen << r }
        assertEquals([], seen)
        assertFalse(scan.headerFound)
        assertFalse(scan.complete)
        assertTrue(scan.problem().contains("no differences section"), scan.problem())
    }

    @Test
    void aDocumentCutPartWayThroughItsRowsIsNotComplete() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        String text = f.getText("UTF-8")
        // Keep rows 1 and 2 whole; the file ends mid-way through row 3.
        int third = text.indexOf("\"primaryId\":\"3\"")
        f.text = text.substring(0, third)
        List<String> seen = []
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(f) { Map r -> seen << (String) r.primaryId }
        assertEquals(["1", "2"], seen)
        assertTrue(scan.headerFound)
        assertFalse(scan.closed)
        assertFalse(scan.complete)
        assertTrue(scan.problem().contains("ends before"), scan.problem())
    }

    @Test
    void aRowThatDoesNotParseIsCountedNotDropped() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        f.text = f.getText("UTF-8").replace(JsonOutput.toJson(row("2")), "{\"primaryId\":\"2\",broken")
        List<String> seen = []
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachRow(f) { Map r -> seen << (String) r.primaryId }
        assertEquals(["1", "3"], seen)
        assertEquals(2, scan.rows)
        assertEquals(1, scan.unparsed)
        assertTrue(scan.closed)
        assertFalse(scan.complete)
        assertTrue(scan.problem().contains("1 row"), scan.problem())
    }
    // OC56: a rewrite must never turn a cut-off result file into a valid, smaller one. It refuses, and the file is left
    // exactly as it was, with no temp files behind.
    private File cutBeforeDifferences() {
        File f = writeDiffDocument([row("1"), row("2")])
        String text = f.getText("UTF-8")
        f.text = text.substring(0, text.indexOf("\"differences\""))
        return f
    }

    private File cutMidRows() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        String text = f.getText("UTF-8")
        f.text = text.substring(0, text.indexOf("\"primaryId\":\"3\""))
        return f
    }

    private void assertRefusedUntouched(File f, String expectedWords, Closure rewrite) {
        String before = f.getText("UTF-8")
        IllegalStateException refused = assertThrows(IllegalStateException) { rewrite.call() }
        assertTrue(refused.message.contains(expectedWords), refused.message)
        assertEquals(before, f.getText("UTF-8"), "the original must be left untouched")
        assertEquals([f.name] as Set, tempDir.list() as Set, "no temp files left behind")
    }

    @Test
    void rewriteDocumentRefusesAFileWithNoDifferencesSection() {
        File f = cutBeforeDifferences()
        assertRefusedUntouched(f, "no differences section") {
            DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r }, { Map s -> s })
        }
    }

    @Test
    void rewriteDocumentRefusesAFileCutMidRows() {
        File f = cutMidRows()
        assertRefusedUntouched(f, "ends before") {
            DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r }, { Map s -> s })
        }
    }

    @Test
    void rewriteDocumentRefusesARowThatDoesNotParse() {
        File f = writeDiffDocument([row("1"), row("2"), row("3")])
        f.text = f.getText("UTF-8").replace(JsonOutput.toJson(row("2")), "{\"primaryId\":\"2\",broken")
        assertRefusedUntouched(f, "could not be read") {
            DiffDocumentStreamSupport.rewriteDocument(f, "conclude-tmp", { Map r -> r }, { Map s -> s })
        }
    }

    // S20 HANDOFF R1: the verify passes attribute a missing row to a side by its missingIn LABEL, which cannot tell two
    // sides of one system apart. A row's side comes from its diffType first (MISSING_IN_FILE_n), the label only when the
    // two labels differ, and is never guessed.
    private static Map missing(String id, String diffType, String missingIn) {
        return [diffType: diffType, primaryId: id, missingIn: missingIn, presentIn: "other", data: "{}"]
    }

    private Map<String, String> sidesOf(File f) {
        Map<String, String> sides = [:]
        DiffDocumentStreamSupport.DiffRowScan scan = DiffDocumentStreamSupport.forEachMissingRowBySide(f, new JsonSlurper()) { Map r, String side ->
            sides.put("${r.primaryId}/${r.diffType}".toString(), side)
        }
        assertTrue(scan.complete, scan.toString())
        return sides
    }

    @Test
    void aMissingRowsSideComesFromItsDiffTypeEvenWhenBothSidesShareALabel() {
        File f = writeDiffDocument([missing("1", "MISSING_IN_FILE_1", "NetSuite"), missing("2", "MISSING_IN_FILE_2", "NetSuite")],
                [file1Label: "NetSuite", file2Label: "NetSuite"], [totalDifferences: 2])
        assertEquals(["1/MISSING_IN_FILE_1": "FILE_1", "2/MISSING_IN_FILE_2": "FILE_2"], sidesOf(f))
    }

    @Test
    void aMissingRowsSideFallsBackToItsLabelWhenTheLabelsDiffer() {
        File f = writeDiffDocument([missing("1", "missing_in_NS", "NS"), missing("2", "missing_in_OMS", "OMS")],
                [file1Label: "OMS", file2Label: "NS"], [totalDifferences: 2])
        assertEquals(["1/missing_in_NS": "FILE_2", "2/missing_in_OMS": "FILE_1"], sidesOf(f))
    }

    @Test
    void aMissingRowsSideIsLeftNullWhenNothingCanTellTheSidesApart() {
        File f = writeDiffDocument([missing("1", "missing_in_netsuite", "NetSuite")],
                [file1Label: "NetSuite", file2Label: "NetSuite"], [totalDifferences: 1])
        assertEquals(["1/missing_in_netsuite": null], sidesOf(f))
    }

    @Test
    void removeMissingRowsBySideRemovesOnlyThatSidesRowsAndReturnsWhatItDropped() {
        // Both sides share a label; id 1 is missing from each side, and id 2 twice from side 2 (a repeated id).
        File f = writeDiffDocument([missing("1", "MISSING_IN_FILE_1", "NetSuite"), missing("1", "MISSING_IN_FILE_2", "NetSuite"),
                                    missing("2", "MISSING_IN_FILE_2", "NetSuite"), missing("2", "MISSING_IN_FILE_2", "NetSuite")],
                [file1Label: "NetSuite", file2Label: "NetSuite"],
                [totalDifferences: 4, onlyInFile1Count: 3, onlyInFile2Count: 1, missingObjectDifferenceCount: 4])
        Map<String, Integer> dropped = DiffDocumentStreamSupport.removeMissingRowsBySide(f, ".verify-tmp",
                [FILE_2: ["1", "2"] as Set], "3 rows were found on a second look")
        assertEquals([FILE_1: 0, FILE_2: 3], dropped)
        Map doc = parse(f)
        assertEquals(["1/MISSING_IN_FILE_1"], doc.differences.collect { "${it.primaryId}/${it.diffType}".toString() })
        // Missing from side 2 = only in side 1, so onlyInFile1Count drops by the side-2 rows removed: the real number, 3.
        assertEquals([totalDifferences: 1L, onlyInFile1Count: 0L, onlyInFile2Count: 1L, missingObjectDifferenceCount: 1L],
                ((Map) doc.summary).collectEntries { k, v -> [(k): (v as long)] })
        assertEquals(["compare warning", "3 rows were found on a second look"], doc.processingWarnings)
        assertEquals([f.name] as Set, tempDir.list() as Set)
    }

    @Test
    void removeMissingRowsBySideRefusesAFileWithNoDifferencesSection() {
        File f = cutBeforeDifferences()
        assertRefusedUntouched(f, "no differences section") {
            DiffDocumentStreamSupport.removeMissingRowsBySide(f, ".verify-tmp", [FILE_2: ["1"] as Set], null)
        }
    }

    @Test
    void removeMissingRowsBySideRefusesACutOffFile() {
        File f = cutMidRows()
        assertRefusedUntouched(f, "ends before") {
            DiffDocumentStreamSupport.removeMissingRowsBySide(f, ".verify-tmp", [FILE_2: ["1"] as Set], null)
        }
    }
    /**
     * QA refute (S21a, R1 regression): a repeated key's rows (DUPLICATE_IN_FILE_n and its _PAIRED partner) and a single-sided
     * finding are written with missingIn "" (CompareDatasetSupport.buildDuplicateRows). Nothing is missing, so they get no
     * side and no verify pass may remove them, even when their id is listed and their presentIn names a side.
     */
    @Test
    void aRowThatNamesNoSideItIsMissingFromIsNeverAMissingRow() {
        List<Map> rows = [
                [type: "DUPLICATE_IN_FILE_1", id: "K1", presentIn: "OMS", missingIn: "", data: "{}", note: "Duplicated in OMS"],
                [diffType: "DUPLICATE_IN_FILE_2_PAIRED", primaryId: "K1", presentIn: "NS", missingIn: "", data: "{}"],
                [diffType: "FINDING", primaryId: "F1", presentIn: "OMS", missingIn: "", data: "{}"],
        ]
        rows.each { Map row -> assertEquals(null, DiffDocumentStreamSupport.missingSide(row, "OMS", "NS"), row.toString()) }

        File f = writeDiffDocument(rows, [file1Label: "OMS", file2Label: "NS"], [totalDifferences: 3, duplicateDifferenceCount: 2])
        Map<String, Integer> dropped = DiffDocumentStreamSupport.removeMissingRowsBySide(f, ".verify-tmp",
                [FILE_1: ["K1", "F1"] as Set, FILE_2: ["K1", "F1"] as Set], null)

        assertEquals([FILE_1: 0, FILE_2: 0], dropped)
        assertEquals(3, ((List) parse(f).differences).size(), "nothing a verify pass may remove was removed")
        assertEquals(3, ((Map) parse(f).summary).totalDifferences)
    }
}
