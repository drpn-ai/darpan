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
        // Copy is randomly selected in production. Pin it so these tests assert on structure, not luck.
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-notification-service-smoke")
        // The REAL seed file, not a hand-copied fixture: the endpoint->system links these alerts are
        // named from live in parentEnumId there (OMS_RETURNS -> OMS), so loading it means a future
        // seed edit is caught here rather than silently diverging from what production resolves.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
    }

    @AfterAll
    void cleanup() {
        RunNotificationVoice.resetLinePicker()
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
        assertTrue(text.contains("API Automation — 4 to look at, missing on both sides."), text)
        assertTrue(text.contains("Tenant: Tenant A"))
        assertTrue(text.contains("Result ID: RUN_RESULT_1"))
        assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_API%2F20260501%2Fresult.json?runName=API+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=OMS&tenantId=TENANT_A|Open run result>"))
        // Inversion: onlyInFile1Count is the SHOPIFY-side count, so it is what OMS is missing.
        assertTrue(text.contains("Missing from OMS: 1"), text)
        assertTrue(text.contains("Missing from SHOPIFY: 3"), text)
        assertTrue(text.contains("Mismatches: 0"), text)
        assertFalse(text.contains("Differences:"), "the redundant total is gone: ${text}")
        assertEquals(10, text.readLines().size(), text)
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
        assertTrue(text.contains("API Automation did not finish."), text)
        assertTrue(text.contains("Status: FAILED"), text)
        assertTrue(text.contains("Warnings (1): RuleSet RS_ORDER compare stage failed"), text)
        // Counts are still reported so the partial result is visible.
        assertTrue(text.contains("Missing from OMS: 1"), text)
        assertTrue(text.contains("Missing from SHOPIFY: 3"), text)
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
        assertTrue(text.contains("API Order Sync — ${RunNotificationVoice.CLEAN_HEADLINES.first()}"), text)
        assertFalse(text.contains("*Details*"), "three zeros is noise on a clean run: ${text}")
        assertFalse(text.contains("Warnings ("), text)
        // The audit sentences stay OUT of the chat body entirely (2026-08-28). They were a second
        // rendering of what *Details* already says, in prose, and on a returns run they ran to six
        // sentences. The artifact keeps them verbatim for the run-result page; what must survive
        // here is that they did not turn a clean run into a warned one, which the two assertions
        // above pin.
        assertFalse(text.contains("Notes:"), text)
        assertFalse(text.contains("Exchange presence check"), text)
    }

    /**
     * Reported from prod 2026-08-26 (Gorjana, Daily Return Reconciliation, result 100617): the
     * Details block named the ENDPOINTS ("Missing from HotWax Returns (Reconciliation API)"), not
     * the systems. A count is per system, and the operator reading the alert thinks in systems.
     *
     * Endpoints and systems share enumTypeId="DarpanSystemSource"; an endpoint carries parentEnumId
     * pointing at its system (OMS_RETURNS -> OMS, "HotWax"), a top-level system carries none. The
     * automation stamps the endpoint label AND the endpoint enum id, so the system name is one hop
     * away and nothing walked it.
     */
    @Test
    void buildRunCompletedPayloadNamesSystemsRatherThanTheEndpointsThatFedThem() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Gorjana",
                        runName                  : "Daily Return Reconciliation",
                        savedRunId               : "RS_RETURNS",
                        reconciliationRunResultId: "100617",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_RETURNS/20260826/result.json",
                        // Exactly what AutomationExecutionSupport stamps: endpoint label + endpoint enum.
                        file1SystemLabel         : "Shopify Order Return References",
                        file1SystemEnumId        : "SHOPIFY_RETURN_REFS",
                        file2SystemLabel         : "HotWax Returns (Reconciliation API)",
                        file2SystemEnumId        : "OMS_RETURNS",
                        differenceCount          : 660,
                        onlyInFile1Count         : 259,
                        onlyInFile2Count         : 401,
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("Missing from HotWax:"), text)
        assertTrue(text.contains("Missing from Shopify:"), text)
        assertFalse(text.contains("Reconciliation API"), "endpoint name leaked into the alert: ${text}")
        assertFalse(text.contains("Order Return References"), "endpoint name leaked into the alert: ${text}")
    }

    /**
     * The one pairing that must NOT collapse: two endpoints of the SAME system would both render
     * "Missing from HotWax", leaving two different counts with identical labels and no way to tell
     * which is which. That pairing keeps the endpoint names, which are what distinguishes them.
     * darpan-ui's darpanSystemNamePair carries the same guard for the run-result tiles.
     */
    @Test
    void buildRunCompletedPayloadKeepsEndpointNamesWhenBothSidesAreTheSameSystem() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Gorjana",
                        runName                  : "HotWax Cross-Endpoint",
                        savedRunId               : "RS_HOTWAX",
                        reconciliationRunResultId: "100618",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_HW/20260826/result.json",
                        file1SystemLabel         : "HotWax Returns (Reconciliation API)",
                        file1SystemEnumId        : "OMS_RETURNS",
                        file2SystemLabel         : "HotWax Transfer Orders",
                        file2SystemEnumId        : "OMS_TRANSFER_ORDERS",
                        differenceCount          : 5,
                        onlyInFile1Count         : 2,
                        onlyInFile2Count         : 3,
                ])
                .disableAuthz()
                .call()

        String text = result.payload.text as String
        assertTrue(text.contains("HotWax Returns (Reconciliation API)"), text)
        assertTrue(text.contains("HotWax Transfer Orders"), text)
        assertFalse(text.contains("Missing from HotWax:"), "collapsed two HotWax endpoints into one name: ${text}")
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
        assertTrue(text.contains("API Order Sync finished, but not cleanly."), text)
        // Exactly the two real failures — the two audit sentences are excluded from the count.
        assertTrue(text.contains("Warnings (2): Exchange presence check skipped: manifest unreadable (boom).; " +
                "Shopify exchange sweep failed: connection reset"), text)
        // Audit sentences are classified out of the warning count (asserted above) and then not
        // rendered at all — the counts an operator acts on are in *Details*.
        assertFalse(text.contains("Notes:"), text)
        assertFalse(text.contains("Verification pass:"), text)
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
        assertTrue(text.contains("API Automation finished, but not cleanly."), text)
        assertTrue(text.contains("Warnings (1): Shopify exchange sweep failed"), text)
        assertFalse(text.contains("Notes:"), text)
    }

    /**
     * differenceCount is onlyInFile1Count + onlyInFile2Count and nothing more
     * (ReconciliationServices.groovy:111 — both sides are left_anti presence checks). Records present
     * on BOTH sides with differing values are counted only in ruleDifferenceCount. Without that
     * parameter this run reported "Differences: 0" and read as a clean sync.
     */
    @Test
    void buildRunCompletedPayloadAcceptsRuleDifferenceCount() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Tenant A",
                        runName                  : "Rule Diff Run",
                        reconciliationRunResultId: "100511",
                        file1SystemLabel         : "HOTWAX",
                        file2SystemLabel         : "SHOPIFY",
                        differenceCount          : 0,
                        onlyInFile1Count         : 0,
                        onlyInFile2Count         : 0,
                        ruleDifferenceCount      : 8,
                ])
                .disableAuthz()
                .call()

        String text = (String) ((Map) result.payload).text
        // A run with zero missing records but eight value mismatches must NOT read as clean.
        assertTrue(text.contains("8"), "expected the mismatch count in the payload, got: ${text}")
    }

    /**
     * A Google Chat alert reaches an operator who may be working in another tenant. Without the run's
     * tenant in the link the app cannot switch into it, so the result loads against the wrong tenant
     * or not at all.
     */
    @Test
    void runResultUrlCarriesTheTenantForDeepLinkSwitching() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyUserGroupId       : "TENANT_A",
                        companyLabel             : "Tenant A",
                        runName                  : "API Automation",
                        reconciliationRunResultId: "100511",
                        savedRunId               : "RS_ORDER",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260501/result.json",
                ])
                .disableAuthz()
                .call()

        String text = (String) ((Map) result.payload).text
        assertTrue(text.contains("tenantId=TENANT_A"),
                "the link must name the tenant so the UI can switch into it: ${text}")
    }

    /**
     * Links already in circulation carry no tenantId and must keep working, so a blank tenant drops
     * the parameter rather than emitting an empty one.
     */
    @Test
    void runResultUrlOmitsTheTenantWhenItIsBlank() {
        Map result = ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters([
                        companyLabel             : "Tenant A",
                        runName                  : "API Automation",
                        reconciliationRunResultId: "100511",
                        savedRunId               : "RS_ORDER",
                        resultDataManagerPath    : "reconciliation-runs/AUTO_API/20260501/result.json",
                ])
                .disableAuthz()
                .call()

        String text = (String) ((Map) result.payload).text
        assertTrue(text.contains("/reconciliation/run-result/RS_ORDER/"), text)
        assertFalse(text.contains("tenantId"),
                "a blank tenant must drop the parameter, not emit an empty one: ${text}")
    }
}
