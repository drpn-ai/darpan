package darpan.reconciliation.automation

import groovy.transform.CompileDynamic

import java.sql.Timestamp

/**
 * Projects an automation's schedule forward across a window, for the admin schedule board.
 *
 * Every step delegates to {@link AutomationExecutionSupport#resolveNextScheduledFireTime} — the
 * same resolver the scanner advances with — so a projected fire time and the time the scheduler
 * actually fires cannot drift apart.
 */
@CompileDynamic
class SchedulePlanner {

    /** Period of the scan_ReconciliationAutomations_5m ServiceJob cron, "0 0/5 * * * ?". */
    static final int SCAN_TICK_SECONDS = 300

    /**
     * Fire times at or before {@code to}, starting from the first fire at or after {@code from}.
     *
     * Only the FIRST entry is authoritative: it is the automation's stored nextScheduledFireTime,
     * which the scanner owns. Everything after it is this class's projection and assumes the
     * automation is neither paused, edited, nor deferred by the concurrency cap in the meantime.
     */
    static List<Timestamp> projectFireTimes(Map automation, Timestamp from, Timestamp to, int limit) {
        List<Timestamp> fires = []
        Timestamp cursor = seedFireTime(automation, from)
        // `limit` is what bounds this loop, not the fire times: the resolver always returns a time
        // strictly after its base (or null for an unparseable/zero-length expression), so a
        // malformed schedule ends the loop through `cursor != null` rather than spinning.
        while (cursor != null && !cursor.after(to) && fires.size() < limit) {
            fires << cursor
            cursor = AutomationExecutionSupport.resolveNextScheduledFireTime(automation, cursor, cursor)
        }
        return fires
    }

    /**
     * The moment the scanner would actually SUBMIT a fire due at {@code fireTime}.
     *
     * scan#DueAutomations is a ServiceJob on "0 0/5 * * * ?" (data/ReconciliationJobSeedData.xml),
     * so nothing is submitted between ticks: an automation due at 15:02 waits for 15:05. The board
     * shows this rather than the raw due time, so a deployment window is judged against when work
     * really starts.
     */
    static Timestamp submitsAt(Timestamp fireTime) {
        if (fireTime == null) return null
        long tickMillis = SCAN_TICK_SECONDS * 1000L
        long remainder = fireTime.time % tickMillis
        return remainder == 0 ? fireTime : new Timestamp(fireTime.time + (tickMillis - remainder))
    }

    private static Timestamp seedFireTime(Map automation, Timestamp from) {
        Timestamp stored = automation.get("nextScheduledFireTime") as Timestamp
        if (stored != null && !stored.before(from)) return stored
        return AutomationExecutionSupport.resolveNextScheduledFireTime(automation, stored, from)
    }
}
