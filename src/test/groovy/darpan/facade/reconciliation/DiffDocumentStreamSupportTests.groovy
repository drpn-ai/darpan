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
        File file = new File(tempDir, "ruleset-diff-${System.nanoTime()}.json")
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson([file1Label: "OMS", file2Label: "NS"]) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson([totalDifferences: rows.size()]) + ",\n"
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

    @Test
    void removeMissingRowsRefusesAFileWithNoDifferencesSection() {
        File f = cutBeforeDifferences()
        assertRefusedUntouched(f, "no differences section") {
            DiffDocumentStreamSupport.removeMissingRows(f, ".verify-tmp", ["ns": ["1"] as Set], 1, 0, 1, null)
        }
    }

    @Test
    void removeMissingRowsRefusesAFileCutMidRows() {
        File f = cutMidRows()
        assertRefusedUntouched(f, "ends before") {
            DiffDocumentStreamSupport.removeMissingRows(f, ".verify-tmp", ["ns": ["1"] as Set], 1, 0, 1, null)
        }
    }

    @Test
    void removeMissingRowsStillRemovesFromAWholeFile() {
        File f = writeDiffDocument([row("1"), row("2")])
        DiffDocumentStreamSupport.removeMissingRows(f, ".verify-tmp", ["ns": ["1"] as Set], 1, 0, 1, null)
        assertEquals(["2"], parse(f).differences*.primaryId)
        assertEquals([f.name] as Set, tempDir.list() as Set)
    }
}
