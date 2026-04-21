package darpan.facade.common

class PilotAccessSupport {
    static final String ADMIN_USER_GROUP_ID = "ADMIN"
    static final String ALL_USERS_GROUP_ID = "ALL_USERS"
    static final String ACTIVE_COMPANY_PREFERENCE_KEY = "darpan.auth.activeCompanyUserGroupId"
    static final String DARPAN_COMPANY_GROUP_TYPE_ENUM_ID = "UgtDarpanCompany"
    static final String SCOPE_TYPE_ANONYMOUS = "ANONYMOUS"
    static final String SCOPE_TYPE_COMPANY = "COMPANY"
    static final String ACTIVE_COMPANY_UNAVAILABLE_MESSAGE = "Requested company is not available for current user."
    static final String COMPANY_RECORD_UNAVAILABLE_MESSAGE = "Requested record is not available in your active company."
    static final String NO_ACTIVE_COMPANY_FILTER_VALUE = "__NO_ACTIVE_COMPANY__"
    static final String OWNED_RECORD_UNAVAILABLE_MESSAGE = "Requested record is not available in your customer scope."

    static Map<String, Object> buildAccessScope(def ec) {
        Map<String, Object> scope = resolveAccessScope(ec)
        applyAccessScopeToUserContext(ec, scope)
        return scope
    }

    static void syncUserContext(def ec) {
        applyAccessScopeToUserContext(ec, resolveAccessScope(ec))
    }

