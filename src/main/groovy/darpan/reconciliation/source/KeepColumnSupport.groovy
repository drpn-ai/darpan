package darpan.reconciliation.source

import darpan.common.ValueSupport
import groovy.transform.CompileStatic
import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.api.java.UDF2
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.DecimalType
import org.moqui.rules.ValueChecks

import java.math.RoundingMode

/**
 * DAR-BE-318 (plan 3b-2b, Task 3). The one Spark column builder both Keep front-ends feed: a list of Keep rules (the
 * KeepRules shape) becomes one string Column, "<rule id>|<reason>" for the first rule test that holds, null for a kept
 * record. Every predicate runs in Spark over the Keep text columns (_darpanKeep_<n>, string, Ruling 3b2b-8); this class
 * holds no system name.
 *
 * A record's value. A null column is no value. A text value is folded first:
 * <ul>
 * <li>A_UPPER (front-end A, SourceFilterSupport.evaluate): ValueSupport.normalize (String.trim), then upper-cased with
 * Locale.ROOT; blank is no value.</li>
 * <li>NORMALIZE (normalizeText): ValueChecks.normalizedText.</li>
 * <li>LIKE_UPPER (like without normalizeText): upper-cased with Locale.ROOT, nothing trimmed.</li>
 * <li>NONE: the text as written; an empty text is a value.</li>
 * </ul>
 * A NUMBER value is try_cast to DECIMAL(38,18) (ANSI mode throws on a bad cast; a text that does not cast is no value). A
 * BOOLEAN value is true for true or y and false for false or n, any case, trimmed; anything else is no value. Listed
 * values are folded or converted the same way, on the driver.
 *
 * What holds for no value (moqui-rules 0.2.0 ValueChecks.fixedValue and Moqui's in-memory condition, compareByOperator,
 * where Groovy orders null before every value): EQ, IN, LIKE, STARTS_WITH_ANY, GT and GE do not; NE, NOT_IN, NOT_LIKE,
 * LT and LE do; IS_NULL does and IS_NOT_NULL does not. Every leaf answers true or false, never SQL null.
 *
 * Not lines are pushed to the leaves (Ruling 3b2b-13): each node builds matches and notMatches the way moqui-rules'
 * prepare#AllLine, AnyLine and NotLine do, a compare's notMatches being its opposite operator (LT and GE, GT and LE, EQ
 * and NE, IN and NOT_IN, LIKE and NOT_LIKE, IS_NULL and IS_NOT_NULL).
 */
@CompileStatic
class KeepColumnSupport {

    static final DecimalType NUMBER_TYPE = DataTypes.createDecimalType(38, 18)

    private static final String NOT_STARTS_WITH_ANY = "NOT_STARTS_WITH_ANY"
    private static final Map<String, String> OPPOSITE = [IN: "NOT_IN", NOT_IN: "IN", EQ: "NE", NE: "EQ", LT: "GE", GE: "LT",
            GT: "LE", LE: "GT", LIKE: "NOT_LIKE", NOT_LIKE: "LIKE", IS_NULL: "IS_NOT_NULL", IS_NOT_NULL: "IS_NULL",
            STARTS_WITH_ANY: NOT_STARTS_WITH_ANY, (NOT_STARTS_WITH_ANY): "STARTS_WITH_ANY"]
    /** Groovy orders null before every value, so these hold for no value. */
    private static final Set<String> HOLD_FOR_NO_VALUE = ["NE", "NOT_IN", "NOT_LIKE", NOT_STARTS_WITH_ANY, "LT", "LE"] as Set

    private static final UserDefinedFunction A_UPPER = functions.udf(new Fold("A_UPPER"), DataTypes.StringType)
    private static final UserDefinedFunction NORMALIZE = functions.udf(new Fold("NORMALIZE"), DataTypes.StringType)
    private static final UserDefinedFunction LIKE_UPPER = functions.udf(new Fold("LIKE_UPPER"), DataTypes.StringType)
    private static final UserDefinedFunction BOOLEAN = functions.udf(new YesNo(), DataTypes.StringType)
    private static final UserDefinedFunction LIKE = functions.udf(new Like(), DataTypes.BooleanType)
    private static final UserDefinedFunction TEXT_ORDER = functions.udf(new TextOrder(), DataTypes.IntegerType)

    /**
     * The set-aside reason. columnFor maps "<field>|<text>" (each (field, text) a compare names) to the column holding
     * that Keep text. Throws IllegalArgumentException, naming the rule, for an invalid rule list, a compare with no column,
     * or a listed value that does not fit the field's kind.
     */
    static Column setAsideReason(List<Map> rules, Map<String, String> columnFor) {
        return reason(rules) { String key ->
            String name = columnFor?.get(key)
            return name ? functions.col("`" + name.replace("`", "``") + "`") : null
        }
    }

