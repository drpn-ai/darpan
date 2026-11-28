package darpan.idindex

import groovy.json.JsonSlurper

/**
 * DAR-BE-374: one record becomes one index row per (field, normalised value), keyed by the record's own normalised id.
 * Both entry points build rows the same way: {@link #rows} from a whole record (tests, small reads), {@link #rowsFromValues}
 * from the values Spark pulled out of a JSON line.
 */
class IdIndexRows {

    /**
     * The record's kind: the source's fixed kind; else the read's kind hint (a NetSuite run side's query record type), else the
     * record's recordKindField value, mapped by recordKindMapJson. Null skips the record.
     */
    static String recordKindOf(Map source, Object kindValue, String kindHint = null) {
        if (source.recordKind) return source.recordKind as String
        Object key = kindHint ?: (source.recordKindField ? kindValue : null)
        if (key == null) return null
        Map map = source.recordKindMapJson ? (Map) new JsonSlurper().parseText(source.recordKindMapJson as String) : [:]
        return map.get(key.toString()) as String
    }

    /** Rows from a whole record: the values each field path reaches ([*] lists are flattened). */
    static List<Map> rows(Map source, List<Map> allFields, Map record, String connectionId, String kindHint = null) {
        List<Map> fields = allFields.findAll { it.fieldPath && it.role }
        Map values = fields.collectEntries { Map f -> [(f.fieldPath as String): valuesAt(record, plainPath(f.fieldPath as String))] }
        return rowsFromValues(source, fields, [key: first(record, source.idField as String),
                kind: source.recordKindField ? first(record, source.recordKindField as String) : null,
                values: values], connectionId, kindHint)
    }

    /** Rows from extracted values: [key, kind, values: {fieldPath: [raw ...]}]. No key or no kind: no rows. */
    static List<Map> rowsFromValues(Map source, List<Map> fields, Map extracted, String connectionId, String kindHint = null) {
        String recordKey = IdNormalizer.normalize(extracted.key)
        String kind = recordKindOf(source, extracted.kind, kindHint)
        if (!recordKey || !kind) return []
        List<Map> out = []
        Set<String> seen = new HashSet<>()
        fields.each { Map f ->
            if (!f.fieldPath || !f.role) return
            if (f.recordKind && f.recordKind != kind) return
            ((Collection) (((Map) extracted.values).get(f.fieldPath) ?: [])).each { Object raw ->
                String norm = IdNormalizer.normalize(raw)
                if (!norm || !seen.add("${f.fieldPath}\u001F${norm}".toString())) return
                out << [systemCode: source.systemCode, systemEnumId: source.systemEnumId, connectionId: connectionId, recordKind: kind,
                        recordKey: recordKey, fieldPath: f.fieldPath, role: f.role, refRecordKind: f.refRecordKind,
                        idNorm: norm, idRaw: raw.toString().trim()]
            }
        }
        return out
    }

    /** The scalar values a dotted path reaches in a record; lists along the way are flattened. Blank values are left out. */
    private static List<String> valuesAt(Object node, String path) {
        List<Object> current = [node]
        for (String p : (path ?: "").tokenize(".")) {
            List<Object> next = []
            current.each { Object n ->
                Object v = n instanceof Map ? ((Map) n).get(p) : null
                if (v instanceof Collection) next.addAll((Collection) v) else if (v != null) next << v
            }
            current = next
        }
        return current.findAll { it != null && !(it instanceof Map) && !(it instanceof Collection) && it.toString().trim() }.collect { it.toString().trim() }.unique()
    }

    /** "lineItems[*].id" -> "lineItems.id" (valuesAt flattens lists by itself). */
    private static String plainPath(String fieldPath) { return (fieldPath ?: "").replace("[*]", "") }

    private static Object first(Map record, String path) {
        List<String> v = valuesAt(record, plainPath(path))
        return v ? v[0] : null
    }
}
