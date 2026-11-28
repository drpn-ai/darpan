package darpan.reconciliation.source

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b Task 6. A connector's built-in Keep lines (moqui-rules "when" lines on plain record fields) to the Keep
 * rule shape, and the data errors for lines Keep cannot run. No Moqui. Made-up system (ACME).
 */
class KeepLineTranslatorTests {

    private static Map line(String seq, String type, Map more = [:]) {
        return [conditionSeqId: seq, parentConditionSeqId: null, sequenceNum: seq as Integer, conditionTypeId: type] + more
    }

    private static Map cmp(String seq, String field, String operator, Object value = null, Map more = [:]) {
        return line(seq, "RCT_COMPARE", [fieldName: field, operatorEnumId: operator, compareValue: value] + more)
    }

    private static List<Map> entry(List<Map> lines, Map<String, String> kinds = [:]) {
        return [[sequenceNum: 1, label: "ACME lines", kinds: kinds, lines: lines]]
    }

    /** The one test of a single declared entry. */
    private static Map testOf(List<Map> lines, Map<String, String> kinds = [:]) {
        return (Map) ((List<Map>) KeepLineTranslator.declared(entry(lines, kinds), [:])[0].tests)[0].test
    }

    private static IllegalArgumentException refused(List<Map> lines, Map<String, String> kinds = [:]) {
        return assertThrows(IllegalArgumentException) { KeepLineTranslator.declared(entry(lines, kinds), [:]) }
    }

    private static Map compareTest(String field, String op, List values, String valueKind = "TEXT", String fold = "NONE") {
        return [kind: "compare", field: field, text: "FACT", op: op, values: values, valueKind: valueKind, fold: fold]
    }

    // ------------------------------------------------------------------------------------------------ lines Keep cannot run

    @Test
    void anExistsLineIsRefused() {
        IllegalArgumentException out = refused([line("01", "RCT_EXISTS", [fieldName: "items"]),
                                                cmp("02", "itemsSku", "ENTCO_EQUALS", "X", [parentConditionSeqId: "01"])], [items: "FVT_LIST"])
        assertTrue(out.message.contains("Keep line 01: an exists line reads a list, and Keep sets aside whole records only"), out.message)
    }

    @Test
    void aPieceIsRefused() {
        IllegalArgumentException out = refused([line("03", "RCT_PIECE", [compareValue: "P1"])])
        assertTrue(out.message.contains("Keep line 03: a piece cannot be used in Keep; write its lines out"), out.message)
    }

    @Test
    void aLineComparingTwoFieldsIsRefused() {
        IllegalArgumentException out = refused([line("04", "RCT_COMPARE_FIELD", [fieldName: "total", operatorEnumId: "ENTCO_EQUALS", compareFieldName: "paid"])])
        assertTrue(out.message.contains("Keep line 04: Keep compares one field with fixed values, not with another field"), out.message)
    }

    @Test
    void aDateTimeOrListFieldIsRefusedNamingEveryLine() {
        IllegalArgumentException out = refused([cmp("01", "shippedAt", "ENTCO_IS_NULL"), cmp("02", "tags", "ENTCO_IS_NOT_NULL")],
                [shippedAt: "FVT_DATE_TIME", tags: "FVT_LIST"])
        assertTrue(out.message.contains("Built-in Keep entry 1 (ACME lines): Keep line 01: shippedAt is a date and time field; " +
                "Keep compares text, numbers and yes/no only; Keep line 02: tags is a list field"), out.message)
    }

    // ------------------------------------------------------------------------------------------------ compare lines

