package darpan.common

import groovy.json.JsonOutput

/**
 * DAR-BE-318. The text a pair fact holds for a record value. A leaf: it imports nothing from core or judge, so the records
 * file writer (core) and the judge replay (judge) share it without a package cycle.
 */
class FactText {

    /**
     * A record as the pair fact holds it: text values, nested values as JSON text, nulls left out. A JSON number reads the same
     * from both paths: Spark renders a kept record's 24.00 as 24.0 (and 12345678.00 as 1.2345678E7) while a read-back record
     * keeps its raw 24.00, so every number is written as its plain value without trailing zeros (24, 12345678). Text, even
     * text that looks like a number, is left as it is: both paths carry a JSON string unchanged, and an id like 000123 is not
     * the number 123.
     */
    static Map<String, String> flat(Map record) {
        Map<String, String> out = new LinkedHashMap<>()
        (record ?: [:]).each { Object key, Object value ->
            if (value == null) return
            out.put(key as String, value instanceof Map || value instanceof List ? JsonOutput.toJson(value) :
                    value instanceof Number ? numberText((Number) value) : value.toString())
        }
        return out
    }

    /** OC219: bounded (BoundedDecimal), so a huge exponent keeps its own text instead of expanding; NaN and Infinity too. */
    private static String numberText(Number value) {
        return BoundedDecimal.plainText(value)
    }
}
