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

    static String enumLabel(def item) {
        if (normalize(item?.enumTypeId) == "DarpanSystemSource" &&
                normalize(item?.enumId) == "OMS") {
            return normalize(item?.description) ?: "HotWax"
        }
        return normalize(item?.enumCode) ?: normalize(item?.description) ?: normalize(item?.enumId)
    }
}