    @Test
    void aCompareLineBecomesACompareTest() {
        Map<String, String> kinds = [amount: "FVT_DECIMAL", qty: "FVT_INTEGER", paid: "FVT_BOOLEAN"]
        // text: exactly as written, no trim; the field is the record key
        assertEquals(compareTest("status", "EQ", ["Open "]), testOf([cmp("01", "status", "ENTCO_EQUALS", "Open ")], kinds))
        assertEquals(compareTest("status", "NE", [""]), testOf([cmp("01", "status", "ENTCO_NOT_EQUALS", "")], kinds))
        assertEquals(compareTest("status", "GT", ["M"]), testOf([cmp("01", "status", "ENTCO_GREATER", "M")], kinds))
        assertEquals(compareTest("status", "LIKE", ["ab%"], "TEXT", "LIKE_UPPER"), testOf([cmp("01", "status", "ENTCO_LIKE", "ab%")], kinds))
        assertEquals(compareTest("status", "NOT_LIKE", ["_x"], "TEXT", "LIKE_UPPER"), testOf([cmp("01", "status", "ENTCO_NOT_LIKE", "_x")], kinds))
        assertEquals(compareTest("status", "IS_NULL", null), testOf([cmp("01", "status", "ENTCO_IS_NULL")], kinds))
        assertEquals(compareTest("status", "IS_NOT_NULL", null), testOf([cmp("01", "status", "ENTCO_IS_NOT_NULL", "ignored")], kinds))
        assertEquals(compareTest("order-id", "EQ", ["1"]), testOf([cmp("01", "order-id", "ENTCO_EQUALS", "1")], kinds))
        // numbers: plain text, scale kept; a whole number field takes 7.0 as 7
        assertEquals(compareTest("amount", "LT", ["24.00"], "NUMBER"), testOf([cmp("01", "amount", "ENTCO_LESS", " 24.00 ")], kinds))
        assertEquals(compareTest("amount", "LE", ["-0.5"], "NUMBER"), testOf([cmp("01", "amount", "ENTCO_LESS_EQ", "-.5")], kinds))
        assertEquals(compareTest("qty", "GE", ["7"], "NUMBER"), testOf([cmp("01", "qty", "ENTCO_GREATER_EQ", "7.0")], kinds))
        assertEquals(compareTest("qty", "IN", ["1", "2"], "NUMBER"), testOf([cmp("01", "qty", "ENTCO_IN", "[1, 2]")], kinds))
        assertEquals(compareTest("qty", "NOT_IN", ["3"], "NUMBER"), testOf([cmp("01", "qty", "ENTCO_NOT_IN", "[3]")], kinds))
        // yes/no: y and n in any case, padded
        assertEquals(compareTest("paid", "EQ", ["true"], "BOOLEAN"), testOf([cmp("01", "paid", "ENTCO_EQUALS", "Y")], kinds))
        assertEquals(compareTest("paid", "NE", ["false"], "BOOLEAN"), testOf([cmp("01", "paid", "ENTCO_NOT_EQUALS", " n ")], kinds))

        // what moqui-rules refuses at save is a data error here
        [cmp("01", "amount", "ENTCO_EQUALS", "1e2"), cmp("01", "qty", "ENTCO_EQUALS", "1.5"),
         cmp("01", "paid", "ENTCO_EQUALS", "1"), cmp("01", "amount", "ENTCO_LIKE", "1%"),
         cmp("01", "status", "ENTCO_BETWEEN", "a"), cmp("01", "status", "ENTCO_EQUALS", null)].each { Map bad -> refused([bad], kinds) }
    }

    @Test
    void inAndNotInTakeTheJsonList() {
        Map<String, String> kinds = [amount: "FVT_DECIMAL", paid: "FVT_BOOLEAN"]
        // on a text field a JSON number is its plain text
        assertEquals(compareTest("code", "IN", ["100", "1.0", "x", "true", "7"]), testOf([cmp("01", "code", "ENTCO_IN", '[1e2, 1.0, "x", true, 7]')], kinds))
        // a number field takes numbers and number strings, scale kept so 2.5 can compare by value with 2.50
        assertEquals(compareTest("amount", "NOT_IN", ["2.50", "3", "100"], "NUMBER"), testOf([cmp("01", "amount", "ENTCO_NOT_IN", '["2.50", 3, 1e2]')], kinds))
        assertEquals(compareTest("paid", "IN", ["true", "true", "false"], "BOOLEAN"), testOf([cmp("01", "paid", "ENTCO_IN", '[true, "y", "N"]')], kinds))
        // a declared line may carry the list itself
        assertEquals(compareTest("code", "IN", ["100"]), testOf([cmp("01", "code", "ENTCO_IN", [new BigDecimal("1E+2")])], kinds))

        ['"x"', '[]', '[null]', '[{"a": 1}]', 'not json'].each { String bad -> refused([cmp("01", "code", "ENTCO_IN", bad)], kinds) }
        // a number string with an exponent is not a number; a yes/no list takes no number
        refused([cmp("01", "amount", "ENTCO_IN", '["1e2"]')], kinds)
        refused([cmp("01", "paid", "ENTCO_IN", '[1]')], kinds)
    }

