package darpan.audit

import darpan.reconciliation.core.ReconciliationServices
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-369. "Re-check all" plans one verify run per automation, not one per finding: a run re-judges every check of its
 * automation for the keys it is given, and each side reads them by their first key field, so the batches split there.
 * Pure; no Moqui boot.
 */
class AuditFindingsVerifyPlanTests {

    private static Map f(String id, String check, String key) { [findingId: id, checkId: check, recordKey: key] }

    @Test
    void findingsOfChecksSharingAnAutomationGoInOneRun() {
        Map plan = AuditFindingsVerifySupport.plan([f("F-1", "C_R2", "1001"), f("F-2", "C_R3", "1001"), f("F-3", "C_R5", "1002")],
                [C_R2: "A1", C_R3: "A1", C_R5: "A1"], 1000)
        assertEquals([[automationId: "A1", recordKeys: ["1001", "1002"]]], plan.runs, "one run, each key once")
        assertEquals([], plan.skipped)
    }

    @Test
    void eachAutomationGetsItsOwnRun() {
        Map plan = AuditFindingsVerifySupport.plan([f("F-1", "C1", "9"), f("F-2", "C2", "8")], [C1: "A1", C2: "A2"], 1000)
        assertEquals([[automationId: "A1", recordKeys: ["9"]], [automationId: "A2", recordKeys: ["8"]]], plan.runs)
    }

    // An extractor reads the first key field (the order); every line of one order rides in the same batch.
    @Test
    void batchesSplitOnTheFirstKeyFieldAndKeepAnOrdersLinesTogether() {
        List<Map> findings = [f("F-1", "C", k("100", "01")), f("F-2", "C", k("100", "02")), f("F-3", "C", k("101", "01")),
                              f("F-4", "C", k("102", "01"))]
        Map plan = AuditFindingsVerifySupport.plan(findings, [C: "A"], 2)
        assertEquals([[automationId: "A", recordKeys: [k("100", "01"), k("100", "02"), k("101", "01")]],
                      [automationId: "A", recordKeys: [k("102", "01")]]], plan.runs)
    }

    /** A composite record key, as the ledger stores it. */
    private static String k(String... fields) { fields.join(ReconciliationServices.COMPOSITE_KEY_DELIMITER) }

    @Test
    void aFindingWhoseCheckHasNoAutomationIsSkippedNotDropped() {
        Map plan = AuditFindingsVerifySupport.plan([f("F-1", "C1", "9"), f("F-2", "C2", "8")], [C1: "A1"], 1000)
        assertEquals([[automationId: "A1", recordKeys: ["9"]]], plan.runs)
        assertEquals(["F-2"], plan.skipped)
    }

    @Test
    void nothingOpenPlansNothing() {
        assertEquals([runs: [], skipped: []], AuditFindingsVerifySupport.plan([], [:], 1000))
    }
}
