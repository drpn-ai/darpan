package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
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
        DiffDocumentStreamSupport.forEachRow(writeDiffDocument([])) { Map r -> seen << r }
        assertEquals([], seen)
    }
}
