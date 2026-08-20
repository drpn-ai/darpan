package darpan.migration

import darpan.admin.AdminAccessSupport
import groovy.transform.CompileDynamic

/**
 * Remote admin surface over the migration supervisor — everything an operator, or an agent acting
 * for one, needs to finish a client installation's migration work without SSH or database access.
 *
 * <p>Two gates gate every method. The first is the {@code DARPAN_ADMIN_API} artifact fence, whose
 * {@code admin\..*} pattern denies a non-super-admin before any body here runs (see
 * {@code data/SecuritySeedData.xml}). The second is the explicit {@link #gate} call below. The
 * fence is the control; this is the backstop for an installation whose security seed data did not
 * load — a failure mode this codebase has already seen, when missing seed rows made a shipped
 * feature silently do nothing on a deployed instance.</p>
 *
 * <p>The underlying {@code migration.*} services stay {@code allow-remote="false"}. Nothing here
 * widens them: these wrappers are the only remote entry point, and they add the super-admin gate
 * the supervisor deliberately does not carry, because it also runs from a Moqui screen.</p>
 *
 * <p><strong>Scope is the installation, not the tenant.</strong> Every registered migration is a
 * cross-tenant sweep and the ledger records what ran on this backend. "Which client" is chosen by
 * pointing at that client's backend, not by a parameter.</p>
 */
@CompileDynamic
class MigrationAdminSupport {

    static final String LIST_SERVICE = "migration.MigrationServices.list#MigrationStatus"
    static final String HISTORY_SERVICE = "migration.MigrationServices.get#MigrationHistory"
    static final String RUN_PENDING_SERVICE = "migration.MigrationServices.run#PendingMigrations"
    static final String RUN_ONE_SERVICE = "migration.MigrationServices.run#Migration"
    static final String DRY_RUN_ONE_SERVICE = "migration.MigrationServices.dryRun#Migration"
    static final String SET_ENABLED_SERVICE = "migration.MigrationServices.set#MigrationEnabled"

    /** The real gate. Held separately so tests can drive the denied branch without UserGroup rows. */
    static final Closure<Boolean> DEFAULT_GATE = { ec -> AdminAccessSupport.requireSuperAdmin(ec) }

    /** Seam for the super-admin gate. Production never reassigns this. */
    static Closure<Boolean> superAdminGate = DEFAULT_GATE

    /** Registry and ledger state for every registered migration, or null (with an error) if denied. */
    static List<Map<String, Object>> listStatus(def ec) {
        if (!gate(ec)) return null
        return (call(ec, LIST_SERVICE, [:])?.get("migrations") as List<Map<String, Object>>) ?: []
    }

    /** Every recorded attempt at one migration, newest first, or null (with an error) if denied. */
    static List<Map<String, Object>> history(def ec, String migrationId, Integer maxRows) {
        if (!gate(ec)) return null
        return (call(ec, HISTORY_SERVICE, [migrationId: migrationId, maxRows: maxRows])
                ?.get("attempts") as List<Map<String, Object>>) ?: []
    }

    /** Runs, or previews, everything outstanding. Null (with an error) if denied. */
    static Map<String, Object> runPending(def ec, boolean dryRun) {
        if (!gate(ec)) return null
        Map<String, Object> out = call(ec, RUN_PENDING_SERVICE, [dryRun: dryRun])
        return [results     : (out?.get("results") as List) ?: [],
                appliedCount: asInt(out?.get("appliedCount")),
                skippedCount: asInt(out?.get("skippedCount")),
                blockedCount: asInt(out?.get("blockedCount")),
                failedCount : asInt(out?.get("failedCount"))]
    }

    /** Runs, or previews, one named migration. Null (with an error) if denied. */
    static Map<String, Object> runMigration(def ec, String migrationId, boolean dryRun, boolean force) {
        if (!gate(ec)) return null
        Map<String, Object> params = dryRun ? [migrationId: migrationId]
                : [migrationId: migrationId, force: force]
        Map<String, Object> out = call(ec, dryRun ? DRY_RUN_ONE_SERVICE : RUN_ONE_SERVICE, params)
        return [migrationId : migrationId,
                outcome     : out?.get("outcome"),
                runId       : out?.get("runId"),
                rowsAffected: out?.get("rowsAffected"),
                detail      : out?.get("detail")]
    }

    /** Parks or unparks a migration. False (with an error) if denied or unknown. */
    static boolean setEnabled(def ec, String migrationId, boolean enabled) {
        if (!gate(ec)) return false
        return call(ec, SET_ENABLED_SERVICE, [migrationId: migrationId, enabled: enabled])
                ?.get("ok") == true
    }

    private static Map<String, Object> call(def ec, String serviceName, Map<String, Object> parameters) {
        return ec.service.sync().name(serviceName).parameters(parameters).call()
    }

    private static boolean gate(def ec) {
        boolean allowed = superAdminGate.call(ec)
        if (!allowed && !ec.message.hasError()) {
            ec.message.addError(AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE)
        }
        return allowed
    }

    private static int asInt(Object raw) {
        return raw instanceof Number ? ((Number) raw).intValue() : 0
    }
}
