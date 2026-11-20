package darpan.facade.reconciliation

import darpan.conclusion.brain.BrainAnswerStore
import darpan.conclusion.brain.BrainConclude
import darpan.judge.JudgeReplaySupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-044. Entity rows become the evaluator's rule shape; the enum description is the label. */
class RunConclusionStepTests {

    @Test
    void rowsBecomeOrderedRulesWithTheirConditions() {
        List rules = RunConclusionStep.buildRules(
                [[sequenceNum: 20, conclusionEnumId: "B", appliesToBucket: "MISSING_FROM_FILE_2", questionText: null, suggestedFilterJson: null],
                 [sequenceNum: 10, conclusionEnumId: "A", appliesToBucket: null, questionText: "Q?",
                  suggestedFilterJson: '{"fileSide":"FILE_1","fieldExpression":"facilityId","operator":"EXCLUDE_IN","values":["_NA_"]}']],
                [[sequenceNum: 10, conditionSeq: 2, subject: "FILE_2", presence: null, keyScope: null, fieldExpression: "q",
                  operator: "IN", conditionValues: "F, E", checkLabel: "second"],
                 [sequenceNum: 10, conditionSeq: 1, subject: "FILE_2", presence: "ABSENT", keyScope: "FIRST_FIELD",
                  fieldExpression: null, operator: null, conditionValues: null, checkLabel: "first"]],
                [A: "Label A", B: "Label B"])
        assertEquals(["A", "B"], rules*.conclusionEnumId)
        assertEquals("Label A", rules[0].label)
        assertEquals(["first", "second"], rules[0].conditions*.checkLabel)
        assertEquals("ANY", rules[0].conditions[1].presence)
        assertEquals("FULL", rules[0].conditions[1].keyScope)
        assertEquals(["F", "E"], rules[0].conditions[1].conditionValues)
        assertEquals("facilityId", rules[0].suggestedFilter.fieldExpression)
        assertNull(rules[1].suggestedFilter)
        assertEquals([], rules[1].conditions)
    }

    // DAR-BE-318, ruling P2-27: a replay (its own lookupFetcher) keeps its brain off the answers live runs share.
    @Test
    void onlyACallerWithItsOwnLookupFetcherKeepsTheBrainOffTheSharedAnswers() {
        assertEquals(true, RunConclusionStep.sharesBrainAnswers([ec: null, compareScopeId: "S"]), "live callers pass no fetcher")
        assertEquals(true, RunConclusionStep.sharesBrainAnswers([lookupFetcher: null]))
        assertEquals(false, RunConclusionStep.sharesBrainAnswers([lookupFetcher: { Object[] a -> [:] }]), "a replay")
    }

    // DAR-BE-228: the brain's per-question ledger lands in the step's metadata beside the counts.
    @Test
    void theBrainsPerQuestionLedgerLandsInTheRunMetadata() {
        Map ledger = [orderPayments: [asked: 1, reused: 0, dropped: 0, answeredFromFacts: 0, failed: 1, batches: 2]]
        Map metrics = RunConclusionStep.stepMetrics([counts: [A: 2], unknownSides: [], brain: [asked: 1, questions: ledger]], ["lookup down"])
        assertEquals(ledger, metrics.brain.questions)
        assertEquals([A: 2], metrics.counts)
        assertEquals(["lookup down"], metrics.lookupWarnings)
        assertNull(RunConclusionStep.stepMetrics([counts: [:]], []).lookupWarnings)
    }

    @Test
    void aMissingEnumLabelFallsBackToTheCode() {
        List rules = RunConclusionStep.buildRules([[sequenceNum: 1, conclusionEnumId: "X"]], [], [:])
        assertEquals("X", rules[0].label)
    }

    @Test
    void labelsComeFromTheDocumentMetadata() {
        File f = File.createTempFile("doc", ".json")
        f.text = '{\n"metadata":{"file1Label":"HotWax","file2Label":null},\n"summary":{},\n"differences":[]\n}'
        Map labels = RunConclusionStep.documentLabels(f)
        assertEquals("HotWax", labels.file1Label)
        assertNull(labels.file2Label)
        f.delete()
    }

    // Final review I1: the side's key is rebuilt the way RuleSetCompareScopeAdapter built it, or the
    // side is declared unreadable — never keyed a different way and then read as "Not found".
    @Test
    void compositeKeysKeepTheirInlineNormalizersAndIgnoreTheSourceOne() {
        Map spec = RunConclusionStep.keySpec(["lineItemId|SHOPIFY_GID_TAIL", "unitOrdinal"], "ignored", "CASE_FOLD", "records")
        assertEquals(["lineItemId|SHOPIFY_GID_TAIL", "unitOrdinal"], spec.keyFields)
        assertNull(spec.idNormalizer)
        assertTrue(spec.keyReliable as boolean)
    }

