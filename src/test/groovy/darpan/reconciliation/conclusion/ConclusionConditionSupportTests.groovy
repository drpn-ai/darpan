package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063. One condition against one subject. Everything except quantifier and dotted paths is the
 * DAR-UI-044 evaluator's check() verbatim; those two default to its behaviour.
 */
class ConclusionConditionSupportTests {

    static Map cond(Map m) {
        return [presence: "ANY", keyScope: "FULL", quantifier: "ALL", fieldExpression: null, operator: null,
                conditionValues: [], checkLabel: "c"] + m
    }

    @Test
    void presenceOnlyReportsThePresenceItSaw() {
        Map r = ConclusionConditionSupport.check(cond(presence: "EXCLUDED"), [presence: "EXCLUDED", record: [:]])
        assertTrue(r.passed)
        assertEquals("excluded", r.value)
    }

    @Test
    void unknownSatisfiesNoPresenceCondition() {
        assertFalse(ConclusionConditionSupport.check(cond(presence: "ABSENT"), [presence: "UNKNOWN"]).passed)
    }

    @Test
    void allIsTheDefaultAndMeansEveryRecord() {
        Map side = [presence: "KEPT", record: [q: "0"], records: [[q: "0"], [q: "2"]]]
        assertFalse(ConclusionConditionSupport.check(cond(fieldExpression: "q", operator: "GT_ZERO"), side).passed)
        Map noQuantifier = cond(fieldExpression: "q", operator: "GT_ZERO")
        noQuantifier.remove("quantifier")
        assertFalse(ConclusionConditionSupport.check(noQuantifier, side).passed)
    }

    @Test
    void anyHoldsWhenOneRecordDoes() {
        Map side = [presence: "KEPT", record: [q: "0"], records: [[q: "0"], [q: "2"]]]
        Map r = ConclusionConditionSupport.check(cond(fieldExpression: "q", operator: "GT_ZERO", quantifier: "ANY"), side)
        assertTrue(r.passed)
        assertEquals("0, 2", r.value)
    }

    @Test
    void aDottedPathFansOutAcrossLists() {
        Map record = [prefs: [[method: "CARD"], [method: null]]]
        assertEquals(["CARD", null], ConclusionConditionSupport.valuesAt(record, "prefs.method"))
        Map side = [presence: "KEPT", record: record]
        assertFalse(ConclusionConditionSupport.check(cond(fieldExpression: "prefs.method", operator: "NOT_BLANK"), side).passed)
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "prefs.method", operator: "NOT_BLANK", quantifier: "ANY"), side).passed)
    }

    @Test
    void aPathThatYieldsNothingReadsAsOneBlank() {
        assertEquals([null], ConclusionConditionSupport.valuesAt([prefs: []], "prefs.method"))
        assertEquals([null], ConclusionConditionSupport.valuesAt([other: 1], "prefs.method"))
        Map r = ConclusionConditionSupport.check(cond(fieldExpression: "prefs.method", operator: "BLANK"),
                [presence: "KEPT", record: [prefs: []]])
        assertTrue(r.passed)
        assertNull(r.value)
    }

    @Test
    void aNestedMapIsWalked() {
        assertEquals(["N1"], ConclusionConditionSupport.valuesAt([ident: [NS_ID: "N1"]], "ident.NS_ID"))
    }

    @Test
    void aFlatFieldKeepsTheOldToStringBehaviour() {
        // Parity with DAR-UI-044: an undotted field is read with record.get(field).toString(), lists included.
        assertEquals(["[a, b]"], ConclusionConditionSupport.valuesAt([tags: ["a", "b"]], "tags"))
    }

    @Test
    void operatorsAreUnchanged() {
        Map side = [presence: "KEPT", record: [s: "f", e: "", n: "x"]]
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "s", operator: "IN", conditionValues: ["F"]), side).passed)
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "s", operator: "NOT_IN", conditionValues: ["G"]), side).passed)
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "e", operator: "BLANK"), side).passed)
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "n", operator: "NOT_BLANK"), side).passed)
        assertFalse(ConclusionConditionSupport.check(cond(fieldExpression: "n", operator: "GT_ZERO"), side).passed)
    }
}
