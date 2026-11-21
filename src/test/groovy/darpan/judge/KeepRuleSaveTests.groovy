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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b, Task 8. A rule saved on a Keep decision (CheckDecision stage CDS_KEEP_FILE_1 / CDS_KEEP_FILE_2) goes
 * through check#KeepRuleLines (SECA on store#Rule): a line Keep cannot run is refused with its reason and nothing is saved; the
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

        insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSystemSource", description: "Darpan system source"])
        ["ACME", "BRAVO"].each { insert("moqui.basic.Enumeration", [enumId: it, enumTypeId: "DarpanSystemSource", description: "Made-up system " + it]) }
        actAs(A)
        checkA = seedCheck(A, "keep-save", "ACME", "BRAVO")
        checkB = seedCheck(B, "keep-save-b", "ACME", "BRAVO")
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
    void anExistsLineOnAKeepDecisionIsRefusedAndNothingIsSaved() {
        String decision = decision(checkA, "CDS_KEEP_FILE_1", factTypeId)
        Map result = storeRule(decision, "has a list", 10, [[conditionSeqId: "01", conditionTypeId: "RCT_EXISTS", factTypeId: factTypeId,
                fieldName: "acme_orderId"]])
        assertRefused(result, "Keep line 01: an exists line reads a list, and Keep sets aside whole records only")
        assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]), "nothing saved")
    }

    @Test
    void aPieceAnAskAndAFieldToFieldLineAreRefusedWithTheirReasons() {
        String decision = decision(checkA, "CDS_KEEP_FILE_1", factTypeId)
        Map result = storeRule(decision, "three bad lines", 10, [
                [conditionSeqId: "01", conditionTypeId: "RCT_PIECE", factTypeId: factTypeId, compareValue: "SOME_PIECE"],
                [conditionSeqId: "02", conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: "ask_orders_status",
                 operatorEnumId: "ENTCO_EQUALS", compareValue: "OPEN"],
                [conditionSeqId: "03", conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: factTypeId, fieldName: "acme_orderId",
                 operatorEnumId: "ENTCO_EQUALS", compareFieldName: "acme_amount"]])
        assertRefused(result, "Keep line 01: a piece cannot be used in Keep; write its lines out")
        assertRefused(result, "Keep line 02: ask_orders_status comes from an ask, which runs after Keep")
        assertRefused(result, "Keep line 03: Keep compares one field with fixed values, not with another field")
        assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]), "nothing saved")
    }

    @Test
    void aValidLineSavesOnASystemPrefixedPairType() {
        String decision = decision(checkA, "CDS_KEEP_FILE_1", factTypeId)
        assertEquals([], storeRule(decision, "acme field", 10, [compare("01", "acme_orderId", "ENTCO_EQUALS", "X")]).errors)
        assertEquals(1, rows("rules.RuleSetRule", [ruleSetId: decision]).size())
    }

    @Test
    void aCheckWithNoSavedRunIsRefusedNotGuessed() {
        String bare = seedCheck(A, "keep-no-run", null, null)
        String decision = decision(bare, "CDS_KEEP_FILE_1", factTypeId)
        Map result = storeRule(decision, "no run", 10, [compare("01", "acme_orderId", "ENTCO_EQUALS", "X")])
        assertRefused(result, "Keep cannot check these lines: the check has no saved run with two systems, so its file 1 field names are unknown")
        assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]))
    }

    @Test
    void aFieldOfTheOtherSideIsRefusedOnTheSideItsStageNames() {
        String decision = decision(checkA, "CDS_KEEP_FILE_2", factTypeId)
        Map result = storeRule(decision, "other side", 10, [compare("01", "acme_orderId", "ENTCO_EQUALS", "X")])
        assertRefused(result, "Keep line 01: acme_orderId is not a bravo record field")
        assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]))
        // The same line on file 2's own field saves.
        assertEquals([], storeRule(decision, "own side", 10, [compare("01", "bravo_tranId", "ENTCO_EQUALS", "X")]).errors)
        assertEquals(1, rows("rules.RuleSetRule", [ruleSetId: decision]).size())
    }

    @Test
    void aFieldThatIsNotInTheSidesPairFactTypeIsRefusedAtSave() {
        // A made-up fact type ACME_PAIR names acme_ghost; the decision may read it, but the check's pair fact type does not hold it.
        String acme = ok(underFacadeGrant("create#rules.FactType", [description: "ACME_PAIR"])).factTypeId
        ["compareId", "acme_presence", "bravo_presence", "claim", "acme_ghost", "acme_orderId"].each { String name ->
            ok(underFacadeGrant("create#rules.FactField", [factTypeId: acme, fieldName: name, valueTypeEnumId: "FVT_TEXT"]))
        }
        String decision = decision(checkA, "CDS_KEEP_FILE_1", acme)
        Map result = storeRule(decision, "ghost", 10, [compare("01", "acme_ghost", "ENTCO_EQUALS", "X")], acme)
        assertRefused(result, "Keep line 01: acme_ghost is not a field of this check's pair fact type, so Keep cannot read it from a file 1 record")
        assertEquals([], rows("rules.RuleSetRule", [ruleSetId: decision]), "nothing saved")
        // A field the pair fact type holds saves.
        assertEquals([], storeRule(decision, "known", 20, [compare("01", "acme_orderId", "ENTCO_EQUALS", "X")], acme).errors)
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

    /** A check whose saved run reads system1 on file 1 and system2 on file 2 (null system1: no saved run). */
    private String seedCheck(String tenant, String code, String system1, String system2) {
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        String savedRunId = system1 ? "KRS_RUN_" + code : null
        if (savedRunId) {
            insert("darpan.rule.RuleSet", [ruleSetId: savedRunId, ruleSetName: "Keep save run " + code, version: "1.0",
                    companyUserGroupId: tenant, createdByUserId: EDITOR_ID])
        }
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: tenant, checkCode: code, label: "check ${code}".toString(),
                savedRunId: savedRunId])
        if (system1) {
            insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "KRS_SCOPE_" + code, ruleSetId: savedRunId, companyUserGroupId: tenant,
                    scopeMode: "COMPARE"])
            insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "KRS_SCOPE_" + code, fileSide: "FILE_1", systemEnumId: system1,
                    companyUserGroupId: tenant])
            insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "KRS_SCOPE_" + code, fileSide: "FILE_2", systemEnumId: system2,
                    companyUserGroupId: tenant])
        }
        insert("darpan.judge.CheckAsk", [checkId: checkId, askName: "orders", companyUserGroupId: tenant, subjectSide: "FILE_1",
                idField: "orderId", systemEnumId: "OMS", sourceConfigId: "CFG", keyField: "id", many: "N", keepRecordFields: "status"])
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
