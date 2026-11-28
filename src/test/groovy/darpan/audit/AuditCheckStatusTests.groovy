package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-OPS-029 S99-066. The one rule record#CheckRun writes and a verify answers (AuditAutomationSupport.verifyStatus):
 * every read is validated before any status, so a reason or a failed read never lets a bad read through.
 */
class AuditCheckStatusTests {

    private static final Map<String, Map> SYSTEMS = [
            OMS_ORDERS_GQL : [enumTypeId: "DarpanSystemSource", parentEnumId: "OMS"],
            NETSUITE       : [enumTypeId: "DarpanSystemSource", parentEnumId: null],
            NOT_A_SOURCE   : [enumTypeId: "SomethingElse", parentEnumId: null]]

    private static Map read(String system, String status, String reason = null) {
        return [systemEnumId: system, statusEnumId: status, reason: reason]
    }

    private static Map derive(List<Map> reads, String notRunReason = null) {
        return AuditCheckStatus.derive(reads, notRunReason, SYSTEMS, "C1")
    }

    @Test
    void everyReadCompleteRanWithNoReason() {
        assertEquals([checkStatusEnumId: "AUDCS_RAN", reason: null],
                derive([read("OMS_ORDERS_GQL", "AUDSR_COMPLETE"), read("NETSUITE", "AUDSR_COMPLETE")]))
    }

    @Test
    void onePartialReadMakesItPartialNamedByItsRootSystem() {
        assertEquals([checkStatusEnumId: "AUDCS_PARTIAL", reason: "The OMS read was partial: no reason was given."],
                derive([read("OMS_ORDERS_GQL", "AUDSR_PARTIAL"), read("NETSUITE", "AUDSR_COMPLETE")]))
    }

    @Test
    void everyReadFailedIsNotRunWithEachReadsReason() {
        assertEquals([checkStatusEnumId: "AUDCS_NOT_RUN", reason: "The OMS read failed: 401. The NetSuite read failed: timeout."],
                derive([read("OMS_ORDERS_GQL", "AUDSR_FAILED", "401"), read("NETSUITE", "AUDSR_FAILED", "timeout")]))
    }

    @Test
    void noReadOrAReasonIsNotRun() {
        assertEquals([checkStatusEnumId: "AUDCS_NOT_RUN", reason: "The check recorded no source read."], derive([]))
        assertEquals([checkStatusEnumId: "AUDCS_NOT_RUN", reason: "The check recorded no source read."], derive(null))
        assertEquals([checkStatusEnumId: "AUDCS_NOT_RUN", reason: "Held for peak hours"],
                derive([read("NETSUITE", "AUDSR_COMPLETE")], "Held for peak hours"))
    }

    @Test
    void aBadReadIsRefusedEvenWithAReasonOrBesideAFailedOne() {
        assertEquals([error: "Unknown system NOT_A_SOURCE on a source read of check C1"],
                derive([read("NOT_A_SOURCE", "AUDSR_COMPLETE")], "Held for peak hours"))
        assertEquals([error: "Unknown system GONE on a source read of check C1"],
                derive([read("NETSUITE", "AUDSR_FAILED"), read("GONE", "AUDSR_FAILED")]), "a system with no Enumeration row")
        assertEquals([error: "Source read status AUDSR_DONE is not an AuditSourceRead status"],
                derive([read("NETSUITE", "AUDSR_DONE")]))
    }
}
