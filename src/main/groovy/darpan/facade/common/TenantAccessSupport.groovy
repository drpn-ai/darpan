package darpan.facade.common

import darpan.common.DarpanEntityConstants

import java.time.ZoneId
import java.time.DateTimeException
import java.util.TimeZone

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.sanitizeFileToken

class TenantAccessSupport {
    static final String ADMIN_USER_GROUP_ID = "ADMIN"
    static final String ALL_USERS_GROUP_ID = "ALL_USERS"
    static final String ACTIVE_TENANT_PREFERENCE_KEY = "darpan.auth.activeTenantUserGroupId"
    static final String DISPLAY_NAME_PREFERENCE_KEY = "darpan.user.displayName"
    static final String TENANT_SETTING_ENTITY_NAME = "darpan.auth.TenantSetting"
    static final String DEFAULT_TIME_ZONE = "UTC"
    static final String DARPAN_COMPANY_GROUP_TYPE_ENUM_ID = "UgtDarpanCompany"
    static final String DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID = "UgtDarpanPermission"
    static final String DARPAN_ADMIN_GROUP_ID = "DARPAN_ADMIN"
    static final String DARPAN_SUPER_ADMIN_GROUP_ID = "DARPAN_SUPER_ADMIN"
    static final String DARPAN_TENANT_ADMIN_GROUP_ID = "DARPAN_TENANT_ADMIN"
    static final String DARPAN_TENANT_USER_GROUP_ID = "DARPAN_TENANT_USER"
    static final String DARPAN_COMPANY_EDITOR_GROUP_ID = "DARPAN_COMPANY_EDITOR"
    static final String DARPAN_COMPANY_VIEW_ONLY_GROUP_ID = "DARPAN_COMPANY_VIEW_ONLY"
    static final List<String> SUPER_ADMIN_GROUP_IDS = [ADMIN_USER_GROUP_ID, DARPAN_SUPER_ADMIN_GROUP_ID].asImmutable()
    static final List<String> DARPAN_ADMIN_GROUP_IDS = [ADMIN_USER_GROUP_ID, DARPAN_ADMIN_GROUP_ID].asImmutable()
    static final List<String> TENANT_VIEW_PERMISSION_GROUP_IDS = [
            DARPAN_TENANT_ADMIN_GROUP_ID,
            DARPAN_TENANT_USER_GROUP_ID,
            DARPAN_COMPANY_EDITOR_GROUP_ID,
            DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
    ].asImmutable()
    static final List<String> TENANT_RUN_PERMISSION_GROUP_IDS = [
            DARPAN_TENANT_ADMIN_GROUP_ID,
            DARPAN_TENANT_USER_GROUP_ID,
            DARPAN_COMPANY_EDITOR_GROUP_ID,
    ].asImmutable()
    static final List<String> TENANT_EDIT_PERMISSION_GROUP_IDS = [
            DARPAN_TENANT_ADMIN_GROUP_ID,
            DARPAN_COMPANY_EDITOR_GROUP_ID,
    ].asImmutable()
    static final String TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME = "darpan.auth.TenantUserPermissionGroupMember"
    static final String SCOPE_TYPE_ANONYMOUS = "ANONYMOUS"
    static final String SCOPE_TYPE_TENANT = "TENANT"
    static final String ACTIVE_TENANT_UNAVAILABLE_MESSAGE = "Requested tenant is not available for current user."
    static final String TENANT_RECORD_UNAVAILABLE_MESSAGE = "Requested record is not available in your active tenant."
    static final String ACTIVE_TENANT_WRITE_REQUIRED_MESSAGE = "An active tenant is required for tenant-scoped writes."
    static final String ACTIVE_TENANT_READ_ONLY_MESSAGE = "Your active tenant is read-only for this action."
    static final String NO_ACTIVE_TENANT_FILTER_VALUE = "__NO_ACTIVE_TENANT__"
    static final String OWNED_RECORD_UNAVAILABLE_MESSAGE = "Requested record is not available in your customer scope."

