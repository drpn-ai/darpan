package darpan.reconciliation.flowchart

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-050. A lifecycle is a start record and its stages in order; the question tree the walker runs is
 * compiled from that list, and every place a record can stop is one question's findings, so every place opens.
 */
class LifecycleStageCompilerTests {

    static Map byKey(List<Map> qs, String key) { qs.find { it.key == key } }

    @Test
    void twoStagesGiveFourPlacesEachItsOwnQuestion() {
        List<Map> qs = LifecycleStageCompiler.compile("SalesOrd", ["CustInvc", "ItemShip"])
        assertEquals(["start", "s0", "x0", "s1", "done", "x0.not1", "s0.has1", "s0.not1"], qs*.key)
        assertEquals(["START", "SalesOrd", "Sales orders"], [qs[0].questionRole, qs[0].recordType, qs[0].runName])

        // The stages, each "is it missing?" on the previous one's yes.
        assertEquals(["start", "YES", "CustInvc", "ABSENT", null], byKey(qs, "s0").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        // Between them, the records an exception let past billing without an invoice go their own way: the next stage
        // sees only records that were billed.
        assertEquals(["s0", "YES", "EXCUSED", "CustInvc", "EXCUSED", null, "Invoice not expected"],
                byKey(qs, "x0").with { [it.parentKey, it.parentBranch, it.questionRole, it.stageRecordType, it.check, it.noOutcomeLabel, it.ruleLabel] })
        assertEquals(["x0", "YES", "ItemShip", "ABSENT", "Billed, never shipped"], byKey(qs, "s1").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        // Complete: beside the last stage, the records that DO have it.
        assertEquals(["x0", "YES", "ItemShip", "PRESENT", "Billed and shipped"], byKey(qs, "done").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        // The excused records stop at the first later stage they are missing, in a place of their own.
        assertEquals(["x0", "NO", "ItemShip", "ABSENT", "Invoice not expected, never shipped"],
                byKey(qs, "x0.not1").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        // Under "never billed": skipped ahead to shipping, or stopped.
        assertEquals(["s0", "NO", "ItemShip", "PRESENT", "Shipped, never billed"], byKey(qs, "s0.has1").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        assertEquals(["s0", "NO", "ItemShip", "ABSENT", "Never billed, never shipped"], byKey(qs, "s0.not1").with { [it.parentKey, it.parentBranch, it.stageRecordType, it.check, it.noOutcomeLabel] })
        assertTrue(qs.every { it.yesOutcomeLabel == null }, "every place is a question's findings, never its yes")
    }

    @Test
    void everyQuestionWithNothingUnderItsNoIsAPlace() {
        List<Map> qs = LifecycleStageCompiler.compile("RtnAuth", ["ItemRcpt", "CustCred", "CustRfnd"])
        Set<String> continuesOnNo = qs.findAll { it.parentBranch == "NO" }*.parentKey as Set
        qs.findAll { it.questionRole != "START" }.each { Map q ->
            assertEquals(!continuesOnNo.contains(q.key), q.noOutcomeLabel != null,
                    "${q.key}: a no that ends is named, a no that continues is not")
        }
        assertEquals(15, qs.count { it.questionRole != "START" })
        assertEquals(["Credited, never refunded", "Received, credited and refunded", "Item receipt not expected, never credited",
                      "Item receipt not expected, never refunded", "Credit memo not expected, never refunded", "Credited, never received",
                      "Refunded, never received", "Never received, nothing after", "Refunded, never credited", "Received, never credited"],
                qs*.noOutcomeLabel.findAll { it })
        // A skip-ahead that passes a missing middle stage hangs from "is that missing too?", not from the stage.
        assertEquals(["s0.not1", "NO"], byKey(qs, "s0.has2").with { [it.parentKey, it.parentBranch] })
    }

    @Test
    void oneStageIsAStopAndAComplete() {
        List<Map> qs = LifecycleStageCompiler.compile("SalesOrd", ["CustInvc"])
        assertEquals(["start", "s0", "done"], qs*.key)
        assertEquals(["Never billed", "Billed"], [byKey(qs, "s0").noOutcomeLabel, byKey(qs, "done").noOutcomeLabel])
        assertEquals(["start", "start"], [byKey(qs, "s0").parentKey, byKey(qs, "done").parentKey])
    }

    @Test
    void theNameIsTheRecords() {
        assertEquals("Sales Orders", LifecycleStageCompiler.lifecycleName("SalesOrd"))
        assertEquals("Return Authorizations", LifecycleStageCompiler.lifecycleName("RtnAuth"))
    }

    // DAR-UI-052. A return is settled by a refund, an invoice or a replacement order, whichever it got: one stage that
    // names no NetSuite type and so can never be where a lifecycle starts.
    @Test
    void settledIsAStageOfItsOwnButNeverAStart() {
        List<Map> qs = LifecycleStageCompiler.compile("RtnAuth", ["ItemRcpt", "CustCred", "Settled"])
        assertTrue(qs*.noOutcomeLabel.containsAll(["Credited, never settled", "Received, credited and settled", "Settled, never credited"]),
                qs*.noOutcomeLabel.findAll { it }.toString())
        assertEquals("Settlement", LifecycleStageCompiler.words("Settled").name)
        assertTrue(LifecycleStageCompiler.isComposite("Settled"))
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("Settled", ["CustCred"]) }
    }

    @Test
    void unknownOrRepeatedRecordTypesAreRefused() {
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", []) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["ItemShip", "ItemShip"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["SalesOrd"]) }
        // Every value reaches SuiteQL by interpolation, so only known record types pass.
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["X' OR 1=1"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("Bogus", ["ItemShip"]) }
    }

    /** DAR-UI-050. The settings page draws a new order's places before it is saved, from the questions a save would make. */
    @Test
    void aPlanReadsBackAsTheQuestionsASaveWouldMake() {
        List<Map> qs = LifecycleStageCompiler.planQuestions("SalesOrd", ["ItemShip", "CustInvc"])
        assertEquals(LifecycleStageCompiler.compile("SalesOrd", ["ItemShip", "CustInvc"])*.key, qs*.reconciliationRunId)
        assertEquals((1..qs.size()).toList(), qs*.runSequence)
        assertTrue(qs.every { it.isActive == "Y" })
        // Reordered, the places are named for the new order: shipping is asked first.
        assertEquals(["s0", "NO", "PRESENT", "Billed, never shipped"],
                qs.find { it.reconciliationRunId == "s0.has1" }.with { [it.parentReconciliationRunId, it.parentBranch, it.check, it.noOutcomeLabel] })
        assertEquals("Never shipped, never billed", qs.find { it.reconciliationRunId == "s0.not1" }.noOutcomeLabel)
        assertEquals(["START", null], qs[0].with { [it.questionRole, it.parentReconciliationRunId] })
        assertEquals("EXCUSED", qs.find { it.reconciliationRunId == "x0" }.questionRole)
    }
}

