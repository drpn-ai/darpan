package darpan.architecture

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The parity gate: a triggered run must execute the same process as a manual one, and must KEEP
 * doing so as the pipeline is developed.
 *
 * <p>Darpan runs one reconciliation engine behind two orchestrators — {@code runSavedRunDiff.groovy}
 * (the Run button) and {@code AutomationExecutionSupport.groovy} (the scheduler). They are supposed
 * to differ only before the trigger. Twice in one week they differed after it, and both times the
 * suite stayed green while production diverged:</p>
 *
 * <ul>
 *   <li><b>VERIFY</b> — the missing-diff pass ran on the manual path only. Same automation, same
 *       window: ~532 differences scheduled vs 2 verified (gorjana 100616, 2026-08-26).</li>
 *   <li><b>NOTIFY</b> — both paths notified, only the manual one recorded the stage, so "did the
 *       alert fire?" was unanswerable from a scheduled run's timeline (2026-08-27).</li>
 * </ul>
 *
 * <p>Nothing in the build could have caught either. The two files were kept in step by hand-written
 * "mirrors runSavedRunDiff" comments, and a comment does not fail CI. This test does.</p>
 *
 * <p><b>What it cannot do.</b> This is a structural gate, not a differential one: it proves the two
 * paths open the same stages and route every verification pass through the same seam, not that they
 * produce identical output for identical input. A true differential test has to drive one request
 * through both entry points, and cannot be written while {@code runSavedRunDiff.groovy}'s pipeline
 * steps are script-local closures that nothing outside that script can call (design
 * {@code 2026-08-26-reconciliation-pipeline-unification} §5). Step 7 of that design is what unblocks
 * it; until then this gate is the enforcement.</p>
 *
 * <p>{@code SftpAutomationSupport} is deliberately out of scope — a third input mode with no
 * interactive counterpart (§4 of the same design).</p>
 */
class RunPathParityGateTest {

    private static final String MANUAL_PATH = "facade/reconciliation/runSavedRunDiff.groovy"
    private static final String SCHEDULED_PATH = "reconciliation/automation/AutomationExecutionSupport.groovy"

    /**
     * The stage vocabulary of a reconciliation run. Both paths must draw from exactly this set — a
     * stage added to one orchestrator alone is the drift this gate exists to stop, and a stage added
     * to both is a deliberate contract change that updates this list in the same commit.
     *
     * <p>The three VERIFY stages are deliberately absent: each pass (missing-diff, and since DAR-OPS-029 S99-044 return
     * presence and exchange pairs) opens its own step inside {@code RunVerificationSupport}, the shared seam both
     * orchestrators call, so neither orchestrator file opens it. It belongs to the run's stage vocabulary, not to this set, which only ever
     * describes what the two orchestrators open directly.</p>
     *
     * <p>The retired STAGE_VERIFY is absent too. The three passes shared it until a run performing
     * two of them rendered two byte-identical timeline rows; each owns a code now, and the retired
     * one survives only so rows recorded before the split still resolve to a label.</p>
     */
    private static final Set<String> CANONICAL_STAGES = [
            "STAGE_RESOLVE", "STAGE_EXTRACT_FILE1", "STAGE_EXTRACT_FILE2",
            "STAGE_COMPARE", "STAGE_WRITE_OUTPUT", "STAGE_NOTIFY",
    ].toSet()

    /**
     * Stages only the scheduler opens, each because its input exists on that path alone. Keep it to that: a stage that
     * could run from the Run button belongs in CANONICAL_STAGES and in both paths.
     *
     * <p>STAGE_RECHECK (DAR-BE-156): an audit's older open findings, read again by key. It needs the audit automation
     * and its window (AuditRecheckSupport.appendOpenFindings), and an audit only ever runs as an automation; the Run
     * button runs a saved run with no audit behind it.</p>
     */
    private static final Set<String> SCHEDULED_ONLY_STAGES = ["STAGE_RECHECK"].toSet()

    /**
     * The shared entry points that START a verification pass. Every one must be reached from BOTH
     * orchestrators: a fourth pass wired into one path only is exactly how the missing-diff pass
     * spent a year being interactive-only.
     */
    private static final List<String> SHARED_PASS_SEAMS = [
            "RunVerificationSupport.runMissingDiffPass",
            // S99-044: each pass's prepare, step, run, settle and fold, as runMissingDiffPass's.
            "RunVerificationSupport.runExchangePairPass",
            "RunVerificationSupport.runReturnPresencePass",
            // DAR-UI-044: conclusions are written on the scheduled path too, or an automation's result
            // page silently shows the legacy view while the same run from the UI shows conclusions.
            "RunConclusionStep.runIfConfigured",
    ]

