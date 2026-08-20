package darpan.migration

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

    static final String OUTCOME_APPLIED = "APPLIED"
    static final String OUTCOME_ALREADY_APPLIED = "ALREADY_APPLIED"
    static final String OUTCOME_BLOCKED = "BLOCKED"
    static final String OUTCOME_FAILED = "FAILED"
    static final String OUTCOME_NO_PREVIEW = "NO_PREVIEW"

    /** True when this migration has at least one SUCCESS ledger row. DRY_RUN and FAILED do not count. */
    static boolean hasSucceeded(def ec, String migrationId) {
        return ec.entity.find(LEDGER_ENTITY)
                .condition("migrationId", migrationId)
                .condition("statusId", MigrationLedgerSupport.STATUS_SUCCESS)
                .disableAuthz()
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
        return ec.entity.find(PREREQ_ENTITY)
                .condition("migrationId", migrationId)
                .orderBy("prereqMigrationId")
                .disableAuthz()
                .list()
                .collect { it.getString("prereqMigrationId") }
                .findAll { !hasSucceeded(ec, it) }
    }

    static final String STATUS_APPLIED = "APPLIED"
    static final String STATUS_PENDING = "PENDING"

    /**
     * Registry, ledger and prerequisite state joined into one row per migration, for the admin
     * screen. Kept in Groovy rather than screen XML so it is testable.
     */
    static List<Map<String, Object>> statusList(def ec) {
        return ec.entity.find(REGISTRY_ENTITY)
                .orderBy("sequenceNum")
                .disableAuthz()
                .list()
                .collect { row ->
                    String migrationId = row.getString("migrationId")

                    def attempts = ec.entity.find(LEDGER_ENTITY)
                            .condition("migrationId", migrationId)
                            .orderBy("-startedDate")
                            .disableAuthz()
                            .list()
                    def latest = attempts ? attempts.first() : null

                    String status
                    if (hasSucceeded(ec, migrationId)) status = STATUS_APPLIED
                    else if (unmetPrereqs(ec, migrationId)) status = OUTCOME_BLOCKED
                    else status = STATUS_PENDING

                    return [migrationId   : migrationId,
                            description   : row.getString("description"),
                            sequenceNum   : row.get("sequenceNum"),
                            supportsDryRun: row.getString("supportsDryRun") == "Y",
                            status        : status,
                            lastRunDate   : latest?.get("completedDate"),
                            lastStatusId  : latest?.getString("statusId"),
                            rowsAffected  : latest?.get("rowsAffected"),
                            attemptCount  : attempts.size()]
                }
    }

    static List<Map<String, Object>> runPending(def ec, boolean dryRun) {
        List<Map<String, Object>> results = []

        def registryRows = ec.entity.find(REGISTRY_ENTITY)
                .condition("enabled", "Y")
                .orderBy("sequenceNum")
                .disableAuthz()
                .list()

        registryRows.each { row ->
            String migrationId = row.getString("migrationId")

            if (hasSucceeded(ec, migrationId)) {
                results << [migrationId: migrationId, outcome: OUTCOME_ALREADY_APPLIED]
                return
            }

            List<String> unmet = unmetPrereqs(ec, migrationId)
            if (unmet) {
                String detail = "blocked: prerequisite(s) not applied — ${unmet.join(', ')}"
                String blockedRunId = ec.service.sync()
                        .name(RECORD_SERVICE)
                        .parameters([migrationId  : migrationId,
                                     statusId     : MigrationLedgerSupport.STATUS_BLOCKED,
                                     messageDetail: detail])
                        .disableAuthz()
                        .call()
                        ?.runId as String
                results << [migrationId: migrationId, outcome: OUTCOME_BLOCKED,
                            runId      : blockedRunId, detail: detail]
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
