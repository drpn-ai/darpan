package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.streak.AutomationStreakSupport
import groovy.json.JsonOutput

import java.sql.Timestamp
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

class AutomationRuntimeSupport {
    /**
     * A secret's key, bare or quoted, then = or :, then its value: a quoted value up to its closing quote (escapes allowed,
     * so spaces, commas and quotes inside it stay masked), a bare one up to a space, comma or ")". The key, separator and
     * quotes stay, so a JSON error body stays readable: "apiToken":"x" reads "apiToken":"***", apiToken=x reads apiToken=***.
     */
    private static final Pattern SECRET_VALUE =
            ~/(?i)(["']?)(password|privateKey|apiToken|apiKey|api_key|clientSecret|secret|token)\1(\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^,\s)]+)/
    private static final Pattern BEARER_VALUE = ~/(?i)\b(Bearer)\s+[A-Za-z0-9._~+\/=-]+/

    static def loadAutomation(def ec, String automationId) {
        // [P0#4 step 3] DUAL-CONTEXT: called by user-initiated run#AutomationNow (which gates via
        // TenantAccessSupport.canAccessTenantRecord in ReconciliationFacadeServices.xml before this is reached)
        // AND by the scheduled execute#Automation runner (no active tenant — scoping would fail-close and
        // break the scheduler). Use findGlobalUnscoped; trust anchor = automation.companyUserGroupId,
        // gated at the run#AutomationNow service entry point for user-facing calls.
        def automation = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.reconciliation.ReconciliationAutomation",
                "automation runner loads by id; tenant gated at run#AutomationNow user entry via canAccessTenantRecord")
                .condition("automationId", automationId)
                .useCache(false)
                .one()
        if (!automation) throw new IllegalArgumentException("Automation ${automationId} not found")
        return automation
    }

    static Map<String, Object> loadAutomationSources(def ec, String automationId) {
        // [P0#4 step 3] DUAL-CONTEXT: same as loadAutomation — system cron has no active tenant.
        // Trust anchor = automation.companyUserGroupId, gated at run#AutomationNow entry for user calls.
        List sources = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.reconciliation.ReconciliationAutomationSource",
                "automation runner loads by automationId; tenant gated at run#AutomationNow user entry via canAccessTenantRecord")
                .condition("automationId", automationId)
                .useCache(false)
                .list() ?: []
        return sources.collectEntries { source ->
            [(normalize(readField(source, "fileSide"))): source]
        } as Map<String, Object>
    }

