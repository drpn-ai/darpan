package darpan.reconciliation.source

import darpan.reconciliation.core.CompareIdExpressionSupport
import darpan.reconciliation.core.JsonRecordLines
import groovy.json.JsonOutput
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 5). Front-end A's parity suite: today's source filter rows compile to Keep rules
 * (SourceFilterSupport.toKeepRules), and Spark, reading the Keep text JsonRecordLines writes beside each record, must set
 * aside exactly the records, for exactly the rule and reason, that SourceFilterSupport.evaluate does, one record at a
 * time. Records are typed values written with JsonOutput as an extract; made-up data only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepColumnParityTests {

    private SparkSession spark = SparkSession.builder()
            .appName("keep-column-parity")
            .master("local[1]")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.ansi.enabled", "true")
            .getOrCreate()

    @TempDir
    File dir

    private int files = 0

    @AfterAll
    void stopSpark() {
        spark?.stop()
    }

    private static Map<String, Object> row(Integer seq, String field, String operator, Object values) {
        return [sequenceNum: seq, fieldExpression: field, operator: operator, filterValues: values] as Map<String, Object>
    }

    /** "<rule id>|<reason>" per record, from today's evaluate. */
    private static List<String> expected(List records, List<Map<String, Object>> parsed) {
        return records.collect { Object record ->
            Map verdict = SourceFilterSupport.evaluate(record, parsed)
            return verdict == null ? null : "A:${((Map) verdict.get('rule')).get('sequenceNum')}|${verdict.get('reason')}".toString()
        }
    }

    /** "<rule id>|<reason>" per record, from Spark over the Keep text the records file carries. */
    private List<String> sparkReasons(List<Map> records, List<Map<String, Object>> parsed) {
        List<Map> rules = SourceFilterSupport.toKeepRules(parsed)
        KeepRules.validate(rules)
        List<Map> injections = KeepRules.injections(rules)
        // no rule, no Keep text to inject: the records file is untouched and nothing is set aside
        if (!injections) return new ArrayList<String>(Collections.nCopies(records.size(), (String) null))
        File extract = new File(dir, "extract-${files++}.json")
        extract.setText(JsonOutput.toJson([metadata: [sourceType: "TEST"], records: records]), "UTF-8")
        List<Map> infos = [CompareIdExpressionSupport.convertJsonPathToSpark('$.records[*].id')]
        File lines = JsonRecordLines.forRecordArray(extract.absolutePath, infos, dir, injections)
        // the schema is the injected columns and the row, as the saved-schema path builds it (JsonRecordLines.recordSchema)
        StructType schema = new StructType()
        injections.each { Map injection -> schema = schema.add(injection.get("column") as String, DataTypes.StringType, true) }
        schema = schema.add(KeepRules.ROW_COLUMN, DataTypes.LongType, true)
        Dataset<Row> annotated = KeepColumnSupport.annotate(spark.read().schema(schema).json(lines.absolutePath), rules)
        List<String> out = new ArrayList<String>(Collections.nCopies(records.size(), (String) null))
        annotated.select(KeepRules.ROW_COLUMN, KeepRules.REASON_COLUMN).collectAsList().each { Row r ->
            out.set(((Long) r.getLong(0)).intValue(), r.isNullAt(1) ? null : r.getString(1))
        }
        return out
    }

    /** The parity assertion: every record, same rule and reason as evaluate. Returns what evaluate said. */
    private List<String> assertParity(List<Map> records, List<Map<String, Object>> rows) {
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(rows)
        List<String> want = expected(records, parsed)
        assertEquals(want, sparkReasons(records, parsed))
        return want
    }

    // ------------------------------------------------------------------------------ SourceFilterSupportTests cases

    @Test
    void noRulesSetsNothingAside() {
        assertEquals([null, null], assertParity([[id: "1", salesChannelEnumId: "POS_SALES_CHANNEL"], [id: "2"]], []))
    }

    @Test
    void matchesConfiguredValueCaseInsensitively() {
        List<Map> records = [[id: "1", salesChannelEnumId: "pos_sales_channel"], [id: "2", salesChannelEnumId: " POS_SALES_CHANNEL "],
                             [id: "3", salesChannelEnumId: "WEB"]]
        assertEquals(["A:1|VALUE", "A:1|VALUE", null], assertParity(records, [row(1, "salesChannelEnumId", null, "POS_SALES_CHANNEL,DRAFT_SALES_CHANNEL")]))
    }

    @Test
    void fieldNameIsCaseSensitiveButTrimmedLikeTheBuiltInFilters() {
        List<Map> records = [[id: "1", SALESCHANNELENUMID: "POS"], [id: "2", " salesChannelEnumId ": "POS"]]
        assertEquals([null, "A:1|VALUE"], assertParity(records, [row(1, "salesChannelEnumId", "EXCLUDE_IN", "POS")]))
    }

    @Test
    void recordMissingTheFieldOrBlankIsKeptByAnExcludeRule() {
        List<Map> records = [[id: "1", orderId: "10001"], [id: "2", salesChannelEnumId: null], [id: "3", salesChannelEnumId: "  "]]
        assertEquals([null, null, null], assertParity(records, [row(1, "salesChannelEnumId", "EXCLUDE_IN", "POS")]))
    }

    @Test
    void firstRejectingRuleWinsAndAnIncludeRuleOwnsWhatItRejects() {
        List<Map> records = [[id: "1", salesChannelEnumId: "POS", statusId: "ORDER_CANCELLED"], [id: "2", salesChannelEnumId: "WEB", statusId: "ORDER_CANCELLED"],
                             [id: "3", salesChannelEnumId: "WEB", statusId: "OK"]]
        List<Map<String, Object>> rows = [row(1, "salesChannelEnumId", null, "POS"), row(2, "statusId", null, "ORDER_CANCELLED")]
        assertEquals(["A:1|VALUE", "A:2|VALUE", null], assertParity(records, rows))

        List<Map> mixed = [[id: "1", origin: "NATIVE", status: "X"], [id: "2", origin: "EDI", status: "X"], [id: "3", origin: "EDI", status: "A"],
                           [id: "4", origin: "EDI"]]
        assertEquals(["A:1|VALUE", "A:2|VALUE", null, "A:2|FIELD_ABSENT"],
                assertParity(mixed, [row(1, "origin", "EXCLUDE_IN", "NATIVE"), row(2, "status", "INCLUDE_IN", "A")]))
    }

    @Test
    void includeInKeepsAListedValueAndRejectsTheRestWithTheReason() {
        List<Map> records = [[id: "1", status: "A"], [id: "2", status: "g"], [id: "3", status: "X"], [id: "4", orderId: "M1"],
                             [id: "5", status: "   "], [id: "6", status: null]]
        assertEquals([null, null, "A:1|VALUE", "A:1|FIELD_ABSENT", "A:1|FIELD_ABSENT", "A:1|FIELD_ABSENT"],
                assertParity(records, [row(1, "status", "INCLUDE_IN", "A,B,G")]))
    }

    @Test
    void includePrefixKeepsOnlyValuesStartingWithAListedOne() {
        List<Map> records = [[id: "1", status: "EXC-#GOR195591082-2"], [id: "2", status: "exc-#gor1"], [id: "3", status: "#GOR195591082"],
                             [id: "4", status: "WTY-EXC-1"], [id: "5", orderId: "M1"], [id: "6", status: "  EXC-9"]]
        assertEquals([null, null, "A:1|VALUE", "A:1|VALUE", "A:1|FIELD_ABSENT", null],
                assertParity(records, [row(1, "status", "INCLUDE_PREFIX", "EXC-")]))
    }

    @Test
    void parseRulesInputsGiveTheSameRulesAsTheirListForms() {
        // values as a list with blanks, a lower-case operator, a missing sequence number
        List<Map<String, Object>> rows = [[fieldExpression: " status ", operator: "include_in", values: ["  a ", "", "b"]] as Map<String, Object>]
        List<Map> records = [[id: "1", status: "A"], [id: "2", status: "c"], [id: "3"]]
        assertEquals([null, "A:1|VALUE", "A:1|FIELD_ABSENT"], assertParity(records, rows))
    }

    // ------------------------------------------------------------------------------ SourceFilterSupportApplyTests cases

    @Test
    void includeKeepsMatchesAndSetsTheRestAside() {
        List<Map> records = [[id: "1", s: "FULFILLED"], [id: "2", s: "UNFULFILLED"], [id: "3"]]
        assertEquals([null, "A:1|VALUE", "A:1|FIELD_ABSENT"], assertParity(records, [row(1, "s", "INCLUDE_IN", "FULFILLED")]))
    }

    @Test
    void aRuleThatMatchedNothingSetsNothingAside() {
        assertEquals([null], assertParity([[id: "1", s: "FULFILLED"]], [row(1, "s", "EXCLUDE_IN", "CANCELLED")]))
    }

    @Test
    void perRuleCountsEqualApplyToRecordsConfiguredExclusions() {
        List<Map> records = [[id: "1", s: "FULFILLED", c: "POS"], [id: "2", s: "UNFULFILLED", c: "WEB"], [id: "3", c: "POS"], [id: "4", s: "FULFILLED", c: "WEB"],
                             [id: "5", s: "FULFILLED"], [id: "6", c: "WEB"]]
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules([row(1, "c", "EXCLUDE_IN", "POS"), row(2, "s", "INCLUDE_IN", "FULFILLED"),
                                                                           row(3, "c", "EXCLUDE_IN", "NEVER")])
        List<String> got = sparkReasons(records, parsed)
        assertEquals(expected(records, parsed), got)

        List<Map> configured = (List<Map>) SourceFilterSupport.applyToRecords(records, parsed).get("configuredExclusions")
        configured.each { Map entry ->
            String id = "A:${entry.get('sequenceNum')}"
            assertEquals(entry.get("excludedCount"), got.count("${id}|VALUE".toString()), "${id} excluded")
            assertEquals(entry.get("fieldAbsentCount"), got.count("${id}|FIELD_ABSENT".toString()), "${id} field absent")
        }
        assertEquals([2, 2, 0], configured.collect { it.get("excludedCount") + it.get("fieldAbsentCount") })
    }

    // ------------------------------------------------------------------------------ the text a filter compares

    @Test
    void numbersCompareByTheirGroovyText() {
        // JsonOutput writes a number's toString(), JsonRecordLines keeps that text, and Groovy compared it as text:
        // 24.00 is not 24.0, a Double 1.0E7 is "1.0E7", a BigDecimal 1.0E+7 is "1.0E+7", and neither is 10000000.
        List<Map> records = [[id: "1", v: new BigDecimal("24.00")], [id: "2", v: 24.0d], [id: "3", v: 24], [id: "4", v: 1.0E7d],
                             [id: "5", v: new BigDecimal("1.0E+7")], [id: "6", v: 10000000], [id: "7", v: new BigDecimal("24.0")]]
        // Double 24.0d and BigDecimal 24.0 both read "24.0"; BigDecimal 24.00 and the integer 24 do not
        assertEquals([null, "A:1|VALUE", null, null, null, null, "A:1|VALUE"], assertParity(records, [row(1, "v", "EXCLUDE_IN", "24.0")]))
        assertEquals([null, null, null, "A:1|VALUE", null, null, null], assertParity(records, [row(1, "v", "EXCLUDE_IN", "1.0E7")]))
        assertEquals([null, null, null, null, "A:1|VALUE", null, null], assertParity(records, [row(1, "v", "EXCLUDE_IN", "1.0e+7")]))
        assertEquals([null, null, null, null, null, "A:1|VALUE", null], assertParity(records, [row(1, "v", "EXCLUDE_IN", "10000000")]))
        // include direction: only the text 24.00 stays; every other number is set aside, as evaluate says
        assertEquals([null, "A:1|VALUE", "A:1|VALUE", "A:1|VALUE", "A:1|VALUE", "A:1|VALUE", "A:1|VALUE"],
                assertParity(records, [row(1, "v", "INCLUDE_IN", "24.00")]))
    }

    @Test
    void booleansAndNestedValuesCompareByToString() {
        // Lists only. A nested OBJECT is not pinned here: Groovy's Map.toString() is "[a:1]" for the extractor's in-memory map, and the
        // Keep text JsonRecordLines writes for it is "{a=1}" (JsonSlurper's map); see the Task 5 report.
        List<Map> records = [[id: "1", v: true], [id: "2", v: false], [id: "3", v: [1, 2]], [id: "4", v: [1, "x", 2.50, true, null]],
                             [id: "5", v: "true"], [id: "6", v: []], [id: "7", v: [[1, 2], [3]]]]
        assertEquals(["A:1|VALUE", null, null, null, "A:1|VALUE", null, null], assertParity(records, [row(1, "v", "EXCLUDE_IN", "true")]))
        assertEquals([null, null, "A:1|VALUE", null, null, null, null], assertParity(records, [row(1, "v", "EXCLUDE_IN", ["[1, 2]"])]))
        assertEquals([null, null, null, "A:1|VALUE", null, null, null], assertParity(records, [row(1, "v", "EXCLUDE_IN", ["[1, x, 2.50, true, null]"])]))
        assertEquals([null, null, null, null, null, null, "A:1|VALUE"], assertParity(records, [row(1, "v", "EXCLUDE_IN", ["[[1, 2], [3]]"])]))
        assertParity(records, [row(1, "v", "INCLUDE_IN", "[],false")])
        assertParity(records, [row(1, "v", "INCLUDE_PREFIX", "[")])
    }

    @Test
    void whitespaceAndCaseFoldLikeEvaluate() {
        List<Map> records = [[id: "1", city: "\tstrasse\n"], [id: "2", city: "STRASSE"], [id: "3", city: "Straße"], [id: "4", city: "\u000Bıd\r"],
                             [id: "5", city: "id"], [id: "6", city: "ID"], [id: "7", city: "\t\n "], [id: "8", city: "İD"], [id: "9", city: " id"],
                             [id: "10", city: "line\nbreak"], [id: "11", city: "ǅ"]]
        for (String operator : ["EXCLUDE_IN", "INCLUDE_IN", "INCLUDE_PREFIX"]) {
            assertParity(records, [row(1, "city", operator, "Straße,ıd")])
            assertParity(records, [row(1, "city", operator, "strasse,ID,line\nbreak,ǆ,İ")])
        }
        // ß upper-cases to SS with Locale.ROOT, so "Straße" and "STRASSE" are one value, as in evaluate
        List<String> got = assertParity(records, [row(1, "city", "EXCLUDE_IN", "Straße")])
        assertEquals(["A:1|VALUE", "A:1|VALUE", "A:1|VALUE"], got.subList(0, 3))
    }

    @Test
    void keysDifferingOnlyInCaseNeverCollide() {
        // Spark resolves column names case-insensitively; evaluate reads the first key whose trimmed text is exactly the field.
        List<Map> records = [[id: "1", Status: "X", status: "A"], [id: "2", status: "A", Status: "X"], [id: "3", " status ": "A", status: "X"],
                             [id: "4", Status: "A"], [id: "5", STATUS: "A", status: "B"], [id: "6", " Status": "A", "status ": "B"]]
        for (String operator : ["EXCLUDE_IN", "INCLUDE_IN", "INCLUDE_PREFIX"]) {
            assertParity(records, [row(1, "status", operator, "A")])
            assertParity(records, [row(1, "Status", operator, "A")])
        }
        // the first key whose trimmed text is exactly "status" is read: records 1-3 read "A", the rest read Status, STATUS or "status " (never the field)
        assertEquals(["A:1|VALUE", "A:1|VALUE", "A:1|VALUE", null, null, null], assertParity(records, [row(1, "status", "EXCLUDE_IN", "A")]))
    }

    @Test
    void anExcludeRuleSkipsABlankAndAnIncludeRuleRejectsIt() {
        List<Map> records = [[id: "1", f: ""], [id: "2", f: "   "], [id: "3", f: null], [id: "4"], [id: "5", f: "\t"]]
        assertEquals([null, null, null, null, null], assertParity(records, [row(1, "f", "EXCLUDE_IN", "A")]))
        List<String> include = ["A:1|FIELD_ABSENT"] * 5
        assertEquals(include, assertParity(records, [row(1, "f", "INCLUDE_IN", "A")]))
        assertEquals(include, assertParity(records, [row(1, "f", "INCLUDE_PREFIX", "A")]))
    }

    @Test
    void theRulesTakeTheOneKeepShape() {
        List<Map> rules = SourceFilterSupport.toKeepRules(SourceFilterSupport.parseRules([row(3, "a", "EXCLUDE_IN", "x,y"), row(4, "b", "INCLUDE_IN", "p"),
                                                                                           row(5, "c", "INCLUDE_PREFIX", "EXC-")]))
        KeepRules.validate(rules)
        assertEquals(["A:3", "A:4", "A:5"], rules*.id)
        assertEquals([3, 4, 5], rules*.sequenceNum)
        assertEquals(["a", "b", "c"], rules*.label)
        assertEquals(["TENANT", "TENANT", "TENANT"], rules*.source)
        assertEquals([["VALUE"], ["FIELD_ABSENT", "VALUE"], ["FIELD_ABSENT", "VALUE"]], rules*.tests*.reason)
        assertEquals([kind: "compare", field: "a", text: "GROOVY", op: "IN", values: ["x", "y"], valueKind: "TEXT", fold: "A_UPPER"], rules[0].tests[0].test)
        assertEquals([op: "IS_NULL", values: null], [op: rules[1].tests[0].test.op, values: rules[1].tests[0].test.values])
        assertEquals("NOT_IN", rules[1].tests[1].test.op)
        assertEquals([kind: "not", of: [kind: "compare", field: "c", text: "GROOVY", op: "STARTS_WITH_ANY", values: ["EXC-"], valueKind: "TEXT", fold: "A_UPPER"]],
                rules[2].tests[1].test)
        assertTrue(SourceFilterSupport.toKeepRules(null).isEmpty())
        assertTrue(SourceFilterSupport.toKeepRules([]).isEmpty())
    }
}
