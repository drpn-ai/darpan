package darpan.reconciliation.conclusion

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-044. The conclude pass over a real-shaped result document: evidence from both sides' kept
 * records and the sidecar of what their filters dropped, a conclusion per finding, and counts that
 * always add up to the rows written.
 */
class RunConclusionSupportTests {

    @TempDir
    File tempDir

    private static final String FILE1 = "HotWax"
    private static final String FILE2 = "NetSuite"
    private static final String SEP = "\u001F"

    private static Map cond(Map m) {
        return [presence: "ANY", keyScope: "FULL", fieldExpression: null, operator: null, conditionValues: []] + m
    }

    private static final List BACKORDER_RULES = [
            [conclusionEnumId: "CONC_NS_BACKORDERED", label: "Backordered in NetSuite", appliesToBucket: "MISSING_FROM_FILE_2",
             conditions: [cond(subject: "FILE_2", presence: "EXCLUDED", checkLabel: "NetSuite has the line"),
                          cond(subject: "FILE_2", fieldExpression: "quantityBackordered", operator: "GT_ZERO", checkLabel: "On backorder")]],
    ]
    private static final List NEVER_REACHED_RULES = [
            [conclusionEnumId: "CONC_NEVER_REACHED_NS", label: "Never reached NetSuite", appliesToBucket: "MISSING_FROM_FILE_2",
             conditions: [cond(subject: "FILE_2", presence: "ABSENT", keyScope: "FIRST_FIELD", checkLabel: "NetSuite has no line for the order")]],
    ]
    private static final Map HOTWAX_EVIDENCE = [system: "HotWax", state: "statusId",
                                                stateLabels: [ITEM_COMPLETED: "Completed"], facts: ["facilityId", "orderItemSeqId"]]
    private static final Map NETSUITE_EVIDENCE = [system: "NetSuite", state: "status",
                                                  stateLabels: [B: "Pending Fulfillment"], facts: ["orderLineId", "quantityShipped", "quantityBackordered"]]

    private static Map missingRow(String id, String presentIn, String missingIn, Map data = null) {
        return [diffType : "missing_in_${missingIn}".toString(), compareScopeId: "SCOPE_1", objectType: "ORDER_LINE_UNIT",
                primaryId: id, presentIn: presentIn, missingIn: missingIn,
                data     : JsonOutput.toJson(data ?: [omsOrderId: id.split(SEP)[0]]),
                message  : "Present in ${presentIn}, missing in ${missingIn}".toString()]
    }

