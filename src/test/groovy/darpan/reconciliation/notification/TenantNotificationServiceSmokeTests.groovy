package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantNotificationServiceSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-notification-service-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void buildRunCompletedPayloadUsesXmlServiceContract() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId           : "TENANT_A",
                        companyLabel                 : "Tenant A",
                        runName                      : "API Automation",
                        savedRunId                   : "RS_ORDER",
                        reconciliationRunResultId    : "RUN_RESULT_1",
                        resultDataManagerPath        : "reconciliation-runs/AUTO_API/20260501/result.json",
                        file1SystemLabel             : "SHOPIFY",
                        file2SystemLabel             : "OMS",
                        differenceCount              : 4,
                        onlyInFile1Count             : 1,
                        onlyInFile2Count             : 3,
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Darpan run completed: API Automation"))
        assertTrue(text.contains("Tenant: Tenant A"))
        assertTrue(text.contains("Result ID: RUN_RESULT_1"))
        assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_API%2F20260501%2Fresult.json?runName=API+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=OMS|Open run result>"))
        assertTrue(text.contains("Differences: 4"))
        assertTrue(text.contains("Only in SHOPIFY: 1"))
        assertTrue(text.contains("Only in OMS: 3"))
        assertEquals(7, text.readLines().size())
    }
}
