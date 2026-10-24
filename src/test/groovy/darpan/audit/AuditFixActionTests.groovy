package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-120. The fix a finding shows is its cause's (Aditi 2026-10-23: "conclusions that truely help the user"): a
 * return whose order is backordered is fixed by fulfilling that order in NetSuite, not by re-running the return sync.
 * A cause's fix may name a fact its own conditions read, by field, filled from the finding's evidence.
 */
class AuditFixActionTests {

    private static final List<Map> RULES = [
            [sequenceNum: 10, conclusionEnumId: "CONC_ORDER_BACKORDERED",
             fixActionTemplate: "Fulfil {orderInNetsuite.tranId} in NetSuite; return {recordKey} syncs once its order is fulfilled."],
            [sequenceNum: 20, conclusionEnumId: "CONC_RETURN_NOT_IN_NS", fixActionTemplate: null]]
    private static final List<Map> CONDITIONS = [
            [sequenceNum: 10, fieldExpression: "statusId", checkLabel: "The return is completed"],
            [sequenceNum: 10, fieldExpression: "orderInNetsuite.tranId", checkLabel: "NetSuite holds its order with units backordered"]]
    private static final List<Map> EVIDENCE = [
            [label: "The return is completed", checkValue: "RETURN_COMPLETED"],
            [label: "NetSuite holds its order with units backordered", checkValue: "SO7323719"]]

    @Test
    void theCausesFixNamesTheFactItsConditionsRead() {
        assertEquals("Fulfil SO7323719 in NetSuite; return M262129 syncs once its order is fulfilled.",
                AuditReportAssembler.causeFix(RULES, CONDITIONS, "CONC_ORDER_BACKORDERED", EVIDENCE, [recordKey: "M262129"]))
    }

    @Test
    void aCauseWithNoFixOfItsOwnLeavesTheChecksFix() {
        assertNull(AuditReportAssembler.causeFix(RULES, CONDITIONS, "CONC_RETURN_NOT_IN_NS", EVIDENCE, [:]))
        assertNull(AuditReportAssembler.causeFix(RULES, CONDITIONS, null, EVIDENCE, [:]))
        assertEquals("Fulfil {orderInNetsuite.tranId} in NetSuite; return M1 syncs once its order is fulfilled.",
                AuditReportAssembler.causeFix(RULES, CONDITIONS, "CONC_ORDER_BACKORDERED", [], [recordKey: "M1"]),
                "a fact the finding does not carry stays named, never guessed")
    }
}
