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

        // Reuse the SAME active-grant definition the reader uses (SharedConfigAccessSupport, which
        // gates live credential access): a null thruDate OR a FUTURE thruDate both count as active.
        // A local `thruDate == null` filter here previously disagreed with the reader and left a
        // future-dated row un-revokable — reachable by grant/read, invisible to revoke
        // (DAR-BE-005 review finding, 2026-08-11).
        List activeRows = SharedConfigAccessSupport.listActiveGrantRows(ec, resolved.configTypeEnumId,
                resolved.configId, resolved.targetTenantUserGroupId)

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
        String normalizedType = normalize(configTypeEnumId)
        Map<String, String> type = SharedConfigAccessSupport.configType(normalizedType)
        if (type == null) {
            ec.message.addError("Unknown shared config type '${configTypeEnumId ?: '(missing)'}'.")
            return null
        }
        // Normalize configId BEFORE the lookup — resolveAndAuthorize already did this; describeSharing
        // previously did not, so a whitespace-padded id was grantable but not describable
        // (DAR-BE-005 review finding, 2026-08-11).
        String normalizedConfigId = normalize(configId)
        def config = loadConfigRow(ec, type, normalizedConfigId)
        if (config == null) {
            ec.message.addError("${type.label} '${normalizedConfigId ?: '(missing)'}' was not found.")
            return null
        }
        if (!SharedConfigAccessSupport.canActiveTenantUseConfig(ec, normalizedType, config)) {
            ec.message.addError(TenantAccessSupport.TENANT_RECORD_UNAVAILABLE_MESSAGE)
            return null
        }

        String ownerTenantUserGroupId = normalize(config.companyUserGroupId)
        List<String> members = SharedConfigAccessSupport.listMemberTenantIds(ec, normalizedType, normalizedConfigId)
        return [
                configTypeEnumId        : normalizedType,
                configId                : normalizedConfigId,
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
     * Shared prologue: apply the two-sided admin rule FIRST, then confirm the named resources
     * actually exist, immediately before the write. Returns null after adding an ec.message error
     * on any failure — callers must treat null as "stop".
     *
     * <p><strong>Ordering — Aditi's 2026-08-11 decision, overturning an earlier version of this
     * method that checked existence before the admin checks.</strong> {@code facade\..*} is granted
     * to every Darpan role, so ANY authenticated user — including one who administers nothing at
     * all — can call {@code grant#}/{@code revoke#ConfigTenantAccess} with guessed ids. If existence
     * were checked first, such a caller could distinguish "config not found" from "you must be a
     * tenant admin" and use the response itself as a cross-tenant existence oracle, enumerating
     * every config row and every real tenant id in the installation. The admin checks (target, then
     * anchor) now run FIRST: a caller who fails either learns nothing else, because the config row
     * is not even loaded until step 3, and a null config row simply yields an empty anchor set at
     * step 4 — the SAME generic "you must be a tenant admin of a tenant that already uses this
     * config" denial a real config the caller has no standing over would also produce.</p>
     *
     * <p>The safety property that must NEVER regress: existence is verified before the WRITE, not
     * before the admin check. {@link #isDarpanTenant} still runs strictly before any
     * {@code create#}/{@code update#} call below (step 5), so a super-admin — who trivially passes
     * BOTH admin checks for any non-blank tenant string, per
     * {@link TenantAccessSupport#isTenantAdmin} — is still stopped here if the named target tenant
     * is a typo or does not exist. See
     * {@code aSuperAdminIsStillStoppedByTenantExistenceEvenAfterPassingBothAdminChecks} for the
     * explicit regression test.</p>
     */
    private static Map<String, Object> resolveAndAuthorize(def ec, String configTypeEnumId, String configId,
            String targetTenantUserGroupId, String verbPhrase) {
        String normalizedType = normalize(configTypeEnumId)
        String normalizedConfigId = normalize(configId)
        String normalizedTarget = normalize(targetTenantUserGroupId)

        // Step 1 — type validation and basic input shape. The type catalog is public and a blank
        // parameter names no specific record, so neither check leaks anything about the datastore.
        Map<String, String> type = SharedConfigAccessSupport.configType(normalizedType)
        if (type == null) {
            ec.message.addError("Unknown shared config type '${configTypeEnumId ?: '(missing)'}'.")
            return null
        }
        if (!normalizedConfigId) { ec.message.addError("A config id is required."); return null }
        if (!normalizedTarget) { ec.message.addError("A target tenant is required."); return null }

        // Step 2 — target admin check. A caller who fails this learns nothing else: the config row
        // has not been loaded yet, so there is no existence signal to leak.
        if (!TenantAccessSupport.requireTenantAdmin(ec, normalizedTarget,
                "You must be a tenant admin of ${normalizedTarget} to ${verbPhrase} this configuration.")) {
            return null
        }

        // Step 3 — load the config row (may be null) and the peer group. listMemberTenantIds reads
        // ConfigTenantAccess directly by (type, configId) — it has no FK to the config row, so it is
        // read unconditionally rather than gated on `config` being non-null.
        def config = loadConfigRow(ec, type, normalizedConfigId)
        String ownerTenantUserGroupId = config == null ? null : normalize(config.companyUserGroupId)
        List<String> members = SharedConfigAccessSupport.listMemberTenantIds(ec, normalizedType, normalizedConfigId)

        // Step 4 — anchor admin check. A null config row (or one with no peers) yields an empty
        // anchor set, which denies here with the same message a real-but-unreachable config would
        // produce — non-disclosing either way.
        List<String> anchorTenantUserGroupIds = (([ownerTenantUserGroupId] + members)
                .findAll { it } as List<String>).unique()
        if (!anchorTenantUserGroupIds.any { String t -> TenantAccessSupport.isTenantAdmin(ec, t) }) {
            ec.message.addError("You must be a tenant admin of a tenant that already uses " +
                    "${type.label} '${normalizedConfigId}' to ${verbPhrase} it.")
            return null
        }

        // Step 5 — only now, after BOTH admin checks passed, confirm the named resources actually
        // exist. configId is polymorphic across three components — no DB FK can do that check.
        // isDarpanTenant runs strictly before any create#/update# call below; this is what stops a
        // super-admin (who trivially passes steps 2 and 4 for any non-blank string) from writing a
        // grant row that points at a typo'd or deleted tenant.
        if (config == null) {
            ec.message.addError("${type.label} '${normalizedConfigId}' was not found.")
            return null
        }
        if (!isDarpanTenant(ec, normalizedTarget)) {
            ec.message.addError("Tenant ${normalizedTarget} was not found.")
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

    /**
     * True while any tenant still holds an active grant on this config.
     *
     * <p>Delete-path guard for all three components. A shared config must NOT be deletable, even by
     * its owner: {@code ConfigTenantAccess.configId} is polymorphic with no DB FK, so a delete
     * cascades nothing and would leave every peer tenant's automation failing at run time with a
     * "not found" that points nowhere. Blocking at delete time is the loud failure; the owner
     * revokes each peer first.</p>
     */
    static boolean hasActiveGrants(def ec, String configTypeEnumId, String configId) {
        return !SharedConfigAccessSupport.listMemberTenantIds(ec, configTypeEnumId, configId).isEmpty()
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
