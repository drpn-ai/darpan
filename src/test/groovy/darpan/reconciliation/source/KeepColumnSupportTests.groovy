package darpan.reconciliation.source

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructField
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.entity.EntityCondition
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.rules.ValueChecks

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 3). The one Spark column builder: Keep rules in, a set-aside reason per record out.
 * Rules are hand-built in the KeepRules shape over string columns, the way JsonRecordLines writes the Keep text. Where a
 * reference implementation exists (SourceFilterSupport.evaluate for front-end A, ValueChecks and Moqui's in-memory
 * condition for front-end B) the Spark answer is checked against it record by record. Made-up records only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepColumnSupportTests {

    private SparkSession spark = SparkSession.builder()
            .appName("keep-column-support")
            .master("local[1]")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.ansi.enabled", "true")
            .getOrCreate()

    @AfterAll
    void stopSpark() {
        spark?.stop()
    }

    @Test
    void theFirstRuleThatHoldsOwnsTheRecord() {
        List<Map> rules = [
                rule("A:1", [[cmp("status", "IN", ["cancelled"], [text: "GROOVY", fold: "A_UPPER"]), "VALUE"]]),
                rule("A:2", [[cmp("channel", "IS_NULL", null, [text: "GROOVY", fold: "A_UPPER"]), "FIELD_ABSENT"],
                             [cmp("channel", "NOT_IN", ["web"], [text: "GROOVY", fold: "A_UPPER"]), "VALUE"]]),
        ]
        Dataset<Row> df = frame(["s", "c"], [["CANCELLED", null], ["open", null], ["open", "pos"], ["open", "Web"]])
        Dataset<Row> out = df.withColumn(KeepRules.REASON_COLUMN,
                KeepColumnSupport.setAsideReason(rules, ["status|GROOVY": "s", "channel|GROOVY": "c"]))

        // both rules hold for the first record, and both of A:2's tests hold for the second: the first one listed wins
        assertEquals(["A:1|VALUE", "A:2|FIELD_ABSENT", "A:2|VALUE", null], reasons(out))
    }

    @Test
    void aRecordNoRuleHoldsForHasANullReason() {
        List<Map> rules = [rule("B:1", [[cmp("code", "EQ", ["X"]), "SKIP"]]),
                           rule("B:2", [[[kind: "const", holds: false], "NEVER"]])]
        assertEquals([null, "B:1|SKIP", null], reasonsOf(rules, [[code: "Y"], [code: "X"], [:]]))

        Dataset<Row> none = KeepColumnSupport.annotate(frame(["a"], [["1"]]), [])
        assertTrue(none.columns().contains(KeepRules.REASON_COLUMN))
        assertEquals([null], reasons(none))
    }

    @Test
    void aUpperTrimsJavaWhitespaceAndUpperCasesWithLocaleRoot() {
        List<String> values = ["Straße", "ıd"]
        List<Map> rules = [rule("A:1", [[cmp("city", "IN", values, [text: "GROOVY", fold: "A_UPPER"]), "VALUE"]]),
                           rule("A:2", [[cmp("city", "IS_NULL", null, [text: "GROOVY", fold: "A_UPPER"]), "FIELD_ABSENT"]])]
        List<String> cities = ["\tstrasse\n", "STRASSE", "\u000Bıd\r", "id", "strass", "  strasse", "\t\n ", null]
        List<String> got = reasonsOf(rules, cities.collect { [city: it] })

        assertEquals(["A:1|VALUE", "A:1|VALUE", "A:1|VALUE", "A:1|VALUE", null, null, "A:2|FIELD_ABSENT", "A:2|FIELD_ABSENT"], got)
        // the same answers as today's source filter (an exclude rule) for every value it sees
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: "city", operator: "EXCLUDE_IN", values: values]])
        cities.eachWithIndex { String city, int i ->
            boolean today = SourceFilterSupport.evaluate(city == null ? [:] : [city: city], parsed) != null
            assertEquals(today, got[i] == "A:1|VALUE", "city ${city?.inspect()}")
        }
    }

    @Test
    void startsWithAnyMatchesTheFoldedText() {
        // front-end A's include-prefix shape: no usable value is FIELD_ABSENT, a value that starts with none is VALUE
        List<Map> rules = [rule("A:1", [[cmp("name", "IS_NULL", null, [text: "GROOVY", fold: "A_UPPER"]), "FIELD_ABSENT"],
                                        [[kind: "not", of: cmp("name", "STARTS_WITH_ANY", ["exc-"], [text: "GROOVY", fold: "A_UPPER"])], "VALUE"]])]
        List<String> names = [" exc-#1", "EXC-2", "ord-3", "", null, "xexc-4"]
        List<String> got = reasonsOf(rules, names.collect { [name: it] })

        assertEquals([null, null, "A:1|VALUE", "A:1|FIELD_ABSENT", "A:1|FIELD_ABSENT", "A:1|VALUE"], got)
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: "name", operator: "INCLUDE_PREFIX", values: ["exc-"]]])
        names.eachWithIndex { String name, int i ->
            Map verdict = SourceFilterSupport.evaluate(name == null ? [:] : [name: name], parsed)
            assertEquals(verdict == null ? null : "A:1|${verdict.reason}".toString(), got[i], "name ${name?.inspect()}")
        }
    }

    @Test
    void aNotOverLessWithNoValueDoesNotHold() {
        // Ruling 3b2b-13: a not line is pushed to its leaves; not over < is >=, and no value is not >= anything
        List<Map> rules = [rule("B:1", [[[kind: "not", of: cmp("amount", "LT", ["5"], [valueKind: "NUMBER"])], "BIG"]]),
                           rule("B:2", [[cmp("amount", "LT", ["5"], [valueKind: "NUMBER"]), "SMALL"]])]
        List<String> got = reasonsOf(rules, [[:], [amount: "3"], [amount: "5"], [amount: "7.50"]])

        assertEquals(["B:2|SMALL", "B:2|SMALL", "B:1|BIG", "B:1|BIG"], got)
        // Moqui's in-memory condition, which moqui-rules judges compare lines with: no value orders before every value
        assertFalse(EntityConditionFactoryImpl.compareByOperator(null, EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, 5G))
        assertTrue(EntityConditionFactoryImpl.compareByOperator(null, EntityCondition.ComparisonOperator.LESS_THAN, 5G))

        // the same through an all: not (amount >= 1 and amount < 5) holds for no value only through its turned-around leaves
        List<Map> between = [rule("B:3", [[[kind: "not", of: [kind: "all", of: [cmp("amount", "GE", ["1"], [valueKind: "NUMBER"]),
                                                                                 cmp("amount", "LT", ["5"], [valueKind: "NUMBER"])]]], "OUTSIDE"]])]
        assertEquals(["B:3|OUTSIDE", null, "B:3|OUTSIDE", "B:3|OUTSIDE"], reasonsOf(between, [[:], [amount: "3"], [amount: "5"], [amount: "0.5"]]))
    }

    @Test
    void lessAndGreaterWithNoValue() {
        // moqui-rules judges a plain compare line with Moqui's in-memory condition, where Groovy orders null before
        // every value: no value holds for < and <=, not for > or >=. Spark's own compare would give null (not kept).
        Map<String, EntityCondition.ComparisonOperator> moqui = [LT: EntityCondition.ComparisonOperator.LESS_THAN,
                LE: EntityCondition.ComparisonOperator.LESS_THAN_EQUAL_TO, GT: EntityCondition.ComparisonOperator.GREATER_THAN,
                GE: EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO]
        for (String kind : ["NUMBER", "TEXT"]) {
            for (String op : moqui.keySet()) {
                List<String> got = reasonsOf([rule("B:1", [[cmp("v", op, ["5"], [valueKind: kind]), "HIT"]])],
                        [[:], [v: null], [v: kind == "NUMBER" ? "not a number" : "5"]])
                boolean expected = EntityConditionFactoryImpl.compareByOperator(null, moqui[op], kind == "NUMBER" ? 5G : "5")
                assertEquals(op in ["LT", "LE"], expected, "${op} on no value in moqui")
                assertEquals(expected, got[0] != null, "${op} ${kind} on a missing column value")
                assertEquals(expected, got[1] != null, "${op} ${kind} on a null column value")
                if (kind == "NUMBER") assertEquals(expected, got[2] != null, "${op} on a number that does not cast")
                else assertEquals(op in ["LE", "GE"], got[2] != null, "${op} on an equal text")
            }
        }
        // a column the schema lacks is no value too
        assertEquals(["B:1|HIT"], reasonsOf([rule("B:1", [[cmp("v", "LE", ["5"], [valueKind: "NUMBER"]), "HIT"]])], [[:]], ["v"] as Set))
        assertEquals([null], reasonsOf([rule("B:1", [[cmp("v", "GT", ["5"], [valueKind: "NUMBER"]), "HIT"]])], [[:]], ["v"] as Set))
    }

    @Test
    void notEqualsAndNotInHoldForNoValue() {
        List<String> codes = [null, "", "  ", "x", "X", " X "]
        Map<String, String> kinds = [EQ: "=", NE: "!=", IN: "in", NOT_IN: "not in"]
        for (String fold : ["NONE", "NORMALIZE"]) {
            for (String op : kinds.keySet()) {
                List<String> got = reasonsOf([rule("B:1", [[cmp("code", op, ["X"], [fold: fold]), "HIT"]])], codes.collect { [code: it] })
                codes.eachWithIndex { String code, int i ->
                    boolean expected = ValueChecks.fixedValue(kinds[op], code, ["X"], fold == "NORMALIZE")
                    assertEquals(expected, got[i] != null, "${op} ${fold} on ${code?.inspect()}")
                }
                if (op in ["NE", "NOT_IN"]) assertEquals("B:1|HIT", got[0], "${op} ${fold} holds for no value")
                else assertNull(got[0], "${op} ${fold} does not hold for no value")
            }
        }
        // without normalizeText an empty text is a value; with it a blank text is no value
        assertEquals([null, "B:1|NULL"], reasonsOf([rule("B:1", [[cmp("code", "IS_NULL", null), "NULL"]])], [[code: ""], [:]]))
    }

    @Test
    void likeUsesValueChecksLikeMatches() {
        List<List> cases = [
                ["_", "NONE", ["😀", "a", "ab", "", null]],        // _ is one UTF-16 unit: an emoji is two
                ["__", "NONE", ["😀", "ab", "a"]],
                ["A\\B", "NONE", ["a\\b", "aXb", "A\\\\B"]],                 // no escape character: \ is itself
                ["A\\%", "NONE", ["a\\xyz", "a\\", "axyz"]],
                ["A%B", "NONE", ["a\nb", "a\r\n\nB", "ab", "a\nbc"]],         // % runs over line breaks
                [" ab%", "NONE", [" abcd", "abcd", " AB"]],                  // without normalizeText nothing is trimmed
                [" ab%", "NORMALIZE", [" abcd ", "\tABx", "xab", "  "]],
                ["straße", "NONE", ["STRASSE", "strasse", "straße"]],         // upper-cased with Locale.ROOT on both sides
        ]
        for (List one : cases) {
            String pattern = one[0], fold = one[1] == "NORMALIZE" ? "NORMALIZE" : "LIKE_UPPER"
            List<String> texts = (List<String>) one[2]
            for (String op : ["LIKE", "NOT_LIKE"]) {
                String kind = op == "LIKE" ? "like" : "not like"
                List<String> got = reasonsOf([rule("B:1", [[cmp("note", op, [pattern], [fold: fold]), "HIT"]])], texts.collect { [note: it] })
                texts.eachWithIndex { String text, int i ->
                    boolean expected = ValueChecks.fixedValue(kind, text, [pattern], fold == "NORMALIZE")
                    assertEquals(expected, got[i] != null, "${op} ${pattern.inspect()} (${fold}) on ${text?.inspect()}")
                }
            }
        }
        assertEquals(["B:1|HIT"], reasonsOf([rule("B:1", [[cmp("note", "NOT_LIKE", ["%"], [fold: "LIKE_UPPER"]), "HIT"]])], [[:]]))
    }

    @Test
    void aNumberThatDoesNotCastIsNoValue() {
        assertEquals("true", spark.conf().get("spark.sql.ansi.enabled"))
        List<Map> rules = [rule("B:1", [[cmp("amount", "EQ", ["24"], [valueKind: "NUMBER"]), "TWENTY_FOUR"]]),
                           rule("B:2", [[cmp("amount", "IS_NULL", null, [valueKind: "NUMBER"]), "NO_VALUE"]]),
                           rule("B:3", [[cmp("amount", "IN", ["2.5", "9"], [valueKind: "NUMBER"]), "LISTED"]]),
                           rule("B:4", [[cmp("amount", "GT", ["1"], [valueKind: "NUMBER"]), "OVER_ONE"]])]
        List<Map> records = [[amount: "24.00"], [amount: "abc"], [amount: ""], [amount: "1e400"], [amount: "2.50"],
                             [amount: "1.5"], [amount: "0.5"], [:]]
        assertEquals(["B:1|TWENTY_FOUR", "B:2|NO_VALUE", "B:2|NO_VALUE", "B:2|NO_VALUE", "B:3|LISTED", "B:4|OVER_ONE", null, "B:2|NO_VALUE"],
                reasonsOf(rules, records))

        // yes/no compares by its meaning: y, Y and true are the same value
        List<Map> yesNo = [rule("B:5", [[cmp("flag", "EQ", ["true"], [valueKind: "BOOLEAN"]), "YES"]]),
                           rule("B:6", [[cmp("flag", "IS_NULL", null, [valueKind: "BOOLEAN"]), "NO_VALUE"]])]
        assertEquals(["B:5|YES", "B:5|YES", null, "B:6|NO_VALUE", "B:6|NO_VALUE"],
                reasonsOf(yesNo, [[flag: "true"], [flag: " Y "], [flag: "n"], [flag: "maybe"], [:]]))
    }

    @Test
    void anInjectedColumnTheSchemaLacksReadsAsNull() {
        List<Map> rules = [rule("A:1", [[cmp("channel", "IN", ["pos"], [text: "GROOVY", fold: "A_UPPER"]), "VALUE"]]),
                           rule("A:2", [[cmp("region", "IS_NULL", null, [text: "GROOVY", fold: "A_UPPER"]), "FIELD_ABSENT"]]),
                           rule("A:3", [[cmp("region", "IN", ["north"], [text: "GROOVY", fold: "A_UPPER"]), "VALUE"]])]
        List<String> got = reasonsOf(rules, [[channel: "POS"], [channel: "web"]], ["region"] as Set)
        assertEquals(["A:1|VALUE", "A:2|FIELD_ABSENT"], got)

        // the lacking column is read as null, not added to the records
        String regionColumn = KeepRules.injections(rules).find { it.field == "region" }.column
        Dataset<Row> df = frame([KeepRules.injections(rules).find { it.field == "channel" }.column], [["pos"]])
        List<String> columns = KeepColumnSupport.annotate(df, rules).columns() as List
        assertFalse(columns.contains(regionColumn))
        assertEquals((df.columns() as List) + [KeepRules.REASON_COLUMN], columns)
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private static Map cmp(String field, String op, List values, Map opts = [:]) {
        return [kind: "compare", field: field, text: opts.text ?: "FACT", op: op, values: values,
                valueKind: opts.valueKind ?: "TEXT", fold: opts.fold ?: "NONE"]
    }

    private static Map rule(String id, List<List> tests) {
        String source = id.startsWith("A:") ? "TENANT" : "DECISION"
        return [id: id, sequenceNum: 1, label: id, source: source, tests: tests.collect { [test: it[0], reason: it[1]] }]
    }

    /** String columns named as given, plus a row number n to read the answers back in order. */
    private Dataset<Row> frame(List<String> columns, List<List<String>> values) {
        List<StructField> fields = [DataTypes.createStructField("n", DataTypes.IntegerType, false)] +
                columns.collect { DataTypes.createStructField(it, DataTypes.StringType, true) }
        List<Row> rows = values.withIndex().collect { List<String> row, int i -> RowFactory.create(([i] + row) as Object[]) }
        return spark.createDataFrame(rows, DataTypes.createStructType(fields))
    }

    private static List<String> reasons(Dataset<Row> out) {
        return out.orderBy("n").collectAsList().collect { Row row -> row.getAs(KeepRules.REASON_COLUMN) as String }
    }

    /** Records keyed by field, laid out as the records file lays them out (one _darpanKeep_<n> per field), then annotated. */
    private List<String> reasonsOf(List<Map> rules, List<Map> records, Set<String> lacking = [] as Set) {
        List<Map> injections = KeepRules.injections(rules).findAll { !(it.field in lacking) }
        Dataset<Row> df = frame(injections.collect { it.column as String },
                records.collect { Map record -> injections.collect { record.get(it.field) as String } })
        return reasons(KeepColumnSupport.annotate(df, rules))
    }
}
