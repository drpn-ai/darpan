package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-320 phase 1b. Where a play read more than it needed, found by code: each flag at its threshold's edge, and the
 * reads that must never be flagged (an unknown connection, a play with no history, the first play of a period).
 */
class AuditReadReportTests {
    static final Map LIMITS = [wholePeriodGraceDays: 4, lookupEveryPlayRuns: 3, throttledMax: 0, callsGrewPercent: 50, callsGrewMinCalls: 50]

    /** One read as AuditReadReport.readRow makes it; a Shopify store read 4 days back on the 16th of a played period. */
    static Map read(Map over = [:]) {
        return [checkId       : "C1", checkCode: "R2", period: "2026-11", reportDay: "2026-11-16", periodFirstDay: "2026-11-01",
                playedBefore  : true, endpointEnumId: "SHOPIFY", systemEnumId: "SHOPIFY", sourceConfigId: "STORE_US",
                statusEnumId  : "AUDSR_COMPLETE", recordCount: 800, windowFrom: "2026-11-12T08:00:00Z", windowThru: "2026-11-16T08:00:00Z",
                windowFromDay : "2026-11-12", calls: [graphql: 10L, costPoints: 40L, bulkStarts: 1L], lookup: null] + over
    }

    static List<String> flags(Map report) { return (report.flags as List<Map>)*.flag }

    @Test
    void oneReadPerConnectionAndRecordTypeIsClean() {
        Map report = AuditReadReport.build([read(), read(checkId: "C2", checkCode: "R6", endpointEnumId: "SHOPIFY_RETURN_REFS")], [], LIMITS)
        assertEquals([], flags(report))
        assertEquals([reads: 2, connections: 2, records: 1600L, calls: [graphql: 20L, costPoints: 80L, bulkStarts: 2L]], report.totals)
        assertEquals([reads: 2, connections: 2, calls: 20L, flags: 0], AuditReadReport.summary(report))
    }

    @Test
    void theSameStoresOrdersReadByThreeChecksIsOneDuplicateNamingThemAll() {
        Map report = AuditReadReport.build([read(), read(checkId: "C3", checkCode: "R3"), read(checkId: "C5", checkCode: "R5")], [], LIMITS)
        assertEquals(["DUPLICATE_READ"], flags(report))
        Map f = (report.flags as List<Map>)[0]
        assertEquals([["R2", "R3", "R5"], [reads: 3]], [f.checks, f.detail])
        assertEquals("SHOPIFY on STORE_US was read 3 times this morning (R2, R3, R5); one read should serve them all.", f.text)
        Map group = (report.groups as List<Map>)[0]
        assertEquals([3, 2400L, 30L], [group.reads, group.records, (group.calls as Map).graphql])
    }

    @Test
    void readsWithNoKnownConnectionAreCountedButNeverCalledDuplicates() {
        Map report = AuditReadReport.build([read(sourceConfigId: null), read(checkId: "C3", checkCode: "R3", sourceConfigId: null)], [], LIMITS)
        assertEquals([], flags(report))
        assertEquals([2, 0], [report.totals.reads, report.totals.connections])
    }

    @Test
    void aReadFromThePeriodsFirstDayIsFlaggedOnlyPastTheGraceInAPlayedPeriod() {
        Map whole = read(windowFromDay: "2026-11-01")
        assertEquals(["WHOLE_PERIOD_READ"], flags(AuditReadReport.build([whole], [], LIMITS)))
        assertEquals(["WHOLE_PERIOD_READ"], flags(AuditReadReport.build([whole + [windowFromDay: "2026-10-29"]], [], LIMITS)),
                "the 3-day slack before the 1st is still the whole period")
        assertEquals([], flags(AuditReadReport.build([whole + [reportDay: "2026-11-05"]], [], LIMITS)), "4 days in: at the grace, not past it")
        assertEquals(["WHOLE_PERIOD_READ"], flags(AuditReadReport.build([whole + [reportDay: "2026-11-06"]], [], LIMITS)))
        assertEquals([], flags(AuditReadReport.build([whole + [playedBefore: false]], [], LIMITS)), "the period's first play reads it all")
        assertEquals([], flags(AuditReadReport.build([whole + [windowFromDay: null]], [], LIMITS)), "a one-sided check reads all, with no window")
    }

