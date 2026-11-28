package darpan.migration

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import reconciliation.rule.FieldComparisonRuleLogicGenerator

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * RULE_LOGIC_SHAPE_CHECK (DAR-OPS-029 S02, the OC6 release check): before the deploy that carries 913ecf6f, list every
 * enabled rule whose stored ruleLogic the compile gate refuses in production (not the generator's exact output, or a
 * validator violation), by tenant and rule set, with ids. Report only: a run, dry or not, changes nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class RuleLogicShapeCheckMigrationTests {
    private static final String SERVICE = "migration.MigrationServices.migrate#RuleLogicShapeCheck"
    private ExecutionContext ec

    private static final String HAND_WRITTEN = '''rule "HAND"
when
    $m : Map(this["file1"] != null)
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", null, null, "WARN", "x");
end'''

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rule-logic-shape-check")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // the company group type the tenant groups below need
        ["RLSC_ACME", "RLSC_KREWE"].each { String tenant ->
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: tenant,
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RLSC_RS_A", ruleSetName: "A", companyUserGroupId: "RLSC_ACME"]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RLSC_RS_K", ruleSetName: "K", companyUserGroupId: "RLSC_KREWE"]).createOrUpdate()
        String generated = FieldComparisonRuleLogicGenerator.generate(
                '{"type":"FIELD_COMPARISON","file1FieldPath":"sku","file2FieldPath":"sku","operator":"="}', "id", "id", "RLSC_GEN", "WARN", 0)
        [[ruleId: "RLSC_GEN", ruleSetId: "RLSC_RS_A", ruleLogic: generated, enabled: "Y"],
         [ruleId: "RLSC_TEXT", ruleSetId: "RLSC_RS_A", ruleText: "status is Pending", enabled: "Y"],
         [ruleId: "RLSC_HAND_A", ruleSetId: "RLSC_RS_A", ruleLogic: HAND_WRITTEN, enabled: "Y"],
         [ruleId: "RLSC_HAND_OFF", ruleSetId: "RLSC_RS_A", ruleLogic: HAND_WRITTEN, enabled: "N"],
         [ruleId: "RLSC_PWN_K", ruleSetId: "RLSC_RS_K", ruleLogic: 'rule "P" when $m : Map() then System.exit(1); end', enabled: "Y"]
        ].eachWithIndex { Map rule, int i ->
            String tenant = rule.ruleSetId == "RLSC_RS_A" ? "RLSC_ACME" : "RLSC_KREWE"
            ec.entity.makeValue("darpan.rule.Rule").setAll(rule + [sequenceNum: (i + 1) * 10, companyUserGroupId: tenant]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    private Map call(boolean dryRun) {
        Map out = ec.service.sync().name(SERVICE).parameters([dryRun: dryRun]).disableAuthz().call()
        assertTrue(out?.ok as boolean, "${ec.message.errors}")
        return out
    }

    @Test
    void theReportNamesEveryEnabledRuleTheGateRefusesByTenantAndRuleSet() {
        Map out = call(true)
        List<String> messages = (out.messages as List).collect { it.toString() }
        assertTrue(messages[0].startsWith("Enabled rules the compile gate refuses in production: 2 of "), messages[0])
        // A generated rule and a ruleText rule pass; a disabled rule is not counted; a validator violation counts.
        assertTrue(messages.any { it == "tenant RLSC_ACME, rule set RLSC_RS_A: RLSC_HAND_A" }, "${messages}")
        assertTrue(messages.any { it == "tenant RLSC_KREWE, rule set RLSC_RS_K: RLSC_PWN_K" }, "${messages}")
        assertTrue(messages.every { !it.contains("RLSC_GEN") && !it.contains("RLSC_TEXT") && !it.contains("RLSC_HAND_OFF") }, "${messages}")
        assertEquals(0, out.rowsUpdated as int, "report only")
    }

    @Test
    void aRealRunChangesNothing() {
        call(false)
        assertEquals("Y", ec.entity.find("darpan.rule.Rule").condition("ruleId", "RLSC_HAND_A").disableAuthz().useCache(false).one().enabled)
        assertEquals(HAND_WRITTEN, ec.entity.find("darpan.rule.Rule").condition("ruleId", "RLSC_HAND_A").disableAuthz().useCache(false).one().ruleLogic)
    }

    @Test
    void theRegistryRowShipsDisabledAndSupportsADryRun() {
        String seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toFile(),
                "runtime/component/darpan/data/MigrationRegistrySeedData.xml").text
        int at = seed.indexOf('migrationId="RULE_LOGIC_SHAPE_CHECK"')
        assertTrue(at >= 0, "the registry row exists")
        String row = seed.substring(at, seed.indexOf('/>', at))
        assertTrue(row.contains('serviceName="' + SERVICE + '"'), row)
        assertTrue(row.contains('enabled="N"'), "ships disabled: " + row)
        assertTrue(row.contains('supportsDryRun="Y"'), row)
    }
}
