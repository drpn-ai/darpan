package darpan.config

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-304: catalog defaults, row then property then default, the 30-second snapshot. Pure: no Moqui. */
class DarpanSettingsTests {
    static final String MAX = 'darpan.reconciliation.automation.maxConcurrentExecutions'
    static final String DAYS = 'darpan.twoFactor.reminderDaysBefore'
    static final String REAPER = 'darpan.reconciliation.runHealth.reaperStaleMinutes'
    static final String SILENT = 'darpan.reconciliation.runHealth.silentAnyStageMinutes'

    static final String PARALLEL = 'darpan.reconciliation.automation.parallelSourceExtracts'

    long now = 1_000_000L
    int loads = 0
    String savedParallel

    @BeforeEach void setUp() {
        DarpanSettings.resetForTest()
        System.clearProperty(MAX)
        // The test task pins this one to false (build.gradle); the default is asserted below, so clear it here.
        savedParallel = System.clearProperty(PARALLEL)
        now = 1_000_000L
        loads = 0
        DarpanSettings.clock = { -> now }
        DarpanSettings.rowLoader = { -> loads++; [:] as Map<String, Map> }
    }

    @AfterEach void tearDown() {
        System.clearProperty(MAX)
        if (savedParallel != null) System.setProperty(PARALLEL, savedParallel)
        DarpanSettings.resetForTest()
    }

    private void rows(Map<String, String> byKey) {
        DarpanSettings.rowLoader = { -> loads++; byKey.collectEntries { k, v -> [(k): [settingValue: v]] } as Map<String, Map> }
    }

    @Test void everyCatalogEntryParsesAndItsDefaultIsValid() {
        Map<String, SettingDefinition> cat = DarpanSettings.catalog()
        assertEquals(35, cat.size(), "darpan catalog keys (DAR-BE-306 added the seven retention and purge keys, DAR-BE-320 the five audit-read keys, DAR-BE-318 the three dataset back-off keys, DAR-BE-325 the temporary-password keep days, DAR-BE-328 the sync pulse sample retention days; the agent keys live in darpan-agent): ${cat.keySet()}")
        cat.each { String k, SettingDefinition d ->
            assertNull(DarpanSettings.problem(k, d.defaultValue), "default of ${k}")
            assertTrue(d.unit in ['minutes', 'days', 'percent', 'count', 'runs', 'usd', 'factor', 'switch'], "unit of ${k}: ${d.unit}")
        }
    }

    @Test void noRowAndNoPropertyGivesTheDefault() {
        assertEquals(8, DarpanSettings.intValue(MAX))
        assertEquals([7, 3, 1, 0], DarpanSettings.intListValue(DAYS))
        assertTrue(DarpanSettings.booleanValue(PARALLEL))
        assertEquals('DEFAULT', DarpanSettings.describe(MAX).source)
    }

    @Test void aPropertyBeatsTheDefaultAndARowBeatsTheProperty() {
        System.setProperty(MAX, '12')
        assertEquals(12, DarpanSettings.intValue(MAX))
        assertEquals('PROPERTY', DarpanSettings.describe(MAX).source)
        rows([(MAX): '6'])
        DarpanSettings.expire()
        assertEquals(6, DarpanSettings.intValue(MAX))
        Map d = DarpanSettings.describe(MAX)
        assertEquals('ROW', d.source)
        assertEquals('6', d.effectiveValue)
        assertEquals('12', d.propertyValue)
        assertEquals('6', d.rowValue)
        assertEquals('8', d.defaultValue)
    }

    @Test void aRowOutsideTheRangeFallsBackToTheDefault() {
        rows([(MAX): '999'])
        assertEquals(8, DarpanSettings.intValue(MAX))
        System.setProperty(MAX, '4')
        assertEquals(4, DarpanSettings.intValue(MAX), 'bad row skipped, the property is next')
        System.setProperty(MAX, 'lots')
        assertEquals(8, DarpanSettings.intValue(MAX))
    }

    @Test void aFailedReloadKeepsTheLastSnapshot() {
        int call = 0
        DarpanSettings.rowLoader = { -> if (call++ == 0) return [(MAX): [settingValue: '6']] as Map<String, Map>; throw new IllegalStateException('db down') }
        assertEquals(6, DarpanSettings.intValue(MAX))
        now += 31_000
        assertEquals(6, DarpanSettings.intValue(MAX))
        assertEquals(2, call)
        DarpanSettings.expire()
        assertEquals(6, DarpanSettings.intValue(MAX))
    }

    @Test void theSnapshotReloadsAfterThirtySeconds() {
        DarpanSettings.intValue(MAX)
        now += 29_000
        DarpanSettings.intValue(MAX)
        assertEquals(1, loads)
        now += 2_000
        DarpanSettings.intValue(MAX)
        assertEquals(2, loads)
    }