    @Test
    void normalizeTextFoldsAndLikeUpperCases() {
        Map y = [normalizeText: "Y"]
        assertEquals(compareTest("code", "EQ", [" ab "], "TEXT", "NORMALIZE"), testOf([cmp("01", "code", "ENTCO_EQUALS", " ab ", y)]))
        assertEquals(compareTest("code", "NOT_IN", ["a", "ß"], "TEXT", "NORMALIZE"), testOf([cmp("01", "code", "ENTCO_NOT_IN", '["a", "ß"]', y)]))
        assertEquals(compareTest("code", "LIKE", ["a%"], "TEXT", "NORMALIZE"), testOf([cmp("01", "code", "ENTCO_LIKE", "a%", y)]))
        assertEquals(compareTest("code", "LIKE", ["a%"], "TEXT", "LIKE_UPPER"), testOf([cmp("01", "code", "ENTCO_LIKE", "a%", [normalizeText: "N"])]))
        assertEquals(compareTest("code", "EQ", ["a"], "TEXT", "NONE"), testOf([cmp("01", "code", "ENTCO_EQUALS", "a", [normalizeText: ""])]))

        [cmp("01", "amount", "ENTCO_EQUALS", "1", y),        // not text
         cmp("01", "code", "ENTCO_LESS", "a", y),            // not an equals, in or like
         cmp("01", "code", "ENTCO_EQUALS", " \t ", y),       // no value once trimmed
         cmp("01", "code", "ENTCO_IN", '["a", "  "]', y),
         cmp("01", "code", "ENTCO_EQUALS", "a", [normalizeText: "X"])].each { Map bad -> refused([bad], [amount: "FVT_DECIMAL"]) }
    }

    @Test
    void allAnyAndNotNest() {
        // written out of order: lines are walked in sequenceNum order under each parent
        List<Map> lines = [
                cmp("08", "e", "ENTCO_EQUALS", "E"),
                cmp("07", "d", "ENTCO_EQUALS", "D", [parentConditionSeqId: "05"]),
                cmp("06", "c", "ENTCO_EQUALS", "C", [parentConditionSeqId: "05"]),
                line("05", "RCT_NOT", [parentConditionSeqId: "03"]),
                cmp("04", "b", "ENTCO_EQUALS", "B", [parentConditionSeqId: "03"]),
                line("03", "RCT_ANY", [parentConditionSeqId: "01"]),
                line("09", "RCT_NOT", [parentConditionSeqId: "01"]),
                cmp("10", "f", "ENTCO_LESS", "5", [parentConditionSeqId: "09"]),
                cmp("02", "a", "ENTCO_EQUALS", "A", [parentConditionSeqId: "01"]),
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

        // an all, any or not line with nothing under it, or a line whose parent is missing, is a data error
        refused([line("01", "RCT_ANY")])
        refused([cmp("02", "a", "ENTCO_EQUALS", "A", [parentConditionSeqId: "99"])])
    }

    // ------------------------------------------------------------------------------------------------ entries

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
        assertEquals([], KeepLineTranslator.declared("[]", [:]))
    }

    /**
     * DAR-OPS-029 S05 (no hollow green): keepLinesJson that is written but is not a list, such as the JSON text "null", is a data
     * error. Read as "no built-ins" it would let a Spark side compare the records its built-ins set aside, without a word.
     */
    @Test
    void writtenKeepLinesThatAreNotAListAreRefused() {
        ["null", " null ", '{"label":"x"}', '"[]"'].each { String text ->
            IllegalArgumentException refused = assertThrows(IllegalArgumentException, { KeepLineTranslator.declared(text, [:]) }, text)
            assertTrue(refused.message.contains("JSON list"), refused.message)
        }
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
        // an entry with no lines, and two entries sharing a sequence number (so one rule id), are data errors too
        assertThrows(IllegalArgumentException) { KeepLineTranslator.declared([[sequenceNum: 6, label: "empty", lines: []]], [:]) }
        assertThrows(IllegalArgumentException) {
            KeepLineTranslator.declared([[label: "A", lines: [cmp("01", "a", "ENTCO_EQUALS", "A")]],
                                         [label: "B", lines: [cmp("01", "b", "ENTCO_EQUALS", "B")]]], [:])
        }
    }

    /**
     * DAR-OPS-029 S05 (conservation): an entry whose lines all sit under one another has no top line. Read as "no lines" it would
     * hold for every record and set the whole side aside without a word; it is a data error naming the entry instead.
     */
    @Test
    void aDeclaredEntryWithNoTopLineIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            KeepLineTranslator.declared([[sequenceNum: 8, label: "ACME loop", lines: [
                    cmp("01", "acmeLineType", "ENTCO_EQUALS", "PAY", [parentConditionSeqId: "02"]),
                    line("02", "RCT_ALL", [parentConditionSeqId: "01"])]]], [:])
        }
        assertTrue(refused.message.contains("8") && refused.message.contains("top"), refused.message)
    }
}
