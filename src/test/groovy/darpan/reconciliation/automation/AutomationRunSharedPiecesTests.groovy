package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S99-047. The API range runner (AutomationExecutionSupport) and the SFTP poller (SftpAutomationSupport) each kept
 * the completed-run notice's payload and the source's tenant check; both now come from AutomationRuntimeSupport. Pure: no Moqui.
 */
class AutomationRunSharedPiecesTests {

    private static final Map AUTOMATION = [automationId: "AUT_1", automationName: "Nightly orders", savedRunId: "SR_1",
                                           reconciliationRunId: "RUN_1", companyUserGroupId: "KREWE", chatSpaceId: "spaces/X"]

    @Test
    void theCompletedRunNoticeCarriesTheRunItsSidesAndItsCounts() {
        Map payload = AutomationRuntimeSupport.runCompletedPayload(AUTOMATION, [systemEnumId: "OMS_GQL"], [systemEnumId: "SHOPIFY"],
                "RR_1", "runs/r.json",
                [file1Label: " HotWax ", file2Label: "Shopify", ruleDifferenceCount: 4, processingWarnings: ["w"], ruleDifferenceFields: ["sku"]],
                [differenceCount: 7, onlyInFile1Count: 2, onlyInFile2Count: 1, statusEnumId: "AUT_STAT_SUCCESS"])
        assertEquals([reconciliationRunResultId: "RR_1", runName: "Nightly orders", savedRunId: "SR_1", reconciliationRunId: "RUN_1",
                      companyUserGroupId: "KREWE", chatSpaceId: "spaces/X", resultDataManagerPath: "runs/r.json",
                      file1SystemEnumId: "OMS_GQL", file2SystemEnumId: "SHOPIFY", file1SystemLabel: "HotWax", file2SystemLabel: "Shopify",
                      differenceCount: 7, onlyInFile1Count: 2, onlyInFile2Count: 1, ruleDifferenceCount: 4, statusEnumId: "AUT_STAT_SUCCESS",
                      processingWarnings: ["w"]], payload)
        assertFalse(payload.containsKey("ruleDifferenceFields"), "the field list is the API path's addition, never the SFTP notice's")
        assertEquals([], AutomationRuntimeSupport.runCompletedPayload(AUTOMATION, null, null, "RR_1", null, [:], [:]).processingWarnings,
                "an evaluate run (one side) and no warnings")
    }

    @Test
    void aSourceMustBeThereAndBelongToTheAutomationsTenant() {
        def missing = assertThrows(IllegalArgumentException) { AutomationRuntimeSupport.requireAutomationSource(AUTOMATION, null, "FILE_1") }
        assertEquals("Automation AUT_1 is missing FILE_1 source", missing.message)
        def foreign = assertThrows(IllegalArgumentException) {
            AutomationRuntimeSupport.requireAutomationSource(AUTOMATION, [companyUserGroupId: "GORJANA"], "FILE_2")
        }
        assertEquals("Automation AUT_1 FILE_2 source belongs to tenant GORJANA, not KREWE", foreign.message)
        Map own = [companyUserGroupId: "KREWE"]
        assertTrue(AutomationRuntimeSupport.requireAutomationSource(AUTOMATION, own, "FILE_1").is(own))
        Map unstamped = [sourceTypeEnumId: "AUT_SRC_API"]
        assertTrue(AutomationRuntimeSupport.requireAutomationSource(AUTOMATION, unstamped, "FILE_1").is(unstamped),
                "a source row with no tenant passes, as both checks let it")
    }
}
