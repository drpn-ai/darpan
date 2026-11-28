package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-120. The fix a finding shows is its cause's (Aditi 2026-10-23: "conclusions that truely help the user"): a
 * return whose order is backordered is fixed by fulfilling that order in NetSuite, not by re-running the return sync.
 * A cause's fix may name a fact its own conditions read, by field, filled from the finding's evidence.
 */
class AuditFixActionTests {

    private static final String FIX = "Fulfil {orderInNetsuite.tranId} in NetSuite; return {recordKey} syncs once its order is fulfilled."
    private static final List<Map> CONDITIONS = [
            [sequenceNum: 10, fieldExpression: "statusId", checkLabel: "The return is completed"],
            [sequenceNum: 10, fieldExpression: "orderInNetsuite.tranId", checkLabel: "NetSuite holds its order with units backordered"]]
    private static final List<Map> EVIDENCE = [
            [label: "The return is completed", checkValue: "RETURN_COMPLETED"],
            [label: "NetSuite holds its order with units backordered", checkValue: "SO7323719"]]

    @Test
    void theCausesFixNamesTheFactItsConditionsRead() {
        assertEquals("Fulfil SO7323719 in NetSuite; return M262129 syncs once its order is fulfilled.",
                AuditFindingDocument.causeFix(FIX, CONDITIONS, EVIDENCE, [recordKey: "M262129"]))
    }

    // DAR-BE-294 (gorjana R10 F-126473 read "Re-route order {orderName}"): a field the finding's record carries fills
    // the fix though no condition reads it; a condition's evidence and the check's values still come first.
    @Test
    void aFieldTheFindingsRecordCarriesFillsTheFix() {
        List<Map> rows = [[recordType: "order", recordId: "SO7373140",
                           recordData: '{"orderName":"EXC-#GOR196892531-1","tranId":"SO7373140","lines":[1],"omsOrder":{"orderId":"M1"}}'],
                          [recordType: "order", recordId: "M1213406", recordData: '{"orderName":"ignored: first record wins"}']]
        Map<String, String> fromRecords = AuditFindingDocument.recordFieldValues(rows)
        assertEquals([orderName: "EXC-#GOR196892531-1", tranId: "SO7373140"], fromRecords,
                "scalar fields only, first record wins")
        assertEquals("Re-route order EXC-#GOR196892531-1 (SO7323719) for M262129.",
                AuditFindingDocument.causeFix("Re-route order {orderName} ({orderInNetsuite.tranId}) for {recordKey}.",
                        CONDITIONS, EVIDENCE, [recordKey: "M262129"], fromRecords))
        assertEquals("Fulfil SO7323719", AuditFindingDocument.causeFix("Fulfil {orderInNetsuite.tranId}", CONDITIONS, EVIDENCE,
                [:], ["orderInNetsuite.tranId": "from the record"]), "a condition's evidence outranks the record")
    }

    @Test
    void aFactTheFindingDoesNotCarryStaysNamed() {
        assertEquals("Fulfil {orderInNetsuite.tranId} in NetSuite; return M1 syncs once its order is fulfilled.",
                AuditFindingDocument.causeFix(FIX, CONDITIONS, [], [recordKey: "M1"]),
                "a fact the finding does not carry stays named, never guessed")
    }
}
