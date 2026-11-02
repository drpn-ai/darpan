package darpan.script

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.ConditionalInterrupt
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.SecureASTCustomizer

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * DAR-BE-175 POC: a tenant's Groovy script, guarded for running inside Moqui (genuine users, not attackers).
 *
 * <p>Not Moqui's ec.resource.script/expression: that path binds the whole ec and caches every script text in a
 * never-evicted map on one shared classloader. Here each compiled script gets its own classloader in a small LRU,
 * so an edited script's old classes unload; the binding is only {@code input} and the {@code d} library.</p>
 *
 * <p>Guards: compile-time rules (no imports, no System/Runtime/Thread/File/reflection, no shell, no sleep, no method
 * definitions) and a checkpoint injected at every loop head, closure and method entry that spends ScriptBudget units
 * (shared with JEXL: same UNITS/TIME errors, same d: library caps) and samples this thread's allocated bytes, CPU
 * and a wall deadline. Known gap, by design of the JVM: a single long call into Groovy's own library, or one huge
 * allocation, runs between checkpoints and cannot be stopped in-process (ScriptLane reports such threads as stuck).</p>
 */
@CompileStatic
class GroovyGuard {
    static final long UNIT_LIMIT = 2_000_000L
    static final long ALLOC_LIMIT_BYTES = 64L * 1024 * 1024
    static final long CPU_LIMIT_NANOS = 2_000_000_000L
    static final long WALL_LIMIT_MS = 3_000L
    static final int MAX_SOURCE_CHARS = 64_000
    static final int CACHE_SIZE = 64

    static final Set<String> DENIED_CALLS = ['execute', 'sleep', 'getClass', 'forName', 'exit', 'invokeMethod',
            'getMetaClass', 'setMetaClass', 'evaluate', 'newInstance', 'getDeclaredMethod', 'getMethod', 'wait',
            'notify', 'notifyAll', 'start', 'run', 'getClassLoader', 'parseClass', 'with', 'tap', 'identity',
            'invokeMethods', 'getProperty', 'setProperty', 'withTraits'] as Set<String>
    static final Set<String> DENIED_PROPERTIES = ['class', 'metaClass', 'classLoader', 'binding', 'properties'] as Set<String>

    private static final com.sun.management.ThreadMXBean TMX = (com.sun.management.ThreadMXBean) ManagementFactory.threadMXBean
    private static final ThreadLocal<long[]> SAMPLE = new ThreadLocal<>()   // [startAlloc, startCpu, deadlineNanos, checks]

    /** Compiled scripts by source hash: LRU, each entry its own classloader, so evicted classes can unload. */
    private static final Map<String, Class> CACHE = Collections.synchronizedMap(new CompiledScripts())

    /** Access-ordered LRU of compiled script classes; the eldest drops out (and its classloader can unload). */
    private static class CompiledScripts extends LinkedHashMap<String, Class> {
        CompiledScripts() { super(16, 0.75f, true) }
        @Override protected boolean removeEldestEntry(Map.Entry<String, Class> eldest) { return this.size() > GroovyGuard.CACHE_SIZE }
    }

