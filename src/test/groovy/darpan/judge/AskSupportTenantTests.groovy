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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 5. The CheckAsk read is tenant-scoped: a foreign checkId gives no asks. Authz ON, as JudgeEntitiesTests.
 * DAR-OPS-029 S04: the read is get#CheckJudgeConfig's (once per judging); AskSupport fetches only from the rows it is given.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AskSupportTenantTests {

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
    private List<Map> asked
    private Closure fetcher

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "ask-support-tenant")
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
        checkA = seedCheck(A, "NS")
        checkB = seedCheck(B, "OMS")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void stub() {
        asked = []
        fetcher = { Object c, Map lookup, List<String> ids, String tenantId, List<String> warnings ->
            asked.add(lookup)
            return ids.collectEntries { [(it): [status: "S-${lookup.systemEnumId}".toString()]] }
        }
        actAs(EDITOR_USERNAME, A)
    }

    @Test
    void anOwnCheckGivesItsAsksFromTheRowsStoredColumns() {
        Map config = underFacadeGrant { configOf(checkA) }
        assertEquals([], config.errors)
        Map out = AskSupport.fetchAsks(ec, config.asks as List<Map>, A, [["P1", [orderId: "O1"], null]] as List<Map>, [], fetcher)
        assertEquals([ask_ask1_status: "S-NS"], out["P1"])
        assertEquals(["NS"], asked*.systemEnumId)
    }

    @Test
    void aForeignCheckIdGivesNoAsks() {
        Map config = underFacadeGrant { configOf(checkB) }
        assertTrue(config.errors.any { it.contains("Check ${checkB} does not exist") }, "${config.errors}")
        assertEquals(null, config.asks, "no foreign ask is read")
    }

    @Test
    void anotherActiveTenantReadsOnlyItsOwnAsks() {
        // The explicit tenant condition and the active-tenant filter must both hold: with B active, A's check does not exist.
        actAs(EDITOR_USERNAME, B)
        Map config = underFacadeGrant { configOf(checkA) }
        assertTrue(config.errors.any { it.contains("Check ${checkA} does not exist") }, "${config.errors}")
        Map own = underFacadeGrant { configOf(checkB) }
        assertEquals([], own.errors)
        assertEquals([["ask1", "OMS", B]], (own.asks as List<Map>).collect { [it.askName, it.systemEnumId, it.companyUserGroupId] })
    }

    /** get#CheckJudgeConfig for one check: [asks, errors]; errors read and cleared. */
    private Map configOf(String checkId) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("judge.JudgeConfigServices.get#CheckJudgeConfig").parameters([checkId: checkId]).call()
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [asks: errors ? null : out?.asks, errors: errors]
    }

    private String seedCheck(String tenant, String system) {
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: tenant, checkCode: "C1", label: "check ${tenant}".toString()])
        insert("darpan.judge.CheckAsk", [checkId: checkId, askName: "ask1", companyUserGroupId: tenant, subjectSide: "FILE_1",
                idField: "orderId", systemEnumId: system, sourceConfigId: "CFG", keyField: "id", many: "N"])
        return checkId
    }

    private Map underFacadeGrant(Closure<Map> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return body.call()
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
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
