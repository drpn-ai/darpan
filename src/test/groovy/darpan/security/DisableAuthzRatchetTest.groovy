package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 — fail-mode ratchet for bare {@code .disableAuthz(} calls in production Groovy.
 *
 * <p>Scans every {@code .groovy} file under
 * {@code runtime/component/darpan/src/main/groovy/darpan/} and counts occurrences of the
 * literal string {@code .disableAuthz(}, excluding the two allowlisted files that are
 * <em>permitted</em> to contain bare calls:</p>
 * <ul>
 *   <li>{@code TenantScopedFinder.groovy} — the new canonical finder; its internal calls are
 *       intentional and form the migration target.</li>
 *   <li>{@code TenantAccessSupport.groovy} — low-level authz helper; its calls are
 *       framework-level and already reviewed.</li>
 * </ul>
 *
 * <p><strong>Step 5 (complete) — explicit allowlist of kept-bare sites:</strong></p>
 * <ul>
 *   <li>{@code facade/common/FacadeSupport.groovy:32} — test-stub guard pattern;
 *       {@code findGlobalUnscoped} would bypass the {@code metaClass.respondsTo} guard
 *       needed for lightweight test stubs. KEPT BARE (entity read, guarded).</li>
 *   <li>{@code facade/reconciliation/ReconciliationOutputSupport.groovy:140} — complex
 *       pre-built finder with a {@code condition("in", pathCandidates)} list; caller does
 *       the ownership check after. KEPT BARE (entity read, guarded by caller).</li>
 *   <li>{@code facade/reconciliation/AutomationFacadeSupport.groovy:279} — service call
 *       {@code ec.service.sync()...disableAuthz().call()}; not an entity read. KEPT BARE.</li>
 *   <li>{@code facade/reconciliation/ReconciliationSavedRunSupport.groovy:806} — service call.
 *       KEPT BARE.</li>
 *   <li>{@code facade/reconciliation/ReconciliationSavedRunSupport.groovy:839} — service call.
 *       KEPT BARE.</li>
 *   <li>{@code facade/reconciliation/runSavedRunDiff.groovy:110} — service call. KEPT BARE.</li>
 *   <li>{@code reconciliation/automation/AutomationExecutionSupport.groovy:597,755,763,775} —
 *       service calls guarded by {@code metaClass.respondsTo}. KEPT BARE (4 sites).</li>
 *   <li>{@code reconciliation/automation/SftpAutomationSupport.groovy:400} — service call
 *       guarded by {@code metaClass.respondsTo}. KEPT BARE.</li>
 *   <li>{@code reconciliation/notification/TenantNotificationSupport.groovy:104} — service call.
 *       KEPT BARE.</li>
 *   <li>{@code facade/reconciliation/AutomationFacadeSupport.groovy} (second site, in
 *       {@code backfillAutomationExcludeFilters}) — one-time cross-tenant migration write; creates a
 *       {@code ReconciliationAutomationSourceFilter} row via {@code ec.service.sync()...call()}.
 *       {@code TenantScopedFinder} has no create-side equivalent (it only wraps reads), so this stays
 *       a bare service call like the other writes above. The read side of the same sweep
 *       ({@code ReconciliationAutomation} list and the per-automation/fileSide existence check) both
 *       route through {@code TenantScopedFinder.findGlobalUnscoped}, matching
 *       {@code AutomationRuntimeSupport.loadAutomationSourceFilters}, and add nothing to this count.
 *       KEPT BARE.</li>
 * </ul>
 *
 * <p><strong>Ratchet rule (step 5 — fail-mode):</strong> the count MUST equal {@code BASELINE}
 * exactly. Any new bare call outside the allowlist fails the build; any unexplained removal
 * also fails (forces a conscious BASELINE update when an allowlisted site is finally
 * migrated).</p>
 */
class DisableAuthzRatchetTest {

    /**
     * Frozen allowlist floor as of P0 #4 step 5 (2026-06-27): all entity reads migrated to
     * {@link darpan.facade.common.TenantScopedFinder}. Lowered 12 -> 11 by DAR-295 (Phase 3), which
     * collapsed the two per-system ensureVirtual{HotWax,Shopify}OrdersRemote builders in
     * ReconciliationSavedRunSupport into one registry-driven ensureVirtualApiOrdersRemote (net -1 bare
     * service call). Raised 11 -> 12 by the exclusion-filter backfill (Task 13,
     * {@code AutomationFacadeSupport.backfillAutomationExcludeFilters}): one new bare service-call
     * write (create a filter row for a pre-existing automation) that has no
     * {@code TenantScopedFinder} write equivalent; its own reads use
     * {@code findGlobalUnscoped} instead and add nothing. Raised 12 -> 15 by the migration
     * supervisor (2026-08-20, {@code migration/MigrationSupervisorSupport}): three new bare
     * service-call sites — the registered-migration invocation and two {@code record#MigrationRun}
     * ledger writes — none of which has a {@code TenantScopedFinder} equivalent, since that finder
     * wraps reads only. All five entity reads in that class use {@code findGlobalUnscoped}.
     * Remaining calls are service calls
     * (guarded {@code metaClass.respondsTo} patterns) or the two entity-read sites that cannot
     * safely use {@code findGlobalUnscoped} without breaking test-stub compatibility
     * ({@code FacadeSupport:32}, {@code ReconciliationOutputSupport:140}). See class Javadoc
     * for the full per-site breakdown.
     *
     * <p>Lowered 15 -&gt; 14 (2026-08-26) by the reconciliation-pipeline unification, step 3
     * ({@code 2026-08-26-reconciliation-pipeline-unification-design}). Bringing the missing-diff
     * verification pass to the scheduled path needed an authz-relaxed lookup dispatch; rather than
     * add a fourth copy of the guarded {@code metaClass.respondsTo} pattern to
     * {@code AutomationExecutionSupport}, the two byte-identical existing copies
     * ({@code callRuleSetCompareScope}, {@code callExecuteAutomationService}) and the new caller were
     * collapsed onto one audited {@code dispatchInternalService} seam — net -1 site, and one place to
     * audit instead of three.</p>
     */
    static final int BASELINE = 14

    /**
     * Allowlisted files matched by their path <em>relative to srcRoot</em>
     * (e.g. {@code facade/common/TenantScopedFinder.groovy}) so that a same-named file in
     * another package cannot accidentally inherit the exemption.
     */
    private static final List<String> ALLOWLISTED_FILES = [
            "facade/common/TenantScopedFinder.groovy",
            "facade/common/TenantAccessSupport.groovy",
    ]

    @Test
    void bareDisableAuthzCountIsWithinRatchetBaseline() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path srcRoot = backendRoot.resolve("runtime/component/darpan/src/main/groovy/darpan")

        assertTrue(Files.exists(srcRoot),
                "Could not locate darpan src root at ${srcRoot}; check resolveBackendRoot()")

        int count = 0
        List<String> violations = []

        Files.walk(srcRoot)
                .filter { Path p -> p.toString().endsWith(".groovy") }
                .filter { Path p -> !ALLOWLISTED_FILES.contains(srcRoot.relativize(p).toString()) }
                .forEach { Path p ->
                    String content = p.toFile().text
                    int fileCount = content.count(".disableAuthz(")
                    if (fileCount > 0) {
                        count += fileCount
                        violations << "${srcRoot.relativize(p)} (${fileCount})"
                    }
                }

        System.out.println("[DisableAuthzRatchetTest] bare .disableAuthz( count: ${count}/${BASELINE} — sites: ${violations}")

        assertTrue(count == BASELINE,
                "Bare .disableAuthz( count ${count} does not match ratchet BASELINE ${BASELINE}. " +
                "If count > BASELINE: a new unscoped call was added outside the allowlist — route it through TenantScopedFinder. " +
                "If count < BASELINE: an allowlisted site was migrated — lower BASELINE to match. " +
                "Sites: ${violations}")
    }

    /**
     * DAR-BE-075. The test above only ever watched darpan's Groovy, so a bypass written in service XML
     * ({@code <set from="ec.entity.find(..).disableAuthz()..">} or {@code <service-call disable-authz="true">})
     * or in any other Darpan component grew unseen — and moving Groovy into XML actions (DAR-BE-074) would
     * have hidden the sites it moved. Same fail-mode rule: each count must equal its baseline exactly.
     * Counts are frozen at 2026-10-19; none is a known leak — the point is that growth is now visible.
     */
    static final Map<String, Integer> COMPONENT_BASELINES = [
            "darpan service+screen XML .disableAuthz("            : 69,
            "darpan service+screen XML disable-authz=\"true\""    : 35,
            "darpan-hotwax Groovy .disableAuthz("                 : 6,
            "darpan-hotwax service+screen XML .disableAuthz("     : 0,
            "darpan-hotwax service+screen XML disable-authz=\"true\"": 0,
            "shopify-darpan Groovy .disableAuthz("                : 1,
            "shopify-darpan service+screen XML .disableAuthz("    : 0,
            "shopify-darpan service+screen XML disable-authz=\"true\"": 0,
            // 5 since DAR-BE-111: extractNetSuiteOrders asks lookup#HotWaxOmsOrderStatuses for each page's HotWax status. A
            // service-call bypass, tenant-pinned by the query row's own companyUserGroupId (the trusted run owner, checked by
            // the lookup's config gate); a scheduled extract may run with no signed-in user to authorize it.
            "netsuite-darpan Groovy .disableAuthz("               : 5,
            "netsuite-darpan service+screen XML .disableAuthz("   : 0,
            "netsuite-darpan service+screen XML disable-authz=\"true\"": 0,
            "database-darpan Groovy .disableAuthz("               : 10,
            "database-darpan service+screen XML .disableAuthz("   : 0,
            "database-darpan service+screen XML disable-authz=\"true\"": 0,
    ]

    @Test
    void everyComponentAndXmlBypassCountIsWithinItsRatchetBaseline() {
        Path componentRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        Map<String, Integer> counts = [:]
        Map<String, List<String>> sites = [:]

        ["darpan", "darpan-hotwax", "shopify-darpan", "netsuite-darpan", "database-darpan"].each { String component ->
            Path root = componentRoot.resolve(component)
            assertTrue(Files.exists(root), "Could not locate component ${component} at ${root}")
            if (component != "darpan") {
                tally(counts, sites, componentRoot, "${component} Groovy .disableAuthz(", [root.resolve("src/main/groovy")], ".groovy", ".disableAuthz(")
            }
            List<Path> xmlDirs = [root.resolve("service"), root.resolve("screen")]
            tally(counts, sites, componentRoot, "${component} service+screen XML .disableAuthz(", xmlDirs, ".xml", ".disableAuthz(")
            tally(counts, sites, componentRoot, "${component} service+screen XML disable-authz=\"true\"", xmlDirs, ".xml", 'disable-authz="true"')
        }

        assertTrue(counts.keySet() == COMPONENT_BASELINES.keySet(),
                "ratchet scopes drifted from the baselines: counted ${counts.keySet()}, baselined ${COMPONENT_BASELINES.keySet()}")
        List<String> mismatches = COMPONENT_BASELINES.findAll { String scope, Integer baseline -> counts[scope] != baseline }
                .collect { String scope, Integer baseline -> "${scope}: ${counts[scope]} vs baseline ${baseline} — sites: ${sites[scope]}".toString() }
        System.out.println("[DisableAuthzRatchetTest] component/XML counts: ${counts}")
        assertTrue(mismatches.isEmpty(),
                "Authz-bypass count changed. If it grew: a new bypass was added — route reads through TenantScopedFinder " +
                "(findGlobalUnscoped with a reason, or a tenant-scoped finder) and justify any service-call bypass. " +
                "If it shrank: a bypass was removed — lower that baseline to match.\n  " + mismatches.join("\n  "))
    }

    private static void tally(Map<String, Integer> counts, Map<String, List<String>> sites, Path labelRoot, String scope,
                              List<Path> dirs, String extension, String needle) {
        int total = 0
        List<String> found = []
        dirs.findAll { Files.exists(it) }.each { Path dir ->
            Files.walk(dir).filter { Path p -> p.toString().endsWith(extension) }.forEach { Path p ->
                int n = p.toFile().text.count(needle)
                if (n > 0) {
                    total += n
                    found << "${labelRoot.relativize(p)} (${n})".toString()
                }
            }
        }
        counts[scope] = total
        sites[scope] = found
    }
}
