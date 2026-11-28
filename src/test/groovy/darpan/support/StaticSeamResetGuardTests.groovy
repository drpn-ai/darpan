package darpan.support

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-026: the shared-Moqui harness resets process-global state reflectively (StaticResets.resetAll calls
 * every `static void resetForTests()` in main). That only works if every class holding such state declares one.
 * This guard fails when a class in src/main/groovy declares
 *   - a `static` field that is not `final` (seam, hook, clock, counter, lazy cache), or
 *   - a `static final` field that is a mutable shared container: type Atomic*, Concurrent*, ThreadLocal, a Cache/Queue/Deque,
 *     a name SHARED, or an initializer that builds one (`new ...`, an empty `[:]`/`[]` literal, boundedXxx(...))
 * and has no `static void resetForTests()`, or whose resetForTests() body (plus the same-class methods it calls, one level)
 * never mentions the field name. Multi-line declarations and an `@Field` on the previous line are joined before matching. Constants (literal lists/maps/sets, loggers, patterns, mappers) pass.
 * Text scan, no Moqui: runs in the unit pool.
 */
class StaticSeamResetGuardTests {

    /** "Class.field" -> why it needs no reset. Every entry must say why; an unused entry fails the guard. */
    private static final Map<String, String> ALLOWLIST = [
            "KnowledgeIndex.STOP_WORDS": "Lucene stop-word set built once at class load and never mutated",
            "RecordConditions.PATTERNS": "pure memo of compiled regexes keyed by source text; a stale entry is still correct",
            "JobScheduleRules.PARSED": "pure memo of parsed cron expressions keyed by expression; a stale entry is still correct",
            "TenantConfigPendingSupport.MARK_LOCKS": "registry of lock objects per key; holds no test-observable state",
            "OutboundHttpClient.sharedByConnectTimeout": "pool of stateless HttpClients keyed by connect timeout; no test-observable state",
            // 2026-11-23: resetForTests removed from these three by Aditi's call (shipped in the DAR-UI-138 hotfix); a test that
            // changes them must restore them itself.
            "BrainConclude.fetchBatch": "test seam, 0 by default; resetForTests removed 2026-11-23, a test that sets it restores it",
            "BrainEngine.CACHE": "compiled rule engines keyed by pack list; a stale entry is still correct; resetForTests removed 2026-11-23",
            "RunConclusionStep.lastCaptureMillis": "monotonic capture-name clock; only ever moves forward, never asserted; resetForTests removed 2026-11-23",
    ]

    private static final Pattern FIELD = Pattern.compile(
            '^\\s+((?:(?:private|public|protected|final|volatile|transient)\\s+|@\\w+(?:\\([^)]*\\))?\\s+)*)static\\s+' +
            '((?:(?:private|public|protected|final|volatile|transient)\\s+)*)' +
            '(?:([\\w.]+(?:<.*>)?(?:\\[\\])*)\\s+)?(\\w+)\\s*(?:=\\s*(.*))?$')
    private static final Pattern RESET = Pattern.compile('static\\s+(?:synchronized\\s+)?void\\s+resetForTests\\s*\\(\\s*\\)')
    private static final Pattern MUTABLE_TYPE = Pattern.compile('(^|[.])(Atomic\\w+|Concurrent\\w+|ThreadLocal|InheritableThreadLocal|\\w*Cache|\\w*Queue|\\w*Deque)(<|$)')
    private static final Pattern MUTABLE_INIT = Pattern.compile('^(new\\s+(?:java\\.util\\.(?:concurrent\\.)?)?\\w*(?:Map|List|Set|Queue|Deque|Cache|Atomic\\w*|Counter|Stack|Vector|Array|Collection)\\b|\\[:?\\]\\s*(as\\b.*)?$|bounded\\w+\\(|Collections\\.synchronized\\w*|Collections\\.newSetFromMap|(?:[\\w.]*\\.)?Concurrent\\w+\\.|(?:[\\w.]*\\.)?newKeySet\\b|\\w+\\.SHARED\\b)')

