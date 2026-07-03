package darpan.facade.common

import static darpan.common.ValueSupport.normalize

class FacadeSupport {
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

    static def findEnum(def ec, Object enumId) {
        String normalized = normalize(enumId)
        if (!normalized) return null
        def finder = ec.entity.find("moqui.basic.Enumeration").condition("enumId", normalized)
        // Guarded for test-stub compatibility – production EntityFind always provides both methods.
        if (finder.metaClass.respondsTo(finder, "disableAuthz")) finder.disableAuthz()
        if (finder.metaClass.respondsTo(finder, "useCache", Boolean)) finder.useCache(true)
        return finder.one()
    }

    static String enumLabel(def item) {
        if (isHotWaxOmsSystemSourceOption(item)) {
            return normalize(item?.description) ?: "HotWax"
        }
        return normalize(item?.enumCode) ?: normalize(item?.description) ?: normalize(item?.enumId)
    }

    private static boolean isHotWaxOmsSystemSourceOption(def item) {
        return normalize(item?.enumTypeId) == "DarpanSystemSource" &&
                normalize(item?.enumId) == "OMS"
    }
}
