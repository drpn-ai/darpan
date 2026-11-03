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
        if (records == null) return out
        if (!(records instanceof Map)) { out.put("records", POPULATED); return out }
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

    /**
     * A strict token stays bare. Free text (long, or with whitespace) comes back as a structured value,
     * [untrusted_customer_text: "<text>"], so JSON encoding escapes it and it cannot close a fence early; control and format
     * characters (bidi, zero-width, tags) are stripped first.
     */
    private static Object fenceValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number) return v
        if (v instanceof Map || v instanceof Collection) return POPULATED
        String s = v.toString()
        if (isCustomerShape(s)) return POPULATED
        if (s.length() > MAX_TOKEN || s.any { Character.isWhitespace(it as char) }) {
            return [untrusted_customer_text: stripInvisible(s)] as Map
        }
        return v
    }

    static String stripInvisible(String s) {
        StringBuilder out = new StringBuilder()
        s.codePoints().each { int cp ->
            int type = Character.getType(cp)
            if (type == Character.FORMAT) return
            if (Character.isISOControl(cp) && cp != 10 && cp != 9) return
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /**
     * The finding as the agent sees it: an explicit field list, everything else dropped. Tenant-authored labels and rule text
     * stay as written; every value that comes from a system's data (headline, evidence values, expected and actual, fix text,
     * explanation, history) goes through safeValue, and records through redactFindingRecords.
     */
    static Map agentFindingView(Map document) {
        if (document == null) return null
        Map f = document.get("finding") instanceof Map ? (Map) document.get("finding") : document
        Map redacted = redactFindingRecords(f)
        Closure<Map> pick = { Object m, List<String> keys -> m instanceof Map ? keys.findAll { ((Map) m).containsKey(it) }.collectEntries { [(it): ((Map) m).get(it)] } : null }
        Map status = pick(f.get("status"), ["state", "firstFound", "lastSeen", "lastChecked", "ageDays", "timesSeen", "pendingUntil", "closedAt"])
        Map evidence = f.get("evidence") instanceof Map ? (Map) f.get("evidence") : [:]
        Map diagnosis = f.get("diagnosis") instanceof Map ? (Map) f.get("diagnosis") : [:]
        Map fix = f.get("fix") instanceof Map ? (Map) f.get("fix") : [:]
        Map view = [
                id       : safeValue(f.get("id")),
                headline : safeValue(f.get("headline")),
                period   : safeValue(f.get("period")),
                audit    : pick(f.get("audit"), ["id"]),
                category : pick(f.get("category"), ["id", "label"]),
                statement: pick(f.get("statement"), ["id", "text", "weight"]),
                check    : pick(f.get("check"), ["id", "label"]),
                status   : status == null ? null : status.collectEntries { k, v -> [(k): safeValue(v)] },
                records  : redacted.get("records"),
                evidence : [rule: evidence.get("rule"), expected: safeValue(evidence.get("expected")), actual: safeValue(evidence.get("actual")),
                            difference: safeValue(evidence.get("difference")),
                            checks: ((evidence.get("checks") instanceof List ? (List) evidence.get("checks") : []).findAll { it instanceof Map }.collect { Map c ->
                                [label: c.get("label"), passed: c.get("passed"), value: safeValue(c.get("value"))] as Map })],
                diagnosis: [conclusion: pick(diagnosis.get("conclusion"), ["code", "label"]), explanation: safeValue(diagnosis.get("explanation"))],
                fix      : [owner: fix.get("owner"), where: fix.get("where"), action: safeValue(fix.get("action"))],
        ]
        return document.get("finding") instanceof Map ? [schema: document.get("schema"), finding: view] : view
    }

    static boolean isCustomerShape(String text) {
        return KnowledgeLeakCheck.shapeHits(text).any { KnowledgeSupport.CUSTOMER_DATA_SHAPES.contains(it) }
    }
}
