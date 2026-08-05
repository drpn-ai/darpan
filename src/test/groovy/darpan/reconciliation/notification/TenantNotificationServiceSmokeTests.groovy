package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantNotificationServiceSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        // Provide an explicit app base URL for the test. In production this comes from
        // DARPAN_APP_BASE_URL env or darpan.app.baseUrl resource property; audit H11.2 removed the
        // hardcoded dev fallback (https://hotwax-darpan-dev.web.app) so the test must set its own.
        System.setProperty("darpan.app.baseUrl", "https://hotwax-darpan-dev.web.app")
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

    @Test
    void buildRunCompletedPayloadSurfacesRuleFailureAndWarnings() {
        // Audit 2026-06-11 #5: a silently broken ruleset must not produce a normal "completed" ping.
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Tenant A",
                        runName                  : "API Automation",
                        savedRunId               : "RS_ORDER",
                        reconciliationRunResultId: "RUN_RESULT_1",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260501/result.json",
                        file1SystemLabel         : "SHOPIFY",
                        file2SystemLabel         : "OMS",
                        differenceCount          : 4,
                        onlyInFile1Count         : 1,
                        onlyInFile2Count         : 3,
                        statusEnumId             : "AUT_STAT_FAILED",
                        processingWarnings       : ["RuleSet RS_ORDER compare stage failed: Drools build errors"],
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Darpan run completed WITH ISSUES: API Automation"), text)
        assertTrue(text.contains("Status: FAILED"), text)
        assertTrue(text.contains("Warnings (1): RuleSet RS_ORDER compare stage failed"), text)
        // Differences are still reported so the partial result is visible.
        assertTrue(text.contains("Differences: 4"), text)
    }

    /**
     * Reported from prod 2026-08-05 (Gorjana, API Order Sync, result 100255): a run with 0
     * differences, 0 missing and 0 pending was headlined "WITH ISSUES" purely because the exchange
     * pass always records its audit sentence into processingWarnings. An always-on alarm is not a
     * signal — the clean run must read clean.
     */
    @Test
    void buildRunCompletedPayloadDoesNotFlagAnAllClearExchangeAuditNoteAsAnIssue() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Gorjana",
                        runName                  : "API Order Sync",
                        savedRunId               : "RS_ORDER",
                        reconciliationRunResultId: "100255",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260805/result.json",
                        file1SystemLabel         : "Shopify",
                        file2SystemLabel         : "HotWax",
                        differenceCount          : 0,
                        onlyInFile1Count         : 0,
                        onlyInFile2Count         : 0,
                        processingWarnings       : ["Exchange presence check: 116 Shopify exchange(s) in window — " +
                                                            "26 matched in HotWax, 83 confirmed by lookup, 0 missing from HotWax, " +
                                                            "0 pending (younger than 3h). 7 in transit (return not yet closed)."],
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Darpan run completed: API Order Sync"), text)
        assertFalse(text.contains("WITH ISSUES"), text)
        assertFalse(text.contains("Warnings ("), text)
        // The audit trail itself is not dropped — it moves to its own line.
        assertTrue(text.contains("Notes: Exchange presence check: 116 Shopify exchange(s) in window"), text)
        assertTrue(text.contains("7 in transit (return not yet closed)."), text)
    }

    /**
     * The classifier keys on the exact audit prefix, NOT on a loose "Exchange presence check" match —
     * that same pass raises genuine warnings which share the opening words. Those must still make the
     * run an issue, and must not be diluted into the note count.
     */
    @Test
    void buildRunCompletedPayloadKeepsRealExchangeWarningsSeparateFromTheAuditNote() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Gorjana",
                        runName                  : "API Order Sync",
                        savedRunId               : "RS_ORDER",
                        reconciliationRunResultId: "100256",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260805/result.json",
                        file1SystemLabel         : "Shopify",
                        file2SystemLabel         : "HotWax",
                        differenceCount          : 2,
                        onlyInFile1Count         : 2,
                        onlyInFile2Count         : 0,
                        processingWarnings       : [
                                "Exchange presence check: 10 Shopify exchange(s) in window — 10 matched in HotWax, " +
                                        "0 confirmed by lookup, 0 missing from HotWax, 0 pending (younger than 3h).",
                                "Exchange presence check skipped: manifest unreadable (boom).",
                                "Verification pass: 3 of 5 'missing in HotWax' difference(s) confirmed present in HotWax " +
                                        "by point lookup (bulk-export index gap) and removed; 2 confirmed missing.",
                                "Shopify exchange sweep failed: connection reset",
                        ],
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Darpan run completed WITH ISSUES: API Order Sync"), text)
        // Exactly the two real failures — the two audit sentences are excluded from the count.
        assertTrue(text.contains("Warnings (2): Exchange presence check skipped: manifest unreadable (boom).; " +
                "Shopify exchange sweep failed: connection reset"), text)
        assertTrue(text.contains("Notes: Exchange presence check: 10 Shopify exchange(s) in window"), text)
        assertTrue(text.contains("Verification pass: 3 of 5"), text)
    }

    /** A run whose only processingWarnings are real still reports no Notes line at all. */
    @Test
    void buildRunCompletedPayloadOmitsTheNotesLineWhenThereAreNoAuditNotes() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Tenant A",
                        runName                  : "API Automation",
                        savedRunId               : "RS_ORDER",
                        reconciliationRunResultId: "RUN_RESULT_1",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260501/result.json",
                        differenceCount          : 0,
                        processingWarnings       : ["Shopify exchange sweep failed: connection reset"],
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Darpan run completed WITH ISSUES: API Automation"), text)
        assertTrue(text.contains("Warnings (1): Shopify exchange sweep failed"), text)
        assertFalse(text.contains("Notes:"), text)
    }
}
