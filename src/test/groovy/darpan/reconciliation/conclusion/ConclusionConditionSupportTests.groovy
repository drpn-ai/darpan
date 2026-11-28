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

    /** Aditi 2026-10-23: an open credit memo is R4's only with more than $5 unapplied. */
    @Test
    void gtHoldsAboveItsNumberAndReportsTheAmountRead() {
        Map over = ConclusionConditionSupport.check(cond(fieldExpression: "unapplied", operator: "GT", conditionValues: ["5"]),
                [presence: "KEPT", record: [unapplied: "6.19"]])
        assertTrue(over.passed)
        assertEquals("6.19", over.value)
        ["5", "5.00", "1.61", "0", "", null, "n/a"].each { String v ->
            assertFalse(ConclusionConditionSupport.check(cond(fieldExpression: "unapplied", operator: "GT", conditionValues: ["5"]),
                    [presence: "KEPT", record: [unapplied: v]]).passed, "${v} is not more than 5")
        }
    }

    /** DAR-BE-327, Aditi 2026-11-20: a gorjana return dated before go-live (2024-10-08 00:00 PDT) never reaches NetSuite. */
    @Test
    void ltHoldsBelowItsNumberSoADateBeforeGoLiveMatches() {
        Map c = cond(fieldExpression: "returnDate", operator: "LT", conditionValues: ["1728370800000"])
        Map before = ConclusionConditionSupport.check(c, [presence: "KEPT", record: [returnDate: 1668844800000L]]) // 2022-11-19
        assertTrue(before.passed)
        assertEquals("1668844800000", before.value)
        [1728370800000L, 1795103893708L, "", null, "n/a"].each { Object v ->
            assertFalse(ConclusionConditionSupport.check(c, [presence: "KEPT", record: [returnDate: v]]).passed, "${v} is not before go-live")
        }
        assertTrue(ConclusionConditionSupport.check(cond(fieldExpression: "unapplied", operator: "LT", conditionValues: ["5"]),
                [presence: "KEPT", record: [unapplied: "-1.5"]]).passed, "plain numbers too")
    }

    /** Aditi 2026-10-24: "it takes a fulfilled item 24 hrs to be billed... outside that timeline it is an issue". */
    @Test
    void withinHoursHoldsForATimeInsideTheLastHoursOnly() {
        Closure at = { long hoursAgo -> java.time.Instant.now().minusSeconds(hoursAgo * 3600L).toString() }
        Map c = cond(fieldExpression: "completedAt", operator: "WITHIN_HOURS", conditionValues: ["24"])
        assertTrue(ConclusionConditionSupport.check(c, [presence: "KEPT", record: [completedAt: at(3)]]).passed)
        assertTrue(ConclusionConditionSupport.check(c, [presence: "KEPT", record: [completedAt: System.currentTimeMillis() - 3_600_000L]]).passed, "epoch millis")
        [at(25), at(72), "", null, "not a time"].each { Object v ->
            assertFalse(ConclusionConditionSupport.check(c, [presence: "KEPT", record: [completedAt: v]]).passed, "${v} is not within 24 hours")
        }
    }

    // DAR-OPS-029 S03a (lens E3, handoff from S04): WITHIN_HOURS measures back from the instant it is given, not the clock,
    // so a replay of 2026-11-01's run concludes as that run did.
    @Test
    void withinHoursMeasuresBackFromTheInstantItIsGiven() {
        long runAt = java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        Map c = cond(fieldExpression: "completedAt", operator: "WITHIN_HOURS", conditionValues: ["24"])
        Map side = [presence: "KEPT", record: [completedAt: "2026-10-31T12:00:00Z"]]
        assertTrue(ConclusionConditionSupport.check(c, side, runAt).passed, "12 hours before the run")
        assertFalse(ConclusionConditionSupport.check(c, side, runAt + 2 * 86_400_000L).passed, "60 hours before a later run")
        assertFalse(ConclusionConditionSupport.check(c, side).passed, "the clock today: weeks later, so not within 24 hours")
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

    /**
     * DAR-BE-345 (Aditi 2026-11-24, option A): a HotWax exchange order's lines carry no flag, only its name (EXC-#1032413-1),
     * so R4 tells them apart by prefix. Case is ignored as IN ignores it; a blank starts with nothing, so NOT_ holds on it.
     */
    @Test
    void startsWithAnyMatchesAListedPrefixAndItsOppositeHoldsOnABlank() {
        Map exc = cond(fieldExpression: "omsOrderName", operator: "STARTS_WITH_ANY", conditionValues: ["EXC-", "REPL-"])
        Map notExc = cond(fieldExpression: "omsOrderName", operator: "NOT_STARTS_WITH_ANY", conditionValues: ["EXC-", "REPL-"])
        Map read = ConclusionConditionSupport.check(exc, [presence: "KEPT", record: [omsOrderName: "EXC-#1032413-1"]])
        assertTrue(read.passed)
        assertEquals("EXC-#1032413-1", read.value)
        assertTrue(ConclusionConditionSupport.check(exc, [presence: "KEPT", record: [omsOrderName: "repl-#707865-1"]]).passed, "case is ignored")
        ["#1041642", "#12947OUT", "XEXC-#1", "", null].each { Object v ->
            Map side = [presence: "KEPT", record: [omsOrderName: v]]
            assertFalse(ConclusionConditionSupport.check(exc, side).passed, "${v} is not an exchange")
            assertTrue(ConclusionConditionSupport.check(notExc, side).passed, "${v} does not start with a listed prefix")
        }
        assertFalse(ConclusionConditionSupport.check(notExc, [presence: "KEPT", record: [omsOrderName: "EXC-#1035299-1"]]).passed)
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
