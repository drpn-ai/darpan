package darpan.script

import groovy.transform.CompileStatic

/**
 * DAR-BE-164. What one evaluation may still spend, and what it logged. Held in a ThreadLocal for the evaluation's
 * duration: the engine and its arithmetic are shared across tenants, so per-call state cannot live on them.
 */
@CompileStatic
class ScriptBudget {

    private static final ThreadLocal<ScriptBudget> CURRENT = new ThreadLocal<>()

    final long unitLimit
    final int maxStringChars
    final int maxCollectionSize
    long units = 0L
    long charsMade = 0L
    final List<String> logs = []

    ScriptBudget(long unitLimit, int maxStringChars, int maxCollectionSize) {
        this.unitLimit = unitLimit
        this.maxStringChars = maxStringChars
        this.maxCollectionSize = maxCollectionSize
    }

    static ScriptBudget current() { CURRENT.get() }

    static void open(ScriptBudget budget) { CURRENT.set(budget) }

    static void close() { CURRENT.remove() }

    /** Spends n units; past the limit the script stops. No budget open means a host call (tests, validation): free. */
    static void charge(long n = 1L) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null) return
        // A negative charge would refund units (an overflowed Collection.size() is negative): treat it as the limit.
        if (n < 0) throw new ScriptLimitException("MEMORY", "A list is too large to count.")
        budget.units += n
        if (budget.units > budget.unitLimit) {
            throw new ScriptLimitException("UNITS", "The script used more than ${budget.unitLimit} units of work.".toString())
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptLimitException("TIME", "The script ran out of time.")
        }
    }

    /** A string the script made: capped in length, and in total characters made across the evaluation. */
    static String checkString(String value) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null || value == null) return value
        if (value.length() > budget.maxStringChars) {
            throw new ScriptLimitException("MEMORY", "A text value grew past ${budget.maxStringChars} characters.".toString())
        }
        budget.charsMade += value.length()
        if (budget.charsMade > budget.maxStringChars * 64L) {
            throw new ScriptLimitException("MEMORY", "The script built more than ${budget.maxStringChars * 64L} characters of text.".toString())
        }
        return value
    }

    /**
     * Refuses a text of this length BEFORE it is built, so the cap holds at the peak allocation, not after it.
     * Counts toward the total made.
     */
    static void checkLength(long length) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null) return
        if (length > budget.maxStringChars) {
            throw new ScriptLimitException("MEMORY", "A text value would grow past ${budget.maxStringChars} characters.".toString())
        }
        if (budget.charsMade + length > budget.maxStringChars * 64L) {
            throw new ScriptLimitException("MEMORY", "The script built more than ${budget.maxStringChars * 64L} characters of text.".toString())
        }
    }

    /** A value is scalar when its text form is its own size: text, numbers, true/false, null. */
    static boolean isScalar(Object x) {
        return x == null || x instanceof CharSequence || x instanceof Number || x instanceof Boolean || x instanceof Character
    }

    /**
     * Text of any value, at most limit characters (then '…'), built node by node and charged, never through a
     * collection's own toString (which would build the whole thing first).
     */
    static String render(Object x, int limit) {
        StringBuilder out = new StringBuilder()
        renderInto(x, out, limit)
        return out.length() > limit ? out.substring(0, limit) + '…' : out.toString()
    }

    private static void renderInto(Object x, StringBuilder out, int limit) {
        if (out.length() > limit) return
        charge()
        if (x instanceof BigDecimal) { out.append(checkDecimal((BigDecimal) x).toPlainString()); return }
        if (isScalar(x)) {
            String text = String.valueOf(x)
            int room = Math.max(0, limit + 1 - out.length())
            out.append(text, 0, Math.min(text.length(), room))
            return
        }
        if (x instanceof Map) {
            out.append('{')
            boolean first = true
            for (Map.Entry e : ((Map) x).entrySet()) {
                if (out.length() > limit) return
                if (!first) out.append(', ')
                first = false
                renderInto(e.key, out, limit)
                out.append(': ')
                renderInto(e.value, out, limit)
            }
            out.append('}')
            return
        }
        if (x instanceof Iterable || (x != null && x.getClass().isArray())) {
            Iterable items = x instanceof Iterable ? (Iterable) x : ScriptArithmetic.arrayItems(x)
            out.append('[')
            boolean first = true
            for (Object item : items) {
                if (out.length() > limit) return
                if (!first) out.append(', ')
                first = false
                renderInto(item, out, limit)
            }
            out.append(']')
            return
        }
        out.append('(not data)')
    }

    /** A decimal's plain text is at most this long; 1E999999999 would print a billion digits. */
    static final int MAX_DECIMAL_CHARS = 1_000

    /** Plain-text length of a decimal, worked out without building it. */
    static long plainLength(BigDecimal d) {
        return (long) d.precision() + Math.max(0L, -(long) d.scale()) + Math.max(0L, (long) d.scale() - d.precision()) + 3L
    }

    /** Refuses a decimal whose plain text would be longer than MAX_DECIMAL_CHARS. Applied wherever one enters. */
    static BigDecimal checkDecimal(BigDecimal d) {
        if (d != null && plainLength(d) > MAX_DECIMAL_CHARS) {
            throw new ScriptLimitException("NUMBER", "A number has more than ${MAX_DECIMAL_CHARS} digits.".toString())
        }
        return d
    }

    /**
     * A copy of any collection, counted as it is walked: never trusts Collection.size(), which a huge range
     * overflows to a negative number.
     */
    static List copyCounted(Iterable items) {
        List out = new ArrayList()
        for (Object item : items) {
            charge()
            out.add(item)
            checkSize(out.size())
        }
        return out
    }

    static void checkSize(int size) {
        ScriptBudget budget = CURRENT.get()
        if (size < 0) throw new ScriptLimitException("MEMORY", "A list is too large to count.")
        if (budget != null && size > budget.maxCollectionSize) {
            throw new ScriptLimitException("MEMORY", "A list or map grew past ${budget.maxCollectionSize} entries.".toString())
        }
    }

    static void log(String line) {
        ScriptBudget budget = CURRENT.get()
        if (budget == null || budget.logs.size() >= 50) return
        budget.logs << (line.length() > 500 ? line.substring(0, 500) + '…' : line)
    }
}
