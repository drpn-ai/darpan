package darpan.agent

import darpan.datasource.GraphQlDocumentCheck
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003 Task 4: the read-only checks, caps, lane and error hygiene of a fresh query. Pure: no Moqui. */
class AgentQuerySupportTests {

    @BeforeEach
    void resetLane() { AgentQueryLane.reset() }

    @Test
    void "suiteql: SELECT and WITH pass"() {
        assertNull(AgentQuerySupport.checkSuiteQl("SELECT id FROM transaction"))
        assertNull(AgentQuerySupport.checkSuiteQl("  select id from transaction;  "))
        assertNull(AgentQuerySupport.checkSuiteQl("WITH t AS (SELECT 1 AS x FROM dual) SELECT x FROM t"))
    }

    @Test
    void "suiteql: INSERT UPDATE DELETE MERGE refused"() {
        ["INSERT INTO t VALUES (1)", "UPDATE t SET a = 1", "DELETE FROM t", "MERGE INTO t USING s ON (1=1)",
         "DROP TABLE t", "", "   ", null].each {
            assertNotNull(AgentQuerySupport.checkSuiteQl(it as String), "should refuse: ${it}")
        }
    }

    @Test
    void "suiteql: two statements refused"() {
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT 1; DELETE FROM x"))
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT 1 /* c */; SELECT 2"))
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT 1 -- c\n; DELETE FROM x"))
    }

    @Test
    void "suiteql: a semicolon inside a string literal is not a second statement"() {
        assertNull(AgentQuerySupport.checkSuiteQl("SELECT 'a;b' FROM dual"))
        assertNull(AgentQuerySupport.checkSuiteQl("SELECT 'it''s; fine' FROM dual"))
        assertNull(AgentQuerySupport.checkSuiteQl("SELECT 1 FROM dual -- not; a second one"))
        // an unterminated literal could hide the rest of the text from the check: refuse it
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT 'oops; DELETE FROM x"))
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT 1 /* never closed; DELETE FROM x"))
    }

    @Test
    void "suiteql: a leading comment does not hide a non-SELECT"() {
        assertNotNull(AgentQuerySupport.checkSuiteQl("/* SELECT */ DELETE FROM x"))
        assertNotNull(AgentQuerySupport.checkSuiteQl("-- SELECT 1\nDELETE FROM x"))
        assertNull(AgentQuerySupport.checkSuiteQl("/* note */ SELECT 1 FROM dual"))
        assertNotNull(AgentQuerySupport.checkSuiteQl("SELECT/**/1;DELETE FROM x"))
    }

    @Test
    void "graphql: a mutation is refused"() {
        assertNotNull(AgentQuerySupport.checkGraphqlQuery("mutation { deleteThing(id: 1) { id } }"))
        assertNull(AgentQuerySupport.checkGraphqlQuery("query { orders { id } }"))
        assertNull(AgentQuerySupport.checkGraphqlQuery("{ orders { id } }"))
    }

    @Test
    void "graphql: a subscription is refused"() {
        assertNotNull(AgentQuerySupport.checkGraphqlQuery("subscription { orderChanged { id } }"))
    }

    @Test
    void "graphql: introspection is allowed only via check(doc, true)"() {
        String doc = "query { __schema { types { name } } }"
        assertFalse(((List) GraphQlDocumentCheck.check(doc).errors).isEmpty())
        assertFalse(((List) GraphQlDocumentCheck.check(doc, false).errors).isEmpty())
        assertTrue(((List) GraphQlDocumentCheck.check(doc, true).errors).isEmpty())
        assertNotNull(AgentQuerySupport.checkGraphqlQuery(doc))
        // a mutation stays refused even when introspection is allowed
        assertFalse(((List) GraphQlDocumentCheck.check("mutation { x }", true).errors).isEmpty())
    }

    @Test
    void "limit: null 200, 5000 to 2000, 0 refused"() {
        assertEquals(200, AgentQuerySupport.clampLimit(null))
        assertEquals(2000, AgentQuerySupport.clampLimit(5000))
        assertEquals(50, AgentQuerySupport.clampLimit("50"))
        assertEquals(1, AgentQuerySupport.clampLimit(1))
        assertThrows(AgentQuerySupport.AgentQueryRefused) { AgentQuerySupport.clampLimit(0) }
        assertThrows(AgentQuerySupport.AgentQueryRefused) { AgentQuerySupport.clampLimit(-3) }
        assertThrows(AgentQuerySupport.AgentQueryRefused) { AgentQuerySupport.clampLimit("many") }
    }

