package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * A run that does not verify its differences must SAY SO, and say why.
 *
 * <p>Every gate in front of the missing-diff pass used to return before the STAGE_VERIFY_MISSING step was
 * opened — the kill switch, an unresolvable config id, a missing diff artifact — so all of them
 * looked identical from the outside: no VERIFY row at all. That is what let v1.5.0 ship a scheduled
 * pass that never ran (indistinguishable from the switch being off), and what made the 2026-08-27
 * production report "403 missing, no VERIFY step" unreadable without reading the source.</p>
 *
 * <p>These tests pin the decision AND its report, in the one place both run entry points call.</p>
 */
class VerificationSkipReasonTests {

    // ---- Recording fakes (no DB), same shape RunObservabilityWriteTest uses --------------------
    static class FakeVal {
        String entityName
        Map<String, Object> v = [:]
        FakeVal(String n) { entityName = n }
        def set(String k, Object val) { v[k] = val; this }
        Object get(String k) { v[k] }
        def setSequencedIdPrimary() {
            if (!v.reconciliationRunStepId) v.reconciliationRunStepId = "STEP_${++FakeEc.seq}".toString()
            this
        }
        def create() { this }
        def update() { this }
    }
    static class FakeFind {
        String entityName; FakeEc ec; Map<String, Object> cond = [:]
        FakeFind(String n, FakeEc e) { entityName = n; ec = e }
        FakeFind condition(String k, Object val) { cond[k] = val; this }
        FakeFind useCache(boolean b) { this }
        FakeFind disableAuthz() { this }
        FakeVal one() { ec.store.find { it.entityName == entityName && cond.every { c -> it.v[c.key] == c.value } } }
    }
    static class FakeEntity {
        FakeEc ec
        FakeEntity(FakeEc e) { ec = e }
        FakeVal makeValue(String n) { def x = new FakeVal(n); ec.store << x; x }
        FakeFind find(String n) { new FakeFind(n, ec) }
    }
    static class FakeTx { def runUseOrBegin(int t, String m, Closure work) { work.call() } }
    static class FakeUser { Timestamp nowTimestamp = new Timestamp(1_700_000_000_000L) }
    static class FakeMessage {
        boolean hasError() { false }
        List getErrors() { [] }
        void clearErrors() {}
    }
    static class FakeEc {
        static int seq = 0
        List<FakeVal> store = []
        FakeEntity entity = new FakeEntity(this)
        FakeTx transaction = new FakeTx()
        FakeUser user = new FakeUser()
        FakeMessage message = new FakeMessage()
    }

    private static Map serviceResultWithMissingRows() {
        return [differenceCount: 403L, missingInFile1Count: 403L, missingInFile2Count: 0L,
                file1Label: "Shopify", file2Label: "HotWax"] as Map
    }

    // ---- the decision -------------------------------------------------------------------------

    @Test
    void theKillSwitchIsReportedAsASkipReasonRatherThanSilence() {
        // The operator-facing half of the 2026-08-27 incident: a run with 403 unverified differences
        // and no VERIFY row could mean "switched off" or "the pass broke". It must say which.
        Map outcome = RunVerificationSupport.prepareMissingDiffPass([
                enabled: false, serviceResult: serviceResultWithMissingRows()])

        assertFalse(outcome.applies as boolean)
        assertEquals(RunVerificationSupport.SKIP_DISABLED, outcome.skipReason)
        assertTrue(((String) outcome.skipDetail).contains("verifyMissingDiffs"),
                "the reason must name the property an operator would check, got: ${outcome.skipDetail}")
    }

    @Test
    void aRunWithNothingMissingIsNotReportedAsSkipped() {
        // Nothing to recheck is not a skip; reporting one would put a permanent "unverified" note on
        // every clean run.
        Map outcome = RunVerificationSupport.prepareMissingDiffPass([
                enabled: true, serviceResult: [missingInFile1Count: 0L, missingInFile2Count: 0L]])

        assertFalse(outcome.applies as boolean)
        assertNull(outcome.skipReason)
    }

