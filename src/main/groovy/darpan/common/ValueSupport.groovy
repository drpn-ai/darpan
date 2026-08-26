package darpan.common

import java.util.Locale
import java.util.Objects

class ValueSupport {
    static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    static String normalizeBlankToNull(Object value) {
        String normalized = normalize(value)
        return normalized ?: null
    }

    static String normalizeLower(Object value) {
        return normalize(value)?.toLowerCase(Locale.ROOT)
    }

    static String normalizeUpper(Object value) {
        return normalize(value)?.toUpperCase(Locale.ROOT)
    }

    static boolean normalizeBool(Object value, boolean defaultValue = false) {
        if (value == null) return defaultValue
        if (value instanceof Boolean) return (Boolean) value

        String normalized = normalizeLower(value)
        if (["y", "yes", "true", "1", "on"].contains(normalized)) return true
        if (["n", "no", "false", "0", "off"].contains(normalized)) return false
        return defaultValue
    }

    static String stringify(Object value) {
        return value == null ? null : value.toString()
    }

    static boolean valuesDiffer(Object left, Object right) {
        return !Objects.equals(left, right)
    }

    static Integer normalizeInt(Object value, Integer defaultValue = null) {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()

        String normalized = normalize(value)
        if (!normalized) return defaultValue
        try {
            return Integer.parseInt(normalized)
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    static int boundedInt(Object value, int defaultValue, int minValue, int maxValue) {
        int normalized = normalizeInt(value, defaultValue)
        return Math.max(minValue, Math.min(maxValue, normalized))
    }

    static Long normalizeLong(Object value, Long defaultValue = null) {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).longValue()

        String normalized = normalize(value)
        if (!normalized) return defaultValue
        try {
            return Long.parseLong(normalized)
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    static String sanitizeFileToken(Object value, String fallback) {
        String sanitized = normalize(value)?.replaceAll(/[^A-Za-z0-9._-]/, "_")
        return sanitized ?: fallback
    }

    static String sanitizePathFileName(Object value, String fallback) {
        String normalized = normalize(value)
        if (!normalized) return fallback

        List<String> pathParts = normalized.tokenize("/\\")
        String fileName = pathParts ? pathParts.last() : normalized
        return sanitizeFileToken(fileName, fallback)
    }

    static String sanitizeDisplayName(Object value, String fallback) {
        String sanitized = normalize(value)?.replaceAll(/[^A-Za-z0-9 _.-]/, "_")
        return sanitized ?: fallback
    }

    static String fileNameFromPath(Object value) {
        String normalized = normalize(value)
        if (!normalized) return null

        List<String> pathParts = normalized.tokenize("/\\")
        return pathParts ? pathParts.last() : normalized
    }

    /**
     * Whether {@code record} actually carries {@code fieldName} at all.
     *
     * <p>Ask this BEFORE {@link #readField} on anything that might be a Moqui EntityValue:
     * {@code EntityValueBase.get} THROWS an EntityException on a name the entity does not declare,
     * rather than returning null the way a Map does. Two run sources with different shapes reach the
     * same shared code — a saved run passes a Map carrying {@code sourceConfigType}, a scheduled run
     * passes a ReconciliationAutomationSource row that has no such column — so "is there one" and
     * "what is it" are genuinely different questions here.</p>
     */
    static boolean hasField(def record, String fieldName) {
        if (record == null || !fieldName) return false
        if (record instanceof Map) return ((Map) record).containsKey(fieldName)
        if (record.metaClass.respondsTo(record, "isField", String)) return record.isField(fieldName)
        return record.metaClass.hasProperty(record, fieldName) != null
    }

    /** {@link #readString} for a field that may not exist on this record shape; null when absent. */
    static String readOptionalString(def record, String fieldName) {
        return hasField(record, fieldName) ? normalize(readField(record, fieldName)) : null
    }

    static Object readField(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return record[fieldName]
        if (record.metaClass.respondsTo(record, "get", String)) return record.get(fieldName)
        return record."${fieldName}"
    }

    static String readString(def record, String fieldName) {
        return normalize(readField(record, fieldName))
    }
}