    @Test
    void "lane: the third concurrent query of one tenant is refused, the fifth of the instance is refused, release frees a slot"() {
        assertTrue(AgentQueryLane.tryAcquire("A"))
        assertTrue(AgentQueryLane.tryAcquire("A"))
        assertFalse(AgentQueryLane.tryAcquire("A"), "third of one tenant")
        assertTrue(AgentQueryLane.tryAcquire("B"))
        assertTrue(AgentQueryLane.tryAcquire("B"))
        assertFalse(AgentQueryLane.tryAcquire("C"), "fifth of the instance")
        AgentQueryLane.release("A")
        assertTrue(AgentQueryLane.tryAcquire("C"), "release frees a slot")
        assertFalse(AgentQueryLane.tryAcquire("A".toString() + ""), "A has one slot left but the instance is full")
    }

    @Test
    void "run: an error message loses the secret and host"() {
        List<Map> logged = []
        Map result = AgentQuerySupport.runCore([systemEnumId: "NETSUITE", connectionId: "C1", queryText: "SELECT 1",
                                                secrets: ["s3cr3t-token", "acme-123.suitetalk.api.netsuite.com"]],
                "T1", { throw new IllegalStateException("401 from https://acme-123.suitetalk.api.netsuite.com with s3cr3t-token") },
                { Map row -> logged << row })
        assertEquals("ERROR", result.code)
        String msg = result.error as String
        assertFalse(msg.contains("s3cr3t-token"), msg)
        assertFalse(msg.contains("acme-123.suitetalk"), msg)
        assertEquals(1, logged.size())
        assertEquals("ERROR", logged[0].outcome)
        assertFalse((logged[0].toString()).contains("s3cr3t-token"))
        assertEquals(0, AgentQueryLane.inFlight("T1"), "the slot is released after an error")
    }

    @Test
    void "run: success returns rows and logs one OK row"() {
        List<Map> logged = []
        Map result = AgentQuerySupport.runCore([systemEnumId: "NETSUITE", connectionId: "C1", queryText: "SELECT 1"], "T1",
                { [rows: [[a: 1], [a: 2]], hasMore: true, nextOffset: 2] }, { Map row -> logged << row })
        assertEquals(2, result.rowCount)
        assertEquals(true, result.hasMore)
        assertEquals(2, result.nextOffset)
        assertEquals(false, result.truncated)
        assertNotNull(result.durationMs)
        assertEquals("OK", logged[0].outcome)
        assertEquals(64, (logged[0].queryHash as String).length())
    }

    @Test
    void "run: a full lane answers THROTTLED with a retry hint and still logs"() {
        2.times { AgentQueryLane.tryAcquire("T1") }
        List<Map> logged = []
        Map result = AgentQuerySupport.runCore([queryText: "SELECT 1"], "T1", { [rows: []] }, { Map row -> logged << row })
        assertEquals("THROTTLED", result.code)
        assertEquals(10, result.retryAfterSeconds)
        assertEquals("THROTTLED", logged[0].outcome)
    }

    @Test
    void "run: a hung call returns TIMEOUT and frees the slot"() {
        List<Map> logged = []
        long previous = AgentQuerySupport.timeoutMillis
        AgentQuerySupport.timeoutMillis = 200
        try {
            Map result = AgentQuerySupport.runCore([queryText: "SELECT 1"], "T1", { Thread.sleep(5000); [rows: []] },
                    { Map row -> logged << row })
            assertEquals("TIMEOUT", result.code)
            assertEquals("TIMEOUT", logged[0].outcome)
            assertEquals(0, AgentQueryLane.inFlight("T1"))
        } finally { AgentQuerySupport.timeoutMillis = previous }
    }

