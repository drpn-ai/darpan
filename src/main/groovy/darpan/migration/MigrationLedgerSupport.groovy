package darpan.migration

/**
 * Ledger constants and the one piece of {@code record#MigrationRun} that is string work.
 *
 * <p>The row itself is written by the XML actions of
 * {@code migration.MigrationServices.record#MigrationRun}, which owns the forced-new transaction.
 * Nothing else the supervisor needs belongs there; that lives in {@link MigrationSupervisorSupport},
 * which runs in the ambient transaction.</p>
 *
 * <p>Why forced-new at all: Moqui marks the active transaction rollback-only when a service reports
 * errors. A ledger write that joined that transaction would be rolled back together with the
 * migration whose failure it exists to record, leaving a ledger that contains only successes — so a
 * migration failing on every attempt would look identical to one never attempted.</p>
 */
class MigrationLedgerSupport {
    static final String STATUS_SUCCESS = "SUCCESS"
    static final String STATUS_FAILED = "FAILED"
    static final String STATUS_BLOCKED = "BLOCKED"
    static final String STATUS_DRY_RUN = "DRY_RUN"

    /** Longest message we will persist. Detail beyond this is truncated, never dropped silently. */
    static final int MAX_MESSAGE_LENGTH = 8000

    /**
     * Truncation is explicit rather than relying on the column width, so an over-long message
     * produces a readable row instead of a database error mid-migration. Empty becomes null.
     */
    static String truncate(Object raw) {
        String text = raw?.toString()
        if (!text) return null
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + " [truncated]"
    }
}
