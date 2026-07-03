package darpan.facade.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Default-secure tenant-scoped finder for {@code disableAuthz} reads (P0 #4).
 *
 * <p>Tenant scoping is the DEFAULT — every {@link #findTenantScoped} and
 * {@link #findTenantScopedById} call pre-applies the active tenant condition before
 * returning the finder/record. Opting out requires the explicitly-named
 * {@link #findGlobalUnscoped} with a mandatory justification string.</p>
 *
 * <p>Call sites that have been migrated through this finder remove the need for a bare
 * {@code .disableAuthz()} in production code. The {@code DisableAuthzRatchetTest} lint
 * test tracks the remaining bare-call count and enforces a ratchet-down baseline.</p>
 *
 * <p>All methods delegate to {@link TenantAccessSupport} for tenant resolution and
 * record-level access gating.</p>
 *
 * <p>ALLOWLISTED for bare {@code .disableAuthz()} usage (see DisableAuthzRatchetTest):
 * {@code TenantScopedFinder.groovy} itself and {@code TenantAccessSupport.groovy}.</p>
 */
class TenantScopedFinder {

    private static final Logger logger = LoggerFactory.getLogger(TenantScopedFinder.class)

    /**
     * Sentinel value used as the {@code companyUserGroupId} condition when no active tenant is
     * resolved.  It is deliberately impossible to match any real tenant ID so that the finder
     * returns EMPTY instead of falling open to all rows (default-deny).
     */
    static final String NO_ACTIVE_TENANT_SENTINEL = "__NO_ACTIVE_TENANT__"

    /**
     * Returns an {@code EntityFind} pre-scoped to the current active tenant via
     * {@code condition("companyUserGroupId", activeTenant)} and {@code disableAuthz()}.
     *
     * <p>If the active tenant is null or blank (unauthenticated / no tenant selected) the
     * impossible sentinel condition {@link #NO_ACTIVE_TENANT_SENTINEL} is applied so the
     * finder returns zero rows — never global rows (default-deny).</p>
     *
     * @param ec         Moqui ExecutionContext (dynamic, matches live and stub callers)
     * @param entityName entity to search
     * @return an {@code EntityFind} with authz disabled and tenant condition pre-applied
     */
    static def findTenantScoped(def ec, String entityName) {
        String activeTenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        def finder = ec.entity.find(entityName).disableAuthz()
        if (!activeTenant) {
            // No active tenant — apply impossible sentinel to prevent global fall-open (default-deny).
            return finder.condition("companyUserGroupId", NO_ACTIVE_TENANT_SENTINEL)
        }
        return finder.condition("companyUserGroupId", activeTenant)
    }

    /**
     * Loads a single record by PK under {@code disableAuthz} and then gates it through
     * {@link TenantAccessSupport#canAccessTenantRecord} before returning.
     *
     * <p>Returns {@code null} for both a missing record and a foreign-tenant record.
     * An error is added to {@code ec.message} (not-found or forbidden) in either case via
     * {@link TenantAccessSupport#requireTenantRecordAccess}.  Callers MUST check
     * {@code ec.message.hasError()} before consuming the returned value.</p>
     *
     * <p>This is fail-safe: a caller doing the obvious {@code if (rec != null)} check cannot
     * accidentally leak cross-tenant data, because foreign records are withheld (null), not
     * returned with an error marker.</p>
     *
     * @param ec         Moqui ExecutionContext
     * @param entityName entity to search
     * @param pkField    primary-key field name
     * @param pkValue    primary-key value
     * @return the gated entity record, or {@code null} if not found or belongs to a foreign tenant
     */
    static def findTenantScopedById(def ec, String entityName, String pkField, Object pkValue) {
        def rec = ec.entity.find(entityName).condition(pkField, pkValue).disableAuthz().one()
        if (rec == null || !TenantAccessSupport.canAccessTenantRecord(ec, rec)) {
            TenantAccessSupport.requireTenantRecordAccess(ec, rec)  // sets the standard not-found / forbidden ec.message error
            return null
        }
        return rec
    }

    /**
     * Identical fail-safe semantics to {@link #findTenantScopedById} — returns the record only if
     * it exists and is owned by the active tenant, {@code null} otherwise — but adds NO error to
     * {@code ec.message} on denial.
     *
     * <p>Uses {@link TenantAccessSupport#canAccessTenantRecord} for the ownership decision; does NOT
     * call {@link TenantAccessSupport#requireTenantRecordAccess}.  Intended for callers that expect
     * and handle a denial themselves (e.g. a create-fresh fallback) and must not pollute the caller's
     * error state with a gate error they already handle silently.</p>
     *
     * @param ec         Moqui ExecutionContext
     * @param entityName entity to search
     * @param pkField    primary-key field name
     * @param pkValue    primary-key value
     * @return the gated entity record, or {@code null} if not found or belongs to a foreign tenant
     */
    static def findTenantScopedByIdQuiet(def ec, String entityName, String pkField, Object pkValue) {
        def rec = ec.entity.find(entityName).condition(pkField, pkValue).disableAuthz().one()
        if (rec == null || !TenantAccessSupport.canAccessTenantRecord(ec, rec)) {
            return null
        }
        return rec
    }

    /**
     * Resolves and gates the parent record (via {@link #findTenantScopedById}), then returns
     * an {@code EntityFind} for its children keyed by {@code childFkField}.
     *
     * <p>If the parent is not found or is not owned by the active tenant, an error is added to
     * {@code ec.message} and {@code null} is returned — no child finder is exposed (prevents
     * cross-tenant child enumeration).  Callers MUST check {@code ec.message.hasError()}.</p>
     *
     * @param ec               Moqui ExecutionContext
     * @param childEntity      child entity to search
     * @param parentEntity     parent entity name (must have {@code companyUserGroupId})
     * @param parentPkField    primary-key field name on the parent
     * @param parentPkValue    primary-key value identifying the specific parent
     * @param childFkField     foreign-key field on the child that references the parent PK
     * @return an {@code EntityFind} for the children scoped to the parent PK, or {@code null}
     *         if the parent gate failed
     */
    static def findTenantScopedChildren(def ec, String childEntity, String parentEntity,
            String parentPkField, Object parentPkValue, String childFkField) {
        // FIRST: resolve and gate the parent SILENTLY — a foreign or missing parent yields null without
        // polluting ec.message.  Callers (e.g. resolveRuleSetCompareScopeConfig) translate a null return
        // into their own IllegalArgumentException, so the extra error added by findTenantScopedById would
        // be noise / a double-error there.  Use findTenantScopedByIdQuiet for a clean null-only signal.
        def parent = findTenantScopedByIdQuiet(ec, parentEntity, parentPkField, parentPkValue)
        // Do not leak child rows if the parent is inaccessible (null = not found; canAccess = false = wrong tenant).
        if (!TenantAccessSupport.canAccessTenantRecord(ec, parent)) return null
        return ec.entity.find(childEntity).condition(childFkField, parentPkValue).disableAuthz()
    }

    /**
     * Deliberately verbose opt-out for legitimate tenant-neutral reads (framework reference data,
     * enums, self-scoped auth keyed by current userId / token hash, system-cron sweeps).
     *
     * <p>A non-blank {@code reason} is mandatory — this prevents silent omission of intent and
     * leaves an audit-log trail.  Passing a blank or null reason throws
     * {@link IllegalArgumentException} immediately.</p>
     *
     * @param ec         Moqui ExecutionContext
     * @param entityName entity to search without tenant scoping
     * @param reason     non-blank justification for skipping tenant scoping (audit-logged)
     * @return an {@code EntityFind} with authz disabled and NO tenant condition applied
     * @throws IllegalArgumentException if {@code reason} is blank or null
     */
    static def findGlobalUnscoped(def ec, String entityName, String reason) {
        if (!reason?.trim()) {
            throw new IllegalArgumentException(
                    "findGlobalUnscoped requires a non-blank reason; got: '${reason}'. " +
                    "Pass a justification string (e.g. \"framework enum reference data\") — " +
                    "this is the opt-out gate for tenant-neutral reads."
            )
        }
        logger.info("[tenant-scope] GLOBAL unscoped read of {}: {}", entityName, reason)
        return ec.entity.find(entityName).disableAuthz()
    }
}
