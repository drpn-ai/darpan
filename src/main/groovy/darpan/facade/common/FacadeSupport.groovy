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

    /**
     * Display label for an enumeration.
     *
     * A source system is a proper noun the operator recognises — Shopify, HotWax, NetSuite — and its
     * enumCode is a machine code (SHOPIFY, HOTWAX, NETSUITE). Showing the code made the same system
     * read two different ways depending on the surface. So for DarpanSystemSource the description
     * wins; this used to be special-cased for OMS alone, which fixed HotWax and left every other
     * system showing its code.
     *
     * Every other enum type keeps enumCode-first: those codes are the short display strings the rest
     * of the app already expects, and their descriptions are often longer prose.
     */
    static String enumLabel(def item) {
        if (isDarpanSystemSourceOption(item)) {
            return normalize(item?.description) ?: normalize(item?.enumCode) ?: normalize(item?.enumId)
        }
        return normalize(item?.enumCode) ?: normalize(item?.description) ?: normalize(item?.enumId)
    }

    private static boolean isDarpanSystemSourceOption(def item) {
        return normalize(item?.enumTypeId) == "DarpanSystemSource"
    }
}
