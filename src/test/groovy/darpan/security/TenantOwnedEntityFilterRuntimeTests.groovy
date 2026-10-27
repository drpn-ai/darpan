package darpan.security

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-076 — the DARPAN_ACTIVE_COMPANY_SCOPE filter, proven at runtime, with authz ON.
 *
 * <p>Every other smoke test runs inside the harness's anonymous-authorized-all "smokeTests" frame
 * with authz disabled, where no entity filter can ever apply — so none of them can tell a filtered
 * entity from an unfiltered one. Here the harness frame is removed and authz enabled (see
 * {@link #withProductionAuthz}), the caller is a real DARPAN_USER member, and the security seed is
 * loaded, so a facade read sees exactly what it sees in production.</p>
 *
 * <p>Two layers:</p>
 * <ul>
 *   <li><b>The filter itself</b>, for all thirteen DAR-BE-076 entities: a plain {@code ec.entity.find}
 *       made under a facade service's grant — the code shape a facade service body has — sees only
 *       the active tenant's rows. Another tenant's row and an unstamped (NULL) row are hidden from
 *       {@code list()}, {@code count()} and a full-primary-key {@code one()}; the no-tenant sentinel
 *       sees nothing; and the active tenant still sees its own row (the positive control that keeps
 *       every "hidden" assertion honest).</li>
 *   <li><b>The callers the filter could break</b>, through the real services: a shared-config peer
 *       still reads the owner's endpoint decisions (fail-closed, never "absent means enabled"); a run
 *       step written without a tenant in its context is still stamped and still heartbeats; and
 *       save#RuleSetRun still replaces unstamped exclusion-filter rows instead of colliding on them.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantOwnedEntityFilterRuntimeTests {

    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final String NO_TENANT = TenantAccessSupport.NO_ACTIVE_TENANT_FILTER_VALUE
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY

    // seedCompanyScope's user (its constants are private there). internalLoginUser takes the USERNAME.
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String NO_TENANT_ID = "TOEF_NO_TENANT_USER"
    private static final String NO_TENANT_USERNAME = "toef.notenant"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    /** A facade service the caller's DARPAN_USER grant covers; its grant is what binds the filter. */
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"

    private static final String HWC_OWNED = "TOEF_HWC_KREWE"
    private static final String OBS_RUN_RESULT = "TOEF_RR_OBS"

    /** entity -> the field that tells this test's fixture rows apart (suffix K / G / N). */
    private static final Map<String, String> MARKER_FIELD = [
            "darpan.database.DatabaseSourceConfig"                     : "databaseSourceConfigId",
            "darpan.database.DatabaseSourceQuery"                      : "databaseSourceQueryId",
            "darpan.reconciliation.NsSuiteQlSourceQuery"               : "nsSuiteQlSourceQueryId",
            "darpan.reconciliation.Reconciliation"                     : "reconciliationId",
            "darpan.reconciliation.ReconciliationRun"                  : "reconciliationRunId",
            "darpan.reconciliation.ReconciliationAutomationSourceFilter": "automationId",
            "darpan.reconciliation.ReconciliationRunStep"              : "reconciliationRunStepId",
            "darpan.reconciliation.SlackOAuthState"                    : "stateToken",
            "darpan.reconciliation.SlackWorkspaceInstall"              : "slackInstallId",
            "darpan.reconciliation.SourceConfigEndpointAccess"         : "configId",
            "darpan.rule.RuleSetCompareSourceFilter"                   : "compareScopeId",
            "darpan.rule.RuleSetConclusionRule"                        : "compareScopeId",
            "darpan.rule.RuleSetConclusionCondition"                   : "compareScopeId",
            "darpan.audit.Audit"                                 : "auditId",
            "darpan.audit.AuditStatement"                    : "statementId",
            "darpan.audit.AuditCheck"                        : "checkId",
            "darpan.audit.AuditStatementCheck"               : "statementId",
            "darpan.audit.AuditFinding"                          : "findingId",
            "darpan.audit.AuditFindingRecord"                    : "findingId",
            "darpan.audit.AuditFindingCondition"                     : "findingId",
            "darpan.audit.AuditFindingEvent"                     : "findingId",
            "darpan.audit.AuditCheckRun"                         : "checkRunId",
            "darpan.audit.AuditSourceRead"                       : "checkRunId",
            "darpan.audit.AuditDigest"                           : "auditDigestId",
            "darpan.reconciliation.AutomationStreak"             : "automationId",
            "darpan.reconciliation.AutomationStreakBadge"        : "automationId",
            "darpan.reconciliation.TenantStreakQuest"            : "questKey",
            "darpan.reconciliation.TenantStreakFact"             : "factId",
    ].asImmutable()

    /** Entities whose companyUserGroupId is not-null: they get no unstamped fixture row. */
    private static final Set<String> TENANT_REQUIRED = [
            "darpan.reconciliation.SlackOAuthState",
            "darpan.reconciliation.SlackWorkspaceInstall",
            "darpan.reconciliation.SourceConfigEndpointAccess",
            "darpan.audit.Audit",
            "darpan.audit.AuditStatement",
            "darpan.audit.AuditCheck",
            "darpan.audit.AuditStatementCheck",
            "darpan.audit.AuditFinding",
            "darpan.audit.AuditFindingRecord",
            "darpan.audit.AuditFindingCondition",
            "darpan.audit.AuditFindingEvent",
            "darpan.audit.AuditCheckRun",
            "darpan.audit.AuditSourceRead",
            "darpan.audit.AuditDigest",
            "darpan.reconciliation.AutomationStreak",
            "darpan.reconciliation.AutomationStreakBadge",
            "darpan.reconciliation.TenantStreakQuest",
            "darpan.reconciliation.TenantStreakFact",
    ].toSet().asImmutable()

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-owned-entity-filter")

        // The security model under test: DARPAN_FACADE_APP grants, their ArtifactAuthzFilter bindings
        // and the DARPAN_ACTIVE_COMPANY_SCOPE filter rows. dummyFks: the file loads in one
        // transaction and forward-references reference data this bare H2 does not carry.
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)   // also seedCompanyScope: KREWE editor

        seedTenant(GORJANA, "Gorjana")
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserAccount", [userId: NO_TENANT_ID, username: NO_TENANT_USERNAME,
                                              userFullName: "No tenant", currentPassword: "", disabled: "N"])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: NO_TENANT_ID, fromDate: FROM_DATE])
        // UserFacade caches a user's groups per login and reuses that login for the same username, so
        // the DARPAN_USER membership above is only seen after a real switch of user.
        actAs(NO_TENANT_USERNAME, null)
        actAs(EDITOR_USERNAME, KREWE)

        seedFilterMatrix()
        seedSharedOmsConfig()
        insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: OBS_RUN_RESULT,
                companyUserGroupId: KREWE, statusEnumId: RunObservability.STATUS_RUNNING])
        seedFlowchartFixtures()
    }

    /** Rows for the run-flowchart services (DAR-BE-074 candidates 5-7), on top of the matrix rows. */
    private void seedFlowchartFixtures() {
        // TOEF_REC_K's question count: TOEF_RUN_K counts; a switched-off KREWE question and a question
        // stamped GORJANA but hung on TOEF_REC_K must not.
        insert("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: "TOEF_RUN_K_OFF",
                reconciliationId: "TOEF_REC_K", companyUserGroupId: KREWE, isActive: "N"])
        insert("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: "TOEF_RUN_K_FORGED_G",
                reconciliationId: "TOEF_REC_K", companyUserGroupId: GORJANA, isActive: "Y"])
        // One press of Run whose result rows carry three different stamps.
        [K: KREWE, G: GORJANA, N: null].each { String suffix, String tenant ->
            insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "TOEF_EXEC_RR_${suffix}".toString(),
                    reconciliationExecutionId: "TOEF_EXEC", companyUserGroupId: tenant,
                    statusEnumId: RunObservability.STATUS_SUCCESS, differenceCount: 7L,
                    createdDate: new Timestamp(FROM_DATE.time + ["K", "G", "N"].indexOf(suffix) * 1000L)])
        }
        // Questions to delete, on a run that is not one of the TOEF_REC_ fixtures above.
        insert("darpan.reconciliation.Reconciliation", [reconciliationId: "TOEF_DREC_K", reconciliationName: "TOEF delete",
                companyUserGroupId: KREWE, isActive: "Y", isArchived: "N"])
        ["TOEF_DEL_K", "TOEF_DEL_K2"].each { String id ->
            insert("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: id, reconciliationId: "TOEF_DREC_K",
                    companyUserGroupId: KREWE, isActive: "Y"])
        }
        insert("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: "TOEF_DEL_K_CHILD_G", reconciliationId: "TOEF_DREC_K",
                parentReconciliationRunId: "TOEF_DEL_K", companyUserGroupId: GORJANA, isActive: "Y"])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsKreweEditor() {
        actAs(EDITOR_USERNAME, KREWE)
    }

    // -----------------------------------------------------------------------------------------
    // Layer 1 — the filter, on every DAR-BE-076 entity
    // -----------------------------------------------------------------------------------------

    @Test
    void controlEveryFixtureRowExistsWhenNoFilterApplies() {
        // Without this, every "hidden" assertion below could pass on rows that were never written.
        MARKER_FIELD.each { String entityName, String marker ->
            int expected = TENANT_REQUIRED.contains(entityName) ? 2 : 3
            assertEquals(expected, ec.entity.find(entityName).condition(marker, "in", markerValues(entityName))
                    .disableAuthz().useCache(false).list().size(), "fixture rows missing for ${entityName}")
        }
    }

    @Test
    void aFacadeReadSeesOnlyTheActiveTenantsRows() {
        assertEquals([], tenantLeaks(KREWE, "K"), "KREWE facade read")
    }

    @Test
    void theFilterFollowsTheActiveTenantNotTheUser() {
        actAs(EDITOR_USERNAME, GORJANA)
        assertEquals([], tenantLeaks(GORJANA, "G"), "GORJANA facade read by the same user")
    }

    @Test
    void theNoTenantSentinelSeesNothing() {
        actAs(NO_TENANT_USERNAME, null)
        assertEquals(NO_TENANT, ec.user.context.activeTenantUserGroupId, "test precondition: no active tenant")
        List<String> visible = []
        underFacadeGrant {
            MARKER_FIELD.each { String entityName, String marker ->
                List rows = ec.entity.find(entityName).condition(marker, "in", markerValues(entityName)).useCache(false).list()
                if (rows) visible << "${entityName}: ${rows*.get(marker)}".toString()
            }
        }
        assertEquals([], visible, "a caller with no active tenant must see no tenant-owned row")
    }

    @Test
    void gorjanasAuditCatalogIsSeenByGorjanaOnly() {
        // DAR-BE-088: the real catalog file, loaded as an install loads it, read the way a facade service reads it.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/GorjanaMonthCloseCatalogData.xml")
        Set<String> categoryIds = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditCategory")
                .disableAuthz().useCache(false).list()*.get("enumId") as Set<String>
        assertEquals(4, categoryIds.size(), "test precondition: the four categories are seeded")

        actAs(EDITOR_USERNAME, GORJANA)
        List gorjana = []
        underFacadeGrant {
            gorjana = ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "like", "GORJANA_R%")
                    .orderBy("sequenceNum").useCache(false).list()
        }
        // R18 (DAR-BE-111) and R19 (DAR-BE-113) follow the seventeen from #483.
        assertEquals((1..20).collect { "R${it}".toString() }, gorjana*.get("statementCode"), "GORJANA sees R1-R20 in order")
        assertEquals([], gorjana.findAll { !categoryIds.contains(it.get("categoryEnumId")) }*.get("statementCode"),
                "every statement carries an AuditCategory")

        actAs(EDITOR_USERNAME, KREWE)
        long krewe = -1L
        underFacadeGrant {
            krewe = ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "like", "GORJANA_R%").count()
        }
        assertEquals(0L, krewe, "another tenant must see none of gorjana's catalog")
    }

    // -----------------------------------------------------------------------------------------
    // Layer 2 — real facade services, authz ON
    // -----------------------------------------------------------------------------------------

    @Test
    void listReconciliationsStillReturnsOwnRowsAndOnlyThem() {
        Map<String, Object> out = withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.list#Reconciliations").call()
        }
        assertTrue(out.ok as Boolean, "list#Reconciliations failed under production authz: ${out.errors}")
        List ids = (out.reconciliations as List)*.reconciliationId.findAll { it.toString().startsWith("TOEF_REC_") }
        assertEquals(["TOEF_REC_K"], ids)
    }

    @Test
    void listReconciliationsCountsOnlyTheTenantsActiveQuestions() {
        Map<String, Object> out = withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.list#Reconciliations").call()
        }
        Map own = (out.reconciliations as List).find { it.reconciliationId == "TOEF_REC_K" } as Map
        assertEquals(1L, own.questionCount as Long, "switched-off and foreign-stamped questions must not count")
        assertEquals(["reconciliationId", "reconciliationName", "description", "defaultTimeWindow", "isActive",
                      "isArchived", "questionCount"].toSet(), own.keySet())
    }

    @Test
    void listReconciliationsIsTenantScopedEvenWithAuthzDisabled() {
        Map<String, Object> out = ec.service.sync().name("facade.ReconciliationFacadeServices.list#Reconciliations")
                .disableAuthz().call()
        List ids = (out.reconciliations as List)*.reconciliationId.findAll { it.toString().startsWith("TOEF_REC_") }
        assertEquals(["TOEF_REC_K"], ids)
        assertEquals(1L, ((out.reconciliations as List).find { it.reconciliationId == "TOEF_REC_K" } as Map).questionCount as Long)
    }

    @Test
    void listReconciliationsGivesACallerWithNoActiveTenantNothing() {
        actAs(NO_TENANT_USERNAME, null)
        Map<String, Object> out = withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.list#Reconciliations").call()
        }
        assertEquals([], (out.reconciliations as List)*.reconciliationId.findAll { it.toString().startsWith("TOEF_") })
    }

    @Test
    void getReconciliationExecutionReturnsOnlyTheActiveTenantsResults() {
        assertEquals(["TOEF_EXEC_RR_K"], executionResultIds(true))
        Map own = (withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.get#ReconciliationExecution")
                    .parameters([reconciliationExecutionId: "TOEF_EXEC"]).call()
        }.results as List)[0] as Map
        assertEquals(["reconciliationRunResultId", "reconciliationRunId", "parentRunResultId", "statusEnumId", "yesCount",
                      "noCount", "unaskedCount", "differenceCount", "errorMessage", "resultDataManagerPath"].toSet(), own.keySet())
        assertEquals(7L, own.differenceCount as Long)
        actAs(EDITOR_USERNAME, GORJANA)
        assertEquals(["TOEF_EXEC_RR_G"], executionResultIds(true))
    }

    @Test
    void getReconciliationExecutionIsTenantScopedEvenWithAuthzDisabled() {
        assertEquals(["TOEF_EXEC_RR_K"], executionResultIds(false))
    }

    @Test
    void getReconciliationExecutionGivesACallerWithNoActiveTenantNothing() {
        actAs(NO_TENANT_USERNAME, null)
        assertEquals([], executionResultIds(true))
    }

    @Test
    void deleteReconciliationQuestionRefusesAnotherTenantsQuestion() {
        Map<String, Object> out = deleteQuestion("TOEF_RUN_G")
        assertEquals(false, out.ok)
        assertEquals(["Question 'TOEF_RUN_G' was not found."], out.errors)
        assertEquals("Y", questionActive("TOEF_RUN_G"))
    }

    @Test
    void deleteReconciliationQuestionRefusesACallerWithNoActiveTenant() {
        actAs(NO_TENANT_USERNAME, null)
        Map<String, Object> out = deleteQuestion("TOEF_DEL_K2")
        assertEquals(false, out.ok)
        assertEquals("Y", questionActive("TOEF_DEL_K2"))
    }

    @Test
    void deleteReconciliationQuestionSoftDeletesTheTenantsOwnQuestion() {
        // Its only child is stamped GORJANA: invisible to KREWE, so it does not block the delete.
        Map<String, Object> out = deleteQuestion("TOEF_DEL_K")
        assertEquals(true, out.ok, "${out.errors}")
        assertEquals("N", questionActive("TOEF_DEL_K"))
        assertEquals("Y", questionActive("TOEF_DEL_K_CHILD_G"), "the foreign child is untouched")
    }

    @Test
    void listDatabaseSourceConfigsStillReturnsOwnRowsAndOnlyThem() {
        Map<String, Object> out = withProductionAuthz {
            ec.service.sync().name("facade.DatabaseFacadeServices.list#DatabaseSourceConfigs").call()
        }
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        List ids = (out.configs as List)*.databaseSourceConfigId.findAll { it.toString().startsWith("TOEF_DSC_") }
        assertEquals(["TOEF_DSC_K"], ids)
    }

    @Test
    void aSharedPeerStillSeesTheOwnersDisabledEndpoint() {
        // The owner switches OMS off on its config, through the real store service, authz ON.
        Map<String, Object> catalog = listEndpoints(HWC_OWNED)
        assertTrue(catalog.ok as Boolean, "owner cannot list its own endpoints: ${catalog.errors}")
        List<String> allEndpoints = (catalog.endpoints as List)*.systemEnumId as List<String>
        assertTrue(allEndpoints.contains("OMS"), "test precondition: OMS is in the HotWax catalog: ${allEndpoints}")
        Map<String, Object> stored = withProductionAuthz {
            ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                    .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                                 configId            : HWC_OWNED,
                                 enabledSystemEnumIds: allEndpoints - ["OMS"]]).call()
        }
        assertTrue(stored.ok as Boolean, "owner store failed under production authz: ${stored.errors}")
        assertEquals(false, endpointState(listEndpoints(HWC_OWNED), "OMS"), "owner must read its own decision")

        // The peer the config is shared with must read the SAME decision. Its rows are stamped with
        // the owner's tenant, so a tenant-filtered read would find none — and absent means enabled.
        actAs(EDITOR_USERNAME, GORJANA)
        Map<String, Object> peer = listEndpoints(HWC_OWNED)
        assertTrue(peer.ok as Boolean, "shared peer was refused: ${peer.errors}")
        assertEquals(false, endpointState(peer, "OMS"),
                "the owner disabled OMS; a peer reading it as enabled is a fail-open")
        assertEquals(true, endpointState(peer, (allEndpoints - ["OMS"])[0]), "positive control: an enabled endpoint")
    }

    @Test
    void aRunStepWrittenWithoutATenantIsStampedAndStillHeartbeats() {
        // RunVerificationSupport passes an empty step context on one path. Under the filter an
        // unstamped step is invisible to its own heartbeat, which would then silently do nothing.
        Object step = null
        withProductionAuthz {
            underFacadeGrant {
                step = RunObservability.beginStep(ec, OBS_RUN_RESULT, [:], RunObservability.STAGE_EXTRACT_FILE1)
                RunObservability.heartbeatStageProgress(ec, OBS_RUN_RESULT, RunObservability.STAGE_EXTRACT_FILE1, 42, null)
            }
        }
        assertNotNull(step, "beginStep failed")
        def row = ec.entity.find("darpan.reconciliation.ReconciliationRunStep")
                .condition("reconciliationRunStepId", step.get("reconciliationRunStepId")).disableAuthz().useCache(false).one()
        assertEquals(KREWE, row.get("companyUserGroupId"), "the step must carry its run's tenant")
        assertEquals(42L, row.get("recordCount") as Long, "the heartbeat did not reach the step")
    }

    @Test
    void saveRuleSetRunReplacesUnstampedExclusionFilters() {
        String runName = "TOEF exclusion filter save"
        Map<String, String> created = createRuleSetRunWithUnstampedFilter(runName)
        String compareScopeId = created.compareScopeId

        Map<String, Object> saved = withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                    .parameters(ruleSetParams(created.savedRunId, runName) +
                            [file2ExcludeFilters: [[fieldExpression: "statusId", values: ["NEW"]]]])
                    .call()
        }
        assertTrue(saved?.ok == true, "save#RuleSetRun failed under production authz: ${saved?.errors ?: ec.message.errors}")
        List rows = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", compareScopeId)
                .condition("fileSide", "FILE_2").disableAuthz().useCache(false).list()
        assertEquals(["NEW"], rows*.filterValues, "the old unstamped row must be replaced, not kept beside the new one")
        assertEquals([KREWE], rows*.companyUserGroupId)
    }

    @Test
    void theStampSweepHealsUnstampedExclusionFilters() {
        Map<String, String> created = createRuleSetRunWithUnstampedFilter("TOEF exclusion filter sweep")
        Closure<List> visibleOnAFacadeRead = {
            List rows = null
            underFacadeGrant {
                rows = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter")
                        .condition("compareScopeId", created.compareScopeId).useCache(false).list()
            }
            return rows
        }
        assertEquals(0, visibleOnAFacadeRead().size(), "test precondition: the unstamped row is filtered out")

        ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps")
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")

        List rows = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", created.compareScopeId)
                .disableAuthz().useCache(false).list()
        assertEquals([KREWE], rows*.companyUserGroupId, "the sweep must stamp the filter row from its scope's rule set")
        assertEquals(1, visibleOnAFacadeRead().size(), "the healed row is visible to its own tenant again")
    }

    @Test
    void saveRuleSetRunReplacesUnstampedCompositeKeyFields() {
        // Same trap as the exclusion filters: RuleSetCompareSourceKeyField is under the tenant filter, so a
        // plain read in the delete pass misses an unstamped row and the recreate collides on its PK.
        String runName = "TOEF composite key save"
        Map<String, String> created = createRuleSetRunWithUnstampedKeyFields(runName)

        Map<String, Object> saved = withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                    .parameters(compositeKeyParams(created.savedRunId, runName, ["order_id", "sku"]))
                    .call()
        }
        assertTrue(saved?.ok == true, "save#RuleSetRun failed under production authz: ${saved?.errors ?: ec.message.errors}")
        List rows = keyFieldRows(created.compareScopeId)
        assertEquals(["order_id", "sku"], rows*.fieldExpression, "the old unstamped key fields must be replaced, not kept")
        assertEquals([KREWE, KREWE], rows*.companyUserGroupId)
    }

    @Test
    void theStampSweepHealsUnstampedCompositeKeyFields() {
        Map<String, String> created = createRuleSetRunWithUnstampedKeyFields("TOEF composite key sweep")
        Closure<List> visibleOnAFacadeRead = {
            List rows = null
            underFacadeGrant {
                rows = ec.entity.find("darpan.rule.RuleSetCompareSourceKeyField")
                        .condition("compareScopeId", created.compareScopeId).condition("fileSide", "FILE_1")
                        .useCache(false).list()
            }
            return rows
        }
        assertEquals(0, visibleOnAFacadeRead().size(), "test precondition: the unstamped rows are filtered out")

        ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps")
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")

        assertEquals([KREWE, KREWE], keyFieldRows(created.compareScopeId)*.companyUserGroupId,
                "the sweep must stamp key-field rows from their scope's rule set")
        assertEquals(2, visibleOnAFacadeRead().size(), "the healed rows are visible to their own tenant again")
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** A KREWE rule set with a two-field FILE_1 key, both key-field rows unstamped as if written before the stamp. */
    private Map<String, String> createRuleSetRunWithUnstampedKeyFields(String runName) {
        Map<String, Object> created = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters(compositeKeyParams(null, runName, ["order_id", "line_id"]))
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        String compareScopeId = created.savedRun.compareScopeId as String
        List rows = keyFieldRows(compareScopeId)
        assertEquals(["order_id", "line_id"], rows*.fieldExpression, "test precondition: two key-field rows")
        rows.each { it.set("companyUserGroupId", null).update() }
        return [savedRunId: created.savedRun.savedRunId as String, compareScopeId: compareScopeId]
    }

    private Map<String, Object> compositeKeyParams(String savedRunId, String runName, List<String> file1Key) {
        Map<String, Object> params = ruleSetParams(savedRunId, runName)
        // Both sides must carry the same number of key fields; only FILE_1's rows are the subject.
        params.remove("file1PrimaryIdExpression")
        params.remove("file2PrimaryIdExpression")
        params.file1PrimaryIdExpressions = file1Key
        params.file2PrimaryIdExpressions = file1Key
        return params
    }

    private List keyFieldRows(String compareScopeId) {
        return ec.entity.find("darpan.rule.RuleSetCompareSourceKeyField").condition("compareScopeId", compareScopeId)
                .condition("fileSide", "FILE_1").orderBy("sequenceNum").disableAuthz().useCache(false).list()
    }

    /** Hidden/visible problems for one tenant's facade read across all thirteen entities. */
    private List<String> tenantLeaks(String tenant, String ownSuffix) {
        assertEquals(tenant, ec.user.context.activeTenantUserGroupId, "test precondition: active tenant")
        List<String> problems = []
        underFacadeGrant {
            MARKER_FIELD.each { String entityName, String marker ->
                List<String> expected = ["TOEF_${prefix(entityName)}_${ownSuffix}".toString()]
                List<String> seen = (ec.entity.find(entityName).condition(marker, "in", markerValues(entityName))
                        .useCache(false).list()*.get(marker)) as List<String>
                if (seen.sort() != expected) problems << "${entityName}: list() saw ${seen}".toString()
                long count = ec.entity.find(entityName).condition(marker, "in", markerValues(entityName)).count()
                if (count != 1L) problems << "${entityName}: count() = ${count}".toString()
                ["K", "G", "N"].each { String suffix ->
                    if (suffix == "N" && TENANT_REQUIRED.contains(entityName)) return
                    def one = ec.entity.find(entityName).condition(pkOf(entityName, suffix)).useCache(false).one()
                    boolean own = suffix == ownSuffix
                    if (own && one == null) problems << "${entityName}: own row ${suffix} not found by PK".toString()
                    if (!own && one != null) problems << "${entityName}: row ${suffix} returned by one() on its full PK".toString()
                }
            }
        }
        return problems
    }

    private List<String> executionResultIds(boolean productionAuthz) {
        Closure<Map> get = {
            def svc = ec.service.sync().name("facade.ReconciliationFacadeServices.get#ReconciliationExecution")
                    .parameters([reconciliationExecutionId: "TOEF_EXEC"])
            if (!productionAuthz) svc.disableAuthz()
            return svc.call() as Map
        }
        Map out = productionAuthz ? withProductionAuthz(get) : get.call()
        assertEquals(true, out.ok, "${out.errors}")
        return (out.results as List)*.reconciliationRunResultId as List<String>
    }

    private Map<String, Object> deleteQuestion(String id) {
        return withProductionAuthz {
            ec.service.sync().name("facade.ReconciliationFacadeServices.delete#ReconciliationQuestion")
                    .parameters([reconciliationRunId: id]).call()
        } as Map<String, Object>
    }

    private String questionActive(String id) {
        return ec.entity.find("darpan.reconciliation.ReconciliationRun").condition("reconciliationRunId", id)
                .disableAuthz().useCache(false).one()?.get("isActive") as String
    }

    private Map<String, Object> listEndpoints(String configId) {
        return withProductionAuthz {
            ec.service.sync().name("facade.SourceEndpointFacadeServices.list#SourceConfigEndpoints")
                    .parameters([configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, configId: configId])
                    .call()
        } as Map<String, Object>
    }

    private static Object endpointState(Map<String, Object> listing, String systemEnumId) {
        return (listing.endpoints as List).find { it.systemEnumId == systemEnumId }?.isEnabled
    }

    /** A KREWE rule set with one FILE_2 exclusion row, unstamped as if written before the stamp existed. */
    private Map<String, String> createRuleSetRunWithUnstampedFilter(String runName) {
        Map<String, Object> created = ec.service.sync().name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters(ruleSetParams(null, runName) + [file2ExcludeFilters: [[fieldExpression: "statusId", values: ["OLD"]]]])
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        String compareScopeId = created.savedRun.compareScopeId as String
        List rows = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", compareScopeId)
                .disableAuthz().useCache(false).list()
        assertEquals(1, rows.size(), "test precondition: one exclusion row")
        rows.each { it.set("companyUserGroupId", null).update() }
        return [savedRunId: created.savedRun.savedRunId as String, compareScopeId: compareScopeId]
    }

    private Map<String, Object> ruleSetParams(String savedRunId, String runName) {
        Map<String, Object> params = [
                runName                 : runName,
                file1SystemEnumId       : "OMS",
                file1FileTypeEnumId     : "DftCsv",
                file1PrimaryIdExpression: "order_id",
                file2SystemEnumId       : "SHOPIFY",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ] as Map<String, Object>
        if (savedRunId) params.savedRunId = savedRunId else params.rules = []
        return params
    }

    /** Production posture: no harness grant on the stack, authz ON. Copied from MigrationAdminServiceSmokeTests. */
    private <T> T withProductionAuthz(Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            assertTrue(ec.artifactExecution.stack.isEmpty(), "test precondition: no inherited grant on the stack")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    /**
     * Runs {@code body} inside a facade service's frame, authorized for the logged-in user exactly as
     * the service engine authorizes a facade call (requiresAuthz). Finds made here are what a facade
     * service body's own finds are: covered by the DARPAN_FACADE_APP grant that carries the filter.
     */
    private void underFacadeGrant(Closure body) {
        Closure inner = {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE,
                    ArtifactExecutionInfo.AUTHZA_VIEW, true)
            try { body.call() } finally { ec.artifactExecution.pop(aei) }
        }
        if (ec.artifactExecution.authzDisabled) withProductionAuthz(inner) else inner.call()
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void seedTenant(String tenantId, String label) {
        insert("moqui.security.UserGroup", [userGroupId: tenantId, description: label,
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: tenantId, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId: tenantId, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
    }

    /** KREWE owns an OMS config and shares it with GORJANA (ConfigTenantAccess, DAR-BE-005). */
    private void seedSharedOmsConfig() {
        insert("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: HWC_OWNED,
                description: "TOEF shared OMS", companyUserGroupId: KREWE, baseUrl: "https://oms.toef.invalid", isActive: "Y"])
        insert("darpan.auth.ConfigTenantAccess", [configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                configId: HWC_OWNED, tenantUserGroupId: GORJANA, fromDate: FROM_DATE, grantedByUserId: EDITOR_ID])
    }

    /** One row per tenant (K = KREWE, G = GORJANA) and one unstamped (N) where the column allows it. */
    private void seedFilterMatrix() {
        withoutReferentialIntegrity {
            MARKER_FIELD.keySet().each { String entityName ->
                [K: KREWE, G: GORJANA, N: null].each { String suffix, String tenant ->
                    if (tenant == null && TENANT_REQUIRED.contains(entityName)) return
                    ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName,
                            fixtureRow(entityName, suffix) + [companyUserGroupId: tenant])
                }
            }
        }
    }

    private static String prefix(String entityName) {
        switch (entityName) {
            case "darpan.database.DatabaseSourceConfig": return "DSC"
            case "darpan.database.DatabaseSourceQuery": return "DSQ"
            case "darpan.reconciliation.NsSuiteQlSourceQuery": return "NSQ"
            case "darpan.reconciliation.Reconciliation": return "REC"
            case "darpan.reconciliation.ReconciliationRun": return "RUN"
            case "darpan.reconciliation.ReconciliationAutomationSourceFilter": return "AUTO"
            case "darpan.reconciliation.AutomationStreak": return "STRK"
            case "darpan.reconciliation.AutomationStreakBadge": return "STRKB"
            case "darpan.reconciliation.TenantStreakQuest": return "STRKQ"
            case "darpan.reconciliation.TenantStreakFact": return "STRKF"
            case "darpan.reconciliation.ReconciliationRunStep": return "STEP"
            case "darpan.reconciliation.SlackOAuthState": return "STATE"
            case "darpan.reconciliation.SlackWorkspaceInstall": return "SWI"
            case "darpan.reconciliation.SourceConfigEndpointAccess": return "CFG"
            case "darpan.rule.RuleSetCompareSourceFilter": return "SCF"
            case "darpan.rule.RuleSetConclusionRule": return "SCR"
            case "darpan.rule.RuleSetConclusionCondition": return "SCC"
            case "darpan.audit.Audit": return "AUD"
            case "darpan.audit.AuditStatement": return "MCS"
            case "darpan.audit.AuditCheck": return "MCCK"
            case "darpan.audit.AuditStatementCheck": return "MCSC"
            case "darpan.audit.AuditFinding": return "MCF"
            case "darpan.audit.AuditFindingRecord": return "MCFR"
            case "darpan.audit.AuditFindingCondition": return "MCFC"
            case "darpan.audit.AuditFindingEvent": return "MCFE"
            case "darpan.audit.AuditCheckRun": return "MCCR"
            case "darpan.audit.AuditSourceRead": return "MCSRD"
            case "darpan.audit.AuditDigest": return "AUDDG"
        }
        throw new IllegalArgumentException(entityName)
    }

    private static Map<String, Object> fixtureRow(String entityName, String s) {
        String id = "TOEF_${prefix(entityName)}_${s}".toString()
        switch (entityName) {
            case "darpan.database.DatabaseSourceConfig": return [databaseSourceConfigId: id]
            case "darpan.database.DatabaseSourceQuery": return [databaseSourceQueryId: id, databaseSourceConfigId: "TOEF_DSC_${s}".toString()]
            case "darpan.reconciliation.NsSuiteQlSourceQuery": return [nsSuiteQlSourceQueryId: id]
            case "darpan.reconciliation.Reconciliation": return [reconciliationId: id, reconciliationName: "TOEF ${s}".toString(), isActive: "Y", isArchived: "N"]
            case "darpan.reconciliation.ReconciliationRun": return [reconciliationRunId: id, reconciliationId: "TOEF_REC_${s}".toString(), isActive: "Y"]
            case "darpan.reconciliation.ReconciliationAutomationSourceFilter": return [automationId: id, fileSide: "FILE_1", sequenceNum: 1L,
                    fieldExpression: "statusId", operator: "IN", filterValues: "X"]
            case "darpan.reconciliation.ReconciliationRunStep": return [reconciliationRunStepId: id, reconciliationRunResultId: "TOEF_RR_${s}".toString(), stageCode: "RESOLVE"]
            case "darpan.reconciliation.SlackOAuthState": return [stateToken: id]
            case "darpan.reconciliation.SlackWorkspaceInstall": return [slackInstallId: id, teamId: "T_${s}".toString()]
            case "darpan.reconciliation.SourceConfigEndpointAccess": return [configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                    configId: id, systemEnumId: "OMS", isEnabled: "N"]
            case "darpan.rule.RuleSetCompareSourceFilter": return [compareScopeId: id, fileSide: "FILE_1", sequenceNum: 1L,
                    fieldExpression: "statusId", operator: "IN", filterValues: "X"]
            case "darpan.rule.RuleSetConclusionRule": return [compareScopeId: id, sequenceNum: 1L, conclusionEnumId: "UNEXPLAINED"]
            case "darpan.rule.RuleSetConclusionCondition": return [compareScopeId: id, sequenceNum: 1L, conditionSeq: 1L,
                    subject: "x", checkLabel: "y"]
            case "darpan.audit.Audit": return [auditId: id, auditCode: id, auditName: "x", periodEnumId: "AUDP_MONTH"]
            case "darpan.audit.AuditStatement": return [statementId: id, auditId: "TOEF_AUD_${s}".toString(), statementCode: id,
                    categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", statementText: "x"]
            case "darpan.audit.AuditCheck": return [checkId: id, checkCode: id, label: "x"]
            case "darpan.audit.AuditStatementCheck": return [statementId: id, checkId: "TOEF_MCCK_${s}".toString()]
            case "darpan.audit.AuditFinding": return [findingId: id, statementId: "TOEF_MCS_${s}".toString(),
                    checkId: "TOEF_MCCK_${s}".toString(), recordKey: id, period: "2026-10", stateEnumId: "AUDFS_OPEN", headline: "x",
                    firstFoundDate: java.sql.Date.valueOf("2026-10-07"), lastSeenDate: java.sql.Date.valueOf("2026-10-08")]
            case "darpan.audit.AuditFindingRecord": return [findingId: id, recordSeqId: "01", systemEnumId: "OMS",
                    recordType: "return", recordId: "x"]
            case "darpan.audit.AuditFindingCondition": return [findingId: id, sequenceNum: 1L, label: "x", passed: "N"]
            case "darpan.audit.AuditFindingEvent": return [findingId: id, eventSeqId: "01", eventStamp: FROM_DATE,
                    eventDate: java.sql.Date.valueOf("2026-10-07"), eventTypeEnumId: "AUDFE_FOUND", byTypeEnumId: "AUDAT_CHECK"]
            case "darpan.audit.AuditCheckRun": return [checkRunId: id, checkId: "TOEF_MCCK_${s}".toString(),
                    reportDate: java.sql.Date.valueOf("2026-10-08"), period: "2026-10", windowFromDate: java.sql.Date.valueOf("2026-10-01"),
                    windowThruDate: java.sql.Date.valueOf("2026-10-08"),
                    statusEnumId: "AUDCS_RAN", runStamp: FROM_DATE]
            case "darpan.audit.AuditSourceRead": return [checkRunId: id, readSeqId: "01", systemEnumId: "OMS",
                    statusEnumId: "AUDSR_COMPLETE", recordCount: 1L]
            case "darpan.reconciliation.AutomationStreak": return [automationId: id, streakStartDate: FROM_DATE, bestDays: 12L]
            case "darpan.reconciliation.AutomationStreakBadge": return [automationId: id, badgeKey: "HIGH_FIVE", timesEarned: 1L]
            // The tenant is part of this entity's primary key, so the PK read needs it on the fixture too.
            case "darpan.reconciliation.TenantStreakQuest": return [questKey: id, earnedDate: FROM_DATE,
                    companyUserGroupId: [K: KREWE, G: GORJANA][s]]
            case "darpan.reconciliation.TenantStreakFact": return [factId: id, awardKey: "HIGH_FIVE", usedDate: FROM_DATE,
                    companyUserGroupId: [K: KREWE, G: GORJANA][s]]
            case "darpan.audit.AuditDigest": return [auditDigestId: id, auditId: "TOEF_AUD_${s}".toString(),
                    chatSpaceId: "TOEF_SPACE_${s}".toString(), kindEnumId: "AUDDK_DAILY", sendTime: "07:00"]
        }
        throw new IllegalArgumentException(entityName)
    }

    private List<String> markerValues(String entityName) {
        return ["K", "G", "N"].collect { "TOEF_${prefix(entityName)}_${it}".toString() }
    }

    private Map<String, Object> pkOf(String entityName, String suffix) {
        Map<String, Object> row = fixtureRow(entityName, suffix)
        return ec.entity.getEntityDefinition(entityName).getPkFieldNames().collectEntries { [(it): row[it]] }
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /** Matrix rows are read fixtures only: their parents (run results, scopes, automations) need not exist. */
    private void withoutReferentialIntegrity(Closure body) {
        Connection connection = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.reconciliation.Reconciliation"))
        try {
            Statement statement = connection.createStatement()
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE")
            try {
                body.call()
            } finally {
                statement.execute("SET REFERENTIAL_INTEGRITY TRUE")
                statement.close()
            }
        } finally {
            connection.close()
        }
    }
}
