package reconciliation.rule

import org.junit.jupiter.api.Test
import org.kie.api.KieServices
import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieContainer

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals

/**
 * DAR-BE-289. Every rule set builds under its own release id. Before, every build used Drools' one default release id,
 * so a container opened after another rule set had built held THAT rule set's rules: two runs compiling at once took
 * each other's comparisons (seen locally: a totals run reporting the payments run's two mismatches).
 */
class RuleModuleReleaseTests {

    private static String drl(String pkg, String rule) {
        return """package ${pkg}
rule "${rule}"
when
    \$m : java.util.Map()
then
end
"""
    }

    private static Set<String> rulesOf(KieContainer container) {
        return container.getKieBase().getKiePackages().collectMany { it.getRules()*.getName() } as Set<String>
    }

    @Test
    void "a container opened after another rule set built still holds its own rules"() {
        KieServices ks = KieServices.Factory.get()
        ReleaseId totals = RuleEngineSupport.buildRuleModule(ks, "RS_TEST_TOTALS", "sig-totals",
                "reconciliation.rules.test.rs_test_totals", drl("reconciliation.rules.test.rs_test_totals", "TOTALS_RULE"))
        ReleaseId payments = RuleEngineSupport.buildRuleModule(ks, "RS_TEST_PAYMENTS", "sig-payments",
                "reconciliation.rules.test.rs_test_payments", drl("reconciliation.rules.test.rs_test_payments", "PAYMENTS_RULE"))
        assertNotEquals(totals, payments)
        assertEquals(["TOTALS_RULE"] as Set, rulesOf(ks.newKieContainer(totals)))
        assertEquals(["PAYMENTS_RULE"] as Set, rulesOf(ks.newKieContainer(payments)))
    }

    @Test
    void "a changed rule set gets a new version, so an old container never answers for new rules"() {
        KieServices ks = KieServices.Factory.get()
        ReleaseId before = RuleEngineSupport.buildRuleModule(ks, "RS_TEST_EDIT", "sig-1", "reconciliation.rules.test.rs_test_edit",
                drl("reconciliation.rules.test.rs_test_edit", "BEFORE"))
        ReleaseId after = RuleEngineSupport.buildRuleModule(ks, "RS_TEST_EDIT", "sig-2", "reconciliation.rules.test.rs_test_edit",
                drl("reconciliation.rules.test.rs_test_edit", "AFTER"))
        assertEquals(before.getArtifactId(), after.getArtifactId())
        assertNotEquals(before.getVersion(), after.getVersion())
        assertEquals(["BEFORE"] as Set, rulesOf(ks.newKieContainer(before)))
        assertEquals(["AFTER"] as Set, rulesOf(ks.newKieContainer(after)))
    }
}