    @Test
    void aLegacyKeyUsesThePrimaryExpressionAndTheSourceNormalizer() {
        Map spec = RunConclusionStep.keySpec([], "internalId", "case-fold", null)
        assertEquals(["internalId"], spec.keyFields)
        assertEquals("case-fold", spec.idNormalizer)
        assertTrue(spec.keyReliable as boolean)
    }

    @Test
    void aNestedKeyOrAForeignRecordRootIsNotReliable() {
        assertFalse(RunConclusionStep.keySpec([], "node.id", null, "records").keyReliable as boolean)
        assertFalse(RunConclusionStep.keySpec(["id"], null, null, "data.orders.edges").keyReliable as boolean)
        assertTrue(RunConclusionStep.keySpec(["id"], null, null, '$.records[*]').keyReliable as boolean)
        assertFalse(RunConclusionStep.keySpec([], null, null, "records").keyReliable as boolean)
    }

    @Test
    void buildRulesCarriesTheTreeAndTheQuantifier() {
        List<Map> rules = RunConclusionStep.buildRules(
                [[sequenceNum: 10, conclusionEnumId: "P"], [sequenceNum: 11, parentSequenceNum: 10, conclusionEnumId: "C"]],
                [[sequenceNum: 11, conditionSeq: 1, subject: "FILE_1", quantifier: "ANY", checkLabel: "x"]],
                [:])
        assertEquals([10, 11], rules*.sequenceNum)
        assertNull(rules[0].parentSequenceNum)
        assertEquals(10, rules[1].parentSequenceNum)
        assertEquals("ANY", rules[1].conditions[0].quantifier)
    }

    @Test
    void aConditionWithNoQuantifierIsAll() {
        List<Map> rules = RunConclusionStep.buildRules([[sequenceNum: 10, conclusionEnumId: "P"]],
                [[sequenceNum: 10, conditionSeq: 1, subject: "FILE_1", checkLabel: "x"]], [:])
        assertEquals("ALL", rules[0].conditions[0].quantifier)
    }

    /** DAR-BE-151: a BRAIN row only carries a brain cause's fix text for the audit; the tree never compiles it. */
    @Test
    void brainRowsAreNotCompiledIntoTheTree() {
        List rules = RunConclusionStep.buildRules([[sequenceNum: 5, conclusionEnumId: "CONC_PARTIAL_PAYMENT_GIVEN_BACK", engine: "BRAIN"],
                                                   [sequenceNum: 10, conclusionEnumId: "CONC_NEVER_REACHED_NS"]], [], [:])
        org.junit.jupiter.api.Assertions.assertEquals(["CONC_NEVER_REACHED_NS"], rules*.conclusionEnumId)
    }

    @Test
    void aBrainScopesKindIsReadFromItsConclusionBrainValue() {
        org.junit.jupiter.api.Assertions.assertEquals("orderLine", RunConclusionStep.brainKind("ORDER_LINE"))
        org.junit.jupiter.api.Assertions.assertEquals("return", RunConclusionStep.brainKind("RETURN"))
        // DAR-BE-192: an order-grain run (gorjana R7) and the default read for a replacement's return.
        org.junit.jupiter.api.Assertions.assertEquals("order", RunConclusionStep.brainKind("ORDER"))
        Map replacement = RunConclusionStep.questionLookup("replacementReturn", [], "gorjana_oms")
        org.junit.jupiter.api.Assertions.assertEquals(["OMS_RETURNS_GQL", "gorjana_oms", "replacementOrderId"],
                [replacement.systemEnumId, replacement.sourceConfigId, replacement.keyField])
        org.junit.jupiter.api.Assertions.assertNull(RunConclusionStep.questionLookup("returnRma", [], "gorjana_oms"),
                "NetSuite is read only through the scope's own lookups")
        org.junit.jupiter.api.Assertions.assertNull(RunConclusionStep.brainKind(null))
        org.junit.jupiter.api.Assertions.assertNull(RunConclusionStep.brainKind("SOMETHING_ELSE"))
    }


