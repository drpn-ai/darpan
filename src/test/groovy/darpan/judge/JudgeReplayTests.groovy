package darpan.judge

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 8. The replay harness: a captured run's extracts go through today's compare (and conclude) and,
 * beside it, through the judge (pair facts, Must hold, Explains), and the two sets of findings are compared by statement and
 * record. The fixture is Dolls Kill R5 in miniature, synthetic (src/test/resources/judge/dk-r5):
 *
 *  - Old config: NetSuite side filter status INCLUDE_IN "Return Authorization : Refunded" (applied by the extractor, so the
 *    RMAs it dropped are in netsuite-rmas.excluded.json), conclusion rows that read NetSuite presence EXCLUDED and ABSENT, and
 *    statement R5 "every Shopify return has an RMA" linked to MISSING_IN_FILE_2 rows.
 *  - New config: Must hold NO_RMA (netsuite_presence != KEPT) and NOT_REFUNDED (netsuite_presence = KEPT and netsuite_status !=
 *    "Return Authorization : Refunded"); Explains WAITING_FOR_ITEM (claim NOT_REFUNDED and status Pending Receipt, EXPECTED)
 *    and UNEXPLAINED. R5 promises NO_RMA, R5R promises NOT_REFUNDED.
 *
 * Shopify returns: SR-1001/1002 have refunded RMAs; SR-1004/1005 (Pending Receipt) and SR-1006 (Pending Refund) have RMAs the
 * filter set aside; SR-1007/1008 have no RMA. NetSuite also holds SR-1009 (refunded, no return in the window) and, set aside,
 * SR-1010 (Pending Receipt, no return in the window).
 *
 * The NetSuite filter is a promise (spec §8: a conclusion of the scope reads NetSuite with presence EXCLUDED), so the judge
 * reads the RMAs it set aside as kept records: they no longer fail NO_RMA, they fail NOT_REFUNDED.
 *
 * Fix round 1 edge cases: SR-1011 is a key NetSuite holds twice (a DUPLICATE row, out of the compare on both sides) with a
 * third, set-aside RMA under it; SR-1012 has a kept refunded RMA and a set-aside one under the same key and no return. Amounts
 * in NetSuite are JSON numbers (Spark renders 24.00 as 24.0 and 12345678.00 as 1.2345678E7). The scope has a conclusion
 * lookup and the check a CheckAsk, both "orderInfo": run A captured it (lookups/lookup-orderInfo-<millis>.json, answering
 * #T10007 as refunded in Shopify); run A2 holds the same extracts and no lookups (ruling P2-24).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeReplayTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final Timestamp REPLAY_TIME = Timestamp.valueOf("2026-11-02 06:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String REFUNDED = "Return Authorization : Refunded"
    private static final String PENDING_RECEIPT = "Return Authorization : Pending Receipt"
    private static final String EXECUTION_A = "JRT_EXEC_A"
    private static final String RUN_FOLDER_A = "reconciliation-runs/" + EXECUTION_A
    private static final String EXECUTION_A2 = "JRT_EXEC_A2"
    private static final String RUN_FOLDER_A2 = "reconciliation-runs/" + EXECUTION_A2
    private static final String CAPTURED_LOOKUP = "lookup-orderInfo-1762000000000.json"
    private static final String KEPT_IN_SPARK = "judge.JudgeReplayTestExtractionServices.extract#KeptInSpark"
    private static final String FILTERED_AT_READ = "judge.JudgeReplayTestExtractionServices.extract#FilteredAtRead"
    private static final List<String> WATCHED = ["rules.RuleSet", "rules.RuleSetRule", "rules.RuleSetCondition", "rules.FactType",
            "rules.FactField", "darpan.judge.CheckDecision", "darpan.judge.DecisionOutcome", "darpan.audit.AuditCheck",
            "darpan.audit.AuditFinding", "darpan.audit.AuditStatement", "darpan.rule.RuleSetCompareScope"]

    private ExecutionContext ec
    private Path backendRoot
    private File dataManagerRoot
    private String checkA
    private String checkB
    private String factTypeId
    private String mustHold
    private String explains
    private Map<String, String> ruleIds = [:]
    private Map cachedReplay
    private Map seededRunFolder

    @BeforeAll
    void setup() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "judge-replay")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml", "component://darpan/data/JudgeSeedData.xml",
                "component://darpan/data/MappingSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml", "component://darpan/data/ConclusionCatalogSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        seedOldConfig()
        seedRunFolder()
        seededRunFolder = runFolderSnapshot()

        actAs(EDITOR_USERNAME, A)
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", [checkId: checkA,
                file1SystemEnumId: "SHOPIFY", file2SystemEnumId: "NETSUITE",
                sampleKeys: [file1: ["returnId", "status"], file2: ["externalReturnId", "status"]]]))
        factTypeId = generated.factTypeId
        assertEquals("netsuite_status", generated.keyNames.file2.status)
        assertEquals(1, rows("rules.FactField", [factTypeId: factTypeId, fieldName: "ask_orderInfo_financialStatus"]).size(),
                "the CheckAsk's kept field is on the pair fact type")
        seedNewConfig()

        // Tenant B: its own check on its own automation, so it can try A's run folder.
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "JRT_AUTO_B", automationName: "B returns",
                companyUserGroupId: B, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "JRT_RS"])
        checkB = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkB, companyUserGroupId: B, checkCode: "b-returns", label: "B returns",
                automationId: "JRT_AUTO_B"])
    }

    @AfterAll
    void cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(SourceSystemConnectorSupport)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsA() {
        actAs(EDITOR_USERNAME, A)
        ec.user.setEffectiveTime(REPLAY_TIME)
    }

    // --------------------------------------------------------------------------------------- the comparison

    @Test
    void replayingTheFixtureLosesNoOldFinding() {
        Map out = replayed()
        assertEquals(["SR-1004", "SR-1005", "SR-1006", "SR-1007", "SR-1008"], out.oldFindings*.recordKey.sort(),
                "today's path: every return missing in NetSuite breaks R5, the ones the filter set aside included")
        assertEquals(["R5"], out.oldFindings*.statementCode.unique())
        assertEquals([], compared().onlyOld)
    }

    @Test
    void sameHoldsEveryOldFindingTheFilterThatIsAPromiseDidNotTouch() {
        assertEquals(2, compared().same, "SR-1007 and SR-1008 have no RMA at all: both paths say R5")
        List<Map> noRma = replayed().judgedFindings.findAll { it.claimOutcomeId == "NO_RMA" }
        assertEquals(["SR-1007", "SR-1008"], noRma*.recordKey.sort())
        assertEquals(["R5"], noRma*.statementCode.unique())
        assertEquals(["REFUNDED_IN_SHOPIFY", "UNEXPLAINED"], noRma.sort { it.recordKey }*.causeOutcomeId,
                "SR-1007's captured ask says refunded in Shopify; SR-1008 has no answer")
    }

    @Test
    void differentStatementHoldsExactlyTheReturnsWhoseRmaTheFilterSetAside() {
        List<Map> moved = compared().differentStatement
        assertEquals(["SR-1004", "SR-1005", "SR-1006"], moved*.recordKey)
        moved.each { Map entry ->
            assertEquals(["R5"], entry.oldStatementCodes, "old: reported as having no RMA")
            assertEquals(["R5R"], entry.judgedStatementCodes, "new: the RMA exists and is not refunded")
        }
        Map<String, Map> byKey = moved.collectEntries { [(it.recordKey): (it.judgedFindings as List<Map>)[0]] }
        assertEquals(["WAITING_FOR_ITEM", "JOK_EXPECTED"], [byKey["SR-1004"].causeOutcomeId, byKey["SR-1004"].outcomeKindEnumId])
        assertEquals(["WAITING_FOR_ITEM", "JOK_EXPECTED"], [byKey["SR-1005"].causeOutcomeId, byKey["SR-1005"].outcomeKindEnumId])
        assertEquals(["UNEXPLAINED", "JOK_FINDING"], [byKey["SR-1006"].causeOutcomeId, byKey["SR-1006"].outcomeKindEnumId],
                "Pending Refund is not waiting for the item")
        assertEquals(["NOT_REFUNDED"], moved.collect { (it.judgedFindings as List<Map>)*.claimOutcomeId }.flatten().unique())
    }

    @Test
    void anRmaTheFilterHidThatHasNoReturnIsOnlyJudged() {
        Map c = compared()
        assertEquals([["R5R", "SR-1010"]], c.onlyJudged.collect { [it.statementCode, it.recordKey] },
                "the filter kept SR-1010 out of today's read entirely; the promise sees it")
        assertEquals("WAITING_FOR_ITEM", c.onlyJudged[0].causeOutcomeId)
        assertNull(replayed().judgedFindings.find { it.recordKey in ["SR-1001", "SR-1002", "SR-1009", "SR-1011", "SR-1012"] },
                "refunded RMAs, with or without a return in the window, break nothing")
    }

    @Test
    void eachJudgedFindingNamesItsPromiseAndCauseRules() {
        List<Map> judged = replayed().judgedFindings
        assertEquals(6, judged.size())
        judged.each { Map f ->
            assertEquals(mustHold, f.claimRuleSetId)
            assertEquals(f.claimOutcomeId == "NO_RMA" ? ruleIds.noRma : ruleIds.notRefunded, f.claimRuleId, "${f}")
            assertEquals(explains, f.causeRuleSetId)
            assertEquals(f.causeOutcomeId == "WAITING_FOR_ITEM" ? ruleIds.waiting :
                    f.causeOutcomeId == "REFUNDED_IN_SHOPIFY" ? ruleIds.refundedInShopify : null, f.causeRuleId, "${f}")
        }
    }

    @Test
    void compareKeysByStatementAndRecord() {
        Map out = ok(underFacadeGrant("judge.JudgeReplayServices.compare#JudgedFindings", [
                oldFindings   : [[statementCode: "S1", recordKey: "K1"], [statementCode: "S1", recordKey: "K2"], [statementCode: "S2", recordKey: "K3"]],
                judgedFindings: [[statementCode: "S1", recordKey: "K1"], [statementCode: "S9", recordKey: "K2"], [statementCode: "S1", recordKey: "K4"]]]))
        assertEquals(1, out.same)
        assertEquals([[statementCode: "S2", recordKey: "K3"]], out.onlyOld)
        assertEquals([[statementCode: "S1", recordKey: "K4"]], out.onlyJudged)
        assertEquals(["K2"], out.differentStatement*.recordKey)
        assertEquals([["S1"], ["S9"]], [out.differentStatement[0].oldStatementCodes, out.differentStatement[0].judgedStatementCodes])
    }

    // --------------------------------------------------------------------------------------- the old path stays as it was

    /**
     * Review M1. Through the entry every live caller uses, reconcile#RuleSetCompareScope, with the flag absent, false and true,
     * on the scope with no field rule and with one: the diff document the live writer (writeDiffDatasetOutput) makes of diffDf,
     * and every other output, are byte-identical; only the judge's three outputs are added, and only with the flag true.
     */
    @Test
    void theCompareLiveCallersUseIsByteIdenticalWithTheFlagAbsentFalseAndTrue() {
        List<String> judgeKeys = ["judgedFindings", "judgeWarnings", "judgedPairCounts"]
        ["N", "Y"].each { String ruleEnabled ->
            updateRow("darpan.rule.Rule", [ruleId: "JRT_AMOUNT_RULE"], [enabled: ruleEnabled])
            try {
                Map<String, byte[]> diffs = [:]
                Map<String, String> results = [:]
                [absent: null, "false": false, "true": true].each { String mode, Boolean flag ->
                    File copy = copyExtractsFor("identity-${ruleEnabled}-${mode}")
                    Map called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope",
                            compareParams(copy, flag))
                    assertEquals([], called.errors, "${ruleEnabled}/${mode}")
                    Map out = called.out
                    try {
                        if (flag == true) assertTrue(out.containsKey("judgedFindings"), "the judge ran")
                        else assertTrue(judgeKeys.every { !out.containsKey(it) }, "no judge output without the flag: ${out.keySet()}")
                        if (ruleEnabled == "Y") assertTrue((out.ruleDifferenceCount as long) > 0, "the field rule fired")
                        File dir = new File(dataManagerRoot, "identity-out/${ruleEnabled}-${mode}")
                        dir.deleteDir()
                        Map written = darpan.reconciliation.core.ReconciliationServices.writeDiffDatasetOutput(ec,
                                (org.apache.spark.sql.Dataset) out.diffDf, dir.absolutePath, "result.json", "diff.json",
                                [timestamp: "fixed", ruleSetId: out.ruleSetId, compareScopeId: out.compareScopeId],
                                [totalDifferences: out.differenceCount], (List) out.validationErrors, (List) out.processingWarnings)
                        diffs[mode] = new File(written.diffLocation as String).bytes
                        results[mode] = groovy.json.JsonOutput.toJson(new TreeMap(out.findAll { k, v ->
                            !(v instanceof org.apache.spark.sql.Dataset) && k != "persistedSources" && !(k in judgeKeys) }))
                    } finally {
                        darpan.reconciliation.core.ReconciliationServices.unpersistDatasets((List) (out?.persistedSources ?: []))
                    }
                }
                assertTrue(diffs.absent.length > 0)
                ["false", "true"].each { String mode ->
                    assertArrayEquals(diffs.absent, diffs[mode], "rules ${ruleEnabled}: the diff document differs with the flag ${mode}")
                    assertEquals(results.absent, results[mode], "rules ${ruleEnabled}: an output differs with the flag ${mode}")
                }
            } finally {
                updateRow("darpan.rule.Rule", [ruleId: "JRT_AMOUNT_RULE"], [enabled: "N"])
            }
        }
    }

    @Test
    void theJudgeDoesNotPerturbTheReplaysOldOutputs() {
        Map before = runFolderSnapshot()
        Map on = replay(checkA, RUN_FOLDER_A, true)
        Map off = replay(checkA, RUN_FOLDER_A, false)
        assertEquals([], on.errors)
        assertEquals([], off.errors)
        File onDir = replayDir(on.out.replayFolder as String)
        File offDir = replayDir(off.out.replayFolder as String)
        assertNotEquals(onDir.canonicalPath, offDir.canonicalPath, "a new folder per replay")
        ["diff.json", "result.json"].each { String name ->
            byte[] a = new File(onDir, name).bytes
            byte[] b = new File(offDir, name).bytes
            assertTrue(a.length > 0, name)
            assertArrayEquals(a, b, "${name} differs with the judge on")
        }
        assertTrue(new File(onDir, "judged-findings.json").isFile())
        assertFalse(new File(offDir, "judged-findings.json").exists(), "the judge off writes no judged findings")
        assertEquals(on.out.oldFindings, off.out.oldFindings)
        assertEquals([], off.out.judgedFindings)
        assertEquals(before, runFolderSnapshot(), "the captured run folder is evidence: nothing written into it")
        assertEquals(seededRunFolder, before)
    }

    @Test
    void theReplayFolderHoldsTheOldOutputsAndTheJudgedFindings() {
        Map out = replayed()
        File dir = replayDir(out.replayFolder as String)
        assertTrue(out.replayFolder.startsWith("judge-replays/${checkA}/".toString()), out.replayFolder as String)
        assertEquals(["diff.json", "extracts", "judged-findings.json", "lookups", "result.json"], dir.list().sort() as List)
        assertEquals([CAPTURED_LOOKUP], new File(dir, "lookups").list() as List)
        assertEquals(["shopify-returns.json", "shopify-returns.json.records.jsonl"], new File(dir, "extracts/FILE_1").list().sort() as List)
        assertEquals(["netsuite-rmas.excluded.json", "netsuite-rmas.json", "netsuite-rmas.json.records.jsonl"],
                new File(dir, "extracts/FILE_2").list().sort() as List, "the compare's records files are written beside the copies")
        assertEquals(seededRunFolder, runFolderSnapshot(), "the captured run folder is evidence: nothing written into it, ever")
        assertEquals("${out.replayFolder}/judged-findings.json".toString(), out.judgedPath)
        Map judgedDoc = new JsonSlurper().parse(new File(dir, "judged-findings.json")) as Map
        assertEquals(checkA, judgedDoc.checkId)
        assertEquals([matched: 2, missing: 7, readBack: 1], out.judgedPairCounts,
                "SR-1001/1002 matched; five returns, SR-1009 and SR-1012 missing; SR-1010 only in the sidecar")
        assertEquals(out.judgedPairCounts, judgedDoc.pairs)
        assertEquals([], out.replayWarnings, "run A captured its lookup")
        assertEquals(out.judgedFindings*.recordKey, (judgedDoc.findings as List<Map>)*.recordKey)
        Map result = new JsonSlurper().parse(new File(dir, "result.json")) as Map
        assertEquals(7, result.missingObjectDifferenceCount, "5 returns missing in NetSuite, SR-1009 and SR-1012 missing in Shopify")
        assertTrue((result.duplicateDifferenceCount as int) > 0, "SR-1011 twice in NetSuite")
        assertEquals((result.missingObjectDifferenceCount as int) + (result.duplicateDifferenceCount as int), result.differenceCount)
        Map diff = new JsonSlurper().parse(new File(dir, "diff.json")) as Map
        Map<String, String> codes = ((List<Map>) diff.differences).findAll { it.diffType == "MISSING_IN_FILE_2" }
                .collectEntries { [(it.primaryId): ((Map) it.conclusion).code] }
        assertEquals("DKR5T_SET_ASIDE", codes["SR-1004"], "today's conclude pass ran on the replay's own document")
        assertEquals("DKR5T_REFUNDED_IN_SHOPIFY", codes["SR-1007"], "the conclude pass's lookup was answered from the capture")
        assertEquals("DKR5T_NO_RMA", codes["SR-1008"])
    }

    /**
     * Ruling P2-24. The scope has a conclusion lookup and the check a CheckAsk, so today's fetchLookup would resolve a connector
     * and call its extract. With the lookup captured, its answers appear (the conclusion and the ask's cause); without, nothing is
     * fetched, each is named in a warning, and no field appears. The live AskSupport.fetcher is never swapped or used.
     * DAR-BE-318 (plan 3b-2b): the only registry reads are a side's Keep plan (replay#CheckJudgement resolves the side's connector
     * row as the run path does, RunExtractSupport.resolveExtractConnector) for a capture its read did not filter; no system is called.
     */
    @Test
    void aReplayCallsNoSystemWhetherItsLookupsWereCapturedOrNot() {
        int connectorCalls = 0
        List<String> keepPlanReads = []
        Closure count = { Object[] args -> connectorCalls++; return null }
        SourceSystemConnectorSupport.metaClass.static.resolve = { def e, String id -> keepPlanReads.add(id); return null }
        SourceSystemConnectorSupport.metaClass.static.resolveBySystemEnumId = { def e, String id -> count() }
        SourceSystemConnectorSupport.metaClass.static.resolveByExtractServiceName = { def e, String name -> count() }
        SourceSystemConnectorSupport.metaClass.static.resolveByExpectedSourceConfigType = { def e, String type -> keepPlanReads.add("configType:" + type); return null }
        Closure liveFetcher = AskSupport.fetcher
        try {
            // The stub is live: today's lookup reaches it.
            RunConclusionStep.fetchLookup(ec, [name: "probe", systemEnumId: "NETSUITE"], ["SR-1"], A, [])
            assertEquals(1, connectorCalls, "test precondition: the counting stub intercepts connector resolution")
            connectorCalls = 0
            keepPlanReads.clear()

            Map captured = replay(checkA, RUN_FOLDER_A, true)
            assertEquals([], captured.errors)
            assertEquals(0, connectorCalls, "captured: no extract or lookup connector was resolved")
            // Task 16e round 2: every side's Keep decision asks KeepPlanSupport.landedFilteredAtRead, which needs the side's connector
            // for its built-in Keep lines, so a capture filtered at read is resolved too (the registry only, no system)
            assertEquals(["NETSUITE", "SHOPIFY", "configType:null", "configType:null"], keepPlanReads.sort(),
                    "only the Keep plan read the registry, once per side, by the run path's resolution")
            assertEquals([], captured.out.replayWarnings)
            assertFalse(captured.out.judgeWarnings.any { it.contains("not captured") }, "${captured.out.judgeWarnings}")
            assertEquals("REFUNDED_IN_SHOPIFY", captured.out.judgedFindings.find { it.recordKey == "SR-1007" }.causeOutcomeId,
                    "the captured ask's field reached the pair fact")

            Map bare = replay(checkA, RUN_FOLDER_A2, true)
            assertEquals([], bare.errors)
            assertEquals(0, connectorCalls, "not captured: still no connector")
            assertEquals(["Lookup orderInfo not captured in run ${RUN_FOLDER_A2}; not replayed".toString(),
                          "Ask orderInfo not captured in the replayed run; not replayed"], bare.out.replayWarnings,
                    "the conclude pass's lookup and the judge's ask, once each")
            assertTrue(bare.out.judgeWarnings.contains("Ask orderInfo not captured in the replayed run; not replayed"), "${bare.out.judgeWarnings}")
            assertEquals("UNEXPLAINED", bare.out.judgedFindings.find { it.recordKey == "SR-1007" }.causeOutcomeId, "no ask field")
            File bareDir = replayDir(bare.out.replayFolder as String)
            Map diff = new JsonSlurper().parse(new File(bareDir, "diff.json")) as Map
            assertEquals("DKR5T_NO_RMA", ((Map) ((List<Map>) diff.differences).find { it.primaryId == "SR-1007" }.conclusion).code,
                    "no lookup answer reached the conclusion")
            assertEquals([], new File(bareDir, "lookups").list() as List)
            assertTrue(AskSupport.fetcher.is(liveFetcher), "the live fetcher is never swapped")
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(SourceSystemConnectorSupport)
        }
    }

    /** Review N-M4 / N-M3: an unanswered brain question (a default-lookup one has no lookup in a replay) and ask warnings are named. */
    @Test
    void replayWarningsNameUnansweredBrainQuestionsAndAsks() {
        List<String> out = JudgeReplaySupport.replayWarnings(["Lookup orderInfo not captured in run R; not replayed"] as Set,
                [warnings: ["Question orderPayments not answered for 3 subject(s): the scope has no lookup to ask orderPayments of"]],
                ["Ask orderInfo not captured in the replayed run; not replayed", "2 DUPLICATE_IN_FILE_2 rows are not pairs and were not judged",
                 "Ask orderInfo not captured in the replayed run; not replayed"])
        assertEquals(["Lookup orderInfo not captured in run R; not replayed",
                      "Brain: Question orderPayments not answered for 3 subject(s): the scope has no lookup to ask orderPayments of",
                      "Ask orderInfo not captured in the replayed run; not replayed"], out)
    }

    @Test
    void anUnreadableCaptureIsAWarningNotAFailure() {
        File dir = new File(dataManagerRoot, "identity/bad-capture")
        dir.deleteDir()
        dir.mkdirs()
        new File(dir, "lookup-orderInfo-1.json").text = '{"records":[{"name":'
        Set<String> notCaptured = new TreeSet<>()
        List<String> warnings = []
        Map found = (Map) JudgeReplaySupport.capturedFetcher(dir, "run R", "Ask", notCaptured)
                .call(ec, [name: "orderInfo", keyField: "name"], ["#T1"], A, warnings)
        assertEquals([:], found)
        assertTrue(warnings.size() == 1 && warnings[0].startsWith("Ask orderInfo not read from its capture lookup-orderInfo-1.json"), "${warnings}")
        assertEquals(warnings as Set, notCaptured)
    }

    // --------------------------------------------------------------------------------------- pairs (review I1, I2)

    @Test
    void aKeyKeptAndSetAsideOnOneSideOrDuplicatedIsPairedOnceAndBreaksNothing() {
        Map out = replayed()
        assertNull(out.judgedFindings.find { it.recordKey in ["SR-1011", "SR-1012"] },
                "SR-1012's kept refunded RMA is judged, its set-aside one is not paired again; SR-1011 is a duplicate, not a pair")
        assertTrue(out.judgeWarnings.any { it ==~ /\d+ DUPLICATE_IN_FILE_2 rows are not pairs and were not judged/ }, "${out.judgeWarnings}")
        assertTrue(out.judgeWarnings.contains("2 file2 records set aside under a key the compare already holds (kept or duplicated) were not judged"),
                "${out.judgeWarnings}")
        assertFalse(out.judgeWarnings.any { it.contains("appears twice") })
    }

    @Test
    void aNumberReadsTheSameFromSparkAndFromTheSidecar() {
        JsonSlurper slurper = new JsonSlurper()
        Map spark = darpan.common.FactText.flat((Map) slurper.parseText('{"a":24.0,"b":24,"c":1.2345678E7,"d":"24.00","e":"000123"}'))
        Map raw = darpan.common.FactText.flat((Map) slurper.parseText('{"a":24.00,"b":24,"c":12345678.00,"d":"24.00","e":"000123"}'))
        assertEquals([a: "24", b: "24", c: "12345678", d: "24.00", e: "000123"], spark, "numbers plain, text untouched")
        assertEquals(spark, raw)

        // End to end: the compare's own frames against the sidecar, as the judge reads them.
        File copy = copyExtractsFor("numbers")
        Map compared = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope",
                compareParams(copy, true))   // the flag builds the matched frame with no field rule
        assertEquals([], compared.errors)
        try {
            Map sidecar = JudgeReplaySupport.readSidecar(new File(copy, "FILE_2/netsuite-rmas.json"),
                    [keyFields: ["externalReturnId"], keyReliable: true], true, "file2", [])
            List<Map> pairs = new JudgeReplaySupport.PairRows((org.apache.spark.sql.Dataset) compared.out.matchedPairDf,
                    (org.apache.spark.sql.Dataset) compared.out.diffDf, [file1: [:], file2: sidecar.records], [:], [:], [:]).toList()
            Map<String, Object> amount = pairs.collectEntries { [(it.compareId): ((Map) it.file2)?.amount] }
            assertEquals("39", amount["SR-1001"], "kept, matched: Spark's 39.0")
            assertEquals("12345678", amount["SR-1009"], "kept, missing in Shopify: Spark's 1.2345678E7")
            assertEquals("24", amount["SR-1004"], "read back: the sidecar's raw 24.00")
            assertEquals("12345678", amount["SR-1010"], "read back, no row: the sidecar's raw 12345678.00")
            assertEquals(pairs*.compareId.unique(), pairs*.compareId, "no compare id paired twice")
        } finally {
            darpan.reconciliation.core.ReconciliationServices.unpersistDatasets((List) (compared.out?.persistedSources ?: []))
        }
    }

    @Test
    void pairRowsReadAnIntegerBeyondTheLongRangeExactly() {
        org.apache.spark.sql.SparkSession spark = bigIntSpark()
        try {
            String data = '{"ref":12345678901234567890123,"lines":[{"serial":-99999999999999999999999999}],"price":12.50,"small":7}'
            for (String type : ["MISSING_IN_FILE_2", "MISSING_IN_FILE_1"]) {
                Map pair = pairsOf(spark, null, [[diffType: type, primaryId: "k1", data: data]])[0]
                Map side = (Map) (type == "MISSING_IN_FILE_2" ? pair.file1 : pair.file2)
                assertEquals("12345678901234567890123", side.ref, type)
                assertEquals('[{"serial":-99999999999999999999999999}]', side.lines, type)
                assertEquals("12.5", side.price, type)
                assertEquals("7", side.small, type)
                assertEquals(["ref", "lines", "price", "small"], side.keySet() as List, type)
            }
            String matched = '{"primaryId":"k2","file1":{"ref":12345678901234567890123,"small":7},"file2":{"ref":-99999999999999999999999999,"small":7}}'
            Map pair = pairsOf(spark, matched, [])[0]
            assertEquals("12345678901234567890123", ((Map) pair.file1).ref)
            assertEquals("-99999999999999999999999999", ((Map) pair.file2).ref)
            assertEquals("7", ((Map) pair.file1).small)
        } finally {
            spark.stop()
        }
    }

    @Test
    void pairRowsFailACorruptRowAsARuntimeError() {
        org.apache.spark.sql.SparkSession spark = bigIntSpark()
        try {
            RuntimeException e = assertThrows(RuntimeException,
                    { pairsOf(spark, null, [[diffType: "MISSING_IN_FILE_2", primaryId: "k9", data: "{not json"]]) })
            assertTrue(e.message.contains("k9"), e.message)
        } finally {
            spark.stop()
        }
    }

    private static org.apache.spark.sql.SparkSession bigIntSpark() {
        return org.apache.spark.sql.SparkSession.builder().appName("JudgeReplayTests-BigInt").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    private static List<Map> pairsOf(org.apache.spark.sql.SparkSession spark, String matchedJson, List<Map> missingRows) {
        org.apache.spark.sql.Dataset matched = matchedJson == null ? null :
                spark.read().json(spark.createDataset([matchedJson], org.apache.spark.sql.Encoders.STRING()))
        org.apache.spark.sql.Dataset missing = missingRows.isEmpty() ? null :
                spark.read().json(spark.createDataset(missingRows.collect { groovy.json.JsonOutput.toJson(it) }, org.apache.spark.sql.Encoders.STRING()))
        return new JudgeReplaySupport.PairRows(matched, missing, [file1: [:], file2: [:]], [:], [:], [:]).toList()
    }

    // --------------------------------------------------------------------------------------- Spark presence (plan 3b-2b, Task 16)

    /**
     * Discovery risk 3: HotWax order grain, HotWax returns and Shopify return refs write no side file, so the judge read every
     * record their filters set aside as ABSENT. On a capture its read did not filter (taken once its extractor stops filtering),
     * the replay keeps such a side in Spark (its connector takes Keep, writes no side file and its extract no longer declares
     * the filter parameter) and the compare's setAsideBySide reaches the judge: the record reads SET_ASIDE. Core has no such
     * system, so the fixture's Shopify side (no read-time exclusions) stands in for them; the grain only shapes the compare id,
     * which Spark computes (Task 11). An old, read-filtered capture of such a side stays as it was (anOldReadFiltered...).
     */
    @Test
    void aSideWithoutASideFileNowReadsSetAside() {
        Map out = null
        Map<String, Map> facts = withConnectors([SHOPIFY: "N"]) {
            withFilter("FILE_1", "returnId", "EXCLUDE_IN", "SR-1001") {
                factsJudged { out = ok(replay(checkA, RUN_FOLDER_A, true)) }
            }
        }
        assertEquals([shopify: "SET_ASIDE", netsuite: "KEPT"], presence(facts["SR-1001"]),
                "Shopify set SR-1001 aside in Spark and wrote no side file; NetSuite holds its refunded RMA")
        assertEquals([shopify: "ABSENT", netsuite: "KEPT"], presence(facts["SR-1009"]), "SR-1009 has no return at all")
        assertEquals([shopify: "KEPT", netsuite: "ABSENT"], presence(facts["SR-1007"]))
        File replayFile1 = new File(replayDir(out.replayFolder as String), "extracts/FILE_1")
        assertEquals(["shopify-returns.json", "shopify-returns.json.records.jsonl", "shopify-returns.keep.json", "shopify-returns.set-aside.rows"],
                replayFile1.list().sort() as List, "Spark's set-aside artifact, and no side file")
    }

    /** Carry note 1: the setAsideBySide Task 11 passes is declared on judge#ComparePairs, so it reaches the judge and is read. */
    @Test
    void theSetAsideIdsTheCompareReportsReachTheJudge() {
        File copy = copyExtractsFor("keep-reaches-judge")
        Map sideKeep = darpan.reconciliation.source.KeepPlanSupport.sideKeepFromFilterRows(ec,
                darpan.reconciliation.source.SourceFilterSupport.toRecordFieldRules([[sequenceNum: 1, fieldExpression: "returnId",
                        operator: "EXCLUDE_IN", filterValues: "SR-1001"] as Map<String, Object>]),
                [systemEnumId: "ACME", filterParameterName: "sourceFilters", excludedRecordsSidecar: "N"], [:])
        Map called = null
        Map<String, Map> facts = factsJudged {
            called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope",
                    compareParams(copy, true) + [keepBySide: [FILE_1: sideKeep]])
        }
        assertEquals([], called.errors)
        try {
            assertEquals([file1: ["SR-1001"] as Set], called.out.setAsideBySide)
            assertEquals("SET_ASIDE", facts["SR-1001"]?.shopify_presence, "the compare's set-aside id reached the pair fact")
        } finally {
            darpan.reconciliation.core.ReconciliationServices.unpersistDatasets((List) (called.out?.persistedSources ?: []))
        }
    }

    /**
     * A capture made after the connectors stop filtering holds every RMA and no side file (the connector writes none): Spark sets
     * the unrefunded ones aside, and on NetSuite, a promise side, the judge reads them back as kept from the Keep artifact.
     */
    @Test
    void aPromiseSideReadsItsSetAsideRecordsBackAsKept() {
        String runFolder = seedNewCapture("JRT_EXEC_KEEP_N")
        Map out = withConnectors([NETSUITE: "N"]) { ok(replay(checkA, runFolder, true)) }
        assertEquals(["netsuite-rmas.json", "netsuite-rmas.json.records.jsonl", "netsuite-rmas.keep.json", "netsuite-rmas.set-aside.rows"],
                new File(replayDir(out.replayFolder as String), "extracts/FILE_2").list().sort() as List,
                "no side file: what is read back comes from Spark's set-aside")
        assertEquals(judgedKeys(replayed().judgedFindings), judgedKeys(out.judgedFindings),
                "SR-1004/1005/1006 and SR-1010 are read back as kept and fail NOT_REFUNDED, as from the old side file")
        assertEquals([matched: 2, missing: 7, readBack: 1], out.judgedPairCounts)
        assertTrue(out.judgeWarnings.contains("2 file2 records set aside under a key the compare already holds (kept or duplicated) were not judged"),
                "${out.judgeWarnings}")
    }

    /**
     * The same new capture with a connector that writes the side file: Spark writes it (KeepStepSupport), today's conclude pass
     * reads it, and every old finding and every judged finding is the old capture's. The judge reads Spark's records once.
     */
    @Test
    void aReplayStillMatchesTodaysFindings() {
        String runFolder = seedNewCapture("JRT_EXEC_KEEP_Y")
        Map out = withConnectors([NETSUITE: "Y"]) { ok(replay(checkA, runFolder, true)) }
        assertTrue(new File(replayDir(out.replayFolder as String), "extracts/FILE_2/netsuite-rmas.excluded.json").isFile(), "Spark wrote the side file")
        assertEquals(oldKeys(replayed().oldFindings), oldKeys(out.oldFindings))
        assertEquals(judgedKeys(replayed().judgedFindings), judgedKeys(out.judgedFindings))
        assertEquals(replayed().judgedPairCounts, out.judgedPairCounts)
        assertFalse(out.judgeWarnings.any { it.contains("already read back") }, "Spark's records are not read twice: ${out.judgeWarnings}")
    }

    /**
     * Carry note 6, fix round 1 (review I1): a capture from before Keep moved into Spark (filtered by its extractor, a side file,
     * read-time configuredExclusions in its metadata, no .keep.json) replays from its side file as before, even where its side
     * would keep in Spark today: the replay runs no Keep over it and writes no .keep.json.
     */
    @Test
    void anOldCaptureStillReadsItsSideFile() {
        Map out = withConnectors([NETSUITE: "Y"]) { ok(replay(checkA, RUN_FOLDER_A, true)) }
        File file2 = new File(replayDir(out.replayFolder as String), "extracts/FILE_2")
        assertFalse(new File(file2, "netsuite-rmas.keep.json").exists(), "its read filtered it: no Keep ran")
        assertFalse(new File(file2, "netsuite-rmas.set-aside.rows").exists())
        assertArrayEquals(fixture().resolve("netsuite-rmas.excluded.json").toFile().bytes, new File(file2, "netsuite-rmas.excluded.json").bytes,
                "the extractor's side file is left as it was")
        assertEquals(judgedKeys(replayed().judgedFindings), judgedKeys(out.judgedFindings))
        assertEquals(oldKeys(replayed().oldFindings), oldKeys(out.oldFindings))
        assertEquals([matched: 2, missing: 7, readBack: 1], out.judgedPairCounts, "SR-1010 is read back from the side file")
    }

    /**
     * Review I1: an old capture of a side whose connector writes no side file (HotWax orders and returns, Shopify return refs) and
     * whose read dropped records (its metadata counts them) concludes as a live run does: a missing record is unknown, never "not
     * found". Keeping it in Spark would write a zero-count .keep.json the conclude pass reads as authoritative.
     */
    @Test
    void anOldReadFilteredCaptureWithNoSideFileStaysUnknown() {
        String runFolder = seedCapture("JRT_EXEC_OLD_N", false)
        Map bare = ok(replay(checkA, runFolder, false))
        Map out = withConnectors([NETSUITE: "N"]) { ok(replay(checkA, runFolder, false)) }
        assertFalse(new File(replayDir(out.replayFolder as String), "extracts/FILE_2/netsuite-rmas.keep.json").exists(), "no Keep ran")
        Map<String, Object> codes = missingInFile2Codes(out)
        assertEquals(missingInFile2Codes(bare), codes, "the conclusions a replay with no Keep gives")
        assertEquals(["SR-1004", "SR-1005", "SR-1006", "SR-1007", "SR-1008"], codes.keySet().sort() as List)
        assertFalse(codes.values().any { it in ["DKR5T_NO_RMA", "DKR5T_REFUNDED_IN_SHOPIFY"] }, "nothing reads NetSuite as having no RMA: ${codes}")
    }

    /**
     * Review I1: an INCLUDE rule on a field a capture lacks sets nothing aside. Shopify's extract still filters at its read
     * (it declares sourceFilters, Ruling 3b2b-23), and NetSuite's capture was filtered at read, so neither keeps in Spark.
     */
    @Test
    void anIncludeRuleOnAFieldTheCaptureLacksSetsNothingAside() {
        Map out = withConnectors([SHOPIFY: "N"], FILTERED_AT_READ) {
            withConnectors([NETSUITE: "Y"]) {
                withFilter("FILE_1", "channel", "INCLUDE_IN", "web") {
                    withFilter("FILE_2", "subsidiary", "INCLUDE_IN", "EU") { ok(replay(checkA, RUN_FOLDER_A, true)) }
                }
            }
        }
        File extracts = new File(replayDir(out.replayFolder as String), "extracts")
        assertEquals(["shopify-returns.json", "shopify-returns.json.records.jsonl"], new File(extracts, "FILE_1").list().sort() as List)
        assertFalse(new File(extracts, "FILE_2/netsuite-rmas.keep.json").exists())
        assertEquals(oldKeys(replayed().oldFindings), oldKeys(out.oldFindings))
        assertEquals(judgedKeys(replayed().judgedFindings), judgedKeys(out.judgedFindings))
        assertEquals(replayed().judgedPairCounts, out.judgedPairCounts)
    }

    /**
     * Review I1 (Task 10 review M5/M6) with Ruling 3b2b-25: Spark cannot keep a side whose records are not the extract's
     * top-level records array. Where its extract no longer filters either, the replay fails with the live path's message rather
     * than replaying it unfiltered; where its extract still filters, it replays as today.
     */
    @Test
    void aSideNotKeyedOnTopLevelRecordsFailsOrReplaysAsToday() {
        insert("darpan.rule.RuleSet", [ruleSetId: "JRT_ROOT_RS", ruleSetName: "Orders under another root", companyUserGroupId: A])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "JRT_ROOT_SCOPE", ruleSetId: "JRT_ROOT_RS", objectType: "ORDER",
                description: "Orders under another root", companyUserGroupId: A])
        [["FILE_1", "ACME_LEFT", "orders"], ["FILE_2", "ACME_RIGHT", "records"]].each { List side ->
            insert("moqui.basic.Enumeration", [enumId: side[1], enumTypeId: "DarpanSystemSource", enumCode: side[1], description: side[1]])
            insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "JRT_ROOT_SCOPE", fileSide: side[0], systemEnumId: side[1],
                    fileTypeEnumId: "DftJson", recordRootExpression: side[2], primaryIdExpression: "orderId", companyUserGroupId: A])
        }
        insert("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "JRT_ROOT_SCOPE", fileSide: "FILE_1", sequenceNum: 1,
                fieldExpression: "status", operator: "INCLUDE_IN", filterValues: "OPEN", companyUserGroupId: A])
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "JRT_AUTO_ROOT", automationName: "Orders under another root",
                companyUserGroupId: A, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "JRT_ROOT_RS"])
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "JRT_EXEC_ROOT", automationId: "JRT_AUTO_ROOT",
                companyUserGroupId: A, statusEnumId: "AUT_STAT_SUCCESS"])
        String checkRoot = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkRoot, companyUserGroupId: A, checkCode: "orders-root", label: "Orders under another root",
                savedRunId: "JRT_ROOT_RS", automationId: "JRT_AUTO_ROOT"])
        File run = new File(dataManagerRoot, "reconciliation-runs/JRT_EXEC_ROOT")
        run.deleteDir()
        File side1 = new File(run, "20261101-060000000")
        File side2 = new File(run, "20261101-060000000-FILE_2")
        [side1, side2]*.mkdirs()
        new File(side1, "acme-orders.json").text = '{"metadata":{},"orders":[{"orderId":"O1","status":"OPEN"},{"orderId":"O2","status":"CLOSED"}]}'
        new File(side2, "acme-ledger.json").text = '{"metadata":{},"records":[{"orderId":"O1"},{"orderId":"O2"}]}'

        Map refused = withConnectors([ACME_LEFT: "N"]) { replay(checkRoot, "reconciliation-runs/JRT_EXEC_ROOT", false) }
        assertEquals(["File 1 (ACME_LEFT) cannot be filtered: its source no longer filters at read and its records are not top-level, " +
                "so Keep cannot run in Spark."], refused.errors)

        Map out = withConnectors([ACME_LEFT: "N"], FILTERED_AT_READ) { ok(replay(checkRoot, "reconciliation-runs/JRT_EXEC_ROOT", false)) }
        File dir = replayDir(out.replayFolder as String)
        assertFalse(new File(dir, "extracts/FILE_1/acme-orders.keep.json").exists(), "no Keep ran on a side not keyed on records")
        Map result = new JsonSlurper().parse(new File(dir, "result.json")) as Map
        assertEquals(0, result.missingObjectDifferenceCount, "O2 was not set aside: both orders match, as today")
        assertEquals(0, result.differenceCount)
    }

    /** A side that is not a promise: Spark's ids and an old side file's ids both read SET_ASIDE; Spark's alone are not copied. */
    @Test
    void sparksIdsAndAnOldSideFilesIdsBothReadSetAside() {
        File dir = scratch("presence-union")
        File extract = new File(dir, "x.json")
        extract.text = '{"records":[]}'
        new File(dir, "x.excluded.json").text = '{"sourceFileName":"x.json","truncated":false,"total":1,"records":[{"id":"A","_excludedBy":"1:id"}]}'
        Map keySpec = [keyFields: ["id"], keyReliable: true]
        Set<String> spark = ["B"] as Set
        assertEquals(["A", "B"] as Set, JudgeReplaySupport.sidePresence(extract, keySpec, false, "file1", spark, []).ids)
        assertEquals(["B"] as Set, spark, "the compare's set is not changed")
        assertEquals(["A"] as Set, JudgeReplaySupport.sidePresence(extract, keySpec, false, "file1", null, []).ids, "an old capture: its side file")
        new File(dir, "x.excluded.json").delete()
        assertSame(spark, JudgeReplaySupport.sidePresence(extract, keySpec, false, "file1", spark, []).ids, "no side file: Spark's set itself")
        List<String> warnings = []
        assertSame(spark, JudgeReplaySupport.sidePresence(extract, [keyReliable: false], false, "file1", spark, warnings).ids)
        assertEquals(["The file1 key cannot be rebuilt from its records, so nothing its side file set aside is judged"], warnings)
    }

    /**
     * A side file a Spark Keep merged its records into (the connector writes one, and the extractor wrote some itself) is read
     * for its extractor's part only: Spark's records come from the rows file, once.
     */
    @Test
    void aPromiseSideReadsAMergedSideFileForItsExtractorsPartOnly() {
        File dir = scratch("presence-merged")
        File extract = new File(dir, "x.json")
        extract.text = '{"records":[{"id":"K1","amount":1},{"id":"S1","amount":24.00}]}'
        KeepArtifactsWriter.rows(extract, "1\tA:1")
        KeepArtifactsWriter.summary(extract, [extractorSideFile: [total: 1, kept: 1, truncated: false]])
        new File(dir, "x.excluded.json").text = '{"sourceFileName":"x.json","truncated":false,"total":2,"records":[' +
                '{"id":"X1","amount":5,"_excludedBy":"BUILT_IN:1"},{"id":"S1","amount":24.00,"_excludedBy":"1:status"}]}'
        List<String> warnings = []
        Map read = JudgeReplaySupport.sidePresence(extract, [keyFields: ["id"], keyReliable: true], true, "file2", null, warnings)
        assertEquals(["S1", "X1"], read.records.keySet() as List, "Spark's set-aside record, then the extractor's")
        assertEquals("24", read.records.S1.amount)
        assertEquals([], warnings, "no record is read twice")
    }

    @Test
    void aPromiseSideReadsBackAtMostMaxEntriesAndSaysSo() {
        int cap = darpan.reconciliation.conclusion.ExcludedRecordsSidecar.MAX_ENTRIES
        File dir = scratch("presence-cap")
        File extract = new File(dir, "x.json")
        extract.withWriter("UTF-8") { w ->
            w << '{"records":['
            (0..cap).each { int i -> w << (i ? "," : "") << '{"id":"R' << i << '"}' }
            w << ']}'
        }
        KeepArtifacts.rowsFile(extract).withWriter("UTF-8") { w -> (0..cap).each { int i -> w << i << "\tA:1\n" } }
        List<String> warnings = []
        Map read = JudgeReplaySupport.sidePresence(extract, [keyFields: ["id"], keyReliable: true], true, "file2", null, warnings)
        assertEquals(cap, read.records.size())
        assertEquals(["1 file2 set-aside records past the first ${cap} were not read back: they read as absent".toString()], warnings)
    }

    /**
     * Task 10 review M3: a capture's Keep artifacts never reach the replay, so a side the replay does not keep never reads them
     * as its own; its side file is copied as the extractor wrote it (a Spark Keep's entries left out, numbers as written).
     */
    @Test
    void aReplayCopiesNoKeepArtifactOfItsCaptureAndOnlyTheExtractorsSideFile() {
        File captured = scratch("m3-capture")
        File extract = new File(captured, "x.json")
        extract.text = '{"records":[{"id":"K1"},{"id":"S1","amount":24.00}]}'
        KeepArtifactsWriter.rows(extract, "1\tA:1")
        KeepArtifactsWriter.summary(extract, [rules: [], configuredExclusions: [], keptCount: 1, setAsideCount: 1,
                extractorSideFile: [total: 3, kept: 1, truncated: true]])
        String side = '{"sourceFileName":"x.json","truncated":true,"total":4,"records":[' +
                '{"id":"X1","amount":12345678.00,"_excludedBy":"BUILT_IN:1"},{"id":"S1","amount":24.00,"_excludedBy":"1:status"}]}'
        new File(captured, "x.excluded.json").text = side
        String base = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), "identity/m3-capture")
        File replayFolder = scratch("m3-replay")

        JudgeReplaySupport.copyExtract(ec, [fileLocation: "${base}/x.json".toString(), fileName: "x.json"], replayFolder, "identity/m3-replay", "FILE_2")
        File copied = new File(replayFolder, "extracts/FILE_2")
        assertEquals(["x.excluded.json", "x.json"], copied.list().sort() as List, "no .keep.json, no .set-aside.rows")
        assertEquals('{"sourceFileName":"x.json","truncated":true,"total":3,"records":[{"id":"X1","amount":12345678.00,"_excludedBy":"BUILT_IN:1"}]}',
                new File(copied, "x.excluded.json").text, "the extractor's part, its own total and truncation, numbers as written")

        // No mark: the whole file is the extractor's, byte for byte. A mark with no extractor part: nothing to copy.
        KeepArtifacts.keepFile(extract).delete()
        File again = scratch("m3-replay-2")
        JudgeReplaySupport.copyExtract(ec, [fileLocation: "${base}/x.json".toString(), fileName: "x.json"], again, "identity/m3-replay-2", "FILE_2")
        assertEquals(side, new File(again, "extracts/FILE_2/x.excluded.json").text)
        KeepArtifactsWriter.summary(extract, [extractorSideFile: [total: 0, kept: 0, truncated: false]])
        File third = scratch("m3-replay-3")
        JudgeReplaySupport.copyExtract(ec, [fileLocation: "${base}/x.json".toString(), fileName: "x.json"], third, "identity/m3-replay-3", "FILE_2")
        assertEquals(["x.json"], new File(third, "extracts/FILE_2").list() as List, "a side file that was Spark's alone is not copied")
    }

    /** Writes Keep artifacts by name, as KeepStepSupport leaves them. */
    private static class KeepArtifactsWriter {
        static void rows(File extract, String text) { KeepArtifacts.rowsFile(extract).text = text + "\n" }
        static void summary(File extract, Map summary) { KeepArtifacts.writeSummary(extract, summary) }
    }

    private File scratch(String name) {
        File dir = new File(dataManagerRoot, "identity/${name}")
        dir.deleteDir()
        dir.mkdirs()
        return dir
    }

    /**
     * Connector rows for one test: each takes Keep (filterParameterName set), writes a side file or not, and names an extract
     * service; KEPT_IN_SPARK no longer declares sourceFilters, so the side keeps in Spark (Ruling 3b2b-23), FILTERED_AT_READ does.
     */
    private <T> T withConnectors(Map<String, String> sideFileBySystem, String extractServiceName = KEPT_IN_SPARK, Closure<T> body) {
        sideFileBySystem.each { String system, String sideFile ->
            insert(SourceSystemConnectorSupport.ENTITY_NAME, [systemEnumId: system, filterParameterName: "sourceFilters",
                    excludedRecordsSidecar: sideFile, extractServiceName: extractServiceName, enabled: "Y"])
        }
        try {
            return body.call()
        } finally {
            sideFileBySystem.keySet().each { String system ->
                ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", system).disableAuthz().deleteAll()
            }
        }
    }

    /** One more source filter on the fixture's scope, for one test. */
    private <T> T withFilter(String fileSide, String field, String operator, String values, Closure<T> body) {
        Map pk = [compareScopeId: "JRT_SCOPE", fileSide: fileSide, sequenceNum: 90]
        insert("darpan.rule.RuleSetCompareSourceFilter", pk + [fieldExpression: field, operator: operator, filterValues: values, companyUserGroupId: A])
        try {
            return body.call()
        } finally {
            ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition(pk).disableAuthz().deleteAll()
        }
    }

    /** Every pair fact the judge was handed while body ran, by compare id; nothing is judged. */
    private Map<String, Map> factsJudged(Closure body) {
        Map<String, Map> facts = new TreeMap<>()
        JudgeSupport.metaClass.static.judge = { def e, String checkId, Iterator<List<Map>> batches, Map statements, List<String> warnings ->
            while (batches.hasNext()) batches.next().each { Map built -> facts.put(built.recordId as String, (Map) built.fact) }
            return []
        }
        try {
            body.call()
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(JudgeSupport)
        }
        return facts
    }

    /**
     * A copy of run A's capture as another execution of check A's automation: its extractor filtered NetSuite at read (metadata
     * counts), with or without the side file a Y connector writes.
     */
    private String seedCapture(String executionId, boolean withSideFile) {
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: executionId, automationId: "JRT_AUTO_A",
                companyUserGroupId: A, statusEnumId: "AUT_STAT_SUCCESS"])
        String folder = "reconciliation-runs/" + executionId
        File run = new File(dataManagerRoot, folder)
        run.deleteDir()
        File side1 = new File(run, "20261101-060000000")
        File side2 = new File(run, "20261101-060000000-FILE_2")
        File lookups = new File(run, "lookups")
        [side1, side2, lookups]*.mkdirs()
        Files.copy(fixture().resolve("shopify-returns.json"), new File(side1, "shopify-returns.json").toPath())
        Files.copy(fixture().resolve("netsuite-rmas.json"), new File(side2, "netsuite-rmas.json").toPath())
        if (withSideFile) Files.copy(fixture().resolve("netsuite-rmas.excluded.json"), new File(side2, "netsuite-rmas.excluded.json").toPath())
        Files.copy(fixture().resolve(CAPTURED_LOOKUP), new File(lookups, CAPTURED_LOOKUP).toPath())
        return folder
    }

    /** The conclusion code of every MISSING_IN_FILE_2 row of a replay's diff.json, by compare id (null: no conclusion). */
    private Map<String, Object> missingInFile2Codes(Map replayOut) {
        Map diff = new JsonSlurper().parse(new File(replayDir(replayOut.replayFolder as String), "diff.json")) as Map
        return new TreeMap<String, Object>(((List<Map>) diff.differences).findAll { it.diffType == "MISSING_IN_FILE_2" }
                .collectEntries { [(it.primaryId): ((Map) it.conclusion)?.code] })
    }

    private static Map presence(Map fact) {
        return [shopify: fact?.shopify_presence, netsuite: fact?.netsuite_presence]
    }

    private static List<List> judgedKeys(List<Map> findings) {
        return findings.collect { [it.statementCode, it.recordKey, it.claimOutcomeId, it.causeOutcomeId] }.sort { it.toString() }
    }

    private static List<List> oldKeys(List<Map> findings) {
        return findings.collect { [it.statementCode, it.recordKey] }.sort { it.toString() }
    }

    /**
     * A capture made after connectors stop filtering: an execution of check A's automation whose NetSuite extract holds every
     * RMA (the fixture's kept ones, then the ones its side file lists, numbers as written) and no side file, with run A's lookup.
     */
    private String seedNewCapture(String executionId) {
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: executionId, automationId: "JRT_AUTO_A",
                companyUserGroupId: A, statusEnumId: "AUT_STAT_SUCCESS"])
        String folder = "reconciliation-runs/" + executionId
        File run = new File(dataManagerRoot, folder)
        run.deleteDir()
        File side1 = new File(run, "20261101-060000000")
        File side2 = new File(run, "20261101-060000000-FILE_2")
        File lookups = new File(run, "lookups")
        [side1, side2, lookups]*.mkdirs()
        Files.copy(fixture().resolve("shopify-returns.json"), new File(side1, "shopify-returns.json").toPath())
        Files.copy(fixture().resolve(CAPTURED_LOOKUP), new File(lookups, CAPTURED_LOOKUP).toPath())
        List<String> records = ["netsuite-rmas.json", "netsuite-rmas.excluded.json"].collectMany { String name ->
            fixture().resolve(name).toFile().readLines("UTF-8").findAll { it.startsWith('{"externalReturnId"') }
                    .collect { it.replaceAll(/,$/, "").replace(',"_excludedBy":"1:status"', "") }
        }
        assertEquals(12, records.size())
        new File(side2, "netsuite-rmas.json").setText('{"metadata":{"systemEnumId":"NETSUITE","recordType":"returnAuthorization"},\n"records":[\n' +
                records.join(",\n") + "\n]}", "UTF-8")
        return folder
    }

    // --------------------------------------------------------------------------------------- tenant safety (P2-9)

    @Test
    void tenantBCannotReplayTenantAsCheckNorReadItsRunFolder() {
        Map rowsBefore = snapshot()
        Set<String> replaysBefore = replayFolders()
        actAs(EDITOR_USERNAME, B)
        Map foreignCheck = replay(checkA, RUN_FOLDER_A, true)
        assertRefused(foreignCheck, "Check ${checkA} does not exist")
        assertNull(foreignCheck.out)
        Map foreignFolder = replay(checkB, RUN_FOLDER_A, true)
        assertRefused(foreignFolder, "Run folder ${RUN_FOLDER_A} does not exist")
        assertNull(foreignFolder.out)
        assertEquals(rowsBefore, snapshot(), "no row written or changed anywhere")
        assertEquals(replaysBefore, replayFolders(), "no replay folder made")
    }

    @Test
    void aRunFolderOutsideTheRunsRootIsRefused() {
        ["../" + RUN_FOLDER_A, "/etc", "reconciliation-runs", "reconciliation-runs/${EXECUTION_A}/20261101-060000000".toString(),
         "elsewhere/${EXECUTION_A}".toString()].each { String folder ->
            assertRefused(replay(checkA, folder, true), "does not exist")
        }
    }

    // --------------------------------------------------------------------------------------- live runs unchanged

    @Test
    void noCallerOutsideTheReplaySetsJudgeWithDecisions() {
        Path component = backendRoot.resolve("runtime/component")
        List<String> allowed = ["darpan/service/reconciliation/ReconciliationCoreServices.xml", "darpan/service/judge/JudgeReplayServices.xml",
                                "darpan/src/main/groovy/darpan/judge/JudgeReplaySupport.groovy"]
        List<String> users = []
        List<Path> roots = (component.toFile().listFiles() ?: []).findAll { it.isDirectory() }
                .collectMany { [it.toPath().resolve("service"), it.toPath().resolve("src/main")] }.findAll { Files.isDirectory(it) }
        assertTrue(roots.size() > 4, "every component is scanned, not only darpan: ${roots}")
        roots.each { Path root ->
            Files.walk(root).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) && (it.toString().endsWith(".xml") || it.toString().endsWith(".groovy") || it.toString().endsWith(".java")) }
                        .each { Path p -> if (p.toFile().getText("UTF-8").contains("judgeWithDecisions")) users.add(component.relativize(p).toString()) }
            }
        }
        assertEquals(allowed.sort(), users.sort())
    }

    /**
     * Ruling P2-27, pinned at RunConclusionStep (N2-M1): a brain scope concluded with its own lookupFetcher (a replay) neither reads
     * BrainAnswerStore (a stored answer is asked again) nor writes it (the fetched answer is not kept).
     */
    @Test
    void aBrainScopeConcludedWithItsOwnLookupFetcherNeitherReadsNorWritesTheSharedAnswers() {
        String SEP = "\u001F"
        String FILE_1 = "FILE_1", FILE_2 = "FILE_2"
        darpan.conclusion.brain.BrainAnswerStore.clear()
        try {
            // Its own run: JRT_RS must keep its one scope (the replay refuses a saved run with two).
            insert("darpan.rule.RuleSet", [ruleSetId: "JRT_BRAIN_RS", ruleSetName: "Brain pin", companyUserGroupId: A])
            insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "JRT_BRAIN_SCOPE", ruleSetId: "JRT_BRAIN_RS", objectType: "ORDER_LINE_UNIT",
                    conclusionBrain: "ORDER_LINE", description: "Brain pin", companyUserGroupId: A,
                    conclusionLookupsJson: groovy.json.JsonOutput.toJson([[name: "orderPayments", subject: "FILE_1", idField: "omsOrderId",
                            systemEnumId: "HOTWAX_OMS", sourceConfigId: "JRT_HW_CFG", keyField: "orderId"]])])
            insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "JRT_BRAIN_SCOPE", sequenceNum: 10, conclusionEnumId: "DKR5T_NO_RMA",
                    appliesToBucket: "MISSING_FROM_FILE_2", companyUserGroupId: A])
            [[FILE_1, "HotWax", "omsOrderId"], [FILE_2, "NetSuite", "orderId"]].each { List side ->
                insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "JRT_BRAIN_SCOPE", fileSide: side[0], systemEnumId: "SHOPIFY",
                        fileTypeEnumId: "DftJson", recordRootExpression: "records", primaryIdExpression: side[2], companyUserGroupId: A])
                insert("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "JRT_BRAIN_SCOPE", fileSide: side[0], sequenceNum: 1,
                        fieldExpression: side[2], companyUserGroupId: A])
                insert("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "JRT_BRAIN_SCOPE", fileSide: side[0], sequenceNum: 2,
                        fieldExpression: side[0] == FILE_1 ? "orderItemSeqId" : "orderLineId", companyUserGroupId: A])
            }
            File doc = File.createTempFile("brain-pin", ".json")
            File extract1 = File.createTempFile("brain-pin-oms", ".json")
            File extract2 = File.createTempFile("brain-pin-ns", ".json")
            [doc, extract1, extract2]*.deleteOnExit()
            extract1.text = groovy.json.JsonOutput.toJson([records: ["JBP1", "JBP2"].collect { [omsOrderId: it, orderItemSeqId: "01"] }])
            extract2.text = groovy.json.JsonOutput.toJson([records: []])
            List rows = ["JBP1", "JBP2"].collect { String id ->
                [diffType: "missing_in_NetSuite", compareScopeId: "JRT_BRAIN_SCOPE", objectType: "ORDER_LINE_UNIT",
                 primaryId: id + SEP + "01", presentIn: "HotWax", missingIn: "NetSuite",
                 data: groovy.json.JsonOutput.toJson([omsOrderId: id, orderItemSeqId: "01", orderGrandTotal: 50]), message: "missing"]
            }
            // The layout ReconciliationServices.writeDiffDatasetOutput writes: the conclude pass reads the rows one per line.
            doc.withWriter("UTF-8") { w ->
                w << "{\n\"metadata\":" + groovy.json.JsonOutput.toJson([file1Label: "HotWax", file2Label: "NetSuite"]) + ",\n"
                w << "\"summary\":" + groovy.json.JsonOutput.toJson([totalDifferences: 2]) + ",\n\"validationErrors\":[],\n\"processingWarnings\":[],\n"
                w << "\"differences\":[" + rows.collect { "\n" + groovy.json.JsonOutput.toJson(it) }.join(",") + "]\n}"
            }
            // JBP2 is already "stored": a reading brain would not ask for it.
            darpan.conclusion.brain.BrainAnswerStore.put(A, "orderPayments", "JBP2", [orderId: "JBP2", stored: true])
            List<List<String>> asked = []
            Closure fetcher = { Object[] a -> asked << ((List<String>) a[2]); ((List<String>) a[2]).collectEntries { [(it): [orderId: it]] } }

            Map result = RunConclusionStep.runIfConfigured([ec: ec, compareScopeId: "JRT_BRAIN_SCOPE", diffFile: doc, lookupFetcher: fetcher,
                    file1Result: [fileLocation: extract1.absolutePath], file2Result: [fileLocation: extract2.absolutePath]])

            assertEquals(2, result.brain?.asked, "the brain ran and asked both orders: ${result}")
            assertEquals([["JBP1", "JBP2"]], asked, "the stored answer is not read, so JBP2 is asked too")
            assertFalse(darpan.conclusion.brain.BrainAnswerStore.has(A, "orderPayments", "JBP1"), "the fetched answer is not written")
            assertEquals(true, darpan.conclusion.brain.BrainAnswerStore.get(A, "orderPayments", "JBP2")?.stored, "the stored answer is left as it was")
        } finally {
            darpan.conclusion.brain.BrainAnswerStore.clear()
        }
    }

    // --------------------------------------------------------------------------------------- fixture

    private void seedOldConfig() {
        insert("moqui.basic.Enumeration", [enumId: "DKR5T_SET_ASIDE", enumTypeId: "DarpanConclusion", enumCode: "DKR5T_SET_ASIDE",
                description: "The RMA is in NetSuite but not refunded yet"])
        insert("moqui.basic.Enumeration", [enumId: "DKR5T_NO_RMA", enumTypeId: "DarpanConclusion", enumCode: "DKR5T_NO_RMA",
                description: "NetSuite has no RMA for the return"])
        insert("moqui.basic.Enumeration", [enumId: "DKR5T_REFUNDED_IN_SHOPIFY", enumTypeId: "DarpanConclusion",
                enumCode: "DKR5T_REFUNDED_IN_SHOPIFY", description: "Refunded in Shopify with no RMA"])
        insert("darpan.rule.RuleSet", [ruleSetId: "JRT_RS", ruleSetName: "Shopify returns vs NetSuite RMAs", companyUserGroupId: A])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "JRT_SCOPE", ruleSetId: "JRT_RS", objectType: "RETURN",
                conclusionLookupsJson: groovy.json.JsonOutput.toJson([[name: "orderInfo", subject: "FILE_1", idField: "orderName",
                        systemEnumId: "SHOPIFY", sourceConfigId: "JRT_SHOP_CFG", keyField: "name",
                        parameters: [keepRecordFields: ["financialStatus"]]]]),
                description: "Shopify returns against NetSuite RMAs", companyUserGroupId: A])
        insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "JRT_SCOPE", fileSide: "FILE_1", systemEnumId: "SHOPIFY",
                fileTypeEnumId: "DftJson", recordRootExpression: "records", primaryIdExpression: "returnId", companyUserGroupId: A])
        insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "JRT_SCOPE", fileSide: "FILE_2", systemEnumId: "NETSUITE",
                fileTypeEnumId: "DftJson", recordRootExpression: "records", primaryIdExpression: "externalReturnId", companyUserGroupId: A])
        // Applied by the NetSuite extractor; the RMAs it dropped are in the captured sidecar.
        insert("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "JRT_SCOPE", fileSide: "FILE_2", sequenceNum: 1,
                fieldExpression: "status", operator: "INCLUDE_IN", filterValues: REFUNDED, companyUserGroupId: A])
        // The conclusions read what the filter set aside: the filter is really a promise (spec §8).
        insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "JRT_SCOPE", sequenceNum: 10, conclusionEnumId: "DKR5T_SET_ASIDE",
                appliesToBucket: "MISSING_FROM_FILE_2", companyUserGroupId: A])
        insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "JRT_SCOPE", sequenceNum: 10, conditionSeq: 1, subject: "FILE_2",
                presence: "EXCLUDED", checkLabel: "NetSuite has the RMA, set aside by the run", companyUserGroupId: A])
        // Reads the conclusion lookup: a return Shopify refunded that NetSuite has no RMA for.
        insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "JRT_SCOPE", sequenceNum: 15, conclusionEnumId: "DKR5T_REFUNDED_IN_SHOPIFY",
                appliesToBucket: "MISSING_FROM_FILE_2", companyUserGroupId: A])
        insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "JRT_SCOPE", sequenceNum: 15, conditionSeq: 1, subject: "FILE_2",
                presence: "ABSENT", checkLabel: "NetSuite has no RMA", companyUserGroupId: A])
        insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "JRT_SCOPE", sequenceNum: 15, conditionSeq: 2, subject: "FILE_1",
                fieldExpression: "orderInfo.financialStatus", operator: "IN", conditionValues: "refunded",
                checkLabel: "Shopify refunded the order", companyUserGroupId: A])
        // A field rule, off unless a test turns it on (the byte-identity test runs the scope with and without one).
        insert("darpan.rule.Rule", [ruleId: "JRT_AMOUNT_RULE", ruleSetId: "JRT_RS", sequenceNum: 10, enabled: "N", ruleType: "DRL",
                severity: "WARN", companyUserGroupId: A, ruleText: "Refund amount equals the RMA amount",
                ruleLogic: 'rule "JRT_AMOUNT_RULE" salience 100 when $m : Map( this["file1"] != null, this["file2"] != null ) eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("refundAmount"), ((Map) $m.get("file2")).get("amount"))) then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "refundAmount", ((Map) $m.get("file1")).get("refundAmount"), ((Map) $m.get("file2")).get("amount"), "WARN", "Refund amount differs"); end'])
        insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "JRT_SCOPE", sequenceNum: 20, conclusionEnumId: "DKR5T_NO_RMA",
                appliesToBucket: "MISSING_FROM_FILE_2", companyUserGroupId: A])
        insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "JRT_SCOPE", sequenceNum: 20, conditionSeq: 1, subject: "FILE_2",
                presence: "ABSENT", checkLabel: "NetSuite has no RMA", companyUserGroupId: A])

        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "JRT_AUTO_A", automationName: "DK R5 returns",
                companyUserGroupId: A, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "JRT_RS"])
        [EXECUTION_A, EXECUTION_A2].each { String executionId ->
            insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: executionId, automationId: "JRT_AUTO_A",
                    companyUserGroupId: A, statusEnumId: "AUT_STAT_SUCCESS"])
        }
        checkA = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkA, companyUserGroupId: A, checkCode: "dk-r5-returns", label: "Returns vs RMAs",
                savedRunId: "JRT_RS", automationId: "JRT_AUTO_A", file1RecordType: "return", file2RecordType: "returnAuthorization"])
        insert("darpan.audit.Audit", [auditId: "JRT_AUDIT", companyUserGroupId: A, auditCode: "dk", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.AuditStatement", [statementId: "JRT_R5", companyUserGroupId: A, auditId: "JRT_AUDIT", statementCode: "R5",
                categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", statementText: "Every Shopify return has an RMA in NetSuite", sequenceNum: 1])
        insert("darpan.audit.AuditStatement", [statementId: "JRT_R5R", companyUserGroupId: A, auditId: "JRT_AUDIT", statementCode: "R5R",
                categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", statementText: "Every RMA is refunded", sequenceNum: 2])
        insert("darpan.audit.AuditStatementCheck", [checkId: checkA, statementId: "JRT_R5", companyUserGroupId: A, sequenceNum: 1,
                diffTypes: "MISSING_IN_FILE_2"])
        insert("darpan.judge.CheckAsk", [checkId: checkA, askName: "orderInfo", companyUserGroupId: A, subjectSide: "FILE_1",
                idField: "orderName", systemEnumId: "SHOPIFY", sourceConfigId: "JRT_SHOP_CFG", keyField: "name",
                keepRecordFields: "financialStatus"])
    }

    private void seedNewConfig() {
        mustHold = createDecision("N", ["NO_RMA", "NOT_REFUNDED"])
        ruleIds.noRma = storeRule(mustHold, "no RMA", 20, [line("01", "netsuite_presence", "ENTCO_NOT_EQUALS", "KEPT")], "NO_RMA")
        ruleIds.notRefunded = storeRule(mustHold, "RMA not refunded", 10, [line("01", "netsuite_presence", "ENTCO_EQUALS", "KEPT"),
                line("02", "netsuite_status", "ENTCO_NOT_EQUALS", REFUNDED)], "NOT_REFUNDED")
        meaning(mustHold, "NO_RMA", "JOK_FINDING", "The return has no RMA")
        meaning(mustHold, "NOT_REFUNDED", "JOK_FINDING", "The RMA is not refunded")

        explains = createDecision("Y", ["WAITING_FOR_ITEM", "REFUNDED_IN_SHOPIFY", "UNEXPLAINED"])
        ruleIds.refundedInShopify = storeRule(explains, "refunded in Shopify", 30, [line("01", "claim", "ENTCO_EQUALS", "NO_RMA"),
                line("02", "ask_orderInfo_financialStatus", "ENTCO_EQUALS", "refunded")], "REFUNDED_IN_SHOPIFY")
        meaning(explains, "REFUNDED_IN_SHOPIFY", "JOK_FINDING", "Refunded in Shopify with no RMA")
        ruleIds.waiting = storeRule(explains, "waiting for the item", 20, [line("01", "claim", "ENTCO_EQUALS", "NOT_REFUNDED"),
                line("02", "netsuite_status", "ENTCO_EQUALS", PENDING_RECEIPT)], "WAITING_FOR_ITEM")
        meaning(explains, "WAITING_FOR_ITEM", "JOK_EXPECTED", "Waiting for the item to come back")
        meaning(explains, "UNEXPLAINED", "JOK_FINDING", "Unexplained")

        insert("darpan.judge.CheckDecision", [checkId: checkA, stageEnumId: "CDS_MUST_HOLD", companyUserGroupId: A, ruleSetId: mustHold])
        insert("darpan.judge.CheckDecision", [checkId: checkA, stageEnumId: "CDS_EXPLAINS", companyUserGroupId: A, ruleSetId: explains])
        updateRow("darpan.audit.AuditStatement", [statementId: "JRT_R5"], [claimRuleSetId: mustHold, claimOutcomeId: "NO_RMA"])
        updateRow("darpan.audit.AuditStatement", [statementId: "JRT_R5R"], [claimRuleSetId: mustHold, claimOutcomeId: "NOT_REFUNDED"])
    }

    /** The captured run: an automation execution's folder, side 1 in <stamp>/, side 2 and its sidecar in <stamp>-FILE_2/. */
    private void seedRunFolder() {
        // The test data manager outlives a run: start from empty run folders and no replays.
        new File(dataManagerRoot, "judge-replays").deleteDir()
        new File(dataManagerRoot, "identity").deleteDir()
        new File(dataManagerRoot, "identity-out").deleteDir()
        [RUN_FOLDER_A, RUN_FOLDER_A2].each { String folder ->
            File run = new File(dataManagerRoot, folder)
            run.deleteDir()
            File side1 = new File(run, "20261101-060000000")
            File side2 = new File(run, "20261101-060000000-FILE_2")
            side1.mkdirs()
            side2.mkdirs()
            Files.copy(fixture().resolve("shopify-returns.json"), new File(side1, "shopify-returns.json").toPath())
            Files.copy(fixture().resolve("netsuite-rmas.json"), new File(side2, "netsuite-rmas.json").toPath())
            Files.copy(fixture().resolve("netsuite-rmas.excluded.json"), new File(side2, "netsuite-rmas.excluded.json").toPath())
        }
        // Only run A captured its lookup.
        File lookups = new File(dataManagerRoot, "${RUN_FOLDER_A}/lookups")
        lookups.mkdirs()
        Files.copy(fixture().resolve(CAPTURED_LOOKUP), new File(lookups, CAPTURED_LOOKUP).toPath())
    }

    private Path fixture() {
        return backendRoot.resolve("runtime/component/darpan/src/test/resources/judge/dk-r5")
    }

    /** The fixture's extracts (and sidecar) in a scratch folder of the data manager, for calling the compare directly. */
    private File copyExtractsFor(String name) {
        File dir = new File(dataManagerRoot, "identity/${name}")
        dir.deleteDir()
        new File(dir, "FILE_1").mkdirs()
        new File(dir, "FILE_2").mkdirs()
        Files.copy(fixture().resolve("shopify-returns.json"), new File(dir, "FILE_1/shopify-returns.json").toPath())
        Files.copy(fixture().resolve("netsuite-rmas.json"), new File(dir, "FILE_2/netsuite-rmas.json").toPath())
        Files.copy(fixture().resolve("netsuite-rmas.excluded.json"), new File(dir, "FILE_2/netsuite-rmas.excluded.json").toPath())
        return dir
    }

    /** reconcile#RuleSetCompareScope's parameters as a live caller passes them; flag null leaves judgeWithDecisions out. */
    private Map compareParams(File copy, Boolean flag) {
        String base = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec),
                dataManagerRoot.toPath().relativize(copy.toPath()).toString())
        Map params = [ruleSetId: "JRT_RS", compareScopeId: "JRT_SCOPE", file1Location: "${base}/FILE_1/shopify-returns.json".toString(),
                      file2Location: "${base}/FILE_2/netsuite-rmas.json".toString(), file1Name: "shopify-returns.json",
                      file2Name: "netsuite-rmas.json", sparkAppName: "JudgeReplayTests"]
        if (flag != null) params.judgeWithDecisions = flag
        if (flag == true) params.judgeCheckId = checkA
        return params
    }

    private Map runFolderSnapshot() {
        Map<String, String> out = new TreeMap<>()
        [RUN_FOLDER_A, RUN_FOLDER_A2].each { String folder ->
            File run = new File(dataManagerRoot, folder)
            run.eachFileRecurse { File f ->
                out.put(folder + "/" + run.toPath().relativize(f.toPath()).toString(),
                        f.isFile() ? MessageDigest.getInstance("SHA-256").digest(f.bytes).encodeHex().toString() : "dir")
            }
        }
        return out
    }

    private Set<String> replayFolders() {
        File root = new File(dataManagerRoot, "judge-replays")
        Set<String> out = new TreeSet<>()
        if (root.isDirectory()) root.eachFileRecurse { File f -> out.add(root.toPath().relativize(f.toPath()).toString()) }
        return out
    }

    private File replayDir(String replayFolder) {
        return new File(dataManagerRoot, replayFolder)
    }

    private Map replayed() {
        if (cachedReplay == null) {
            Map out = replay(checkA, RUN_FOLDER_A, true)
            assertEquals([], out.errors)
            cachedReplay = out.out as Map
        }
        return cachedReplay
    }

    private Map compared() {
        Map out = replayed()
        return ok(underFacadeGrant("judge.JudgeReplayServices.compare#JudgedFindings",
                [oldFindings: out.oldFindings, judgedFindings: out.judgedFindings]))
    }

    private Map replay(String checkId, String runFolder, boolean judge) {
        return underFacadeGrant("judge.JudgeReplayServices.replay#CheckJudgement",
                [checkId: checkId, runFolder: runFolder, judgeWithDecisions: judge])
    }

    private String createDecision(String firstMatchOnly, List<String> outcomeIds) {
        Map created = ok(underFacadeGrant("create#rules.RuleSet", [description: "DK R5 decision", firstMatchOnly: firstMatchOnly,
                factTypes: [[factTypeId: factTypeId]], outcomes: outcomeIds.collect { [outcomeId: it] }]))
        assertNotNull(created.ruleSetId)
        return created.ruleSetId as String
    }

    private String storeRule(String ruleSetId, String ruleName, int priority, List<Map> lines, String outcomeId) {
        Map stored = ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: lines, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]))
        assertNotNull(stored.ruleId)
        return stored.ruleId as String
    }

    private Map line(String seq, String fieldName, String operatorEnumId, String compareValue) {
        return [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: fieldName,
                operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    private void meaning(String ruleSetId, String outcomeId, String kind, String label) {
        ok(underFacadeGrant("judge.JudgeConfigServices.store#DecisionOutcome", [ruleSetId: ruleSetId, outcomeId: outcomeId,
                outcomeKindEnumId: kind, label: label]))
    }

    private static Map ok(Map result) {
        assertEquals([], result.errors, "call failed")
        return result.out as Map
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        return underFacadeGrantDo { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
    }

    /** Runs inside a facade service's frame, authz ON, for the logged-in user, as a production facade call does. */
    private Map underFacadeGrantDo(Closure<Map> call) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect(call)
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: errors ? null : out, errors: errors]
    }

    private static void assertRefused(Map result, String... expected) {
        assertTrue(result.errors.any { String error -> expected.every { error?.contains(it.toString()) } },
                "expected a refusal containing ${expected}, got errors ${result.errors}")
    }

    private Map<String, List<Map>> snapshot() {
        return WATCHED.collectEntries { String entityName ->
            [(entityName): ec.entity.find(entityName).disableAuthz().useCache(false).list()
                    .collect { new TreeMap(it.getMap()) }.sort { it.toString() }]
        } as Map<String, List<Map>>
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
    }

    private void updateRow(String entityName, Map pk, Map fields) {
        def row = ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
        row.setAll(fields)
        row.update()
    }
}
