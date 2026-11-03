package darpan.agent.pipeline

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

/** DAR-BE-159. A record id in a proposed change must come from the human, the opened finding or the tenant's config. Synthetic values. */
class AgentProvenanceTests {

    private static final String ZW = "​"

    @Test
    void "an id the human typed passes"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude M1088731 from R1"], [:], [] as Set)
        assertEquals("PASS", r.status)
        assertEquals([], r.blockedIds)
    }

    @Test
    void "an id seen only in a tool result is blocked"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude test orders from R1"], [:], [] as Set)
        assertEquals("BLOCKED", r.status)
        assertEquals(["M1088731"], r.blockedIds)
    }

    @Test
    void "an id from the opened finding passes, nested values included"() {
        Map ctx = [finding: [records: [OMS: [Order: [[orderId: "M1088731"]]]]]]
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude it"], ctx, [] as Set)
        assertEquals("PASS", r.status)
    }

    @Test
    void "an id already in the tenant config passes"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], [], [:], ["M1088731"] as Set)
        assertEquals("PASS", r.status)
    }

    @Test
    void "a substring match does not count"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude M10887"], [:], [] as Set)
        assertEquals("BLOCKED", r.status)
        assertEquals(["M1088731"], r.blockedIds)
        // and the id as the longer token does not vouch for the shorter one
        Map r2 = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M10887"]], ["exclude M1088731"], [:], [] as Set)
        assertEquals("BLOCKED", r2.status)
    }

    @Test
    void "an id bounded by punctuation or sentence end still matches"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["drop 'M1088731', and (M1088731)"], [:], [] as Set)
        assertEquals("PASS", r.status)
        Map r2 = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude M1088731; thanks"], [:], [] as Set)
        assertEquals("PASS", r2.status)
    }

    @Test
    void "trailing sentence punctuation ends an id but a dot inside a token does not"() {
        ["exclude M1088731.", "is M1088731? odd", "M1088731! yes", "(M1088731)", "say \"M1088731\" now", "a, M1088731: b"].each { String t ->
            assertEquals("PASS", AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], [t], [:], [] as Set).status, t)
        }
        ["M1088731.5", "M1088731.x", "M1088731.5."].each { String t ->
            assertEquals("BLOCKED", AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], [t], [:], [] as Set).status, t)
        }
    }

    @Test
    void "matching is case sensitive"() {
        Map r = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude m1088731"], [:], [] as Set)
        assertEquals("BLOCKED", r.status)
    }

    @Test
    void "fix text placeholders are not identifiers"() {
        assertEquals([] as Set, AgentProvenance.identifiersOf("FIX_TEXT", [text: "Set {orderId} to {status}"]))
        assertEquals("PASS", AgentProvenance.check("FIX_TEXT", [text: "Set {orderId}"], [], [:], [] as Set).status)
        assertEquals([] as Set, AgentProvenance.identifiersOf("STATEMENT_NARROWING", [codes: ["A", "B"]]))
    }

    @Test
    void "conclusion row check values are identifiers and in and not-in values are split"() {
        Map after = [rows: [[conditions: [[checkValue: "A1, B2"], [checkValue: "C3"]]], [conditions: [[checkValue: null]]]]]
        assertEquals(["A1", "B2", "C3"] as Set, AgentProvenance.identifiersOf("CONCLUSION_ROWS", after))
        Map r = AgentProvenance.check("CONCLUSION_ROWS", after, ["A1 and C3"], [:], [] as Set)
        assertEquals("BLOCKED", r.status)
        assertEquals(["B2"], r.blockedIds)
    }

    @Test
    void "an unknown kind fails closed"() {
        assertThrows(IllegalArgumentException) { AgentProvenance.identifiersOf("NOPE", [:]) }
        assertThrows(IllegalArgumentException) { AgentProvenance.check("NOPE", [:], [], [:], [] as Set) }
    }

    @Test
    void "a zero-width-split copy typed by the human matches the plain id, and a split id in no allowed source is blocked"() {
        Map pass = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude M10${ZW}88731"], [:], [] as Set)
        assertEquals("PASS", pass.status)
        // the proposal itself carries a split id; the only place it appears is tool data, which is not an allowed source
        Map blocked = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M10${ZW}88731"]], ["exclude test orders"], [:], [] as Set)
        assertEquals("BLOCKED", blocked.status)
        assertEquals(["M1088731"], blocked.blockedIds)
        // fullwidth variant of the id in the human text is the same id after NFKC
        Map full = AgentProvenance.check("EXCLUSION_FILTER", [filterValues: ["M1088731"]], ["exclude Ｍ1088731"], [:], [] as Set)
        assertEquals("PASS", full.status)
    }
}
