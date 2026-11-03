package darpan.agent

import darpan.knowledge.KnowledgeLeakCheck
import darpan.knowledge.KnowledgeSupport

import java.util.regex.Pattern

/**
 * DAR-BE-159. What the embedded agent may see of a record. Two shapes of redaction, both fail-closed:
 *   redactRecord        a catalog read (inspect#AgentRecord): only the catalog's allowed fields; a customer-data shape
 *                       (KnowledgeSupport.CUSTOMER_DATA_SHAPES, matched by KnowledgeLeakCheck) is "<populated>"; free text
 *                       is kept but fenced as untrusted so the model reads it as data, never as an instruction.
 *   redactFindingRecords a finding document's records (record facts and the whole recordData): codes, numbers, booleans,
 *                       dates and short id-like text survive; anything else is "<populated>".
 */
class AgentRedaction {

    static final String POPULATED = "<populated>"
    static final int MAX_TOKEN = 64
    private static final Pattern TOKEN = Pattern.compile(/^[A-Za-z0-9#_\-.:\/]{1,64}$/)

    static Map redactRecord(Map record, Set<String> allowedFields) {
        Map out = new LinkedHashMap()
        if (record == null || !allowedFields) return out
        record.each { Object k, Object v ->
            if (!allowedFields.contains(k?.toString())) return
            out.put(k, fenceValue(v))
        }
        return out
    }

    /** The finding document with every record's fields reduced to what carries no customer text. The input is not changed. */
    static Map redactFindingRecords(Map finding) {
        if (finding == null) return null
        Map out = new LinkedHashMap(finding)
        // The assembler's document wraps the finding: {schema, finding: {..., records}}.
        if (finding.get("records") == null && finding.get("finding") instanceof Map) {
            out.put("finding", redactFindingRecords((Map) finding.get("finding")))
            return out
        }
        Object records = finding.get("records")
        if (!(records instanceof Map)) return out
        Map redacted = new LinkedHashMap()
        ((Map) records).each { Object system, Object byType ->
            if (!(byType instanceof Map)) { redacted.put(system, POPULATED); return }
            Map types = new LinkedHashMap()
            ((Map) byType).each { Object type, Object list ->
                types.put(type, list instanceof List ? ((List) list).collect { Object rec -> redactFindingRecord(rec) } : POPULATED)
            }
            redacted.put(system, types)
        }
        out.put("records", redacted)
        return out
    }

    private static Object redactFindingRecord(Object rec) {
        if (!(rec instanceof Map)) return POPULATED
        Map out = new LinkedHashMap()
        ((Map) rec).each { Object k, Object v -> out.put(k, safeValue(v)) }
        return out
    }

    /** Booleans, numbers, ISO dates and id-like tokens that are not a customer-data shape stay; maps and lists keep their shape. */
    static Object safeValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number) return v
        if (v instanceof Map) {
            Map out = new LinkedHashMap()
            ((Map) v).each { Object k, Object x -> out.put(k, safeValue(x)) }
            return out
        }
        if (v instanceof Collection) return ((Collection) v).collect { Object x -> safeValue(x) }
        String s = v.toString()
        return TOKEN.matcher(s).matches() && !isCustomerShape(s) ? v : POPULATED
    }

    private static Object fenceValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number) return v
        if (v instanceof Map || v instanceof Collection) return POPULATED
        String s = v.toString()
        if (isCustomerShape(s)) return POPULATED
        if (s.length() > MAX_TOKEN || s.any { Character.isWhitespace(it as char) }) return "[untrusted_customer_text: ${s}]".toString()
        return v
    }

    static boolean isCustomerShape(String text) {
        return KnowledgeLeakCheck.shapeHits(text).any { KnowledgeSupport.CUSTOMER_DATA_SHAPES.contains(it) }
    }
}