    /** Runs on the CALLER's thread (ScriptLane supplies it). {ok, profile, result, units, elapsedMs, logs, error}. Never throws. */
    static Map<String, Object> evaluate(String body, Object input) {
        long started = System.nanoTime()
        if (!body?.trim()) return outcome(false, null, [kind: 'SYNTAX', message: 'The script is empty.'], null, started)
        if (body.length() > MAX_SOURCE_CHARS) {
            return outcome(false, null, [kind: 'SIZE', message: "The script is longer than ${MAX_SOURCE_CHARS} characters.".toString()], null, started)
        }
        Class scriptClass
        try {
            scriptClass = compile(body)
        } catch (MultipleCompilationErrorsException e) {
            return outcome(false, null, [kind: 'NOT_ALLOWED', message: firstLine(e)], null, started)
        } catch (Throwable e) {
            return outcome(false, null, [kind: 'SYNTAX', message: String.valueOf(e.message).take(300)], null, started)
        }
        Object plainInput
        try {
            plainInput = input == null ? null : new JsonSlurper().parseText(JsonOutput.toJson(input))   // a copy: plain JSON only
        } catch (Throwable e) {
            return outcome(false, null, [kind: 'INPUT', message: 'The input is not plain JSON.'], null, started)
        }
        ScriptBudget budget = new ScriptBudget(UNIT_LIMIT, ScriptSupervisor.MAX_STRING_CHARS, ScriptSupervisor.MAX_COLLECTION_SIZE)
        ScriptBudget.open(budget)
        long id = Thread.currentThread().threadId()
        SAMPLE.set([TMX.getThreadAllocatedBytes(id), TMX.getThreadCpuTime(id), System.nanoTime() + WALL_LIMIT_MS * 1_000_000L, 0L] as long[])
        try {
            Script script = (Script) scriptClass.getDeclaredConstructor().newInstance()
            script.binding = new Binding([input: plainInput, d: new ScriptLibrary()] as Map)
            Object result = script.run()
            String json = JsonOutput.toJson(result)
            if (json.length() > ScriptSupervisor.MAX_OUTPUT_JSON_CHARS) {
                return outcome(false, null, [kind: 'SIZE', message: "The result is larger than ${ScriptSupervisor.MAX_OUTPUT_JSON_CHARS} characters.".toString()], budget, started)
            }
            return outcome(true, new JsonSlurper().parseText(json), null, budget, started)
        } catch (ScriptLimitException e) {
            return outcome(false, null, [kind: e.kind, message: e.message], budget, started)
        } catch (StackOverflowError e) {
            return outcome(false, null, [kind: 'MEMORY', message: 'The script recursed too deeply.'], budget, started)
        } catch (OutOfMemoryError e) {
            return outcome(false, null, [kind: 'MEMORY', message: 'The server ran short of memory during this script.'], budget, started)
        } catch (NullPointerException e) {
            // The CALLER's input: the script only ever had a copy, so nothing it grew or looped is walked here.
            return outcome(false, null, emptyValueError(e, body, input), budget, started)
        } catch (Throwable e) {
            return outcome(false, null, [kind: 'ERROR', message: "${e.getClass().simpleName}: ${String.valueOf(e.message).take(240)}".toString()], budget, started)
        } finally {
            SAMPLE.remove()
            ScriptBudget.close()
        }
    }

    /**
     * The checkpoint the compiler injects (through ConditionalInterrupt) at every loop head, closure and method entry.
     * Spends one ScriptBudget unit (which also honours an interrupt as TIME) and, every 256th call, samples the thread.
     * Throws ScriptLimitException to stop; returns false to continue.
     */
    static boolean checkpoint() {
        ScriptBudget.charge()
        long[] s = SAMPLE.get()
        if (s == null || ((++s[3]) & 255L) != 0L) return false
        long id = Thread.currentThread().threadId()
        if (TMX.getThreadAllocatedBytes(id) - s[0] > ALLOC_LIMIT_BYTES) {
            throw new ScriptLimitException('MEMORY', "The script allocated more than ${ALLOC_LIMIT_BYTES >> 20} MB.".toString())
        }
        if (TMX.getThreadCpuTime(id) - s[1] > CPU_LIMIT_NANOS) {
            throw new ScriptLimitException('TIME', "The script used more than ${CPU_LIMIT_NANOS.intdiv(1_000_000L)} ms of CPU.".toString())
        }
        if (System.nanoTime() > s[2]) throw new ScriptLimitException('TIME', "The script ran past ${WALL_LIMIT_MS} ms.".toString())
        return false
    }

    static int cachedScripts() { CACHE.size() }

    private static Class compile(String body) {
        String key = sha256(body)
        Class cached = CACHE.get(key)
        if (cached != null) return cached
        Class compiled = new GroovyClassLoader(GroovyGuard.classLoader, config()).parseClass(body, 'TenantGroovy_' + key.take(16))
        CACHE.put(key, compiled)
        return compiled
    }

    static CompilerConfiguration config() {
        CompilerConfiguration conf = new CompilerConfiguration()
        ClosureExpression check = new ClosureExpression(Parameter.EMPTY_ARRAY, new ExpressionStatement(
                new StaticMethodCallExpression(ClassHelper.make(GroovyGuard), 'checkpoint', ArgumentListExpression.EMPTY_ARGUMENTS)))
        check.variableScope = new VariableScope()
        conf.addCompilationCustomizers(new ASTTransformationCustomizer([value: check] as Map<String, Object>, ConditionalInterrupt))

        SecureASTCustomizer secure = new SecureASTCustomizer()
        secure.allowedImports = []
        secure.allowedStarImports = []
        secure.allowedStaticImports = []
        secure.allowedStaticStarImports = []
        secure.indirectImportCheckEnabled = false   // the receiver list, the import ban and the denied calls do the work
        secure.methodDefinitionAllowed = false      // closures cover helper functions; no def m() {}
        secure.disallowedReceiversClasses = [System, Runtime, Thread, ThreadGroup, ProcessBuilder, Process, File,
                Class, ClassLoader, GroovyShell, GroovyClassLoader, Eval, ManagementFactory] as List<Class>
        secure.addExpressionCheckers({ Expression e ->
            if (e instanceof MethodCallExpression && DENIED_CALLS.contains(((MethodCallExpression) e).methodAsString)) return false
            if (e instanceof StaticMethodCallExpression && DENIED_CALLS.contains(((StaticMethodCallExpression) e).methodAsString)) return false
            if (e instanceof PropertyExpression && DENIED_PROPERTIES.contains(((PropertyExpression) e).propertyAsString)) return false
            return true
        } as SecureASTCustomizer.ExpressionChecker)
        conf.addCompilationCustomizers(secure)
        return conf
    }