    /**
     * Configured exclusion rules for one automation source side, ordered by sequenceNum, in GETTER
     * shape — {@code fieldExpression} reduced from the stored operator-facing JSONPath to the
     * top-level record key SourceFilterSupport.evaluate tests. The snapshot rows are copied
     * verbatim from the rule set, so they carry the same board-written expression the interactive
     * path stores, and skipping this reduction would make every scheduled exclusion a silent no-op
     * (the interactive equivalent is ReconciliationSavedRunSupport.resolveExtractExcludeFilters).
     *
     * [P0#4 step 3] DUAL-CONTEXT: same as loadAutomationSources — the scheduled runner has no active
     * tenant. Trust anchor is automation.companyUserGroupId, gated at run#AutomationNow for user calls.
     * DAR-BE-318 (plan 3b-2b, Task 14): the read is pinned to the automation's own tenant by an explicit
     * companyUserGroupId condition, since the scheduler's context has none to filter by (Review Focus 4). A row
     * stamped for another tenant is never read, whatever its automationId; a blank tenant throws.
     */
    static List<Map<String, Object>> loadAutomationSourceFilters(def ec, String automationId, String fileSide, String companyUserGroupId) {
        String tenant = normalize(companyUserGroupId)
        if (!tenant) throw new IllegalArgumentException("Automation ${automationId}'s source filters are read for its own tenant; it names none.")
        if (!automationId || !fileSide) return []
        List rows = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.reconciliation.ReconciliationAutomationSourceFilter",
                "automation runner loads by automationId; pinned to the automation's companyUserGroupId (the scheduler has no active tenant)")
                .condition("companyUserGroupId", tenant)
                .condition("automationId", automationId)
                .condition("fileSide", fileSide)
                .orderBy("sequenceNum")
                .useCache(false)
                .list() ?: []
        return SourceFilterSupport.toRecordFieldRules(rows.collect { def row ->
            [
                    sequenceNum    : row.sequenceNum,
                    fieldExpression: normalize(row.fieldExpression),
                    operator       : normalize(row.operator),
                    filterValues   : normalize(row.filterValues),
            ] as Map<String, Object>
        } as List<Map<String, Object>>)
    }

    static void updateAutomationExecution(def ec, def execution, Map<String, Object> fields) {
        if (!execution) return
        runInTransaction(ec, "Error updating reconciliation automation execution", {
            fields.findAll { it.value != null }.each { entry ->
                execution.set(entry.key as String, entry.value)
            }
            execution.update()
            return null
        })
        // DAR-BE-107: every run path ends a window here, so the streak folds before the run's message is built.
        if (AutomationStreakSupport.isTerminal(fields.statusEnumId)) AutomationStreakSupport.recordSlots(ec, execution.automationId)
    }

    static Object runInTransaction(def ec, String message, Closure work) {
        if (ec?.transaction?.metaClass?.respondsTo(ec.transaction, "runUseOrBegin", Integer, String, Closure)) {
            return ec.transaction.runUseOrBegin(30, message, work)
        }
        return work.call()
    }

    /**
     * Runs {@code work} in its OWN new transaction (Moqui's {@code runRequireNew}). Unlike
     * {@link #runInTransaction}, which JOINS an ambient transaction if one is already open,
     * {@code work} here is guaranteed to commit or roll back independently of whatever transaction
     * the caller happens to be running inside.
     *
     * Mechanism, verified against the framework rather than assumed: {@code runRequireNew}'s
     * {@code requireNewThread} flag is a {@code final static true}, so the live path hands the work to
     * a FRESH THREAD with an empty transaction stack; it does not suspend and resume the caller's
     * transaction on the calling thread (the suspend/resume branch is dead code). The isolation
     * guarantee is the same either way — the distinction matters only when reasoning about thread
     * affinity, e.g. that {@code work} must not assume thread-local caller state.
     *
     * A sweep that commits per unit (one tenant, one claim) needs this, not {@code runInTransaction}: invoked inside an
     * already-open ambient transaction, {@code runInTransaction} would join it instead of starting its own, so one unit's
     * failure would roll back units already done. Use this helper wherever per-unit transactional isolation must hold
     * unconditionally, not just when there is no ambient transaction.
     */
    static Object runInNewTransaction(def ec, String message, Closure work) {
        if (ec?.transaction?.metaClass?.respondsTo(ec.transaction, "runRequireNew", Integer, String, Closure)) {
            return ec.transaction.runRequireNew(30, message, work)
        }
        return work.call()
    }

    static String normalizeDataManagerPath(def ec, Object rawPath) {
        String normalized = normalize(rawPath)
        if (!normalized) return null

        String dataManagerLocation = DataManagerSupport.resolveDataManagerLocation(ec)
        if (normalized.startsWith(dataManagerLocation + "/")) {
            return DataManagerSupport.normalizeRelativePath(normalized.substring(dataManagerLocation.length() + 1))
        }

        String relativePath = DataManagerSupport.normalizeRelativePath(normalized)
        if (relativePath) return relativePath

        if (normalized.contains("://")) return null

        File root = DataManagerSupport.resolveDirectoryFile(ec, dataManagerLocation, false)
        if (root == null) return null

        File candidate = new File(normalized)
        try {
            def rootPath = root.canonicalFile.toPath()
            def candidatePath = candidate.canonicalFile.toPath()
            if (!candidatePath.startsWith(rootPath)) return null
            return rootPath.relativize(candidatePath).toString().replace(File.separator, "/")
        } catch (Exception ignored) {
            return null
        }
    }

    static String safeMetadataJson(Map<String, Object> metadata) {
        return truncate(JsonOutput.toJson(safeJsonValue(metadata)), 3900)
    }

    private static Object safeJsonValue(Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return value
        if (value instanceof Collection) return value.collect { safeJsonValue(it) }
        if (value instanceof Map) {
            return value.collectEntries { entry ->
                [(entry.key?.toString()): safeJsonValue(entry.value)]
            }
        }
        return value.toString()
    }

    static String currentUserId(def ec) {
        try {
            return TenantAccessSupport.currentUserId(ec)
        } catch (Exception ignored) {
            return normalize(ec?.user?.userId)
        }
    }

    static Timestamp nowTimestamp(def ec) {
        return ec?.user?.nowTimestamp ?: new Timestamp(System.currentTimeMillis())
    }

    static String requireNormalized(Object value, String message) {
        String normalized = normalize(value)
        if (!normalized) throw new IllegalArgumentException(message)
        return normalized
    }

    static String sanitizeErrorMessage(Throwable t) {
        // DAR-BE-031: a Java service's failure arrives wrapped as "Java method for service [..] threw an exception"; the
        // person reads the cause's own message.
        String plain = FacadeSupport.withoutServicePrefix(t?.message) ?: t?.cause?.message
        return sanitizeText(plain ?: t?.class?.name ?: "Automation execution failed")
    }

    static String sanitizeErrorDetail(Throwable t) {
        if (t == null) return null

        StringBuilder detail = new StringBuilder()
        Throwable cursor = t
        int depth = 0
        while (cursor != null && depth < 8) {
            if (depth > 0) detail.append("\nCaused by: ")
            detail.append(cursor.class.name)
            if (cursor.message) detail.append(": ").append(cursor.message)
            cursor.stackTrace?.each { StackTraceElement element ->
                detail.append("\n    at ").append(element.toString())
            }
            cursor = cursor.cause
            depth++
        }
        return sanitizeText(detail.toString())
    }

    static String sanitizeText(String value) {
        if (value == null) return null
        String keyed = value.replaceAll(SECRET_VALUE) { String all, String quote, String key, String separator, String secret ->
            String valueQuote = secret.startsWith('"') || secret.startsWith("'") ? secret.substring(0, 1) : ""
            "${quote}${key}${quote}${separator}${valueQuote}***${valueQuote}".toString()
        }
        return keyed.replaceAll(BEARER_VALUE, '$1 ***')
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value
        return value.substring(0, maxLength)
    }
}