    /**
     * Pass implementations neither orchestrator may call directly — they are reached through
     * {@code RunVerificationSupport}, which is what makes the two paths run the same code rather
     * than two copies of it. Zero on both sides, and it must stay zero.
     */
    private static final List<String> IMPLEMENTATION_CLASSES = [
            "MissingDiffVerificationSupport", "ExchangePairVerificationSupport",
            "ReturnPresenceVerificationSupport",
    ]

    /**
     * S99-044: the steps inside a shared pass. An orchestrator that calls one directly has rebuilt the pass's step block,
     * settle and fold beside it, the copy S99-044 deleted.
     */
    private static final List<String> PASS_INTERNALS = ["RunVerificationSupport.prepareExchangePairPass",
            "RunVerificationSupport.prepareReturnPresencePass", "RunVerificationSupport.prepareMissingDiffPass"]

    private static final Pattern STAGE_SITE =
            Pattern.compile(/beginStep\([^)]*RunObservability\.(STAGE_\w+)/)

    @Test
    void bothRunPathsOpenTheSameStages() {
        Set<String> manual = stagesOpenedBy(MANUAL_PATH)
        Set<String> scheduledAll = stagesOpenedBy(SCHEDULED_PATH)
        assertTrue(scheduledAll.containsAll(SCHEDULED_ONLY_STAGES),
                "SCHEDULED_ONLY_STAGES names a stage the scheduler no longer opens: ${SCHEDULED_ONLY_STAGES - scheduledAll}. Drop it from the list.")
        assertTrue(manual.intersect(SCHEDULED_ONLY_STAGES).isEmpty(),
                "The manual path opens ${manual.intersect(SCHEDULED_ONLY_STAGES)}, so it is not scheduled-only: move it to CANONICAL_STAGES.")
        Set<String> scheduled = scheduledAll - SCHEDULED_ONLY_STAGES

        assertEquals(manual, scheduled,
                "The two run paths no longer record the same stages. A triggered run must execute the " +
                        "same process as a manual one — manual-only: ${manual - scheduled}, " +
                        "scheduled-only: ${scheduled - manual}. Add the stage to both paths (through a " +
                        "shared seam), or if the contract genuinely changed, update CANONICAL_STAGES here.")
        assertEquals(CANONICAL_STAGES, manual,
                "The run stage vocabulary changed. Both paths agree, so this is a deliberate contract " +
                        "change: update CANONICAL_STAGES in the same commit, and check the UI's stage " +
                        "labels (reconciliationDisplay.ts) know the new stage.")
    }

    @Test
    void everyVerificationPassIsReachableFromBothRunPaths() {
        String manual = codeOf(MANUAL_PATH)
        String scheduled = codeOf(SCHEDULED_PATH)

        SHARED_PASS_SEAMS.each { String seam ->
            assertTrue(manual.contains(seam),
                    "The manual path never calls ${seam}. A verification pass wired into one path only " +
                            "is the 100616 divergence again.")
            assertTrue(scheduled.contains(seam),
                    "The scheduled path never calls ${seam}. A verification pass wired into one path " +
                            "only is the 100616 divergence again.")
        }
    }