    /**
     * DAR-BE-151: the order-payments question needs no lookup config. It reads the scope's own HotWax source (its FILE_1
     * source config), so no prod lookup JSON is rewritten to add it; a lookup the scope names "orderPayments" still wins.
     */
    @Test
    void theOrderPaymentsQuestionReadsTheScopesOwnHotwaxSource() {
        Map lookup = RunConclusionStep.questionLookup("orderPayments", [], "gorjana_oms")
        org.junit.jupiter.api.Assertions.assertEquals("OMS_GQL", lookup.systemEnumId)
        org.junit.jupiter.api.Assertions.assertEquals("gorjana_oms", lookup.sourceConfigId)
        org.junit.jupiter.api.Assertions.assertEquals("orderId", lookup.keyField)
        org.junit.jupiter.api.Assertions.assertEquals(["orderId", "grandTotal", "paymentPreferences"], lookup.parameters.keepRecordFields)
        Map named = [name: "orderPayments", systemEnumId: "OMS_GQL", sourceConfigId: "other"]
        org.junit.jupiter.api.Assertions.assertSame(named, RunConclusionStep.questionLookup("orderPayments", [named], "gorjana_oms"))
        org.junit.jupiter.api.Assertions.assertNull(RunConclusionStep.questionLookup("orderPayments", [], null))
    }


    // DAR-BE-151 (prod gorjana 2026-11-06: R1 and R7 asked 0 questions — "the scope has no lookup to ask orderPayments of"):
    // a scheduled or audit run hands the brain its ReconciliationAutomationSource row, which keeps the HotWax connection in
    // safeMetadataJson.parameters under the connector's own name and has no sourceConfigId column. The same shape made
    // verification inert on 2026-08-27; the brain's default questions now read it the same way.
    @Test
    void aBrainQuestionFindsTheHotwaxConnectionOnAnAutomationSource() {
        Map connector = [configParameterName: "omsRestSourceConfigId"]
        Map automationSource = [systemEnumId: "OMS_ORDER_ITEMS_GQL", safeMetadataJson: '{"parameters":{"omsRestSourceConfigId":"100002"}}']
        assertEquals("100002", RunConclusionStep.hotwaxSourceConfigId(automationSource, connector))
        assertEquals("100002", RunConclusionStep.questionLookup("orderPayments", [],
                RunConclusionStep.hotwaxSourceConfigId(automationSource, connector)).sourceConfigId)
        assertEquals("cfg9", RunConclusionStep.hotwaxSourceConfigId([sourceConfigId: "cfg9"], connector), "a run's own source keeps its column")
        assertNull(RunConclusionStep.hotwaxSourceConfigId([safeMetadataJson: "{}"], connector))
    }

    // ---- DAR-BE-318 plan 3a, task 1: live lookup and brain answers are kept in <run>/lookups ----

    @TempDir File tmp

    @BeforeEach
    void clearStore() { BrainAnswerStore.clear() }

    private static final Map LOOKUP = [name: "orderInNetsuite", subject: "FILE_1", idField: "orderId", systemEnumId: "NETSUITE",
                                       sourceConfigId: "NS1", keyField: "orderId"]

    /** An ec whose connector row, extract service and call are stubs; the extract writes {metadata, records} where outputLocation says. */
    private Expando stubEc(List<Map> answerRecords, Map seen) {
        Map row = [systemEnumId: "NETSUITE", extractServiceName: "stub.extract#Orders", configParameterName: "configId"]
        Expando service = new Expando(
                getServiceDefinition: { String n -> [inParameterNameList: ["lookupKeys", "outputLocation"]] },
                sync: {
                    Map params = [:]
                    Expando call = new Expando()
                    call.name = { String n -> call }
                    call.parameters = { Map p -> params.putAll(p); seen.putAll(p); call }
                    call.call = {
                        File dir = params.outputLocation ? new File((String) params.outputLocation) : new File(tmp, "default-extract")
                        dir.mkdirs()
                        File f = new File(dir, (String) params.fileName)
                        f.setText(groovy.json.JsonOutput.toJson([metadata: [recordCount: answerRecords.size()], records: answerRecords]), "UTF-8")
                        return [fileLocation: f.absolutePath, dataAvailable: true]
                    }
                    return call
                })
        Expando query = new Expando()
        query.condition = { String a, Object b -> query }
        query.useCache = { boolean b -> query }
        query.one = { row }
        return new Expando(
                entity: new Expando(find: { String e -> query }),
                service: service,
                user: new Expando(nowTimestamp: new java.sql.Timestamp(1000L)),
                message: new Expando(hasError: { false }, errors: [], validationErrors: []),
                logger: new Expando(info: { String m -> }))
    }

