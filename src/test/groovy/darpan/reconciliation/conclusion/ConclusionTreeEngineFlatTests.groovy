package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-UI-044. First full match wins; no match is UNEXPLAINED; a check reports the value the condition
 * SAW, never text from the rule; an UNKNOWN presence (sidecar missing or truncated) never matches.
 */
class ConclusionTreeEngineFlatTests {

    /** The DAR-UI-044 evaluator's contract, now served by the engine: same inputs, same answers. */
    static Map evaluate(List rules, String bucket, Map sides) {
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile((List<Map>) rules)
        try {
            return engine.conclude([[bucket: bucket, sides: sides]])[0]
        } finally {
            engine.close()
        }
    }


    static Map rule(String code, String label, List conds, Map extra = [:]) {
        return [conclusionEnumId: code, label: label, appliesToBucket: null, questionText: null,
                suggestedFilter: null, conditions: conds] + extra
    }

    static Map cond(Map m) {
        return [presence: "ANY", keyScope: "FULL", fieldExpression: null, operator: null, conditionValues: []] + m
    }

    static final List RULES = [
            rule("CONC_NS_BACKORDERED", "Backordered in NetSuite", [
                    cond(subject: "FILE_2", presence: "EXCLUDED", checkLabel: "NetSuite has the line"),
                    cond(subject: "FILE_2", fieldExpression: "quantityBackordered", operator: "GT_ZERO", checkLabel: "One on backorder")]),
            rule("CONC_NEVER_REACHED_NS", "Never reached NetSuite", [
                    cond(subject: "FILE_2", presence: "ABSENT", keyScope: "FIRST_FIELD", checkLabel: "NetSuite has no line for the order")]),
    ]

    @Test
    void firstFullMatchWinsAndChecksShowWhatWasSeen() {
        Map c = evaluate(RULES, "MISSING_FROM_FILE_2", [
                FILE_1: [presence: "KEPT", record: [omsOrderId: "M1"]],
                FILE_2: [presence: "EXCLUDED", record: [quantityBackordered: "1"], firstFieldPresence: "KEPT", firstFieldRecord: [:]]])
        assertEquals("CONC_NS_BACKORDERED", c.code)
        assertEquals("Backordered in NetSuite", c.label)
        assertEquals([["NetSuite has the line", "excluded"], ["One on backorder", "1"]],
                c.checks.collect { [it.label, it.value] })
        assertNull(c.question)
    }

    @Test
    void aPartialMatchFallsThroughToTheNextRuleOrUnexplained() {
        Map c = evaluate(RULES, "MISSING_FROM_FILE_2", [
                FILE_1: [presence: "KEPT", record: [:]],
                FILE_2: [presence: "EXCLUDED", record: [quantityBackordered: "0"], firstFieldPresence: "EXCLUDED", firstFieldRecord: [:]]])
        assertEquals("UNEXPLAINED", c.code)
        assertEquals("Unexplained", c.label)
        assertEquals([], c.checks)
    }

    @Test
    void firstFieldScopeReadsTheOrderLevelPresence() {
        Map c = evaluate(RULES, "MISSING_FROM_FILE_2", [
                FILE_1: [presence: "KEPT", record: [:]],
                FILE_2: [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]])
        assertEquals("CONC_NEVER_REACHED_NS", c.code)
        assertEquals([["NetSuite has no line for the order", "absent"]], c.checks.collect { [it.label, it.value] })
    }

    @Test
    void unknownPresenceNeverMatches() {
        Map c = evaluate(RULES, "MISSING_FROM_FILE_2", [
                FILE_1: [presence: "KEPT", record: [:]],
                FILE_2: [presence: "UNKNOWN", record: null, firstFieldPresence: "UNKNOWN", firstFieldRecord: null]])
        assertEquals("UNEXPLAINED", c.code, "a missing/truncated sidecar must not become 'Not found'")
    }

    @Test
    void bucketGateIsHonoured() {
        List r = [rule("X", "X", [], [appliesToBucket: "MISSING_FROM_FILE_1"])]
        assertEquals("UNEXPLAINED", evaluate(r, "MISSING_FROM_FILE_2", [FILE_1: [:], FILE_2: [:]]).code)
        assertEquals("X", evaluate(r, "MISSING_FROM_FILE_1", [FILE_1: [:], FILE_2: [:]]).code)
    }

    @Test
    void inIsCaseInsensitiveAndTheQuestionTravels() {
        Map filter = [fileSide: "FILE_1", fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", values: ["POS_SALES_CHANNEL"]]
        List r = [rule("POS", "POS order never reached NetSuite", [
                cond(subject: "FILE_1", fieldExpression: "salesChannelEnumId", operator: "IN",
                        conditionValues: ["pos_sales_channel"], checkLabel: "POS order")],
                [questionText: "Should POS orders reach NetSuite?", suggestedFilter: filter])]
        Map c = evaluate(r, "MISSING_FROM_FILE_2",
                [FILE_1: [presence: "KEPT", record: [salesChannelEnumId: "POS_SALES_CHANNEL"]], FILE_2: [:]])
        assertEquals("POS", c.code)
        assertEquals("Should POS orders reach NetSuite?", c.question.text)
        assertEquals(filter, c.question.suggestedFilter)
    }

    @Test
    void notInBlankAndNotBlank() {
        List r = [rule("N", "N", [
                cond(subject: "FILE_1", fieldExpression: "status", operator: "NOT_IN", conditionValues: ["G"], checkLabel: "Not billed"),
                cond(subject: "FILE_1", fieldExpression: "shipDate", operator: "BLANK", checkLabel: "No ship date"),
                cond(subject: "FILE_1", fieldExpression: "tranId", operator: "NOT_BLANK", checkLabel: "Has a number")])]
        assertEquals("N", evaluate(r, "FINDING",
                [FILE_1: [presence: "KEPT", record: [status: "F", shipDate: "", tranId: "SO1"]]]).code)
        assertEquals("UNEXPLAINED", evaluate(r, "FINDING",
                [FILE_1: [presence: "KEPT", record: [status: "G", shipDate: "", tranId: "SO1"]]]).code)
    }

    @Test
    void gtZeroRefusesNonNumbers() {
        List r = [rule("G", "G", [cond(subject: "FILE_1", fieldExpression: "q", operator: "GT_ZERO", checkLabel: "q")])]
        assertEquals("UNEXPLAINED", evaluate(r, "FINDING", [FILE_1: [presence: "KEPT", record: [q: "abc"]]]).code)
        assertEquals("G", evaluate(r, "FINDING", [FILE_1: [presence: "KEPT", record: [q: "2.0"]]]).code)
    }
}
