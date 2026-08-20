package darpan.migration

import darpan.facade.common.TenantScopedFinder

import java.sql.Timestamp

/**
 * Walks the {@code DarpanMigration} registry and runs whatever is outstanding.
 *
 * <p>Runs in the ambient transaction, never a forced-new one. Every ledger write goes through
 * {@code migration.MigrationServices.record#MigrationRun}, which owns that boundary — see
 * {@link MigrationLedgerSupport}.</p>
 *
 * <p>Failure is detected two ways because Moqui services signal it two ways: an exception, and
 * errors accumulated on {@code ec.message}. The second is far more common in this codebase, so
 * checking only for a thrown exception would record failures as successes.</p>
 */
class MigrationSupervisorSupport {
    static final String REGISTRY_ENTITY = "darpan.migration.DarpanMigration"
    static final String LEDGER_ENTITY = "darpan.migration.DarpanMigrationRun"
    static final String RECORD_SERVICE = "migration.MigrationServices.record#MigrationRun"
    static final String PREREQ_ENTITY = "darpan.migration.DarpanMigrationPrereq"

    /**
     * Justifications for the tenant-unscoped reads below. The migration entities carry no
     * companyUserGroupId at all — a migration is a property of the INSTALLATION, not of a tenant —
     * so there is no tenant condition to apply and TenantScopedFinder.findTenantScoped would
     * default-deny every row. findGlobalUnscoped is the sanctioned opt-out for exactly this shape.
     */
    static final String REGISTRY_READ_REASON =
            "migration registry is installation-wide reference data, not tenant-owned"
    static final String LEDGER_READ_REASON =
            "migration ledger records what ran on this installation, not on a tenant"

    // DarpanMigration and DarpanMigrationPrereq are declared cache="true" — correct for reference
    // data, wrong for the reads below. These decide whether a migration may run against a client's
    // production data, and a stale cache would answer that question from a snapshot: a prereq row
    // added after the first query, or an enabled flag just flipped, would go unseen and the
    // migration would run anyway. Every decision-making read therefore bypasses the cache.

    static final String OUTCOME_APPLIED = "APPLIED"
    static final String OUTCOME_ALREADY_APPLIED = "ALREADY_APPLIED"
    static final String OUTCOME_BLOCKED = "BLOCKED"
    static final String OUTCOME_FAILED = "FAILED"
    static final String OUTCOME_NO_PREVIEW = "NO_PREVIEW"
    static final String OUTCOME_NOT_FOUND = "NOT_FOUND"
    static final String OUTCOME_DISABLED = "DISABLED"

