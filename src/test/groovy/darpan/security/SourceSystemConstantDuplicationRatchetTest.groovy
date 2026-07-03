package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drift guard (DAR-299, KT §7 Phase 6). The MACH audit flagged the same per-source system-enum-id
 * literals ("OMS" / "SHOPIFY" / "NETSUITE" / "SAPI") re-declared as {@code static final String} across
 * the source-dispatch support classes. Now that dispatch is data-driven (SourceSystemConnector), this
 * ratchet freezes the remaining literal declarations at {@code BASELINE} so no NEW duplicate can creep
 * back — new source facts belong in the registry / seed, not a re-declared constant.
 *
 * <p>Extract-service, remote-id, endpoint-label and config-type constants are ALIASES of
 * {@code ReconciliationSavedRunSupport} (single source of truth — cannot drift), so they are not
 * counted here; only bare string literals are.</p>
 *
 * <p><strong>Ratchet rule (fail-mode, mirrors {@code DisableAuthzRatchetTest}):</strong> the count MUST
 * equal {@code BASELINE}. count &gt; BASELINE = a new duplicate literal was added (route it through the
 * registry or reference the single holder instead); count &lt; BASELINE = a duplicate was legitimately
 * collapsed (lower BASELINE to match).</p>
 */
class SourceSystemConstantDuplicationRatchetTest {

    /**
     * System-enum-id string-literal declarations across the three source-dispatch classes as of
     * DAR-299: ReconciliationSavedRunSupport (SHOPIFY/OMS/NETSUITE/SAPI = 4) + AutomationFacadeSupport
     * (OMS/SHOPIFY/NETSUITE = 3) = 7. AutomationExecutionSupport no longer declares any (its copies were
     * removed once dispatch became registry-driven).
     */
    static final int BASELINE = 7

    private static final List<String> SCANNED_FILES = [
            "reconciliation/automation/AutomationExecutionSupport.groovy",
            "facade/reconciliation/AutomationFacadeSupport.groovy",
            "facade/reconciliation/ReconciliationSavedRunSupport.groovy",
    ]

    private static final Pattern SYSTEM_ENUM_LITERAL =
            Pattern.compile(/static final String \w+\s*=\s*"(OMS|SHOPIFY|NETSUITE|SAPI)"/)

    @Test
    void systemEnumLiteralDeclarationsMatchRatchetBaseline() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path srcRoot = backendRoot.resolve("runtime/component/darpan/src/main/groovy/darpan")
        assertTrue(Files.exists(srcRoot), "Could not locate darpan src root at ${srcRoot}")

        int count = 0
        List<String> sites = []
        SCANNED_FILES.each { String rel ->
            Path p = srcRoot.resolve(rel)
            if (!Files.exists(p)) return
            Matcher m = SYSTEM_ENUM_LITERAL.matcher(p.toFile().text)
            int fileCount = 0
            while (m.find()) fileCount++
            if (fileCount > 0) {
                count += fileCount
                sites << "${rel} (${fileCount})".toString()
            }
        }

        System.out.println("[SourceSystemConstantDuplicationRatchetTest] system-enum literal decls: ${count}/${BASELINE} — ${sites}")
        assertEquals(BASELINE, count,
                "System-enum-id literal declarations (${count}) != BASELINE ${BASELINE}. " +
                        "If count > BASELINE: a new duplicate per-source constant was declared — reference the " +
                        "SourceSystemConnector registry (or a single holder) instead of re-declaring " +
                        "\"OMS\"/\"SHOPIFY\"/\"NETSUITE\"/\"SAPI\". If count < BASELINE: a duplicate was collapsed — " +
                        "lower BASELINE to match. Sites: ${sites}")
    }
}