    @Test
    void everyMutableStaticSeamHasResetForTests() {
        Path root = findMainGroovy()
        List<String> violations = []
        Set<String> used = [] as Set
        int scanned = 0
        List<Path> files = []
        root.toFile().eachFileRecurse { File f -> if (f.name.endsWith('.groovy')) files << f.toPath() }
        files.sort().with { List<Path> sorted ->
            sorted.each { Path file ->
                scanned++
                String text = file.toFile().getText('UTF-8')
                boolean hasReset = RESET.matcher(text).find()
                String cls = file.fileName.toString().replace('.groovy', '')
                scan(text).each { Map f ->
                    String key = "${cls}.${f.name}".toString()
                    if (ALLOWLIST.containsKey(key)) { used << key; return }
                    if (!hasReset) {
                        violations << "${root.relativize(file)}:${f.line} ${f.kind} static '${f.name}' but ${cls} has no static void resetForTests()".toString()
                    } else if (!resetMentions(text, f.name as String)) {
                        violations << "${root.relativize(file)}:${f.line} ${cls}.${f.name} is a ${f.kind} static that ${cls}.resetForTests() never touches".toString()
                    }
                }
            }
        }
        assertTrue(scanned > 100, "scanned only ${scanned} source files under ${root}: the scan has gone stale")
        assertTrue(violations.isEmpty(), "DAR-OPS-026: mutable process-global statics need a resetForTests() " +
                "(see StaticResets) or an allowlist entry with a reason:\n  " + violations.join('\n  '))
        Set<String> stale = ALLOWLIST.keySet() - used
        assertTrue(stale.isEmpty(), "allowlist entries that match no mutable static: ${stale}")
        ALLOWLIST.each { k, why -> assertTrue(why?.trim() as boolean, "allowlist entry ${k} needs a reason") }
    }

    @Test
    void scannerFlagsMutableStaticsAndSkipsConstants() {
        String sample = '''
class Sample {
    private static final Logger logger = LoggerFactory.getLogger(Sample.class)
    static final String NAME = "x"
    static final Pattern P = Pattern.compile("a")
    static final List<String> LINES = ["a", "b"].asImmutable()
    static final Map<String, String> M = [a: "b"]
    private static Closure hook = null
    static volatile long timeoutMillis = 60_000L
    static final AtomicInteger counter = new AtomicInteger()
    private static final Map<String, String> CYCLES = new ConcurrentHashMap<String, String>()
    static final Map<String, Object> BAG = [:]
    private static final ThreadLocal<Map> CURRENT = new ThreadLocal<Map>()
    static Foo SHARED = new Foo()
    static final Foo ALIAS = Foo.SHARED
    static String describe(def x) { return null }
    static void reset() { hook = null }
    static {
        hook = null
    }
}
'''
        assertEquals(['hook', 'timeoutMillis', 'counter', 'CYCLES', 'BAG', 'CURRENT', 'SHARED', 'ALIAS'], scan(sample).collect { it.name })
    }

