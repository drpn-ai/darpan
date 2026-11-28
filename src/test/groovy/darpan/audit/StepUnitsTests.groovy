package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Date

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S10 (lens E). AuditReportAssembler.stepUnits, the one place the report and the by-order list decide where a
 * finding is listed and counted. Pure: no Moqui.
 *
 * Invariants:
 *  - Conservation: every ledger entry is in exactly one unit, and each unit is counted or waiting, never both.
 *  - Unique (Aditi 2026-11-12, "make sure every issue reported is unique ... or else the numbers will be inflated"): an
 *    order with open findings at several steps is counted open once, at its earliest open step. Every waiting unit hangs
 *    under exactly one counted open unit, and that unit is at the statement it waits on.
 *  - Determinism: the statements' input order does not move a finding to another statement or holder.
 */
class StepUnitsTests {

    private static final String OPEN = "AUDFS_OPEN"
    private static final String FIXED = "AUDFS_FIXED"

    private static Map statement(String id, Long seq) { [statementId: id, statementCode: id, sequenceNum: seq] }

    private static Map entry(String id, String statementId, String state, String day = "2026-10-20") {
        [row: [findingId: id, statementId: statementId, firstFoundDate: Date.valueOf(day)], state: state]
    }

    private static Map item(String itemId) { [OMS: [orderItem: [[id: itemId]]]] }

    /** All the entries' finding ids per bucket: counted units, waiting units, and the units hung under each holder. */
    private static Map<String, List<String>> buckets(Map<String, Map> split) {
        List<String> counted = [], waiting = [], hung = []
        split.values().each { Map s ->
            (s.counted as List<Map>).each { Map u ->
                counted.addAll((u.entries as List<Map>)*.row*.findingId as List<String>)
                ((u.waitingUnits ?: []) as List<Map>).each { hung.addAll((it.entries as List<Map>)*.row*.findingId as List<String>) }
            }
            (s.waiting as List<Map>).each { waiting.addAll((it.entries as List<Map>)*.row*.findingId as List<String>) }
        }
        return [counted: counted, waiting: waiting, hung: hung]
    }

    /** Order M1 is open at step 4 (line 01) and at step 8 (lines 02 open, 03 fixed this morning); M9 is open at step 8 only. */
    private static Map scenario() {
        List<Map> statements = [statement("S4", 4L), statement("S8", 8L)]
        Map<String, List<Map>> entries = [
                S4: [entry("F-1", "S4", OPEN)],
                S8: [entry("F-2", "S8", OPEN), entry("F-3", "S8", FIXED), entry("F-9", "S8", OPEN, "2026-10-21")]]
        Map<String, Map> records = ["F-1": item("M1/01"), "F-2": item("M1/02"), "F-3": item("M1/03"), "F-9": item("M9/01")]
        return [statements: statements, entries: entries, records: records]
    }

    @Test
    void everyEntryLandsInExactlyOneUnitAndEveryWaitingUnitHangsOnce() {
        Map s = scenario()
        Map<String, Map> split = AuditOrderUnits.stepUnits(s.statements as List<Map>, s.entries as Map, s.records as Map)
        Map<String, List<String>> b = buckets(split)
        List<String> all = (s.entries as Map).values().flatten()*.row*.findingId as List<String>
        assertEquals(all.sort(), (b.counted + b.waiting).sort(), "each entry counted or waiting, once")
        assertEquals(b.waiting.sort(), b.hung.sort(), "each waiting entry listed under one holder")
    }

    // A line fixed this morning beside lines that wait: the order was counted open at both steps.
    @Test
    void anOrderWaitingAtALaterStepIsNotCountedOpenThereBecauseOneOfItsLinesWasFixedToday() {
        Map s = scenario()
        Map<String, Map> split = AuditOrderUnits.stepUnits(s.statements as List<Map>, s.entries as Map, s.records as Map)
        List<Map> s8 = split.S8.counted as List<Map>
        assertEquals([["M1", FIXED, ["F-3"]], ["M9", OPEN, ["F-9"]]],
                s8.collect { [it.order, it.state, (it.entries as List<Map>)*.row*.findingId] },
                "at step 8 M1 counts only as the line fixed today; its open line waits")
        assertEquals([["M1", ["F-2"]]], (split.S8.waiting as List<Map>).collect { [it.order, (it.entries as List<Map>)*.row*.findingId] })
        List<String> openOrders = split.values().collectMany { (it.counted as List<Map>).findAll { it.state == OPEN }*.order } as List<String>
        assertEquals(["M1", "M9"], openOrders, "M1 is counted open once, at step 4")
    }

    // The report sorted statements in the database (NULL sequenceNum first); the by-order list and OrderWorlds put it last.
    @Test
    void statementsSortByStepWithNoStepLastThenCodeThenId() {
        List<Map> sorted = [[statementId: "c", statementCode: "R9", sequenceNum: null], [statementId: "b", statementCode: "R8", sequenceNum: 8L],
                            [statementId: "a2", statementCode: "R4", sequenceNum: 4L], [statementId: "a1", statementCode: "R4", sequenceNum: 4L],
                            [statementId: "d", statementCode: "R1", sequenceNum: null]].sort(false, AuditReportAssembler.STATEMENT_ORDER)
        assertEquals(["a1", "a2", "b", "d", "c"], sorted*.statementId)
    }

