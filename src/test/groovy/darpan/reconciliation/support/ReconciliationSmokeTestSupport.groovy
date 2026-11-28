package darpan.reconciliation.support

import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonOutput
import reconciliation.rule.FieldComparisonRuleLogicGenerator
import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission

import java.sql.Timestamp
import java.sql.Connection
import java.sql.PreparedStatement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.ThreadPoolExecutor

class ReconciliationSmokeTestSupport {
    private static final String TEST_COMPANY_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TEST_COMPANY_USERNAME = "test.customer"
    private static final String TEST_COMPANY_USER_GROUP_ID = "KREWE"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    // DAR-OPS-026 shared mode (@SharedMoqui): one ECF per test JVM, reset between classes. See initSharedMoqui.
    private static final Object SHARED_LOCK = new Object()
    // Caches that hold parsed artifacts (definitions, compiled scripts and screens), not database rows; they survive the
    // per-class reset so the shared JVM does not re-parse them. Every other cache (entity.record.*, the sequence bank,
    // data-feed info, l10n messages, darpan.*) is cleared, since it can hold the previous class's rows.
    private static final List<String> SHARED_KEPT_CACHE_PREFIXES = ["entity.definition", "entity.location",
            "service.", "screen.location", "screen.url", "screen.info", "screen.template.", "screen.find.path",
            "resource.", "widget.template.", "kie."]
    private static ExecutionContextFactoryImpl sharedEcf
    private static ExecutionContext sharedClassEc
    private static Path sharedSchemaScript
    private static Path sharedDbBasePath
    private static Path sharedTxLogPath
    private static Map<String, Timestamp> sharedTablesChecked
    private static Map<String, Boolean> sharedTablesExist
    private static int sharedClassCount
    private static boolean sharedShutdownHookAdded
    /** @SharedMoqui classes now running (innermost first; @Nested classes stack), set by SharedMoquiExtension. */
    private static final Deque<Class<?>> sharedClassStack = new ArrayDeque<>()
    /** The class whose ec is sharedClassEc; named when its background work outlives it. */
    private static String sharedClassEcOwner
    /** How long a class's reset waits for the previous class's background work before failing. */
    static final long SHARED_QUIET_TIMEOUT_MILLIS = 30_000L

    /**
     * Boots Moqui for one smoke class. An unmarked class gets a fresh ECF on its own DB token (one JVM each).
     * A class annotated @SharedMoqui shares one ECF per JVM (initSharedMoqui).
     */
    static ExecutionContext initMoqui(Path backendRoot, String testDbName) {
        if (sharedClassActive()) return initSharedMoqui(backendRoot, testDbName)
        tearDownSharedMoqui("unmarked class booting its own ECF on ${testDbName}")
        return bootMoqui(backendRoot, testDbName)
    }

    private static ExecutionContext bootMoqui(Path backendRoot, String testDbName) {
        String runtimePath = backendRoot.resolve("runtime").toString()
        Path testDbBasePath = backendRoot.resolve("runtime/tmp/test-h2/${testDbName}")
        resetDatabaseFiles(testDbBasePath)
        String testDbPath = testDbBasePath.toString()
        String safeTestToken = testDbName.replaceAll(/[^A-Za-z0-9_-]/, "_")
        Path txLogPath = backendRoot.resolve("runtime/tmp/test-txlog/${safeTestToken}")

        System.setProperty("moqui.runtime", runtimePath)
        System.setProperty("moqui_runtime", runtimePath)
        System.setProperty("entity_ds_url", "jdbc:h2:${testDbPath};lock_timeout=30000")
        System.setProperty("darpan.data.manager.location", "runtime://tmp/test-data-manager/${safeTestToken}")
        // Bitronix is the JTA TM (moqui-atomikos retired). bitronix-default-config.properties points the
        // journal at ${moqui.runtime}/txlog, which a locally running dev-stack holds an exclusive lock on —
        // without this the whole smoke suite dies at ECFI init with "cannot open disk journal". Give each
        // test class its own journal and serverId (forkEvery=1, so the config is read fresh per JVM).
        resetDirectory(txLogPath)
        System.setProperty("bitronix.tm.journal.disk.logPart1Filename", txLogPath.resolve("btm1.tlog").toString())
        System.setProperty("bitronix.tm.journal.disk.logPart2Filename", txLogPath.resolve("btm2.tlog").toString())
        System.setProperty("bitronix.tm.serverId", "test-${safeTestToken}".toString().take(45))

        // MACH P0 #5 — several smoke fixtures seed TENANT rulesets with hand-written raw DRL as a
        // shortcut (real tenants use regenerated FIELD_COMPARISON rules). RuleEngineSupport now fails
        // closed on tenant raw DRL by default; opt these execution-mechanics tests into the raw-DRL
        // path. forkEvery=1 scopes this to the smoke JVM, so the default-OFF gate stays pinned by
        // reconciliation.rule.RuleEngineSupportCompileGateTests running in its own JVM.
        System.setProperty("darpan.rules.allowRawDrl", "true")

        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }

        Moqui.dynamicInit(new ExecutionContextFactoryImpl(
                runtimePath,
                "conf/MoquiDevConf.xml"
        ))

