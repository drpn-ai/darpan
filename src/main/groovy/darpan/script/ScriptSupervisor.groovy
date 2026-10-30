package darpan.script

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlEngine
import org.apache.commons.jexl3.JexlException
import org.apache.commons.jexl3.JexlFeatures
import org.apache.commons.jexl3.JexlInfo
import org.apache.commons.jexl3.JexlScript
import org.apache.commons.jexl3.MapContext

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAR-BE-164. The one place Darpan runs tenant-written JEXL (sandbox design §12: nothing else may reference jexl3;
 * ScriptSupervisorTests pins that). Two profiles on one engine family:
 * <ul>
 *   <li>EXPRESSION (tier T1): one expression, no loops, lambdas, local variables or side effects. Terminates by
 *   construction.</li>
 *   <li>SCRIPT (tier T2): statements, local variables, loops and lambdas. Still no method calls, no {@code new},
 *   no pragmas, no annotations, no assigning to {@code input}.</li>
 * </ul>
 * Both reach only {@code input} (plain JSON values) and the {@code d:} library (ScriptPermissions). Every evaluation
 * runs on a small dedicated pool, never on the caller's thread, under a unit budget (ScriptArithmetic), text and
 * collection caps, and a wall-clock stop.
 *
 * <p>MVP boundary: SCRIPT runs in Moqui's JVM. The caps bound what one evaluation can hold, but JEXL has no heap limit
 * of its own, so the design's worker JVM (§4, stage 2a) is still the gate before tenants outside Darpan author
 * SCRIPT-profile code. Evaluations never open a transaction or touch the database: the caller reads the input
 * first and writes nothing.</p>
 */
@CompileStatic
class ScriptSupervisor {

    static final String EXPRESSION = 'EXPRESSION'
    static final String SCRIPT = 'SCRIPT'
    static final List<String> PROFILES = [EXPRESSION, SCRIPT].asImmutable()

    /** The one name a script may read. */
    static final String INPUT_NAME = 'input'

    static final Map<String, Object> LIMITS = [
            (EXPRESSION): [maxSourceChars: 2_000, unitLimit: 100_000L, timeoutMs: 250L],
            (SCRIPT)    : [maxSourceChars: 64_000, unitLimit: 2_000_000L, timeoutMs: 2_000L],
    ].asImmutable() as Map<String, Object>
    static final int MAX_STRING_CHARS = 65_536
    static final int MAX_COLLECTION_SIZE = 10_000
    static final int MAX_INPUT_JSON_CHARS = 2_000_000
    static final int MAX_OUTPUT_JSON_CHARS = 65_536
    static final int MAX_OUTPUT_NODES = 20_000

    private static final JexlFeatures SCRIPT_FEATURES = JexlFeatures.createScript()
            .methodCall(false).newInstance(false).annotation(false)
            .pragma(false).pragmaAnywhere(false).importPragma(false).namespacePragma(false).namespaceInstantiation(false)
            .sideEffectGlobal(false).register(false)

    private static final JexlFeatures EXPRESSION_FEATURES = JexlFeatures.createScript()
            .methodCall(false).newInstance(false).annotation(false)
            .pragma(false).pragmaAnywhere(false).importPragma(false).namespacePragma(false).namespaceInstantiation(false)
            .sideEffectGlobal(false).register(false)
            .script(false).loops(false).lambda(false).thinArrow(false).fatArrow(false).localVar(false).sideEffect(false)

    private static final Map<String, JexlEngine> ENGINES = [
            (EXPRESSION): engine(EXPRESSION_FEATURES),
            (SCRIPT)    : engine(SCRIPT_FEATURES),
    ].asImmutable()

