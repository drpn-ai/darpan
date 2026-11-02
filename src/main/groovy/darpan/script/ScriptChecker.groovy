package darpan.script

import groovy.transform.CompileStatic

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Matcher
import java.util.regex.Pattern

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
 *
 * <p>Bounded (security review of 49dd046): it runs on a request thread outside any script budget. The source is
 * already capped by validate (64,000 characters); here every pass is linear in the source, lines come from one
 * offset table, known sample keys stop at {@value #MAX_KNOWN_KEYS}, suggestions only compare names of similar length,
 * reports stop at {@value #MAX_FIELD_REPORTS} fields and {@value #MAX_PROBLEMS} problems, at most {@value #MAX_LOOPS}
 * loops are analysed, and the static pass stops at {@value #STATIC_DEADLINE_MS} ms (with a note, never a refusal).</p>
 */
@CompileStatic
class ScriptChecker {
    static final double HEAVY_SHARE = 0.5d
    static final int MAX_PROBLEMS = 20
    static final int MAX_FIELD_REPORTS = 10
    static final int MAX_LOOPS = 50
    static final int MAX_KNOWN_KEYS = 5_000
    static final long STATIC_DEADLINE_MS = 500L

    private static final Set<String> LIBRARY = ScriptLibrary.declaredMethods
            .findAll { Method m -> Modifier.isPublic(m.modifiers) && Modifier.isStatic(m.modifiers) }
            .collect { Method m -> m.name }.toSet()
    private static final Pattern WHILE = ~/\bwhile\s*\(/
    private static final Pattern D_CALL = ~/\bd\s*:\s*([A-Za-z_]\w*)\s*\(/
    private static final Pattern ASSIGN_IN_IF = ~/\bif\s*\(\s*[A-Za-z_][\w.]*\s*=(?!=)/
    /** .name after a value; group 1 = ?. (optional), group 2 = the name, group 3 = a following ?? (optional). */
    private static final Pattern FIELD_READ = ~/(?<=[\w\])])\s*(\?)?\.\s*([A-Za-z_]\w*)(\s*\?\?)?/
    private static final Pattern QUOTED_KEY = ~/['"]([A-Za-z_]\w*)['"]\s*:/
    private static final Pattern SET_FIELD = ~/\.([A-Za-z_]\w*)\s*=(?!=)/
    private static final Pattern CONDITION_NAME = ~/(?<![\w.:])([A-Za-z_]\w*)\b(?!\s*\()/
    private static final Pattern EXIT = ~/\b(break|return)\b/
    private static final Set<String> NOT_VARIABLES = ['input', 'true', 'false', 'null', 'and', 'or', 'not', 'eq', 'ne', 'lt', 'le',
                                                      'gt', 'ge', 'size', 'empty'] as Set<String>

    /** One check's working state: line offsets, the static deadline, the problems so far. */
    private static class Pass {
        final String source
        final String code
        final int[] lineStarts
        final long deadline
        final List<Map<String, Object>> problems = []
        boolean cut = false

        Pass(String source) {
            this.source = source
            this.code = mask(source)
            List<Integer> starts = [0]
            for (int i = 0; i < source.length(); i++) if (source.charAt(i) == ('\n' as char)) starts << (i + 1)
            this.lineStarts = starts as int[]
            this.deadline = System.nanoTime() + STATIC_DEADLINE_MS * 1_000_000L
        }

        int lineOf(int offset) {
            int i = Arrays.binarySearch(lineStarts, offset)
            return (i >= 0 ? i : -i - 2) + 1
        }

        /** True once the static pass must stop: out of time, or enough problems to report. */
        boolean done() {
            if (problems.size() >= MAX_PROBLEMS) return true
            if (System.nanoTime() > deadline) { cut = true; return true }
            return false
        }

        void add(String rule, Integer line, String message) {
            if (problems.size() < MAX_PROBLEMS) problems << ([rule: rule, line: line, message: message] as Map<String, Object>)
        }
    }

    /** {ok, problems: [{rule, line, message}], notes: [String]}. */
    static Map<String, Object> check(Object profile, String body, String sampleJson) {
        String p = ScriptSupervisor.profileOf(profile) ?: ScriptSupervisor.SCRIPT
        Pass pass = new Pass(body ?: '')
        List<String> notes = []

        loops(pass)
        functions(pass)
        assignInCondition(pass)

        Object sample = null
        if (sampleJson?.trim()) {
            try { sample = ScriptSupervisor.parseInputJson(sampleJson) } catch (IllegalArgumentException ignored) { sample = null }
        }
        if (sample != null) fields(pass, sample)
        if (pass.cut) notes << 'This script is long, so some checks stopped early; the rest still ran.'
        if (pass.problems.size() >= MAX_PROBLEMS) notes << "Only the first ${MAX_PROBLEMS} problems are listed.".toString()

        // Only a script with no static problem gets a trial run (a NO_PROGRESS loop would just burn its budget).
        if (pass.problems.isEmpty() && sample != null) {
            Map<String, Object> run = ScriptSupervisor.evaluate(p, pass.source, sample)
            Map error = run.error as Map
            if (!(run.ok as boolean) && error?.kind == 'BUSY') {
                notes << 'The trial run on the sample input was skipped because Darpan is busy; the script was checked without it.'
            } else if (!(run.ok as boolean)) {
                pass.add('FAILS_ON_SAMPLE', error?.line as Integer,
                        "On its own sample input the script stops: ${error?.kind}: ${error?.message}".toString())
            } else {
                long limit = ((ScriptSupervisor.LIMITS[p] as Map).unitLimit as Long)
                long units = (run.units as Long) ?: 0L
                if (units > limit * HEAVY_SHARE) {
                    pass.add('TOO_HEAVY', null, ("On the sample input alone the script uses ${units} of its ${limit} units " +
                            "(${Math.round(100d * units / limit)}%); real data is usually bigger. A loop inside a loop is the usual cause: " +
                            "build a map keyed by id first (m[o.orderId] = o), then look items up in it.").toString())
                }
            }
        }
        return [ok: pass.problems.isEmpty(), problems: pass.problems, notes: notes] as Map<String, Object>
    }

    // -- the rules

    private static void loops(Pass pass) {
        String code = pass.code
        Matcher m = WHILE.matcher(code)
        int seen = 0
        while (m.find() && seen++ < MAX_LOOPS && !pass.done()) {
            int open = m.end() - 1
            int close = matching(code, open, '(' as char, ')' as char)
            if (close < 0) continue
            String condition = code.substring(open + 1, close).trim()
            int at = close + 1
            while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++
            int bodyEnd
            if (at < code.length() && code.charAt(at) == ('{' as char)) {
                int end = matching(code, at, '{' as char, '}' as char)
                bodyEnd = end < 0 ? code.length() : end + 1
            } else {
                int end = code.indexOf(';', at)
                bodyEnd = end < 0 ? code.length() : end + 1
            }
            CharSequence body = code.subSequence(at, bodyEnd)
            boolean exits = EXIT.matcher(body).find()
            int line = pass.lineOf(m.start())
            if (condition == 'true') {
                if (!exits) pass.add('ENDLESS_LOOP', line, 'This loop never stops: while (true) has no break or return inside it.')
                continue
            }
            if (exits) continue
            Set<String> names = new TreeSet<String>()
            Matcher n = CONDITION_NAME.matcher(condition)
            while (n.find() && names.size() < 10) names << n.group(1)
            names.removeAll(NOT_VARIABLES)
            if (names.isEmpty()) continue
            if (!names.any { String name -> changes(body, name) }) {
                String first = names.first()
                pass.add('NO_PROGRESS', line, ("This loop never changes ${names.collect { "'${it}'" }.join(', ')}, so it never ends. " +
                        "Change it inside the loop, for example ${first} = ${first} + 1, or use for (var x : list).").toString())
            }
        }
    }

    private static boolean changes(CharSequence code, String name) {
        String n = Pattern.quote(name)
        return Pattern.compile("(?<![\\w.])${n}\\s*(\\+\\+|--|[-+*/%]?=(?!=))").matcher(code).find() ||
                Pattern.compile("(\\+\\+|--)\\s*${n}\\b").matcher(code).find()
    }

    private static void functions(Pass pass) {
        Matcher m = D_CALL.matcher(pass.code)
        Set<String> reported = [] as Set<String>
        while (m.find() && !pass.done()) {
            String fn = m.group(1)
            if (LIBRARY.contains(fn) || !reported.add(fn)) continue
            String near = closest(fn, LIBRARY)
            pass.add('UNKNOWN_FUNCTION', pass.lineOf(m.start()), ("d:${fn} is not a Darpan function.${near ? " Did you mean d:${near}?" : ''}").toString())
        }
    }

    private static void assignInCondition(Pass pass) {
        Matcher m = ASSIGN_IN_IF.matcher(pass.code)
        while (m.find() && !pass.done()) {
            pass.add('ASSIGN_IN_CONDITION', pass.lineOf(m.start()), 'This if sets a value with =; to compare, use ==.')
        }
    }

    private static void fields(Pass pass, Object sample) {
        Set<String> known = keysOf(sample)
        // Fields the script makes itself: quoted map literal keys ({'k': ..}) and m.k = ... (in JEXL an unquoted key
        // {k: ..} is a variable's value, not a name, so it does not count).
        Matcher q = QUOTED_KEY.matcher(pass.source)
        while (q.find() && known.size() < MAX_KNOWN_KEYS * 2) known << q.group(1)
        Matcher s = SET_FIELD.matcher(pass.code)
        while (s.find() && known.size() < MAX_KNOWN_KEYS * 2) known << s.group(1)
        Set<String> reported = [] as Set<String>
        Matcher m = FIELD_READ.matcher(pass.code)
        while (m.find() && reported.size() < MAX_FIELD_REPORTS && !pass.done()) {
            String name = m.group(2)
            if (known.contains(name) || reported.contains(name)) continue
            if (m.group(1) != null || m.group(3) != null) continue   // ?. or ?? : read on purpose as optional
            reported << name
            String near = closest(name, known)
            pass.add('UNKNOWN_FIELD', pass.lineOf(m.start()), ("'${name}' is not a field anywhere in the sample input." +
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

    /** Sample key names: at most MAX_KNOWN_KEYS distinct, at most 50,000 entries walked. */
    private static Set<String> keysOf(Object sample) {
        Set<String> keys = new HashSet<>()
        Deque<Object> todo = new ArrayDeque<>([sample])
        int entries = 0
        while (!todo.isEmpty() && entries < 50_000 && keys.size() < MAX_KNOWN_KEYS) {
            Object x = todo.poll()
            if (x instanceof Map) {
                for (Map.Entry e : ((Map) x).entrySet()) {
                    if (++entries > 50_000 || keys.size() >= MAX_KNOWN_KEYS) break
                    keys.add(String.valueOf(e.key))
                    if (e.value instanceof Map || e.value instanceof Collection) todo.add(e.value)
                }
            } else if (x instanceof Collection) {
                for (Object v : (Collection) x) {
                    if (++entries > 50_000) break
                    if (v instanceof Map || v instanceof Collection) todo.add(v)
                }
            }
        }
        return keys
    }

    /** The closest name within two edits, comparing only names within two characters in length; or null. */
    private static String closest(String name, Collection<String> candidates) {
        if (name.length() > 64) return null
        String best = null
        int bestDistance = 3
        String lower = name.toLowerCase()
        for (String c : candidates) {
            if (Math.abs(c.length() - name.length()) > 2) continue
            int d = distance(lower, c.toLowerCase())
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
}
