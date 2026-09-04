package darpan.architecture

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
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
     * <p>STAGE_VERIFY_MISSING is deliberately absent: the missing-diff pass opens its own step inside
     * {@code RunVerificationSupport}, the shared seam both orchestrators call, so neither orchestrator
     * file opens it. It belongs to the run's stage vocabulary, not to this set, which only ever
     * describes what the two orchestrators open directly.</p>
     *
     * <p>The retired STAGE_VERIFY is absent too. The three passes shared it until a run performing
     * two of them rendered two byte-identical timeline rows; each owns a code now, and the retired
     * one survives only so rows recorded before the split still resolve to a label.</p>
     */
    private static final Set<String> CANONICAL_STAGES = [
            "STAGE_RESOLVE", "STAGE_EXTRACT_FILE1", "STAGE_EXTRACT_FILE2",
            "STAGE_COMPARE", "STAGE_VERIFY_EXCHANGE", "STAGE_VERIFY_RETURNS",
            "STAGE_WRITE_OUTPUT", "STAGE_NOTIFY",
    ].toSet()

    /**
     * The shared entry points that START a verification pass. Every one must be reached from BOTH
     * orchestrators: a fourth pass wired into one path only is exactly how the missing-diff pass
     * spent a year being interactive-only.
     */
    private static final List<String> SHARED_PASS_SEAMS = [
            "RunVerificationSupport.runMissingDiffPass",
            "RunVerificationSupport.prepareExchangePairPass",
            "RunVerificationSupport.prepareReturnPresencePass",
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

    private static final Pattern STAGE_SITE =
            Pattern.compile(/beginStep\([^)]*RunObservability\.(STAGE_\w+)/)

    @Test
    void bothRunPathsOpenTheSameStages() {
        Set<String> manual = stagesOpenedBy(MANUAL_PATH)
        Set<String> scheduled = stagesOpenedBy(SCHEDULED_PATH)

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
            IMPLEMENTATION_CLASSES.each { String impl ->
                int hits = source.count("${impl}.")
                assertEquals(0, hits,
                        "${rel} calls ${impl} directly (${hits} site(s)). Route it through " +
                                "RunVerificationSupport instead — a pass invoked from an orchestrator is a " +
                                "copy that only that path runs, which is what this gate exists to prevent.")
            }
        }
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