    /** Two threads, sixteen waiting: a burst of runs queues briefly, then is refused, never piles onto Moqui. */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16), new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger()
        @Override
        Thread newThread(Runnable r) {
            Thread t = new Thread(r, "darpan-script-${seq.incrementAndGet()}".toString())
            t.daemon = true
            t.priority = Thread.NORM_PRIORITY - 1
            return t
        }
    })

    private static JexlEngine engine(JexlFeatures features) {
        return new JexlBuilder()
                .features(features)
                .permissions(ScriptPermissions.INSTANCE)
                .namespaces([d: (Object) ScriptLibrary] as Map<String, Object>)
                .arithmetic(new ScriptArithmetic())
                .antish(false)        // a.b.c is never a variable named "a.b.c"
                .strict(true)
                .safe(false)
                .silent(false)
                .cancellable(true)
                .cache(512)
                .create()
    }

    static String profileOf(Object profile) {
        String value = profile?.toString()?.trim()?.toUpperCase(Locale.ROOT) ?: SCRIPT
        return PROFILES.contains(value) ? value : null
    }

    /**
     * Parses without running. {ok, profile, variables, error: {kind, message, line, column}}. Refuses syntax the
     * profile leaves out, and any name other than {@code input} (locals declared with var are fine).
     */
    static Map<String, Object> validate(Object profile, String body) {
        String p = profileOf(profile)
        if (p == null) return failure(null, 'INVALID', "Profile must be one of ${PROFILES.join(', ')}.".toString())
        String source = body ?: ''
        if (!source.trim()) return failure(p, 'INVALID', 'The script is empty.')
        int maxChars = (LIMITS[p] as Map).maxSourceChars as int
        if (source.length() > maxChars) {
            return failure(p, 'INVALID', "The script is ${source.length()} characters; the ${p.toLowerCase(Locale.ROOT)} limit is ${maxChars}.".toString())
        }
        int templateLine = templateLiteralLine(source)
        if (templateLine > 0) {
            Map<String, Object> refused = failure(p, 'NOT_ALLOWED', 'The ` character is not available (template text skips the limits); join text with +.')
            (refused.error as Map).line = templateLine
            return refused
        }
        JexlScript script
        try {
            script = ENGINES[p].createScript(new JexlInfo('script', 1, 1), source)
        } catch (JexlException e) {
            return failure(p, e instanceof JexlException.Feature ? 'NOT_ALLOWED' : 'SYNTAX', message(e), e)
        }
        List<String> names = (script.variables ?: [] as Set<List<String>>).collect { List<String> path -> path ? path[0] : null }
                .findAll { it }.unique() as List<String>
        List<String> unknown = names.findAll { it != INPUT_NAME }
        if (unknown) {
            return failure(p, 'ACCESS', "Unknown name${unknown.size() > 1 ? 's' : ''} ${unknown.collect { "'${it}'" }.join(', ')}: a script reads only input (declare your own with var).".toString())
        }
        return [ok: true, profile: p, variables: names, error: null] as Map<String, Object>
    }

    /**
     * Runs the script against input (any JSON-shaped value, copied before the script sees it).
     * {ok, profile, result, units, elapsedMs, logs, error: {kind, message, line, column}}. Never throws.
     */
    static Map<String, Object> evaluate(Object profile, String body, Object input) {
        Map<String, Object> checked = validate(profile, body)
        if (!checked.ok) return notRun(checked)
        String p = checked.profile as String
        Map limits = LIMITS[p] as Map

        Object plainInput
        try {
            plainInput = copyInput(input)
        } catch (IllegalArgumentException e) {
            return notRun(failure(p, 'INPUT', e.message))
        }

        JexlScript script = ENGINES[p].createScript(new JexlInfo('script', 1, 1), body)
        ScriptBudget budget = new ScriptBudget(limits.unitLimit as long, MAX_STRING_CHARS, MAX_COLLECTION_SIZE)
        long started = System.nanoTime()
        Future<Object> future
        try {
            future = POOL.submit({ ->
                ScriptBudget.open(budget)
                try {
                    MapContext context = new MapContext()
                    context.set(INPUT_NAME, plainInput)
                    Object raw = script.execute(context)
                    // Shaped here, inside the budget and the timeout: a result can be a huge range or a deep graph.
                    Object plain = toPlain(raw, 0, [0L, 0L] as long[])
                    String json = JsonOutput.toJson(plain)
                    if (json.length() > MAX_OUTPUT_JSON_CHARS) {
                        throw new ScriptLimitException('MEMORY', "The result is ${json.length()} characters of JSON; the limit is ${MAX_OUTPUT_JSON_CHARS}.".toString())
                    }
                    return plain
                } finally {
                    ScriptBudget.close()
                }
            } as java.util.concurrent.Callable<Object>)
        } catch (RejectedExecutionException ignored) {
            return notRun(failure(p, 'BUSY', 'Scripts are busy right now; try again in a moment.'))
        }

        Map<String, Object> out
        try {
            Object plain = future.get(limits.timeoutMs as long, TimeUnit.MILLISECONDS)
            out = [ok: true, profile: p, result: plain, error: null] as Map<String, Object>
        } catch (TimeoutException ignored) {
            future.cancel(true)
            out = failure(p, 'TIME', "The script ran past ${limits.timeoutMs} ms and was stopped.".toString())
        } catch (ExecutionException e) {
            out = failureFrom(p, e.cause ?: e)
        }
        out.units = budget.units
        out.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        out.logs = new ArrayList<String>(budget.logs)
        return out
    }

    /**
     * The line of the first backtick, or 0. JEXL builds `${a}${b}` template text through its template engine, not the
     * arithmetic, so it would skip the text caps, and JexlFeatures has no switch for it. Any backtick is refused, even
     * in a string or comment: a second lexer that skips quotes can be fooled (a ~/'/ regex literal), one that
     * refuses the character cannot.
     */
    static int templateLiteralLine(String source) {
        int at = source.indexOf('`')
        if (at < 0) return 0
        int line = 1
        for (int i = 0; i < at; i++) if (source.charAt(i) == ('\n' as char)) line++
        return line
    }

    /** Parses JSON text for an input; blank is an empty map. */
    static Object parseInputJson(String json) {
        if (!json?.trim()) return [:]
        if (json.length() > MAX_INPUT_JSON_CHARS) throw new IllegalArgumentException("The input is more than ${MAX_INPUT_JSON_CHARS} characters.".toString())
        try {
            return new JsonSlurper().parseText(json)
        } catch (Exception e) {
            throw new IllegalArgumentException("The input is not valid JSON: ${e.message?.readLines()?.first()}".toString())
        }
    }

    /** The library's functions and one-line help, for the editor. */
    static List<Map<String, String>> libraryCatalog() { ScriptLibrary.CATALOG }

    // -- input and output shaping

    /** A deep copy holding only maps, lists, text, numbers, true/false and null. */
    private static Object copyInput(Object input) {
        int[] nodes = [0] as int[]
        return copyNode(input, 0, nodes)
    }

    private static Object copyNode(Object value, int depth, int[] nodes) {
        if (++nodes[0] > 500_000) throw new IllegalArgumentException('The input holds more than 500000 values.')
        if (depth > 32) throw new IllegalArgumentException('The input is nested more than 32 levels deep.')
        if (value == null || value instanceof Boolean || value instanceof String) return value
        if (value instanceof BigDecimal) return checkInputDecimal((BigDecimal) value)
        if (value instanceof BigInteger || value instanceof Long || value instanceof Integer) return value
        if (value instanceof Number) return checkInputDecimal(new BigDecimal(value.toString()))
        if (value instanceof CharSequence || value instanceof Date || value instanceof java.time.temporal.Temporal) return value.toString()
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>()
            ((Map) value).each { Object k, Object v -> out.put(String.valueOf(k), copyNode(v, depth + 1, nodes)) }
            return out
        }
        if (value instanceof Collection) return ((Collection) value).collect { copyNode(it, depth + 1, nodes) }
        throw new IllegalArgumentException("The input holds a ${value.getClass().simpleName}, which is not a JSON value.".toString())
    }

    private static BigDecimal checkInputDecimal(BigDecimal d) {
        if (ScriptBudget.plainLength(d) > ScriptBudget.MAX_DECIMAL_CHARS) {
            throw new IllegalArgumentException("The input holds a number with more than ${ScriptBudget.MAX_DECIMAL_CHARS} digits.".toString())
        }
        return d
    }

    /**
     * What the script returned, as JSON values; anything else (a function, a host object) is refused. Runs on the
     * pool thread with the budget open: every node is charged, at most MAX_OUTPUT_NODES are shaped, and a non-list
     * collection (a range) is size-checked before it is walked.
     */
    private static Object toPlain(Object value, int depth, long[] nodes) {
        ScriptBudget.charge()
        // nodes[1] is the JSON size so far, kept as it is built, so the JSON is never written past the limit.
        nodes[1] += 4
        if (value instanceof String) nodes[1] += ((String) value).length() + 2
        else if (value instanceof BigDecimal) nodes[1] += ScriptBudget.plainLength(ScriptBudget.checkDecimal((BigDecimal) value))
        if (nodes[1] > MAX_OUTPUT_JSON_CHARS) {
            throw new ScriptLimitException('MEMORY', "The result is more than ${MAX_OUTPUT_JSON_CHARS} characters of JSON.".toString())
        }
        if (++nodes[0] > MAX_OUTPUT_NODES) throw new ScriptLimitException('MEMORY', "The result holds more than ${MAX_OUTPUT_NODES} values.".toString())
        if (depth > 32) throw new ScriptLimitException('OUTPUT', 'The result is nested more than 32 levels deep.')
        if (value == null || value instanceof Boolean || value instanceof String) return value
        if (value instanceof BigDecimal) return value
        if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue()
                if (d.isNaN() || d.isInfinite()) return null
            }
            return value
        }
        if (value instanceof Character) return value.toString()
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>()
            ((Map) value).each { Object k, Object v ->
                String key = ScriptBudget.render(k, 200)
                nodes[1] += key.length() + 3
                out.put(key, toPlain(v, depth + 1, nodes))
            }
            return out
        }
        if (value instanceof Collection) {
            List out = []
            for (Object item : (Collection) value) {
                out.add(toPlain(item, depth + 1, nodes))
                ScriptBudget.checkSize(out.size())
            }
            return out
        }
        if (value.getClass().isArray()) return ScriptArithmetic.arrayItems(value).collect { toPlain(it, depth + 1, nodes) }
        throw new ScriptLimitException('OUTPUT', 'The script returned something that is not data (a function?); return a value, list or map.')
    }

    // -- errors

    private static Map<String, Object> notRun(Map<String, Object> out) {
        out.units = 0L
        out.elapsedMs = 0L
        out.logs = [] as List<String>
        return out
    }

    private static Map<String, Object> failureFrom(String profile, Throwable thrown) {
        Throwable t = thrown
        int guard = 0
        while (t != null && guard++ < 10) {
            if (t instanceof ScriptLimitException) return failure(profile, ((ScriptLimitException) t).kind, t.message, thrown)
            if (t instanceof JexlException.Cancel || t instanceof InterruptedException) return failure(profile, 'TIME', 'The script ran out of time and was stopped.', thrown)
            t = t.cause == t ? null : t.cause
        }
        String kind = thrown instanceof JexlException.Variable || thrown instanceof JexlException.Property ? 'ACCESS' : 'ERROR'
        String text = thrown instanceof JexlException ? message((JexlException) thrown) : null
        if (thrown instanceof JexlException && thrown.cause != null && !(thrown.cause instanceof JexlException)) {
            text = (text ? text + ': ' : '') + (thrown.cause.message ?: thrown.cause.getClass().simpleName)
        }
        return failure(profile, kind, text ?: (thrown.message ?: thrown.getClass().simpleName), thrown)
    }

    private static Map<String, Object> failure(String profile, String kind, String message, Throwable thrown = null) {
        Map<String, Object> error = [kind: kind, message: message] as Map<String, Object>
        JexlInfo info = thrown instanceof JexlException ? ((JexlException) thrown).info : null
        if (info != null) {
            error.line = info.line
            error.column = info.column
        }
        return [ok: false, profile: profile, result: null, error: error] as Map<String, Object>
    }

    /** JEXL's message without its "script@1:5 " location prefix (the location is returned as line/column). */
    private static String message(JexlException e) {
        String raw = e.message ?: e.getClass().simpleName
        return raw.replaceFirst(/^\S*@\d+:\d+(?::\d+:\d+)?\s+/, '').trim()
    }
}
