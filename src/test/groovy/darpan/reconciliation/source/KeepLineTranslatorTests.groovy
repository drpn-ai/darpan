package darpan.reconciliation.source

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b Task 6. Front-end B: moqui-rules Keep lines to the Keep rule shape, and the refusals for lines
 * Keep cannot run. No Moqui. The side is a made-up system (prefix acme, other side beta).
 */
class KeepLineTranslatorTests {

    private static Map line(String seq, String type, Map more = [:]) {
        return [conditionSeqId: seq, parentConditionSeqId: null, sequenceNum: seq as Integer, conditionTypeId: type] + more
    }

    private static Map cmp(String seq, String field, String operator, Object value = null, Map more = [:]) {
        return line(seq, "RCT_COMPARE", [fieldName: field, operatorEnumId: operator, compareValue: value] + more)
    }

    private static Map rule(String ruleId, Integer priority, List<Map> conditions, Map more = [:]) {
        return [ruleId: ruleId, ruleName: "rule " + ruleId, priority: priority, outcomeId: "OUT_" + ruleId, conditions: conditions] + more
    }

    /** The one test of a translated single-rule decision. */
    private static Map testOf(List<Map> conditions, Map<String, String> kinds = [:], Map params = [:], Map recordKeys = null) {
        Map out = KeepLineTranslator.translate([rule("R1", 1, conditions)], "acme", kinds, params, recordKeys)
        assertEquals([], out.refusals)
        return (Map) ((List<Map>) ((List<Map>) out.rules)[0].tests)[0].test
    }