    /**
     * df with REASON_COLUMN added. Each (field, text) reads from its injected column (KeepRules.injections); an injected
     * column the schema lacks reads as null and is not added.
     */
    static Dataset annotate(Dataset df, List<Map> rules) {
        KeepRules.validate(rules)
        Set<String> present = (df.columns() as List<String>) as Set<String>
        Map<String, Column> byKey = [:]
        for (Map injection : KeepRules.injections(rules)) {
            String column = injection.get("column") as String
            byKey.put("${injection.get('field')}|${injection.get('text')}".toString(),
                    present.contains(column) ? df.col("`" + column + "`").cast(DataTypes.StringType) : functions.lit(null).cast(DataTypes.StringType))
        }
        return df.withColumn(KeepRules.REASON_COLUMN, reason(rules) { String key -> byKey.get(key) })
    }

    private static Column reason(List<Map> rules, Closure<Column> columnOf) {
        KeepRules.validate(rules)
        Column chain = null
        for (Map rule : (rules ?: [])) {
            String id = rule.get("id") as String
            for (Object raw : (List) rule.get("tests")) {
                Map entry = (Map) raw
                Column holds = node((Map) entry.get("test"), columnOf, id)[0]
                Column label = functions.lit("${id}|${entry.get('reason')}".toString())
                chain = chain == null ? functions.when(holds, label) : chain.when(holds, label)
            }
        }
        return chain ?: functions.lit(null).cast(DataTypes.StringType)
    }

    /** [matches, notMatches] for one predicate. */
    private static List<Column> node(Map predicate, Closure<Column> columnOf, String ruleId) {
        switch (predicate.get("kind") as String) {
            case "compare":
                Column raw = columnOf.call("${predicate.get('field')}|${predicate.get('text')}".toString())
                if (raw == null) {
                    throw new IllegalArgumentException(("Keep rule ${ruleId}: no column holds field ${predicate.get('field')} " +
                            "(${predicate.get('text')} text).").toString())
                }
                Column value = valueOf(predicate, raw)
                String op = predicate.get("op") as String
                return [leaf(predicate, value, op, ruleId), leaf(predicate, value, OPPOSITE.get(op), ruleId)]
            case "all":
                List<List<Column>> all = ((List) predicate.get("of")).collect { node((Map) it, columnOf, ruleId) }
                return [joined(all.collect { it[0] }, true), joined(all.collect { it[1] }, false)]
            case "any":
                List<List<Column>> any = ((List) predicate.get("of")).collect { node((Map) it, columnOf, ruleId) }
                return [joined(any.collect { it[0] }, false), joined(any.collect { it[1] }, true)]
            case "not":
                List<Column> inner = node((Map) predicate.get("of"), columnOf, ruleId)
                return [inner[1], inner[0]]
            case "const":
                boolean holds = predicate.get("holds") as boolean
                return [functions.lit(holds), functions.lit(!holds)]
            default:
                throw new IllegalArgumentException("Keep rule ${ruleId} has an unknown predicate kind: ${predicate.get('kind')}.".toString())
        }
    }

    /** The record's value: cast for NUMBER and BOOLEAN, folded for TEXT; null is no value. */
    private static Column valueOf(Map predicate, Column raw) {
        switch (predicate.get("valueKind") as String) {
            case "NUMBER": return raw.try_cast(NUMBER_TYPE)
            case "BOOLEAN": return BOOLEAN.apply(raw)
            default:
                switch (predicate.get("fold") as String) {
                    case "A_UPPER": return A_UPPER.apply(raw)
                    case "NORMALIZE": return NORMALIZE.apply(raw)
                    case "LIKE_UPPER": return LIKE_UPPER.apply(raw)
                    default: return raw
                }
        }
    }

