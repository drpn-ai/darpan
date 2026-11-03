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
    private static final String TOKEN_CHARS = "\\p{L}\\p{N}\\p{M}#_\\-."
    private static final int MAX_LEAF_TOKEN = 64
    private static final String UNTRUSTED_KEY = "untrusted_customer_text"
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z][A-Za-z_.]*\\}")
    private static final Pattern FREE_TEXT_TOKEN = Pattern.compile("[" + TOKEN_CHARS + "]+")
    private static final Pattern PIECE_GAP = Pattern.compile("[\\s/:+,\\-]+")

    /** Sentence punctuation that ends an id when followed by whitespace or the end of the text ("exclude M1.", "is M1? odd"). */
    private static final String TRAILING_PUNCT = "[.?!,:;)\\]\"']"

    /**
     * The record-specific literals of a proposal, normalized: filter values, condition check values (comma-split for IN/NOT_IN),
     * and digit-bearing tokens of 3+ characters in free text (fixActionTemplate, fieldLabel) outside {placeholders} (R19).
     * Validates the shape first; unknown kinds and malformed proposals throw.
     */
    static Set<String> identifiersOf(String kind, Map after) {
        validateShape(kind, after)
        Set<String> out = new LinkedHashSet<String>()
        if (kind == "EXCLUSION_FILTER") {
            ((List) after.get("filterValues")).each { Object v -> out.add((String) v) }
            if (after.get("fieldLabel") != null) freeTextIds(out, after.get("fieldLabel").toString())
        } else if (kind == "FIX_TEXT") {
            freeTextIds(out, (String) after.get("fixActionTemplate"))
        } else if (kind == "CONCLUSION_ROWS") {
            ((List) after.get("rows")).each { Object row ->
                Object tpl = ((Map) row).get("fixActionTemplate")
                if (tpl != null) freeTextIds(out, tpl.toString())
                Object conds = ((Map) row).get("conditions")
                if (conds == null) return
                ((List) conds).each { Object c ->
                    Object cv = ((Map) c).get("checkValue")
                    if (cv == null) return
                    String op = ((Map) c).get("operator")
                    if (op == "IN" || op == "NOT_IN") cv.toString().split(",", -1).each { String part -> out.add(part) }
                    else out.add((String) cv)
                }
            }
        }
        return out
    }

    /**
     * Free-text identifiers (R19). Only renderer placeholders ({name}, letters/underscore/dot, no digit) are skipped; any other
     * brace content is text. A digit-bearing piece of 3+ characters is an identifier; adjacent digit-bearing pieces separated by
     * whitespace or [/:+,-] join into ONE candidate ("77 77 777"); an email, a URL or a www. host is always an identifier.
     */
    private static void freeTextIds(Set<String> out, String text) {
        String plain = PLACEHOLDER.matcher(text).replaceAll(" ")
        plain.split(/\s+/).each { String word ->
            String w = word.replaceAll(/^[(\["'<]+|[.,;:!?)\]"'>]+$/, "")
            if (w && (w.contains("@") || w.contains("://") || w.toLowerCase().startsWith("www."))) out.add(w)
        }
        List<int[]> pieces = []   // [start, end) of each digit-bearing piece, after trimming a leading '-' and trailing '.'/'-'
        def m = FREE_TEXT_TOKEN.matcher(plain)
        while (m.find()) {
            int st = m.start(), en = m.end()
            while (st < en && plain.charAt(st) == ('-' as char)) st++
            while (en > st && (plain.charAt(en - 1) == ('.' as char) || plain.charAt(en - 1) == ('-' as char))) en--
            if (en > st && plain.substring(st, en).any { Character.isDigit(it as char) }) pieces.add([st, en] as int[])
            else pieces.add(null)
        }
        int i = 0
        while (i < pieces.size()) {
            if (pieces[i] == null) { i++; continue }
            int[] first = pieces[i]; int[] last = first; int j = i + 1
            while (j < pieces.size() && pieces[j] != null && PIECE_GAP.matcher(plain.substring(last[1], pieces[j][0])).matches()) { last = pieces[j]; j++ }
            String cand = plain.substring(first[0], last[1])
            if (cand.length() >= 3) out.add(cand)
            i = j
        }
    }

    /** Fail-closed contract for `after` (R18). Throws IllegalArgumentException on an unknown kind, unknown key, wrong type or missing required key. */
    static void validateShape(String kind, Map after) {
        if (after == null) throw new IllegalArgumentException("Proposal 'after' is missing")
        switch (kind) {
            case "EXCLUSION_FILTER":
                keys(after, ["fieldExpression", "operator", "filterValues", "fieldLabel"], "after")
                str(after, "fieldExpression", true, "after"); str(after, "operator", false, "after"); str(after, "fieldLabel", false, "after")
                freeText(after, "fieldLabel", "after")
                strList(after, "filterValues", true, "after")
                break
            case "STATEMENT_NARROWING":
                keys(after, ["diffTypes", "conclusionCodes", "ruleIds"], "after")
                ["diffTypes", "conclusionCodes", "ruleIds"].each { String k -> strList(after, k, false, "after") }
                if (["diffTypes", "conclusionCodes", "ruleIds"].every { String k -> !(after.get(k) instanceof List) || ((List) after.get(k)).isEmpty() })
                    throw new IllegalArgumentException("after needs at least one non-empty list of diffTypes, conclusionCodes, ruleIds")
                break
            case "FIX_TEXT":
                keys(after, ["fixActionTemplate"], "after")
                str(after, "fixActionTemplate", true, "after")
                freeText(after, "fixActionTemplate", "after")
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
                    freeText(r, "fixActionTemplate", where)
                    Object conds = r.get("conditions")
                    if (conds == null) return
                    if (!(conds instanceof List)) throw new IllegalArgumentException("${where}.conditions must be a list".toString())
                    ((List) conds).eachWithIndex { Object c, int j ->
                        String cw = "${where}.conditions[${j}]".toString()
                        if (!(c instanceof Map)) throw new IllegalArgumentException("${cw} must be a map".toString())
                        keys((Map) c, ["fieldExpression", "operator", "checkValue"], cw)
                        str((Map) c, "fieldExpression", true, cw); str((Map) c, "operator", true, cw); str((Map) c, "checkValue", false, cw)
                        Object cv = ((Map) c).get("checkValue")
                        if (cv != null) {
                            String op = ((Map) c).get("operator")
                            if (op == "IN" || op == "NOT_IN") cv.toString().split(",", -1).each { String part -> canonical(part, "${cw}.checkValue part".toString(), true) }
                            else canonical((String) cv, "${cw}.checkValue".toString(), true)
                        }
                    }
                }
                break
            default:
                throw new IllegalArgumentException("Unknown proposal kind: ${kind}".toString())
        }
    }

    /** An identifier-bearing string must already be in normalized, trimmed form, so the value checked is the value applied. */
    private static void canonical(String raw, String where, boolean notBlank) {
        if (notBlank && !raw.trim()) throw new IllegalArgumentException("${where} must not be blank".toString())
        if (raw != AgentRedaction.normalize(raw).trim())
            throw new IllegalArgumentException("${where} contains invisible, compatibility or surrounding-space characters".toString())
    }

    /** Free text reaches people as written, so it must already be in normalized form (no invisible characters, no fullwidth braces). */
    private static void freeText(Map m, String key, String where) {
        Object v = m.get(key)
        if (v != null && v.toString() != AgentRedaction.normalize(v.toString()))
            throw new IllegalArgumentException("${where}.${key} contains invisible or compatibility characters".toString())
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
        if (required && !((String) v).trim()) throw new IllegalArgumentException("${where}.${key} must not be blank".toString())
    }

    private static void strList(Map m, String key, boolean required, String where) {
        Object v = m.get(key)
        if (v == null) { if (required) throw new IllegalArgumentException("${where}.${key} is required".toString()); return }
        if (!(v instanceof List) || (required && ((List) v).isEmpty()) || !((List) v).every { it instanceof String })
            throw new IllegalArgumentException("${where}.${key} must be a ${required ? 'non-empty ' : ''}list of strings".toString())
        if (key == "filterValues") ((List) v).each { Object e -> canonical((String) e, "${where}.${key} entry".toString(), true) }
    }

    static Map check(String kind, Map after, List<String> humanTexts, Map openContext, Set<String> configValues) {
        Set<String> ids = identifiersOf(kind, after)
        List<String> human = new ArrayList<String>()
        humanTexts?.each { String t -> if (t != null) human.add(AgentRedaction.normalize(t)) }
        // Opened-finding and config values vouch only by whole-value equality; their free text never does.
        Set<String> exact = new HashSet<String>()
        collectLeaves(openContext, exact)
        configValues?.each { String v -> if (v != null) exact.add(AgentRedaction.normalize(v).trim()) }
        List<String> blocked = new ArrayList<String>(ids.findAll { String id ->
            if (exact.contains(id)) return false
            Pattern p = tokenPattern(id)
            return !human.any { String s -> p.matcher(s).find() }
        })
        return [status: blocked ? "BLOCKED" : "PASS", blockedIds: blocked]
    }

    /** Strict-token leaves only; customer free text (untrusted_customer_text subtrees, whitespace, long values) is never a source. */
    private static void collectLeaves(Object node, Set<String> leaves) {
        if (node == null) return
        if (node instanceof Map) {
            ((Map) node).each { Object k, Object v -> if (k?.toString() != UNTRUSTED_KEY) collectLeaves(v, leaves) }
        } else if (node instanceof Collection) {
            ((Collection) node).each { Object v -> collectLeaves(v, leaves) }
        } else {
            String leaf = AgentRedaction.normalize(node.toString()).trim()
            if (leaf && leaf.length() <= MAX_LEAF_TOKEN && !leaf.any { Character.isWhitespace(it as char) }) leaves.add(leaf)
        }
    }

    /** Case-sensitive whole-token pattern (human text only): the neighbours are the string edge or a character outside the token set; trailing sentence punctuation before whitespace/end also ends it. */
    private static Pattern tokenPattern(String id) {
        return Pattern.compile("(?<![" + TOKEN_CHARS + "])" + Pattern.quote(id) +
                "(?:(?![" + TOKEN_CHARS + "])|(?=" + TRAILING_PUNCT + "+(?:\\s|\\z)))")
    }
}
