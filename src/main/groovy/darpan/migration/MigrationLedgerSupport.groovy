package darpan.migration

import java.sql.Timestamp

/**
 * Writes exactly one {@code DarpanMigrationRun} row.
 *
 * <p>This class does one thing on purpose. It is the only code permitted to run inside the
 * forced-new transaction declared by {@code migration.MigrationServices.record#MigrationRun}, and
 * keeping that boundary at a file boundary makes it harder to widen by accident. Anything else the
 * supervisor needs belongs in {@link MigrationSupervisorSupport}, which runs in the ambient
 * transaction.</p>
 *
 * <p>Why forced-new at all: Moqui marks the active transaction rollback-only when a service reports
 * errors. A ledger write that joined that transaction would be rolled back together with the
 * migration whose failure it exists to record, leaving a ledger that contains only successes — so a
 * migration failing on every attempt would look identical to one never attempted.</p>
 */
class MigrationLedgerSupport {
    static final String ENTITY_NAME = "darpan.migration.DarpanMigrationRun"

    static final String STATUS_SUCCESS = "SUCCESS"
    static final String STATUS_FAILED = "FAILED"
    static final String STATUS_BLOCKED = "BLOCKED"
    static final String STATUS_DRY_RUN = "DRY_RUN"

    /** Longest message we will persist. Detail beyond this is truncated, never dropped silently. */
    static final int MAX_MESSAGE_LENGTH = 8000

    /**
     * Writes one ledger row and returns its generated {@code runId}.
     *
     * @param fields migrationId (required), statusId (required), startedDate, rowsAffected,
     *               messageDetail
     */
    static String write(def ec, Map<String, Object> fields) {
        String migrationId = fields.get("migrationId")?.toString()?.trim()
        String statusId = fields.get("statusId")?.toString()?.trim()
        if (!migrationId || !statusId) {
            ec.message.addError("record#MigrationRun requires migrationId and statusId")
            return null
        }

        Timestamp now = ec.user.nowTimestamp
        Object startedRaw = fields.get("startedDate")
        Timestamp started = (startedRaw instanceof Timestamp) ? (Timestamp) startedRaw : now

        String runId = ec.entity.sequencedIdPrimary("DarpanMigrationRun", null, null)

        ec.entity.makeValue(ENTITY_NAME)
                .setAll([migrationId  : migrationId,
                         runId        : runId,
                         startedDate  : started,
                         completedDate: now,
                         appliedByUserId: ec.user.userId,
                         statusId     : statusId,
                         rowsAffected : asInteger(fields.get("rowsAffected")),
                         messageDetail: truncate(fields.get("messageDetail"))])
                .create()

        return runId
    }

    private static Integer asInteger(Object raw) {
        if (raw == null) return null
        if (raw instanceof Number) return ((Number) raw).intValue()
        String text = raw.toString().trim()
        return text.isInteger() ? text.toInteger() : null
    }

    /**
     * Truncation is explicit rather than relying on the column width, so an over-long message
     * produces a readable row instead of a database error mid-migration.
     */
    private static String truncate(Object raw) {
        String text = raw?.toString()
        if (!text) return null
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + " [truncated]"
    }
}
