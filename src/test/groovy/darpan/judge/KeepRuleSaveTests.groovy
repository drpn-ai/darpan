package darpan.judge

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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b, Task 8, narrowed by DAR-OPS-029 S04 (Aditi 2026-11-28: the judge Keep path is deleted; no run ever read a
 * Keep decision). A rule saved on a Keep decision (CheckDecision stage CDS_KEEP_FILE_1 / CDS_KEEP_FILE_2) is refused by
 * check#KeepRuleLines (SECA on store#Rule) and nothing is saved, instead of being checked line by line and then never read. The
 * same lines on a Must hold decision save. Tenant B's Keep decision row never gates tenant A's rule. Runs as the tenant user with
 * authz ON, as production does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepRuleSaveTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String NOT_SUPPORTED = "Keep decisions are not supported yet"

    private ExecutionContext ec
    private String checkA
    private String checkB
    private String factTypeId

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "keep-rule-save")
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
        // Seed data no longer names the Keep stages; a database loaded before still holds them, so a saved row can still name one.
        ["CDS_KEEP_FILE_1", "CDS_KEEP_FILE_2"].each { String stage ->
            insert("moqui.basic.Enumeration", [enumId: stage, enumTypeId: "CheckDecisionStage", description: "Keep (legacy row)"])
        }

        actAs(A)
        checkA = seedCheck(A, "keep-save")
        checkB = seedCheck(B, "keep-save-b")
        // A's pair fact type: file1 holds orderId and amount, file2 tranId, and one ask field.
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", [checkId: checkA, file1SystemEnumId: "ACME", file2SystemEnumId: "BRAVO",
                sampleKeys: [file1: ["orderId", "amount"], file2: ["tranId"], asks: ["ask_orders_status"]]]))
        factTypeId = generated.factTypeId
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsA() {
        actAs(A)
    }

    @Test
    void aRuleOnAKeepDecisionOfEitherSideIsRefusedAndNothingIsSaved() {
        ["CDS_KEEP_FILE_1", "CDS_KEEP_FILE_2"].each { String stage ->
            String decision = decision(checkA, stage, factTypeId)
            Map result = storeRule(decision, "a line Keep could run", 10, [compare("01", "acme_orderId", "ENTCO_EQUALS", "X")])
            assertRefused(result, NOT_SUPPORTED)
            assertFalse(result.errors.any { it?.contains("Keep line") }, "no line-by-line reasons: ${result.errors}")
            assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]), "${stage}: nothing saved")
        }
    }

    @Test
    void theSameLinesOnAMustHoldDecisionSave() {
        String decision = decision(checkA, "CDS_MUST_HOLD", factTypeId)
        Map result = storeRule(decision, "same lines", 10, [
                [conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: "ask_orders_status",
                 operatorEnumId: "ENTCO_EQUALS", compareValue: "OPEN"],
                [conditionSeqId: "02", conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: factTypeId, fieldName: "acme_orderId",
                 operatorEnumId: "ENTCO_EQUALS", compareFieldName: "acme_amount"]])
        assertEquals([], result.errors)
        assertEquals(1, rows("rules.RuleSetRule", [ruleSetId: decision]).size())
    }

    @Test
    void anotherTenantsKeepDecisionDoesNotGateThisTenantsRule() {
        // A's decision is Must hold for A's check; tenant B has a Keep row naming the same ruleSetId. It must not gate A.
        String decision = decision(checkA, "CDS_MUST_HOLD", factTypeId)
        insert("darpan.judge.CheckDecision", [checkId: checkB, stageEnumId: "CDS_KEEP_FILE_1", companyUserGroupId: B, ruleSetId: decision])
        Map result = storeRule(decision, "not gated", 10, [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId,
                fieldName: "ask_orders_status", operatorEnumId: "ENTCO_EQUALS", compareValue: "OPEN"]])
        assertEquals([], result.errors)
        assertEquals(1, rows("rules.RuleSetRule", [ruleSetId: decision]).size())
    }

    // ---------------------------------------------------------------------------------------

    private String decision(String checkId, String stage, String type) {
        String ruleSetId = ok(underFacadeGrant("create#rules.RuleSet", [description: "Keep save ${stage}".toString(), firstMatchOnly: "Y",
                factTypes: [[factTypeId: type]], outcomes: [[outcomeId: "ASIDE"]]])).ruleSetId
        insert("darpan.judge.CheckDecision", [checkId: checkId, stageEnumId: stage, companyUserGroupId: A, ruleSetId: ruleSetId])
        return ruleSetId
    }

    private Map storeRule(String ruleSetId, String ruleName, int priority, List<Map> lines, String type = factTypeId) {
        return underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: ruleName, priority: priority,
                conditions: lines.collect { new LinkedHashMap(it + [factTypeId: type]) },
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "ASIDE"]]])
    }

    private Map compare(String seq, String field, String op, String value) {
        return [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", fieldName: field, operatorEnumId: op, compareValue: value]
    }

    private String seedCheck(String tenant, String code) {
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: tenant, checkCode: code, label: "check ${code}".toString()])
        return checkId
    }

    private static Map ok(Map result) {
        assertEquals([], result.errors, "call failed")
        return result.out as Map
    }

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

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
    }

    private void insert(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }
}
