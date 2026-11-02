package darpan.script

import groovy.transform.CompileStatic

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Matcher

/**
 * DAR-BE-175: refuses common mistakes before a JEXL script is saved, with the line and a fix. Built from what genuine
 * users did on 2026-11-02 (a loop that never counts up, a misspelt field) and other common slips.
 *
 * <p>Without running anything (on the source with text literals and comments blanked, so words inside them never
 * count): ENDLESS_LOOP (while (true) with no break/return), NO_PROGRESS (a while whose condition names nothing its
 * body changes), UNKNOWN_FIELD (a .field found nowhere in the sample input and not made by the script; reading it
 * with ?? or ?. marks it optional), UNKNOWN_FUNCTION (d:name not in the library), ASSIGN_IN_CONDITION (if (x = 1)).
 * Then one trial run on the sample input: FAILS_ON_SAMPLE (stopped or errored), TOO_HEAVY (over half its unit
 * budget on the sample; real data is usually bigger). The trial run goes through ScriptSupervisor.evaluate, so it
 * runs last like any script: while Darpan is busy it is skipped with a note, never a refusal.</p>
 */
@CompileStatic
class ScriptChecker {
    static final double HEAVY_SHARE = 0.5d
    private static final Set<String> LIBRARY = ScriptLibrary.declaredMethods
            .findAll { Method m -> Modifier.isPublic(m.modifiers) && Modifier.isStatic(m.modifiers) }
            .collect { Method m -> m.name }.toSet()

    /** {ok, problems: [{rule, line, message}], notes: [String]}. */
    static Map<String, Object> check(Object profile, String body, String sampleJson) {
        String p = ScriptSupervisor.profileOf(profile) ?: ScriptSupervisor.SCRIPT
        String source = body ?: ''
        String code = mask(source)
        List<Map<String, Object>> problems = []
        List<String> notes = []

        loops(source, code, problems)
        functions(code, problems)
        assignInCondition(code, problems)

        Object sample = null
        if (sampleJson?.trim()) {
            try { sample = ScriptSupervisor.parseInputJson(sampleJson) } catch (IllegalArgumentException ignored) { sample = null }
        }
        if (sample != null) fields(source, code, sample, problems)

        // Only a script with no static problem gets a trial run (a NO_PROGRESS loop would just burn its budget).
        if (problems.isEmpty() && sample != null) {
            Map<String, Object> run = ScriptSupervisor.evaluate(p, source, sample)
            Map error = run.error as Map
            if (!(run.ok as boolean) && error?.kind == 'BUSY') {
                notes << 'The trial run on the sample input was skipped because Darpan is busy; the script was checked without it.'
            } else if (!(run.ok as boolean)) {
                problems << problem('FAILS_ON_SAMPLE', error?.line as Integer,
                        "On its own sample input the script stops: ${error?.kind}: ${error?.message}".toString())
            } else {
                long limit = ((ScriptSupervisor.LIMITS[p] as Map).unitLimit as Long)
                long units = (run.units as Long) ?: 0L
                if (units > limit * HEAVY_SHARE) {
                    problems << problem('TOO_HEAVY', null, ("On the sample input alone the script uses ${units} of its ${limit} units " +
                            "(${Math.round(100d * units / limit)}%); real data is usually bigger. A loop inside a loop is the usual cause: " +
                            "build a map keyed by id first (m[o.orderId] = o), then look items up in it.").toString())
                }
            }
        }
        return [ok: problems.isEmpty(), problems: problems, notes: notes] as Map<String, Object>
    }

    // -- the rules

