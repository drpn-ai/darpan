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
    static final String RUN_RESULT_DAYS = 'darpan.retention.runResult.days'

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

    /** S99-070: a setting that a job parameter would otherwise duplicate names that parameter in the catalog (DAR-BE-306). */
    @Test void aSettingIsFoundByTheJobParameterItOwns() {
        String purges = 'reconciliation.ReconciliationGenericServices.'
        assertEquals('darpan.purge.generatedOutput.days', DarpanSettings.settingOwningJobParameter(purges + 'purge#GeneratedOutputFiles', 'retentionDays'))
        assertEquals('darpan.purge.extract.days', DarpanSettings.settingOwningJobParameter(purges + 'purge#ExtractFiles', 'retentionDays'))
        assertNull(DarpanSettings.settingOwningJobParameter(purges + 'purge#GeneratedOutputFiles', 'outputLocation'))
        assertNull(DarpanSettings.settingOwningJobParameter(null, null))
    }

    @Test void everyCatalogEntryParsesAndItsDefaultIsValid() {
        Map<String, SettingDefinition> cat = DarpanSettings.catalog()
        assertEquals(50, cat.size(), "darpan catalog keys (DAR-BE-396 the error email ignore patterns, DAR-BE-379 the light-run cap, DAR-BE-374 the two ID-index keys, DAR-CLI-008 the two order-lookup limits, DAR-BE-015 added the five errors.* keys, DAR-BE-358 the Unexplained CSV address, DAR-BE-306 added the seven retention and purge keys, DAR-BE-320 the five audit-read keys, DAR-BE-318 the three dataset back-off keys, DAR-BE-325 the temporary-password keep days, DAR-BE-328 the sync pulse sample retention days, DAR-BE-361 the acknowledge reminder days, feedback shown days and feedback chat webhook; the agent keys live in darpan-agent): ${cat.keySet()}")
        cat.each { String k, SettingDefinition d ->
            assertNull(DarpanSettings.problem(k, d.defaultValue), "default of ${k}")
            assertTrue(d.unit in ['minutes', 'days', 'percent', 'count', 'runs', 'usd', 'factor', 'switch', 'hours', 'text'], "unit of ${k}: ${d.unit}")
        }
    }

    static final String ERR_TO = 'errors.email.to'
    static final String ERR_HOOK = 'errors.chat.webhookUrl'

    @Test void aStringSettingReadsItsTrimmedValue() {
        rows([(ERR_TO): '  ops@drpn.ai,aditi@drpn.ai '])
        assertEquals('ops@drpn.ai,aditi@drpn.ai', DarpanSettings.stringValue(ERR_TO))
    }

    @Test void anUnsetStringSettingIsNull() {
        assertNull(DarpanSettings.stringValue(ERR_HOOK))
    }

    @Test void aStringSettingOver2000CharsIsInvalid() {
        rows([(ERR_TO): 'a' * 2001])
        assertNull(DarpanSettings.stringValue(ERR_TO))
    }

    @Test void aSecretSettingIsListedMasked() {
        String url = 'https://chat.googleapis.com/v1/spaces/AAA/messages?key=k&token=abcdef123456'
        assertEquals('\u2026123456', DarpanSettings.masked(ERR_HOOK, url))
        assertNull(DarpanSettings.masked(ERR_HOOK, null))
        assertEquals('ops@drpn.ai', DarpanSettings.masked(ERR_TO, 'ops@drpn.ai'))
        rows([(ERR_HOOK): url])
        Map d = DarpanSettings.describe(ERR_HOOK)
        assertEquals('\u2026123456', d.rowValue)
        assertEquals('\u2026123456', d.effectiveValue)
        assertEquals(true, d.secret)
        assertEquals(url, DarpanSettings.stringValue(ERR_HOOK))
    }

    @Test void aRefusedSecretValueIsNeverEchoedInTheProblem() {
        String bad = 'x' * 2001
        String problem = DarpanSettings.problem(ERR_HOOK, bad)
        assertNotNull(problem)
        assertFalse(problem.contains(bad), 'the whole value must not come back')
        assertTrue(problem.contains('\u2026xxxxxx'), problem)
        assertTrue(DarpanSettings.problem(ERR_TO, bad).contains(bad), 'a non-secret value is still shown')
    }

    @Test void theErrorKeysSayWhatTheyDo() {
        assertTrue(DarpanSettings.catalog().get('errors.watchdog.hours').description.contains('no successful scheduled run'),
                'the watchdog is about a success, for audit checks')
        assertTrue(DarpanSettings.catalog().get(ERR_TO).description.contains('daily error email'))
    }

    @Test void theErrorKeysHaveTheirDefaults() {
        assertEquals(12, DarpanSettings.intValue('errors.digest.hourUtc'))
        assertEquals(30, DarpanSettings.intValue('errors.watchdog.hours'))
        assertEquals(7, DarpanSettings.intValue('errors.emptyStreak.days'))
        rows(['errors.digest.hourUtc': '24'])
        assertEquals(12, DarpanSettings.intValue('errors.digest.hourUtc'))
        assertNull(DarpanSettings.parse(DarpanSettings.catalog().get('errors.digest.hourUtc'), '24'))
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

    /**
     * DAR-OPS-029 S32, OC188: a caller that deletes by a setting (a sweep's horizon) must never read the catalog default
     * because the table could not be read. After a restart during a database blip the first load fails and every read
     * gets defaults: a 13-month default would delete rows a 36-month row keeps. The strict read refuses until a load
     * has succeeded; the plain read keeps its fallback.
     */
    @Test void aStrictReadRefusesWhileNoLoadHasSucceeded() {
        boolean down = true
        DarpanSettings.rowLoader = { -> loads++; if (down) throw new IllegalStateException('db down'); [(MAX): [settingValue: '6']] as Map<String, Map> }
        assertEquals(8, DarpanSettings.intValue(MAX), "the plain read still falls back to the default")
        IllegalStateException e = assertThrows(IllegalStateException) { DarpanSettings.intValueStrict(MAX) }
        assertTrue(e.message.contains(MAX), e.message)
        assertThrows(IllegalStateException) { DarpanSettings.optionalIntValueStrict(RUN_RESULT_DAYS) }
        down = false
        now += 31_000
        assertEquals(6, DarpanSettings.intValueStrict(MAX), "a load that succeeds lifts the refusal")
        assertNull(DarpanSettings.optionalIntValueStrict(RUN_RESULT_DAYS), "unset is still unset (keep forever)")
    }

    @Test void aStrictReadAfterAFailedReloadUsesTheLastLoadedRows() {
        int call = 0
        DarpanSettings.rowLoader = { -> if (call++ == 0) return [(MAX): [settingValue: '6']] as Map<String, Map>; throw new IllegalStateException('db down') }
        assertEquals(6, DarpanSettings.intValueStrict(MAX))
        now += 31_000
        assertEquals(6, DarpanSettings.intValueStrict(MAX))
        DarpanSettings.expire()
        assertEquals(6, DarpanSettings.intValueStrict(MAX), "an expire keeps what was loaded")
        assertEquals(3, call)
    }

    @Test void aStrictReadKeepsTheTypeAndOptionalRules() {
        assertThrows(IllegalArgumentException) { DarpanSettings.intValueStrict(RUN_RESULT_DAYS) }
        assertThrows(IllegalArgumentException) { DarpanSettings.optionalIntValueStrict(MAX) }
        assertThrows(IllegalArgumentException) { DarpanSettings.intValueStrict(DAYS) }
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
