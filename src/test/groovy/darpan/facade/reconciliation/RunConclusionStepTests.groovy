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
}