    private static Map compareTest(String field, String op, List values, String valueKind = "TEXT", String fold = "NONE") {
        return [kind: "compare", field: field, text: "FACT", op: op, values: values, valueKind: valueKind, fold: fold]
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Test
    void anExistsLineIsRefused() {
        List<Map> lines = [line("01", "RCT_EXISTS", [fieldName: "acme_items"]),
                           cmp("02", "acme_items_sku", "ENTCO_EQUALS", "X", [parentConditionSeqId: "01"])]
        assertEquals(["Keep line 01: an exists line reads a list, and Keep sets aside whole records only"],
                KeepLineTranslator.refusals(lines, "acme", [acme_items: "FVT_LIST"]))
    }

    @Test
    void aPieceIsRefused() {
        assertEquals(["Keep line 03: a piece cannot be used in Keep; write its lines out"],
                KeepLineTranslator.refusals([line("03", "RCT_PIECE", [compareValue: "P1"])], "acme", [:]))
    }

    @Test
    void aLineComparingTwoFieldsIsRefused() {
        assertEquals(["Keep line 04: Keep compares one field with fixed values, not with another field"],
                KeepLineTranslator.refusals([line("04", "RCT_COMPARE_FIELD",
                        [fieldName: "acme_total", operatorEnumId: "ENTCO_EQUALS", compareFieldName: "acme_paid"])], "acme", [:]))
    }

    @Test
    void anAskFieldIsRefused() {
        assertEquals(["Keep line 05: ask_lookup_status comes from an ask, which runs after Keep"],
                KeepLineTranslator.refusals([cmp("05", "ask_lookup_status", "ENTCO_EQUALS", "X")], "acme", [:]))
    }

    @Test
    void aFieldThatIsNotThisSidesRecordFieldIsRefused() {
        List<Map> lines = [line("01", "RCT_ANY"),
                           cmp("02", "beta_status", "ENTCO_EQUALS", "X", [parentConditionSeqId: "01"]),
                           cmp("03", "acme_presence", "ENTCO_EQUALS", "KEPT", [parentConditionSeqId: "01"]),
                           cmp("04", "claim", "ENTCO_EQUALS", "OUT_1", [parentConditionSeqId: "01"]),
                           cmp("05", "acme_status", "ENTCO_EQUALS", "X", [parentConditionSeqId: "01"])]
        assertEquals(["Keep line 02: beta_status is not a acme record field",
                      "Keep line 03: acme_presence is not a acme record field",
                      "Keep line 04: claim is not a acme record field"],
                KeepLineTranslator.refusals(lines, "acme", [:]))
    }

    @Test
    void aDateTimeOrListFieldIsRefused() {
        List<Map> lines = [cmp("01", "acme_shippedAt", "ENTCO_IS_NULL"), cmp("02", "acme_tags", "ENTCO_IS_NOT_NULL")]
        assertEquals(["Keep line 01: acme_shippedAt is a date and time field; Keep compares text, numbers and yes/no only",
                      "Keep line 02: acme_tags is a list field; Keep compares text, numbers and yes/no only"],
                KeepLineTranslator.refusals(lines, "acme", [acme_shippedAt: "FVT_DATE_TIME", acme_tags: "FVT_LIST"]))
    }

    @Test
    void aRuleWithARefusedLineIsLeftOutAndItsRefusalListed() {
        Map out = KeepLineTranslator.translate([rule("R1", 2, [cmp("01", "ask_x_y", "ENTCO_EQUALS", "Z")]),
                                                rule("R2", 1, [cmp("01", "acme_status", "ENTCO_EQUALS", "Z")])], "acme", [:], [:])
        assertEquals(["Keep line 01: ask_x_y comes from an ask, which runs after Keep"], out.refusals)
        assertEquals(["B:R2"], ((List<Map>) out.rules)*.id)
    }

    // ------------------------------------------------------------------------------------------------ compare lines

    @Test
    void aCompareLineBecomesACompareTest() {
        Map<String, String> kinds = [acme_amount: "FVT_DECIMAL", acme_qty: "FVT_INTEGER", acme_paid: "FVT_BOOLEAN"]
        // text: exactly as written, no trim, field less the side prefix
        assertEquals(compareTest("status", "EQ", ["Open "]), testOf([cmp("01", "acme_status", "ENTCO_EQUALS", "Open ")], kinds))
        assertEquals(compareTest("status", "NE", [""]), testOf([cmp("01", "acme_status", "ENTCO_NOT_EQUALS", "")], kinds))
        assertEquals(compareTest("status", "GT", ["M"]), testOf([cmp("01", "acme_status", "ENTCO_GREATER", "M")], kinds))
        assertEquals(compareTest("status", "LIKE", ["ab%"], "TEXT", "LIKE_UPPER"),
                testOf([cmp("01", "acme_status", "ENTCO_LIKE", "ab%")], kinds))
        assertEquals(compareTest("status", "NOT_LIKE", ["_x"], "TEXT", "LIKE_UPPER"),
                testOf([cmp("01", "acme_status", "ENTCO_NOT_LIKE", "_x")], kinds))
        assertEquals(compareTest("status", "IS_NULL", null), testOf([cmp("01", "acme_status", "ENTCO_IS_NULL")], kinds))
        assertEquals(compareTest("status", "IS_NOT_NULL", null), testOf([cmp("01", "acme_status", "ENTCO_IS_NOT_NULL", "ignored")], kinds))
        // numbers: plain text, scale kept; a whole number field takes 7.0 as 7
        assertEquals(compareTest("amount", "LT", ["24.00"], "NUMBER"), testOf([cmp("01", "acme_amount", "ENTCO_LESS", " 24.00 ")], kinds))
        assertEquals(compareTest("amount", "LE", ["-0.5"], "NUMBER"), testOf([cmp("01", "acme_amount", "ENTCO_LESS_EQ", "-.5")], kinds))
        assertEquals(compareTest("qty", "GE", ["7"], "NUMBER"), testOf([cmp("01", "acme_qty", "ENTCO_GREATER_EQ", "7.0")], kinds))
        assertEquals(compareTest("qty", "IN", ["1", "2"], "NUMBER"), testOf([cmp("01", "acme_qty", "ENTCO_IN", "[1, 2]")], kinds))
        assertEquals(compareTest("qty", "NOT_IN", ["3"], "NUMBER"), testOf([cmp("01", "acme_qty", "ENTCO_NOT_IN", "[3]")], kinds))
        // yes/no: y and n in any case
        assertEquals(compareTest("paid", "EQ", ["true"], "BOOLEAN"), testOf([cmp("01", "acme_paid", "ENTCO_EQUALS", "Y")], kinds))
        assertEquals(compareTest("paid", "NE", ["false"], "BOOLEAN"), testOf([cmp("01", "acme_paid", "ENTCO_NOT_EQUALS", "n")], kinds))
        // the record key the field was named from, when given
        assertEquals(compareTest("order-id", "EQ", ["1"]),
                testOf([cmp("01", "acme_order_id", "ENTCO_EQUALS", "1")], kinds, [:], [acme_order_id: "order-id"]))

        // what moqui-rules refuses at save is a data error here
        [cmp("01", "acme_amount", "ENTCO_EQUALS", "1e2"), cmp("01", "acme_qty", "ENTCO_EQUALS", "1.5"),
         cmp("01", "acme_paid", "ENTCO_EQUALS", "1"), cmp("01", "acme_amount", "ENTCO_LIKE", "1%"),
         cmp("01", "acme_status", "ENTCO_BETWEEN", "a"), cmp("01", "acme_status", "ENTCO_EQUALS", null)].each { Map bad ->
            assertThrows(IllegalArgumentException) { KeepLineTranslator.translate([rule("R1", 1, [bad])], "acme", kinds, [:]) }
        }
    }

    @Test
    void inAndNotInTakeTheJsonList() {
        Map<String, String> kinds = [acme_amount: "FVT_DECIMAL", acme_paid: "FVT_BOOLEAN"]
        // on a text field a JSON number is its plain text
        assertEquals(compareTest("code", "IN", ["100", "1.0", "x", "true", "7"]),
                testOf([cmp("01", "acme_code", "ENTCO_IN", '[1e2, 1.0, "x", true, 7]')], kinds))
        // a number field takes numbers and number strings, scale kept so 2.5 can compare by value with 2.50
        assertEquals(compareTest("amount", "NOT_IN", ["2.50", "3", "100"], "NUMBER"),
                testOf([cmp("01", "acme_amount", "ENTCO_NOT_IN", '["2.50", 3, 1e2]')], kinds))
        assertEquals(compareTest("paid", "IN", ["true", "true", "false"], "BOOLEAN"),
                testOf([cmp("01", "acme_paid", "ENTCO_IN", '[true, "y", "N"]')], kinds))
        // a declared line may carry the list itself
        assertEquals(compareTest("code", "IN", ["100"]), testOf([cmp("01", "acme_code", "ENTCO_IN", [new BigDecimal("1E+2")])], kinds))

        ['"x"', '[]', '[null]', '[{"a": 1}]', 'not json'].each { String bad ->
            assertThrows(IllegalArgumentException) {
                KeepLineTranslator.translate([rule("R1", 1, [cmp("01", "acme_code", "ENTCO_IN", bad)])], "acme", kinds, [:])
            }
        }
        // a number string with an exponent is not a number; a yes/no list takes no number
        assertThrows(IllegalArgumentException) {
            KeepLineTranslator.translate([rule("R1", 1, [cmp("01", "acme_amount", "ENTCO_IN", '["1e2"]')])], "acme", kinds, [:])
        }
        assertThrows(IllegalArgumentException) {
            KeepLineTranslator.translate([rule("R1", 1, [cmp("01", "acme_paid", "ENTCO_IN", '[1]')])], "acme", kinds, [:])
        }
    }

    @Test
    void normalizeTextFoldsAndLikeUpperCases() {
        Map y = [normalizeText: "Y"]
        assertEquals(compareTest("code", "EQ", [" ab "], "TEXT", "NORMALIZE"), testOf([cmp("01", "acme_code", "ENTCO_EQUALS", " ab ", y)]))
        assertEquals(compareTest("code", "NOT_IN", ["a", "ß"], "TEXT", "NORMALIZE"),
                testOf([cmp("01", "acme_code", "ENTCO_NOT_IN", '["a", "ß"]', y)]))
        assertEquals(compareTest("code", "LIKE", ["a%"], "TEXT", "NORMALIZE"), testOf([cmp("01", "acme_code", "ENTCO_LIKE", "a%", y)]))
        assertEquals(compareTest("code", "LIKE", ["a%"], "TEXT", "LIKE_UPPER"),
                testOf([cmp("01", "acme_code", "ENTCO_LIKE", "a%", [normalizeText: "N"])]))
        assertEquals(compareTest("code", "EQ", ["a"], "TEXT", "NONE"), testOf([cmp("01", "acme_code", "ENTCO_EQUALS", "a", [normalizeText: ""])]))

        [cmp("01", "acme_amount", "ENTCO_EQUALS", "1", y),        // not text
         cmp("01", "acme_code", "ENTCO_LESS", "a", y),            // not an equals, in or like
         cmp("01", "acme_code", "ENTCO_EQUALS", " \t ", y),       // no value once trimmed
         cmp("01", "acme_code", "ENTCO_IN", '["a", "  "]', y),
         cmp("01", "acme_code", "ENTCO_EQUALS", "a", [normalizeText: "X"])].each { Map bad ->
            assertThrows(IllegalArgumentException) {
                KeepLineTranslator.translate([rule("R1", 1, [bad])], "acme", [acme_amount: "FVT_DECIMAL"], [:])
            }
        }
    }

    @Test
    void allAnyAndNotNest() {
        // written out of order: lines are walked in sequenceNum order under each parent
        List<Map> lines = [
                cmp("08", "acme_e", "ENTCO_EQUALS", "E"),
                cmp("07", "acme_d", "ENTCO_EQUALS", "D", [parentConditionSeqId: "05"]),
                cmp("06", "acme_c", "ENTCO_EQUALS", "C", [parentConditionSeqId: "05"]),
                line("05", "RCT_NOT", [parentConditionSeqId: "03"]),
                cmp("04", "acme_b", "ENTCO_EQUALS", "B", [parentConditionSeqId: "03"]),
                line("03", "RCT_ANY", [parentConditionSeqId: "01"]),
                line("09", "RCT_NOT", [parentConditionSeqId: "01"]),
                cmp("10", "acme_f", "ENTCO_LESS", "5", [parentConditionSeqId: "09"]),
                cmp("02", "acme_a", "ENTCO_EQUALS", "A", [parentConditionSeqId: "01"]),
                line("01", "RCT_ALL")]
        Map expected = [kind: "all", of: [
                [kind: "all", of: [
                        compareTest("a", "EQ", ["A"]),
                        [kind: "any", of: [
                                compareTest("b", "EQ", ["B"]),
                                // a not over several lines: none of them holds
                                [kind: "not", of: [kind: "any", of: [compareTest("c", "EQ", ["C"]), compareTest("d", "EQ", ["D"])]]]]],
                        [kind: "not", of: compareTest("f", "LT", ["5"])]]],
                compareTest("e", "EQ", ["E"])]]
        assertEquals(expected, testOf(lines))

        // a rule with no lines holds for every record, as a moqui-rules rule with no lines fires for every fact
        assertEquals([kind: "const", holds: true], testOf([]))
        // an all, any or not line with nothing under it, or a line whose parent is missing, is a data error
        assertThrows(IllegalArgumentException) { testOf([line("01", "RCT_ANY")]) }
        assertThrows(IllegalArgumentException) { testOf([cmp("02", "acme_a", "ENTCO_EQUALS", "A", [parentConditionSeqId: "99"])]) }
    }

    @Test
    void aParamLineIsAConstFromTheParams() {
        Map<String, String> kinds = [param_limit: "FVT_DECIMAL"]
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_mode", "ENTCO_EQUALS", "strict")], kinds, [mode: "strict"]))
        assertEquals([kind: "const", holds: false], testOf([cmp("01", "param_mode", "ENTCO_EQUALS", "strict")], kinds, [mode: "Strict"]))
        assertEquals([kind: "const", holds: true],
                testOf([cmp("01", "param_mode", "ENTCO_EQUALS", "STRICT", [normalizeText: "Y"])], kinds, [mode: " strict "]))
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_mode", "ENTCO_LIKE", "st%")], kinds, [mode: "Strict"]))
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_limit", "ENTCO_IN", '["2.50"]')], kinds, [limit: "2.5"]))
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_limit", "ENTCO_GREATER", "2")], kinds, [limit: "10"]))
        // a missing param is no value: not equal to anything, and null
        assertEquals([kind: "const", holds: false], testOf([cmp("01", "param_mode", "ENTCO_EQUALS", "strict")], kinds, [:]))
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_mode", "ENTCO_NOT_IN", '["strict"]')], kinds, [:]))
        assertEquals([kind: "const", holds: true], testOf([cmp("01", "param_mode", "ENTCO_IS_NULL")], kinds, [:]))
        assertEquals([kind: "const", holds: false], testOf([cmp("01", "param_limit", "ENTCO_GREATER_EQ", "2")], kinds, [:]))
        // a param that does not fit its kind is refused as moqui-rules refuses the fact
        assertThrows(IllegalArgumentException) { testOf([cmp("01", "param_limit", "ENTCO_EQUALS", "2")], kinds, [limit: "two"]) }
    }

    @Test
    void rulesKeepPriorityOrderAndNameTheirOutcome() {
        List<Map> decision = [
                rule("R10", 10, [cmp("01", "acme_a", "ENTCO_EQUALS", "A")], [outcomeId: null, actions: [[actionTypeId: "X"], [outcomeId: "VOIDED"]]]),
                rule("R30", 30, [cmp("01", "acme_b", "ENTCO_EQUALS", "B")]),
                rule("R20", 20, [cmp("01", "acme_c", "ENTCO_EQUALS", "C")])]
        List<Map> rules = (List<Map>) KeepLineTranslator.translate(decision, "acme", [:], [:]).rules

        // moqui-rules fires the highest priority first
        assertEquals(["B:R30", "B:R20", "B:R10"], rules*.id)
        assertEquals([30, 20, 10], rules*.sequenceNum)
        assertEquals(["rule R30", "rule R20", "rule R10"], rules*.label)
        assertEquals(["DECISION"] * 3, rules*.source)
        assertEquals(["OUT_R30", "OUT_R20", "VOIDED"], rules.collect { ((List<Map>) it.tests)[0].reason })
        KeepRules.validate(rules)
    }

    // ------------------------------------------------------------------------------------------------ built-ins

    private static final String DECLARED = JsonOutput.toJson([
            [sequenceNum: 20, label: "Voided lines", appliesUnless: [acmeIncludeVoided: "Y"], kinds: [:],
             lines: [cmp("01", "acmeLineStatus", "ENTCO_EQUALS", "VOID")]],
            [sequenceNum: 10, label: "Unkeyed lines", lines: [cmp("01", "acmeUnkeyed", "ENTCO_EQUALS", "Y")]]])

    @Test
    void aDeclaredEntrySkipsWhenItsOptionSaysSo() {
        List<Map> all = KeepLineTranslator.declared(DECLARED, [:])
        assertEquals(["BUILT_IN:10", "BUILT_IN:20"], all*.id)
        assertEquals([10, 20], all*.sequenceNum)
        assertEquals(["Unkeyed lines", "Voided lines"], all*.label)
        assertEquals(["BUILT_IN"] * 2, all*.source)
        assertEquals(["BUILT_IN"] * 2, all.collect { ((List<Map>) it.tests)[0].reason })

        assertEquals(["BUILT_IN:10"], KeepLineTranslator.declared(DECLARED, [acmeIncludeVoided: "Y"])*.id)
        assertEquals(["BUILT_IN:10", "BUILT_IN:20"], KeepLineTranslator.declared(DECLARED, [acmeIncludeVoided: "N"])*.id)
        assertEquals([], KeepLineTranslator.declared(null, [:]))
        assertEquals([], KeepLineTranslator.declared("", [:]))
    }

    @Test
    void aDeclaredEntryOnAMadeUpSystemCompiles() {
        Object parsed = [[sequenceNum: 5, label: "ACME payment lines", kinds: [acmeAmount: "FVT_DECIMAL"],
                          lines: [line("01", "RCT_ANY"),
                                  cmp("02", "acmeLineType", "ENTCO_IN", '["PAY", "pay "]', [parentConditionSeqId: "01", normalizeText: "Y"]),
                                  cmp("03", "acmeAmount", "ENTCO_LESS", "0", [parentConditionSeqId: "01"])]]]
        List<Map> rules = KeepLineTranslator.declared(parsed, null)

        assertEquals([[id: "BUILT_IN:5", sequenceNum: 5, label: "ACME payment lines", source: "BUILT_IN",
                       tests: [[test: [kind: "any", of: [compareTest("acmeLineType", "IN", ["PAY", "pay "], "TEXT", "NORMALIZE"),
                                                         compareTest("acmeAmount", "LT", ["0"], "NUMBER")]], reason: "BUILT_IN"]]]], rules)
        assertEquals([[column: "_darpanKeep_0", field: "acmeLineType", text: "FACT"],
                      [column: "_darpanKeep_1", field: "acmeAmount", text: "FACT"]], KeepRules.injections(rules))

        // a declared line Keep cannot run is a data error naming the entry
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            KeepLineTranslator.declared([[sequenceNum: 7, label: "ACME dated", kinds: [acmeShippedAt: "FVT_DATE_TIME"],
                                          lines: [cmp("01", "acmeShippedAt", "ENTCO_IS_NULL")]]], [:])
        }
        assertTrue(refused.message.contains("7") && refused.message.contains("acmeShippedAt is a date and time field"), refused.message)
    }
}
