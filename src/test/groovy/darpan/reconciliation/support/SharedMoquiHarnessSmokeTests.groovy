package darpan.reconciliation.support

import darpan.config.DarpanSettings
import darpan.dataset.KeptCopyStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-026: proves the shared harness. This class and SharedMoquiHarnessSecondSmokeTests do the same thing in
 * either order: at class start check the state is clean, then dirty it (a DB row, a cached entity row, a sequence, a
 * static seam, a DarpanSettings row) and leave it dirty. Whichever class runs second in the JVM therefore fails unless
 * the harness reset the DB, the entity caches, the sequence bank and the statics between them — on the same ECF.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedMoquiHarnessSmokeTests {
    static final String SETTING_KEY = 'darpan.reconciliation.automation.maxConcurrentExecutions'
    static final String PROBE_ENUM_TYPE = 'DarOps026Probe'
    static final String PROBE_ENUM_ID = 'DAR_OPS_026_PROBE'
    /** System.identityHashCode of the ECF each class ran on, in run order. */
    static final List<Integer> ecfSeen = Collections.synchronizedList([])

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), 'shared-moqui-harness-1')
        assertCleanStart(ec, 'SharedMoquiHarnessSmokeTests')
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
        // The ECF outlives the class in shared mode.
        assertNotNull(ReconciliationSmokeTestSupport.sharedEcf())
    }

    @Test
    void dirtiesEveryKindOfStateTheNextClassMustNotSee() {
        dirty(ec)
    }

    @Test
    void staticResetsFindsResetsOutsidePackageDarpan() {
        List<String> names = StaticResets.resetClassNames()
        assertTrue(names.contains('reconciliation.rule.RuleEngineSupport'), "RuleEngineSupport (package reconciliation.rule) not reset: ${names}")
        assertTrue(names.contains('darpan.config.DarpanSettings'), "DarpanSettings not reset: ${names}")
        assertTrue(names.contains('darpan.dataset.KeptCopyStore'), "KeptCopyStore not reset: ${names}")
        // Only this component's main classes: nothing from the framework or the test tree.
        assertTrue(names.every { !it.startsWith('org.moqui.') && !it.contains('Tests') }, "unexpected reset targets: ${names}")
    }

    static void assertCleanStart(ExecutionContext ec, String who) {
        // Same ECF as any class that ran before in this JVM, on this JVM's shared token.
        int ecf = System.identityHashCode(Moqui.getExecutionContextFactory())
        synchronized (ecfSeen) {
            if (!ecfSeen.isEmpty()) assertEquals(ecfSeen.last(), ecf, "${who}: a new ECF was booted instead of reusing the shared one")
            ecfSeen << ecf
        }
        assertSame(Moqui.getExecutionContextFactory(), ReconciliationSmokeTestSupport.sharedEcf())
        assertEquals(ecfSeen.size(), ReconciliationSmokeTestSupport.sharedClassCount())
        assertEquals("test-${ReconciliationSmokeTestSupport.sharedToken()}".toString(), System.getProperty('bitronix.tm.serverId'))
        System.out.println("SharedMoquiHarness: ${who} is class ${ecfSeen.size()} on ECF ${ecf}")

        // A working ec: authz off (dirty() then runs a service and entity writes on it).
        assertTrue(ec.artifactExecution.authzDisabled)
        // Empty tables: the previous class's rows are gone.
        assertEquals(0L, ec.entity.find('moqui.basic.Enumeration').useCache(false).count())
        assertEquals(0L, ec.entity.find('darpan.config.DarpanSetting').useCache(false).count())
        // No stale entity-cache row for a record that no longer exists.
        assertNull(ec.entity.find('moqui.basic.Enumeration').condition('enumId', PROBE_ENUM_ID).useCache(true).one())
        // Sequence bank cleared: ids start where a fresh boot starts them.
        assertEquals('100000', ec.entity.sequencedIdPrimary('moqui.basic.Enumeration', null, null))
        // Static seam and settings cache at their defaults.
        assertNull(KeptCopyStore.lineWrittenHook)
        int defaultValue = DarpanSettings.catalog()[SETTING_KEY].defaultValue as int
        assertEquals(defaultValue, DarpanSettings.intValue(SETTING_KEY))
        // Each class keeps its own data-manager folder, named by its own token as in unmarked mode.
        assertTrue((System.getProperty('darpan.data.manager.location') ?: '').startsWith('runtime://tmp/test-data-manager/shared-moqui-harness-'))
    }

    static void dirty(ExecutionContext ec) {
        ec.service.sync().name('create#moqui.basic.EnumerationType')
                .parameters([enumTypeId: PROBE_ENUM_TYPE, description: 'DAR-OPS-026 probe']).call()
        assertTrue(!ec.message.hasError(), ec.message.errorsString)
        ec.entity.makeValue('moqui.basic.Enumeration')
                .setAll([enumId: PROBE_ENUM_ID, enumTypeId: PROBE_ENUM_TYPE, description: 'probe']).create()
        assertNotNull(ec.entity.find('moqui.basic.Enumeration').condition('enumId', PROBE_ENUM_ID).useCache(true).one())

        KeptCopyStore.lineWrittenHook = { -> }

        int defaultValue = DarpanSettings.catalog()[SETTING_KEY].defaultValue as int
        ec.entity.makeValue('darpan.config.DarpanSetting')
                .setAll([settingKey: SETTING_KEY, settingValue: (defaultValue + 1) as String]).create()
        DarpanSettings.expire()
        assertEquals(defaultValue + 1, DarpanSettings.intValue(SETTING_KEY))
    }
}