    protected static Map<String, Object> resolveAccessScope(def ec) {
        String userId = currentUserId(ec)
        boolean superAdmin = isSuperAdmin(ec)
        if (!userId) {
            return [
                isSuperAdmin            : false,
                scopeType               : SCOPE_TYPE_ANONYMOUS,
                customerScopeId         : null,
                activeCompanyUserGroupId: null,
                activeCompanyLabel      : null,
                availableCompanies      : [],
            ]
        }

        List<Map<String, Object>> availableCompanies = listAvailableCompanies(ec)
        Map<String, Object> activeCompany = resolveActiveCompany(availableCompanies, readPreferredActiveCompanyUserGroupId(ec))
        return [
            isSuperAdmin            : superAdmin,
            scopeType               : SCOPE_TYPE_COMPANY,
            customerScopeId         : activeCompany?.userGroupId,
            activeCompanyUserGroupId: activeCompany?.userGroupId,
            activeCompanyLabel      : activeCompany?.label,
            availableCompanies      : availableCompanies,
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

    static List<Map<String, Object>> listAvailableCompanies(def ec) {
        List companyRecords = isSuperAdmin(ec) ? listAllCompanyRecords(ec) : listCompanyMembershipRecords(ec)
        return companyRecords
                .collect { record ->
                    String userGroupId = extractString(record, "userGroupId")
                    if (!userGroupId || userGroupId in [ADMIN_USER_GROUP_ID, ALL_USERS_GROUP_ID]) return null

                    [
                        userGroupId: userGroupId,
                        label      : resolveCompanyLabel(record, userGroupId),
                    ]
                }
                .findAll { it != null }
                .unique { it.userGroupId }
                .sort { left, right ->
                    String leftLabel = FacadeSupport.normalize(left?.label) ?: left?.userGroupId ?: ""
                    String rightLabel = FacadeSupport.normalize(right?.label) ?: right?.userGroupId ?: ""
                    int labelCompare = leftLabel <=> rightLabel
                    return labelCompare != 0 ? labelCompare : ((left?.userGroupId ?: "") <=> (right?.userGroupId ?: ""))
                } as List<Map<String, Object>>
    }

    static boolean saveActiveCompany(def ec, Object requestedCompanyUserGroupId) {
        if (!currentUserId(ec)) {
            ec?.message?.addError("Authentication required to change the active company.")
            return false
        }

        String normalizedCompanyUserGroupId = FacadeSupport.normalize(requestedCompanyUserGroupId)
        if (!normalizedCompanyUserGroupId) {
            ec?.message?.addError("activeCompanyUserGroupId is required.")
            return false
        }

        List<Map<String, Object>> availableCompanies = listAvailableCompanies(ec)
        if (!availableCompanies) {
            ec?.message?.addError("No company is available for the current user.")
            return false
        }

        Map<String, Object> requestedCompany = availableCompanies.find {
            it.userGroupId == normalizedCompanyUserGroupId
        }
        if (requestedCompany == null) {
            ec?.message?.addError(ACTIVE_COMPANY_UNAVAILABLE_MESSAGE)
            return false
        }

        ec?.user?.setPreference(ACTIVE_COMPANY_PREFERENCE_KEY, normalizedCompanyUserGroupId)
        applyAccessScopeToUserContext(ec, resolveAccessScope(ec))
        return true
    }

    static String currentActiveCompanyUserGroupId(def ec) {
        if (!currentUserId(ec)) return null
        List<Map<String, Object>> availableCompanies = listAvailableCompanies(ec)
        return resolveActiveCompany(availableCompanies, readPreferredActiveCompanyUserGroupId(ec))?.userGroupId
    }

    static void applyScopeToSessionInfo(def ec, Map<String, Object> sessionInfo) {
        if (sessionInfo == null) return
        sessionInfo.putAll(buildAccessScope(ec))
    }

    static Map<String, Object> buildSessionInfo(def ec) {
        Map<String, Object> sessionInfo = [
            userId  : ec?.user?.userId,
            username: ec?.user?.username,
            locale  : ec?.l10n?.locale?.toLanguageTag(),
            timeZone: ec?.user?.userAccount?.timeZone ?: ec?.l10n?.timeZone,
        ]
        applyScopeToSessionInfo(ec, sessionInfo)
        return sessionInfo
    }

    static boolean canAccessCompanyRecord(def ec, def record, String companyField = "companyUserGroupId") {
        if (record == null) return false
        String activeCompanyUserGroupId = currentActiveCompanyUserGroupId(ec)
        if (!activeCompanyUserGroupId) return false
        String recordCompanyUserGroupId = extractString(record, companyField)
        return recordCompanyUserGroupId != null && recordCompanyUserGroupId == activeCompanyUserGroupId
    }

    static void requireCompanyRecordAccess(def ec, def record, String missingMessage = "Requested record was not found.",
            String forbiddenMessage = COMPANY_RECORD_UNAVAILABLE_MESSAGE, String companyField = "companyUserGroupId") {
        if (record == null) {
            ec?.message?.addError(missingMessage)
            return
        }
        if (!canAccessCompanyRecord(ec, record, companyField)) {
            ec?.message?.addError(forbiddenMessage)
        }
    }

    static void assignCompanyOwnershipOnCreate(def value, def ec, String companyField = "companyUserGroupId",
            String createdByField = "createdByUserId") {
        if (value == null) return

        String activeCompanyUserGroupId = currentActiveCompanyUserGroupId(ec)
        if (!activeCompanyUserGroupId) {
            ec?.message?.addError("An active company is required for company-scoped data.")
            return
        }

        String userId = currentUserId(ec)
        if (value instanceof Map) {
            value[companyField] = activeCompanyUserGroupId
            if (userId) value[createdByField] = userId
        } else {
            value."${companyField}" = activeCompanyUserGroupId
            if (userId) value."${createdByField}" = userId
        }
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
        if (!normalizedBase) return normalizedBase

        String scopedSuffix = resolveRuntimeScopeSuffix(ec)
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

    protected static List listCompanyMembershipRecords(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return []

        def finder = ec?.entity?.find("moqui.security.UserGroupAndMember")
        if (finder == null) return []

        finder.condition("userId", userId).condition("groupTypeEnumId", DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
        if (finder.metaClass.respondsTo(finder, "conditionDate", String, String, Object)) {
            finder.conditionDate("fromDate", "thruDate", ec?.user?.nowTimestamp)
        }
        if (finder.metaClass.respondsTo(finder, "useCache", Boolean)) {
            finder.useCache(false)
        }
        if (!finder.metaClass.respondsTo(finder, "list")) return []

        def membershipList = finder.list()
        return membershipList instanceof Collection ? membershipList as List : []
    }

    protected static List listAllCompanyRecords(def ec) {
        def finder = ec?.entity?.find("moqui.security.UserGroup")
        if (finder == null) return []

        finder.condition("groupTypeEnumId", DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
        if (finder.metaClass.respondsTo(finder, "useCache", Boolean)) {
            finder.useCache(false)
        }
        if (!finder.metaClass.respondsTo(finder, "list")) return []

        def companyList = finder.list()
        return companyList instanceof Collection ? companyList as List : []
    }

    protected static String extractString(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return FacadeSupport.normalize(record[fieldName])
        if (record.metaClass.respondsTo(record, "getString", String)) {
            return FacadeSupport.normalize(record.getString(fieldName))
        }
        return FacadeSupport.normalize(record."${fieldName}")
    }

    protected static String readPreferredActiveCompanyUserGroupId(def ec) {
        return FacadeSupport.normalize(ec?.user?.getPreference(ACTIVE_COMPANY_PREFERENCE_KEY))
    }

    protected static void applyAccessScopeToUserContext(def ec, Map<String, Object> scope) {
        Map<String, Object> userContext = ec?.user?.context as Map<String, Object>
        if (userContext == null) return

        if (!currentUserId(ec)) {
            userContext.remove("activeCompanyUserGroupId")
            userContext.remove("activeCompanyLabel")
            userContext.remove("availableCompanyUserGroupIds")
            userContext.remove("isSuperAdmin")
            userContext.remove("scopeType")
            return
        }

        List<String> availableCompanyUserGroupIds = ((scope?.availableCompanies ?: []) as List)
                .collect { Map<String, Object> company -> FacadeSupport.normalize(company?.userGroupId) }
                .findAll { it != null } as List<String>
        String contextCompanyUserGroupId = FacadeSupport.normalize(scope?.activeCompanyUserGroupId) ?: NO_ACTIVE_COMPANY_FILTER_VALUE

        userContext.activeCompanyUserGroupId = contextCompanyUserGroupId
        userContext.activeCompanyLabel = FacadeSupport.normalize(scope?.activeCompanyLabel)
        userContext.availableCompanyUserGroupIds = availableCompanyUserGroupIds
        userContext.isSuperAdmin = scope?.isSuperAdmin == true
        userContext.scopeType = FacadeSupport.normalize(scope?.scopeType)
    }

    protected static Map<String, Object> resolveActiveCompany(List<Map<String, Object>> availableCompanies, String preferredCompanyUserGroupId) {
        if (!availableCompanies) return null
        if (preferredCompanyUserGroupId) {
            Map<String, Object> preferredCompany = availableCompanies.find {
                it.userGroupId == preferredCompanyUserGroupId
            }
            if (preferredCompany != null) return preferredCompany
        }
        return availableCompanies.first()
    }

    protected static String resolveCompanyLabel(def record, String fallbackUserGroupId) {
        return extractString(record, "description") ?: fallbackUserGroupId
    }

    static String resolveCompanyLabelForUserGroupId(def ec, Object userGroupId) {
        String normalizedUserGroupId = FacadeSupport.normalize(userGroupId)
        if (!normalizedUserGroupId) return null

        def matchingCompany = listAllCompanyRecords(ec).find { record ->
            extractString(record, "userGroupId") == normalizedUserGroupId
        }
        return resolveCompanyLabel(matchingCompany, normalizedUserGroupId)
    }

    protected static String resolveRuntimeScopeSuffix(def ec) {
        String activeCompanyUserGroupId = currentActiveCompanyUserGroupId(ec)
        if (activeCompanyUserGroupId) {
            return "company/${sanitizePathToken(activeCompanyUserGroupId)}"
        }

        String userId = currentUserId(ec)
        return "no-company/${sanitizePathToken(userId ?: 'anonymous')}"
    }

    protected static String sanitizePathToken(String rawToken) {
        String normalized = FacadeSupport.normalize(rawToken)
        if (!normalized) return "anonymous"
        return normalized.replaceAll(/[^A-Za-z0-9._-]/, "_")
    }
}
