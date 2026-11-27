package darpan.ops

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class ErrorDigestAssemblerTests {
    static final Instant NOW_I = Instant.parse("2026-11-25T12:00:00Z")
    static final Timestamp NOW = Timestamp.from(NOW_I)
    static final Timestamp SINCE = Timestamp.from(NOW_I.minusSeconds(24 * 3600))

    private static Timestamp hoursAgo(int h) { Timestamp.from(NOW_I.minusSeconds(h * 3600L)) }

    private static Map input(Map over = [:]) {
        return [instance: "prod", since: SINCE, now: NOW, executions: [], checks: [], logRows: [],
                watchdogHours: 30, emptyStreakDays: 7] + over
    }

    private static Map check(Map over = [:]) {
        return [tenantId: "T1", auditName: "Month close", statementId: "S1", checkId: "C1", auditScheduleActive: true,
                statementActive: true, automationId: "A1", automationActive: true, lastSuccessDate: hoursAgo(2),
                recentScheduledReads: []] + over
    }

    private static Map failed(Map over = [:]) {
        return [statusEnumId: "AUT_STAT_FAILED", automationId: "A1", companyUserGroupId: "T1", triggerType: "scheduled",
                scheduledDate: hoursAgo(3), completedDate: hoursAgo(3), errorMessage: "boom"] + over
    }

    @Test
    void noErrorsSaysNone() {
        Map d = ErrorDigestAssembler.assemble(input())
        assertEquals("[prod] Darpan errors 2026-11-25: none", d.subject)
    }

    @Test
    void subjectCountsChecksAndLogErrors() {
        Map d = ErrorDigestAssembler.assemble(input(
                checks: [check(), check(checkId: "C2", automationId: "A2", lastSuccessDate: hoursAgo(40)),
                         check(checkId: "C3", automationId: "A3", lastSuccessDate: hoursAgo(40)),
                         check(checkId: "C4", automationId: null)],
                executions: [failed()],
                logRows: [[fingerprint: "f1", count: 30, loggerName: "x"], [fingerprint: "OVERFLOW", count: 7]]))
        assertEquals("[prod] Darpan errors 2026-11-25: 4 checks, 37 log errors", d.subject)
        assertEquals(37, d.counts.logErrors)
        assertEquals(4, d.counts.checks)
        assertEquals(30, d.logErrors[0].count)
    }

    // DAR-BE-396: the 2026-11-27 email's 140 "[Login]" lines were the DAR-BE-264 fence turning scanners away on purpose.
    @Test
    void logLinesMatchingAnIgnorePatternAreLeftOutAndCounted() {
        String fence = "Error running groovy script (org.moqui.impl.webapp.ScreenResourceNotFoundException: Could not find subscreen or transition or file/content [Login] under screen [null]"
        Map d = ErrorDigestAssembler.assemble(input(
                ignorePatterns: ["ScreenResourceNotFoundException: Could not find subscreen or transition or file/content \\[(Login|SecondFactor|ChangePassword)\\]",
                                 "^org\\.moqui\\.impl\\.webapp\\.ElasticRequestLogFilter "],
                logRows: [[fingerprint: "org.moqui.impl.actions.XmlAction ~91b2e17b", loggerName: "org.moqui.impl.actions.XmlAction", sampleMessage: fence, count: 91],
                          [fingerprint: "org.moqui.impl.webapp.ElasticRequestLogFilter In ElasticRequestLogFilter init", loggerName: "org.moqui.impl.webapp.ElasticRequestLogFilter",
                           sampleMessage: "In ElasticRequestLogFilter init could not find ElasticClient", count: 9],
                          [fingerprint: "org.moqui.impl.context.MessageFacadeImpl Sync pair * was not found.", loggerName: "org.moqui.impl.context.MessageFacadeImpl",
                           sampleMessage: "Sync pair '100102' was not found.", count: 652],
                          [fingerprint: "OVERFLOW", count: 3]]))
        assertEquals(["org.moqui.impl.context.MessageFacadeImpl Sync pair * was not found.", "OVERFLOW"], d.logErrors*.fingerprint)
        assertEquals(655, d.counts.logErrors)
        assertEquals(100, d.counts.ignoredLogErrors)
        assertEquals("[prod] Darpan errors 2026-11-25: 0 checks, 655 log errors", d.subject)
    }

    @Test
    void aBrokenIgnorePatternIsSkippedNotFatal() {
        Map d = ErrorDigestAssembler.assemble(input(ignorePatterns: ["([unclosed", "Elastic"],
                logRows: [[fingerprint: "f1", loggerName: "x", sampleMessage: "Elastic down", count: 2],
                          [fingerprint: "f2", loggerName: "x", sampleMessage: "([unclosed", count: 5]]))
        assertEquals(["f2"], d.logErrors*.fingerprint)
        assertEquals(2, d.counts.ignoredLogErrors)
    }

    @Test
    void everyLineIgnoredSaysNone() {
        Map d = ErrorDigestAssembler.assemble(input(ignorePatterns: ["Elastic"],
                logRows: [[fingerprint: "f1", loggerName: "x", sampleMessage: "Elastic down", count: 2]]))
        assertEquals("[prod] Darpan errors 2026-11-25: none", d.subject)
        assertEquals(2, d.counts.ignoredLogErrors)
    }

    @Test
    void aCheckWithNoSuccessFor31HoursIsStale() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(lastSuccessDate: hoursAgo(31))]))
        assertEquals(["STALE"], d.watchdog*.kind)
        assertEquals("C1", d.watchdog[0].check)
    }

    @Test
    void thirtyHoursIsNotStale() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(lastSuccessDate: hoursAgo(30))]))
        assertTrue(d.watchdog.isEmpty())
    }

    @Test
    void aQuietDayCountsAsSuccessForStale() {
        Map quiet = [statusEnumId: "AUT_STAT_NO_DATA", automationId: "A1", companyUserGroupId: "T1", triggerType: "scheduled",
                     scheduledDate: hoursAgo(5), completedDate: hoursAgo(5), file1ReadCount: 0, file2ReadCount: 0]
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(lastSuccessDate: hoursAgo(50))], executions: [quiet]))
        assertTrue(d.watchdog.isEmpty())
        assertTrue(d.runErrors.isEmpty())
    }

    @Test
    void anActiveStatementWithNoAutomationIsNoAutomation() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(automationId: null)]))
        assertEquals(["NO_AUTOMATION"], d.watchdog*.kind)
    }

    @Test
    void sevenScheduledEmptyRunsIsAnEmptyStreak() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(recentScheduledReads: (1..7).collect { [0, 0] })]))
        assertEquals(["EMPTY_STREAK"], d.watchdog*.kind)
    }

    @Test
    void sixIsNot() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [check(recentScheduledReads: (1..6).collect { [0, 0] })]))
        assertTrue(d.watchdog.isEmpty())
        Map e = ErrorDigestAssembler.assemble(input(checks: [check(recentScheduledReads: (1..6).collect { [0, 0] } + [[3, 0]])]))
        assertTrue(e.watchdog.isEmpty())
    }

    @Test
    void aPausedAuditIsNotWatched() {
        Map d = ErrorDigestAssembler.assemble(input(checks: [
                check(auditScheduleActive: false, lastSuccessDate: hoursAgo(99)),
                check(checkId: "C2", automationId: null, auditScheduleActive: false)]))
        assertTrue(d.watchdog.isEmpty())
    }

    @Test
    void onlyExecutionsEndedSinceTheLastDigestAreListed() {
        Map d = ErrorDigestAssembler.assemble(input(
                checks: [check()],
                executions: [failed(completedDate: hoursAgo(30), scheduledDate: hoursAgo(30)),
                             failed(completedDate: hoursAgo(1), scheduledDate: hoursAgo(1), errorMessage: "recent")]))
        assertEquals(1, d.runErrors.size())
        assertEquals("recent", d.runErrors[0].errorText)
        assertEquals("FAILED", d.runErrors[0].kind)
        assertEquals("C1", d.runErrors[0].check)
    }

    @Test
    void daysInARowCountsConsecutiveScheduledDaysAndManualHasNone() {
        Map d = ErrorDigestAssembler.assemble(input(executions: [
                failed(scheduledDate: hoursAgo(1), completedDate: hoursAgo(1)),
                failed(scheduledDate: hoursAgo(25), completedDate: hoursAgo(25)),
                failed(scheduledDate: hoursAgo(49), completedDate: hoursAgo(49)),
                failed(scheduledDate: hoursAgo(121), completedDate: hoursAgo(121)),
                failed(triggerType: "manual", scheduledDate: hoursAgo(2), completedDate: hoursAgo(2))]))
        assertEquals(2, d.runErrors.size())
        Map sched = d.runErrors.find { it.triggerType == "scheduled" }
        Map manual = d.runErrors.find { it.triggerType == "manual" }
        assertEquals(3, sched.daysInARow)
        assertEquals(null, manual.daysInARow)
    }

    @Test
    void noDataErrorsCarryReasonCountsAndFiles() {
        Map d = ErrorDigestAssembler.assemble(input(executions: [[
                statusEnumId: "AUT_STAT_NO_DATA", noDataKind: "KEPT_NOTHING", automationId: "A1", companyUserGroupId: "T1",
                scheduledDate: hoursAgo(2), completedDate: hoursAgo(2), file1ReadCount: 10, file2ReadCount: 29963,
                file1RecordCount: 10, file2RecordCount: 0, file1Name: "a.jsonl", file2Name: "b.jsonl",
                errorMessage: "The NetSuite side kept 0 of 29,963 records.", reconciliationRunResultId: "R9"]]))
        Map e = d.runErrors[0]
        assertEquals("KEPT_NOTHING", e.kind)
        assertEquals("The NetSuite side kept 0 of 29,963 records.", e.errorText)
        assertEquals([10, 29963], [e.readCounts.file1, e.readCounts.file2])
        assertEquals([10, 0], [e.keptCounts.file1, e.keptCounts.file2])
        assertEquals(["a.jsonl", "b.jsonl"], e.files)
    }
}
