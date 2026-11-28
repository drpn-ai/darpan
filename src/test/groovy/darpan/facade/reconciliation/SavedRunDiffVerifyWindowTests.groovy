package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S18, invariant 6 (time): a manual run's verify passes judge rows against the window its sides were read over.
 *
 * <p>run#SavedRunDiff reads its API sides over {@code windowStartDateValue}/{@code windowEndDateValue}: the requested window
 * re-anchored to the tenant's zone (normalizeSavedRunApiWindow), or a retried run's own window (RunRetrySupport.plan). The
 * return-presence and exchange passes used to read the raw {@code windowStartDate}/{@code windowEndDate} parameters instead:
 * UTC midnight where the extract read tenant midnight (5.5 h apart for an IST tenant), and null on a retry, which drops
 * return grading to grace-only and skips the exchange pass. The scheduled path hands both passes the run's own window
 * (AutomationExecutionSupport.readWindowStartMillis), so the two paths graded the same run differently.</p>
 *
 * <p>Read from source, comments stripped, as RunPathParityGateTest does: the script cannot run without Moqui, Spark and a
 * returns connector pair on both sides.</p>
 */
class SavedRunDiffVerifyWindowTests {

    private static final String SCRIPT = "facade/reconciliation/runSavedRunDiff.groovy"

    @Test
    void verifyPassesReadTheWindowTheSidesWereReadOver() {
        String code = codeOf(SCRIPT)
        assertEquals(2, (code =~ /windowStartMillis\s*:\s*windowStartDateValue\?\.time\b/).size(),
                "${SCRIPT}: the return-presence and exchange passes must both get windowStartDateValue")
        assertEquals(1, (code =~ /windowEndMillis\s*:\s*windowEndDateValue\?\.time\b/).size(),
                "${SCRIPT}: the exchange pass must get windowEndDateValue")
    }

    @Test
    void theRawWindowParametersAreReadOnlyToParseThem() {
        // String literals out (error texts name the parameters); a property read (retryPlan.windowStartDate) or a map key is not
        // the binding.
        String code = codeOf(SCRIPT).replaceAll(/"(?:[^"\\]|\\.)*"/, '""')
        List<String> rawReads = (code =~ /(?<![.\w])window(?:Start|End)Date\b(?!\s*:)/).collect { it as String }
        assertEquals(["windowStartDate", "windowEndDate"], rawReads,
                "${SCRIPT}: windowStartDate/windowEndDate are parsed once (toTimestampValue); everything after uses the *Value window")
        assertTrue(code.contains("toTimestampValue(windowStartDate)") && code.contains("toTimestampValue(windowEndDate)"),
                "${SCRIPT}: the raw parameters are read only by toTimestampValue")
    }

    private static String codeOf(String relativePath) {
        Path root = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/src/main/groovy/darpan")
        Path p = root.resolve(relativePath)
        assertTrue(Files.exists(p), "Could not locate ${relativePath} — if it moved, update this test.")
        return p.toFile().text
                .replaceAll(/(?s)\/\*.*?\*\//, " ")
                .split("\n")
                .findAll { String line -> !line.trim().startsWith("//") }
                .join("\n")
    }
}
