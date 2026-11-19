package darpan.judge

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.automation.SourceSystemConnectorSupport
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
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
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
     */
    @Test
    void aReplayCallsNoSystemWhetherItsLookupsWereCapturedOrNot() {
        int connectorCalls = 0
        Closure count = { Object[] args -> connectorCalls++; return null }
        SourceSystemConnectorSupport.metaClass.static.resolve = { def e, String id -> count() }
        SourceSystemConnectorSupport.metaClass.static.resolveBySystemEnumId = { def e, String id -> count() }
        SourceSystemConnectorSupport.metaClass.static.resolveByExtractServiceName = { def e, String name -> count() }
        SourceSystemConnectorSupport.metaClass.static.resolveByExpectedSourceConfigType = { def e, String type -> count() }
        Closure liveFetcher = AskSupport.fetcher
        try {
            // The stub is live: today's lookup reaches it.
            RunConclusionStep.fetchLookup(ec, [name: "probe", systemEnumId: "NETSUITE"], ["SR-1"], A, [])
            assertEquals(1, connectorCalls, "test precondition: the counting stub intercepts connector resolution")
            connectorCalls = 0

            Map captured = replay(checkA, RUN_FOLDER_A, true)
            assertEquals([], captured.errors)
            assertEquals(0, connectorCalls, "captured: no extract or lookup connector was resolved")
            assertEquals([], captured.out.replayWarnings)
            assertFalse(captured.out.judgeWarnings.any { it.contains("not captured") }, "${captured.out.judgeWarnings}")
            assertEquals("REFUNDED_IN_SHOPIFY", captured.out.judgedFindings.find { it.recordKey == "SR-1007" }.causeOutcomeId,
                    "the captured ask's field reached the pair fact")

            Map bare = replay(checkA, RUN_FOLDER_A2, true)
            assertEquals([], bare.errors)
            assertEquals(0, connectorCalls, "not captured: still no connector")
            assertEquals(["Lookup orderInfo not captured in run ${RUN_FOLDER_A2}; not replayed".toString()], bare.out.replayWarnings)
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
        Map spark = JudgeReplaySupport.flat((Map) slurper.parseText('{"a":24.0,"b":24,"c":1.2345678E7,"d":"24.00","e":"000123"}'))
        Map raw = JudgeReplaySupport.flat((Map) slurper.parseText('{"a":24.00,"b":24,"c":12345678.00,"d":"24.00","e":"000123"}'))
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