    /** True when this migration has at least one SUCCESS ledger row. DRY_RUN and FAILED do not count. */
    static boolean hasSucceeded(def ec, String migrationId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, LEDGER_ENTITY, LEDGER_READ_REASON)
                .condition("migrationId", migrationId)
                .condition("statusId", MigrationLedgerSupport.STATUS_SUCCESS)
                .count() > 0
    }

    /**
     * Prerequisites of this migration that have no SUCCESS ledger row.
     *
     * <p>Verified rather than sorted on: sequenceNum decides the order the registry is walked, these
     * rows decide whether a migration reached in that order is allowed to run. Separating the two
     * avoids a topological sort for what is currently seven migrations, and makes a disagreement
     * between declared order and declared dependency visible as a BLOCKED outcome rather than as
     * silently wrong results.</p>
     */
    static List<String> unmetPrereqs(def ec, String migrationId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, PREREQ_ENTITY, REGISTRY_READ_REASON)
                .useCache(false)
                .condition("migrationId", migrationId)
                .orderBy("prereqMigrationId")
                .list()
                .collect { it.getString("prereqMigrationId") }
                .findAll { !hasSucceeded(ec, it) }
    }

    static final String STATUS_APPLIED = "APPLIED"
    static final String STATUS_PENDING = "PENDING"
    static final String STATUS_DISABLED = "DISABLED"

    /**
     * Registry, ledger and prerequisite state joined into one row per migration, for the admin
     * screen. Kept in Groovy rather than screen XML so it is testable.
     */
    static List<Map<String, Object>> statusList(def ec) {
        return TenantScopedFinder.findGlobalUnscoped(ec, REGISTRY_ENTITY, REGISTRY_READ_REASON)
                .useCache(false)
                .orderBy("sequenceNum")
                .list()
                .collect { row ->
                    String migrationId = row.getString("migrationId")

                    def attempts = TenantScopedFinder
                            .findGlobalUnscoped(ec, LEDGER_ENTITY, LEDGER_READ_REASON)
                            .condition("migrationId", migrationId)
                            .orderBy("-startedDate")
                            .list()
                    def latest = attempts ? attempts.first() : null

                    boolean enabled = row.getString("enabled") == "Y"

                    String status
                    if (hasSucceeded(ec, migrationId)) status = STATUS_APPLIED
                    // DISABLED outranks PENDING. runPending filters on enabled but statusList does
                    // not, so a parked migration used to read PENDING while the sweep silently
                    // skipped it — an operator would run the sweep, see nothing happen, and have
                    // nothing on the screen explaining why.
                    else if (!enabled) status = STATUS_DISABLED
                    else if (unmetPrereqs(ec, migrationId)) status = OUTCOME_BLOCKED
                    else status = STATUS_PENDING

                    return [migrationId   : migrationId,
                            description   : row.getString("description"),
                            sequenceNum   : row.get("sequenceNum"),
                            supportsDryRun: row.getString("supportsDryRun") == "Y",
                            enabled       : enabled,
                            status        : status,
                            lastRunDate   : latest?.get("completedDate"),
                            lastStatusId  : latest?.getString("statusId"),
                            // Read the failure text back out. The supervisor already sanitizes it on
                            // write; without this the ledger is write-only from the API's point of
                            // view and diagnosing a client failure needs database access.
                            lastMessageDetail: latest?.getString("messageDetail"),
                            lastRunId     : latest?.getString("runId"),
                            rowsAffected  : latest?.get("rowsAffected"),
                            attemptCount  : attempts.size()]
                }
    }

    /** Default cap on returned attempts. A ledger row per retry means this list is unbounded. */
    static final int DEFAULT_HISTORY_LIMIT = 50

    /**
     * Every recorded attempt at one migration, newest first.
     *
     * <p>Separate from {@link #statusList} because status answers "where does this installation
     * stand" while history answers "what happened here, and why" — the second is what an operator
     * needs when a client's migration failed and the only alternative is reading the database.</p>
     */
    static List<Map<String, Object>> history(def ec, String migrationId, Integer maxRows) {
        int limit = (maxRows != null && maxRows > 0) ? maxRows : DEFAULT_HISTORY_LIMIT
        return TenantScopedFinder.findGlobalUnscoped(ec, LEDGER_ENTITY, LEDGER_READ_REASON)
                .condition("migrationId", migrationId)
                .orderBy("-startedDate")
                .limit(limit)
                .list()
                .collect { row ->
                    [runId          : row.getString("runId"),
                     statusId       : row.getString("statusId"),
                     startedDate    : row.get("startedDate"),
                     completedDate  : row.get("completedDate"),
                     appliedByUserId: row.getString("appliedByUserId"),
                     rowsAffected   : row.get("rowsAffected"),
                     messageDetail  : row.getString("messageDetail")]
                }
    }

    /** Park or unpark a migration. A parked migration is skipped by the sweep and refused by name. */
    static boolean setEnabled(def ec, String migrationId, boolean enabled) {
        def row = TenantScopedFinder.findGlobalUnscoped(ec, REGISTRY_ENTITY, REGISTRY_READ_REASON)
                .useCache(false)
                .condition("migrationId", migrationId)
                .one()
        if (row == null) {
            ec.message.addError("No registered migration with id ${migrationId}")
            return false
        }
        row.set("enabled", enabled ? "Y" : "N")
        row.set("lastUpdatedDate", ec.user.nowTimestamp)
        row.update()
        return true
    }

    /**
     * Runs one named migration, for targeted remediation on a single installation.
     *
     * <p>Applies the same gates as the sweep, in the same order, with one addition: {@code force}
     * re-runs a migration that already has a SUCCESS row. That exists because re-running a backfill
     * on a client is a real operation, and the alternative is editing the ledger by hand.</p>
     *
     * <p>{@code force} does NOT override prerequisites. Re-running something is a judgement call an
     * operator can make; running it ahead of its prerequisite breaks the one ordering guarantee the
     * design rests on, and no flag should be able to do that.</p>
     */
    static Map<String, Object> runMigration(def ec, String migrationId, boolean dryRun, boolean force) {
        def row = TenantScopedFinder.findGlobalUnscoped(ec, REGISTRY_ENTITY, REGISTRY_READ_REASON)
                .useCache(false)
                .condition("migrationId", migrationId)
                .one()
        if (row == null) {
            // Loudly, not as an empty success: an agent walking a stale list of ids must not read a
            // typo as "already done".
            ec.message.addError("No registered migration with id ${migrationId}")
            return [migrationId: migrationId, outcome: OUTCOME_NOT_FOUND,
                    detail     : "no registered migration with that id"]
        }
        if (row.getString("enabled") != "Y") {
            return [migrationId: migrationId, outcome: OUTCOME_DISABLED,
                    detail     : "this migration is parked; re-enable it before running"]
        }
        if (!force && hasSucceeded(ec, migrationId)) {
            return [migrationId: migrationId, outcome: OUTCOME_ALREADY_APPLIED]
        }

        List<String> unmet = unmetPrereqs(ec, migrationId)
        if (unmet) return recordBlocked(ec, migrationId, unmet)

        if (dryRun && row.getString("supportsDryRun") != "Y") {
            return [migrationId: migrationId, outcome: OUTCOME_NO_PREVIEW,
                    detail     : "this migration has no preview mode yet"]
        }

        return runOne(ec, migrationId, row.getString("serviceName"), dryRun)
    }

    static List<Map<String, Object>> runPending(def ec, boolean dryRun) {
        List<Map<String, Object>> results = []

        def registryRows = TenantScopedFinder.findGlobalUnscoped(ec, REGISTRY_ENTITY, REGISTRY_READ_REASON)
                .useCache(false)
                .condition("enabled", "Y")
                .orderBy("sequenceNum")
                .list()

        registryRows.each { row ->
            String migrationId = row.getString("migrationId")

            if (hasSucceeded(ec, migrationId)) {
                results << [migrationId: migrationId, outcome: OUTCOME_ALREADY_APPLIED]
                return
            }

            List<String> unmet = unmetPrereqs(ec, migrationId)
            if (unmet) {
                results << recordBlocked(ec, migrationId, unmet)
                return
            }
            if (dryRun && row.getString("supportsDryRun") != "Y") {
                results << [migrationId: migrationId, outcome: OUTCOME_NO_PREVIEW,
                            detail     : "this migration has no preview mode yet"]
                return
            }

            results << runOne(ec, migrationId, row.getString("serviceName"), dryRun)
        }

        return results
    }

    /** Records a BLOCKED attempt and describes it. Shared by the sweep and the targeted run. */
    private static Map<String, Object> recordBlocked(def ec, String migrationId, List<String> unmet) {
        String detail = "blocked: prerequisite(s) not applied — ${unmet.join(', ')}"
        String blockedRunId = ec.service.sync()
                .name(RECORD_SERVICE)
                .parameters([migrationId  : migrationId,
                             statusId     : MigrationLedgerSupport.STATUS_BLOCKED,
                             messageDetail: detail])
                .disableAuthz()
                .call()
                ?.runId as String
        return [migrationId: migrationId, outcome: OUTCOME_BLOCKED,
                runId      : blockedRunId, detail: detail]
    }

    private static Map<String, Object> runOne(def ec, String migrationId, String serviceName, boolean dryRun) {
        Timestamp started = ec.user.nowTimestamp
        // Moqui refuses to run ANY service while errors sit on the message facade
        // (ServiceCallSyncImpl.java:135). Clearing here is what lets the migration run at all;
        // clearing again below is what lets its ledger row be written at all.
        ec.message.clearErrors()

        String detail = null
        Integer rowsAffected = null
        boolean failed = false

        try {
            Map<String, Object> parameters = dryRun ? [dryRun: true] : [:]
            Map<String, Object> out = ec.service.sync()
                    .name(serviceName)
                    .parameters(parameters)
                    .disableAuthz()
                    .call()

            rowsAffected = firstRowCount(out)
            detail = describe(out)
            failed = ec.message.hasError()
            if (failed) detail = sanitize(ec.message.errorsString)
        } catch (Throwable t) {
            failed = true
            detail = sanitize("${t.class.simpleName}: ${t.message}")
        }

        ec.message.clearErrors()

        String statusId = failed ? MigrationLedgerSupport.STATUS_FAILED
                : (dryRun ? MigrationLedgerSupport.STATUS_DRY_RUN : MigrationLedgerSupport.STATUS_SUCCESS)

        String runId = ec.service.sync()
                .name(RECORD_SERVICE)
                .parameters([migrationId  : migrationId,
                             statusId     : statusId,
                             startedDate  : started,
                             rowsAffected : rowsAffected,
                             messageDetail: detail])
                .disableAuthz()
                .call()
                ?.runId as String

        return [migrationId : migrationId,
                outcome     : failed ? OUTCOME_FAILED : OUTCOME_APPLIED,
                runId       : runId,
                rowsAffected: rowsAffected,
                detail      : detail]
    }

    /**
     * The six existing migrations each report their own count under a differently named out-parameter
     * (rowsWritten, rowsDeleted, disabledConfigCount, ...). Rather than force a uniform contract onto
     * services that already shipped, take the first integer-valued out-parameter whose name ends in
     * Count or begins with rows. A migration reporting nothing simply records a null count.
     */
    private static Integer firstRowCount(Map<String, Object> out) {
        if (out == null) return null
        def entry = out.find { key, value ->
            value instanceof Number &&
                    (key.toString().endsWith("Count") || key.toString().startsWith("rows"))
        }
        return entry == null ? null : ((Number) entry.value).intValue()
    }

    private static String describe(Map<String, Object> out) {
        List messages = out?.get("messages") as List
        return messages ? sanitize(messages.join("; ")) : null
    }

    /**
     * Migration output is written to a persisted ledger an admin screen renders, so anything
     * secret-shaped is removed here rather than trusted not to appear.
     */
    private static String sanitize(String raw) {
        if (!raw) return null
        return raw.replaceAll(/(?i)(password|token|secret|apikey|api_key|webhook)\s*[=:]\s*\S+/, '$1=[redacted]')
    }
}
