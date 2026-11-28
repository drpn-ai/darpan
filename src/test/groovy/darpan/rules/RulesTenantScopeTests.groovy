package darpan.rules

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
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
 * DAR-BE-318 (D2). Every moqui-rules row belongs to exactly one tenant inside Darpan.
 *
 * <p>The caller is a real DARPAN_USER member of two tenants (KREWE = A, GORJANA = B), with the security seed
 * loaded, authz ON and the harness frame removed, so a moqui-rules service called under a facade grant sees exactly
 * what it sees in production: the DARPAN_ACTIVE_COMPANY_SCOPE filter on reads, Darpan's RulesTenant EECAs on writes.
 * Darpan never calls moqui-rules with authz disabled, so neither does this test. An entity-auto update or delete writes
 * by primary key without a find, so no filter sees it: one block shows the write guard alone keeps such a write, and a
 * create naming another tenant's row, off that tenant's rows.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class RulesTenantScopeTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY

    // seedCompanyScope's user; internalLoginUser takes the USERNAME.
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String NO_TENANT_ID = "RTS_NO_TENANT_USER"
    private static final String NO_TENANT_USERNAME = "rts.notenant"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    /** A facade service the caller's DARPAN_USER grant covers; Darpan calls moqui-rules from inside such a service. */
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"

    private static final List<String> RULES_ENTITIES = ["rules.FactType", "rules.FactField", "rules.FactNode",
            "rules.FactNodeCondition", "rules.RuleSet", "rules.RuleSetFactType", "rules.RuleSetOutcome", "rules.RuleSetRule",
            "rules.RuleSetCondition", "rules.RuleSetAction", "rules.RuleSetActionField", "rules.RulePiece",
            "rules.RulePieceCondition", "rules.FileCheck", "rules.FileCheckRecord"].asImmutable()

    private static final String FACT_A = "RTS_ORDER_K"
    private static final String FACT_B = "RTS_ORDER_G"
    private static final String ORDERS_CSV = "amount,status\n150,OPEN\n20,OPEN\n"

    private ExecutionContext ec
    private String ruleSetA
    private String ruleA
    private String pieceA
    private String fileCheckA
    private String ruleSetB
    private String ruleB

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rules-tenant-scope")
        // The security model under test: DARPAN_FACADE_APP grants, their ArtifactAuthzFilter bindings and the
        // DARPAN_ACTIVE_COMPANY_SCOPE filter rows. dummyFks: the file forward-references data this bare H2 lacks.
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        // The comparison operators and moqui-rules' own types (line and action kinds, value and node kinds).
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // KREWE, and test.customer as its editor

        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserAccount", [userId: NO_TENANT_ID, username: NO_TENANT_USERNAME,
                                              userFullName: "No tenant", currentPassword: "", disabled: "N"])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: NO_TENANT_ID, fromDate: FROM_DATE])
        // UserFacade caches a user's groups per login, so the DARPAN_USER membership is seen only after a real switch.
        actAs(NO_TENANT_USERNAME, null)

        actAs(EDITOR_USERNAME, A)
        createFactType(FACT_A, true)
        ruleSetA = createDecision(FACT_A)
        ruleA = storeBigOpenRule(ruleSetA, FACT_A).out.ruleId as String
        pieceA = ec.entity.sequencedIdPrimary("rules.RulePiece", null, null)
        Map piece = asTenant(A, "moqui.rules.RuleServices.store#Piece", [pieceId: pieceA, factTypeId: FACT_A,
                conditions: [line("01", FACT_A, "status", "ENTCO_EQUALS", "OPEN")]])
        assertEquals([], piece.errors, "fixture: A's piece")
        Map checked = asTenant(A, "moqui.rules.RuleServices.check#File", [ruleSetId: ruleSetA, factTypeId: FACT_A,
                fileName: "orders.csv", fileText: ORDERS_CSV])
        assertEquals([], checked.errors, "fixture: A's file check")
        fileCheckA = checked.out.fileCheckId as String

        actAs(EDITOR_USERNAME, B)
        createFactType(FACT_B, false)
        ruleSetB = createDecision(FACT_B)
        // Its own name and priority, so moving A's rule into B's decision would hit no unique index: only the guard stops it.
        ruleB = storeBigOpenRule(ruleSetB, FACT_B, "big open (B)", 20).out.ruleId as String
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    // -----------------------------------------------------------------------------------------
    // Every row carries its tenant
    // -----------------------------------------------------------------------------------------

    @Test
    void aRuleStoredUnderTenantAStampsEveryRowItCreatesWithTenantA() {
        assertEquals(A, row("rules.RuleSet", [ruleSetId: ruleSetA]).companyUserGroupId)
        assertEquals([A], rows("rules.RuleSetFactType", [ruleSetId: ruleSetA])*.companyUserGroupId)
        assertEquals([A, A], rows("rules.RuleSetOutcome", [ruleSetId: ruleSetA])*.companyUserGroupId)
        assertEquals(A, row("rules.RuleSetRule", [ruleId: ruleA]).companyUserGroupId)
        List conditions = rows("rules.RuleSetCondition", [ruleId: ruleA])
        assertEquals(2, conditions.size(), "the rule has two lines")
        assertEquals([A, A], conditions*.companyUserGroupId)
        assertEquals([A], rows("rules.RuleSetAction", [ruleId: ruleA])*.companyUserGroupId)
    }

    @Test
    void aPieceAndAFactTypeAreTenantOwnedTheSameWay() {
        assertEquals(A, row("rules.RulePiece", [pieceId: pieceA]).companyUserGroupId)
        assertEquals([A], rows("rules.RulePieceCondition", [pieceId: pieceA])*.companyUserGroupId)
        assertEquals(A, row("rules.FactType", [factTypeId: FACT_A]).companyUserGroupId)
        assertEquals([A, A], rows("rules.FactField", [factTypeId: FACT_A])*.companyUserGroupId)
        assertEquals([A], rows("rules.FactNode", [factTypeId: FACT_A])*.companyUserGroupId)
        assertEquals([A], rows("rules.FactNodeCondition", [factTypeId: FACT_A])*.companyUserGroupId)
        assertEquals(A, row("rules.FileCheck", [fileCheckId: fileCheckA]).companyUserGroupId)
        assertEquals([A, A], rows("rules.FileCheckRecord", [fileCheckId: fileCheckA])*.companyUserGroupId)
        assertEquals(B, row("rules.FactType", [factTypeId: FACT_B]).companyUserGroupId)
        assertEquals(B, row("rules.RuleSet", [ruleSetId: ruleSetB]).companyUserGroupId)
    }

    @Test
    void tenantAStillEvaluatesAndExplainsItsOwnDecision() {
        Map evaluated = asTenant(A, "moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: ruleSetA,
                facts: [[factTypeId: FACT_A, fact: [amount: 150, status: "OPEN"], recordId: "r1"]]])
        assertEquals([], evaluated.errors)
        assertEquals(["BIG_OPEN"], evaluated.out.outcomes)

        Map explained = asTenant(A, "moqui.rules.RuleServices.explain#RuleSet", [ruleSetId: ruleSetA, factTypeId: FACT_A,
                fact: [amount: 20, status: "OPEN"]])
        assertEquals([], explained.errors)
        assertEquals([false], (explained.out.rules as List)*.matched)
    }

    @Test
    void tenantAStillChangesItsOwnFieldKindAgainstItsOwnLines() {
        // update#FactField reads the rule-and-line view under the filter: A's own line is found, so a kind it does not fit
        // is refused, and a kind it fits is saved.
        Map refused = asTenant(A, "moqui.rules.RuleServices.update#FactField", [factTypeId: FACT_A, fieldName: "status",
                valueTypeEnumId: "FVT_INTEGER"])
        assertRefused(refused, "Rule big open: value OPEN does not fit field status")
        assertEquals("FVT_TEXT", row("rules.FactField", [factTypeId: FACT_A, fieldName: "status"]).valueTypeEnumId)

        Map changed = asTenant(A, "moqui.rules.RuleServices.update#FactField", [factTypeId: FACT_A, fieldName: "amount",
                valueTypeEnumId: "FVT_INTEGER"])
        assertEquals([], changed.errors)
        assertEquals("FVT_INTEGER", row("rules.FactField", [factTypeId: FACT_A, fieldName: "amount"]).valueTypeEnumId)
        assertEquals([], asTenant(A, "moqui.rules.RuleServices.update#FactField", [factTypeId: FACT_A, fieldName: "amount",
                valueTypeEnumId: "FVT_DECIMAL"]).errors)
    }

    // -----------------------------------------------------------------------------------------
    // Tenant B, through the moqui-rules services under a facade grant (filter ON)
    // -----------------------------------------------------------------------------------------

    @Test
    void tenantBCannotEvaluateExplainOrEditTenantAsDecision() {
        Map<String, List<Map>> before = snapshot()

        Map evaluated = asTenant(B, "moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: ruleSetA,
                facts: [[factTypeId: FACT_A, fact: [amount: 150, status: "OPEN"]]]])
        assertRefused(evaluated, "Decision ${ruleSetA} does not exist")
        assertEquals(null, evaluated.out?.outcomes)

        Map explained = asTenant(B, "moqui.rules.RuleServices.explain#RuleSet", [ruleSetId: ruleSetA, factTypeId: FACT_A,
                fact: [amount: 150, status: "OPEN"]])
        assertRefused(explained, "Decision ${ruleSetA} does not exist")
        assertEquals(null, explained.out?.rules)

        Map storedRule = asTenant(B, "moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetA, ruleName: "big open",
                priority: 10, conditions: [line("01", FACT_A, "status", "ENTCO_EQUALS", "CLOSED")],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "OTHER"]]])
        assertRefused(storedRule, "Decision ${ruleSetA} does not exist")

        // B names A's minted piece id over a fact type of its own: moqui-rules finds no piece and goes to create one.
        Map storedPiece = asTenant(B, "moqui.rules.RuleServices.store#Piece", [pieceId: pieceA, factTypeId: FACT_B,
                conditions: [line("01", FACT_B, "status", "ENTCO_EQUALS", "OPEN")]])
        assertRefused(storedPiece, "Piece ${pieceA} does not exist")

        assertEquals(before, snapshot(), "no rules row changed in any tenant")
    }

    @Test
    void tenantBCannotChangeTenantAsFieldsFactTypeOrFileChecks() {
        Map<String, List<Map>> before = snapshot()

        Map kind = asTenant(B, "moqui.rules.RuleServices.update#FactField", [factTypeId: FACT_A, fieldName: "status",
                valueTypeEnumId: "FVT_INTEGER"])
        assertRefused(kind, "Fact type ${FACT_A} has no field status")

        Map checked = asTenant(B, "moqui.rules.RuleServices.check#File", [ruleSetId: ruleSetA, factTypeId: FACT_A,
                fileName: "orders.csv", fileText: ORDERS_CSV])
        assertRefused(checked, "Decision ${ruleSetA} does not exist")

        Map learned = asTenant(B, "moqui.rules.RuleServices.learn#FileFields", [factTypeId: FACT_A, fileName: "more.csv",
                fileText: "amount,status,channel\n1,OPEN,web\n"])
        assertRefused(learned, "Fact type ${FACT_A} does not exist")

        Map kept = asTenant(B, "moqui.rules.RuleServices.get#FileCheck", [fileCheckId: fileCheckA])
        assertRefused(kept, "File check ${fileCheckA} does not exist")

        assertEquals(before, snapshot(), "no rules row changed in any tenant")
    }

    @Test
    void tenantBsRuleCannotUseTenantAsPiece() {
        Map<String, List<Map>> before = snapshot()
        Map stored = asTenant(B, "moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetB, ruleName: "borrowed piece",
                priority: 5, conditions: [[conditionSeqId: "01", conditionTypeId: "RCT_PIECE", compareValue: pieceA]],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "BIG_OPEN"]]])
        assertRefused(stored, "piece ${pieceA} does not exist")
        assertEquals(before, snapshot())
    }

    @Test
    void aRootRowCreatedWithNoActiveTenantIsInvisibleToEveryTenant() {
        actAs(NO_TENANT_USERNAME, null)
        assertEquals(TenantAccessSupport.NO_ACTIVE_TENANT_FILTER_VALUE, ec.user.context.activeTenantUserGroupId,
                "test precondition: no active tenant")
        Map created = underFacadeGrant("create#rules.RuleSet", [description: "no tenant", firstMatchOnly: "N"])
        assertEquals([], created.errors)
        String orphanId = created.out.ruleSetId as String
        assertEquals(null, row("rules.RuleSet", [ruleSetId: orphanId]).companyUserGroupId, "left blank, owned by no tenant")
        // It has a status field, so only the tenant filter can answer update#FactField's find with "has no field".
        assertEquals([], underFacadeGrant("create#rules.FactType", [factTypeId: "RTS_NO_TENANT",
                fields: [[fieldName: "status", valueTypeEnumId: "FVT_TEXT"]]]).errors)
        assertEquals(null, row("rules.FactType", [factTypeId: "RTS_NO_TENANT"]).companyUserGroupId)
        assertEquals(null, row("rules.FactField", [factTypeId: "RTS_NO_TENANT", fieldName: "status"]).companyUserGroupId)

        // A tenantless writer cannot hang a row on a tenant's decision, nor stamp a root for a tenant.
        Map<String, List<Map>> before = snapshot()
        assertRefused(underFacadeGrant("create#rules.RuleSetOutcome", [ruleSetId: ruleSetA, outcomeId: "PLANTED"]),
                "Decision ${ruleSetA} does not exist")
        assertRefused(underFacadeGrant("create#rules.RuleSet", [description: "for A", companyUserGroupId: A]),
                "only for the active tenant")
        assertEquals(before, snapshot())

        for (String tenant in [A, B]) {
            Map evaluated = asTenant(tenant, "moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: orphanId,
                    facts: [[factTypeId: FACT_A, fact: [amount: 150, status: "OPEN"]]]])
            assertRefused(evaluated, "Decision ${orphanId} does not exist")
            Map kind = asTenant(tenant, "moqui.rules.RuleServices.update#FactField", [factTypeId: "RTS_NO_TENANT",
                    fieldName: "status", valueTypeEnumId: "FVT_TEXT"])
            assertRefused(kind, "Fact type RTS_NO_TENANT has no field status")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Primary-key writes, which no entity filter sees (authz on, under the facade grant)
    // -----------------------------------------------------------------------------------------

    @Test
    void aPrimaryKeyWriteCannotReachAnotherTenantsRows() {
        actAs(EDITOR_USERNAME, B)
        Map<String, List<Map>> before = snapshot()
        String lineSeq = rows("rules.RuleSetCondition", [ruleId: ruleA])[0].conditionSeqId

        assertRefused(underFacadeGrant("create#rules.RuleSetRule", [ruleSetId: ruleSetA, ruleName: "planted", priority: 99]),
                "Decision ${ruleSetA} does not exist")
        assertRefused(underFacadeGrant("update#rules.RuleSetRule", [ruleId: ruleA, description: "hijacked"]),
                "Rule ${ruleA} does not exist")
        assertRefused(underFacadeGrant("delete#rules.RuleSetCondition", [ruleId: ruleA, conditionSeqId: lineSeq]),
                "Rule line ${ruleA}/${lineSeq} does not exist")
        assertRefused(underFacadeGrant("create#rules.RuleSetFactType", [ruleSetId: ruleSetB, factTypeId: FACT_A]),
                "Fact type ${FACT_A} does not exist")
        assertRefused(underFacadeGrant("update#rules.RuleSetRule", [ruleId: ruleA, ruleSetId: ruleSetB]),
                "Rule ${ruleA} does not exist")

        assertEquals(before, snapshot(), "no rules row changed in any tenant")
    }

    @Test
    void aTenantCannotReparentItsOwnRuleIntoAnotherTenantsDecision() {
        // A owns the rule, so the row-owner check passes; only the parent check stops A planting it in B's decision.
        Map<String, List<Map>> before = snapshot()
        assertRefused(underFacadeGrant("update#rules.RuleSetRule", [ruleId: ruleA, ruleSetId: ruleSetB]),
                "Decision ${ruleSetB} does not exist")
        assertEquals(before, snapshot(), "no rules row changed in any tenant")
    }

    @Test
    void aTenantCannotPointItsOwnLineAtAnotherTenantsPiece() {
        // B owns the line and the write skips moqui-rules' own checks, so only the guard's piece reference check stops it.
        actAs(EDITOR_USERNAME, B)
        String lineSeq = rows("rules.RuleSetCondition", [ruleId: ruleB])[0].conditionSeqId
        Map<String, List<Map>> before = snapshot()
        assertRefused(underFacadeGrant("update#rules.RuleSetCondition", [ruleId: ruleB, conditionSeqId: lineSeq,
                conditionTypeId: "RCT_PIECE", compareValue: pieceA]), "Piece ${pieceA} does not exist")
        assertEquals(before, snapshot(), "no rules row changed in any tenant")
    }

    @Test
    void aRowCannotMoveToAnotherTenant() {
        Map<String, List<Map>> before = snapshot()
        assertRefused(underFacadeGrant("update#rules.RuleSetRule", [ruleId: ruleA, companyUserGroupId: B]),
                "cannot move to another tenant")
        assertRefused(underFacadeGrant("create#rules.RuleSet", [description: "for B", companyUserGroupId: B]),
                "only for the active tenant")
        assertEquals(before, snapshot())
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures and helpers
    // -----------------------------------------------------------------------------------------

    private void createFactType(String factTypeId, boolean withTree) {
        Map params = [factTypeId: factTypeId, description: "RTS order",
                      fields: [[fieldName: "amount", valueTypeEnumId: "FVT_DECIMAL"], [fieldName: "status", valueTypeEnumId: "FVT_TEXT"]]]
        if (withTree) params.nodes = [[nodeSeqId: "01", sequenceNum: 1, nodeTypeEnumId: "FNT_RECORD", entityName: "moqui.basic.Enumeration",
                                       conditions: [[conditionSeqId: "01", entityFieldName: "enumId", operatorEnumId: "ENTCO_EQUALS",
                                                     parameterName: "enumId"]]]]
        Map created = underFacadeGrant("create#rules.FactType", params)
        assertEquals([], created.errors, "fixture: fact type ${factTypeId}")
    }

    /** A decision, its id minted by the entity-auto create (sequenced), never chosen by the caller. */
    private String createDecision(String factTypeId) {
        Map created = underFacadeGrant("create#rules.RuleSet", [description: "RTS decision", firstMatchOnly: "N",
                factTypes: [[factTypeId: factTypeId]], outcomes: [[outcomeId: "BIG_OPEN"], [outcomeId: "OTHER"]]])
        assertEquals([], created.errors, "fixture: decision over ${factTypeId}")
        assertNotNull(created.out.ruleSetId)
        return created.out.ruleSetId as String
    }

    private Map storeBigOpenRule(String ruleSetId, String factTypeId, String ruleName = "big open", Integer priority = 10) {
        Map stored = underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: [line("01", factTypeId, "amount", "ENTCO_GREATER", "100"),
                                           line("02", factTypeId, "status", "ENTCO_EQUALS", "OPEN")],
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "BIG_OPEN"]]])
        assertEquals([], stored.errors, "fixture: rule of ${ruleSetId}")
        return stored
    }

    private static Map line(String seq, String factTypeId, String fieldName, String operatorEnumId, String compareValue) {
        return [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: fieldName,
                operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    private Map asTenant(String tenant, String serviceName, Map params) {
        actAs(EDITOR_USERNAME, tenant)
        return underFacadeGrant(serviceName, params)
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

    private def row(String entityName, Map pk) {
        def value = ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
        assertNotNull(value, "${entityName} ${pk} must exist")
        return value
    }

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
    }

    /** Every rules row of every tenant, so a refused call is shown to change nothing anywhere. */
    private Map<String, List<Map>> snapshot() {
        return RULES_ENTITIES.collectEntries { String entityName ->
            [(entityName): ec.entity.find(entityName).disableAuthz().useCache(false).list()
                    .collect { new TreeMap(it.getMap()) }.sort { it.toString() }]
        } as Map<String, List<Map>>
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
