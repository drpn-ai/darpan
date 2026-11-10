package darpan.reconciliation.core

/**
 * DAR-BE-031. A run's ID field is chosen from a platform-wide field list, but whether a field exists depends on the
 * tenant's own system (Mephisto's OMS sends no shopifyReturnId; gorjana's does). When the records lack it, Spark fails
 * with a struct-field error after both paid extracts. This turns that error into what the person needs: which field,
 * on which side, what the records do have, and the remedy.
 */
class CompareFieldErrors {
    // [FIELD_NOT_FOUND] No such struct field `x` in `a`, `b`.
    private static final java.util.regex.Pattern STRUCT = ~/No such struct field `([^`]+)` in (.+?)(?:\. SQLSTATE.*|\.?$)/
    // [UNRESOLVED_COLUMN...] A column or function parameter with name `x` cannot be resolved. Did you mean one of the following? [`a`, `b`].
    private static final java.util.regex.Pattern COLUMN = ~/name `([^`]+)` cannot be resolved(?:.*?\[(.+?)\])?/

    /** The plain message for a missing-field failure anywhere in the cause chain; null for any other error. */
    static String plainMessage(Throwable t, String sideLabel) {
        for (Throwable c = t; c != null; c = c.cause) {
            String m = c.message ?: ""
            def s = STRUCT.matcher(m)
            if (s.find()) return words(s.group(1), s.group(2), sideLabel)
            def col = COLUMN.matcher(m)
            if (col.find()) return words(col.group(1), col.group(2), sideLabel)
            if (c.cause == c) break
        }
        return null
    }

    private static String words(String field, String available, String sideLabel) {
        String name = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field
        List<String> have = (available ?: "").findAll(/`([^`]+)`/) { all, f -> f } as List<String>
        String side = sideLabel ?: "one side's"
        return ("The ID field '${name}' is not in the ${side} records of this run. Pick a different ID field for this run." +
                (have ? " They have: ${have.join(', ')}." : "")).toString()
    }
}
