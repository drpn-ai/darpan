package darpan.facade.common

class PilotAccessSupport {
    static final String ADMIN_USER_GROUP_ID = "ADMIN"
    static final String SCOPE_TYPE_ANONYMOUS = "ANONYMOUS"
    static final String SCOPE_TYPE_CUSTOMER = "CUSTOMER"
    static final String SCOPE_TYPE_GLOBAL = "GLOBAL"
    static final String OWNED_RECORD_UNAVAILABLE_MESSAGE = "Requested record is not available in your customer scope."

    static Map<String, Object> buildAccessScope(def ec) {
        String userId = currentUserId(ec)
        boolean superAdmin = isSuperAdmin(ec)
        return [
            isSuperAdmin: superAdmin,
            scopeType: !userId ? SCOPE_TYPE_ANONYMOUS : (superAdmin ? SCOPE_TYPE_GLOBAL : SCOPE_TYPE_CUSTOMER),
            customerScopeId: superAdmin ? null : userId,
        ]
    }

    static boolean isSuperAdmin(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return false

        def finder = ec?.entity?.find("moqui.security.UserGroupMember")
        if (finder == null) return false

        finder.condition("userId", userId).condition("userGroupId", ADMIN_USER_GROUP_ID)
        if (finder.metaClass.respondsTo(finder, "conditionDate", String, String, Object)) {
            finder.conditionDate("fromDate", "thruDate", ec?.user?.nowTimestamp)
        }
        if (finder.metaClass.respondsTo(finder, "useCache", Boolean)) {
            finder.useCache(false)
        }
        return finder.one() != null
    }

    static boolean requireSuperAdmin(def ec, String message = "This operation requires super-admin access.") {
        if (isSuperAdmin(ec)) return true
        ec?.message?.addError(message)
        return false
    }

    static void applyScopeToSessionInfo(def ec, Map<String, Object> sessionInfo) {
        if (sessionInfo == null) return
        sessionInfo.putAll(buildAccessScope(ec))
    }

    static void applyOwnerFilter(def finder, def ec, String ownerField = "ownerUserId") {
        if (finder == null || isSuperAdmin(ec)) return
        String userId = currentUserId(ec)
        if (!userId) return
        finder.condition(ownerField, userId)
    }

    static boolean canAccessOwnedRecord(def ec, def record, String ownerField = "ownerUserId") {
        if (record == null) return false
        if (isSuperAdmin(ec)) return true
        String ownerUserId = extractString(record, ownerField)
        String currentUserId = currentUserId(ec)
        return ownerUserId != null && ownerUserId == currentUserId
    }

    static void requireOwnedRecordAccess(def ec, def record, String missingMessage = "Requested record was not found.",
            String forbiddenMessage = OWNED_RECORD_UNAVAILABLE_MESSAGE, String ownerField = "ownerUserId") {
        if (record == null) {
            ec?.message?.addError(missingMessage)
            return
        }
        if (!canAccessOwnedRecord(ec, record, ownerField)) {
            ec?.message?.addError(forbiddenMessage)
        }
    }

    static void assignOwnerOnCreate(def value, def ec, String ownerField = "ownerUserId") {
        if (value == null || isSuperAdmin(ec)) return
        String userId = currentUserId(ec)
        if (!userId) return
        if (value instanceof Map) {
            value[ownerField] = userId
        } else {
            value."${ownerField}" = userId
        }
    }

    static String resolveScopedRuntimeLocation(def ec, String baseLocation) {
        String normalizedBase = FacadeSupport.normalize(baseLocation)
        if (!normalizedBase || isSuperAdmin(ec)) return normalizedBase

        String userId = currentUserId(ec)
        if (!userId) return normalizedBase

        String scopedSuffix = "user/${sanitizePathToken(userId)}"
        if (normalizedBase.endsWith(scopedSuffix)) return normalizedBase
        return normalizedBase + (normalizedBase.endsWith("/") ? "" : "/") + scopedSuffix
    }

    static String resolveGenericTempLocation(def ec) {
        String baseLocation = ec?.resource?.properties?.get("reconciliation.temp.location")?.toString()
        return resolveScopedRuntimeLocation(ec, baseLocation ?: "runtime://tmp/reconciliation/generic")
    }

    static String resolveGenericOutputLocation(def ec) {
        String tempLocation = resolveGenericTempLocation(ec)
        return tempLocation + (tempLocation.endsWith("/") ? "" : "/") + "output"
    }

    static String currentUserId(def ec) {
        return FacadeSupport.normalize(ec?.user?.userId)
    }

    protected static String extractString(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return FacadeSupport.normalize(record[fieldName])
        if (record.metaClass.respondsTo(record, "getString", String)) {
            return FacadeSupport.normalize(record.getString(fieldName))
        }
        return FacadeSupport.normalize(record."${fieldName}")
    }

    protected static String sanitizePathToken(String rawToken) {
        String normalized = FacadeSupport.normalize(rawToken)
        if (!normalized) return "anonymous"
        return normalized.replaceAll(/[^A-Za-z0-9._-]/, "_")
    }
}