    private static List<File> captures(File runDir) {
        File dir = new File(runDir, "lookups")
        return dir.isDirectory() ? (dir.listFiles() as List<File>).findAll { it.isFile() } : []
    }

    @Test
    void aLiveLookupLandsInTheRunFolder() {
        File run = new File(tmp, "run")
        List<Map> answer = [[orderId: "A1", status: "OPEN"], [orderId: "B2", status: "DONE"]]
        List<String> warnings = []
        Map found = RunConclusionStep.fetchLookup(stubEc(answer, [:]), LOOKUP, ["A1", "B2"], "T1", warnings, new File(run, "lookups"))
        assertEquals([:], warnings.isEmpty() ? [:] : [w: warnings])
        assertEquals(["A1", "B2"], found.keySet().sort())
        List<File> files = captures(run)
        assertEquals(1, files.size())
        assertTrue(JudgeReplaySupport.CAPTURED_LOOKUP.matcher(files[0].name).matches(), files[0].name)
        Map doc = new JsonSlurper().parse(files[0]) as Map
        assertEquals(answer, doc.records)
        assertNotNull(doc.metadata)
    }

    @Test
    void aCaptureReplaysThroughTheCapturedFetcher() {
        File run = new File(tmp, "run")
        List<Map> answer = [[orderId: "A1", status: "OPEN"], [orderId: "B2", status: "DONE"]]
        Map live = RunConclusionStep.fetchLookup(stubEc(answer, [:]), LOOKUP, ["A1", "B2"], "T1", [], new File(run, "lookups"))
        Set<String> notCaptured = [] as Set
        Closure replay = JudgeReplaySupport.capturedFetcher(new File(run, "lookups"), "the run", "Lookup", notCaptured)
        List<String> warnings = []
        Map replayed = (Map) replay.call(null, LOOKUP, ["A1", "B2"], "T1", warnings)
        assertEquals(live, replayed)
        assertEquals(answer, replayed.keySet().sort().collect { replayed[it] })
        assertTrue(notCaptured.isEmpty() && warnings.isEmpty())
    }

