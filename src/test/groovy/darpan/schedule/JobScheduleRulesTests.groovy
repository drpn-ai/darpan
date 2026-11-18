package darpan.schedule

import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-305: the cron and preview rules, pure. */
class JobScheduleRulesTests {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z")

    @Test
    void aDailyCronOnADailyFloorIsFine() {
        assertNull(JobScheduleRules.cronProblem("0 0 2 * * ?", 360, NOW))
        assertNull(JobScheduleRules.cronProblem("0 0/5 * * * ?", 1, NOW))
        assertNull(JobScheduleRules.cronProblem("0 0/5 * * * ?", null, NOW))
    }

    @Test
    void aBurstInsideOneHourIsRefusedEvenThoughItParses() {
        String p = JobScheduleRules.cronProblem("0 * 3 * * ?", 360, NOW)
        assertNotNull(p)
        assertTrue(p.contains("Runs every 1 minute at its tightest"), p)
        assertTrue(p.contains("at most every 360 minutes"), p)
        assertNotNull(JobScheduleRules.cronProblem("0 0 2,3 * * ?", 360, NOW), "two runs an hour apart")
        assertNotNull(JobScheduleRules.cronProblem("0/30 * * * * ?", 1, NOW), "every 30 seconds is under one minute")
    }

    @Test
    void aCronThatNeverFiresIsRefused() {
        String p = JobScheduleRules.cronProblem("0 0 0 30 2 ?", 1, NOW)
        assertNotNull(p)
        assertTrue(p.contains("never runs"), p)
    }

    @Test
    void junkIsRefusedWithAReason() {
        assertTrue(JobScheduleRules.cronProblem("* * * * *", 1, NOW).contains("Not a valid Quartz cron"))
        assertTrue(JobScheduleRules.cronProblem("nonsense", 1, NOW).contains("Not a valid Quartz cron"))
        assertTrue(JobScheduleRules.cronProblem("  ", 1, NOW).contains("blank"))
        assertTrue(JobScheduleRules.cronProblem(null, 1, NOW).contains("blank"))
    }

    @Test
    void aOneHundredTwentyOneCharCronIsRefused() {
        String cron = ("0 0 2 * * ? " + ("*" * 109))
        assertEquals(121, cron.length())
        assertTrue(JobScheduleRules.cronProblem(cron, 1, NOW).contains("121 characters"))
    }

    @Test
    void nextRunsAndGaps() {
        List<Instant> runs = JobScheduleRules.nextRuns("0 0 2 * * ?", NOW, 3)
        // 02:00 in the zone the job runner uses (the JVM default), not UTC
        java.time.ZonedDateTime first = java.time.ZonedDateTime.ofInstant(NOW, JobScheduleRules.zone()).plusDays(1).withHour(2).withMinute(0).withSecond(0).withNano(0)
        if (!first.toInstant().isAfter(NOW)) first = first.plusDays(1)
        assertEquals([first, first.plusDays(1), first.plusDays(2)]*.toInstant(), runs)
        assertEquals(first.toInstant(), JobScheduleRules.firstRunAtOrAfter("0 0 2 * * ?", first.toInstant()))
        assertEquals(first.plusDays(1).toInstant(), JobScheduleRules.firstRunAtOrAfter("0 0 2 * * ?", first.toInstant().plusMillis(1)))
        assertEquals(1440, JobScheduleRules.smallestGapMinutes("0 0 2 * * ?", NOW))
        assertEquals(5, JobScheduleRules.smallestGapMinutes("0 0/5 * * * ?", NOW))
        assertEquals([], JobScheduleRules.nextRuns("garbage", NOW, 3))
        assertNull(JobScheduleRules.smallestGapMinutes("garbage", NOW))
    }

    @Test
    void thePreviewToleranceIsTheLargerOfOneHundredRowsAndFivePercent() {
        assertNotNull(JobScheduleRules.previewProblem(null, 0))
        assertNull(JobScheduleRules.previewProblem(1000, 1050))
        assertNull(JobScheduleRules.previewProblem(1000, 1100))
        assertNotNull(JobScheduleRules.previewProblem(1000, 1200))
        assertNull(JobScheduleRules.previewProblem(0, 100))
        assertNotNull(JobScheduleRules.previewProblem(0, 101))
        assertNull(JobScheduleRules.previewProblem(100000, 104999))
        assertNotNull(JobScheduleRules.previewProblem(100000, 105001))
        assertNull(JobScheduleRules.previewProblem(1000, 10), "fewer rows than shown deletes less than approved")
        assertEquals("The numbers changed since your preview; preview again.", JobScheduleRules.previewProblem(0, 5000))
    }
}
