package darpan.facade.common

import darpan.admin.AdminAuditSupport
import darpan.common.DarpanEntityConstants
import groovy.transform.CompileDynamic

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-005 — the ONLY writer of {@code darpan.auth.ConfigTenantAccess}.
 *
 * <p><strong>Fence warning.</strong> These operations are exposed as {@code facade.*} services, and
 * {@code facade\..*} is granted to every Darpan role including {@code DARPAN_TENANT_USER}
 * (SecuritySeedData.xml:97-121). The artifact fence therefore provides NO protection here. The
 * two-sided {@link TenantAccessSupport#requireTenantAdmin} check below is the whole gate. It must
 * fail closed on every path.</p>
 *
 * <p><strong>The rule.</strong> Granting config {@code C} to tenant {@code T} requires the acting
 * user to be tenant-admin of {@code T} AND tenant-admin of at least one tenant in {@code C}'s anchor
 * set — the owning tenant plus the current peer group. Revoke is gated identically. This encodes the
 * requester's worked example: a user administering companies 1 and 2 may wire 1↔2 in either
 * direction but may not pull in company 3.</p>
 *
 * <p>Sharing a config shares its ENCRYPTED CREDENTIALS with another tenant. That is intended and is
 * exactly why both ends are admin-gated and why the UI warns before an edit. Do not relax either.</p>
 */
@CompileDynamic
class SharedConfigGrantSupport {

    static final String GRANT_SERVICE_NAME = "facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess"
    static final String REVOKE_SERVICE_NAME = "facade.ConfigSharingFacadeServices.revoke#ConfigTenantAccess"
    static final String AUDIT_TARGET_TYPE = "ConfigTenantAccess"

    /** Adds a tenant to a config's peer group. False (with ec.message errors) when denied. */
    static boolean grantAccess(def ec, String configTypeEnumId, String configId, String targetTenantUserGroupId) {
        Map<String, Object> resolved = resolveAndAuthorize(ec, configTypeEnumId, configId,
                targetTenantUserGroupId, "share")
        if (resolved == null) return false

        if (resolved.targetTenantUserGroupId == resolved.ownerTenantUserGroupId) {
            ec.message.addError("${resolved.typeLabel} '${resolved.configId}' is already owned by " +
                    "${resolved.targetTenantUserGroupId}.")
            return false
        }
        if (resolved.targetTenantUserGroupId in (resolved.memberTenantUserGroupIds as List)) {
            ec.message.addError("${resolved.typeLabel} '${resolved.configId}' is already shared with " +
                    "${resolved.targetTenantUserGroupId}.")
            return false
        }

        ec.service.sync().name("create#${DarpanEntityConstants.CONFIG_TENANT_ACCESS}").parameters([
                configTypeEnumId : resolved.configTypeEnumId,
                configId         : resolved.configId,
                tenantUserGroupId: resolved.targetTenantUserGroupId,
                fromDate         : ec.user.nowTimestamp,
                grantedByUserId  : ec.user.userId,
        ]).call()

        AdminAuditSupport.record(ec, GRANT_SERVICE_NAME, AUDIT_TARGET_TYPE,
                "${resolved.configTypeEnumId}:${resolved.configId}",
                "Shared ${resolved.typeLabel} ${resolved.configId} (owner ${resolved.ownerTenantUserGroupId}) " +
                "with tenant ${resolved.targetTenantUserGroupId}.")
        return true
    }

    /** Soft-revokes every active grant of this config to this tenant. Never deletes a row. */
    static boolean revokeAccess(def ec, String configTypeEnumId, String configId, String targetTenantUserGroupId) {
        Map<String, Object> resolved = resolveAndAuthorize(ec, configTypeEnumId, configId,
                targetTenantUserGroupId, "stop sharing")
        if (resolved == null) return false

        List activeRows = (TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.CONFIG_TENANT_ACCESS,
                        "shared-config revoke: locate the caller's own grant rows by explicit PK parts (DAR-BE-005)")
                .condition("configTypeEnumId", resolved.configTypeEnumId)
                .condition("configId", resolved.configId)
                .condition("tenantUserGroupId", resolved.targetTenantUserGroupId)
                .useCache(false)
                .list() ?: []).findAll { it.thruDate == null }

        if (!activeRows) {
            ec.message.addError("${resolved.typeLabel} '${resolved.configId}' is not shared with " +
                    "${resolved.targetTenantUserGroupId}.")
            return false
        }

        def thruDate = ec.user.nowTimestamp
        activeRows.each { row ->
            ec.service.sync().name("update#${DarpanEntityConstants.CONFIG_TENANT_ACCESS}").parameters([
                    configTypeEnumId : resolved.configTypeEnumId,
                    configId         : resolved.configId,
                    tenantUserGroupId: resolved.targetTenantUserGroupId,
                    fromDate         : row.fromDate,
                    thruDate         : thruDate,
            ]).call()
        }

        AdminAuditSupport.record(ec, REVOKE_SERVICE_NAME, AUDIT_TARGET_TYPE,
                "${resolved.configTypeEnumId}:${resolved.configId}",
                "Stopped sharing ${resolved.typeLabel} ${resolved.configId} with tenant " +
                "${resolved.targetTenantUserGroupId}.")
        return true
    }

    /**
     * Sharing state for one config: owner, peers, and the count the UI shows in its
     * "changes affect N tenants" confirmation. Read-only; requires only that the ACTIVE tenant can
     * use the config, so any member tenant can render the panel.
     */
    static Map<String, Object> describeSharing(def ec, String configTypeEnumId, String configId) {
        Map<String, String> type = SharedConfigAccessSupport.configType(configTypeEnumId)
        if (type == null) {
            ec.message.addError("Unknown shared config type '${configTypeEnumId ?: '(missing)'}'.")
            return null
        }
        def config = loadConfigRow(ec, type, configId)
        if (config == null) {
            ec.message.addError("${type.label} '${configId ?: '(missing)'}' was not found.")
            return null
        }
        if (!SharedConfigAccessSupport.canActiveTenantUseConfig(ec, configTypeEnumId, config)) {
            ec.message.addError(TenantAccessSupport.TENANT_RECORD_UNAVAILABLE_MESSAGE)
            return null
        }

        String ownerTenantUserGroupId = normalize(config.companyUserGroupId)
        List<String> members = SharedConfigAccessSupport.listMemberTenantIds(ec,
                normalize(configTypeEnumId), normalize(configId))
        return [
                configTypeEnumId        : normalize(configTypeEnumId),
                configId                : normalize(configId),
                ownerTenantUserGroupId  : ownerTenantUserGroupId,
                ownerTenantLabel        : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, ownerTenantUserGroupId),
                memberTenantUserGroupIds: members,
                memberTenantLabels      : members.collect { String t ->
                    [tenantUserGroupId: t, label: TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, t)]
                },
                // owner + peers: what "changes affect N tenants" must say.
                memberCount             : (([ownerTenantUserGroupId] + members).findAll { it }.unique()).size(),
                canManage               : TenantAccessSupport.isTenantAdmin(ec, ownerTenantUserGroupId) ||
                        members.any { String t -> TenantAccessSupport.isTenantAdmin(ec, t) },
        ]
    }

    /**
     * Shared prologue: validate the type, prove the config row exists (there is no DB FK to do it),
     * then apply the two-sided admin rule. Returns null after adding an ec.message error on any
     * failure — callers must treat null as "stop".
     */
    private static Map<String, Object> resolveAndAuthorize(def ec, String configTypeEnumId, String configId,
            String targetTenantUserGroupId, String verbPhrase) {
        String normalizedType = normalize(configTypeEnumId)
        String normalizedConfigId = normalize(configId)
        String normalizedTarget = normalize(targetTenantUserGroupId)

        Map<String, String> type = SharedConfigAccessSupport.configType(normalizedType)
        if (type == null) {
            ec.message.addError("Unknown shared config type '${configTypeEnumId ?: '(missing)'}'.")
            return null
        }
        if (!normalizedConfigId) { ec.message.addError("A config id is required."); return null }
        if (!normalizedTarget) { ec.message.addError("A target tenant is required."); return null }

        // configId is polymorphic across three components — no DB FK can do this check.
        def config = loadConfigRow(ec, type, normalizedConfigId)
        if (config == null) {
            ec.message.addError("${type.label} '${normalizedConfigId}' was not found.")
            return null
        }
        // ORDERING IS LOAD-BEARING — this existence check MUST stay ahead of requireTenantAdmin
        // below. isTenantAdmin performs no existence check of its own and returns true for a
        // super-admin on ANY non-blank string, including a typo'd or deleted tenant id. This is the
        // only thing standing between "admin fat-fingers a tenant name" and a grant row pointing at
        // a tenant nobody vetted. Raised in Task 2 review, 2026-08-11.
        if (!isDarpanTenant(ec, normalizedTarget)) {
            ec.message.addError("Tenant ${normalizedTarget} was not found.")
            return null
        }

        String ownerTenantUserGroupId = normalize(config.companyUserGroupId)
        List<String> members = SharedConfigAccessSupport.listMemberTenantIds(ec, normalizedType, normalizedConfigId)

        // Side 1 — the target. You may not add or remove a tenant you do not administer.
        if (!TenantAccessSupport.requireTenantAdmin(ec, normalizedTarget,
                "You must be a tenant admin of ${normalizedTarget} to ${verbPhrase} this configuration.")) {
            return null
        }

        // Side 2 — the anchor. You may not reach into a group you have no standing in.
        List<String> anchorTenantUserGroupIds = (([ownerTenantUserGroupId] + members)
                .findAll { it } as List<String>).unique()
        if (!anchorTenantUserGroupIds.any { String t -> TenantAccessSupport.isTenantAdmin(ec, t) }) {
            ec.message.addError("You must be a tenant admin of a tenant that already uses " +
                    "${type.label} '${normalizedConfigId}' to ${verbPhrase} it.")
            return null
        }

        return [
                configTypeEnumId        : normalizedType,
                configId                : normalizedConfigId,
                targetTenantUserGroupId : normalizedTarget,
                ownerTenantUserGroupId  : ownerTenantUserGroupId,
                memberTenantUserGroupIds: members,
                typeLabel               : type.label,
        ]
    }

    /**
     * Loads the config row across the tenant boundary on purpose: a grant is by definition about a
     * config the active tenant may not own yet. Authorization is applied by the caller immediately
     * after — never return this row to a caller that has not passed resolveAndAuthorize.
     */
    private static def loadConfigRow(def ec, Map<String, String> type, String configId) {
        if (!configId) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, type.entityName,
                        "shared-config grant target loaded for existence + owner resolution; the two-sided " +
                        "requireTenantAdmin check runs immediately after (DAR-BE-005)")
                .condition(type.pkField, configId)
                .useCache(false)
                .one()
    }

    /** Rejects non-tenant UserGroups so a grant can never name ADMIN or a permission group. */
    private static boolean isDarpanTenant(def ec, String tenantUserGroupId) {
        def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup",
                        "framework reference data: verify the grant target is a real Darpan tenant group")
                .condition("userGroupId", tenantUserGroupId)
                .useCache(true)
                .one()
        return group != null &&
                normalize(group.groupTypeEnumId) == TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
    }
}
