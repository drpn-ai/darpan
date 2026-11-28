package reconciliation.rule

import org.junit.jupiter.api.Test
import org.kie.api.KieServices
import org.kie.api.builder.ReleaseId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S99-059: the one Drools module build behind the rule sets and the derive, conclusion-tree and brain engines.
 */
class EngineModuleTests {

    private static final String PATH = "src/main/resources/darpan/test/engine/engine.drl"

    private static String drl(String rule) {
        return """package darpan.test.engine
rule "${rule}"
when
    \$m : java.util.Map()
then
end
"""
    }

    private static final String BROKEN = "package darpan.test.engine\nrule \"broken\"\nwhen\n    \$m : NoSuchType(\nthen\nend\n"

    private static Set<String> rulesOf(EngineModule module) {
        return module.container.getKieBase().getKiePackages().collectMany { it.getRules()*.getName() } as Set<String>
    }

    @Test
    void anOpenedModuleHoldsItsRulesUnderAReleaseIdOfItsOwnAndCloseRemovesIt() {
        KieServices ks = KieServices.Factory.get()
        EngineModule first = EngineModule.open("darpan.test", "engine-", [(PATH): drl("FIRST")], getClass().classLoader, "Test engine")
        EngineModule second = EngineModule.open("darpan.test", "engine-", [(PATH): drl("SECOND")], getClass().classLoader, "Test engine")
        try {
            assertNotEquals(first.releaseId, second.releaseId)
            assertTrue(first.releaseId.artifactId.startsWith("engine-"))
            assertEquals(["FIRST"] as Set, rulesOf(first))
            assertEquals(["SECOND"] as Set, rulesOf(second))
            assertNotNull(ks.getRepository().getKieModule(first.releaseId))
        } finally {
            first.close()
            second.close()
        }
        assertNull(ks.getRepository().getKieModule(first.releaseId))
        assertNull(ks.getRepository().getKieModule(second.releaseId))
    }

    @Test
    void aBrokenBuildThrowsNamingTheEngineAndRestoresTheThreadLoader() {
        ClassLoader before = Thread.currentThread().getContextClassLoader()
        IllegalStateException e = assertThrows(IllegalStateException) {
            EngineModule.open("darpan.test", "engine-", [(PATH): BROKEN], getClass().classLoader, "Test engine")
        }
        assertTrue(e.message.startsWith("Test engine did not compile: "), e.message)
        assertSame(before, Thread.currentThread().getContextClassLoader())
    }

    @Test
    void aFailedBuildAnswersItsErrorsAndLeavesNoModuleInTheRepository() {
        KieServices ks = KieServices.Factory.get()
        ReleaseId rid = ks.newReleaseId("darpan.test", "engine-broken-" + UUID.randomUUID(), "1.0.0")
        List<String> errors = EngineModule.build(ks, rid, [(PATH): BROKEN])
        assertTrue(!errors.isEmpty())
        assertNull(ks.getRepository().getKieModule(rid))
        ReleaseId ok = ks.newReleaseId("darpan.test", "engine-ok-" + UUID.randomUUID(), "1.0.0")
        assertEquals([], EngineModule.build(ks, ok, [(PATH): drl("OK")]))
        ks.getRepository().removeKieModule(ok)
    }

    /** A tenant rule set that fails to build is refused as before, and its module no longer stays in the repository. */
    @Test
    void aRuleSetThatFailsToBuildIsRefusedAndLeavesNoModule() {
        KieServices ks = KieServices.Factory.get()
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            RuleEngineSupport.buildRuleModule(ks, "RS_TEST_BROKEN", "sig-broken", "darpan.test.engine", BROKEN)
        }
        assertTrue(e.message.startsWith("Drools build errors for RS_TEST_BROKEN: "), e.message)
        String version = "1.0.0-" + java.security.MessageDigest.getInstance("SHA-256").digest("sig-broken".getBytes("UTF-8"))
                .encodeHex().toString().take(16)
        assertNull(ks.getRepository().getKieModule(ks.newReleaseId("darpan.rules", "rs_test_broken", version)))
    }
}