    @Test
    void "run: a body over 5 MB is cut and flagged truncated"() {
        String big = "x" * 100_000
        List rows = (1..100).collect { [i: it, blob: big] }   // about 10 MB
        Map result = AgentQuerySupport.runCore([queryText: "SELECT 1"], "T1", { [rows: rows, nextOffset: 100] }, { })
        assertEquals(true, result.truncated)
        assertTrue((result.rowCount as int) < 100 && (result.rowCount as int) > 0)
        assertEquals(true, result.hasMore)
        assertEquals(result.rowCount, result.nextOffset, "the cut row count is where the next page starts")
    }

    @Test
    void "run: a refusal thrown inside the work is REFUSED and logged"() {
        List<Map> logged = []
        Map result = AgentQuerySupport.runCore([queryText: "x"], "T1",
                { throw new AgentQuerySupport.AgentQueryRefused("no") }, { Map row -> logged << row })
        assertEquals("REFUSED", result.code)
        assertEquals("REFUSED", logged[0].outcome)
    }

    /** A worker that ignores interrupt until the test lets it go. */
    private static Closure<Map> stubborn(java.util.concurrent.CountDownLatch release, java.util.concurrent.CountDownLatch started = null) {
        return {
            started?.countDown()
            while (true) {
                try { release.await(); break } catch (InterruptedException ignored) { /* keep going */ }
            }
            [rows: []]
        }
    }

    private static boolean eventually(Closure<Boolean> cond) {
        for (int i = 0; i < 100; i++) { if (cond.call()) return true; Thread.sleep(50) }
        return false
    }

    @Test
    void "lane: a worker that ignores interrupt keeps its tenant slot until it really ends"() {
        long previous = AgentQuerySupport.timeoutMillis
        AgentQuerySupport.timeoutMillis = 150
        def gate = new java.util.concurrent.CountDownLatch(1)
        try {
            assertEquals("TIMEOUT", AgentQuerySupport.runCore([queryText: "q"], "T1", stubborn(gate), { })?.code)
            assertEquals(1, AgentQueryLane.inFlight("T1"), "the timed-out worker still holds the slot")
            assertEquals("TIMEOUT", AgentQuerySupport.runCore([queryText: "q"], "T1", stubborn(gate), { })?.code)
            Map third = AgentQuerySupport.runCore([queryText: "q"], "T1", { [rows: []] }, { })
            assertEquals("THROTTLED", third.code, "the tenant's two slots are both held by running workers")
            gate.countDown()
            assertTrue(eventually { AgentQueryLane.inFlight("T1") == 0 }, "slots free once the workers end")
            assertEquals(0, AgentQuerySupport.runCore([queryText: "q"], "T1", { [rows: []] }, { }).rowCount)
        } finally { gate.countDown(); AgentQuerySupport.timeoutMillis = previous }
    }

    @Test
    void "lane: the instance cap holds while timed-out workers are still running"() {
        long previous = AgentQuerySupport.timeoutMillis
        AgentQuerySupport.timeoutMillis = 150
        def gate = new java.util.concurrent.CountDownLatch(1)
        try {
            ["A", "A", "B", "B"].each { AgentQuerySupport.runCore([queryText: "q"], it, stubborn(gate), { }) }
            Map fifth = AgentQuerySupport.runCore([queryText: "q"], "C", { [rows: []] }, { })
            assertEquals("THROTTLED", fifth.code)
            assertEquals(4, ["A", "B"].sum { AgentQueryLane.inFlight(it) })
            gate.countDown()
            assertTrue(eventually { AgentQueryLane.inFlight("A") + AgentQueryLane.inFlight("B") == 0 })
            assertEquals(0, AgentQuerySupport.runCore([queryText: "q"], "C", { [rows: []] }, { }).rowCount)
        } finally { gate.countDown(); AgentQuerySupport.timeoutMillis = previous }
    }

    @Test
    void "lane: normal completion and a throwing worker free the slot"() {
        AgentQuerySupport.runCore([queryText: "q"], "T1", { [rows: []] }, { })
        assertTrue(eventually { AgentQueryLane.inFlight("T1") == 0 })
        AgentQuerySupport.runCore([queryText: "q"], "T1", { throw new IllegalStateException("x") }, { })
        assertTrue(eventually { AgentQueryLane.inFlight("T1") == 0 })
    }
}
