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
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 6 (D7). generate#PairFactType runs on the request thread as the tenant user, authz ON, under a
 * facade grant (the harness frame removed), exactly as a production call. Two tenants: B cannot read or extend A's generated
 * fact type, and B generating for its own check makes its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PairFactTypeServiceTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SERVICE = "judge.PairFactServices.generate#PairFactType"

    private ExecutionContext ec
    private String checkA
    private String checkB

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "pair-fact-type")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        checkA = seedCheck(A, "returns-vs-credits", [days: "3", tolerance: "0.01"], "ask1", "status,total")
        checkB = seedCheck(B, "returns-vs-credits", [days: "9"], "ask1", "status")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsA() {
        actAs(EDITOR_USERNAME, A)
    }

    @Test
    void aFirstRunMintsTheFactTypeAndSavesItsIdOnTheCheck() {
        Map out = generate(checkA, [file1: ["orderId", "items[0].status"], file2: ["tranId"]])
        assertEquals([], out.errors)
        String factTypeId = out.out.factTypeId
        assertNotNull(factTypeId)
        assertEquals(factTypeId, row("darpan.audit.AuditCheck", [checkId: checkA]).pairFactTypeId)
        def type = row("rules.FactType", [factTypeId: factTypeId])
        assertEquals(A, type.companyUserGroupId)
        assertEquals("returns-vs-credits_PAIR", type.description)

        Map byName = fields(factTypeId)
        assertEquals(["ask_ask1_status", "ask_ask1_total", "compareId", "file1_presence", "file2_presence",
                      "file1_items_0__status", "file1_orderId", "file2_tranId", "param_days", "param_tolerance"].sort(), byName.keySet().sort())
        assertTrue(byName.values().every { it.valueTypeEnumId == "FVT_TEXT" })
        assertEquals("items[0].status", byName.file1_items_0__status.description)
        assertEquals([A], byName.values()*.companyUserGroupId.unique())
    }

    @Test
    void theSystemsGivePrefixesAndTwoSameSystemSidesGetNumbers() {
        String check = seedCheck(A, "same-system", [:], null, null)
        Map out = generate(check, [file1: ["id"], file2: ["id"]], [file1SystemEnumId: "OMS", file2SystemEnumId: "OMS"])
        assertEquals([], out.errors)
        assertEquals(["compareId", "hotwax1_id", "hotwax1_presence", "hotwax2_id", "hotwax2_presence"],
                fields(out.out.factTypeId as String).keySet().sort())
    }

    @Test
    void aRerunWithOneMoreKeyKeepsTheOldFieldsAndTheSameFactType() {
        String check = seedCheck(A, "rerun", [:], null, null)
        String first = generate(check, [file1: ["a", "b"], file2: ["c"]]).out.factTypeId
        Map again = generate(check, [file1: ["a", "b", "d"], file2: []])
        assertEquals([], again.errors)
        assertEquals(first, again.out.factTypeId)
        Set<String> names = fields(first).keySet()
        assertTrue(names.containsAll(["file1_a", "file1_b", "file1_d", "file2_c"]), "old fields stay, new one added: ${names}")
        assertEquals(1, rows("rules.FactType", [description: "rerun_PAIR"]).size())
    }

    @Test
    void aRerunAddingACollidingKeyLeavesExistingFieldsUntouchedAndReturnsKeyNames() {
        String check = seedCheck(A, "collide", [:], null, null)
        Map first = generate(check, [file1: ["a-b"], file2: []])
        String type = first.out.factTypeId
        assertEquals([ "a-b": "file1_a_b"], first.out.keyNames.file1)
        Map before = fields(type).collectEntries { k, v -> [(k): v.description] }
        Map again = generate(check, [file1: ["a_b", "a b", "a-b"], file2: []])
        assertEquals([], again.errors)
        assertEquals("file1_a_b", again.out.keyNames.file1["a-b"])
        assertEquals(["file1_a_b", "file1_a_b_2", "file1_a_b_3"], again.out.keyNames.file1.values().sort())
        Map after = fields(type).collectEntries { k, v -> [(k): v.description] }
        before.each { k, v -> assertEquals(v, after[k], "field ${k} unchanged") }
        assertEquals("a b", after.file1_a_b_2)
        assertEquals("a_b", after.file1_a_b_3)
    }

    @Test
    void aKindOverrideGoesThroughUpdateFactField() {
        String check = seedCheck(A, "kinds", [:], null, null)
        Map out = generate(check, [file1: ["amount"], file2: []], [kindOverrides: [file1_amount: "FVT_DECIMAL"]])
        assertEquals([], out.errors)
        assertEquals("FVT_DECIMAL", fields(out.out.factTypeId as String).file1_amount.valueTypeEnumId)
        // A second run without the override leaves the chosen kind alone.
        assertEquals([], generate(check, [file1: ["amount"], file2: []]).errors)
        assertEquals("FVT_DECIMAL", fields(out.out.factTypeId as String).file1_amount.valueTypeEnumId)
    }

    @Test
    void tenantBCannotGenerateForTenantAsCheckNorExtendItsFactType() {
        String aType = generate(checkA, [file1: ["orderId"], file2: []]).out.factTypeId
        Map before = fields(aType)
        actAs(EDITOR_USERNAME, B)
        Map refused = generate(checkA, [file1: ["planted"], file2: []])
        assertRefused(refused, "Check ${checkA} does not exist")
        assertEquals(aType, row("darpan.audit.AuditCheck", [checkId: checkA]).pairFactTypeId, "A's check unchanged")
        assertEquals(before, fields(aType), "no field planted on A's type")

        // B cannot extend A's type by the moqui-rules entity services either.
        assertRefused(underFacadeGrant("create#rules.FactField", [factTypeId: aType, fieldName: "planted", valueTypeEnumId: "FVT_TEXT"]),
                "Fact type ${aType} does not exist")
        assertRefused(underFacadeGrant("moqui.rules.RuleServices.update#FactField", [factTypeId: aType, fieldName: "file1_orderId",
                valueTypeEnumId: "FVT_INTEGER"]), "Fact type ${aType} has no field file1_orderId")
        assertEquals(before, fields(aType))
    }

    @Test
    void tenantBGeneratingForItsOwnCheckMakesItsOwnFactType() {
        String aType = generate(checkA, [file1: ["orderId"], file2: []]).out.factTypeId
        Map aBefore = fields(aType)
        actAs(EDITOR_USERNAME, B)
        Map out = generate(checkB, [file1: ["orderId"], file2: ["tranId"]])
        assertEquals([], out.errors)
        String bType = out.out.factTypeId
        assertNotEquals(aType, bType)
        assertEquals(B, row("rules.FactType", [factTypeId: bType]).companyUserGroupId)
        assertEquals([B], fields(bType).values()*.companyUserGroupId.unique())
        assertEquals(["ask_ask1_status", "compareId", "file1_orderId", "file1_presence", "file2_presence", "file2_tranId", "param_days"],
                fields(bType).keySet().sort())
        assertEquals(aBefore, fields(aType), "A's type is untouched")
    }

    @Test
    void anUnknownOrBlankCheckIsRefused() {
        assertRefused(generate("NOPE", [file1: [], file2: []]), "Check NOPE does not exist")
        assertNull(generate("NOPE", [file1: [], file2: []]).out?.factTypeId)
    }

    // ---------------------------------------------------------------------------------------

    private String seedCheck(String tenant, String code, Map<String, String> params, String askName, String keep) {
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: tenant, checkCode: code, label: "check ${code}".toString()])
        if (params) {
            insert("darpan.judge.CheckParameterSet", [checkId: checkId, parameterSetId: "P1", companyUserGroupId: tenant, label: "Set"])
            params.each { String name, String value ->
                insert("darpan.judge.CheckParameter", [checkId: checkId, parameterSetId: "P1", parameterName: name,
                        companyUserGroupId: tenant, parameterValue: value])
            }
        }
        if (askName) {
            insert("darpan.judge.CheckAsk", [checkId: checkId, askName: askName, companyUserGroupId: tenant, subjectSide: "FILE_1",
                    idField: "orderId", systemEnumId: "OMS", sourceConfigId: "CFG", keyField: "id", many: "N", keepRecordFields: keep])
        }
        return checkId
    }

    private Map generate(String checkId, Map sampleKeys, Map extra = [:]) {
        return underFacadeGrant(SERVICE, [checkId: checkId, sampleKeys: sampleKeys] + extra)
    }

    private Map<String, Object> fields(String factTypeId) {
        return rows("rules.FactField", [factTypeId: factTypeId]).collectEntries { [(it.fieldName as String): it] }
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

    private def row(String entityName, Map pk) {
        def value = ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
        assertNotNull(value, "${entityName} ${pk} must exist")
        return value
    }

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
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
