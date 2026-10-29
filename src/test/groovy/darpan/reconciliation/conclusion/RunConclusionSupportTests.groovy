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

    /** DAR-BE-151: a scope in the brain takes the brain's conclusion where it has one and the tree's for the rest. */
    @Test
    void aBrainScopeWritesTheBrainsConclusionAndTheTreesForTheRest() {
        darpan.conclusion.brain.BrainAnswerStore.clear()
        File doc = writeDiffDocument([
                missingRow("M1088731${SEP}01", FILE1, FILE2, [omsOrderId: "M1088731", orderItemSeqId: "01", orderGrandTotal: 371.54]),
                missingRow("M2${SEP}01", FILE1, FILE2, [omsOrderId: "M2", orderItemSeqId: "01", orderGrandTotal: 50])])
        List asked = []
        Map a = args(doc, NEVER_REACHED_RULES) + [brain: [kind: "orderLine", tenantId: "T_BRAIN", labelFor: { String c -> c },
                fetchQuestion: { String q, List ids -> asked << ids; [M1088731: [orderId: "M1088731", paymentPreferenceSlotsFull: false,
                        paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_CASH", statusId: "PAYMENT_SETTLED", maxAmount: "10"],
                                             [paymentMethodTypeId: "EXT_SHOP_CASH", statusId: "PAYMENT_REFUNDED", maxAmount: "10"]]]] }]]

        Map result = RunConclusionSupport.concludeRun(a)

        assertEquals([["M1088731", "M2"]], asked, "both orders are asked once, together")
        Map byId = ((List<Map>) parse(doc).differences).collectEntries { [(it.primaryId): it.conclusion] }
        assertEquals("CONC_PARTIAL_PAYMENT_GIVEN_BACK", byId["M1088731${SEP}01".toString()].code)
        assertEquals("CONC_NEVER_REACHED_NS", byId["M2${SEP}01".toString()].code, "nothing found for M2: the tree concludes it")
        assertEquals(1, result.counts.CONC_PARTIAL_PAYMENT_GIVEN_BACK)
        assertEquals(1, result.counts.CONC_NEVER_REACHED_NS)
        assertEquals(2, result.brain.asked, "two orders asked in one call")
    }

    /** Security review 2026-10-28: the brain takes at most a bounded number of findings per run; the tree concludes the rest. */
    @Test
    void theBrainTakesABoundedNumberOfFindingsAndTheTreeTheRest() {
        darpan.conclusion.brain.BrainAnswerStore.clear()
        int saved = RunConclusionSupport.maxBrainFindings
        RunConclusionSupport.maxBrainFindings = 1
        try {
            Map paid = [orderId: "X", paymentPreferenceSlotsFull: false, paymentPreferences: [
                    [paymentMethodTypeId: "EXT_SHOP_CASH", statusId: "PAYMENT_SETTLED", maxAmount: "10"],
                    [paymentMethodTypeId: "EXT_SHOP_CASH", statusId: "PAYMENT_REFUNDED", maxAmount: "10"]]]
            File doc = writeDiffDocument([
                    missingRow("M7${SEP}01", FILE1, FILE2, [omsOrderId: "M7", orderItemSeqId: "01", orderGrandTotal: 50]),
                    missingRow("M8${SEP}01", FILE1, FILE2, [omsOrderId: "M8", orderItemSeqId: "01", orderGrandTotal: 50])])
            Map a = args(doc, NEVER_REACHED_RULES) + [brain: [kind: "orderLine", tenantId: "T_CAP", labelFor: { String c -> c },
                    fetchQuestion: { String q, List ids -> ids.collectEntries { [(it): paid] } }]]
            Map result = RunConclusionSupport.concludeRun(a)
            Map byId = ((List<Map>) parse(doc).differences).collectEntries { [(it.primaryId): it.conclusion.code] }
            assertEquals("CONC_PARTIAL_PAYMENT_GIVEN_BACK", byId["M7${SEP}01".toString()])
            assertEquals("CONC_NEVER_REACHED_NS", byId["M8${SEP}01".toString()])
            assertTrue(((List) result.brain.warnings).any { it.toString().contains("1 of 2") })
        } finally {
            RunConclusionSupport.maxBrainFindings = saved
        }
    }

    /**
     * DAR-BE-118 (gorjana R8, F-104609): a return whose order is backordered in NetSuite. Only the findings that reach the
     * rule reading the lookup are asked about, each id once, in one call; a found record names the cause, a miss falls through.
     */
    @Test
    void aLookupAsksOnlyForTheFindingsThatReachItsRuleAndConcludesWithTheAnswer() {
        List rules = [
                [conclusionEnumId: "C_NO_ORDER", label: "Its order never reached NetSuite", appliesToBucket: "MISSING_FROM_FILE_2",
                 conditions: [cond(subject: "FILE_1", fieldExpression: "orderNetsuiteId", operator: "BLANK", checkLabel: "No order id")]],
                [conclusionEnumId: "C_BACKORDER", label: "Its order is backordered in NetSuite", appliesToBucket: "MISSING_FROM_FILE_2",
                 conditions: [cond(subject: "FILE_1", fieldExpression: "orderInNetsuite.backordered", operator: "GT", conditionValues: ["0"],
                                   checkLabel: "Units backordered")]],
                [conclusionEnumId: "C_NOT_IN_NS", label: "Return not in NetSuite", appliesToBucket: "MISSING_FROM_FILE_2",
                 conditions: [cond(subject: "FILE_1", fieldExpression: "statusId", operator: "IN", conditionValues: ["RETURN_COMPLETED"],
                                   checkLabel: "Completed")]]]
        Closure row = { String id, String orderNs -> [diffType: "missing_in_${FILE2}".toString(), primaryId: id, presentIn: FILE1,
                missingIn: FILE2, data: JsonOutput.toJson([returnId: id, statusId: "RETURN_COMPLETED", orderNetsuiteId: orderNs])] }
        File doc = writeDiffDocument([row("R1", null), row("R2", "81"), row("R3", "82"), row("R4", "81")])
        List<List<String>> asked = []
        Map a = args(doc, rules)
        a.file1 = [extractFile: extract("oms-r.json", []), keyFields: ["returnId"], idNormalizer: null, evidence: HOTWAX_EVIDENCE]
        a.lookups = [[name: "orderInNetsuite", subject: "FILE_1", idField: "orderNetsuiteId", keyField: "internalId"]]
        a.fetchLookup = { Map lookup, List<String> ids -> asked.add(ids); return ["81": [internalId: "81", backordered: "2"]] }

        Map result = RunConclusionSupport.concludeRun(a)

        assertEquals([["81", "82"]], asked, "R1 is explained first; 81 and 82 are asked once, together")
        Map byId = ((List<Map>) parse(doc).differences).collectEntries { [(it.primaryId): it.conclusion] }
        assertEquals(["C_NO_ORDER", "C_BACKORDER", "C_NOT_IN_NS", "C_BACKORDER"], ["R1", "R2", "R3", "R4"].collect { byId[it].code })
        assertEquals("2", ((List<Map>) byId.R2.checks).find { it.label == "Units backordered" }.value)
        assertEquals(2, result.counts.C_BACKORDER)
    }

    /**
     * DAR-BE-157 (Aditi on the gorjana product run's "No NS id": a product whose SKU NetSuite does not have is not expected to
     * carry a NetSuite id). A lookup from the run's other side answers from that side's own read, kept and excluded records
     * alike, by a field instead of the compare key, ignoring case. A miss reads found N; a side whose excluded records are
     * not all known answers nothing, so no rule can claim the SKU is absent.
     */
    @Test
    void aSideLookupAnswersFromTheOtherSidesReadByAField() {
        List rules = [
                [conclusionEnumId: "C_NOT_IN_NS", label: "Not a NetSuite item", appliesToBucket: "MISSING_FROM_FILE_2",
                 conditions: [cond(subject: "FILE_1", fieldExpression: "nsItemBySku.found", operator: "IN", conditionValues: ["N"],
                                   checkLabel: "NetSuite has no item with the SKU")]],
                [conclusionEnumId: "C_NO_NS_ID", label: "No NetSuite id", appliesToBucket: "MISSING_FROM_FILE_2",
                 conditions: [cond(subject: "FILE_1", fieldExpression: "nsItemBySku.id", operator: "NOT_IN", conditionValues: ["-"],
                                   checkLabel: "NetSuite item with the SKU")]]]
        Closure row = { String key, String sku -> [diffType: "missing_in_${FILE2}".toString(), primaryId: key, presentIn: FILE1,
                missingIn: FILE2, data: JsonOutput.toJson([nsKey: key, internalName: sku])] }
        Closure conclude = { File ns ->
            File doc = writeDiffDocument([row("P1", "hg-neck"), row("P2", "2210-013a-G-OLD"), row("P3", "052-0161-G"), row("P4", null)])
            Map a = args(doc, rules, ns)
            a.file1 = [extractFile: extract("oms-p-${System.nanoTime()}.json", []), keyFields: ["nsKey"], idNormalizer: null, evidence: HOTWAX_EVIDENCE]
            a.file2 = [extractFile: ns, keyFields: ["id"], idNormalizer: null, evidence: NETSUITE_EVIDENCE]
            a.lookups = [[name: "nsItemBySku", subject: "FILE_1", idField: "internalName", fromSide: "FILE_2", keyField: "sku"]]
            a.fetchLookup = { Map l, List<String> ids -> throw new AssertionError("a side lookup never calls a connection") }
            RunConclusionSupport.concludeRun(a)
            return ((List<Map>) parse(doc).differences).collectEntries { [(it.primaryId): it.conclusion] }
        }

        File ns = extract("ns-items.json", [[id: "30345", sku: "HG-NECK"]], [configuredExclusions: [[sequenceNum: 1]]])
        sidecar(ns, [[id: "18", sku: "052-0161-G"]])
        Map byKey = conclude(ns)
        assertEquals(["C_NO_NS_ID", "C_NOT_IN_NS", "C_NO_NS_ID", "C_NO_NS_ID"], ["P1", "P2", "P3", "P4"].collect { byKey[it].code },
                "in NetSuite (any case), absent, screened out by a filter yet read, no SKU at all")
        assertEquals("30345", ((List<Map>) byKey.P1.checks)[0].value)
        assertEquals("18", ((List<Map>) byKey.P3.checks)[0].value)

        File unknown = extract("ns-items-unknown.json", [[id: "30345", sku: "HG-NECK"]], [configuredExclusions: [[sequenceNum: 1]]])
        assertEquals("C_NO_NS_ID", conclude(unknown).P2.code, "no sidecar: the filtered-out items are unknown, so absence is not claimed")
    }

    /** Aditi on R1 F-100781: the NetSuite line the run screened out is the finding's NetSuite record; its ids ride on the row. */
    @Test
    void aFoundOtherSideRecordsIdsRideOnTheRow() {
        File doc = writeDiffDocument([missingRow("M1${SEP}01", FILE1, FILE2, [omsOrderId: "M1", orderItemSeqId: "01"])])
        File ns = extract("ns-ids.json", [], [configuredExclusions: [[sequenceNum: 1]]])
        sidecar(ns, [[orderId: "M1", orderLineId: "01", tranId: "SO7330652", internalId: "81049165", quantityBackordered: "1", status: "B"]])
        RunConclusionSupport.concludeRun(args(doc, BACKORDER_RULES, ns))
        Map row = (Map) ((List) parse(doc).differences)[0]
        assertEquals([tranId: "SO7330652", internalId: "81049165"], ((Map) row.ids).file2)
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

    // DAR-BE-095: an extract is streamed, never parsed whole, so a large one is indexed rather than read as unknown, and
    // only the records a finding needs stay in memory (a month of invoices once ran a 512 MB JVM out of heap here).
    @Test
    void aLargeExtractIsStreamedAndIndexed() {
        File doc = writeDiffDocument([missingRow("M5${SEP}01", FILE1, FILE2), missingRow("M6${SEP}02", FILE1, FILE2)])
        List<Map> lines = (1..100000).collect { int i -> [orderId: "N${i}".toString(), orderLineId: "01"] } + [[orderId: "M6", orderLineId: "01"]]
        File ns = extract("ns-big.json", lines)
        Map result = RunConclusionSupport.concludeRun(args(doc, NEVER_REACHED_RULES, ns))
        assertFalse(((List) result.unknownSides).contains("FILE_2"), "a large extract is read, not given up on")
        List rows = (List) parse(doc).differences
        assertEquals("CONC_NEVER_REACHED_NS", ((Map) ((Map) rows[0]).conclusion).code, "M5: NetSuite has no line for the order")
        assertEquals("UNEXPLAINED", ((Map) ((Map) rows[1]).conclusion).code, "M6: NetSuite has the order, on another line")
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

    // DAR-BE-157 (Aditi 2026-10-29: expected rows should not show in the product run): a rule whose outcome is HIDE drops
    // the rows it concludes from the result, takes them off the summary's totals, and says what it hid.
    @Test
    void aHideRuleDropsItsRowsAndSaysWhatItHid() {
        List rules = [BACKORDER_RULES[0], NEVER_REACHED_RULES[0] + [outcome: "HIDE"]]
        File nsExtract = extract("ns-hide.json", [])
        sidecar(nsExtract, [[orderId: "O1", orderLineId: "1", quantityBackordered: "2"]])
        File doc = writeDiffDocument([missingRow("O1${SEP}1", FILE1, FILE2), missingRow("O2${SEP}1", FILE1, FILE2)],
                [totalDifferences: 2, onlyInFile1Count: 2, onlyInFile2Count: 0])
        Map out = RunConclusionSupport.concludeRun(args(doc, rules, nsExtract))
        Map d = (Map) new JsonSlurper().parse(doc)
        assertEquals(["O1${SEP}1".toString()], d.differences*.primaryId)
        assertEquals([totalDifferences: 1, onlyInFile1Count: 1, onlyInFile2Count: 0], d.summary.subMap(["totalDifferences", "onlyInFile1Count", "onlyInFile2Count"]))
        assertEquals([[code: "CONC_NEVER_REACHED_NS", label: "Never reached NetSuite", count: 1]], d.summary.conclusions.hidden)
        assertFalse(d.summary.conclusions.counts*.code.contains("CONC_NEVER_REACHED_NS"), "a hidden cause is not among the shown ones")
        assertEquals([MISSING_FROM_FILE_2: 1], out.hiddenByBucket)
    }

    @Test
    void hiddenRowsComeOffTheRunsStoredCounts() {
        Map counts = [differenceCount: 10, onlyInFile1Count: 6, onlyInFile2Count: 4, missingInFile2Count: 6, missingInFile1Count: 4,
                      missingObjectDifferenceCount: 10]
        RunConclusionSupport.applyHidden(counts, [MISSING_FROM_FILE_2: 5, MISSING_FROM_FILE_1: 1])
        assertEquals([differenceCount: 4, onlyInFile1Count: 1, onlyInFile2Count: 3, missingInFile2Count: 1, missingInFile1Count: 3,
                      missingObjectDifferenceCount: 4], counts, "the run result page reads missingObjectDifferenceCount")
        Map untouched = [differenceCount: 3]
        RunConclusionSupport.applyHidden(untouched, null)
        assertEquals([differenceCount: 3], untouched)
    }
}