    private static Map<String, Object> outcome(boolean ok, Object result, Map error, ScriptBudget budget, long started) {
        return [ok: ok, profile: 'GROOVY', result: result, error: error, units: budget?.units ?: 0L,
                elapsedMs: (System.nanoTime() - started).intdiv(1_000_000L), logs: budget?.logs ?: []] as Map<String, Object>
    }

    /**
     * DAR-BE-175: instead of "NullPointerException: Cannot invoke method minus() on null object", say which line,
     * and when a field read on that line exists nowhere in the input, name it (a misspelt field reads as null).
     */
    static Map<String, Object> emptyValueError(Throwable e, String body, Object input) {
        StackTraceElement frame = e.stackTrace.find { StackTraceElement f -> f.className.startsWith('TenantGroovy_') && f.lineNumber > 0 }
        int line = frame?.lineNumber ?: 0
        String source = line > 0 ? (body.readLines()[line - 1] ?: '').trim().take(120) : ''
        String where = line > 0 ? "line ${line}".toString() + (source ? ": ${source}" : '') : 'this script'
        Set<String> known = inputKeys(input)
        String missing = null
        if (source) {
            java.util.regex.Matcher m = (source =~ /\.([A-Za-z_]\w*)\b(?!\s*[({])/)
            while (m.find()) {
                String name = m.group(1)
                if (!known.contains(name) && !['size', 'length', 'value', 'key', 'it'].contains(name)) { missing = name; break }
            }
        }
        if (missing) {
            List<String> some = known.toList().sort().take(12)
            return [kind: 'ERROR', field: missing, message: ("'${missing}' is not a field anywhere in the input (${where}). " +
                    "Fields in the input include: ${some.join(', ')}. A misspelt field reads as empty: fix the name, or use ?: for a default.").toString()] as Map<String, Object>
        }
        return [kind: 'ERROR', message: "An empty value (null) was used at ${where}. Check that it has a value, or use ?: for a default.".toString()] as Map<String, Object>
    }

    static final int MAX_HINT_ENTRIES = 50_000

    /**
     * Map key names in the input, for the misspelt-field hint. Bounded by ENTRIES VISITED (every map entry and list
     * item counts, queued or not) and safe on cycles (identity set); stops quietly at the bound.
     */
    static Set<String> inputKeys(Object input) {
        Set<String> keys = new HashSet<>()
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>())
        Deque<Object> todo = new ArrayDeque<>()
        if (input instanceof Map || input instanceof Collection) todo.add(input)
        int entries = 0
        while (!todo.isEmpty()) {
            Object x = todo.poll()
            if (!visited.add(x)) continue
            Iterator it = x instanceof Map ? ((Map) x).entrySet().iterator() : ((Collection) x).iterator()
            while (it.hasNext()) {
                if (++entries > MAX_HINT_ENTRIES) return keys
                Object item = it.next()
                Object child = item
                if (x instanceof Map) { Map.Entry e = (Map.Entry) item; keys.add(String.valueOf(e.key)); child = e.value }
                if ((child instanceof Map || child instanceof Collection) && !visited.contains(child)) todo.add(child)
            }
        }
        return keys
    }

    private static String firstLine(MultipleCompilationErrorsException e) {
        String line = e.message.readLines().find { String l -> l.contains('not allowed') || l.contains('disallowed') }
                ?: e.message.readLines().find { String l -> l.contains('@ line') } ?: e.message
        return line.replaceFirst('^.*?General error during [a-z ]+: ', '').trim().take(300)
    }

    private static String sha256(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(text.getBytes(StandardCharsets.UTF_8))
        StringBuilder hex = new StringBuilder(64)
        for (byte b : digest) hex.append(String.format('%02x', b & 0xff))
        return hex.toString()
    }
}