    @Test
    void aWaitingUnitHangsUnderTheStatementItWaitsOnWhateverTheStatementsInputOrder() {
        Map s = scenario()
        // Statements with no step sort last in the engine but first in a database's ascending order: neither may move a holder.
        List<Map> withUnstepped = [statement("S0", null)] + (s.statements as List<Map>)
        Map entries = (s.entries as Map) + [S0: [entry("F-0", "S0", OPEN), entry("F-0b", "S0", FIXED)]]
        Map records = (s.records as Map) + ["F-0": item("M1/04"), "F-0b": item("M1/05")]
        [withUnstepped, withUnstepped.reverse(), [withUnstepped[1], withUnstepped[0], withUnstepped[2]]].each { List<Map> order ->
            Map<String, Map> split = AuditOrderUnits.stepUnits(order, entries, records)
            Map holder = (split.S4.counted as List<Map>).find { it.order == "M1" }
            assertNotNull(holder, "M1 counts at S4 for ${order*.statementId}")
            assertEquals([["S0", ["F-0"]], ["S8", ["F-2"]]].toSet(),
                    (holder.waitingUnits as List<Map>).collect { [it.statementId, (it.entries as List<Map>)*.row*.findingId] }.toSet(),
                    "for ${order*.statementId}")
            assertTrue(split.values().every { Map st -> (st.counted as List<Map>).every { it.order != "M1" || it.state == FIXED || it.is(holder) } },
                    "no other open M1 unit is counted, for ${order*.statementId}")
        }
    }

    /** The whole split as plain data: per statement, its counted and waiting units and what hangs under each holder. */
    private static Map shape(Map<String, Map> split) {
        Closure ids = { Map u -> (u.entries as List<Map>)*.row*.findingId }
        return split.collectEntries { String st, Map s ->
            [(st): [counted: (s.counted as List<Map>).collect { [it.order, it.state, ids(it),
                        ((it.waitingUnits ?: []) as List<Map>).collect { w -> [w.statementId, ids(w)] }] },
                    waiting: (s.waiting as List<Map>).collect { [it.order, ids(it)] }]]
        }
    }

    // DAR-OPS-029 S10 (QA 14:38): OrderWorlds waits only on a strictly earlier step, so two statements at one step, or
    // both with none, each counted the same order open. Ties go to story order (STATEMENT_ORDER: code, then id).
    @Test
    void statementsAtOneStepCountAnOrderOnceAtTheFirstInStoryOrder() {
        [5L, null].each { Long seq ->
            List<Map> statements = [statement("SB", seq), statement("SA", seq)]
            Map<String, List<Map>> entries = [SA: [entry("F-A", "SA", OPEN)], SB: [entry("F-B", "SB", OPEN)]]
            Map<String, Map> records = ["F-A": item("M2/01"), "F-B": item("M2/02")]
            Map<String, Map> split = AuditOrderUnits.stepUnits(statements, entries, records)
            List<String> openAt = split.findAll { k, v -> (v.counted as List<Map>).any { it.state == OPEN } }*.key
            assertEquals(["SA"], openAt, "M2 counted open once, at SA (step ${seq})")
            assertEquals([["M2", ["F-B"]]], (split.SB.waiting as List<Map>).collect { [it.order, (it.entries as List<Map>)*.row*.findingId] })
        }
    }

    // sequenceNum 0 read as "no step" (Groovy: 0 is false), so the earliest step lost to the next one.
    @Test
    void stepZeroIsTheEarliestStep() {
        List<Map> statements = [statement("S1", 1L), statement("S0", 0L)]
        Map<String, Map> split = AuditOrderUnits.stepUnits(statements,
                [S0: [entry("F-0", "S0", OPEN)], S1: [entry("F-1", "S1", OPEN)]], ["F-0": item("M1/01"), "F-1": item("M1/02")])
        assertEquals([["M1", OPEN, ["F-0"]]], (split.S0.counted as List<Map>).collect { [it.order, it.state, (it.entries as List<Map>)*.row*.findingId] })
        assertEquals([], split.S1.counted)
        assertEquals(["S0", "S1"], [[statementId: "S1", statementCode: "S1", sequenceNum: 1L], [statementId: "S0", statementCode: "S0", sequenceNum: 0L]]
                .sort(false, AuditReportAssembler.STATEMENT_ORDER)*.statementId)
    }

    // QA: the input-order test checked only M1's holder; the whole split must be the same in every order.
    @Test
    void theWholeSplitIsTheSameInEveryInputOrder() {
        Map s = scenario()
        List<Map> statements = [statement("S0", null), statement("S2", 8L)] + (s.statements as List<Map>)
        Map entries = (s.entries as Map) + [S0: [entry("F-0", "S0", OPEN)], S2: [entry("F-20", "S2", OPEN)]]
        Map records = (s.records as Map) + ["F-0": item("M1/04"), "F-20": item("M9/02")]
        Map expected = shape(AuditOrderUnits.stepUnits(statements, entries, records))
        [statements.reverse(), [statements[2], statements[0], statements[3], statements[1]]].each { List<Map> order ->
            assertEquals(expected, shape(AuditOrderUnits.stepUnits(order, entries, records)), "for ${order*.statementId}")
        }
    }
}