    private static void loops(String source, String code, List<Map<String, Object>> problems) {
        Matcher m = (code =~ /\bwhile\s*\(/)
        while (m.find()) {
            int open = m.end() - 1
            int close = matching(code, open, '(' as char, ')' as char)
            if (close < 0) continue
            String condition = code.substring(open + 1, close).trim()
            int at = close + 1
            while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++
            String bodyCode
            if (at < code.length() && code.charAt(at) == ('{' as char)) {
                int end = matching(code, at, '{' as char, '}' as char)
                bodyCode = end < 0 ? code.substring(at) : code.substring(at, end + 1)
            } else {
                int end = code.indexOf(';', at)
                bodyCode = end < 0 ? code.substring(at) : code.substring(at, end + 1)
            }
            boolean exits = bodyCode =~ /\b(break|return)\b/
            int line = lineOf(source, m.start())
            if (condition == 'true') {
                if (!exits) problems << problem('ENDLESS_LOOP', line, 'This loop never stops: while (true) has no break or return inside it.')
                continue
            }
            if (exits) continue
            Set<String> names = ((condition =~ /(?<![\w.:])([A-Za-z_]\w*)\b(?!\s*\()/).collect { Object g -> ((List) g)[1] as String } as Set<String>)
            names.removeAll(['input', 'true', 'false', 'null', 'and', 'or', 'not', 'eq', 'ne', 'lt', 'le', 'gt', 'ge', 'size', 'empty'])
            if (names.isEmpty()) continue
            boolean progresses = names.any { String n -> changes(bodyCode, n) }
            if (!progresses) {
                String listed = names.sort().collect { "'${it}'" }.join(', ')
                String first = names.sort()[0]
                problems << problem('NO_PROGRESS', line, ("This loop never changes ${listed}, so it never ends. " +
                        "Change it inside the loop, for example ${first} = ${first} + 1, or use for (var x : list).").toString())
            }
        }
    }

    private static boolean changes(String code, String name) {
        String n = java.util.regex.Pattern.quote(name)
        return (code =~ "(?<![\\w.])${n}\\s*(\\+\\+|--|[-+*/%]?=(?!=))") || (code =~ "(\\+\\+|--)\\s*${n}\\b")
    }

    private static void functions(String code, List<Map<String, Object>> problems) {
        Matcher m = (code =~ /\bd\s*:\s*([A-Za-z_]\w*)\s*\(/)
        while (m.find()) {
            String fn = m.group(1)
            if (LIBRARY.contains(fn)) continue
            String near = closest(fn, LIBRARY)
            problems << problem('UNKNOWN_FUNCTION', lineOf(code, m.start()),
                    ("d:${fn} is not a Darpan function.${near ? " Did you mean d:${near}?" : ''}").toString())
        }
    }

    private static void assignInCondition(String code, List<Map<String, Object>> problems) {
        Matcher m = (code =~ /\bif\s*\(\s*[A-Za-z_][\w.]*\s*=(?!=)/)
        while (m.find()) {
            problems << problem('ASSIGN_IN_CONDITION', lineOf(code, m.start()), 'This if sets a value with =; to compare, use ==.')
        }
    }

    private static void fields(String source, String code, Object sample, List<Map<String, Object>> problems) {
        Set<String> known = keysOf(sample)
        // Fields the script makes itself: quoted map literal keys ({'k': ..}) and m.k = ... (in JEXL an unquoted key
        // {k: ..} is a variable's value, not a name, so it does not count).
        (source =~ /['"]([A-Za-z_]\w*)['"]\s*:/).each { Object g -> known.add(((List) g)[1] as String) }
        (code =~ /\.([A-Za-z_]\w*)\s*=(?!=)/).each { Object g -> known.add(((List) g)[1] as String) }
        Set<String> reported = [] as Set<String>
        Matcher m = (code =~ /(?<=[\w\])])\s*(\?)?\.\s*([A-Za-z_]\w*)/)
        while (m.find()) {
            String name = m.group(2)
            if (known.contains(name) || reported.contains(name)) continue
            boolean optional = m.group(1) != null || (code.substring(m.end()) =~ /^\s*\?\?/)
            if (optional) continue
            reported << name
            String near = closest(name, known)
            problems << problem('UNKNOWN_FIELD', lineOf(code, m.start()), ("'${name}' is not a field anywhere in the sample input." +
                    (near ? " Did you mean '${near}'?" : '') + " If it is optional, read it with a default: x.${name} ?? 0.").toString())
        }
    }

    // -- helpers

    /** Text literals and comments replaced by spaces, newlines kept, so positions and lines still match the source. */
    static String mask(String source) {
        StringBuilder out = new StringBuilder(source)
        int i = 0
        int n = source.length()
        while (i < n) {
            char c = source.charAt(i)
            if (c == ('/' as char) && i + 1 < n && source.charAt(i + 1) == ('/' as char)) {
                while (i < n && source.charAt(i) != ('\n' as char)) { out.setCharAt(i, ' ' as char); i++ }
            } else if (c == ('/' as char) && i + 1 < n && source.charAt(i + 1) == ('*' as char)) {
                while (i < n && !(source.charAt(i) == ('*' as char) && i + 1 < n && source.charAt(i + 1) == ('/' as char))) {
                    if (source.charAt(i) != ('\n' as char)) out.setCharAt(i, ' ' as char)
                    i++
                }
                if (i < n) { out.setCharAt(i, ' ' as char); if (i + 1 < n) out.setCharAt(i + 1, ' ' as char); i += 2 }
            } else if (c == ('\'' as char) || c == ('"' as char)) {
                char quote = c
                i++
                while (i < n && source.charAt(i) != quote) {
                    if (source.charAt(i) == ('\\' as char) && i + 1 < n) { out.setCharAt(i, ' ' as char); i++ }
                    if (source.charAt(i) != ('\n' as char)) out.setCharAt(i, ' ' as char)
                    i++
                }
                i++
            } else {
                i++
            }
        }
        return out.toString()
    }

    private static int matching(String code, int open, char opening, char closing) {
        int depth = 0
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == opening) depth++
            else if (code.charAt(i) == closing && --depth == 0) return i
        }
        return -1
    }

    private static int lineOf(String text, int offset) {
        int line = 1
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == ('\n' as char)) line++
        return line
    }

    private static Set<String> keysOf(Object sample) {
        Set<String> keys = new HashSet<>()
        Deque<Object> todo = new ArrayDeque<>([sample])
        int entries = 0
        while (!todo.isEmpty() && entries < 50_000) {
            Object x = todo.poll()
            if (x instanceof Map) {
                for (Map.Entry e : ((Map) x).entrySet()) { entries++; keys.add(String.valueOf(e.key)); if (e.value instanceof Map || e.value instanceof Collection) todo.add(e.value) }
            } else if (x instanceof Collection) {
                for (Object v : (Collection) x) { entries++; if (v instanceof Map || v instanceof Collection) todo.add(v) }
            }
        }
        return keys
    }

    /** The closest name within two edits, or null. */
    private static String closest(String name, Collection<String> candidates) {
        String best = null
        int bestDistance = 3
        for (String c : candidates) {
            int d = distance(name.toLowerCase(), c.toLowerCase())
            if (d < bestDistance) { best = c; bestDistance = d }
        }
        return best
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1]
        int[] cur = new int[b.length() + 1]
        for (int j = 0; j <= b.length(); j++) prev[j] = j
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            int[] t = prev; prev = cur; cur = t
        }
        return prev[b.length()]
    }

    private static Map<String, Object> problem(String rule, Integer line, String message) {
        return [rule: rule, line: line, message: message] as Map<String, Object>
    }
}
