package darpan.agent.pipeline

import darpan.agent.AgentRedaction

import java.util.regex.Pattern

/**
 * DAR-BE-159, spec 5.3 ("CaMeL-lite"). Before a proposed audit-brain change reaches a human, every record-specific literal in it
 * must have come from the human, the opened finding/statement, or the tenant's current config for that lever, never only from
 * tool data an outsider can influence. Pure and deterministic. Ids and sources are compared after AgentRedaction.normalize
 * (invisible characters stripped, NFKC), so a split or fullwidth variant can neither pass nor be smuggled.
 */
class AgentProvenance {

    /** Characters that continue a token; an id matches only when neither neighbour is one of these. */
    private static final String TOKEN_CHARS = "A-Za-z0-9#_\\-."

    /** Sentence punctuation that ends an id when followed by whitespace or the end of the text ("exclude M1.", "is M1? odd"). */
    private static final String TRAILING_PUNCT = "[.?!,:;)\\]\"']"

    /** The record-specific literals of a proposal, normalized. Validates the shape first; unknown kinds and malformed proposals throw. */
    static Set<String> identifiersOf(String kind, Map after) {
        validateShape(kind, after)
        Set<String> out = new LinkedHashSet<String>()
        if (kind == "EXCLUSION_FILTER") {
            ((List) after.get("filterValues")).each { Object v -> addId(out, v) }
        } else if (kind == "CONCLUSION_ROWS") {
            ((List) after.get("rows")).each { Object row ->
                Object conds = ((Map) row).get("conditions")
                if (conds == null) return
                ((List) conds).each { Object c ->
                    Object cv = ((Map) c).get("checkValue")
                    if (cv == null) return
                    String op = ((Map) c).get("operator")
                    if (op == "IN" || op == "NOT_IN") cv.toString().split(",").each { String part -> addId(out, part) }
                    else addId(out, cv)
                }
            }
        }
        return out
    }

    /** Fail-closed contract for `after` (R18). Throws IllegalArgumentException on an unknown kind, unknown key, wrong type or missing required key. */
    static void validateShape(String kind, Map after) {
        if (after == null) throw new IllegalArgumentException("Proposal 'after' is missing")
        switch (kind) {
            case "EXCLUSION_FILTER":
                keys(after, ["fieldExpression", "operator", "filterValues", "fieldLabel"], "after")
                str(after, "fieldExpression", true, "after"); str(after, "operator", false, "after"); str(after, "fieldLabel", false, "after")
                strList(after, "filterValues", true, "after")
                break
            case "STATEMENT_NARROWING":
                keys(after, ["diffTypes", "conclusionCodes", "ruleIds"], "after")
                ["diffTypes", "conclusionCodes", "ruleIds"].each { String k -> strList(after, k, false, "after") }
                if (["diffTypes", "conclusionCodes", "ruleIds"].every { String k -> after.get(k) == null })
                    throw new IllegalArgumentException("after needs at least one of diffTypes, conclusionCodes, ruleIds")
                break
            case "FIX_TEXT":
                keys(after, ["fixActionTemplate"], "after")
                str(after, "fixActionTemplate", true, "after")
                break
            case "CONCLUSION_ROWS":
                keys(after, ["rows"], "after")
                Object rows = after.get("rows")
                if (!(rows instanceof List) || ((List) rows).isEmpty()) throw new IllegalArgumentException("after.rows must be a non-empty list")
                ((List) rows).eachWithIndex { Object row, int i ->
                    String where = "after.rows[${i}]".toString()
                    if (!(row instanceof Map)) throw new IllegalArgumentException("${where} must be a map".toString())
                    Map r = (Map) row
                    keys(r, ["conclusionCode", "parentConclusionCode", "bucket", "fixActionTemplate", "conditions"], where)
                    str(r, "conclusionCode", true, where)
                    ["parentConclusionCode", "bucket", "fixActionTemplate"].each { String k -> str(r, k, false, where) }
                    Object conds = r.get("conditions")
                    if (conds == null) return
                    if (!(conds instanceof List)) throw new IllegalArgumentException("${where}.conditions must be a list".toString())
                    ((List) conds).eachWithIndex { Object c, int j ->
                        String cw = "${where}.conditions[${j}]".toString()
                        if (!(c instanceof Map)) throw new IllegalArgumentException("${cw} must be a map".toString())
                        keys((Map) c, ["fieldExpression", "operator", "checkValue"], cw)
                        str((Map) c, "fieldExpression", true, cw); str((Map) c, "operator", true, cw); str((Map) c, "checkValue", false, cw)
                    }
                }
                break
            default:
                throw new IllegalArgumentException("Unknown proposal kind: ${kind}".toString())
        }
    }

    private static void keys(Map m, List<String> allowed, String where) {
        m.keySet().each { Object k ->
            if (!allowed.contains(k)) throw new IllegalArgumentException("Unknown key '${k}' in ${where}".toString())
        }
    }

    private static void str(Map m, String key, boolean required, String where) {
        Object v = m.get(key)
        if (v == null) { if (required) throw new IllegalArgumentException("${where}.${key} is required".toString()); return }
        if (!(v instanceof String)) throw new IllegalArgumentException("${where}.${key} must be a string".toString())
    }

    private static void strList(Map m, String key, boolean required, String where) {
        Object v = m.get(key)
        if (v == null) { if (required) throw new IllegalArgumentException("${where}.${key} is required".toString()); return }
        if (!(v instanceof List) || (required && ((List) v).isEmpty()) || !((List) v).every { it instanceof String })
            throw new IllegalArgumentException("${where}.${key} must be a ${required ? 'non-empty ' : ''}list of strings".toString())
    }

    static Map check(String kind, Map after, List<String> humanTexts, Map openContext, Set<String> configValues) {
        Set<String> ids = identifiersOf(kind, after)
        List<String> sources = new ArrayList<String>()
        humanTexts?.each { String t -> if (t != null) sources.add(AgentRedaction.normalize(t)) }
        collectLeaves(openContext, sources)
        configValues?.each { String v -> if (v != null) sources.add(AgentRedaction.normalize(v)) }
        List<String> blocked = new ArrayList<String>(ids.findAll { String id -> !sources.any { String s -> containsToken(s, id) } })
        return [status: blocked ? "BLOCKED" : "PASS", blockedIds: blocked]
    }

    private static void addId(Set<String> out, Object raw) {
        if (raw == null) return
        String id = AgentRedaction.normalize(raw.toString()).trim()
        if (id) out.add(id)
    }

    private static void collectLeaves(Object node, List<String> sources) {
        if (node == null) return
        if (node instanceof Map) ((Map) node).values().each { Object v -> collectLeaves(v, sources) }
        else if (node instanceof Collection) ((Collection) node).each { Object v -> collectLeaves(v, sources) }
        else sources.add(AgentRedaction.normalize(node.toString()))
    }

    /** Case-sensitive whole-token occurrence: the neighbours are the string edge or a character outside the token set; trailing sentence punctuation before whitespace/end also ends it. */
    private static boolean containsToken(String source, String id) {
        return Pattern.compile("(?<![" + TOKEN_CHARS + "])" + Pattern.quote(id) +
                "(?:(?![" + TOKEN_CHARS + "])|(?=" + TRAILING_PUNCT + "+(?:\\s|\\z)))").matcher(source).find()
    }
}
