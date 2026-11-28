package darpan.reconciliation.rule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-OPS-029 S35 (S02 R2). entity/RuleChildTenant.eecas.xml stamps a rule set's child rows on create: a blank
 * companyUserGroupId takes the parent's tenant, an explicit one is kept, and a child with no parent stays blank. Once
 * every create is stamped, the sweep that used to heal unstamped rows finds nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class RuleChildTenantStampEecaTests {

    private static final String TENANT = "S35_STAMP_T"
    private static final String OTHER = "S35_STAMP_O"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rule-child-tenant-stamp")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private String tenantOf(String entityName, Map<String, Object> pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()?.companyUserGroupId as String
    }

    /** Created as a system write (authz off, as a migration or import runs), without FKs: the tenants and the system enum
     *  are not this bare database's concern; each test creates the parents it needs. */
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
        assertEquals(0, result.stampedCount, "with the EECA stamping every create, the hourly sweep has nothing to heal")
    }
}
