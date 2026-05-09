package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

class AutomationRuntimeSupport {
    static def loadAutomation(def ec, String automationId) {
        def automation = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomation")
                ?.condition("automationId", automationId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        if (!automation) throw new IllegalArgumentException("Automation ${automationId} not found")
        return automation
    }

    static Map<String, Object> loadAutomationSources(def ec, String automationId) {
        List sources = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomationSource")
                ?.condition("automationId", automationId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.list() ?: []
        return sources.collectEntries { source ->
            [(normalize(readField(source, "fileSide"))): source]
        } as Map<String, Object>
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
    }

    static Object runInTransaction(def ec, String message, Closure work) {
        if (ec?.transaction?.metaClass?.respondsTo(ec.transaction, "runUseOrBegin", Integer, String, Closure)) {
            return ec.transaction.runUseOrBegin(30, message, work)
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

    static Object safeJsonValue(Object value) {
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
        return sanitizeText(t?.message ?: t?.class?.name ?: "Automation execution failed")
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
        return value?.replaceAll(/(?i)(password|privateKey|apiToken|token)\s*[:=]\s*[^,\s)]+/, "\$1=***")
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value
        return value.substring(0, maxLength)
    }
}
