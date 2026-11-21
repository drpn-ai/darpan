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
 *   - a `static final` field that is a mutable shared container: type Atomic*, Concurrent*, a Cache/Queue/Deque,
 *     a name SHARED, or an initializer that builds one (`new ...`, an empty `[:]`/`[]` literal, boundedXxx(...))
 * and has no `static void resetForTests()`. Constants (literal lists/maps/sets, loggers, patterns, mappers) pass.
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
            "ScriptSupervisor.threadCount": "monotonic counter used only to name worker threads",
            "AgentQueryLane.IN_FLIGHT": "TODO DAR-OPS-026: reset owed",
            "AgentQueryLane.total": "TODO DAR-OPS-026: reset owed",
            "AgentQuerySupport.timeoutMillis": "TODO DAR-OPS-026: reset owed",
            "AgentQuerySupport.WORKER_SEQ": "TODO DAR-OPS-026: reset owed",
            "AuditChangePlanner.backtest": "TODO DAR-OPS-026: reset owed",
            "AuditDigestSupport.sendEmail": "TODO DAR-OPS-026: reset owed",
            "AuditRunUpdateSupport.sendEmail": "TODO DAR-OPS-026: reset owed",
            "AuditRunUpdateSupport.dispatch": "TODO DAR-OPS-026: reset owed",
            "BrainEngine.CACHE": "TODO DAR-OPS-026: reset owed",
            "EmailCopy.seedDefaultsCache": "TODO DAR-OPS-026: reset owed",
            "EmailCopy.seedSubjectsCache": "TODO DAR-OPS-026: reset owed",
            "AuthFacadeSupport.passwordHashCostMillis": "TODO DAR-OPS-026: reset owed",
            "AuthFacadeSupport.passwordHashCostMeasured": "TODO DAR-OPS-026: reset owed",
            "PasskeySupport.decoyKeyCache": "TODO DAR-OPS-026: reset owed",
            "RunConclusionStep.lastCaptureMillis": "TODO DAR-OPS-026: reset owed",
            "KnowledgeSupport.product": "TODO DAR-OPS-026: reset owed",
            "KnowledgeSupport.GENERATIONS": "TODO DAR-OPS-026: reset owed",
            "RunConclusionSupport.maxBrainFindings": "TODO DAR-OPS-026: reset owed",
            "RunNotificationVoice.linePicker": "TODO DAR-OPS-026: reset owed",
            "RunNotificationVoice.CYCLES": "TODO DAR-OPS-026: reset owed",
            "RunNotificationVoice.LAST_DEALT": "TODO DAR-OPS-026: reset owed",
    ]

    private static final Pattern FIELD = Pattern.compile(
            '^\\s+((?:(?:private|public|protected|final|volatile|transient)\\s+|@\\w+(?:\\([^)]*\\))?\\s+)*)static\\s+' +
            '((?:(?:private|public|protected|final|volatile|transient)\\s+)*)' +
            '(?:([\\w.]+(?:<.*>)?(?:\\[\\])*)\\s+)?(\\w+)\\s*(?:=\\s*(.*))?$')
    private static final Pattern RESET = Pattern.compile('static\\s+void\\s+resetForTests\\s*\\(\\s*\\)')
    private static final Pattern MUTABLE_TYPE = Pattern.compile('(^|[.])(Atomic\\w+|Concurrent\\w+|\\w*Cache|\\w*Queue|\\w*Deque)(<|$)')
    private static final Pattern MUTABLE_INIT = Pattern.compile('^(new\\s+(?:java\\.util\\.(?:concurrent\\.)?)?\\w*(?:Map|List|Set|Queue|Deque|Cache|Atomic\\w*|Counter|Stack|Vector|Array|Collection)\\b|\\[:?\\]\\s*(as\\b.*)?$|bounded\\w+\\(|Collections\\.synchronized|\\w+\\.SHARED\\b)')

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
    static Foo SHARED = new Foo()
    static final Foo ALIAS = Foo.SHARED
    static String describe(def x) { return null }
    static void reset() { hook = null }
    static {
        hook = null
    }
}
'''
        assertEquals(['hook', 'timeoutMillis', 'counter', 'CYCLES', 'BAG', 'SHARED', 'ALIAS'], scan(sample).collect { it.name })
    }

    /** Mutable static fields of one source text: [name, kind, line]. */
    static List<Map> scan(String text) {
        List<Map> found = []
        int n = 0
        for (String line : text.readLines()) {
            n++
            String t = line.trim()
            if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) continue
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

    private static Path findMainGroovy() {
        Path p = Paths.get('src/main/groovy').toAbsolutePath()
        if (Files.isDirectory(p)) return p
        Path q = Paths.get('runtime/component/darpan/src/main/groovy').toAbsolutePath()
        assertTrue(Files.isDirectory(q), "cannot find src/main/groovy from ${Paths.get('').toAbsolutePath()}")
        return q
    }
}
