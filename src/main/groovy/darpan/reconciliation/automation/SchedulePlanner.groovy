package darpan.reconciliation.automation

import darpan.schedule.JobScheduleRules
import groovy.transform.CompileDynamic

import java.sql.Timestamp
import java.time.Instant

/**
 * Projects an automation's schedule forward across a window, for the admin schedule board.
 *
 * Every step delegates to {@link AutomationExecutionSupport#resolveNextScheduledFireTime} — the
 * same resolver the scanner advances with — so a projected fire time and the time the scheduler
 * actually fires cannot drift apart.
 */
@CompileDynamic
class SchedulePlanner {

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
     * The moment the scanner would actually SUBMIT a fire due at {@code fireTime}: the first run of the scan job's cron at or
     * after it.
     *
     * scan#DueAutomations is the ServiceJob scan_ReconciliationAutomations_5m, and nothing is submitted between its runs, so an
     * automation due at 15:02 on a "0 0/5 * * * ?" scan waits for 15:05. The cron is production data (DAR-BE-305), so the caller
     * passes the live one. The board shows this rather than the raw due time, so a deployment window is judged against when
     * work really starts. Null when there is no fire, or no scan cron (the scan job is paused: nothing is submitted).
     */
    static Timestamp submitsAt(Timestamp fireTime, String scanCron) {
        if (fireTime == null || !scanCron?.trim()) return null
        Instant run = JobScheduleRules.firstRunAtOrAfter(scanCron, fireTime.toInstant())
        return run == null ? null : Timestamp.from(run)
    }

    private static Timestamp seedFireTime(Map automation, Timestamp from) {
        Timestamp stored = automation.get("nextScheduledFireTime") as Timestamp
        if (stored != null && !stored.before(from)) return stored
        // the resolver answers strictly after its base: start a millisecond early so a fire exactly at `from` is kept
        return AutomationExecutionSupport.resolveNextScheduledFireTime(automation, stored, new Timestamp(from.time - 1))
    }
}
