package darpan.facade.settings

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Pure unit coverage for the check-contract normalizer — no Moqui boot.
 *
 * The property under test is that nothing a connector probe returns can be misread as healthy:
 * an unrecognized status must land on FAIL rather than be dropped or defaulted to PASS.
 */
class SourceConnectionDiagnosticsSupportTests {

    @Test
    void normalizesWellFormedChecks() {
        List<Map<String, Object>> out = SourceConnectionDiagnosticsSupport.normalizeChecks([
                [key: "credential", label: "Credential readable", status: "PASS"],
                [key: "auth", label: "Credentials accepted", status: "fail", detail: "401", durationMillis: 91],
                [key: "ordersRead", label: "Orders readable", status: "SKIP"],
        ])

        assertEquals(3, out.size())
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_PASS, out[0].status)
        // Status is case-normalized before validation.
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_FAIL, out[1].status)
        assertEquals("401", out[1].detail)
        assertEquals(91L, out[1].durationMillis)
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_SKIP, out[2].status)
        assertNull(out[0].detail)
        assertNull(out[0].durationMillis)
    }

    @Test
    void unrecognizedStatusBecomesFailNotPass() {
        // The load-bearing case: a probe that emits garbage must not produce a green row.
        List<Map<String, Object>> out = SourceConnectionDiagnosticsSupport.normalizeChecks([
                [key: "a", label: "A", status: "OK"],
                [key: "b", label: "B", status: null],
                [key: "c", label: "C"],
                [key: "d", label: "D", status: "PASSED"],
        ])

        assertEquals(4, out.size())
        assertTrue(out.every { it.status == SourceConnectionDiagnosticsSupport.STATUS_FAIL },
                "unclassifiable statuses must read as FAIL, got ${out*.status}")
    }

    @Test
    void dropsRowsThatCannotBeRendered() {
        List<Map<String, Object>> out = SourceConnectionDiagnosticsSupport.normalizeChecks([
                [key: "keep", label: "Keep", status: "PASS"],
                [key: "", label: "No key", status: "PASS"],
                [key: "noLabel", label: "  ", status: "PASS"],
                [label: "Missing key entirely", status: "PASS"],
                "not a map",
                null,
        ])

        assertEquals(1, out.size())
        assertEquals("keep", out[0].key)
    }

    @Test
    void nonCollectionInputYieldsNoChecks() {
        assertTrue(SourceConnectionDiagnosticsSupport.normalizeChecks(null).isEmpty())
        assertTrue(SourceConnectionDiagnosticsSupport.normalizeChecks("checks").isEmpty())
        assertTrue(SourceConnectionDiagnosticsSupport.normalizeChecks([:]).isEmpty())
        assertTrue(SourceConnectionDiagnosticsSupport.normalizeChecks([]).isEmpty())
    }

    @Test
    void checkBuilderRejectsUnknownStatus() {
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_FAIL,
                SourceConnectionDiagnosticsSupport.check("k", "L", "WHATEVER").status)
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_PASS,
                SourceConnectionDiagnosticsSupport.check("k", "L", SourceConnectionDiagnosticsSupport.STATUS_PASS).status)
    }
}
