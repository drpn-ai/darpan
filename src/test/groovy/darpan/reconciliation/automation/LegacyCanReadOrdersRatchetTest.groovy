package darpan.reconciliation.automation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * canReadOrders is retired over two releases: release N keeps the column but nothing may READ it
 * except the migration and the write paths that still persist it. Release N+1 removes the field
 * and this test with it. A new reader means a gate was missed.
 *
 * <p>The allowlist below was derived from a literal grep of every component's {@code src/main}
 * (Task 13), not copied from the plan brief's guess — the brief named a migration file
 * ({@code migrateSourceConfigEndpointAccess.groovy}) that does not exist; the real migration lives
 * in {@link SourceEndpointWriteSupport#migrateLegacyCanReadOrders}. Matching is by bare filename
 * (same as the brief's reference implementation), so a same-named file added to a different
 * package would also need adding here — that is a known, accepted limitation of this ratchet's
 * granularity, not a gap this task closes.</p>
 */
class LegacyCanReadOrdersRatchetTest {

    private static final List<String> ALLOWED = [
            // --- HotWax OMS write path: persists the legacy flag and echoes it back to callers ---
            // saveOmsRestSourceConfig.groovy normalizes the incoming value and writes it onto the
            // entity; OmsRestSourceSupport.groovy both declares it in the save round-trip's
            // CONFIG_FIELD_NAMES list and reads it back in safeConfigMapFromPlain() so callers see
            // what was just persisted (same shape as every other config field there).
            "saveOmsRestSourceConfig.groovy",
            "OmsRestSourceSupport.groovy",

            // --- Shopify write path: same pattern as the HotWax pair above ---
            // ShopifyAuthConfigSupport.groovy both writes canReadOrders on save (deriveCanReadOrders/
            // canReadOrdersValue) and echoes it back in the safe-config projection.
            "ShopifyAuthConfigSupport.groovy",

            // --- One-time migration ---
            // SourceEndpointWriteSupport.migrateLegacyCanReadOrders(ec) translates the legacy flag
            // into explicit SourceConfigEndpointAccess rows exactly once. This is the ONLY reader
            // that is supposed to still exist once release N+1 drops the column.
            "SourceEndpointWriteSupport.groovy",

            // --- Comment-only mentions: no field access, just prose explaining the migration ---
            // AutomationExecutionSupport.groovy's post-filter comment ("Endpoint enablement replaced
            // canReadOrders...") and ShopifyGraphqlFacadeSupport.groovy's similar comment both name
            // the retired flag in text only; grep-by-substring can't tell a comment from a read, so
            // both files are allowlisted rather than teaching the ratchet to parse Groovy comments.
            "AutomationExecutionSupport.groovy",
            "ShopifyGraphqlFacadeSupport.groovy",

            // --- Known, tracked-elsewhere readers that Task 13 does not fix ---
            // ShopifyConnectionProbe.groovy still reads config.canReadOrders directly (build() and
            // isDisabled()) — Task 14, immediately after this one, replaces that single boolean with
            // a per-endpoint report. Stays allowlisted until Task 14 lands.
            "ShopifyConnectionProbe.groovy",
            // AutomationFacadeSupport.groovy's listOmsRestSourceConfigOptions/listShopifyAuthConfigOptions/
            // listOmsRestSourceRemoteOptions still filter the automation source-config picker by the
            // raw canReadOrders field instead of SourceEndpointAccessSupport.isEndpointEnabled. This
            // is real, pre-existing (not introduced by Task 13) technical debt: the plan document's
            // own "Explicitly NOT in this plan" section names these "registry-driven option builders"
            // as Plan 2 scope. Allowlisted here for the same reason as the connection probe above —
            // tracked, not silently accepted as permanent.
            "AutomationFacadeSupport.groovy",
    ]

    @Test
    void onlyTheMigrationAndWritePathsStillReadTheLegacyFlag() {
        Path componentsRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        List<String> offenders = []

        ["darpan", "darpan-hotwax", "shopify-darpan"].each { String component ->
            Path mainSrc = componentsRoot.resolve("${component}/src/main")
            if (!Files.isDirectory(mainSrc)) return
            Files.walk(mainSrc).filter { it.toString().endsWith(".groovy") }.forEach { Path file ->
                if (ALLOWED.contains(file.fileName.toString())) return
                if (Files.readString(file).contains("canReadOrders")) {
                    offenders.add(componentsRoot.relativize(file).toString())
                }
            }
        }

        assertEquals([], offenders.sort(),
                "New canReadOrders reader(s) found. Per-endpoint enablement replaced this flag — " +
                "route the check through SourceEndpointAccessSupport.isEndpointEnabled instead.")
    }
}
