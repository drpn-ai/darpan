package darpan.reconciliation.rule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
 * DAR-OPS-029 S35 (S02 R2). entity/RuleChildTenant.eecas.xml stamps a rule set's child rows on create: a blank
 * companyUserGroupId takes the parent's tenant, whoever writes it (the parent is read across tenants); an explicit one is
 * kept; a child with no parent stays blank; an update never restamps. Once every create is stamped, the sweep that used to
 * heal unstamped rows finds nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class RuleChildTenantStampEecaTests {

    private static final String TENANT = "S35_STAMP_T"
    private static final String OTHER = "S35_STAMP_O"
    private static final String EDITOR_ID = "S35_STAMP_EDITOR"
    private static final String EDITOR_USERNAME = "s35.stamp.editor"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    /** A facade service the caller's DARPAN_USER grant covers; its grant is what binds the active-tenant filter. */
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rule-child-tenant-stamp")
        // The DARPAN_FACADE_APP grants, their filter bindings and the DARPAN_ACTIVE_COMPANY_SCOPE rows; dummyFks as in
        // TenantOwnedEntityFilterRuntimeTests (the file forward-references data this bare H2 does not carry).
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            [TENANT, OTHER].each { String tenant ->
                ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: tenant,
                        groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            }
            // An editor of OTHER only: a filtered caller who cannot see TENANT's rule sets.
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: EDITOR_ID, username: EDITOR_USERNAME,
                    userFullName: "S35 stamp editor", currentPassword: "", disabled: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: EDITOR_ID,
                    fromDate: FROM_DATE]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: OTHER, userId: EDITOR_ID,
                    fromDate: FROM_DATE]).createOrUpdate()
            ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                    tenantUserGroupId: OTHER, userId: EDITOR_ID,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE]).createOrUpdate()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        if (ec?.user?.userId) ec.user.logoutUser()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private String tenantOf(String entityName, Map<String, Object> pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()?.companyUserGroupId as String
    }

    /** Created as a system write (authz off, as a migration or import runs), without FKs: the system enum is not this bare
     *  database's concern; each test creates the parents it needs. */
    private void create(String entityName, Map<String, Object> fields) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.getConnection(ec.entity.getEntityGroupName(entityName)).withCloseable { conn ->
                conn.createStatement().withCloseable { it.execute("SET REFERENTIAL_INTEGRITY FALSE") }
                try {
                    ec.entity.makeValue(entityName).setAll(fields).create()
                } finally {
                    conn.createStatement().withCloseable { it.execute("SET REFERENTIAL_INTEGRITY TRUE") }
                }
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    /** A rule set of TENANT with one compare scope and source; the children are created with no stamp. */
    private void seedRuleSet(String ruleSetId, String scopeId, String scopeTenant = null) {
        create("darpan.rule.RuleSet", [ruleSetId: ruleSetId, ruleSetName: ruleSetId, companyUserGroupId: TENANT])
        create("darpan.rule.RuleSetCompareScope", [compareScopeId: scopeId, ruleSetId: ruleSetId, companyUserGroupId: scopeTenant])
        create("darpan.rule.RuleSetCompareSource", [compareScopeId: scopeId, fileSide: "FILE_1", systemEnumId: "OMS"])
    }

    /** Runs {@code body} as EDITOR_USERNAME with OTHER active, authz ON, inside a facade frame: the filter applies to every find. */
    private void asFilteredEditorOfOther(Closure body) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OTHER)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertEquals(OTHER, ec.user.context.activeTenantUserGroupId, "test precondition: OTHER is the active tenant")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_VIEW, true)
            try { body.call() } finally { ec.artifactExecution.pop(aei) }
        } finally {
            ec.user.logoutUser()
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    @Test
    void everyChildCreatedWithoutAStampTakesItsRuleSetsTenant() {
        seedRuleSet("S35_RS_A", "S35_SCOPE_A")
        create("darpan.rule.Rule", [ruleId: "S35_RULE_A", ruleSetId: "S35_RS_A", ruleText: "x"])
        create("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "S35_SCOPE_A", fileSide: "FILE_1", sequenceNum: 1L,
                fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", filterValues: "POS"])
        create("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "S35_SCOPE_A", fileSide: "FILE_1", sequenceNum: 1L,
                fieldExpression: "orderId"])

        assertEquals(TENANT, tenantOf("darpan.rule.Rule", [ruleId: "S35_RULE_A"]))
        assertEquals(TENANT, tenantOf("darpan.rule.RuleSetCompareScope", [compareScopeId: "S35_SCOPE_A"]))
        assertEquals(TENANT, tenantOf("darpan.rule.RuleSetCompareSource", [compareScopeId: "S35_SCOPE_A", fileSide: "FILE_1"]))
        assertEquals(TENANT, tenantOf("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "S35_SCOPE_A", fileSide: "FILE_1", sequenceNum: 1L]))
        assertEquals(TENANT, tenantOf("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "S35_SCOPE_A", fileSide: "FILE_1", sequenceNum: 1L]))
    }

    @Test
    void aCallerWhoCannotSeeTheParentStillStampsTheParentsTenant() {
        seedRuleSet("S35_RS_F", "S35_SCOPE_F")
        Object parentSeen = "unread"
        asFilteredEditorOfOther {
            parentSeen = ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "S35_RS_F").useCache(false).one()
            ec.entity.makeValue("darpan.rule.Rule").setAll([ruleId: "S35_RULE_F", ruleSetId: "S35_RS_F", ruleText: "x"]).create()
            ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([compareScopeId: "S35_SCOPE_F", fileSide: "FILE_1",
                    sequenceNum: 1L, fieldExpression: "orderId"]).create()
        }
        assertNull(parentSeen, "test precondition: the filter hides TENANT's rule set from an editor of OTHER")
        assertEquals(TENANT, tenantOf("darpan.rule.Rule", [ruleId: "S35_RULE_F"]),
                "a child belongs to its parent's tenant, not the writer's, and is never left blank")
        assertEquals(TENANT, tenantOf("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "S35_SCOPE_F", fileSide: "FILE_1", sequenceNum: 1L]))
    }

    @Test
    void aWritersExplicitStampIsNeverReplaced() {
        // The scope says OTHER on purpose: the source copies the scope's stamp, and the scope's own explicit stamp stands.
        seedRuleSet("S35_RS_B", "S35_SCOPE_B", OTHER)
        create("darpan.rule.Rule", [ruleId: "S35_RULE_B", ruleSetId: "S35_RS_B", ruleText: "x", companyUserGroupId: OTHER])

        assertEquals(OTHER, tenantOf("darpan.rule.RuleSetCompareScope", [compareScopeId: "S35_SCOPE_B"]))
        assertEquals(OTHER, tenantOf("darpan.rule.Rule", [ruleId: "S35_RULE_B"]))
        assertEquals(OTHER, tenantOf("darpan.rule.RuleSetCompareSource", [compareScopeId: "S35_SCOPE_B", fileSide: "FILE_1"]),
                "a source follows its compare scope, as the sweep did")
    }

    @Test
    void anUpdateNeverRestampsARow() {
        seedRuleSet("S35_RS_U", "S35_SCOPE_U")
        create("darpan.rule.Rule", [ruleId: "S35_RULE_U", ruleSetId: "S35_RS_U", ruleText: "x"])
        assertEquals(TENANT, tenantOf("darpan.rule.Rule", [ruleId: "S35_RULE_U"]), "test precondition: stamped on create")
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("darpan.rule.Rule").setAll([ruleId: "S35_RULE_U", companyUserGroupId: null, ruleText: "y"]).update()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        assertNull(tenantOf("darpan.rule.Rule", [ruleId: "S35_RULE_U"]), "the EECAs run on create only")
    }

    @Test
    void aChildWhoseParentDoesNotExistStaysBlank() {
        create("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "S35_NO_SCOPE", fileSide: "FILE_1", sequenceNum: 1L,
                fieldExpression: "f", operator: "EXCLUDE_IN", filterValues: "v"])
        assertNull(tenantOf("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "S35_NO_SCOPE", fileSide: "FILE_1", sequenceNum: 1L]))
    }

    @Test
    void theSweepFindsNothingLeftToStamp() {
        seedRuleSet("S35_RS_C", "S35_SCOPE_C")
        create("darpan.rule.Rule", [ruleId: "S35_RULE_C", ruleSetId: "S35_RS_C", ruleText: "x"])
        Map result = ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps")
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        assertEquals(0, result.stampedCount, "with the EECA stamping every create, the sweep has nothing to heal")
    }
}