    /**
     * DAR-BE-346: the free returns grading (grace, pre-window, cancelled-order refunds) runs before the
     * paid missing-diff lookup on both paths. The lookup's share gate otherwise counts rows grading
     * clears a moment later, and a run that ends with 0 missing still warns "above the 5% a lookup is
     * for" (RAILS EU 2 of 5, 2026-11-24). Call sites, not seams: the manual path defines its passes as
     * closures above where it calls them.
     */
    @Test
    void freeReturnsGradingRunsBeforeThePaidMissingLookup() {
        [[MANUAL_PATH, ~/runReturnPresenceVerificationPass\(serviceResult\b/, ~/runVerificationPass\(serviceResult\b/],
         [SCHEDULED_PATH, ~/verifyReturnPresenceIfEnabled\(ec\b/, ~/verifyMissingDiffsIfEnabled\(ec\b/]].each { List row ->
            String rel = row[0]
            String code = codeOf(rel)
            Matcher grading = ((Pattern) row[1]).matcher(code)
            Matcher lookup = ((Pattern) row[2]).matcher(code)
            assertTrue(grading.find(), "${rel}: the returns grading call site was not found; if it moved, update this gate")
            assertTrue(lookup.find(), "${rel}: the missing-diff lookup call site was not found; if it moved, update this gate")
            assertTrue(grading.start() < lookup.start(),
                    "${rel} calls the paid missing-diff lookup before the free returns grading, so its share gate counts rows grading clears")
            assertFalse(grading.find() || lookup.find(), "${rel}: more than one call site matched; anchor this gate again")
        }
    }

    @Test
    void theLookupShareGateReachesOnlyAnAuditsWindows() {
        // DAR-BE-351 (Aditi: "The 5% rule is mainly for audits. Since they do large extracts"). The scheduled path says
        // whether the window is an audit's, the shared pass forwards it, and a manual run never claims to be one.
        String scheduled = methodBody(codeOf(SCHEDULED_PATH), "verifyMissingDiffsIfEnabled")
        assertTrue((scheduled =~ /auditRun\s*:\s*window\?\.auditReportDate\s*!=\s*null/).find(),
                "${SCHEDULED_PATH}: verifyMissingDiffsIfEnabled must pass auditRun from the window's audit date")
        assertTrue((codeOf("facade/reconciliation/RunVerificationSupport.groovy") =~ /auditRun\s*:\s*args\?\.get\("auditRun"\)\s*==\s*true/).find(),
                "RunVerificationSupport must forward auditRun to MissingDiffVerificationSupport")
        assertFalse(codeOf(MANUAL_PATH).contains("auditRun"), "${MANUAL_PATH}: a manual run is never an audit's window")
    }

    @Test
    void bothRunPathsOwnTheSameNumberOfVerifyStepSites() {
        // Counts every verify stage, not one named code: the passes each own a code now, and a gate
        // pinned to a single name goes hollow the moment another one is added. Equal counts, not a
        // fixed number — the number may fall as passes move behind the shared seam, but it may
        // never fall on one side alone.
        int manual = countOf(MANUAL_PATH, STAGE_SITE) { String stage -> stage.startsWith("STAGE_VERIFY") }
        int scheduled = countOf(SCHEDULED_PATH, STAGE_SITE) { String stage -> stage.startsWith("STAGE_VERIFY") }

        assertEquals(manual, scheduled,
                "The two paths open a different number of VERIFY steps (manual ${manual}, scheduled " +
                        "${scheduled}), so one of them runs a verification pass the other does not.")
    }

    @Test
    void neitherRunPathCallsAVerificationImplementationDirectly() {
        [MANUAL_PATH, SCHEDULED_PATH].each { String rel ->
            String source = codeOf(rel)
            PASS_INTERNALS.each { String step ->
                assertEquals(0, source.count(step), "${rel} calls ${step} directly; call its run...Pass instead")
            }
            IMPLEMENTATION_CLASSES.each { String impl ->
                int hits = source.count("${impl}.")
                assertEquals(0, hits,
                        "${rel} calls ${impl} directly (${hits} site(s)). Route it through " +
                                "RunVerificationSupport instead — a pass invoked from an orchestrator is a " +
                                "copy that only that path runs, which is what this gate exists to prevent.")
            }
        }
    }

    /**
     * DAR-BE-318 plan 3a: both live extract dispatchers offer the side to the kept copy, through the one shared seam,
     * before their live call. One path alone would serve a manual run from the copy and read the scheduled one live (or
     * the other way round), and the same window would compare different data.
     */
    @Test
    void bothExtractDispatchersOfferTheSideToTheKeptCopy() {
        Pattern seam = ~/KeptCopySupport\s*\.\s*sliceIfServed\s*\(\s*ec\s*,\s*KeptCopySupport\s*\.\s*side\s*\(/
        // [file, the dispatcher method, its live call], each matched only inside that method's own body
        [["facade/reconciliation/RunExtractSupport.groovy", "extractApiSource",
          ~/serviceCaller\s*\.\s*call\s*\(\s*extractServiceName\s*,\s*extractParams\s*\)/],
         [SCHEDULED_PATH, "callConfiguredSourceExtractor",
          ~/def\s+call\s*=\s*ec\s*\.\s*service\s*\.\s*sync\s*\(\s*\)\s*\.\s*name\s*\(\s*serviceName\s*\)/]].each { List row ->
            String rel = row[0], method = row[1]
            String body = methodBody(codeOf(rel), method)
            Matcher offered = seam.matcher(body)
            assertTrue(offered.find(), "${rel} ${method} must offer its side to the kept copy, through KeptCopySupport.sliceIfServed")
            int offeredAt = offered.start()
            assertFalse(offered.find(), "${rel} ${method} must offer its side to the kept copy exactly once")
            Matcher live = ((Pattern) row[2]).matcher(body)
            assertTrue(live.find(), "${rel} ${method}: its live call was not found; if it changed, update this gate")
            assertTrue(offeredAt < live.start(), "${rel} ${method} must offer the side to the kept copy before its live call")
            assertFalse(live.find(), "${rel} ${method}: more than one live call matched; anchor this gate again")
        }
    }

    /**
     * DAR-OPS-029 S99-218 (OC19, caa84abe). A manual run that compared nothing closes like a scheduled one: both paths decide
     * the close with AutomationNoDataJudge.closeFinding (the manual one through ReconciliationSavedRunSupport.manualNoDataClose,
     * asked from each of its four compare branches: evaluate and compare, before and after Keep), the manual path keeps no
     * rule of its own (the pre-OC19 bare recordCount check), and it tells a no-data run through notifySavedRunNoData.
     */
    @Test
    void aRunThatComparedNothingClosesThroughTheSameJudgeOnBothPaths() {
        Pattern judge = ~/AutomationNoDataJudge\s*\.\s*closeFinding\s*\(/
        assertTrue(judge.matcher(codeOf(SCHEDULED_PATH)).find(), "${SCHEDULED_PATH} must decide a no-data close with AutomationNoDataJudge.closeFinding")
        String bridge = methodBody(codeOf("facade/reconciliation/ReconciliationSavedRunSupport.groovy"), "manualNoDataClose")
        assertTrue(judge.matcher(bridge).find(), "manualNoDataClose must ask the scheduler's AutomationNoDataJudge.closeFinding")
        String manual = codeOf(MANUAL_PATH)
        assertEquals(4, (manual =~ /ReconciliationSavedRunSupport\s*\.\s*manualNoDataClose\s*\(/).size(),
                "${MANUAL_PATH}: each compare branch (evaluate and compare, before and after Keep) asks manualNoDataClose")
        assertFalse((manual =~ /obsNoData\s*=\s*[^\n]*recordCount\s*==\s*0/).find(),
                "${MANUAL_PATH}: a bare recordCount check is the manual-only rule OC19 replaced")
        assertTrue((manual =~ /notifySavedRunNoData\s*\(/).find(), "${MANUAL_PATH} must tell a no-data run through notifySavedRunNoData")
    }

    /** The text of {@code method}'s body: from its declaration to the next method declaration at class level. */
    private static String methodBody(String code, String method) {
        Matcher decl = Pattern.compile(/(?m)^    (?:(?:public|protected|private|static)\s+)+[\w<>,\[\]\s]+\s+${method}\s*\(/).matcher(code)
        assertTrue(decl.find(), "Could not find the declaration of ${method}; if it moved, update this gate.")
        Matcher next = Pattern.compile(/(?m)^    (?:(?:public|protected|private|static)\s+)+[\w<>,\[\]\s]+\s+\w+\s*\(/).matcher(code)
        int end = next.find(decl.end()) ? next.start() : code.length()
        return code.substring(decl.start(), end)
    }

    // -------------------------------------------------------------------------------------------

    private static Path srcRoot() {
        Path root = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/src/main/groovy/darpan")
        assertTrue(Files.exists(root), "Could not locate the darpan src root at ${root}")
        return root
    }

    private static String sourceOf(String relativePath) {
        Path p = srcRoot().resolve(relativePath)
        assertTrue(Files.exists(p), "Could not locate ${relativePath} — if it moved, update this gate.")
        return p.toFile().text
    }

    /**
     * The file with its comments removed.
     *
     * <p>Not a nicety — matching raw source is how this gate failed its own first test. Renaming the
     * scheduled path's call to {@code prepareExchangePairPass} while leaving the javadoc above it
     * intact left the gate green: the seam was still <em>mentioned</em>, just no longer
     * <em>called</em>. A parity gate satisfied by a comment is precisely the mechanism it replaces —
     * these two files were "kept in sync" by comments for a year.</p>
     */
    private static String codeOf(String relativePath) {
        return sourceOf(relativePath)
                .replaceAll(/(?s)\/\*.*?\*\//, " ")
                .split("\n")
                .findAll { String line -> !line.trim().startsWith("//") }
                .join("\n")
    }

    private static Set<String> stagesOpenedBy(String relativePath) {
        Set<String> stages = new TreeSet<>()
        Matcher m = STAGE_SITE.matcher(codeOf(relativePath))
        while (m.find()) stages.add(m.group(1))
        return stages
    }

    private static int countOf(String relativePath, Pattern pattern, Closure<Boolean> accept) {
        int count = 0
        Matcher m = pattern.matcher(codeOf(relativePath))
        while (m.find()) if (accept.call(m.group(1))) count++
        return count
    }
}