    /** Mutable static fields of one source text: [name, kind, line]. */
    static List<Map> scan(String text) {
        List<Map> found = []
        List<String> lines = text.readLines()
        String prevNonBlank = ''
        for (int i = 0; i < lines.size(); i++) {
            int n = i + 1
            String line = lines[i]
            String t = line.trim()
            if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue
            if (t.isEmpty()) continue
            String before = prevNonBlank
            prevNonBlank = t
            if (!(line =~ /\bstatic\b/)) continue
            // join continuation lines: a declaration ending in '=' or leaving a '(' / '[' open. Each physical line loses
            // its own `//` comment before it joins, so one comment can't swallow the lines after it.
            int guard = 0
            while (i + 1 < lines.size() && guard++ < 40 && continues(line)) {
                i++
                line = '    ' + withoutLineComment(line).trim() + ' ' + withoutLineComment(lines[i]).trim()
            }
            // an annotation alone on the line above (@Field)
            if (before.matches(/^@\w+(\([^)]*\))?$/)) line = '    ' + before + ' ' + line.trim()
            Matcher m = FIELD.matcher(line)
            if (!m.matches()) continue
            String mods = (m.group(1) ?: '') + ' ' + (m.group(2) ?: '')
            String type = m.group(3)
            String name = m.group(4)
            String init = m.group(5)?.trim() ?: ''
            if (name in ['class', 'void', 'def'] || line.contains('static {') || (type == null && init == '' && !mods.contains('final') && !line.trim().matches(/.*\bstatic\s+\w+$/))) continue
            if (init.startsWith('(') && type == null) continue
            if (line.indexOf('(') >= 0 && (init == '' || line.indexOf('(') < line.indexOf('='))) continue // a method signature, not a field
            boolean isFinal = mods =~ /\bfinal\b/
            if (!isFinal) {
                found << [name: name, kind: 'non-final', line: n]
            } else if ((type != null && MUTABLE_TYPE.matcher(type).find()) || name == 'SHARED' || MUTABLE_INIT.matcher(init).find()) {
                found << [name: name, kind: 'mutable-container', line: n]
            }
        }
        return found
    }

    private static String withoutLineComment(String line) {
        return line.replaceAll(/\s*\/\/.*$/, '')
    }

    private static boolean continues(String line) {
        String t = withoutLineComment(line).trim()
        if (t.endsWith('=')) return true
        if (t.contains('{')) return false
        int open = 0
        for (char c : t.toCharArray()) {
            if (c == ('(' as char) || c == ('[' as char)) open++
            else if (c == (')' as char) || c == (']' as char)) open--
        }
        return open > 0
    }

    /** Body (between the braces) of the first method declared by `header`, comments stripped; null when absent. */
    private static String bodyOf(String text, Pattern header) {
        Matcher m = header.matcher(text)
        if (!m.find()) return null
        int open = text.indexOf('{', m.end())
        if (open < 0) return null
        int depth = 0
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i)
            if (c == ('{' as char)) depth++
            else if (c == ('}' as char) && --depth == 0) {
                return text.substring(open + 1, i).replaceAll(/(?s)\/\*.*?\*\//, '').replaceAll(/\/\/[^\n]*/, '')
            }
        }
        return null
    }

    /** True when resetForTests() (or a same-class method it calls by name, one level) mentions the field. */
    static boolean resetMentions(String text, String field) {
        String body = bodyOf(text, RESET)
        if (body == null) return false
        StringBuilder all = new StringBuilder(body)
        Matcher calls = Pattern.compile('\\b(\\w+)\\s*\\(').matcher(body)
        Set<String> seen = [] as Set
        while (calls.find()) {
            String callee = calls.group(1)
            if (callee == 'resetForTests' || !seen.add(callee)) continue
            String sub = bodyOf(text, Pattern.compile('static\\s+(?:synchronized\\s+)?[\\w.<>\\[\\]]+\\s+' + Pattern.quote(callee) + '\\s*\\([^)]*\\)'))
            if (sub != null) all.append('\n').append(sub)
        }
        return Pattern.compile('\\b' + Pattern.quote(field) + '\\b').matcher(all).find()
    }

    @Test
    void scannerCatchesKeySetMultiLineAndFieldOnPreviousLine() {
        String sample = '''
class S {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet()
    private static final Set<String> VIA = java.util.concurrent.ConcurrentHashMap.newKeySet()
    static final Set<String> SW = Collections.newSetFromMap(new java.util.WeakHashMap())
    static final Map<String, String> MULTI =
        new HashMap<String, String>()
    static final Map<String, String> BRACKET = Collections.synchronizedMap(
        new HashMap<String, String>())
    static final Map<String, String> CONSTANT =
        [a: "b"]
    @Field
    static final Map<String, Object> ANNOTATED = [:]
    static String describe(
        def x) { return null }
}
'''
        assertEquals(['WARNED', 'VIA', 'SW', 'MULTI', 'BRACKET', 'ANNOTATED'], scan(sample).collect { it.name })
    }

    /** DAR-OPS-029 S39 (from S16b): an inline comment inside a multi-line literal must not hide the literal's close. The
     *  joiner used to strip `//` from the whole joined line, so the `]` never counted, the join ran on, swallowed the next
     *  static (missed) and read a method's local as a static (phantom RetentionSupport.out). */
    @Test
    void inlineCommentInMultiLineLiteralDoesNotRunTheJoinOn() {
        String sample = '''
class Retention {
    static final Map<String, String> TABLES = [
            (A): "a.days", // the A table
            (B): "b.days",
    ].asImmutable()
    static Closure hook = null

    static Map plan() {
        Map<String, Map> out = [:]
        return out
    }
}
'''
        assertEquals(['hook'], scan(sample).collect { it.name })
    }

    @Test
    void resetBodyMustMentionEveryFlaggedField() {
        String missing = '''
class R {
    static Closure hook = null
    static final Map<String, String> CACHE = new HashMap<String, String>()
    static void resetForTests() { hook = null }
}
'''
        assertTrue(resetMentions(missing, 'hook'))
        assertTrue(!resetMentions(missing, 'CACHE'))
        String delegated = '''
class R {
    static final Map<String, String> CACHE = new HashMap<String, String>()
    static void resetForTests() { clearCache() }
    static void clearCache() { CACHE.clear() }
}
'''
        assertTrue(resetMentions(delegated, 'CACHE'))
        assertTrue(!resetMentions('class R { static int n = 1 }', 'n'))
    }

    private static Path findMainGroovy() {
        Path p = Paths.get('src/main/groovy').toAbsolutePath()
        if (Files.isDirectory(p)) return p
        Path q = Paths.get('runtime/component/darpan/src/main/groovy').toAbsolutePath()
        assertTrue(Files.isDirectory(q), "cannot find src/main/groovy from ${Paths.get('').toAbsolutePath()}")
        return q
    }
}
