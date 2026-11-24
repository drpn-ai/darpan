package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** A finding's level: its reason's override, else the statement's, else P2; an order unit takes its most urgent line. */
class AuditPriorityTests {

    @Test
    void "no level reads P2"() {
        assertEquals([level: "P2", source: "statement", conclusionEnumId: null, index: 0], AuditPriority.resolve(null, [:], [null]))
    }

    @Test
    void "a reason override wins over the statement level"() {
        Map r = AuditPriority.resolve("P2", [C1: "KNOWN"], ["C1"])
        assertEquals("KNOWN", r.level)
        assertEquals("reason", r.source)
        assertEquals("C1", r.conclusionEnumId)
    }

    @Test
    void "an unexplained line takes the statement level"() {
        assertEquals("P0", AuditPriority.resolve("P0", [C1: "P2"], [null]).level)
        assertEquals("P0", AuditPriority.resolve("P0", [C1: "P2"], null).level)
    }

    @Test
    void "the most urgent line wins"() {
        assertEquals([level: "P0", source: "reason", conclusionEnumId: "C1", index: 1], AuditPriority.resolve("P2", [C1: "P0"], ["C2", "C1"]))
    }

    @Test
    void "ties go to the first line"() {
        assertEquals("C2", AuditPriority.resolve("P1", [C1: "P1", C2: "P1"], ["C2", "C1"]).conclusionEnumId)
    }

    @Test
    void "zero counts are ordered by level"() {
        assertEquals(AuditPriority.LEVELS, new ArrayList<String>(AuditPriority.zeroCounts().keySet()))
        assertEquals(0, AuditPriority.zeroCounts().values().sum())
    }
}
