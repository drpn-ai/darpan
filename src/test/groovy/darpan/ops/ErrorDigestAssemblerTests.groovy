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
    private static Map logRow(String logger, String message, int n) {
        return [fingerprint: ErrorLogCapture.fingerprint(logger, message), loggerName: logger, sampleMessage: message, count: n]
    }

    static final String FENCE = "^org\\.moqui\\.impl\\.actions\\.XmlAction Error running groovy script \\(org\\.moqui\\.impl\\.webapp\\.ScreenResourceNotFoundException: Could not find subscreen or transition or file/content \\[(Login|SecondFactor|ChangePassword)\\] under screen \\[null\\]"
    static final String SIGNED_OUT = "^org\\.moqui\\.impl\\.service\\.ServiceJsonRpcDispatcher Error calling service [\\w.#]+ from JSON-RPC request: org\\.moqui\\.context\\.AuthenticationRequiredException: User must be logged in to call service "

    @Test
    void logLinesMatchingAnIgnorePatternAreLeftOutAndCounted() {
        Map d = ErrorDigestAssembler.assemble(input(
                ignorePatterns: [FENCE, "^org\\.moqui\\.impl\\.webapp\\.ElasticRequestLogFilter In ElasticRequestLogFilter init could not find ElasticClient with name logger or default, not starting\$"],
                logRows: [logRow("org.moqui.impl.actions.XmlAction", "Error running groovy script (org.moqui.impl.webapp.ScreenResourceNotFoundException: Could not find subscreen or transition or file/content [Login] under screen [null] while finding url for path [Login] under from screen [null]): \n1 : import static org.moq", 91),
                          logRow("org.moqui.impl.webapp.ElasticRequestLogFilter", "In ElasticRequestLogFilter init could not find ElasticClient with name logger or default, not starting", 9),
                          logRow("org.moqui.impl.context.MessageFacadeImpl", "Sync pair '100102' was not found.", 652),
                          [fingerprint: "OVERFLOW", count: 3]]))
        assertEquals(["org.moqui.impl.context.MessageFacadeImpl Sync pair * was not found.", "OVERFLOW"], d.logErrors*.fingerprint)
        assertEquals(655, d.counts.logErrors)
        assertEquals(100, d.counts.ignoredLogErrors)
        assertEquals([91, 9], d.ignoredLogErrors.collect { it.count as int }, "each left-out fingerprint is named with its count, biggest first")
        assertTrue((d.ignoredLogErrors[0].fingerprint as String).contains("[Login]"))
        assertEquals("[prod] Darpan errors 2026-11-25: 0 checks, 655 log errors", d.subject)
    }

    // Security review of 4a9fc9ae: a message can echo text a caller chose, so a pattern is tried against the fingerprint
    // only (logger first, quoted values, digits and hex masked) and the shipped ones are anchored to their logger and shape.
    @Test
    void wordsEchoedInsideAnotherErrorNeverLeaveItOut() {
        Map d = ErrorDigestAssembler.assemble(input(ignorePatterns: [FENCE, SIGNED_OUT],
                logRows: [logRow("org.moqui.impl.context.MessageFacadeImpl", "Validation error (WrongType@[orders]) : argument sortKey with value User must be logged in to call service x is not a valid OrderSortKey", 4),
                          logRow("darpan.reconciliation.Something", "Error running groovy script (org.moqui.impl.webapp.ScreenResourceNotFoundException: Could not find subscreen or transition or file/content [Login] under screen [null]", 2),
                          [fingerprint: "darpan.x Boom #", loggerName: "darpan.x", sampleMessage: "Error calling service a.b#C from JSON-RPC request: org.moqui.context.AuthenticationRequiredException: User must be logged in to call service ", count: 1]]))
        assertEquals(7, d.counts.logErrors)
        assertEquals(0, d.counts.ignoredLogErrors)
    }

    @Test
    void aBrokenIgnorePatternIsSkippedNotFatal() {
        Map d = ErrorDigestAssembler.assemble(input(ignorePatterns: ["([unclosed", "^x Elastic"],
                logRows: [logRow("x", "Elastic down", 2), logRow("x", "([unclosed", 5)]))
        assertEquals(["x ([unclosed"], d.logErrors*.fingerprint)
        assertEquals(2, d.counts.ignoredLogErrors)
    }

    @Test
    void everyLineIgnoredSaysNone() {
        Map d = ErrorDigestAssembler.assemble(input(ignorePatterns: ["^x Elastic"], logRows: [logRow("x", "Elastic down", 2)]))
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
