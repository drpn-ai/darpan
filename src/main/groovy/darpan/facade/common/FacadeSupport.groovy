package darpan.facade.common

class FacadeSupport {
    static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    static boolean normalizeBool(Object value, boolean defaultValue) {
        if (value == null) return defaultValue
        if (value instanceof Boolean) return (boolean) value
        String raw = normalize(value)?.toLowerCase()
        if (["y", "yes", "true", "1", "on"].contains(raw)) return true
        if (["n", "no", "false", "0", "off"].contains(raw)) return false
        return defaultValue
    }

    static Integer normalizeInt(Object value, Integer defaultValue) {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        String raw = normalize(value)
        if (!raw) return defaultValue
        try {
            return Integer.parseInt(raw)
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    static List<String> getMessages(def ec) {
        return (ec.message?.getMessages() ?: []) as List<String>
    }

    static List<String> getErrors(def ec) {
        List<String> baseErrors = (ec.message?.getErrors() ?: []) as List<String>
        List<String> validationErrors = []
        (ec.message?.getValidationErrors() ?: []).each { ve ->
            if (ve?.message) validationErrors.add(ve.message.toString())
        }
        return (baseErrors + validationErrors).findAll { it }
    }

    static Map<String, Object> envelope(def ec) {
        return [
            ok: !ec.message?.hasError(),
            messages: getMessages(ec),
            errors: getErrors(ec)
        ]
    }

    static String enumLabel(def item) {
        return item?.enumCode?.toString()?.trim() ?: item?.description?.toString()?.trim() ?: item?.enumId?.toString()?.trim()
    }
}
