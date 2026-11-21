package darpan.judge

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 4. The tenant-owned config the judge reads: outcome meaning and fix text, a check's two
 * decisions and its parameter sets. Authz is ON with the facade grant (as RulesTenantScopeTests), so the
 * DARPAN_ACTIVE_COMPANY_SCOPE filter is live; a foreign checkId or decision id reads as "does not exist".
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeEntitiesTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SERVICES = "judge.JudgeConfigServices"

    private ExecutionContext ec
    private String ruleSetA
    private String ruleSetA2
    private String ruleSetB
    private String checkA
    private String checkB

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "judge-entities")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml", "component://darpan/data/JudgeSeedData.xml",
                "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        actAs(EDITOR_USERNAME, A)
        ruleSetA = createDecision(["FOUND", "OK"])
        ruleSetA2 = createDecision(["LATE"])
        checkA = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkA, companyUserGroupId: A, checkCode: "C1", label: "A check"])
        insert("darpan.judge.CheckDecision", [checkId: checkA, stageEnumId: "CDS_MUST_HOLD", companyUserGroupId: A, ruleSetId: ruleSetA])
        insert("darpan.judge.CheckDecision", [checkId: checkA, stageEnumId: "CDS_EXPLAINS", companyUserGroupId: A, ruleSetId: ruleSetA2])
        insert("darpan.judge.CheckParameterSet", [checkId: checkA, parameterSetId: "P1", companyUserGroupId: A, label: "Strict"])
        insert("darpan.judge.CheckParameter", [checkId: checkA, parameterSetId: "P1", parameterName: "days", companyUserGroupId: A, parameterValue: "3"])
        insert("darpan.judge.CheckParameter", [checkId: checkA, parameterSetId: "P1", parameterName: "tolerance", companyUserGroupId: A, parameterValue: "0.01"])
        insert("darpan.judge.CheckParameterSet", [checkId: checkA, parameterSetId: "P2", companyUserGroupId: A, label: "Loose"])

        actAs(EDITOR_USERNAME, B)
        ruleSetB = createDecision(["FOUND"])
        checkB = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkB, companyUserGroupId: B, checkCode: "C1", label: "B check"])
        insert("darpan.judge.CheckDecision", [checkId: checkB, stageEnumId: "CDS_MUST_HOLD", companyUserGroupId: B, ruleSetId: ruleSetB])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    @Test
    void storeRefusesAnOutcomeTheDecisionDoesNotList() {
        Map r = call("store#DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "NOT_LISTED", outcomeKindEnumId: "JOK_FINDING", label: "x"])
        assertRefused(r, "Decision ${ruleSetA} does not list outcome NOT_LISTED")
        assertNull(find("darpan.judge.DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "NOT_LISTED"]))
    }

    @Test
    void storeRefusesAnotherTenantsDecisionAndABadKind() {
        // B's decision outcome FOUND exists, but not for A.
        Map foreign = call("store#DecisionOutcome", [ruleSetId: ruleSetB, outcomeId: "FOUND", outcomeKindEnumId: "JOK_FINDING", label: "x"])
        assertRefused(foreign, "Decision ${ruleSetB} does not list outcome FOUND")
        assertNull(find("darpan.judge.DecisionOutcome", [ruleSetId: ruleSetB, outcomeId: "FOUND"]))
        assertRefused(call("store#DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "FOUND", outcomeKindEnumId: "JOK_BOGUS", label: "x"]),
                "Outcome kind JOK_BOGUS")
    }

    @Test
    void storeCreatesThenUpdatesAnOutcomeAndStampsItsTenant() {
        assertEquals([], call("store#DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "OK", outcomeKindEnumId: "JOK_EXPECTED",
                label: "Fine", fixText: "none"]).errors)
        def row = find("darpan.judge.DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "OK"])
        assertEquals(A, row.companyUserGroupId)
        assertEquals("Fine", row.label)
        assertEquals([], call("store#DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "OK", outcomeKindEnumId: "JOK_HIDDEN",
                label: "Quiet", fixText: "later", fixOwner: "Ops", fixWhereEnumId: "OMS"]).errors)
        row = find("darpan.judge.DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "OK"])
        assertEquals("JOK_HIDDEN", row.outcomeKindEnumId)
        assertEquals("Quiet", row.label)
        assertEquals("Ops", row.fixOwner)
        assertEquals("OMS", row.fixWhereEnumId)
    }

    @Test
    void getReturnsBothDecisionsTheirOutcomesAndTheParameterSets() {
        assertEquals([], call("store#DecisionOutcome", [ruleSetId: ruleSetA, outcomeId: "FOUND", outcomeKindEnumId: "JOK_FINDING",
                label: "Missing", fixText: "Create it", fixOwner: "HotWax Support", fixWhereEnumId: "OMS"]).errors)
        assertEquals([], call("store#DecisionOutcome", [ruleSetId: ruleSetA2, outcomeId: "LATE", outcomeKindEnumId: "JOK_EXPECTED",
                label: "Late but fine"]).errors)
        Map r = call("get#CheckJudgeConfig", [checkId: checkA])
        assertEquals([], r.errors)
        assertEquals(ruleSetA, r.out.mustHoldRuleSetId)
        assertEquals(ruleSetA2, r.out.explainsRuleSetId)
        Map outcomes = r.out.outcomes as Map
        Map found = outcomes["${ruleSetA}|FOUND".toString()] as Map
        assertEquals("JOK_FINDING", found.outcomeKindEnumId)
        assertEquals("Missing", found.label)
        assertEquals("Create it", found.fixText)
        assertEquals("HotWax Support", found.fixOwner)
        assertEquals("OMS", found.fixWhereEnumId)
        assertEquals("JOK_EXPECTED", (outcomes["${ruleSetA2}|LATE".toString()] as Map).outcomeKindEnumId)
        assertFalse(outcomes.keySet().any { it.toString().startsWith(ruleSetB) }, "no row of tenant B")
        List sets = r.out.parameterSets as List<Map>
        assertEquals(["P1", "P2"], sets*.parameterSetId)
        assertEquals("Strict", sets[0].label)
        assertEquals([days: "3", tolerance: "0.01"], sets[0].parameters)
        assertEquals([:], sets[1].parameters)
    }

    @Test
    void anotherTenantsCheckReadsAsDoesNotExist() {
        assertRefused(call("get#CheckJudgeConfig", [checkId: checkB]), "Check ${checkB} does not exist")
        actAs(EDITOR_USERNAME, B)
        assertRefused(call("get#CheckJudgeConfig", [checkId: checkA]), "Check ${checkA} does not exist")
        Map own = call("get#CheckJudgeConfig", [checkId: checkB])
        assertEquals([], own.errors)
        assertEquals(ruleSetB, own.out.mustHoldRuleSetId)
        assertNull(own.out.explainsRuleSetId)
        assertEquals([], own.out.parameterSets)
    }

    private String createDecision(List<String> outcomeIds) {
        Map created = underFacadeGrant("create#rules.RuleSet", [description: "judge decision", firstMatchOnly: "N",
                outcomes: outcomeIds.collect { [outcomeId: it] }])
        assertEquals([], created.errors, "fixture: decision")
        return created.out.ruleSetId as String
    }

    private Map call(String verbNoun, Map params) {
        return underFacadeGrant("${SERVICES}.${verbNoun}".toString(), params)
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    /**
     * Calls a service inside a facade service's frame, authorized for the logged-in user exactly as the service
     * engine authorizes a facade call: the DARPAN_FACADE_APP grant that binds DARPAN_ACTIVE_COMPANY_SCOPE.
     */
    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private static void assertRefused(Map result, String expected) {
        assertTrue(result.errors.any { it?.contains(expected.toString()) },
                "expected a refusal containing '${expected}', got errors ${result.errors}")
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