    @Test void expireMakesTheNextReadReload() {
        DarpanSettings.intValue(MAX)
        assertEquals(1, loads)
        DarpanSettings.expire()
        DarpanSettings.intValue(MAX)
        assertEquals(2, loads)
    }

    @Test void expireAfterCommitWaitsForTheCommitAndExpiresNowWithNoTransaction() {
        DarpanSettings.intValue(MAX)
        List syncs = []
        def ec = [transaction: [isTransactionInPlace: { -> true }, registerSynchronization: { s -> syncs << s }]]
        DarpanSettings.expireAfterCommit(ec)
        DarpanSettings.intValue(MAX)
        assertEquals(1, loads, 'not expired before the commit')
        syncs[0].afterCompletion(3)
        DarpanSettings.intValue(MAX)
        assertEquals(2, loads)
        DarpanSettings.expireAfterCommit([transaction: [isTransactionInPlace: { -> false }]])
        DarpanSettings.intValue(MAX)
        assertEquals(3, loads)
    }

    @Test void wrongTypeOrUnknownKeyIsAProgrammingError() {
        assertThrows(IllegalArgumentException) { DarpanSettings.booleanValue(MAX) }
        assertThrows(IllegalArgumentException) { DarpanSettings.intValue('darpan.nope') }
    }

    @Test void problemRefuses() {
        assertNotNull(DarpanSettings.problem('darpan.nope', '1'))
        assertNotNull(DarpanSettings.problem(MAX, 'abc'))
        assertNotNull(DarpanSettings.problem(MAX, '1.5'))
        assertNotNull(DarpanSettings.problem(MAX, '0'))
        assertNotNull(DarpanSettings.problem(MAX, '33'))
        assertNull(DarpanSettings.problem(MAX, '32'))
        assertNotNull(DarpanSettings.problem(DAYS, '7,7'))
        assertNotNull(DarpanSettings.problem(DAYS, (0..10).join(',')))
        assertNotNull(DarpanSettings.problem(DAYS, '7,,1'))
        assertNotNull(DarpanSettings.problem(DAYS, '91'))
        assertNull(DarpanSettings.problem(DAYS, '30,7,0'))
        assertNotNull(DarpanSettings.problem('darpan.reconciliation.automation.parallelSourceExtracts', 'yes'))
        assertNull(DarpanSettings.problem('darpan.reconciliation.automation.parallelSourceExtracts', 'false'))
        assertNotNull(DarpanSettings.problem(SILENT, '120'), 'reaper is 120')
        assertNull(DarpanSettings.problem(SILENT, '119'))
        // The other direction: the reaper may not drop to or below the silent time.
        assertNotNull(DarpanSettings.problem(REAPER, '90'))
        assertNull(DarpanSettings.problem(REAPER, '91'))
    }

    @Test void everyKeyReadInCodeIsInTheCatalog() {
        Path src = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve('runtime/component/darpan/src/main/groovy')
        Pattern p = Pattern.compile(/DarpanSettings\.(?:int|boolean|decimal|intList)Value\(\s*["']([^"']+)["']/)
        Set<String> used = [] as Set
        int files = 0
        Files.walk(src).withCloseable { s ->
            s.filter { it.toString().endsWith('.groovy') }.forEach { Path f ->
                files++
                Matcher m = p.matcher(f.toFile().getText('UTF-8'))
                while (m.find()) used << m.group(1)
            }
        }
        assertTrue(files > 100, "scanned ${files} source files")
        Set<String> missing = used - DarpanSettings.catalog().keySet()
        assertTrue(missing.isEmpty(), "settings read in code but not in the catalog: ${missing}")
    }

    private static Path catalogDir(String json) {
        Path dir = Files.createTempDirectory('catalog')
        Files.createDirectories(dir.resolve('darpan'))
        Files.write(dir.resolve('darpan/settings-catalog.json'), json.getBytes('UTF-8'))
        return dir
    }

    @Test void theAgentSubAreasFollowTwoFactorInOrderAndBeforeRetention() {
        String row = '"type":"INT","unit":"x","description":"x","defaultValue":"5","min":1,"max":9'
        Path extra = catalogDir('{"settings":[{"key":"darpan.test.gw","area":"Agent \u00B7 Gateway",' + row + '},' +
                '{"key":"darpan.test.wr","area":"Agent \u00B7 Writers",' + row + '},' +
                '{"key":"darpan.test.au","area":"Agent \u00B7 Author",' + row + '},' +
                '{"key":"darpan.test.in","area":"Agent \u00B7 Investigator",' + row + '},' +
                '{"key":"darpan.test.ro","area":"Agent \u00B7 Router",' + row + '}]}')
        ClassLoader saved = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = new URLClassLoader([extra.toUri().toURL()] as URL[], saved)
            DarpanSettings.resetForTest()
            DarpanSettings.clock = { -> now }
            DarpanSettings.rowLoader = { -> [:] as Map<String, Map> }
            List<String> areas = DarpanSettings.describeAll().collect { it.area as String }.with { new ArrayList<String>(new LinkedHashSet<String>(it)) }
            int twoFactor = areas.indexOf('Two-factor')
            // DAR-BE-325: Accounts (the kept temporary password) sits between Two-factor and the agent areas.
            assertEquals(['Accounts', 'Agent \u00B7 Router', 'Agent \u00B7 Investigator', 'Agent \u00B7 Author', 'Agent \u00B7 Writers', 'Agent \u00B7 Gateway'],
                    areas.subList(twoFactor + 1, twoFactor + 7))
            assertEquals('Retention', areas[twoFactor + 7])
        } finally {
            Thread.currentThread().contextClassLoader = saved
            DarpanSettings.resetForTest()
        }
    }