    protected static final List<String> ACCESS_SCOPE_CONTEXT_KEYS = [
            "activeTenantUserGroupId",
            "activeTenantLabel",
            "availableTenantUserGroupIds",
            "activeTenantPermissionGroupIds",
            "canViewActiveTenantData",
            "canRunActiveTenantReconciliation",
            "canEditActiveTenantData",
            "canManageDarpanCore",
            "isSuperAdmin",
            "scopeType",
    ].asImmutable()

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
        boolean darpanAdmin = isDarpanAdmin(ec)
        if (!userId) {
            return [
                isSuperAdmin                : false,
                scopeType                   : SCOPE_TYPE_ANONYMOUS,
                customerScopeId             : null,
                activeTenantUserGroupId    : null,
                activeTenantLabel          : null,
                availableTenants          : [],
                activeTenantPermissionGroupIds: [],
                canViewActiveTenantData    : false,
                canRunActiveTenantReconciliation: false,
                canEditActiveTenantData    : false,
                canManageDarpanCore        : false,
            ]
        }

        List<Map<String, Object>> availableTenants = listAvailableTenants(ec)
        Map<String, Object> activeTenant = resolveActiveTenant(availableTenants, readPreferredActiveTenantUserGroupId(ec))
        Map<String, Object> activeTenantPermissionScope = resolveActiveTenantPermissionScope(ec, activeTenant?.userGroupId, superAdmin)
        return [
            isSuperAdmin                : superAdmin,
            scopeType                   : SCOPE_TYPE_TENANT,
            customerScopeId             : activeTenant?.userGroupId,
            activeTenantUserGroupId    : activeTenant?.userGroupId,
            activeTenantLabel          : activeTenant?.label,
            availableTenants          : availableTenants,
            activeTenantPermissionGroupIds: activeTenantPermissionScope.permissionGroupIds,
            canViewActiveTenantData    : activeTenantPermissionScope.canView,
            canRunActiveTenantReconciliation: activeTenantPermissionScope.canRun,
            canEditActiveTenantData    : activeTenantPermissionScope.canEdit,
            canManageDarpanCore        : darpanAdmin,
        ]
    }

    static boolean isSuperAdmin(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return false

        return SUPER_ADMIN_GROUP_IDS.any { String userGroupId -> hasActiveUserGroupMembership(ec, userGroupId) }
    }

    static boolean isDarpanAdmin(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return false

        return DARPAN_ADMIN_GROUP_IDS.any { String userGroupId -> hasActiveUserGroupMembership(ec, userGroupId) }
    }

    static boolean requireSuperAdmin(def ec, String message = "This operation requires super-admin access.") {
        if (isSuperAdmin(ec)) return true
        ec?.message?.addError(message)
        return false
    }

    static boolean requireDarpanAdmin(def ec, String message = "This operation requires Darpan admin access.") {
        if (isDarpanAdmin(ec)) return true
        ec?.message?.addError(message)
        return false
    }

    static List<Map<String, Object>> listAvailableTenants(def ec) {
        List companyRecords = isSuperAdmin(ec) ? listAllTenantRecords(ec) : listTenantMembershipRecords(ec)
        return companyRecords
                .collect { record ->
                    String userGroupId = extractString(record, "userGroupId")
                    if (!userGroupId || userGroupId in [ADMIN_USER_GROUP_ID, ALL_USERS_GROUP_ID]) return null

                    [
                        userGroupId: userGroupId,
                        label      : resolveTenantLabel(record, userGroupId),
                    ]
                }
                .findAll { it != null }
                .unique { it.userGroupId }
                .sort { left, right ->
                    String leftLabel = normalize(left?.label) ?: left?.userGroupId ?: ""
                    String rightLabel = normalize(right?.label) ?: right?.userGroupId ?: ""
                    int labelCompare = leftLabel <=> rightLabel
                    return labelCompare != 0 ? labelCompare : ((left?.userGroupId ?: "") <=> (right?.userGroupId ?: ""))
                } as List<Map<String, Object>>
    }

    static boolean saveActiveTenant(def ec, Object requestedTenantUserGroupId) {
        if (!currentUserId(ec)) {
            ec?.message?.addError("Authentication required to change the active tenant.")
            return false
        }

        String normalizedTenantUserGroupId = normalize(requestedTenantUserGroupId)
        if (!normalizedTenantUserGroupId) {
            ec?.message?.addError("activeTenantUserGroupId is required.")
            return false
        }

        List<Map<String, Object>> availableTenants = listAvailableTenants(ec)
        if (!availableTenants) {
            ec?.message?.addError("No tenant is available for the current user.")
            return false
        }

        Map<String, Object> requestedTenant = availableTenants.find {
            it.userGroupId == normalizedTenantUserGroupId
        }
        if (requestedTenant == null) {
            ec?.message?.addError(ACTIVE_TENANT_UNAVAILABLE_MESSAGE)
            return false
        }

        ec?.user?.setPreference(ACTIVE_TENANT_PREFERENCE_KEY, normalizedTenantUserGroupId)
        applyAccessScopeToUserContext(ec, resolveAccessScope(ec))
        return true
    }

    static String currentActiveTenantUserGroupId(def ec) {
        if (!currentUserId(ec)) return null
        List<Map<String, Object>> availableTenants = listAvailableTenants(ec)
        return resolveActiveTenant(availableTenants, readPreferredActiveTenantUserGroupId(ec))?.userGroupId
    }

    static List<String> currentActiveTenantPermissionGroupIds(def ec) {
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        boolean superAdmin = isSuperAdmin(ec)
        return (resolveActiveTenantPermissionScope(ec, activeTenantUserGroupId, superAdmin).permissionGroupIds ?: []) as List<String>
    }

    static boolean canEditActiveTenantData(def ec) {
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        boolean superAdmin = isSuperAdmin(ec)
        return resolveActiveTenantPermissionScope(ec, activeTenantUserGroupId, superAdmin).canEdit == true
    }

    static boolean canRunActiveTenantReconciliation(def ec) {
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        boolean superAdmin = isSuperAdmin(ec)
        return resolveActiveTenantPermissionScope(ec, activeTenantUserGroupId, superAdmin).canRun == true
    }

    static boolean hasActiveTenantWriteAccess(def ec) {
        return canEditActiveTenantData(ec)
    }

    static boolean hasActiveTenantRunAccess(def ec) {
        return canRunActiveTenantReconciliation(ec)
    }

    static boolean requireActiveTenantRunAccess(def ec,
            String readOnlyMessage = ACTIVE_TENANT_READ_ONLY_MESSAGE,
            String missingCompanyMessage = ACTIVE_TENANT_WRITE_REQUIRED_MESSAGE) {
        if (!currentActiveTenantUserGroupId(ec)) {
            ec?.message?.addError(missingCompanyMessage)
            return false
        }
        if (hasActiveTenantRunAccess(ec)) return true
        ec?.message?.addError(readOnlyMessage)
        return false
    }

    static boolean requireActiveTenantWriteAccess(def ec,
            String readOnlyMessage = ACTIVE_TENANT_READ_ONLY_MESSAGE,
            String missingCompanyMessage = ACTIVE_TENANT_WRITE_REQUIRED_MESSAGE) {
        if (!currentActiveTenantUserGroupId(ec)) {
            ec?.message?.addError(missingCompanyMessage)
            return false
        }
        if (hasActiveTenantWriteAccess(ec)) return true
        ec?.message?.addError(readOnlyMessage)
        return false
    }

    static void applyScopeToSessionInfo(def ec, Map<String, Object> sessionInfo) {
        if (sessionInfo == null) return
        sessionInfo.putAll(buildAccessScope(ec))
    }

    static Map<String, Object> buildSessionInfo(def ec) {
        Map<String, Object> sessionInfo = [
            userId       : ec?.user?.userId,
            username     : ec?.user?.username,
            displayName  : resolveDisplayName(ec),
            locale       : ec?.l10n?.locale?.toLanguageTag(),
            timeZone     : resolveActiveTenantTimeZone(ec),
            lastLoginDate: resolveLastLoginDate(ec),
            lastRun      : resolveLastRun(ec),
        ]
        applyScopeToSessionInfo(ec, sessionInfo)
        return sessionInfo
    }

    static boolean saveUserSettings(def ec, Object rawDisplayName) {
        if (!currentUserId(ec)) {
            ec?.message?.addError("Authentication required to save user settings.")
            return false
        }

        String displayName = normalize(rawDisplayName)
        ec?.user?.setPreference(DISPLAY_NAME_PREFERENCE_KEY, displayName)
        return true
    }

    static Map<String, Object> readActiveTenantSettings(def ec) {
        String tenantId = currentActiveTenantUserGroupId(ec)
        if (!tenantId) return buildTenantSettingsResponse(ec, null, null)
        return buildTenantSettingsResponse(ec, findTenantSettingsForTenant(ec, tenantId), tenantId)
    }

    static Map<String, Object> saveActiveTenantSettings(def ec, Object rawTimeZone) {
        String tenantId = currentActiveTenantUserGroupId(ec)
        if (!tenantId) {
            ec?.message?.addError("Active tenant is required for tenant settings.")
            return buildTenantSettingsResponse(ec, null, null)
        }

        requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for tenant settings.")
        def existing = findTenantSettingsForTenant(ec, tenantId)
        if (ec?.message?.hasError()) return buildTenantSettingsResponse(ec, existing, tenantId)

        String timeZone = normalizeTimeZoneId(rawTimeZone)
        String validationError = validateTimeZone(timeZone)
        if (validationError) ec?.message?.addError(validationError)
        if (ec?.message?.hasError()) return buildTenantSettingsResponse(ec, existing, tenantId)

        def nowTs = ec?.user?.nowTimestamp
        Map<String, Object> tenantSettingsMap = [
                companyUserGroupId: tenantId,
                createdByUserId   : normalize(existing?.createdByUserId) ?: currentUserId(ec),
                timeZone          : timeZone,
                createdDate       : existing?.createdDate ?: nowTs,
                lastUpdatedDate   : nowTs,
        ]

        ec?.service?.sync()
                ?.name("store#${TENANT_SETTING_ENTITY_NAME}".toString())
                ?.parameters(tenantSettingsMap)
                ?.call()

        if (!ec?.message?.hasError()) ec?.message?.addMessage("Saved tenant settings.")
        return buildTenantSettingsResponse(ec, findTenantSettingsForTenant(ec, tenantId), tenantId, tenantSettingsMap)
    }

    static String resolveActiveTenantTimeZone(def ec) {
        String tenantId = currentActiveTenantUserGroupId(ec)
        def tenantSettings = tenantId ? findTenantSettingsForTenant(ec, tenantId) : null
        return resolveTenantSettingsTimeZone(tenantSettings, ec)
    }

    static String validateTimeZone(Object rawTimeZone) {
        String timeZone = normalizeTimeZoneId(rawTimeZone)
        if (!timeZone) return "Timezone is required."
        try {
            ZoneId.of(timeZone)
            return null
        } catch (DateTimeException ignored) {
            return "Timezone is invalid."
        }
    }

    protected static String resolveTenantSettingsTimeZone(def tenantSettings, def ec) {
        return normalizeTimeZoneId(tenantSettings?.timeZone) ?:
                normalizeTimeZoneId(ec?.user?.userAccount?.timeZone) ?:
                normalizeTimeZoneId(ec?.l10n?.timeZone) ?:
                DEFAULT_TIME_ZONE
    }

    protected static String normalizeTimeZoneId(Object rawTimeZone) {
        if (rawTimeZone instanceof TimeZone) return normalize(rawTimeZone.ID)
        return normalize(rawTimeZone)
    }

    protected static def findTenantSettingsForTenant(def ec, String tenantId) {
        if (!tenantId) return null
        return ec?.entity?.find(TENANT_SETTING_ENTITY_NAME)
                ?.condition("companyUserGroupId", tenantId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
    }

    protected static Map<String, Object> buildTenantSettingsResponse(def ec, def tenantSettings, String tenantId,
            Map<String, Object> fallbackSettings = null) {
        String resolvedTenantId = tenantId ?: normalize(fallbackSettings?.companyUserGroupId)
        return [
                companyUserGroupId: resolvedTenantId,
                companyLabel      : resolveTenantLabelForUserGroupId(ec, resolvedTenantId),
                timeZone          : resolveTenantSettingsTimeZone(tenantSettings ?: fallbackSettings, ec),
                createdByUserId   : normalize(tenantSettings?.createdByUserId ?: fallbackSettings?.createdByUserId),
                createdDate       : tenantSettings?.createdDate ?: fallbackSettings?.createdDate,
                lastUpdatedDate   : tenantSettings?.lastUpdatedDate ?: fallbackSettings?.lastUpdatedDate,
        ]
    }

    static boolean canAccessTenantRecord(def ec, def record, String companyField = "companyUserGroupId") {
        if (record == null) return false
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return false
        String recordCompanyUserGroupId = extractString(record, companyField)
        return recordCompanyUserGroupId != null && recordCompanyUserGroupId == activeTenantUserGroupId
    }

    static void requireTenantRecordAccess(def ec, def record, String missingMessage = "Requested record was not found.",
            String forbiddenMessage = TENANT_RECORD_UNAVAILABLE_MESSAGE, String companyField = "companyUserGroupId") {
        if (record == null) {
            ec?.message?.addError(missingMessage)
            return
        }
        if (!canAccessTenantRecord(ec, record, companyField)) {
            ec?.message?.addError(forbiddenMessage)
        }
    }

    static void assignTenantOwnershipOnCreate(def value, def ec, String companyField = "companyUserGroupId",
            String createdByField = "createdByUserId") {
        if (value == null) return

        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) {
            ec?.message?.addError("An active tenant is required for tenant-scoped data.")
            return
        }

        String userId = currentUserId(ec)
        if (value instanceof Map) {
            value[companyField] = activeTenantUserGroupId
            if (userId) value[createdByField] = userId
        } else {
            value."${companyField}" = activeTenantUserGroupId
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
        String normalizedBase = normalize(baseLocation)
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
        return normalize(ec?.user?.userId)
    }

    protected static List listTenantMembershipRecords(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return []

        def finder = ec?.entity?.find("moqui.security.UserGroupAndMember")
        if (finder == null) return []

        finder.condition("userId", userId).condition("groupTypeEnumId", DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
        applyFinderDefaults(finder, ec)

        def membershipList = finder.list()
        return membershipList instanceof Collection ? membershipList as List : []
    }

    protected static List listAllTenantRecords(def ec) {
        def finder = ec?.entity?.find("moqui.security.UserGroup")
        if (finder == null) return []

        finder.condition("groupTypeEnumId", DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
        applyFinderDefaults(finder, ec, false)

        def companyList = finder.list()
        return companyList instanceof Collection ? companyList as List : []
    }

    /**
     * Apply disableAuthz, conditionDate, and useCache(false) defensively for finders
     * that may be partial stubs in tests. Each method is guarded so test stubs without
     * a given method continue to function.
     */
    private static void applyFinderDefaults(def finder, def ec, boolean includeDateFilter = true) {
        if (finder.metaClass.respondsTo(finder, "disableAuthz")) finder.disableAuthz()
        if (includeDateFilter && finder.metaClass.respondsTo(finder, "conditionDate", String, String, Object)) {
            finder.conditionDate("fromDate", "thruDate", ec?.user?.nowTimestamp)
        }
        if (finder.metaClass.respondsTo(finder, "useCache", Boolean)) finder.useCache(false)
    }

    protected static String extractString(def record, String fieldName) {
        if (record == null || !fieldName) return null
        Object value = record instanceof Map ? record[fieldName] : record."${fieldName}"
        return normalize(value)
    }

    protected static String readPreferredActiveTenantUserGroupId(def ec) {
        return ec?.user?.getPreference(ACTIVE_TENANT_PREFERENCE_KEY)?.toString()?.trim()
    }

    protected static String resolveDisplayName(def ec) {
        return ec?.user?.getPreference(DISPLAY_NAME_PREFERENCE_KEY)?.toString()?.trim()
                ?: normalize(ec?.user?.userAccount?.userFullName)
                ?: normalize(ec?.user?.username)
                ?: currentUserId(ec)
    }

    protected static Object resolveLastLoginDate(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return null

        def finder = ec?.entity?.find("moqui.security.UserLoginHistory")
        if (finder == null) return null

        finder.condition("userId", userId).condition("successfulLogin", "Y")
        applyFinderDefaults(finder, ec, false)
        if (finder.metaClass.respondsTo(finder, "orderBy", String)) finder.orderBy("-fromDate")

        def lastLogin = finder.one()
        return lastLogin instanceof Map ? lastLogin.fromDate : lastLogin?.fromDate
    }

    protected static Map<String, Object> resolveLastRun(def ec) {
        String userId = currentUserId(ec)
        if (!userId) return null

        def finder = ec?.entity?.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
        if (finder == null) return null

        finder.condition("createdByUserId", userId)
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        if (activeTenantUserGroupId) finder.condition("companyUserGroupId", activeTenantUserGroupId)
        applyFinderDefaults(finder, ec, false)
        if (finder.metaClass.respondsTo(finder, "orderBy", String)) finder.orderBy("-createdDate")

        def lastRunResult = finder.one()
        if (lastRunResult == null) return null

        return [
            reconciliationRunResultId: extractString(lastRunResult, "reconciliationRunResultId"),
            savedRunId              : extractString(lastRunResult, "savedRunId"),
            savedRunType            : extractString(lastRunResult, "savedRunType"),
            reconciliationRunId     : extractString(lastRunResult, "reconciliationRunId"),
            createdDate             : lastRunResult instanceof Map ? lastRunResult.createdDate : lastRunResult.createdDate,
        ].findAll { it.value != null } as Map<String, Object>
    }

    protected static List<String> listTenantPermissionGroupIds(def ec, String tenantUserGroupId) {
        String userId = currentUserId(ec)
        String normalizedTenantUserGroupId = normalize(tenantUserGroupId)
        if (!userId || !normalizedTenantUserGroupId) return []

        def finder = ec?.entity?.find(TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
        if (finder == null) return []

        finder.condition("userId", userId).condition("tenantUserGroupId", normalizedTenantUserGroupId)
        applyFinderDefaults(finder, ec)

        def assignmentList = finder.list()
        return (assignmentList instanceof Collection ? assignmentList : [])
                .collect { assignment -> extractString(assignment, "permissionUserGroupId") }
                .findAll { it != null }
                .unique()
                .sort() as List<String>
    }

    protected static Map<String, Object> resolveActiveTenantPermissionScope(def ec, Object activeTenantUserGroupId, boolean superAdmin) {
        String normalizedActiveTenantUserGroupId = normalize(activeTenantUserGroupId)
        if (!normalizedActiveTenantUserGroupId) {
            return [permissionGroupIds: [], canView: false, canRun: false, canEdit: false]
        }
        if (superAdmin) {
            return [
                    permissionGroupIds: [DARPAN_SUPER_ADMIN_GROUP_ID, DARPAN_TENANT_ADMIN_GROUP_ID, DARPAN_TENANT_USER_GROUP_ID],
                    canView           : true,
                    canRun            : true,
                    canEdit           : true,
            ]
        }

        List<String> effectivePermissionGroupIds = listTenantPermissionGroupIds(ec, normalizedActiveTenantUserGroupId)
        boolean canView = effectivePermissionGroupIds.any { String permissionGroupId -> permissionGroupId in TENANT_VIEW_PERMISSION_GROUP_IDS }
        boolean canRun = effectivePermissionGroupIds.any { String permissionGroupId -> permissionGroupId in TENANT_RUN_PERMISSION_GROUP_IDS }
        boolean canEdit = effectivePermissionGroupIds.any { String permissionGroupId -> permissionGroupId in TENANT_EDIT_PERMISSION_GROUP_IDS }
        return [permissionGroupIds: effectivePermissionGroupIds, canView: canView, canRun: canRun, canEdit: canEdit]
    }

    protected static boolean hasActiveUserGroupMembership(def ec, String userGroupId) {
        String userId = currentUserId(ec)
        String normalizedUserGroupId = normalize(userGroupId)
        if (!userId || !normalizedUserGroupId) return false

        def finder = ec?.entity?.find("moqui.security.UserGroupMember")
        if (finder == null) return false

        finder.condition("userId", userId).condition("userGroupId", normalizedUserGroupId)
        applyFinderDefaults(finder, ec)
        return finder.one() != null
    }

    protected static void applyAccessScopeToUserContext(def ec, Map<String, Object> scope) {
        Map<String, Object> userContext = ec?.user?.context as Map<String, Object>
        if (userContext == null) return

        if (!currentUserId(ec)) {
            ACCESS_SCOPE_CONTEXT_KEYS.each { String key -> userContext.remove(key) }
            return
        }

        List<String> availableTenantUserGroupIds = ((scope?.availableTenants ?: []) as List)
                .collect { Map<String, Object> tenant -> normalize(tenant?.userGroupId) }
                .findAll { it != null } as List<String>
        List<String> activeTenantPermissionGroupIds = ((scope?.activeTenantPermissionGroupIds ?: []) as List)
                .collect { Object permissionUserGroupId -> normalize(permissionUserGroupId) }
                .findAll { it != null } as List<String>
        String contextTenantUserGroupId = normalize(scope?.activeTenantUserGroupId) ?: NO_ACTIVE_TENANT_FILTER_VALUE

        userContext.activeTenantUserGroupId = contextTenantUserGroupId
        userContext.activeTenantLabel = normalize(scope?.activeTenantLabel)
        userContext.availableTenantUserGroupIds = availableTenantUserGroupIds
        userContext.activeTenantPermissionGroupIds = activeTenantPermissionGroupIds
        userContext.canViewActiveTenantData = scope?.canViewActiveTenantData == true
        userContext.canRunActiveTenantReconciliation = scope?.canRunActiveTenantReconciliation == true
        userContext.canEditActiveTenantData = scope?.canEditActiveTenantData == true
        userContext.canManageDarpanCore = scope?.canManageDarpanCore == true
        userContext.isSuperAdmin = scope?.isSuperAdmin == true
        userContext.scopeType = normalize(scope?.scopeType)
    }

    protected static Map<String, Object> resolveActiveTenant(List<Map<String, Object>> availableTenants, String preferredTenantUserGroupId) {
        if (!availableTenants) return null
        if (preferredTenantUserGroupId) {
            Map<String, Object> preferredTenant = availableTenants.find {
                it.userGroupId == preferredTenantUserGroupId
            }
            if (preferredTenant != null) return preferredTenant
        }
        return availableTenants.first()
    }

    protected static String resolveTenantLabel(def record, String fallbackUserGroupId) {
        return extractString(record, "description") ?: fallbackUserGroupId
    }

    static String resolveTenantLabelForUserGroupId(def ec, Object userGroupId) {
        String normalizedUserGroupId = normalize(userGroupId)
        if (!normalizedUserGroupId) return null

        def matchingTenant = listAllTenantRecords(ec).find { record ->
            extractString(record, "userGroupId") == normalizedUserGroupId
        }
        return resolveTenantLabel(matchingTenant, normalizedUserGroupId)
    }

    protected static String resolveRuntimeScopeSuffix(def ec) {
        String activeTenantUserGroupId = currentActiveTenantUserGroupId(ec)
        if (activeTenantUserGroupId) {
            return "tenant/${sanitizePathToken(activeTenantUserGroupId)}"
        }

        String userId = currentUserId(ec)
        return "no-tenant/${sanitizePathToken(userId ?: 'anonymous')}"
    }

    protected static String sanitizePathToken(String rawToken) {
        return sanitizeFileToken(rawToken, "anonymous")
    }
}