        ExecutionContext ec = Moqui.getExecutionContext()
        if (!ec.user.internalLoginUser("john.doe")) {
            assert ec.user.loginAnonymousIfNoUser()
        }
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push(
                "smokeTests",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        ec.message.clearErrors()
        return ec
    }

    /**
     * Ends one smoke class. Shared mode is keyed on identity (the ec is the shared class ec) or on a @SharedMoqui
     * class still running, never on the caller, so an inherited @AfterAll cannot tear down the shared ECF.
     */
    static void cleanupMoqui(ExecutionContext ec) {
        if (isSharedCleanup(ec)) {
            cleanupSharedMoqui(ec)
            return
        }
        ec?.destroy()
        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
        System.clearProperty("darpan.data.manager.location")
    }

    /** The DB token of this JVM's shared ECF: names its H2 file, txlog directory and Bitronix serverId. */
    static String sharedToken() { return "shared-${ProcessHandle.current().pid()}".toString() }

    /** How many @SharedMoqui classes have started on the current shared ECF (1 for the class that booted it). */
    static int sharedClassCount() { synchronized (SHARED_LOCK) { return sharedClassCount } }

    /** The current shared ECF, or null when none is up. */
    static ExecutionContextFactoryImpl sharedEcf() { synchronized (SHARED_LOCK) { return liveSharedEcf() } }

    /** SharedMoquiExtension.beforeAll: a @SharedMoqui class starts (before its @BeforeAll methods). */
    static void enterSharedClass(Class<?> testClass) {
        synchronized (SHARED_LOCK) { sharedClassStack.push(testClass) }
    }

    /**
     * SharedMoquiExtension.afterAll: a @SharedMoqui class ended (after its @AfterAll methods). When the outermost
     * class ends, an ec it never cleaned up is destroyed here; the ECF stays up.
     */
    static void exitSharedClass(Class<?> testClass) {
        // The class's own async work must be done when it ends: fail it here, by name, not the next class.
        SharedMoqui marker = testClass.getAnnotation(SharedMoqui)
        if (marker == null || marker.quietAtEnd()) {
            try {
                awaitQuietOrTearDown(testClass.name, true)
            } finally {
                exitSharedClassState(testClass)
            }
            return
        }
        exitSharedClassState(testClass)
    }

    private static void exitSharedClassState(Class<?> testClass) {
        synchronized (SHARED_LOCK) {
            sharedClassStack.removeFirstOccurrence(testClass)
            if (sharedClassStack.isEmpty() && sharedClassEc != null) {
                sharedClassEc.destroy()
                sharedClassEc = null
                System.clearProperty("darpan.data.manager.location")
            }
        }
    }

    private static boolean sharedClassActive() { synchronized (SHARED_LOCK) { return !sharedClassStack.isEmpty() } }

    private static boolean isSharedCleanup(ExecutionContext ec) {
        synchronized (SHARED_LOCK) {
            return (ec != null && ec.is(sharedClassEc)) || !sharedClassStack.isEmpty()
        }
    }

    /**
     * An unmarked class always gets a fresh ECF, as before shared mode. If a shared ECF is still up in this JVM (an IDE
     * run mixing marked and unmarked classes), it is torn down deliberately and logged rather than left for the
     * unmarked boot to destroy behind the harness's back; the next marked class boots a new one.
     */
    private static void tearDownSharedMoqui(String reason) {
        synchronized (SHARED_LOCK) {
            if (liveSharedEcf() == null) return
            System.err.println("Shared Moqui: tearing down the shared ECF (${sharedClassCount} class(es) on it, last " +
                    "${sharedClassEcOwner}): ${reason}")
            if (sharedClassEc != null) sharedClassEc.destroy()
            sharedClassEc = null
            Moqui.destroyActiveExecutionContextFactory()
            sharedEcf = null
            System.clearProperty("scheduled_job_check_time")
            deleteSharedFiles()
        }
    }

    /**
     * DAR-OPS-026 shared mode. The first marked class in a JVM boots the ECF exactly as an unmarked class does, on
     * token shared-<pid>, and snapshots the empty schema. Every later class: destroy the previous class's ec, drop
     * all objects and re-run the snapshot, clear Moqui's row caches (entity records, sequence bank, ...) and restore
     * the table-check state to the post-boot one, then a fresh ec with john.doe, authz off and AUTHZA_ALL pushed.
     * Every class (first included) gets its own data-manager location and StaticResets.resetAll().
     */
    private static ExecutionContext initSharedMoqui(Path backendRoot, String testDbName) {
        synchronized (SHARED_LOCK) {
            String safeTestToken = testDbName.replaceAll(/[^A-Za-z0-9_-]/, "_")
            ExecutionContext ec
            if (liveSharedEcf() != null) {
                ec = resetSharedMoqui(sharedClassEcOwner)
                sharedClassCount++
            } else {
                String token = sharedToken()
                // No scheduled job runner: it would fire seeded jobs mid-class and write rows the quiet gate ignores.
                System.setProperty("scheduled_job_check_time", "0")
                try {
                    ec = bootMoqui(backendRoot, token)
                } catch (Throwable t) {
                    System.clearProperty("scheduled_job_check_time")
                    throw t
                }
                sharedEcf = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
                sharedDbBasePath = backendRoot.resolve("runtime/tmp/test-h2/${token}")
                sharedTxLogPath = backendRoot.resolve("runtime/tmp/test-txlog/${token}")
                // Named <token>.schema.sql so resetDatabaseFiles clears a stale one at the next boot on this token.
                sharedSchemaScript = backendRoot.resolve("runtime/tmp/test-h2/${token}.schema.sql")
                Files.deleteIfExists(sharedSchemaScript)
                withSharedConnection { Connection c ->
                    c.createStatement().withCloseable { it.execute("SCRIPT NODATA TO '${sqlPath(sharedSchemaScript)}'".toString()) }
                }
                def dbMeta = sharedEcf.entityFacade.getEntityDbMeta()
                sharedTablesChecked = new HashMap<>((Map<String, Timestamp>) dbMeta.@entityTablesChecked)
                sharedTablesExist = new HashMap<>((Map<String, Boolean>) dbMeta.@entityTablesExist)
                sharedClassCount = 1
                addSharedShutdownHook()
            }
            sharedClassEc = ec
            sharedClassEcOwner = sharedClassStack.peekFirst()?.name
            System.setProperty("darpan.rules.allowRawDrl", "true")
            System.setProperty("darpan.data.manager.location", "runtime://tmp/test-data-manager/${safeTestToken}".toString())
            StaticResets.resetAll()
            return ec
        }
    }