    @Test
    void aVerifyTheShareGateSkippedIsFlaggedWithItsNumbers() {
        Map report = AuditReadReport.build([read(lookup: [candidates: 412, checkedAgainst: 900, maxSharePercent: 5, skipped: "OVER_SHARE"])], [], LIMITS)
        assertEquals(["LOOKUP_OVER_SHARE"], flags(report))
        assertEquals("R2: 412 of 900 records were missing on SHOPIFY on STORE_US, above the 5% a lookup is for; the read or the pairing is wrong.",
                (report.flags as List<Map>)[0].text)
        assertEquals([], flags(AuditReadReport.build([read(lookup: [candidates: 2, skipped: "NO_COUNT"])], [], LIMITS)),
                "no count is wiring, not the read")
    }

    @Test
    void aLookupOnEveryOneOfTheLastPlaysIsFlaggedButNotWithAGapOrTooLittleHistory() {
        Map looked = read(lookup: [candidates: 4, checkedAgainst: 800, lookedUp: 4, found: 1])
        List<Map> earlier = [read(lookup: [lookedUp: 2])]
        assertEquals(["LOOKUP_EVERY_PLAY"], flags(AuditReadReport.build([looked], [earlier, earlier], LIMITS)))
        assertEquals([], flags(AuditReadReport.build([looked], [earlier], LIMITS)), "two plays of history needed for three in a row")
        assertEquals([], flags(AuditReadReport.build([looked], [earlier, [read()]], LIMITS)), "a play with no lookup breaks the run")
        assertEquals([], flags(AuditReadReport.build([looked], [earlier, [read(checkId: "C9", lookup: [lookedUp: 3])]], LIMITS)),
                "another check's lookup is not this one's")
    }

    @Test
    void throttlingAndGrowingCallsAreFlaggedPastTheirSettings() {
        assertEquals(["THROTTLED"], flags(AuditReadReport.build([read(calls: [graphql: 10L, throttled: 1L])], [], LIMITS)))
        assertEquals([], flags(AuditReadReport.build([read(calls: [graphql: 10L, throttled: 1L])], [], LIMITS + [throttledMax: 1])))

        List<Map> before = [read(calls: [graphql: 100L])]
        assertEquals(["CALLS_GREW"], flags(AuditReadReport.build([read(calls: [graphql: 151L])], [before], LIMITS)))
        assertEquals([], flags(AuditReadReport.build([read(calls: [graphql: 150L])], [before], LIMITS)), "50% up is at the setting, not past it")
        assertEquals([], flags(AuditReadReport.build([read(calls: [graphql: 40L])], [[read(calls: [graphql: 20L])]], LIMITS)),
                "doubling from 20 to 40 is under the calls floor")
        assertEquals([], flags(AuditReadReport.build([read(calls: [graphql: 400L])], [], LIMITS)), "no play before: nothing to compare")
    }

    @Test
    void aCutOrBrokenTallyReadsAsUnknownNotZero() {
        Map row = AuditReadReport.readRow([endpointEnumId: "SHOPIFY", systemEnumId: "SHOPIFY", callsJson: '{"graphql":14,"bulkSt',
                                           lookupJson: '["not","a","map"]'], [checkId: "C1", period: "2026-11"], [C1: [checkCode: "R2"]],
                java.time.ZoneId.of("UTC"), [] as Set)
        assertNull(row.calls)
        assertNull(row.lookup)
        assertEquals("R2", row.checkCode)
        assertNull(AuditReadReport.summary(AuditReadReport.build([], [], LIMITS)), "nothing read: no line")
    }

    @Test
    void theShippedThresholdsAreTheSettingsDefaults() {
        assertEquals(LIMITS, AuditReadReport.thresholds())
        assertTrue(AuditReadReport.FLAGS.containsAll(["DUPLICATE_READ", "WHOLE_PERIOD_READ", "LOOKUP_OVER_SHARE", "LOOKUP_EVERY_PLAY", "THROTTLED", "CALLS_GREW"]))
    }
}