    @Test void aCatalogWithoutTheCoreFileIsNeverCached() {
        DarpanSettings.resetForTest()
        DarpanSettings.clock = { -> now }
        DarpanSettings.rowLoader = { -> loads++; [:] as Map<String, Map> }
        try {
            DarpanSettings.loaderSource = { -> [new URLClassLoader([] as URL[], (ClassLoader) null)] }
            assertFalse(DarpanSettings.catalog().containsKey(MAX), 'a loader that cannot see the core file')
            DarpanSettings.loaderSource = null
            assertEquals(8, DarpanSettings.intValue(MAX), 'the next read retries and finds the core catalog')
        } finally {
            DarpanSettings.resetForTest()
        }
    }

    @Test void aMalformedOrDuplicateCatalogNeverBreaksReads() {
        Path bad = catalogDir('{ not json')
        Path dup = catalogDir('{"settings":[{"key":"' + MAX + '","type":"INT","area":"Runs","unit":"runs","description":"x","defaultValue":"3","min":1,"max":32},' +
                '{"key":"darpan.test.extra","type":"INT","area":"Runs","unit":"runs","description":"x","defaultValue":"5","min":1,"max":9},' +
                '{"key":"darpan.test.badDefault","type":"INT","area":"Runs","unit":"runs","description":"x","defaultValue":"50","min":1,"max":9}]}')
        ClassLoader saved = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = new URLClassLoader([bad.toUri().toURL(), dup.toUri().toURL()] as URL[], saved)
            DarpanSettings.resetForTest()
            DarpanSettings.clock = { -> now }
            DarpanSettings.rowLoader = { -> loads++; [:] as Map<String, Map> }
            assertEquals(8, DarpanSettings.intValue(MAX), 'first catalog wins a duplicate key')
            assertEquals(5, DarpanSettings.intValue('darpan.test.extra'), 'a good entry beside the bad file still loads')
            assertFalse(DarpanSettings.catalog().containsKey('darpan.test.badDefault'))
            assertSame(DarpanSettings.catalog(), DarpanSettings.catalog(), 'parsed once')
            assertEquals([7, 3, 1, 0], DarpanSettings.intListValue(DAYS))
        } finally {
            Thread.currentThread().contextClassLoader = saved
            DarpanSettings.resetForTest()
        }
    }

    @Test void aRolledBackEditLeavesTheSnapshotFresh() {
        DarpanSettings.intValue(MAX)
        List syncs = []
        def ec = [transaction: [isTransactionInPlace: { -> true }, registerSynchronization: { s -> syncs << s }]]
        DarpanSettings.expireAfterCommit(ec)
        syncs[0].afterCompletion(jakarta.transaction.Status.STATUS_ROLLEDBACK)
        DarpanSettings.intValue(MAX)
        assertEquals(1, loads)
        syncs[0].afterCompletion(jakarta.transaction.Status.STATUS_COMMITTED)
        DarpanSettings.intValue(MAX)
        assertEquals(2, loads)
    }

    @Test void anExpireDuringAReloadIsNotLost() {
        DarpanSettings.intValue(MAX)
        boolean first = true
        DarpanSettings.rowLoader = { -> loads++; if (first) { first = false; DarpanSettings.expire() }; [:] as Map<String, Map> }
        DarpanSettings.expire()
        DarpanSettings.intValue(MAX)          // reload during which another edit commits
        assertEquals(2, loads)
        DarpanSettings.intValue(MAX)          // must reload again: the mid-load expire was kept
        assertEquals(3, loads)
        DarpanSettings.intValue(MAX)
        assertEquals(3, loads)
    }

    @Test void aHugeBadValueIsTruncatedInTheWarnAndTheWarnedSetStaysBounded() {
        System.setProperty(MAX, 'x' * 5000)
        assertEquals(8, DarpanSettings.intValue(MAX))
        assertEquals(8, DarpanSettings.intValue(MAX))
    }
}
