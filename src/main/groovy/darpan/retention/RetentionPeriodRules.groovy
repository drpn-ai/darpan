package darpan.retention

import groovy.transform.CompileDynamic

/**
 * DAR-BE-306. The pure rules for a retention period a person types: what a value means, and when a change can delete more
 * than before (the case that needs a fresh preview). No reads or writes; the services do those.
 */
@CompileDynamic
class RetentionPeriodRules {
    static final int MIN_DAYS = 1
    static final int MAX_DAYS = 3650
    static final String FOREVER = "forever"
    static final String INHERIT = "inherit"

    /**
     * One value for one table: a whole number of days from 1 to 3650, "forever" (blank or null is refused: an empty value must never silently keep everything), or, when
     * {@code allowInherit}, "inherit" (a tenant follows the instance period). Returns [days: n], [forever: true],
     * [inherit: true] or [error: why].
     */
    static Map parse(String table, Object raw, boolean allowInherit) {
        String text = raw == null ? "" : raw.toString().trim()
        if (text.equalsIgnoreCase(FOREVER)) return [forever: true]
        if (text.equalsIgnoreCase(INHERIT)) {
            return allowInherit ? ([inherit: true])
                    : ([error: "${table}: inherit means follow the instance period, so it only applies to a tenant.".toString()])
        }
        BigDecimal n
        try { n = new BigDecimal(text) } catch (NumberFormatException ignored) { n = null }
        if (n == null || n.stripTrailingZeros().scale() > 0 || n < MIN_DAYS || n > MAX_DAYS) {
            return [error: "${table}: '${text}' is not a whole number of days from ${MIN_DAYS} to ${MAX_DAYS}, or forever${allowInherit ? ', or inherit' : ''}.".toString()]
        }
        return [days: n.intValueExact()]
    }

    /** Null when every value in a preview map is acceptable (inherit is not a preview of the instance period); otherwise the first reason. */
    static String overrideProblem(Map overrides, boolean allowInherit) {
        if (overrides == null) return null
        for (Map.Entry e : overrides.entrySet()) {
            if (!RetentionSupport.TABLE_SETTINGS.containsKey(e.key)) return "Unknown retention table ${e.key}.".toString()
            Map parsed = parse(e.key, e.value, allowInherit)
            if (parsed.error != null) return parsed.error as String
        }
        return null
    }

    /** True when keeping {@code before} days (null = forever) and then {@code after} days (null = forever) can delete rows the first did not. */
    static boolean deletesMore(Integer before, Integer after) {
        if (after == null) return false
        return before == null || after < before
    }

    /**
     * What a requested change to the periods comes to, or {error}. {@code tenantLevel}: the request is for one tenant (then
     * "inherit" is allowed and the comparison is against the tenant's own row); otherwise for the instance. {@code current} is
     * RetentionSupport.periods for that scope today, {@code instance} the instance's. A value that changes nothing is dropped.
     * Result: changes [{table, key, mode days|forever|inherit, before, beforeSource, after (effective days or null)}], whatIf
     * (the dry run's periodDays / tenantPeriodDays for the changed tables), deletesMore (any change keeps rows for less time,
     * or ends a keep-forever), summary (`TABLE: 90d -> 30d; ...`, for the audit row).
     */
    static Map plan(boolean tenantLevel, Map<String, Map> current, Map<String, Map> instance, Map requested) {
        List<Map> changes = []
        Map whatIf = [:]
        List<String> parts = []
        boolean more = false
        for (Map.Entry e : requested.entrySet()) {
            String table = e.key?.toString()?.trim()
            if (!RetentionSupport.TABLE_SETTINGS.containsKey(table)) {
                return [error: "Unknown retention table ${table}. The tables are ${RetentionSupport.TABLE_SETTINGS.keySet().join(', ')}.".toString()]
            }
            Map p = parse(table, e.value, tenantLevel)
            if (p.error) return [error: p.error]
            Map cur = current[table]
            Integer after = p.inherit ? (instance[table].periodDays as Integer) : (p.days as Integer)
            boolean same = tenantLevel ? (p.inherit ? cur.source == RetentionSupport.INSTANCE : (cur.source == RetentionSupport.TENANT && cur.periodDays == after))
                    : cur.periodDays == after
            if (same) continue
            String mode = p.inherit ? INHERIT : (p.forever ? FOREVER : "days")
            whatIf[table] = p.inherit ? INHERIT : (p.forever ? FOREVER : p.days)
            more = more || deletesMore(cur.periodDays as Integer, after)
            changes << [table: table, key: RetentionSupport.TABLE_SETTINGS[table], mode: mode, before: cur.periodDays, beforeSource: cur.source, after: after]
            parts << "${table}: ${label(cur.periodDays, tenantLevel && cur.source == RetentionSupport.INSTANCE ? 'instance ' : '')} -> ${p.inherit ? "inherit (${label(after, 'instance ')})" : label(after, '')}".toString()
        }
        return [changes: changes, whatIf: whatIf, deletesMore: more, summary: parts.join("; ")]
    }

    private static String label(Object days, String prefix) { return days == null ? "${prefix}forever".toString() : "${prefix}${days}d".toString() }
}
