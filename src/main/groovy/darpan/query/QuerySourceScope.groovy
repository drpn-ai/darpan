package darpan.query

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.moqui.context.ArtifactAuthorizationException

/**
 * DAR-BE-334. moqui-jexl runs a query's read as its owner in a fresh context with no tenant chosen. A Darpan source
 * takes the tenant from the saved query (userScriptId), or for an unsaved run from the ownerKey the facade passed (the
 * active tenant), never from the read spec; enters that tenant as the owner's requested tenant (refused unless the owner
 * is a current member) so tenant-checked readers and filters see it; and reads with explicit tenant conditions.
 */
class QuerySourceScope {
    /** body(tenantId) inside the query's tenant, or an error map {error: {kind, message}}. */
    static Map<String, Object> withTenant(def ec, Map params, Closure<Map<String, Object>> body) {
        String tenantId
        String userScriptId = params.userScriptId as String
        if (userScriptId) {
            def script = TenantScopedFinder.findGlobalUnscoped(ec, "jexl.script.UserScript",
                    "DAR-BE-334 query read: the saved query names the tenant the read is for").condition("userScriptId", userScriptId).useCache(false).one()
            if (script == null) return error("READ_ERROR", "The query ${userScriptId} was not found.")
            tenantId = script.companyUserGroupId as String
        } else {
            tenantId = params.ownerKey as String
        }
        if (!tenantId) return error("READ_NOT_ALLOWED", "A query reads for one tenant, and this one names none.")
        try {
            return TenantAccessSupport.withRequestedTenant(tenantId) {
                TenantAccessSupport.syncUserContext(ec)
                if (TenantAccessSupport.currentActiveTenantUserGroupId(ec) != tenantId) {
                    return error("READ_NOT_ALLOWED", "The query's owner is not a member of tenant ${tenantId}.")
                }
                // NOTE: reads below name the tenant explicitly; authz is off so internal readers need no per-entity grant
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    return body.call(tenantId)
                } finally {
                    if (!wasDisabled) ec.artifactExecution.enableAuthz()
                }
            }
        } catch (ArtifactAuthorizationException ignored) {
            return error("READ_NOT_ALLOWED", "The query's owner is not a member of tenant ${tenantId}.")
        }
    }

    static Map<String, Object> error(String kind, Object message) {
        return [error: [kind: kind, message: message.toString()]] as Map<String, Object>
    }
}