    /** Mirrors ReconciliationServices.writeDiffDatasetOutput's writer exactly. */
    private File writeDiffDocument(List<Map> rows, Map summary = null) {
        File file = new File(tempDir, "ruleset-diff-${System.nanoTime()}.json")
        file.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson([file1Label: FILE1, file2Label: FILE2]) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson(summary ?: [totalDifferences: rows.size()]) + ",\n"
            writer << "\"validationErrors\":" + JsonOutput.toJson([]) + ",\n"
            writer << "\"processingWarnings\":" + JsonOutput.toJson([]) + ",\n"
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

    private File extract(String name, List records, Map metadata = [:]) {
        File f = new File(tempDir, name)
        f.text = JsonOutput.toJson([records: records, metadata: metadata])
        return f
    }

    private static void sidecar(File extractFile, List records, boolean truncated = false) {
        Map c = ExcludedRecordsSidecar.newCollector()
        records.each { Map r -> ExcludedRecordsSidecar.collect(c, r, [sequenceNum: 1, fieldExpression: "quantityShipped"]) }
        c.truncated = truncated
        ExcludedRecordsSidecar.write(new File(extractFile.parentFile, ExcludedRecordsSidecar.fileNameFor(extractFile.name)), c, extractFile.name)
    }

    private Map args(File doc, List rules, File nsExtract = null, File omsExtract = null) {
        return [diffFile: doc, file1Label: FILE1, file2Label: FILE2, singleSided: false, rules: rules,
                file1   : [extractFile: omsExtract ?: extract("oms-${System.nanoTime()}.json", []), keyFields: ["omsOrderId", "orderItemSeqId"],
                           idNormalizer: null, evidence: HOTWAX_EVIDENCE],
                file2   : [extractFile: nsExtract ?: extract("ns-${System.nanoTime()}.json", []), keyFields: ["orderId", "orderLineId"],
                           idNormalizer: null, evidence: NETSUITE_EVIDENCE]]
    }

    private static Map parse(File f) {
        return (Map) new JsonSlurper().parseText(f.getText("UTF-8"))
    }

    @Test
    void noRulesLeavesTheDocumentByteIdentical() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2)])
        String before = doc.text
        assertFalse(RunConclusionSupport.concludeRun(args(doc, [])).ran as boolean)
        assertEquals(before, doc.text)
    }

    @Test
    void backorderedLineIsConcludedFromTheSidecar() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2,
                [omsOrderId: "M1", orderItemSeqId: "01", statusId: "ITEM_COMPLETED", facilityId: "217"])])
        File ns = extract("ns.json", [], [configuredExclusions: [[sequenceNum: 1]]])
        sidecar(ns, [[orderId: "M1", orderLineId: "01", quantityShipped: "0", quantityBackordered: "1", status: "B"]])

        Map result = RunConclusionSupport.concludeRun(args(doc, BACKORDER_RULES, ns))

        assertTrue(result.ran as boolean)
        Map conclusion = (Map) ((Map) ((List) parse(doc).differences)[0]).conclusion
        assertEquals("CONC_NS_BACKORDERED", conclusion.code)
        Map hotwax = (Map) ((List) conclusion.systems).find { it.side == "FILE_1" }
        Map netsuite = (Map) ((List) conclusion.systems).find { it.side == "FILE_2" }
        assertEquals(["KEPT", "HotWax", "Completed", ["217", "01"]], [hotwax.presence, hotwax.system, hotwax.state, hotwax.facts])
        assertEquals(["EXCLUDED", "Pending Fulfillment", ["01", "0", "1"]], [netsuite.presence, netsuite.state, netsuite.facts])
        assertEquals([[code: "CONC_NS_BACKORDERED", label: "Backordered in NetSuite", count: 1],
                      [code: "UNEXPLAINED", label: "Unexplained", count: 0]], parse(doc).summary.conclusions.counts)
        assertTrue(parse(doc).summary.conclusions.enabled as boolean)
    }

    @Test
    void countsSumToTheRowsWritten() {
        File doc = writeDiffDocument([missingRow("A${SEP}1", FILE1, FILE2), missingRow("B${SEP}1", FILE1, FILE2),
                                      missingRow("C${SEP}1", FILE2, FILE1)])
        RunConclusionSupport.concludeRun(args(doc, BACKORDER_RULES))
        Map s = (Map) parse(doc).summary
        assertEquals(3, ((List) s.conclusions.counts).sum { it.count })
        assertEquals(s.totalDifferences, ((List) s.conclusions.counts).sum { it.count })
    }

    @Test
    void aTruncatedSidecarMakesTheMissingSideUnknownNotAbsent() {
        File doc = writeDiffDocument([missingRow("M9${SEP}01", FILE1, FILE2)])
        File ns = extract("ns.json", [], [configuredExclusions: [[sequenceNum: 1]]])
        sidecar(ns, [], true)
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
        Map conclusion = (Map) ((Map) ((List) parse(doc).differences)[0]).conclusion
        assertEquals("UNEXPLAINED", conclusion.code)
        assertEquals("UNKNOWN", ((List) conclusion.systems).find { it.side == "FILE_2" }.presence)
    }

    @Test
    void aMissingSidecarOnAFilteredSideIsUnknownToo() {
        File doc = writeDiffDocument([missingRow("M9${SEP}01", FILE1, FILE2)])
        File ns = extract("ns.json", [], [filters: [configuredExclusions: [[sequenceNum: 1]]]])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
        assertEquals("UNEXPLAINED", ((Map) ((Map) ((List) parse(doc).differences)[0]).conclusion).code)
    }

    @Test
    void absentOnTheFirstFieldConcludesNeverReached() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2)])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES,
                extract("ns.json", [[orderId: "M6", orderLineId: "01"]])))
        assertEquals("CONC_NEVER_REACHED_NS", ((Map) ((Map) ((List) parse(doc).differences)[0]).conclusion).code)
    }

    @Test
    void anOrderPresentOnAnotherLineIsNotNeverReached() {
        File doc = writeDiffDocument([missingRow("M5${SEP}02", FILE1, FILE2)])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES,
                extract("ns.json", [[orderId: "M5", orderLineId: "01"]])))
        assertEquals("UNEXPLAINED", ((Map) ((Map) ((List) parse(doc).differences)[0]).conclusion).code)
    }

    @Test
    void singleSidedFindingsUseTheFindingBucket() {
        File doc = writeDiffDocument([[diffType: "FINDING", primaryId: "SO1", presentIn: FILE1,
                                       data: JsonOutput.toJson([internalId: "SO1", status: "F"])]])
        List rules = [[conclusionEnumId: "CONC_SHIPPED_NOT_INVOICED", label: "Shipped, never invoiced", appliesToBucket: "FINDING",
                       conditions: [cond(subject: "FILE_1", fieldExpression: "status", operator: "IN", conditionValues: ["F"], checkLabel: "Pending Billing")]]]
        RunConclusionSupport.concludeRun([diffFile: doc, file1Label: FILE1, file2Label: null, singleSided: true, rules: rules,
                                          file1   : [extractFile: extract("f1.json", []), keyFields: ["internalId"], evidence: NETSUITE_EVIDENCE],
                                          file2   : null])
        Map conclusion = (Map) ((Map) ((List) parse(doc).differences)[0]).conclusion
        assertEquals("CONC_SHIPPED_NOT_INVOICED", conclusion.code)
        assertEquals(1, ((List) conclusion.systems).size())
    }

    private static Map onlyConclusion(File doc) {
        return (Map) ((Map) ((List) parse(doc).differences)[0]).conclusion
    }

    // Final review I2: extractors write no sidecar when nothing was excluded, so a filtered side whose
    // own metadata PROVES it dropped nothing must still be able to read as absent.
    @Test
    void aFilteredSideThatProvablyExcludedNothingCanStillBeAbsent() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2)])
        File ns = extract("ns.json", [[orderId: "M6", orderLineId: "01"]],
                [configuredExclusions: [[sequenceNum: 1, excludedCount: 0, fieldAbsentCount: 0]]])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
        assertEquals("CONC_NEVER_REACHED_NS", onlyConclusion(doc).code)
    }

    @Test
    void aSideThatExcludedRowsButLostItsSidecarStaysUnknown() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2)])
        File ns = extract("ns.json", [], [filters: [configuredExclusions: [[sequenceNum: 1, excludedCount: 0, fieldAbsentCount: 2]]]])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
        assertEquals("UNEXPLAINED", onlyConclusion(doc).code)
    }

    // Final review I1: a side whose key the pass cannot rebuild is unknown, never "Not found".
    @Test
    void aSideWhoseKeyCannotBeRebuiltIsUnknownNotAbsent() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2)])
        Map a = args(doc, NEVER_REACHED_RULES)
        ((Map) a.file2).keyReliable = false
        RunConclusionSupport.concludeRun(a)
        assertEquals("UNEXPLAINED", onlyConclusion(doc).code)
        assertEquals("UNKNOWN", ((List) onlyConclusion(doc).systems).find { it.side == "FILE_2" }.presence)
    }

    // Final review I3: the compare collapses duplicate keys to ONE arbitrary record, so a field
    // condition on the present side must hold for EVERY record the side has under that key.
    private static final List GIFT_CARD_RULES = [
            [conclusionEnumId: "CONC_GIFT_CARD", label: "Gift card", appliesToBucket: "MISSING_FROM_FILE_2",
             conditions: [cond(subject: "FILE_1", fieldExpression: "facilityId", operator: "IN", conditionValues: ["_NA_"], checkLabel: "Not fulfilled from a facility")]],
    ]

    @Test
    void aMixedOrderIsNotConcludedFromOneArbitraryUnit() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2, [omsOrderId: "M1", orderItemSeqId: "01", facilityId: "_NA_"])])
        File oms = extract("oms-mixed.json", [[omsOrderId: "M1", orderItemSeqId: "01", facilityId: "_NA_"],
                                              [omsOrderId: "M1", orderItemSeqId: "01", facilityId: "217"]])
        RunConclusionSupport.concludeRun(args(doc, GIFT_CARD_RULES, null, oms))
        assertEquals("UNEXPLAINED", onlyConclusion(doc).code)
    }

    @Test
    void anOrderWhoseEveryUnitAgreesIsConcluded() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2, [omsOrderId: "M1", orderItemSeqId: "01", facilityId: "_NA_"])])
        File oms = extract("oms-giftcard.json", [[omsOrderId: "M1", orderItemSeqId: "01", facilityId: "_NA_"],
                                                 [omsOrderId: "M1", orderItemSeqId: "01", facilityId: "_NA_"]])
        RunConclusionSupport.concludeRun(args(doc, GIFT_CARD_RULES, null, oms))
        assertEquals("CONC_GIFT_CARD", onlyConclusion(doc).code)
        assertEquals(["_NA_"], onlyConclusion(doc).checks*.value)
    }

    // Final review I4: an extract too large to parse whole is not indexed; its side reads unknown.
    @Test
    void anExtractOverTheSizeCeilingIsNotParsedAndReadsUnknown() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2)])
        File ns = extract("ns-big.json", [[orderId: "M6", orderLineId: "01"]])
        long saved = RunConclusionSupport.maxIndexedExtractBytes
        RunConclusionSupport.maxIndexedExtractBytes = 8L
        try {
            Map result = RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
            assertEquals("UNEXPLAINED", onlyConclusion(doc).code)
            assertTrue(((List) result.unknownSides).contains("FILE_2"))
        } finally {
            RunConclusionSupport.maxIndexedExtractBytes = saved
        }
    }

    @Test
    void anUncompilableTreeFailsTheStepNotTheRun() {
        // DAR-BE-063. A tree whose parents form a cycle must refuse to conclude — concludeRun throws, and
        // RunConclusionStep (which catches every Throwable) ends the step FAILED. The contract here is only
        // that it THROWS before touching the document, rather than writing conclusions from a broken tree.
        List cyclic = [
                [sequenceNum: 10, parentSequenceNum: 20, conclusionEnumId: "A", label: "A", appliesToBucket: null, conditions: []],
                [sequenceNum: 20, parentSequenceNum: 10, conclusionEnumId: "B", label: "B", appliesToBucket: null, conditions: []],
        ]
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2)])
        String before = doc.text
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException) {
            RunConclusionSupport.concludeRun(args(doc, cyclic))
        }
        assertEquals(before, doc.text, "the document is untouched when the tree does not compile")
    }

    @Test
    void everyConcludedRowCarriesItsPath() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2)])
        RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES))
        Map row = (Map) ((List) parse(doc).differences)[0]
        assertEquals(["CONC_NEVER_REACHED_NS"], ((List) ((Map) row.conclusion).path)*.code)
    }

    @Test
    void aDuplicateRowConcludesInItsOwnBucketWithItsOwnSidePresent() {
        List rules = [[sequenceNum: 10, conclusionEnumId: "DUP", label: "Duplicate", appliesToBucket: "DUPLICATE_IN_FILE_2",
                       conditions: [cond(subject: "FILE_2", presence: "KEPT", checkLabel: "NetSuite holds it")]]]
        Map row = [diffType: "DUPLICATE_IN_FILE_2", compareScopeId: "SCOPE_1", objectType: "ORDER",
                   primaryId: "M1${SEP}01", presentIn: FILE2, missingIn: "",
                   data: JsonOutput.toJson([orderId: "M1", orderLineId: "01"]), message: "Duplicated in ${FILE2}".toString()]
        File doc = writeDiffDocument([row])
        RunConclusionSupport.concludeRun(args(doc, rules))
        Map concluded = (Map) ((Map) ((List) parse(doc).differences)[0]).conclusion
        assertEquals("DUP", concluded.code)
    }
}
