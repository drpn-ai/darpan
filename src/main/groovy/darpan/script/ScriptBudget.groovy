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

    static void checkSize(int size) {
        ScriptBudget budget = CURRENT.get()
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