    private static ExecutionContext resetSharedMoqui(String previousClass) {
        if (sharedClassEc != null) sharedClassEc.destroy()
        sharedClassEc = null
        sharedEcf.destroyActiveExecutionContext()
        // The previous class's async work must not write into this class's fresh DB or hold locks the DROP waits on.
        awaitQuietOrTearDown(previousClass, false)

        withSharedConnection { Connection c ->
            c.createStatement().withCloseable {
                it.execute("DROP ALL OBJECTS")
                it.execute("RUNSCRIPT FROM '${sqlPath(sharedSchemaScript)}'".toString())
            }
        }
        for (String cacheName : new ArrayList<String>(sharedEcf.cacheFacade.getCacheNames())) {
            String plainName = cacheName.contains("__") ? cacheName.substring(cacheName.indexOf("__") + 2) : cacheName
            if (SHARED_KEPT_CACHE_PREFIXES.any { String prefix -> plainName.startsWith(prefix) }) continue
            sharedEcf.cacheFacade.getCache(cacheName).clear()
        }
        def dbMeta = sharedEcf.entityFacade.getEntityDbMeta()
        synchronized (dbMeta) {
            Map<String, Timestamp> checked = (Map<String, Timestamp>) dbMeta.@entityTablesChecked
            Map<String, Boolean> exist = (Map<String, Boolean>) dbMeta.@entityTablesExist
            checked.clear(); checked.putAll(sharedTablesChecked)
            exist.clear(); exist.putAll(sharedTablesExist)
        }

        ExecutionContext ec = Moqui.getExecutionContext()
        if (!ec.user.internalLoginUser("john.doe")) {
            assert ec.user.loginAnonymousIfNoUser()
        }
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push(
                "smokeTests",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        ec.message.clearErrors()
        return ec
    }

    /** Shared mode: the class's ec only; the ECF stays up for the next class and the shutdown hook destroys it. */
    private static void cleanupSharedMoqui(ExecutionContext ec) {
        synchronized (SHARED_LOCK) {
            ec?.destroy()
            if (sharedClassEc != null && !sharedClassEc.is(ec)) sharedClassEc.destroy()
            sharedClassEc = null
            System.clearProperty("darpan.data.manager.location")
        }
    }

    /**
     * The quiet gate at a class boundary. After a timeout the shared ECF is unusable (work still running on it), so it
     * is torn down here and the next class boots fresh instead of every later class in the JVM failing.
     */
    private static void awaitQuietOrTearDown(String previousClass, boolean atClassEnd) {
        try {
            awaitSharedQuiet(previousClass, SHARED_QUIET_TIMEOUT_MILLIS, atClassEnd)
        } catch (IllegalStateException e) {
            tearDownSharedMoqui("quiet gate timed out waiting for ${previousClass}")
            throw e
        }
    }

    /**
     * Waits until the shared ECF's worker pools are idle (no active thread, empty queue), its scheduled executor runs
     * nothing, and run admission has nothing running or queued; past timeoutMillis it fails naming previousClass,
     * whose work is still going. The scheduled executor's queue always holds Moqui's periodic tasks, so only its
     * active threads are checked, and not those running a Moqui framework periodic task (the ElasticSearch log
     * flusher blocks in an HTTP bulk call for long stretches).
     */
    static void awaitSharedQuiet(String previousClass, long timeoutMillis) {
        awaitSharedQuiet(previousClass, timeoutMillis, false)
    }

    /** atClassEnd: the wait runs when previousClass itself ends, so the message blames it directly. */
    static void awaitSharedQuiet(String previousClass, long timeoutMillis, boolean atClassEnd) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        String busy
        while ((busy = sharedBusy()) != null) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Shared Moqui: ${previousClass ?: 'the previous class'} left background " +
                        "work running ${timeoutMillis} ms after it ended (${busy}). It would write into the next class's " +
                        "fresh database: make it wait for its async work in @AfterAll, or leave it unmarked." +
                        "\nActive pool threads:\n${activePoolThreads()}")
            }
            Thread.sleep(50)
        }
    }

    /** Moqui's own periodic tasks on the scheduled executor: they never touch a test class's data, so they do not count as leaked work. */
    private static final List<String> FRAMEWORK_PERIODIC_FRAMES = [
            'org.moqui.impl.util.ElasticSearchLogger', 'org.moqui.impl.webapp.ElasticRequestLogFilter',
            'org.moqui.jcache.MCache'].asImmutable()

    /** Threads started by darpan code or the entity facade for work a class may leave behind: busy ones count as leaked work. */
    private static final List<String> OWNED_THREAD_PREFIXES = ['darpan-script-', 'data-source-read-', 'MoquiEntityExec-',
            'darpan-agent-query-'].asImmutable()

    private static boolean isIdlePoolThread(StackTraceElement[] frames) {
        return frames.length > 0 && frames[0].methodName == 'park' && frames.any { it.methodName == 'getTask' || it.methodName == 'take' }
    }

    /** A task is a Moqui periodic one when its ROOT frame (bottom-most frame outside the JDK and Moqui's CustomScheduledTask wrapper) is one, so a framework frame inside darpan work cannot hide it. */
    private static boolean isFrameworkPeriodic(StackTraceElement[] frames) {
        StackTraceElement root = frames.reverse().find { StackTraceElement f ->
            !(f.className.startsWith('java.') || f.className.startsWith('javax.') || f.className.startsWith('jdk.') ||
                    f.className.startsWith('sun.') || f.className.startsWith('org.moqui.impl.context.ContextJavaUtil$'))
        }
        return root != null && FRAMEWORK_PERIODIC_FRAMES.any { String p -> root.className.startsWith(p) }
    }

    /** Live Moqui pool threads (workers, jobs, scheduled) that are running something, with their frames. */
    private static Map<Thread, StackTraceElement[]> activePoolThreadMap() {
        Map<Thread, StackTraceElement[]> result = new LinkedHashMap<>()
        Thread.getAllStackTraces().each { Thread t, StackTraceElement[] frames ->
            if (!(t.name.startsWith('MoquiWorker-') || t.name.startsWith('MoquiJob-') || t.name.startsWith('MoquiScheduled-') ||
                    OWNED_THREAD_PREFIXES.any { String p -> t.name.startsWith(p) })) return
            if (isIdlePoolThread(frames)) return
            result.put(t, frames)
        }
        return result
    }

    /** Scheduled-executor threads running something other than a Moqui framework periodic task. */
    private static int busyScheduledThreadCount() {
        return activePoolThreadMap().findAll { Thread t, StackTraceElement[] frames ->
            t.name.startsWith('MoquiScheduled-') && !isFrameworkPeriodic(frames)
        }.size()
    }

    /** Active darpan-owned (script, data-source read, entity exec, agent query) threads. */
    private static int busyOwnedThreadCount() {
        return activePoolThreadMap().findAll { Thread t, StackTraceElement[] frames ->
            OWNED_THREAD_PREFIXES.any { String p -> t.name.startsWith(p) }
        }.size()
    }

    /** Name and top 8 frames of each active Moqui pool thread, marking framework periodic tasks (not counted as leaked). */
    private static String activePoolThreads() {
        StringBuilder sb = new StringBuilder()
        activePoolThreadMap().each { Thread t, StackTraceElement[] frames ->
            sb.append("  ").append(t.name).append(" [").append(t.state).append("]")
            if (isFrameworkPeriodic(frames)) sb.append(" (Moqui periodic task, not counted)")
            sb.append("\n")
            frames.take(8).each { sb.append("      at ").append(it).append("\n") }
        }
        return sb.length() == 0 ? "  (none active)" : sb.toString()
    }

    private static String sharedBusy() {
        ExecutionContextFactoryImpl ecfi = liveSharedEcf()
        if (ecfi == null) return null
        List<String> busy = []
        Map<String, ThreadPoolExecutor> pools = [workerPool: ecfi.workerPool, jobWorkerPool: ecfi.serviceFacade.jobWorkerPool]
        pools.each { String name, ThreadPoolExecutor pool ->
            if (pool != null && (pool.activeCount > 0 || !pool.queue.isEmpty())) {
                busy << "${name}: ${pool.activeCount} active, ${pool.queue.size()} queued".toString()
            }
        }
        int scheduledBusy = ecfi.scheduledExecutor != null ? busyScheduledThreadCount() : 0
        if (scheduledBusy > 0) busy << "scheduledExecutor: ${scheduledBusy} active".toString()
        int ownedBusy = busyOwnedThreadCount()
        if (ownedBusy > 0) busy << "darpan/entity threads: ${ownedBusy} active".toString()
        for (RunAdmission admission : [AutomationExecutionSupport.getRunAdmission(), RunAdmission.SHARED].unique { System.identityHashCode(it) }) {
            if (admission == null) continue
            Map<String, Object> state = admission.snapshot()
            if (((state.running ?: 0) as int) > 0 || ((state.queued ?: 0) as int) > 0) {
                busy << "run admission: ${state.running} running, ${state.queued} queued".toString()
            }
        }
        return busy ? busy.join("; ") : null
    }

    private static ExecutionContextFactoryImpl liveSharedEcf() {
        if (sharedEcf == null || sharedEcf.isDestroyed() || !sharedEcf.is(Moqui.getExecutionContextFactory())) return null
        return sharedEcf
    }

    private static void addSharedShutdownHook() {
        if (sharedShutdownHookAdded) return
        sharedShutdownHookAdded = true
        Runtime.runtime.addShutdownHook(new Thread({
            synchronized (SHARED_LOCK) {
                try {
                    if (sharedEcf != null && !sharedEcf.isDestroyed()) {
                        if (sharedEcf.is(Moqui.getExecutionContextFactory())) Moqui.destroyActiveExecutionContextFactory()
                        else sharedEcf.destroy()
                    }
                } catch (Throwable t) {
                    System.err.println("Shared Moqui shutdown: ECF destroy failed: ${t}")
                }
                deleteSharedFiles()
            }
        } as Runnable, "shared-moqui-shutdown"))
    }

    private static void deleteSharedFiles() {
        try {
            if (sharedDbBasePath != null) resetDatabaseFiles(sharedDbBasePath)
            if (sharedTxLogPath != null && Files.exists(sharedTxLogPath)) {
                Files.walk(sharedTxLogPath).sorted(Comparator.reverseOrder()).forEach { Path p -> Files.deleteIfExists(p) }
            }
        } catch (Throwable ignored) {
            // best effort: the next boot on a token clears its own files anyway
        }
    }

    /** A plain JDBC session on the shared H2 file DB (same URL and credentials as Moqui's transactional datasource). */
    private static void withSharedConnection(Closure body) {
        Properties props = new Properties()
        props.setProperty("user", System.getProperty("entity_ds_user") ?: "sa")
        props.setProperty("password", System.getProperty("entity_ds_password") ?: "")
        java.sql.Driver driver = (java.sql.Driver) Class.forName("org.h2.Driver", true, ReconciliationSmokeTestSupport.classLoader)
                .getDeclaredConstructor().newInstance()
        Connection c = driver.connect(System.getProperty("entity_ds_url"), props)
        try {
            body.call(c)
        } finally {
            c.close()
        }
    }

    private static String sqlPath(Path path) { return path.toAbsolutePath().toString().replace("'", "''") }

    static void loadSeedData(ExecutionContext ec, String... locations) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "loadData",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            locations.each { String location ->
                ec.entity.makeDataLoader().location(location).load()
            }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    static void seedBaseCompareRuleSet(ExecutionContext ec) {
        seedCompanyScope(ec)
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                ruleSetName   : "Darpan Test Compare RuleSet",
                description   : "Base RuleSet required by compare-scope smoke fixtures.",
                version       : "1.0",
                explosionPath : "rows",
                primaryKeyPath: "itemId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
    }

    /**
     * DAR-OPS-029 S99-205b. A FIELD_COMPARISON rule the way save#Rule stores one: its expression, and the ruleLogic
     * FieldComparisonRuleLogicGenerator makes from it, the only logic the compile gate lets through with raw DRL off.
     * The field compared is the same on both sides; a difference reads "field = field" (the generator's label).
     */
    static void seedGeneratedFieldComparison(ExecutionContext ec, String ruleId, String ruleSetId, int sequenceNum,
                                             String field, String severity) {
        String expression = JsonOutput.toJson([type: FieldComparisonRuleLogicGenerator.FIELD_COMPARISON_TYPE,
                                               file1FieldPath: field, file2FieldPath: field, operator: "="])
        upsertEntity(ec, "darpan.rule.Rule", [ruleId: ruleId], [
                ruleId     : ruleId,
                ruleSetId  : ruleSetId,
                sequenceNum: sequenceNum,
                ruleText   : "${field} = ${field}".toString(),
                ruleType   : FieldComparisonRuleLogicGenerator.FIELD_COMPARISON_TYPE,
                expression : expression,
                ruleLogic  : FieldComparisonRuleLogicGenerator.generate(expression, "", "", ruleId, severity, 0),
                enabled    : "Y",
                severity   : severity
        ])
    }

    static void seedCompareScopeFixtures(ExecutionContext ec) {
        seedBaseCompareRuleSet(ec)
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_PRODUCT_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                ruleSetName   : "Darpan Test Product Compare RuleSet",
                description   : "Smoke-test RuleSet for matched-pair field diff generation.",
                version       : "1.0",
                explosionPath : "data.products",
                primaryKeyPath: "productId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_PRODUCT_BROKEN_RS"], [
                ruleSetId     : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                ruleSetName   : "Darpan Test Broken Product RuleSet",
                description   : "Smoke-test RuleSet for preserving base diffs when DRL compilation fails.",
                version       : "1.0",
                explosionPath : "data.products",
                primaryKeyPath: "productId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])

        // DAR-OPS-029 S99-205b: the SKU and price rules are what a tenant can author, generated field comparisons (the
        // compile gate refuses any other logic once darpan.rules.allowRawDrl is off), not hand-written DRL.
        seedGeneratedFieldComparison(ec, "DARPAN_TEST_PRODUCT_SKU_MISMATCH", "DARPAN_TEST_PRODUCT_COMPARE_RS", 10, "sku", "WARN")
        seedGeneratedFieldComparison(ec, "DARPAN_TEST_PRODUCT_PRICE_MISMATCH", "DARPAN_TEST_PRODUCT_COMPARE_RS", 20, "price", "WARN")
        upsertEntity(ec, "darpan.rule.Rule", [ruleId: "DARPAN_TEST_PRODUCT_BROKEN_RULE"], [
                ruleId      : "DARPAN_TEST_PRODUCT_BROKEN_RULE",
                ruleSetId   : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                sequenceNum : 10,
                ruleText    : "Broken DRL used to verify base-diff preservation",
                ruleLogic   : 'rule "DARPAN_TEST_PRODUCT_BROKEN_RULE" when $m : Map( this["file1"] != null ) then BROKEN end',
                enabled     : "Y",
                ruleType    : "DRL",
                severity    : "ERROR"
        ])

        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                objectType    : "ORDER",
                description   : "Smoke-test compare scope for nested JSON order IDs."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE",
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                objectType    : "ORDER",
                description   : "Smoke-test compare scope for CSV order IDs."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                objectType    : "PRODUCT",
                description   : "Smoke-test compare scope for product field mismatches."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                objectType    : "PRODUCT",
                description   : "Smoke-test compare scope for broken DRL preservation behavior."
        ])

        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ORDER_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id|SHOPIFY_GID_TAIL"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_ORDER_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id",
                idValueNormalizer   : "SHOPIFY_GID_TAIL"
        ])
        // DAR-BE-049: a RuleSet whose ONE compare scope is single-sided, so resolveRuleSetRun can be
        // exercised against a saved run that legitimately has no second side. Kept separate from
        // DARPAN_TEST_COMPARE_RS because a saved run requires exactly one compare scope per RuleSet.
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_EVALUATE_RS"], [
                ruleSetId     : "DARPAN_TEST_EVALUATE_RS",
                ruleSetName   : "Darpan Test Evaluate RuleSet",
                description   : "Smoke-test RuleSet for a single-sided evaluate run.",
                version       : "1.0",
                explosionPath : "data.orders.edges",
                primaryKeyPath: "node.id",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_EVALUATE_ONLY_SCOPE"], [
                compareScopeId: "DARPAN_TEST_EVALUATE_ONLY_SCOPE",
                ruleSetId     : "DARPAN_TEST_EVALUATE_RS",
                objectType    : "ORDER",
                scopeMode     : "EVALUATE",
                description   : "Orders with no fulfillment."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_EVALUATE_ONLY_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_EVALUATE_ONLY_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id|SHOPIFY_GID_TAIL"
        ])

        // The complement of the fixture above: a COMPARE scope with only FILE_1, which must still be
        // REFUSED. Without it, "one source resolves" would pass for the wrong reason.
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_ONE_SIDED_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_ONE_SIDED_COMPARE_RS",
                ruleSetName   : "Darpan Test One-Sided Compare RuleSet",
                description   : "Smoke-test RuleSet for a COMPARE scope missing its second side.",
                version       : "1.0",
                explosionPath : "data.orders.edges",
                primaryKeyPath: "node.id",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ONE_SIDED_COMPARE_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ONE_SIDED_COMPARE_SCOPE",
                ruleSetId     : "DARPAN_TEST_ONE_SIDED_COMPARE_RS",
                objectType    : "ORDER",
                description   : "A compare scope missing its second side."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ONE_SIDED_COMPARE_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ONE_SIDED_COMPARE_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id|SHOPIFY_GID_TAIL"
        ])

        // DAR-BE-049: a single-sided EVALUATE scope. ONE source and deliberately no FILE_2 — the shape
        // an exception check has, where the predicate already ran in the extractor.
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ORDER_EVALUATE_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ORDER_EVALUATE_SCOPE",
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                objectType    : "ORDER",
                scopeMode     : "EVALUATE",
                description   : "Smoke-test single-sided evaluate scope for exception findings."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_EVALUATE_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ORDER_EVALUATE_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id|SHOPIFY_GID_TAIL"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ORDER_CSV_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftCsv",
                primaryIdExpression : "orderId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_ORDER_CSV_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftCsv",
                primaryIdExpression : "orderId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
    }

    static void seedSchemaBackedCsvMappingFixtures(ExecutionContext ec) {
        seedCompanyScope(ec)
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestOmsOrderSchema"], [
                jsonSchemaId       : "TestOmsOrderSchema",
                schemaName         : "test-oms-order-id.schema.json",
                description        : "Smoke-test OMS order id schema",
                systemEnumId       : "OMS",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestShopifyOrderSchema"], [
                jsonSchemaId       : "TestShopifyOrderSchema",
                schemaName         : "test-shopify-order-id.schema.json",
                description        : "Smoke-test Shopify order id schema",
                systemEnumId       : "SHOPIFY",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestOmsOrdersJsonSchema"], [
                jsonSchemaId       : "TestOmsOrdersJsonSchema",
                schemaName         : "test-oms-orders.schema.json",
                description        : "Smoke-test OMS orders JSON schema",
                systemEnumId       : "OMS",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"orders":{"type":"array","items":{"type":"object","properties":{"order_id":{"type":"string"},"status":{"type":"string"}}}}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestShopifyOrdersJsonSchema"], [
                jsonSchemaId       : "TestShopifyOrdersJsonSchema",
                schemaName         : "test-shopify-orders.schema.json",
                description        : "Smoke-test Shopify orders JSON schema",
                systemEnumId       : "SHOPIFY",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"data":{"type":"object","properties":{"orders":{"type":"object","properties":{"edges":{"type":"array","items":{"type":"object","properties":{"node":{"type":"object","properties":{"id":{"type":"string"},"status":{"type":"string"}}}}}}}}}}}}'
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMapping", [reconciliationMappingId: "OrderIdSchemaMap"], [
                reconciliationMappingId: "OrderIdSchemaMap",
                mappingName            : "Order ID",
                description            : "Order ID field mapping backed by saved schemas",
                companyUserGroupId     : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId        : TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdSchemaMapOms"], [
                mappingMemberId         : "OrderIdSchemaMapOms",
                reconciliationMappingId : "OrderIdSchemaMap",
                systemEnumId            : "OMS",
                fileTypeEnumId          : "DftCsv",
                schemaFileName          : "test-oms-order-id.schema.json",
                idFieldExpression       : "order_id"
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdSchemaMapShopify"], [
                mappingMemberId         : "OrderIdSchemaMapShopify",
                reconciliationMappingId : "OrderIdSchemaMap",
                systemEnumId            : "SHOPIFY",
                fileTypeEnumId          : "DftCsv",
                schemaFileName          : "test-shopify-order-id.schema.json",
                idFieldExpression       : "order_id"
        ])
    }

    static void seedCompanyScope(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "UserGroupType"], [
                enumTypeId  : "UserGroupType",
                description : "User Group Type"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "UgtDarpanPermission"], [
                enumId      : "UgtDarpanPermission",
                enumTypeId  : "UserGroupType",
                description : "Darpan permission groups"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID], [
                enumId      : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
                enumTypeId  : "UserGroupType",
                description : "Darpan company groups"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], [
                userGroupId      : TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                description      : "Can create, update, run, and delete tenant-scoped Darpan data",
                groupTypeEnumId  : "UgtDarpanPermission"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: TEST_COMPANY_USER_GROUP_ID], [
                userGroupId     : TEST_COMPANY_USER_GROUP_ID,
                description     : "Krewe",
                groupTypeEnumId : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
        ])
        upsertEntity(ec, "moqui.security.UserAccount", [userId: TEST_COMPANY_USER_ID], [
                userId        : TEST_COMPANY_USER_ID,
                username      : TEST_COMPANY_USERNAME,
                userFullName  : "Smoke Test Customer",
                currentPassword: "",
                disabled      : "N"
        ])
        upsertEntity(ec, "moqui.security.UserGroupMember", [
                userGroupId: TEST_COMPANY_USER_GROUP_ID,
                userId     : TEST_COMPANY_USER_ID,
                fromDate   : TEST_FROM_DATE
        ], [
                userGroupId: TEST_COMPANY_USER_GROUP_ID,
                userId     : TEST_COMPANY_USER_ID,
                fromDate   : TEST_FROM_DATE
        ])
        upsertEntity(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : TEST_COMPANY_USER_GROUP_ID,
                userId               : TEST_COMPANY_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE
        ], [
                tenantUserGroupId    : TEST_COMPANY_USER_GROUP_ID,
                userId               : TEST_COMPANY_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE
        ])
        if (!ec.user.internalLoginUser(TEST_COMPANY_USER_ID)) {
            ec.user.internalLoginUser(TEST_COMPANY_USERNAME)
        }
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TEST_COMPANY_USER_GROUP_ID)
        ec.message.clearErrors()
    }

    static void seedSftpServerFixtures(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanSftpScope"], [
                enumTypeId : "DarpanSftpScope",
                description: "Darpan SFTP server scope"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_TENANT"], [
                enumId     : "DARPAN_SFTP_TENANT",
                enumTypeId : "DarpanSftpScope",
                description: "Tenant-owned SFTP server"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_TENANT_GROUP"], [
                enumId     : "DARPAN_SFTP_TENANT_GROUP",
                enumTypeId : "DarpanSftpScope",
                description: "SFTP server shared across explicitly assigned tenant groups"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_ADMIN"], [
                enumId     : "DARPAN_SFTP_ADMIN",
                enumTypeId : "DarpanSftpScope",
                description: "Admin/platform-owned SFTP server"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: "GORJANA"], [
                userGroupId     : "GORJANA",
                description     : "Gorjana",
                groupTypeEnumId : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "SHOPIFY_TEST_SFTP"], [
                sftpServerId     : "SHOPIFY_TEST_SFTP",
                description      : "Smoke-test Shopify SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "shopify.test",
                port             : 22,
                username         : "shopify-user",
                password         : "shopify-pass",
                remoteAttributes : "N"
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "OMS_TEST_SFTP"], [
                sftpServerId     : "OMS_TEST_SFTP",
                description      : "Smoke-test OMS SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "oms.test",
                port             : 22,
                username         : "oms-user",
                password         : "oms-pass",
                remoteAttributes : "N"
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_TEST_SFTP"], [
                sftpServerId     : "GORJANA_TEST_SFTP",
                description      : "Smoke-test Gorjana SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: "GORJANA",
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "gorjana.test",
                port             : 22,
                username         : "gorjana-user",
                password         : "gorjana-pass",
                remoteAttributes : "N"
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "ADMIN_TEST_SFTP"], [
                sftpServerId     : "ADMIN_TEST_SFTP",
                description      : "Smoke-test admin SFTP server",
                scopeEnumId      : "DARPAN_SFTP_ADMIN",
                host             : "admin.test",
                port             : 22,
                username         : "admin-user",
                password         : "admin-pass",
                remoteAttributes : "N"
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "SHARED_TEST_SFTP"], [
                sftpServerId     : "SHARED_TEST_SFTP",
                description      : "Smoke-test shared tenant-group SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT_GROUP",
                host             : "shared.test",
                port             : 22,
                username         : "shared-user",
                password         : "shared-pass",
                remoteAttributes : "N"
        ])
        upsertEntity(ec, "darpan.reconciliation.SftpServerTenantAccess", [
                sftpServerId      : "SHARED_TEST_SFTP",
                tenantUserGroupId : TEST_COMPANY_USER_GROUP_ID
        ], [
                sftpServerId      : "SHARED_TEST_SFTP",
                tenantUserGroupId : TEST_COMPANY_USER_GROUP_ID
        ])
    }

    static Path resolveBackendRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        List<Path> candidates = [
                cwd,
                cwd.resolve("darpan-backend"),
                cwd.resolve("runtime/component/darpan").normalize(),
                cwd.resolve("../../..").normalize(),
                cwd.resolve("../../../..").normalize()
        ].unique()

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("runtime/conf/MoquiDevConf.xml")) &&
                    Files.exists(candidate.resolve("runtime/component/darpan"))) {
                return candidate
            }
        }

        throw new IllegalStateException("Unable to resolve darpan-backend root from ${cwd}")
    }

    private static void resetDirectory(Path directory) {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Path path -> Files.deleteIfExists(path) }
        }
        Files.createDirectories(directory)
    }

    private static void resetDatabaseFiles(Path dbBasePath) {
        Path parent = dbBasePath.parent
        if (parent == null) return
        Files.createDirectories(parent)

        String baseName = dbBasePath.fileName.toString()
        Files.list(parent).withCloseable { stream ->
            stream.filter { Path path ->
                String fileName = path.fileName.toString()
                fileName == baseName || fileName.startsWith(baseName + ".")
            }.forEach { Path path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private static void seedSharedReconciliationEnums(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanSystemSource"], [
                enumTypeId  : "DarpanSystemSource",
                description : "Darpan System Source"
        ])
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanFileType"], [
                enumTypeId  : "DarpanFileType",
                description : "File Types for Reconciliation"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "OMS"], [
                enumId      : "OMS",
                enumTypeId  : "DarpanSystemSource",
                enumCode    : "HOTWAX",
                description : "HotWax",
                sequenceNum : 1
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "SHOPIFY"], [
                enumId      : "SHOPIFY",
                enumTypeId  : "DarpanSystemSource",
                enumCode    : "SHOPIFY",
                description : "Shopify",
                sequenceNum : 2
        ])
        // MACH database-source epic Task 1: mirrors data/DarpanSystemSourceSeedData.xml's DATABASE row,
        // which this fixture (unlike the real seed file) hand-inserts row-by-row rather than loading.
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DATABASE"], [
                enumId      : "DATABASE",
                enumTypeId  : "DarpanSystemSource",
                enumCode    : "DATABASE",
                description : "Database",
                sequenceNum : 5
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DftCsv"], [
                enumId      : "DftCsv",
                enumTypeId  : "DarpanFileType",
                enumCode    : "CSV",
                description : "CSV",
                sequenceNum : 1
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DftJson"], [
                enumId      : "DftJson",
                enumTypeId  : "DarpanFileType",
                enumCode    : "JSON",
                description : "JSON",
                sequenceNum : 2
        ])
    }

    private static void upsertEntity(ExecutionContext ec, String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedEntity",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing == null) {
                insertEntityDirect(ec, entityName, fields)
            }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    static void insertEntityDirect(ExecutionContext ec, String entityName, Map<String, Object> fields) {
        def entityDefinition = ec.entity.getEntityDefinition(entityName)
        Map<String, Object> insertFields = new LinkedHashMap<>(fields)
        if (entityDefinition.isField("lastUpdatedStamp") && !insertFields.containsKey("lastUpdatedStamp")) {
            insertFields.lastUpdatedStamp = ec.user.nowTimestamp ?: new Timestamp(System.currentTimeMillis())
        }

        List<String> fieldNames = insertFields.keySet() as List<String>
        String groupName = ec.entity.getEntityGroupName(entityName)
        String columns = fieldNames.collect { String fieldName -> entityDefinition.getColumnName(fieldName) }.join(", ")
        String keyColumns = entityDefinition.getPkFieldNames()
                .collect { String fieldName -> entityDefinition.getColumnName(fieldName) }
                .join(", ")
        String placeholders = fieldNames.collect { "?" }.join(", ")
        Connection connection = null
        PreparedStatement statement = null
        try {
            connection = ec.entity.getConnection(groupName)
            statement = connection.prepareStatement("MERGE INTO ${entityDefinition.getFullTableName()} (${columns}) KEY(${keyColumns}) VALUES (${placeholders})")
            fieldNames.eachWithIndex { String fieldName, int index ->
                entityDefinition.getFieldInfo(fieldName).setPreparedStatementValue(
                        statement,
                        index + 1,
                        insertFields[fieldName],
                        entityDefinition,
                        ec.entity
                )
            }
            statement.executeUpdate()
        } finally {
            statement?.close()
            connection?.close()
        }
    }
}
