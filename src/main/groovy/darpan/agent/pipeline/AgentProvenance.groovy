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

    /** The record-specific literals of a proposal, normalized. Unknown kinds throw (fail closed). */
    static Set<String> identifiersOf(String kind, Map after) {
        Set<String> out = new LinkedHashSet<String>()
        switch (kind) {
            case "EXCLUSION_FILTER":
                Object values = after?.get("filterValues")
                if (values instanceof Collection) ((Collection) values).each { Object v -> addId(out, v) }
                break
            case "CONCLUSION_ROWS":
                Object rows = after?.get("rows")
                if (rows instanceof Collection) ((Collection) rows).each { Object row ->
                    Object conds = row instanceof Map ? ((Map) row).get("conditions") : null
                    if (conds instanceof Collection) ((Collection) conds).each { Object c ->
                        Object cv = c instanceof Map ? ((Map) c).get("checkValue") : null
                        if (cv != null) cv.toString().split(",").each { String part -> addId(out, part) }
                    }
                }
                break
            case "FIX_TEXT":
            case "STATEMENT_NARROWING":
                break
            default:
                throw new IllegalArgumentException("Unknown proposal kind: ${kind}".toString())
        }
        return out
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

    /** Case-sensitive whole-token occurrence: the neighbours are the string edge or a character outside the token set. */
    private static boolean containsToken(String source, String id) {
        return Pattern.compile("(?<![" + TOKEN_CHARS + "])" + Pattern.quote(id) + "(?![" + TOKEN_CHARS + "])").matcher(source).find()
    }
}
