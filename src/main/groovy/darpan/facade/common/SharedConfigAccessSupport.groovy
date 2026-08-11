package darpan.facade.common

import darpan.common.DarpanEntityConstants
import groovy.transform.CompileDynamic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-005 — read-side resolution for API source configs SHARED across tenants.
 *
 * <p>A config is shared iff {@code darpan.auth.ConfigTenantAccess} holds active rows for it. Those
 * rows ARE the peer group: every member tenant is an equal peer, there is no owner-of-the-group, and
 * there is no copy. The underlying config row remains the single source of truth, so an edit made
 * under any member tenant is immediately effective for all of them.</p>
 *
 * <p><strong>Tenant isolation.</strong> The single cross-tenant read is
 * {@link TenantScopedFinder#findGlobalUnscoped}, the explicitly-named and allowlisted opt-out. That
 * is the same shape {@code SftpAutomationSupport.tenantGroupServerAllowsTenant} has used since the
 * SFTP tenant-group feature shipped. {@code findTenantScoped} is untouched by this class, and no
 * bare {@code disableAuthz()} is introduced — {@code DisableAuthzRatchetTest} stays at 12.</p>
 *
 * <p><strong>Extending.</strong> A new shareable config type needs one new
 * {@code DarpanSharedConfigType} Enumeration value and one {@link #CONFIG_TYPE_REGISTRY} row.
 * No schema change: {@code ConfigTenantAccess.configId} is polymorphic by design.</p>
 *
 * <p>Read-only. Every write goes through {@code SharedConfigGrantSupport}.</p>
 */
@CompileDynamic
class SharedConfigAccessSupport {

    private static final Logger logger = LoggerFactory.getLogger(SharedConfigAccessSupport.class)

    static final String CONFIG_TYPE_HOTWAX_OMS = "SCFG_HOTWAX_OMS"
    static final String CONFIG_TYPE_SHOPIFY_AUTH = "SCFG_SHOPIFY_AUTH"
    static final String CONFIG_TYPE_NS_AUTH = "SCFG_NS_AUTH"
    static final String CONFIG_TYPE_NS_RESTLET = "SCFG_NS_RESTLET"

    /**
     * The catalog of shareable config types.
     *
     * <p>{@code moqui.service.message.SystemMessageRemote} is deliberately ABSENT. It is a
     * framework-owned endpoint descriptor (URL + sendServiceName) already read through
     * {@code findGlobalUnscoped} and therefore already visible to every tenant; it carries no
     * per-tenant credential on the OMS or Shopify path. Sharing it would be a no-op, and adding a
     * grant row for it would imply a tenant boundary it does not have.</p>
     */
    static final Map<String, Map<String, String>> CONFIG_TYPE_REGISTRY = [
            (CONFIG_TYPE_HOTWAX_OMS)  : [entityName: DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG,
                                         pkField   : "omsRestSourceConfigId",
                                         label     : "HotWax OMS source config"],
            (CONFIG_TYPE_SHOPIFY_AUTH): [entityName: DarpanEntityConstants.SHOPIFY_AUTH_CONFIG,
                                         pkField   : "shopifyAuthConfigId",
                                         label     : "Shopify auth config"],
            (CONFIG_TYPE_NS_AUTH)     : [entityName: DarpanEntityConstants.NS_AUTH_CONFIG,
                                         pkField   : "nsAuthConfigId",
                                         label     : "NetSuite auth config"],
            (CONFIG_TYPE_NS_RESTLET)  : [entityName: DarpanEntityConstants.NS_RESTLET_CONFIG,
                                         pkField   : "nsRestletConfigId",
                                         label     : "NetSuite Restlet config"],
    ].asImmutable()

    private static final String GRANT_READ_REASON =
            "shared-config grant table; keyed by explicit configTypeEnumId/configId/tenantUserGroupId — " +
            "the cross-tenant peer-group join is the point of this read (DAR-BE-005)"

    /** Registry row for a config type, or null when the type is unknown or blank. */
    static Map<String, String> configType(String configTypeEnumId) {
        String normalized = normalize(configTypeEnumId)
        if (!normalized) return null
        return CONFIG_TYPE_REGISTRY[normalized]
    }

    /** Every tenant currently in this config's peer group, sorted. Empty when it is not shared. */
    static List<String> listMemberTenantIds(def ec, String configTypeEnumId, String configId) {
        String normalizedConfigId = normalize(configId)
        if (configType(configTypeEnumId) == null || !normalizedConfigId) return []

        return activeGrants(ec) { def finder ->
            finder.condition("configTypeEnumId", normalize(configTypeEnumId))
                    .condition("configId", normalizedConfigId)
        }
                .collect { row -> normalize(readField(row, "tenantUserGroupId")) }
                .findAll { it != null }
                .unique()
                .sort() as List<String>
    }

    /** Ids of configs of this type shared TO the named tenant. Does NOT include tenant-owned rows. */
    static Set<String> listSharedConfigIdsForTenant(def ec, String configTypeEnumId, String tenantUserGroupId) {
        String normalizedTenant = normalize(tenantUserGroupId)
        if (configType(configTypeEnumId) == null || !normalizedTenant) return [] as Set

        return activeGrants(ec) { def finder ->
            finder.condition("configTypeEnumId", normalize(configTypeEnumId))
                    .condition("tenantUserGroupId", normalizedTenant)
        }
                .collect { row -> normalize(readField(row, "configId")) }
                .findAll { it != null }
                .toSet() as Set<String>
    }

    /** True when an active grant puts this tenant in this config's peer group. */
    static boolean isSharedWithTenant(def ec, String configTypeEnumId, String configId, String tenantUserGroupId) {
        String normalizedConfigId = normalize(configId)
        if (!normalizedConfigId) return false
        return normalizedConfigId in listSharedConfigIdsForTenant(ec, configTypeEnumId, tenantUserGroupId)
    }

    /**
     * Active grant rows (per {@link #isActiveGrant}) for exactly this
     * (configTypeEnumId, configId, tenantUserGroupId) triple.
     *
     * <p>This is the ONE place that decides "is this specific grant currently active" — every
     * reader in this class funnels through {@link #isActiveGrant} already; this exposes the same
     * decision to writers (namely {@code SharedConfigGrantSupport.revokeAccess}) so reader and
     * writer cannot disagree about what "active" means. Before this existed, revoke filtered on
     * {@code thruDate == null} while this class's readers (which gate live credential access)
     * treated a FUTURE thruDate as active too — a row with a future thruDate was reachable by
     * {@code listMemberTenantIds} but invisible to revoke's own filter, making it un-revokable
     * (DAR-BE-005 review finding, 2026-08-11).</p>
     */
    static List listActiveGrantRows(def ec, String configTypeEnumId, String configId, String tenantUserGroupId) {
        String normalizedConfigId = normalize(configId)
        String normalizedTenant = normalize(tenantUserGroupId)
        if (configType(configTypeEnumId) == null || !normalizedConfigId || !normalizedTenant) return []

        return activeGrants(ec) { def finder ->
            finder.condition("configTypeEnumId", normalize(configTypeEnumId))
                    .condition("configId", normalizedConfigId)
                    .condition("tenantUserGroupId", normalizedTenant)
        }
    }

    /**
     * The owner-or-shared decision for the ACTIVE tenant, given an already-loaded config record.
     *
     * <p>Strictly wider than {@link TenantAccessSupport#canAccessTenantRecord}: it returns true for
     * everything that method returns true for, plus peer-group members. With zero grant rows the two
     * are identical — which is what makes sharing backward compatible.</p>
     */
    static boolean canActiveTenantUseConfig(def ec, String configTypeEnumId, def configRecord) {
        if (configRecord == null) return false
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return false

        String ownerTenantUserGroupId = normalize(readField(configRecord, "companyUserGroupId"))
        if (ownerTenantUserGroupId && ownerTenantUserGroupId == activeTenantUserGroupId) return true

        Map<String, String> type = configType(configTypeEnumId)
        if (type == null) return false
        String configId = normalize(readField(configRecord, type.pkField))
        return isSharedWithTenant(ec, configTypeEnumId, configId, activeTenantUserGroupId)
    }

    /**
     * Every config row of this type the ACTIVE tenant may use: the rows it owns, plus the rows
     * shared with it. Ordered by description then PK, deduped by PK.
     *
     * <p>Owned rows come through {@link TenantScopedFinder#findTenantScoped} — untouched, still
     * default-deny. Shared rows are fetched by explicit PK, one at a time: a peer group is a handful
     * of tenants, and a point lookup keeps the cross-tenant read narrow and auditable rather than
     * handing a caller an unbounded unscoped finder.</p>
     *
     * <p>With zero grant rows this returns exactly what the tenant-only finder returns, which is what
     * makes the settings lists backward compatible.</p>
     */
    static List listAccessibleConfigRows(def ec, String configTypeEnumId) {
        Map<String, String> type = configType(configTypeEnumId)
        if (type == null) return []
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        List ownedRows = TenantScopedFinder.findTenantScoped(ec, type.entityName)
                .orderBy("description,${type.pkField}")
                .useCache(false)
                .list() ?: []

        Set<String> ownedIds = ownedRows.collect { normalize(readField(it, type.pkField)) }.findAll { it } as Set
        Set<String> sharedIds = listSharedConfigIdsForTenant(ec, configTypeEnumId, activeTenantUserGroupId)

        List sharedRows = (sharedIds - ownedIds).collect { String configId ->
            TenantScopedFinder.findGlobalUnscoped(ec, type.entityName,
                            "shared config resolved by explicit PK for a tenant holding an active " +
                            "ConfigTenantAccess grant (DAR-BE-005)")
                    .condition(type.pkField, configId)
                    .useCache(false)
                    .one()
        }.findAll { it != null }

        return (ownedRows + sharedRows).sort { left, right ->
            String leftKey = normalize(readField(left, "description")) ?: normalize(readField(left, type.pkField)) ?: ""
            String rightKey = normalize(readField(right, "description")) ?: normalize(readField(right, type.pkField)) ?: ""
            int byDescription = leftKey <=> rightKey
            return byDescription != 0 ? byDescription :
                    ((normalize(readField(left, type.pkField)) ?: "") <=> (normalize(readField(right, type.pkField)) ?: ""))
        }
    }

    /**
     * Reads grant rows and drops revoked ones IN GROOVY rather than via {@code conditionDate}.
     *
     * <p>Matches {@code AdminMembershipSupport.findActiveMembershipRows}, the existing house pattern
     * for this table shape. It is also the only form the stub-based tests can actually prove:
     * {@code FinderStub.conditionDate} is a no-op, so a conditionDate-based expiry check would pass
     * a stub test without filtering anything.</p>
     */
    private static List activeGrants(def ec, Closure applyConditions) {
        try {
            def finder = TenantScopedFinder.findGlobalUnscoped(ec,
                    DarpanEntityConstants.CONFIG_TENANT_ACCESS, GRANT_READ_REASON)
            applyConditions(finder)
            finder.useCache(false)

            def now = ec?.user?.nowTimestamp
            return (finder.list() ?: []).findAll { row -> isActiveGrant(row, now) }
        } catch (Exception e) {
            // Fail CLOSED: a resolver failure must withhold access, never widen it.
            logger.warn("Failed to read shared-config grants; treating as not shared", e)
            return []
        }
    }

    private static boolean isActiveGrant(def row, def now) {
        def thruDate = readField(row, "thruDate")
        if (thruDate == null) return true
        if (now == null) return false
        return thruDate.compareTo(now) > 0
    }

    private static Object readField(def record, String fieldName) {
        if (record == null || !fieldName) return null
        return record instanceof Map ? ((Map) record).get(fieldName) : record."${fieldName}"
    }
}
