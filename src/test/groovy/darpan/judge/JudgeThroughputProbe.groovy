package darpan.judge

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import reconciliation.rule.FieldComparisonRuleLogicGenerator

import java.lang.management.ManagementFactory
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 9. Throughput probe, measurement only: 125,602 synthetic pairs (Steve Madden's October count)
 * through today's matched-pair path (reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs, batches of
 * 500) and then through the judge path's Must hold decision (PairFactSupport.batches + buildFact + moqui-rules
 * evaluate#RuleSet, batches of 500). Both use two equivalent rules: "requires" and "= within 1.00".
 *
 * Tagged perf: excluded from test, unitTest, smokeTest and testAll; run it with
 * `./gradlew :runtime:component:darpan:perfTest`. Writes build/judge-throughput.txt (the report line), and, when the new path
 * is more than 3 times slower, build/judge-throughput-profile.txt (top frames by sampling the test thread).
 *
 * Nothing real is read: every pair is generated here. The new path runs on the test thread as the tenant user with authz ON
 * under a facade grant (P2-7, P2-9). The old path runs under the same conditions, so neither side gets a cheaper security frame.
 *
 * Time covers batching, fact building and rule evaluation; pair generation is outside the clock. Each path is warmed by one
 * small untimed batch so the Drools build and JIT are not charged to a side.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeThroughputProbe {

    private static final int PAIRS = 125602
    private static final int BATCH = 500
    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String OLD_RULE_SET = "JTP_OLD_RS"

    private ExecutionContext ec
    private String factTypeId
    private Map keyNames
    private String decisionId

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "judge-throughput-probe")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/MappingSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: "TEST_CUSTOMER_USER",
                                                  fromDate: Timestamp.valueOf("2026-04-21 00:00:00")])
        actAs()

        // Today's side: one RuleSet, two FIELD_COMPARISON rules whose DRL the server generates.
        insert("darpan.rule.RuleSet", [ruleSetId: OLD_RULE_SET, ruleSetName: "Throughput probe", version: "1.0",
                                       companyUserGroupId: A, createdByUserId: "TEST_CUSTOMER_USER"])
        [["JTP_OLD_REQUIRES", 10, [type: "FIELD_COMPARISON", file1FieldPath: "flag", file2FieldPath: "flag", operator: "requires"]],
         ["JTP_OLD_AMOUNT", 20, [type: "FIELD_COMPARISON", file1FieldPath: "amount", file2FieldPath: "amount", operator: "=",
                                 tolerance: "1.00"]]].eachWithIndex { List r, int i ->
            String expression = groovy.json.JsonOutput.toJson(r[2])
            insert("darpan.rule.Rule", [ruleId: r[0], ruleSetId: OLD_RULE_SET, sequenceNum: r[1], ruleText: r[0], enabled: "Y",
                    ruleType: "FIELD_COMPARISON", severity: "WARN", companyUserGroupId: A, expression: expression,
                    ruleLogic: FieldComparisonRuleLogicGenerator.generate(expression, "", "", r[0] as String, "WARN", i)])
        }

        // The new side: a check, its pair fact type, and one Must hold decision (firstMatchOnly N).
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: A, checkCode: "throughput", label: "throughput probe"])
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType",
                [checkId: checkId, sampleKeys: [file1: ["flag", "amount"], file2: ["flag", "amount"]]]))
        factTypeId = generated.factTypeId
        keyNames = generated.keyNames
        Map created = ok(underFacadeGrant("create#rules.RuleSet", [description: "throughput probe", firstMatchOnly: "N",
                factTypes: [[factTypeId: factTypeId]], outcomes: [[outcomeId: "NOT_REQUIRED"], [outcomeId: "AMOUNT_OFF"]]]))
        decisionId = created.ruleSetId
        assertNotNull(decisionId)
        // A promise is broken when it does NOT hold, so each rule is NOT over the comparison.
        ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: decisionId, ruleName: "flag requires flag", priority: 20,
                conditions: not(compareField("FPO_REQUIRES", null)), actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "NOT_REQUIRED"]]]))
        ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: decisionId, ruleName: "amount within 1.00", priority: 10,
                conditions: not(compareField("ENTCO_EQUALS", "1.00", "amount")), actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "AMOUNT_OFF"]]]))
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void throughputOfTheJudgePathAgainstTodaysMatchedPairRules() {
        String startedAt = Instant.now().toString()
        double loadBefore = ManagementFactory.operatingSystemMXBean.systemLoadAverage
        List<Map> pairs = generatePairs(PAIRS, "S")
        List<Map> warmPairs = generatePairs(200, "W")
        int expectedBreaks = pairs.count { Map p -> brokenRequires(p) } + pairs.count { Map p -> brokenAmount(p) }
        assertTrue(expectedBreaks > 0, "the synthetic data must break some promises or the probe measures nothing")

        // Warm both paths: one small untimed batch each (Drools build, JIT, service caches).
        runOld(warmPairs)
        runNew(warmPairs)

        System.gc()
        long oldStart = System.nanoTime()
        long oldBreaks = runOld(pairs)
        long oldMs = (System.nanoTime() - oldStart).intdiv(1_000_000L)

        System.gc()
        long newStart = System.nanoTime()
        long newBreaks = runNew(pairs)
        long newMs = (System.nanoTime() - newStart).intdiv(1_000_000L)

        assertEquals((long) expectedBreaks, oldBreaks, "today's path found a different number of broken promises than the data holds")
        assertEquals((long) expectedBreaks, newBreaks, "the judge path found a different number of broken promises than the data holds")

        double ratio = oldMs > 0 ? newMs / (double) oldMs : Double.NaN
        String line = "pairs=${PAIRS} old_ms=${oldMs} new_ms=${newMs} ratio=${String.format('%.2f', ratio)}".toString()
        double loadAfter = ManagementFactory.operatingSystemMXBean.systemLoadAverage
        File out = new File("build/judge-throughput.txt")
        out.parentFile.mkdirs()
        out.text = line + "\n"
        println line
        String notes = ("started=${startedAt} ended=${Instant.now()} brokenPromises=${expectedBreaks} batch=${BATCH} " +
                "load1m_before=${String.format('%.2f', loadBefore)} load1m_after=${String.format('%.2f', loadAfter)} " +
                "maxHeapMb=${Runtime.runtime.maxMemory().intdiv(1048576L)} cpus=${Runtime.runtime.availableProcessors()} " +
                "jvm=${System.getProperty('java.vm.name')} ${System.getProperty('java.version')} " +
                "jvmArgs=${ManagementFactory.runtimeMXBean.inputArguments.findAll { !it.startsWith('--add-opens') }}").toString()
        new File("build/judge-throughput-notes.txt").text = notes + "\n"
        println notes

        if (ratio > 3.0d) {
            String profile = "ratio ${String.format('%.2f', ratio)} is above 3\n\nNEW path top frames:\n" + profile { runNew(pairs) } +
                    "\nOLD path top frames:\n" + profile { runOld(pairs) }
            new File("build/judge-throughput-profile.txt").text = profile
            println profile
        }
    }

    // ---------------------------------------------------------------------------------------

    private long runOld(List<Map> pairs) {
        long diffs = 0
        pairs.collate(BATCH).each { List<Map> batch ->
            List<Map> facts = batch.collect { Map p -> [primaryId: p.compareId, file1: p.file1, file2: p.file2] as Map }
            Map result = underFacadeGrant("reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs",
                    [ruleSetId: OLD_RULE_SET, dataList: facts])
            Map out = ok(result)
            assertTrue(out.error == null, "old path: ${out.error}")
            diffs += (out.diffResults as List).size()
        }
        return diffs
    }

    private long runNew(List<Map> pairs) {
        Map sideState = [factTypeId: factTypeId, file1: [prefix: "file1", setAside: [], keyNames: keyNames.file1],
                         file2: [prefix: "file2", setAside: [], keyNames: keyNames.file2]]
        long outcomes = 0
        Iterator<List<Map>> batches = PairFactSupport.batches(pairs.iterator(), BATCH)
        while (batches.hasNext()) {
            List<Map> facts = batches.next().collect { Map row -> PairFactSupport.buildFact(row, sideState, [:], [:]) }
            Map out = ok(underFacadeGrant("moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: decisionId, facts: facts]))
            List<Map> answers = out.decisions as List<Map>
            assertEquals(facts.size(), answers.size())
            answers.each { Map answer -> outcomes += ((answer.outcomes ?: []) as List).size() }
        }
        return outcomes
    }

    /** Pairs are [compareId, file1, file2]. Roughly 8% break "requires", roughly 6% break the amount within 1.00. */
    private static List<Map> generatePairs(int count, String idPrefix) {
        Random random = new Random(318L)
        List<Map> pairs = new ArrayList<>(count)
        for (int i = 0; i < count; i++) {
            boolean flag1 = random.nextInt(100) < 50
            boolean flag2 = flag1 ? random.nextInt(100) >= 16 : random.nextBoolean()
            BigDecimal amount = new BigDecimal(1000 + random.nextInt(900000)).movePointLeft(2)
            BigDecimal other = random.nextInt(100) < 6 ? amount.add(new BigDecimal(3 + random.nextInt(40))) : amount.add(new BigDecimal(random.nextInt(50)).movePointLeft(2))
            pairs.add([compareId: "${idPrefix}${i}".toString(),
                       file1    : [flag: flag1 ? "Y" : "N", amount: amount.toPlainString()],
                       file2    : [flag: flag2 ? "Y" : "N", amount: other.toPlainString()]] as Map)
        }
        return pairs
    }

    private static boolean brokenRequires(Map pair) { pair.file1.flag == "Y" && pair.file2.flag != "Y" }

    private static boolean brokenAmount(Map pair) {
        new BigDecimal(pair.file1.amount as String).subtract(new BigDecimal(pair.file2.amount as String)).abs() >= 1.00
    }

    /** NOT over one compare-two-fields line; the line reads file1_<field> against file2_<field>. */
    private List<Map> not(Map line) {
        return [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], line + [conditionSeqId: "02", parentConditionSeqId: "01"]]
    }

    private Map compareField(String operator, String tolerance, String key = "flag") {
        return [conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: factTypeId, fieldName: keyNames.file1[key],
                operatorEnumId: operator, compareFieldName: keyNames.file2[key], tolerance: tolerance]
    }

    /** Samples the test thread every 10 ms while the closure runs and returns the 15 most frequent top frames. */
    private static String profile(Closure work) {
        Thread target = Thread.currentThread()
        Map<String, Integer> top = new HashMap<>()
        Map<String, Integer> inclusive = new HashMap<>()
        int[] samples = [0] as int[]
        boolean[] running = [true] as boolean[]
        Thread sampler = new Thread({
            while (running[0]) {
                StackTraceElement[] stack = target.stackTrace
                if (stack.length > 0) {
                    samples[0]++
                    top.merge(stack[0].toString(), 1, Integer.&sum)
                    stack.take(40).collect { it.className + "." + it.methodName }.unique().each { inclusive.merge(it, 1, Integer.&sum) }
                }
                Thread.sleep(10)
            }
        } as Runnable, "probe-sampler")
        sampler.daemon = true
        sampler.start()
        try { work.call() } finally { running[0] = false; sampler.join(2000) }
        String own = top.entrySet().sort { -it.value }.take(15).collect { "  ${it.value} of ${samples[0]}  ${it.key}" }.join("\n")
        String incl = inclusive.entrySet().findAll { !it.key.startsWith("java.") && !it.key.startsWith("jdk.") && !it.key.startsWith("org.junit") &&
                !it.key.startsWith("org.gradle") && !it.key.startsWith("worker.") && !it.key.startsWith("org.codehaus.groovy") &&
                !it.key.startsWith("groovy.lang") && !it.key.startsWith("org.apache.groovy") }.sort { -it.value }.take(15)
                .collect { "  ${it.value} of ${samples[0]}  ${it.key}" }.join("\n")
        return "self (top frame):\n${own}\nincluding callees (non-JDK):\n${incl}\n"
    }

    private static Map ok(Map result) {
        assertEquals([], result.errors, "call failed")
        return result.out as Map
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void actAs() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(PREF_KEY, A)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