    @Test
    void aRuleExecutionFailureIsNotReportedAsSkipped() {
        // Partial diffs are preserved deliberately; the run already reports the rule failure.
        Map outcome = RunVerificationSupport.prepareMissingDiffPass([
                enabled: true,
                serviceResult: [ruleExecutionFailed: true, missingInFile1Count: 403L, missingInFile2Count: 0L]])

        assertFalse(outcome.applies as boolean)
        assertNull(outcome.skipReason)
    }

    @Test
    void anUnreadableDiffArtifactIsReported() {
        Map outcome = RunVerificationSupport.prepareMissingDiffPass([
                enabled: true, serviceResult: serviceResultWithMissingRows(), diffFile: null])

        assertFalse(outcome.applies as boolean)
        assertEquals(RunVerificationSupport.SKIP_NO_DIFF_FILE, outcome.skipReason)
    }

    @Test
    void aRunWhoseSidesOfferNoPointLookupAtAllIsNotReportedAsSkipped() {
        // Two uploaded CSVs have no source of record to recheck against — verification never applied
        // to that kind of run, and saying "not verified" on every one of them would make the report
        // meaningless exactly where it matters. Only a side that COULD be point-checked and was not
        // is worth a word (proved end to end in AutomationExecutionServiceSmokeTests, where
        // OMS_RETURNS declares a lookup service but no config id resolves).
        File diffFile = File.createTempFile("verify-skip", ".jsonl")
        diffFile.text = '{"id":"1"}\n'
        try {
            Map outcome = RunVerificationSupport.prepareMissingDiffPass([
                    ec: null, enabled: true, serviceResult: serviceResultWithMissingRows(),
                    diffFile: diffFile, file1Source: null, file2Source: null,
                    file1Label: "Shopify", file2Label: "HotWax"])

            assertFalse(outcome.applies as boolean)
            assertNull(outcome.skipReason,
                    "no side declares a point-lookup, so there is nothing that failed to happen")
        } finally {
            diffFile.delete()
        }
    }

    // ---- the report ---------------------------------------------------------------------------

    @Test
    void theSkipIsRecordedAsAVerifyStepOnTheRunTimeline() {
        // "Count the run steps" is how operators read these runs, so the absence of verification has
        // to be a row on that timeline, not an inference from its length.
        def ec = new FakeEc()
        Map serviceResult = serviceResultWithMissingRows()

        boolean ran = RunVerificationSupport.runMissingDiffPass([
                ec: ec, runResultId: "RUN_SKIP_1", stepCtx: [companyUserGroupId: "KREWE"],
                enabled: false, serviceResult: serviceResult])

        assertFalse(ran, "the pass did not run")
        FakeVal step = ec.store.find { it.v.stageCode == RunObservability.STAGE_VERIFY_MISSING }
        assertNotNull(step, "a run that skipped verification must still record a VERIFY step")
        assertEquals(RunObservability.STATUS_NO_DATA, step.v.statusEnumId)
        assertTrue(((String) step.v.errorMessage).toLowerCase().contains("not verified"),
                "the step must carry the reason, got: ${step.v.errorMessage}")
    }

    @Test
    void theSkipAlsoTravelsWithTheResultAsAProcessingWarning() {
        // The step row lives on the timeline; the warning is what reaches the artifact and the
        // notification, which is where anyone reading the counts elsewhere would see it.
        def ec = new FakeEc()
        Map serviceResult = serviceResultWithMissingRows()

        RunVerificationSupport.runMissingDiffPass([
                ec: ec, runResultId: "RUN_SKIP_2", stepCtx: [:],
                enabled: false, serviceResult: serviceResult])

        List warnings = (serviceResult.processingWarnings ?: []) as List
        assertEquals(1, warnings.size(), "exactly one warning, got: ${warnings}")
        assertTrue(((String) warnings.first()).contains("verifyMissingDiffs"))
    }
}