    @Test
    void aStoreServedBrainAnswerIsCapturedToo() {
        File lookupsDir = new File(tmp, "run/lookups")
        Map payments = [orderId: "M1088731", grandTotal: 371.54, paymentPreferenceSlotsFull: false, paymentPreferences: []]
        BrainAnswerStore.put("GORJANA", "orderPayments", "M1088731", payments)
        Map lookup = RunConclusionStep.questionLookup("orderPayments", [], "HW1")
        List<String> warnings = []
        List asked = []
        Map out = BrainConclude.run([kind: "orderLine", tenantId: "GORJANA", labelFor: { String c -> c },
                findings: [[key: "M1088731\u001F01", bucket: "MISSING_FROM_FILE_2", sides: [
                        FILE_1: [presence: "KEPT", record: [omsOrderId: "M1088731", orderItemSeqId: "01", orderGrandTotal: 371.54]],
                        FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]],
                fetchQuestion: { String q, List ids -> asked << ids; [:] },
                captureServed: { String q, Map served -> RunConclusionStep.captureServed(lookupsDir, lookup, served, warnings) }])
        assertEquals(1, out.reused)
        assertEquals([], asked, "served from the store, never fetched")
        assertEquals([], warnings)
        List<File> files = captures(new File(tmp, "run"))
        assertEquals(1, files.size())
        assertTrue(JudgeReplaySupport.CAPTURED_LOOKUP.matcher(files[0].name).matches(), files[0].name)
        assertEquals([payments], (new JsonSlurper().parse(files[0]) as Map).records)
        // and the replay reads it back by the lookup's key
        Map replayed = (Map) JudgeReplaySupport.capturedFetcher(lookupsDir, "the run", "Lookup", [] as Set)
                .call(null, lookup, ["M1088731"], "GORJANA", [])
        assertEquals(payments, replayed.M1088731)
    }

    @Test
    void aReplayWritesNoCapture() {
        File diff = new File(tmp, "run/diff.json")
        diff.parentFile.mkdirs()
        Closure own = { Object[] a -> [:] }
        assertNull(RunConclusionStep.capturesDirFor([diffFile: diff, lookupFetcher: own]), "a replay has no capture folder")
        assertEquals(new File(tmp, "run/lookups"), RunConclusionStep.capturesDirFor([diffFile: diff]))
        assertEquals(new File(tmp, "x"), RunConclusionStep.capturesDirFor([diffFile: diff, lookupsDir: new File(tmp, "x")]))
        // a replay's brain (shareAnswers false) is never handed the store's answers, so it has nothing to capture
        BrainAnswerStore.put("GORJANA", "orderPayments", "M1088731", [orderId: "M1088731"])
        List served = []
        BrainConclude.run([kind: "orderLine", tenantId: "GORJANA", labelFor: { String c -> c }, shareAnswers: false,
                findings: [[key: "M1088731\u001F01", bucket: "MISSING_FROM_FILE_2", sides: [
                        FILE_1: [presence: "KEPT", record: [omsOrderId: "M1088731", orderItemSeqId: "01", orderGrandTotal: 371.54]],
                        FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]],
                fetchQuestion: { String q, List ids -> [:] }, captureServed: { String q, Map m -> served << q }])
        assertEquals([], served)
        assertFalse(new File(tmp, "run/lookups").exists())
    }

    @Test
    void aLookupNameOutsideThePatternIsWarnedNotCaptured() {
        File run = new File(tmp, "run")
        Map odd = LOOKUP + [name: "order-info"]
        List<String> warnings = []
        Map found = RunConclusionStep.fetchLookup(stubEc([[orderId: "A1"]], [:]), odd, ["A1"], "T1", warnings, new File(run, "lookups"))
        assertEquals(1, warnings.size())
        assertTrue(warnings[0].contains("order-info"), warnings[0])
        assertEquals(["A1"], found.keySet() as List, "the answer itself stands")
        assertEquals([], captures(run))
        List<String> w2 = []
        RunConclusionStep.captureServed(new File(run, "lookups"), odd, [A1: [orderId: "A1"]], w2)
        assertTrue(w2[0].contains("order-info"))
        assertEquals([], captures(run))
    }

    private static void writeCapture(File dir, String name, long millis, List<Map> records) {
        dir.mkdirs()
        new File(dir, "lookup-${name}-${millis}.json").setText(groovy.json.JsonOutput.toJson([metadata: [:], records: records]), "UTF-8")
    }

    private static Map replayOf(File dir, Map lookup, List<String> ids) {
        return (Map) JudgeReplaySupport.capturedFetcher(dir, "the run", "Lookup", [] as Set).call(null, lookup, ids, "T1", [])
    }

    @Test
    void aReplayAnswersEveryBatchOfALookup() {
        File dir = new File(tmp, "run/lookups")
        writeCapture(dir, "orderInNetsuite", 1000, [[orderId: "A1", v: 1]])
        writeCapture(dir, "orderInNetsuite", 2000, [[orderId: "B2", v: 2]])
        Map replayed = replayOf(dir, LOOKUP, ["A1", "B2"])
        assertEquals(["A1", "B2"], replayed.keySet().sort())
    }

    @Test
    void theNewerCaptureWinsForTheSameId() {
        File dir = new File(tmp, "run/lookups")
        writeCapture(dir, "orderInNetsuite", 2000, [[orderId: "A1", v: "new"]])
        writeCapture(dir, "orderInNetsuite", 1000, [[orderId: "A1", v: "old"], [orderId: "C3", v: "only-old"]])
        Map replayed = replayOf(dir, LOOKUP, ["A1", "C3"])
        assertEquals("new", replayed.A1.v)
        assertEquals("only-old", replayed.C3.v)
        Map many = LOOKUP + [many: true]
        writeCapture(new File(tmp, "m"), "orderInNetsuite", 1000, [[orderId: "A1", i: 1], [orderId: "A1", i: 2]])
        writeCapture(new File(tmp, "m"), "orderInNetsuite", 2000, [[orderId: "A1", i: 2], [orderId: "A1", i: 3]])
        assertEquals([1, 2, 3], replayOf(new File(tmp, "m"), many, ["A1"]).A1*.i)
    }

    @Test
    void aLivePassOfTwoBatchesReplaysEveryId() {
        File dir = new File(tmp, "run/lookups")
        RunConclusionStep.fetchLookup(stubEc([[orderId: "A1"]], [:]), LOOKUP, ["A1"], "T1", [], dir)
        Thread.sleep(5)
        RunConclusionStep.fetchLookup(stubEc([[orderId: "B2"]], [:]), LOOKUP, ["B2"], "T1", [], dir)
        assertEquals(2, captures(new File(tmp, "run")).size())
        assertEquals(["A1", "B2"], replayOf(dir, LOOKUP, ["A1", "B2"]).keySet().sort())
    }
}
