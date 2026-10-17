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

    @Test
    void unknownOrRepeatedRecordTypesAreRefused() {
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", []) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["ItemShip", "ItemShip"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["SalesOrd"]) }
        // Every value reaches SuiteQL by interpolation, so only known record types pass.
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["X' OR 1=1"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("Bogus", ["ItemShip"]) }
    }
}
