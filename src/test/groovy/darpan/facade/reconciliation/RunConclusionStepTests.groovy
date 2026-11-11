package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
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
}
