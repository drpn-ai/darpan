package darpan.reconciliation.automation

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicReference

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regression cover for the scheduled ({@code scan_ReconciliationAutomations_5m}) automation path.
 *
 * <p>UAT 2026-07-31: every cron-fired automation failed at
 * {@code reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope} with
 * "RuleSet ... was not found or is not accessible in your active tenant", ~5 minutes into the run
 * (after both source extractions had already succeeded). Interactive "Run now" was unaffected.</p>
 *
 * <p>Cause: {@code ScheduledJobRunner} builds an ExecutionContext with no logged-in user, and
 * {@code moqui.service.job.ServiceJob} has no {@code userId} field to run as. {@code scan#DueAutomations}
 * and {@code execute#Automation} are {@code authenticate="anonymous-all"}, so Moqui logs in the
 * anonymous {@code _NA_} user — enough to satisfy the service auth check on the nested
 * {@code reconcile#RuleSetCompareScope}, but {@code _NA_} belongs to no tenant, so
 * {@code TenantAccessSupport.currentActiveTenantUserGroupId} still resolves null and the RuleSet gate
 * could only ever fail-closed on the cron path.</p>
 *
 * <p>These tests run the REAL reconcile pipeline on a fresh unauthenticated ExecutionContext —
 * exactly what the job runner does — so they fail without the system tenant context and pass with
 * it. Only the source extractor is stubbed (it is the network boundary); the RuleSet tenant gate,
 * compare scope resolution, and Spark diff all run for real.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledAutomationTenantContextTests {

    private static final String AUTOMATION_ID = "AUTO_SCHEDULED_TENANT"
    private static final String RULE_AUTOMATION_ID = "AUTO_SCHEDULED_TENANT_RULES"
    private static final String SFTP_AUTOMATION_ID = "AUTO_SCHEDULED_TENANT_SFTP"
    private static final String TENANT = "KREWE"
    private static final String FOREIGN_TENANT = "GORJANA"
    private static final String FOREIGN_RULE_SET_ID = "RS_GORJANA_SCHEDULED"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "scheduled-automation-tenant-context")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
        seedForeignTenantRuleSet()
        seedApiAutomation(AUTOMATION_ID, "DARPAN_TEST_COMPARE_RS", "DARPAN_TEST_ORDER_JSON_SCOPE")
        seedApiAutomation(RULE_AUTOMATION_ID, "DARPAN_TEST_PRODUCT_COMPARE_RS", "DARPAN_TEST_PRODUCT_JSON_SCOPE")
        seedSftpAutomation()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void prepare() {
        ec.message.clearErrors()
        AutomationExecutionSupport.resetExecutionHooks()
    }

    @AfterEach
    void resetHooks() {
        AutomationExecutionSupport.resetExecutionHooks()
    }

    // -----------------------------------------------------------------------
    // The production failure
    // -----------------------------------------------------------------------

    /**
     * The exact UAT failure: cron fires, both extractions return data, and the reconcile stage
     * rejects the automation's own RuleSet because no user is logged in.
     *
     * <p>Fails without the fix with "RuleSet DARPAN_TEST_COMPARE_RS was not found or is not
     * accessible in your active tenant" thrown from {@code prepare#RuleSetCompareScope}.</p>
     */
    @Test
    void scheduledAutomationReconcilesWithoutAnAuthenticatedUser() {
        stubSourceExtractorWithData()

        Map<String, Object> result = (Map<String, Object>) onSchedulerThread { ExecutionContext jobEc ->
            return callExecuteAutomation(jobEc, [
                    automationId     : AUTOMATION_ID,
                    scheduledFireTime: Timestamp.valueOf("2026-05-01 10:00:00"),
                    sparkMaster      : "local[1]",
                    sparkAppName     : "ScheduledAutomationTenantContextTests",
            ])
        }

        assertEquals(1, result.executedCount,
                "Scheduled automation must complete on a job thread with no logged-in user. Result: ${result}")
        assertEquals(0, result.failedCount, "Result: ${result}")

        def execution = findOne("darpan.reconciliation.ReconciliationAutomationExecution",
                [automationId: AUTOMATION_ID])
        assertNotNull(execution)
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
        assertEquals(TENANT, execution.companyUserGroupId)

        def runResult = findOne("darpan.reconciliation.ReconciliationRunResult",
                [reconciliationRunResultId: execution.reconciliationRunResultId])
        assertNotNull(runResult, "A scheduled run must persist its run-result row")
        assertEquals(TENANT, runResult.companyUserGroupId,
                "The run result must be stamped with the automation's tenant, not left unowned")
    }

    /**
     * Pins the mechanism the test above proves end to end: by the time the reconcile stage runs,
     * the automation's asserted tenant is the resolved active tenant.
     */
    @Test
    void reconcileStageSeesTheAutomationTenantAsActiveTenant() {
        stubSourceExtractorWithData()
        AtomicReference<String> observedTenant = new AtomicReference<String>(null)
        AutomationExecutionSupport.setReconcileRunner { Object jobEc, Object automation, Object file1Source,
                Object file2Source, Map<String, Object> file1Result, Map<String, Object> file2Result,
                Map<String, Object> window, Map<String, Object> params ->
            observedTenant.set(TenantAccessSupport.currentActiveTenantUserGroupId(jobEc))
            return [
                    reconciliationType: "ORDER",
                    diffLocation      : "reconciliation-runs/${AUTOMATION_ID}/result.json".toString(),
                    diffFileName      : "result.json",
                    differenceCount   : 0,
                    onlyInFile1Count  : 0,
                    onlyInFile2Count  : 0,
                    validationErrors  : [],
                    processingWarnings: [],
            ]
        }

        onSchedulerThread { ExecutionContext jobEc ->
            return callExecuteAutomation(jobEc, [
                    automationId     : AUTOMATION_ID,
                    scheduledFireTime: Timestamp.valueOf("2026-05-02 10:00:00"),
            ])
        }

        assertEquals(TENANT, observedTenant.get(),
                "The reconcile stage on a job thread must resolve the automation's tenant")
    }

    /**
     * The rule-execution stage has its own tenant gate
     * ({@code RuleEngineSupport} → {@code findTenantScopedById} on the RuleSet), reached only when the
     * compare scope's RuleSet has enabled rules. Real tenants do; the order fixture above does not.
     */
    @Test
    void scheduledAutomationWithEnabledRulesCompletes() {
        stubSourceExtractor("component://darpan/data/test/test-products-1.json",
                "component://darpan/data/test/test-products-2.json")

        Map<String, Object> result = (Map<String, Object>) onSchedulerThread { ExecutionContext jobEc ->
            return callExecuteAutomation(jobEc, [
                    automationId     : RULE_AUTOMATION_ID,
                    scheduledFireTime: Timestamp.valueOf("2026-05-03 10:00:00"),
                    sparkMaster      : "local[1]",
                    sparkAppName     : "ScheduledAutomationTenantContextTests",
            ])
        }

        assertEquals(1, result.executedCount,
                "A scheduled run whose RuleSet has enabled rules must clear the rule-engine tenant gate too. Result: ${result}")
        def execution = findOne("darpan.reconciliation.ReconciliationAutomationExecution",
                [automationId: RULE_AUTOMATION_ID])
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId)
    }

    /**
     * SFTP automations reach the runner through {@code run#SftpFileAutomation}, which calls
     * {@code SftpAutomationSupport.runSftpFileAutomation} directly rather than through
     * {@code execute#Automation}. Its {@code resolveRunScope} defaults {@code runTenantUserGroupId}
     * from the active tenant and throws "runTenantUserGroupId is required for tenant-scoped SFTP
     * automation" when there is none — the same defect, on the other entry point.
     *
     * <p>{@code resolveRunScope} runs before any SFTP connect, so reaching the client factory proves
     * the tenant resolved.</p>
     */
    @Test
    void scheduledSftpAutomationResolvesItsTenantBeforeConnecting() {
        AtomicReference<Boolean> reachedSftpStage = new AtomicReference<Boolean>(false)
        SftpAutomationSupport.setClientFactory { String host, String user, Integer port ->
            reachedSftpStage.set(true)
            throw new IllegalStateException("SFTP stage reached — no real connection in tests")
        }
        try {
            onSchedulerThread { ExecutionContext jobEc ->
                return jobEc.service.sync()
                        .name("reconciliation.ReconciliationAutomationServices.run#SftpFileAutomation")
                        .parameters([
                                automationId       : SFTP_AUTOMATION_ID,
                                pollIntervalMinutes: 10,
                                pollTimeoutMinutes : 1,
                        ])
                        .call()
            }
        } finally {
            SftpAutomationSupport.resetClientFactory()
        }

        assertTrue(reachedSftpStage.get(),
                "A scheduled SFTP automation must resolve its own tenant and reach the SFTP stage")
    }

    // -----------------------------------------------------------------------
    // Security invariants the fix must not break
    // -----------------------------------------------------------------------

    /** Default-deny holds: with no user and no system context, nothing resolves a tenant. */
    @Test
    void jobThreadWithoutSystemContextStillResolvesNoTenant() {
        String resolved = (String) onSchedulerThread { ExecutionContext jobEc ->
            return TenantAccessSupport.currentActiveTenantUserGroupId(jobEc)
        }
        assertNull(resolved, "An unauthenticated job thread must not resolve a tenant on its own")
    }

    /** An authenticated user's tenant can never be widened or replaced by a system context. */
    @Test
    void authenticatedUserTenantIsNotOverriddenBySystemContext() {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)

        String resolved = TenantAccessSupport.withSystemTenant(FOREIGN_TENANT) {
            return TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        }

        assertEquals(TENANT, resolved,
                "A logged-in user's active tenant must win over any system tenant context")
    }

    /** The system context is scoped to one tenant — foreign records stay denied. */
    @Test
    void systemTenantContextStillDeniesForeignTenantRecords() {
        Boolean canAccessForeign = (Boolean) onSchedulerThread { ExecutionContext jobEc ->
            def foreignRuleSet = jobEc.entity.find("darpan.rule.RuleSet")
                    .condition("ruleSetId", FOREIGN_RULE_SET_ID)
                    .disableAuthz().useCache(false).one()
            assertNotNull(foreignRuleSet)
            return TenantAccessSupport.withSystemTenant(TENANT) {
                return TenantAccessSupport.canAccessTenantRecord(jobEc, foreignRuleSet)
            }
        }
        assertFalse(canAccessForeign,
                "A ${TENANT} system context must not grant access to a ${FOREIGN_TENANT} record")
    }

    /** A pooled worker thread must not carry one automation's tenant into the next job. */
    @Test
    void systemTenantContextDoesNotLeakAfterTheRun() {
        String afterRun = (String) onSchedulerThread { ExecutionContext jobEc ->
            TenantAccessSupport.withSystemTenant(TENANT) {
                assertEquals(TENANT, TenantAccessSupport.currentActiveTenantUserGroupId(jobEc))
                return null
            }
            return TenantAccessSupport.currentActiveTenantUserGroupId(jobEc)
        }
        assertNull(afterRun, "The system tenant context must be cleared when the run finishes")
    }

    /** A failing run must still clear the context rather than leaving the thread poisoned. */
    @Test
    void systemTenantContextIsClearedWhenTheRunThrows() {
        String afterThrow = (String) onSchedulerThread { ExecutionContext jobEc ->
            try {
                TenantAccessSupport.withSystemTenant(TENANT) {
                    throw new IllegalStateException("boom")
                }
            } catch (IllegalStateException ignored) {
                // expected
            }
            return TenantAccessSupport.currentActiveTenantUserGroupId(jobEc)
        }
        assertNull(afterThrow, "A thrown run must not leave the system tenant context set")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Runs {@code work} on a fresh ExecutionContext with authz disabled and NO logged-in user —
     * the same context shape {@code ScheduledJobRunner.runInternal} creates for a service job.
     */
    private Object onSchedulerThread(Closure work) {
        AtomicReference<Object> resultRef = new AtomicReference<Object>(null)
        AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>(null)
        Thread jobThread = new Thread({
            ExecutionContext jobEc = Moqui.getExecutionContext()
            try {
                jobEc.artifactExecution.disableAuthz()
                jobEc.artifactExecution.push("schedulerThread", ArtifactExecutionInfo.AT_OTHER,
                        ArtifactExecutionInfo.AUTHZA_ALL, false)
                jobEc.artifactExecution.setAnonymousAuthorizedAll()
                assertNull(jobEc.user.userId, "Job thread must start with no logged-in user")
                resultRef.set(work.call(jobEc))
            } catch (Throwable t) {
                errorRef.set(t)
            } finally {
                jobEc.destroy()
            }
        }, "scheduled-automation-tenant-test")
        jobThread.start()
        jobThread.join()
        if (errorRef.get() != null) throw errorRef.get()
        return resultRef.get()
    }

    /**
     * Invokes the automation through the real {@code execute#Automation} service rather than the
     * Groovy support class. That service is {@code authenticate="anonymous-all"}, so Moqui logs in
     * the anonymous {@code _NA_} user — reproducing the production context exactly: a user that
     * satisfies the service auth check but belongs to no tenant.
     */
    private Map<String, Object> callExecuteAutomation(ExecutionContext jobEc, Map<String, Object> parameters) {
        return (Map<String, Object>) jobEc.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                .parameters(parameters)
                .call()
    }

    private void stubSourceExtractorWithData() {
        stubSourceExtractor("component://darpan/data/test/test-orders-1.json",
                "component://darpan/data/test/test-orders-2.json")
    }

    private void stubSourceExtractor(String file1Location, String file2Location) {
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            String fileSide = source.get("fileSide")
            return [
                    dataAvailable : true,
                    fileLocation  : fileSide == AutomationExecutionSupport.FILE_SIDE_1 ? file1Location : file2Location,
                    fileName      : "${fileSide}.json".toString(),
                    fileTypeEnumId: "DftJson",
                    recordCount   : 3,
            ]
        }
    }

    private def findOne(String entityName, Map<String, Object> pkFields) {
        return ec.entity.find(entityName).condition(pkFields).disableAuthz().useCache(false).one()
    }

    private void seedForeignTenantRuleSet() {
        if (findOne("moqui.security.UserGroup", [userGroupId: FOREIGN_TENANT]) == null) {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "moqui.security.UserGroup", [
                    userGroupId    : FOREIGN_TENANT,
                    description    : "Gorjana",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
            ])
        }
        if (findOne("darpan.rule.RuleSet", [ruleSetId: FOREIGN_RULE_SET_ID]) == null) {
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.rule.RuleSet", [
                    ruleSetId         : FOREIGN_RULE_SET_ID,
                    ruleSetName       : "Gorjana Scheduled RS",
                    description       : "RuleSet owned by GORJANA — used for scheduled-path denial cover.",
                    version           : "1.0",
                    explosionPath     : "rows",
                    primaryKeyPath    : "id",
                    companyUserGroupId: FOREIGN_TENANT,
                    createdByUserId   : TEST_USER_ID,
            ])
        }
    }

    private void seedApiAutomation(String automationId, String ruleSetId, String compareScopeId) {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation", [automationId: automationId], [
                automationId            : automationId,
                automationName          : "Scheduled Tenant Context ${automationId}".toString(),
                companyUserGroupId      : TENANT,
                createdByUserId         : TEST_USER_ID,
                inputModeEnumId         : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId              : ruleSetId,
                savedRunType            : "ruleset",
                ruleSetId               : ruleSetId,
                compareScopeId          : compareScopeId,
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                isActive                : "Y",
                createdDate             : ec.user.nowTimestamp,
                lastUpdatedDate         : ec.user.nowTimestamp,
        ])
        [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2].each { String fileSide ->
            upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource",
                    [automationId: automationId, fileSide: fileSide], [
                    automationId      : automationId,
                    fileSide          : fileSide,
                    companyUserGroupId: TENANT,
                    createdByUserId   : TEST_USER_ID,
                    sourceTypeEnumId  : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                    systemEnumId      : fileSide == AutomationExecutionSupport.FILE_SIDE_1 ? "SHOPIFY" : "OMS",
                    fileTypeEnumId    : "DftJson",
                    createdDate       : ec.user.nowTimestamp,
                    lastUpdatedDate   : ec.user.nowTimestamp,
            ])
        }
    }

    private void seedSftpAutomation() {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation", [automationId: SFTP_AUTOMATION_ID], [
                automationId            : SFTP_AUTOMATION_ID,
                automationName          : "Scheduled Tenant Context SFTP",
                companyUserGroupId      : TENANT,
                createdByUserId         : TEST_USER_ID,
                inputModeEnumId         : AutomationExecutionSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId              : "DARPAN_TEST_COMPARE_RS",
                savedRunType            : "ruleset",
                ruleSetId               : "DARPAN_TEST_COMPARE_RS",
                compareScopeId          : "DARPAN_TEST_ORDER_JSON_SCOPE",
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                isActive                : "Y",
                createdDate             : ec.user.nowTimestamp,
                lastUpdatedDate         : ec.user.nowTimestamp,
        ])
        [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2].each { String fileSide ->
            boolean isFile1 = fileSide == AutomationExecutionSupport.FILE_SIDE_1
            upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource",
                    [automationId: SFTP_AUTOMATION_ID, fileSide: fileSide], [
                    automationId      : SFTP_AUTOMATION_ID,
                    fileSide          : fileSide,
                    companyUserGroupId: TENANT,
                    createdByUserId   : TEST_USER_ID,
                    sourceTypeEnumId  : "AUT_SRC_SFTP",
                    systemEnumId      : isFile1 ? "SHOPIFY" : "OMS",
                    fileTypeEnumId    : "DftJson",
                    sftpServerId      : isFile1 ? "SHOPIFY_TEST_SFTP" : "OMS_TEST_SFTP",
                    remotePathTemplate: isFile1 ? "/incoming/shopify" : "/incoming/oms",
                    createdDate       : ec.user.nowTimestamp,
                    lastUpdatedDate   : ec.user.nowTimestamp,
            ])
        }
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        if (findOne(entityName, pkFields) != null) return
        ec.entity.makeValue(entityName).setAll(fields).create()
    }
}