    /** One operator over the value; true or false, never null. */
    private static Column leaf(Map predicate, Column value, String op, String ruleId) {
        if (op == "IS_NULL") return value.isNull()
        if (op == "IS_NOT_NULL") return value.isNotNull()
        List<Column> listed = listedValues(predicate, ruleId)
        Column found
        switch (op) {
            case "EQ": case "NE": case "IN": case "NOT_IN":
                found = anyOf(listed.collect { Column one -> value.equalTo(one) }); break
            case "LIKE": case "NOT_LIKE":
                found = anyOf(listed.collect { Column one -> LIKE.apply(value, one) }); break
            case "STARTS_WITH_ANY": case NOT_STARTS_WITH_ANY:
                found = anyOf(listed.collect { Column one -> value.startsWith(one) }); break
            case "LT": case "LE": case "GT": case "GE":
                if (!listed) throw new IllegalArgumentException("Keep rule ${ruleId}: ${op} needs a value.".toString())
                found = ordered(predicate, value, op, listed[0]); break
            default:
                throw new IllegalArgumentException("Keep rule ${ruleId} has an unknown op: ${op}.".toString())
        }
        boolean turned = op in ["NE", "NOT_IN", "NOT_LIKE", NOT_STARTS_WITH_ANY]
        Column holds = turned ? functions.not(found) : found
        return functions.coalesce(holds, functions.lit(HOLD_FOR_NO_VALUE.contains(op)))
    }

    private static Column ordered(Map predicate, Column value, String op, Column one) {
        // Java String.compareTo (UTF-16 order), as Groovy compares text; Spark's own string order is by code point
        Column left = predicate.get("valueKind") == "TEXT" ? TEXT_ORDER.apply(value, one) : value
        Column right = predicate.get("valueKind") == "TEXT" ? functions.lit(0) : one
        switch (op) {
            case "LT": return left.lt(right)
            case "LE": return left.leq(right)
            case "GT": return left.gt(right)
            default: return left.geq(right)
        }
    }

    private static Column anyOf(List<Column> checks) {
        return checks ? joined(checks, false) : functions.lit(false)
    }

    private static Column joined(List<Column> columns, boolean and) {
        Column out = columns[0]
        for (int i = 1; i < columns.size(); i++) out = and ? out.and(columns[i]) : out.or(columns[i])
        return out
    }

    /** The listed values folded or converted as the record's value is; a value that folds to no value matches nothing. */
    private static List<Column> listedValues(Map predicate, String ruleId) {
        List<Column> out = []
        for (Object raw : ((List) predicate.get("values") ?: [])) {
            String text = raw?.toString()
            switch (predicate.get("valueKind") as String) {
                case "NUMBER":
                    BigDecimal number
                    try { number = new BigDecimal(text?.trim()) } catch (Exception ignored) { number = null }
                    if (number == null) throw new IllegalArgumentException("Keep rule ${ruleId}: ${raw} is not a number.".toString())
                    BigDecimal scaled = number.setScale(18, RoundingMode.HALF_UP)
                    if (scaled.precision() > 38) throw new IllegalArgumentException("Keep rule ${ruleId}: ${raw} does not fit DECIMAL(38,18).".toString())
                    out.add(functions.lit(scaled).cast(NUMBER_TYPE))
                    break
                case "BOOLEAN":
                    String yesNo = YesNo.convert(text)
                    if (yesNo == null) throw new IllegalArgumentException("Keep rule ${ruleId}: ${raw} is not yes or no.".toString())
                    out.add(functions.lit(yesNo))
                    break
                default:
                    String folded = Fold.fold(predicate.get("fold") as String, text)
                    if (folded != null) out.add(functions.lit(folded))
            }
        }
        return out
    }

    /** A text fold; deterministic and serializable. */
    static class Fold implements UDF1<String, String> {
        final String fold

        Fold(String fold) { this.fold = fold }

        String call(String value) { return fold(fold, value) }

        static String fold(String fold, String value) {
            switch (fold) {
                case "A_UPPER":
                    String text = ValueSupport.normalize(value)
                    return text ? text.toUpperCase(Locale.ROOT) : null
                case "NORMALIZE": return ValueChecks.normalizedText(value)
                case "LIKE_UPPER": return value?.toUpperCase(Locale.ROOT)
                default: return value
            }
        }
    }

    /** A yes/no as "true" or "false", from true, false, y or n in any case, trimmed; anything else is no value. */
    static class YesNo implements UDF1<String, String> {
        String call(String value) { return convert(value) }

        static String convert(String value) {
            switch (value?.trim()?.toLowerCase(Locale.ROOT)) {
                case "true": case "y": return "true"
                case "false": case "n": return "false"
                default: return null
            }
        }
    }

    /** ValueChecks.likeMatches over folded text and pattern; no value is like nothing. */
    static class Like implements UDF2<String, String, Boolean> {
        Boolean call(String text, String pattern) { return text != null && pattern != null && ValueChecks.likeMatches(text, pattern) }
    }

    /** Java String.compareTo; null when either side is no value. */
    static class TextOrder implements UDF2<String, String, Integer> {
        Integer call(String text, String other) { return text == null || other == null ? null : text.compareTo(other) }
    }
}
