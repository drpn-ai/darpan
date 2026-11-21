package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b, Task 13. The interactive run keeps in Spark, and nothing a run reports changes.
 *
 * Before: the read is sent the compare scope's filters and the extractor drops what they reject, writing its side file where
 * its connector says Y (ReconciliationSavedRunSupport.resolveExtractExcludeFilters, as run#SavedRunDiff sent them until now).
 * After: run#SavedRunDiff sends a SPARK side no filters; Spark Keep sets the same records aside at ingest. The diff, the
 * compare's result, the conclusions, the side files and every side's record count are compared, before against after.
 *
 * Every system is made up (KL_*), served by KeepLiveTestExtractionServices.xml, and the extractors drop records themselves
 * when sent filters, as today's do:
 * <ul>
 * <li>KL_SPARK (side file Y) and KL_QUIET (side file N): coded connectors whose extract no longer takes the filter
 *     parameter, so Keep runs in Spark;</li>
 * <li>KL_FILTERING: a coded connector whose extract still takes it, as every real one does until Tasks 17–23, so Keep stays at
 *     the read (Ruling 3b2b-23);</li>
 * <li>KL_DEFINED: data-defined, so Keep stays at the read (Ruling 3b2b-16);</li>
 * <li>an upload: no Keep at all.</li>
 * </ul>
 * The extractors still make their own drops (a record marked _builtInDrop), which Tasks 17–23 move into Spark later.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepLiveParityTests {

    private static final String A = "KREWE"
    private static final String SERVICE = "reconciliation.KeepLiveTestExtractionServices.extract#KeepLiveRecords"
    private static final String SELECTING = "reconciliation.KeepLiveTestExtractionServices.extract#KeepLiveSelectingRecords"
    private static final String FILTERING = "reconciliation.KeepLiveTestExtractionServices.extract#KeepLiveFilteringRecords"
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T00:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T00:00:00Z"))

    /** One of each conclusion the presence of the other side gives: any change in presence changes the conclusion. */
    private static final List<Map> PRESENCE_RULES = ["MISSING_FROM_FILE_1": "FILE_1", "MISSING_FROM_FILE_2": "FILE_2"].collectMany { String bucket, String other ->
        ["KEPT", "EXCLUDED", "ABSENT"].collect { String presence ->
            [conclusionEnumId: "${bucket}_${presence}".toString(), label: "${other} ${presence}".toString(), appliesToBucket: bucket,
             conditions: [[subject: other, presence: presence, keyScope: "FULL", fieldExpression: null, operator: null, conditionValues: [],
                           checkLabel: "${other} presence".toString()]]] as Map
        }
    } as List<Map>

    private ExecutionContext ec
    private File dataManagerRoot

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "keep-live-parity")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        [DataManagerSupport.RECONCILIATION_RUNS_PATH, "keep-live"].each { new File(dataManagerRoot, it).deleteDir() }
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            ${connector("KL_SPARK", SERVICE, "Y")}
            ${connector("KL_QUIET", SERVICE, "N")}
            ${connector("KL_DEFINED", FILTERING, "Y", 'dataSourceDefinitionId="KL_DEF"')}
            ${connector("KL_FILTERING", FILTERING, "Y")}
            ${connector("KL_SELECT", SELECTING, "Y", 'keepFieldsParameterName="keepFields" keepFieldsBase="orderId"')}
            ${savedRun("KL_RS", ["FILE_1": api("KL_SPARK", "kl-1"), "FILE_2": api("KL_QUIET", "kl-2")])}
            ${filter("KL_RS", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST,B2B")}
            ${filter("KL_RS", "FILE_1", 2, "orderName", "INCLUDE_PREFIX", "#SYN")}
            ${filter("KL_RS", "FILE_1", 3, "total", "EXCLUDE_IN", "24.00")}
            ${filter("KL_RS", "FILE_2", 1, "status", "INCLUDE_IN", "PAID,REFUNDED")}
            ${savedRun("KL_RS_REAL", ["FILE_1": api("KL_FILTERING", "kl-1"), "FILE_2": api("KL_QUIET", "kl-2")])}
            ${filter("KL_RS_REAL", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST,B2B")}
            ${filter("KL_RS_REAL", "FILE_1", 2, "orderName", "INCLUDE_PREFIX", "#SYN")}
            ${filter("KL_RS_REAL", "FILE_1", 3, "total", "EXCLUDE_IN", "24.00")}
            ${filter("KL_RS_REAL", "FILE_2", 1, "status", "INCLUDE_IN", "PAID,REFUNDED")}
            ${savedRun("KL_RS_UP", ["FILE_1": api("KL_SPARK", "kl-up"), "FILE_2": 'systemEnumId="KL_SPARK" fileTypeEnumId="DftJson"'])}
            ${filter("KL_RS_UP", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${filter("KL_RS_UP", "FILE_2", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${savedRun("KL_RS_KEYS", ["FILE_1": api("KL_QUIET", "kl-keys-1") + ' extractParametersJson="{&quot;readByOtherSideKeys&quot;:true}"',
                                      "FILE_2": api("KL_SPARK", "kl-keys-2")])}
            ${filter("KL_RS_KEYS", "FILE_2", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${savedRun("KL_RS_DEF", ["FILE_1": api("KL_DEFINED", "kl-def"), "FILE_2": api("KL_SELECT", "kl-select")])}
            ${filter("KL_RS_DEF", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${filter("KL_RS_DEF", "FILE_2", 1, "channel", "EXCLUDE_IN", "TEST")}
            <darpan.rule.RuleSet ruleSetId="KL_RS_NEST" ruleSetName="KL_RS_NEST" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="KL_RS_NEST" ruleSetId="KL_RS_NEST" objectType="ORDER" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KL_RS_NEST" fileSide="FILE_1" ${api("KL_SPARK", "kl-nest-1")}
                recordRootExpression="records" primaryIdExpression="\$.records[*].lines[*].lineId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KL_RS_NEST" fileSide="FILE_2" ${api("KL_SPARK", "kl-nest-2")}
                recordRootExpression="orders" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            ${filter("KL_RS_NEST", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${filter("KL_RS_NEST", "FILE_2", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${savedRun("KL_RS_NONE", ["FILE_1": api("KL_SPARK", "kl-none-1"), "FILE_2": api("KL_QUIET", "kl-none-2")])}
            ${filter("KL_RS_NONE", "FILE_1", 1, "channel", "EXCLUDE_IN", "TEST")}
            ${filter("KL_RS_NONE", "FILE_2", 1, "status", "INCLUDE_IN", "PAID")}
        </entity-facade-xml>""").dummyFks(true).load()

        KeepLiveTestExtracts.RECORDS.clear()
        KeepLiveTestExtracts.RECORDS.putAll([
                // FILE_1 of KL_RS: every rule shape, a numeric text, a case and spacing difference, and the extractor's own drop
                "kl-1"     : [order("O1", "WEB", "#SYN1", 10.00G), order("O2", "TEST", "#SYN2", 11.00G),
                              order("O3", "WEB", "#XYZ3", 12.00G), order("O4", "WEB", null, 13.00G),
                              order("O5", "WEB", "#SYN5", 24.00G), order("O6", "WEB", "#SYN6", 24.0d),
                              order("O7", "POS", "#SYN7", 30.00G) + [_builtInDrop: true], order("O8", " test ", "#SYN8", 9.5G),
                              order("O9", "B2B", "#SYN9", 7), order("O10", "POS", "#SYN10", 1.0E7d), order("O12", "WEB", "#SYN12", 5)],
                // FILE_2 of KL_RS: an INCLUDE_IN with a value it rejects and a record missing the field (FIELD_ABSENT)
                "kl-2"     : [[orderId: "O1", status: "PAID"], [orderId: "O2", status: "PAID"], [orderId: "O3", status: "paid"],
                              [orderId: "O5", status: "REFUNDED"], [orderId: "O6", status: "VOIDED"], [orderId: "O9", status: "PAID"],
                              [orderId: "O10", status: "PAID"], [orderId: "O11", status: "PAID"], [orderId: "O12"]],
                "kl-up"    : [order("U1", "WEB", "#SYN1", 1), order("U2", "TEST", "#SYN2", 2)],
                "kl-keys-1": [[orderId: "K1"], [orderId: "K2"], [orderId: "K3"]],
                "kl-keys-2": [order("K1", "WEB", "#SYN1", 1), order("K2", "TEST", "#SYN2", 2), order("K3", " Test", "#SYN3", 3)],
                "kl-def"   : [order("D1", "WEB", "#SYN1", 1), order("D2", "TEST", "#SYN2", 2)],
                "kl-select": [order("D1", "WEB", "#SYN1", 1), order("D2", "TEST", "#SYN2", 2)],
                "kl-none-1": [order("N1", "TEST", "#SYN1", 1)],
                "kl-none-2": [[orderId: "N1", status: "VOIDED"]],
        ])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        KeepLiveTestExtracts.CALLS.clear()
        ec.message.clearAll()
    }

    // ----------------------------------------------------------------------------------------------------- the tests

    /**
     * The whole run, before and after: same diff, same result, same conclusions, same side files (the set-aside records with
     * the extract's own projection, and the extractor's own drop kept beside Spark's), same counts. Covers EXCLUDE_IN,
     * INCLUDE_IN, INCLUDE_PREFIX, a numeric field and its text (24.00 against 24.0, 1.0E7), a value in another case or with
     * spaces, a missing field, a side file Y side and a side file N side.
     */
    @Test
    void aSparkSideKeepsWhatItsFilteredReadKeptAndReportsTheSame() {
        Map run = runSaved("KL_RS")
        Map sent1 = call("kl-1"), sent2 = call("kl-2")
        assertNull(sent1.sourceFilters, "a Spark side's read is sent no filters")
        assertNull(sent2.sourceFilters)
        assertEquals(["channel", "orderName", "total"] as Set, sent1.keepRuleFields as Set, "the read is asked for the fields Keep reads")
        assertEquals(["status"], sent2.keepRuleFields)

        File after1 = extractOf(sent1), after2 = extractOf(sent2)
        assertEquals(10, json(after1).records.size(), "the read kept every record but the extractor's own drop")
        Map keep1 = KeepArtifacts.readSummary(after1), keep2 = KeepArtifacts.readSummary(after2)
        assertTrue(keep1 != null && keep2 != null, "Spark Keep ran on both sides at the run's ingest")

        // before: the same reads sent today's filters
        Map src1 = source("KL_RS", "FILE_1"), src2 = source("KL_RS", "FILE_2")
        File before1 = beforeRead("kl-1", "KL_SPARK", src1, after1.name)
        File before2 = beforeRead("kl-2", "KL_QUIET", src2, after2.name)
        List kept1 = json(before1).records, kept2 = json(before2).records
        assertEquals(["O1", "O6", "O10", "O12"], kept1*.orderId, "the fixture is not hollow: each rule drops something")
        assertEquals(["O1", "O2", "O3", "O5", "O9", "O10", "O11"], kept2*.orderId)

        // the counts each side reports
        assertEquals(kept1.size() as long, keep1.keptCount as long)
        assertEquals(kept2.size() as long, keep2.keptCount as long)
        assertEquals(json(before1).metadata.filters.configuredExclusions, keep1.configuredExclusions, "the exclusions read back as the extractor reported them")
        assertEquals(json(before2).metadata.filters.configuredExclusions, keep2.configuredExclusions)

        // the compare, before (no Keep) and after (each side's plan as the run built it)
        Map keepBySide = [FILE_1: RunExtractSupport.sideKeep(ec, src1, "FILE_1", connectorOf("KL_SPARK")),
                          FILE_2: RunExtractSupport.sideKeep(ec, src2, "FILE_2", connectorOf("KL_QUIET"))]
        assertEquals([KeepPlanSupport.SPARK, KeepPlanSupport.SPARK], keepBySide.values()*.placement)
        Map labels = RunConclusionStep.documentLabels(runDiffFile(run))
        assertTrue(labels.file1Label && labels.file2Label, "${labels}")
        Map before = compareAndWrite("KL_RS", before1, before2, null, labels, "before")
        Map after = compareAndWrite("KL_RS", after1, after2, keepBySide, labels, "after")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(((byte[]) before[name]).length > 0, name)
            assertArrayEquals((byte[]) before[name], (byte[]) after[name], "${name} differs before and after Keep moved into Spark")
        }
        assertEquals(["file1", "file2"] as Set, (after.setAsideBySide as Map).keySet())
        assertEquals(["O2", "O3", "O4", "O5", "O8", "O9"] as Set, (after.setAsideBySide as Map).file1)

        // what the run itself wrote and counted is the before compare's
        Map runDiff = json(runDiffFile(run))
        assertEquals(JsonOutput.toJson(json(before.diffFile as File).differences), JsonOutput.toJson(runDiff.differences))
        assertEquals(before.out.differenceCount as long, run.differenceCount as long)
        assertEquals(before.out.missingInFile2Count as long, run.onlyInFile1Count as long)
        assertEquals(before.out.missingInFile1Count as long, run.onlyInFile2Count as long)
        assertTrue((run.differenceCount as long) > 0)

        // the conclusions each finding reaches, from each side's presence (KEPT, EXCLUDED, ABSENT or unknown)
        List beforeConclusions = conclusions(before.diffFile as File, before1, before2)
        List afterConclusions = conclusions(runDiffFile(run), after1, after2)
        assertEquals(beforeConclusions, afterConclusions)
        assertTrue(beforeConclusions.contains(["O2", "MISSING_FROM_FILE_1_EXCLUDED"]), "a set-aside record on a side file side: ${beforeConclusions}")
        assertTrue(beforeConclusions.contains(["O6", "UNEXPLAINED"]), "a side with no side file that set something aside is unknown")
        assertTrue(beforeConclusions.contains(["O11", "MISSING_FROM_FILE_1_ABSENT"]), "a key no record holds is not found")

        // the side files: the same records, with the extract's projection and the same _excludedBy, the extractor's drop kept
        File side1Before = sideFile(before1), side1After = sideFile(after1)
        assertTrue(side1Before.isFile() && side1After.isFile())
        assertEquals(sideEntries(side1Before) as Set, sideEntries(side1After) as Set)
        assertTrue(sideEntries(side1After).contains([orderId: "O7", channel: "POS", orderName: "#SYN7", total: 30.00G, _excludedBy: "0:built-in drop"]),
                "the extractor's own side file entry is kept, not replaced")
        assertEquals(json(side1Before).total, json(side1After).total)
        assertFalse(sideFile(before2).exists(), "a side file N side writes none, before")
        assertFalse(sideFile(after2).exists(), "nor after")
    }

    /**
     * Ruling 3b2b-23: a side whose extract still takes the connector's filter parameter (every real extract until its system's
     * task) keeps today's read: it is sent the filters, Spark runs nothing over it and writes no .keep.json, and the run reports
     * what it reported before. Its other side, an extract that no longer filters, keeps in Spark in the same run.
     */
    @Test
    void aSideWhoseExtractStillTakesFiltersKeepsTodaysRead() {
        Map run = runSaved("KL_RS_REAL")
        Map sent1 = call("kl-1"), sent2 = call("kl-2")
        assertEquals([[1, "channel"], [2, "orderName"], [3, "total"]],
                ((List<Map>) sent1.sourceFilters).collect { [it.sequenceNum as Integer, it.fieldExpression] }, "the read gets today's filters")
        assertNull(sent1.keepRuleFields)
        assertNull(sent1.readFields, "no Keep field joins its projection")
        File after1 = extractOf(sent1), after2 = extractOf(sent2)
        assertEquals(["O1", "O6", "O10", "O12"], json(after1).records*.orderId, "the read dropped what the filters reject")
        assertFalse(KeepArtifacts.keepFile(after1).exists(), "no Spark Keep artifact beside it")
        assertNotNull(KeepArtifacts.readSummary(after2), "the side whose extract no longer filters keeps in Spark")

        // today: both reads sent the filters
        File before1 = beforeRead("kl-1", "KL_FILTERING", source("KL_RS_REAL", "FILE_1"), "real-" + after1.name)
        File before2 = beforeRead("kl-2", "KL_QUIET", source("KL_RS_REAL", "FILE_2"), "real-" + after2.name)
        assertEquals(json(before1).records, json(after1).records)
        assertEquals(json(sideFile(before1)).records as Set, json(sideFile(after1)).records as Set, "the read's own side file, as today")
        Map labels = RunConclusionStep.documentLabels(runDiffFile(run))
        Map before = compareAndWrite("KL_RS_REAL", before1, before2, null, labels, "real-before")
        assertEquals(JsonOutput.toJson(json(before.diffFile as File).differences), JsonOutput.toJson(json(runDiffFile(run)).differences))
        assertEquals(before.out.differenceCount as long, run.differenceCount as long)
        assertEquals(before.out.missingInFile2Count as long, run.onlyInFile1Count as long)
        assertEquals(before.out.missingInFile1Count as long, run.onlyInFile2Count as long)
        assertEquals(conclusions(before.diffFile as File, before1, before2), conclusions(runDiffFile(run), after1, after2))
        assertTrue(conclusions(runDiffFile(run), after1, after2).contains(["O6", "UNEXPLAINED"]), "the N side stays unknown")
    }

    /** Ruling 3b2b-16: a data-defined side keeps filtering at the read, and Spark runs nothing for it. */
    @Test
    void aDataDefinedSideStillGetsItsFiltersAtRead() {
        Map run = runSaved("KL_RS_DEF")
        Map sent = call("kl-def")
        assertEquals([[1, "channel", "EXCLUDE_IN", "TEST"]], ((List<Map>) sent.sourceFilters).collect {
            [it.sequenceNum as Integer, it.fieldExpression, it.operator, it.filterValues] }, "the read gets today's filters")
        assertNull(sent.keepRuleFields)
        File extract = extractOf(sent)
        assertEquals(["D1"], json(extract).records*.orderId, "the read dropped what they reject")
        assertNull(KeepArtifacts.readSummary(extract), "Spark Keep ran nothing for it")
        assertTrue(sideFile(extract).isFile(), "the read wrote its own side file")
        assertEquals(["D2"], json(sideFile(extract)).records*.orderId)
        assertEquals(0L, run.differenceCount as long, "D2 is set aside on both sides: by the read on side 1, by Spark on side 2")
        assertNotNull(KeepArtifacts.readSummary(extractOf(call("kl-select"))), "the other side, a coded connector, keeps in Spark")
    }

    /** Review Focus 1: a side read by the other side's keys is asked only for the keys that side keeps. */
    @Test
    void theOtherSideIsReadOnlyByKeptKeys() {
        Map run = runSaved("KL_RS_KEYS")
        assertEquals(["kl-keys-2", "kl-keys-1"], KeepLiveTestExtracts.CALLS*.projConfigId, "side 2 is read first")
        Map byKeys = call("kl-keys-1")
        assertEquals(["K1"], byKeys.lookupKeys, "K2 and K3 are set aside on side 2 (TEST, ' Test'), so they are not asked for")
        assertEquals("orderId", byKeys.lookupKeyField)
        assertEquals(0L, run.differenceCount as long)
    }

    /** Ruling 3b2b-4: the fields Keep reads join the side's keepFields and readFields; keepRuleFields only where it is declared. */
    @Test
    void keepFieldsAndReadFieldsCarryTheKeepFields() {
        Map selecting = sentBy("KL_RS_DEF", "FILE_2", "KL_SELECT", true)
        assertNull(selecting.sourceFilters)
        assertTrue((selecting.keepFields as List).containsAll(["orderId", "channel"]), "${selecting.keepFields}")
        assertEquals(["channel"], selecting.readFields)
        assertFalse(selecting.containsKey(KeepPlanSupport.KEEP_FIELDS_PARAMETER), "an extract that does not declare keepRuleFields is not sent it")

        Map spark = sentBy("KL_RS", "FILE_1", "KL_SPARK", true)
        assertEquals(["channel", "orderName", "total"] as Set, spark.keepRuleFields as Set)
        assertFalse(spark.containsKey("readFields"), "an extract that does not declare readFields is not sent it")
        assertFalse(spark.containsKey("keepFields"))
    }

    /** An evaluate run's compare takes no Keep, so its read keeps today's filters and Spark runs nothing for it. */
    @Test
    void aRunWhoseCompareTakesNoKeepFiltersAtTheRead() {
        Map evaluate = sentBy("KL_RS", "FILE_1", "KL_SPARK", false)
        assertEquals(["channel", "orderName", "total"], (evaluate.sourceFilters as List<Map>)*.fieldExpression)
        assertFalse(evaluate.containsKey(KeepPlanSupport.KEEP_FIELDS_PARAMETER))
        assertEquals(KeepPlanSupport.READ, evaluate.plan.placement)
    }

    /**
     * Review M5/M6: Spark Keep reads the extract's top-level records array. A side keyed through a nested array, or whose records
     * sit under another array, keeps today's filters at the read rather than being set aside wrongly or failing.
     */
    @Test
    void aSideNotKeyedOnTopLevelRecordsKeepsItsFiltersAtTheRead() {
        ["FILE_1", "FILE_2"].each { String side ->
            Map sent = sentBy("KL_RS_NEST", side, "KL_SPARK", true)
            assertEquals(["channel"], (sent.sourceFilters as List<Map>)*.fieldExpression, side)
            assertEquals(KeepPlanSupport.READ, sent.plan.placement, side)
            assertFalse(sent.containsKey(KeepPlanSupport.KEEP_FIELDS_PARAMETER), side)
        }
    }

    /** An upload never gets Keep, even when its system's connector would place Keep in Spark. */
    @Test
    void anUploadSideGetsNoKeep() {
        String upload = JsonOutput.toJson([records: [[orderId: "U1", channel: "WEB"], [orderId: "U2", channel: "TEST"]]])
        Map run = runSaved("KL_RS_UP", [file2Name: "upload.json", file2Text: upload])
        File uploaded = DataManagerSupport.resolveDataManagerFile(ec, run.file2DataManagerPath, false)
        assertTrue(uploaded?.isFile(), "${run.file2DataManagerPath}")
        assertNull(KeepArtifacts.readSummary(uploaded), "no Keep beside an upload")
        assertNull(call("kl-up").sourceFilters)
        assertEquals(1L, run.onlyInFile2Count as long, "U2: set aside on the API side, kept in the upload, as before")
        assertEquals(1L, run.differenceCount as long)
    }

    /** A run that keeps nothing on either side has no data, as when the reads dropped everything: its count is what it kept. */
    @Test
    void aRunThatKeepsNothingHasNoDataAsBefore() {
        Map run = runSaved("KL_RS_NONE")
        assertEquals(1, json(extractOf(call("kl-none-1"))).records.size(), "the read kept the record; Spark sets it aside")
        def row = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", run.reconciliationRunResultId).useCache(false).one()
        assertEquals(RunObservability.STATUS_NO_DATA, row.statusEnumId)
        assertEquals(0L, run.differenceCount as long)
    }

    /** DAR-BE-163: a retry hands back the side the failed run already read, and that side still gets its Keep. */
    @Test
    void aRetriedRunKeepsTheSideItHandsBack() {
        String path = "${DataManagerSupport.RECONCILIATION_RUNS_PATH}/KL_RS_UP/20261121-000000001/kl-up-landed.json".toString()
        File landed = DataManagerSupport.resolveDataManagerFile(ec, path, true)
        landed.setText(JsonOutput.toJson([metadata: [:], records: [[orderId: "U1", channel: "WEB"], [orderId: "U2", channel: "TEST"]]]), "UTF-8")
        Timestamp started = new Timestamp(System.currentTimeMillis() + 1000L)
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunResult", [
                reconciliationRunResultId: "KL_FAILED", savedRunId: "KL_RS_UP", savedRunType: "ruleset", ruleSetId: "KL_RS_UP",
                compareScopeId: "KL_RS_UP", companyUserGroupId: A, statusEnumId: RunObservability.STATUS_FAILED, startedDate: started,
                createdDate: started, windowStartDate: START, windowEndDate: END, windowTimeZone: "UTC", file1Name: landed.name,
                file1DataManagerPath: path])
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunStep", [
                reconciliationRunStepId: "KL_FAILED_E1", reconciliationRunResultId: "KL_FAILED", companyUserGroupId: A,
                stageCode: RunObservability.STAGE_EXTRACT_FILE1, stageSequence: 2, statusEnumId: RunObservability.STATUS_SUCCESS,
                startedDate: started, completedDate: started, recordCount: 2])
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.ReconciliationRunStep", [
                reconciliationRunStepId: "KL_FAILED_E2", reconciliationRunResultId: "KL_FAILED", companyUserGroupId: A,
                stageCode: RunObservability.STAGE_EXTRACT_FILE2, stageSequence: 3, statusEnumId: RunObservability.STATUS_FAILED,
                startedDate: started, completedDate: started, errorMessage: "made up"])

        Map run = runSaved("KL_RS_UP", [retryFromRunResultId: "KL_FAILED", file2Name: "upload.json",
                                        file2Text: JsonOutput.toJson([records: [[orderId: "U1", channel: "WEB"]]])])
        assertEquals([], KeepLiveTestExtracts.CALLS, "the landed side is not read again")
        assertEquals(path, run.file1DataManagerPath, "side 1 is the file the failed run read")
        assertEquals([1L, 1L], [KeepArtifacts.readSummary(landed)?.keptCount as Long, KeepArtifacts.readSummary(landed)?.setAsideCount as Long],
                "Spark Keep set U2 aside on the handed-back side")
        assertEquals(0L, run.differenceCount as long, "U2 is set aside, so the upload's lack of it is no difference")
    }

    // ------------------------------------------------------------------------------------------------------- the runs

    private Map runSaved(String savedRunId, Map extra = [:]) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([savedRunId: savedRunId, windowStartDate: START, windowEndDate: END] + extra).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map result = (Map) out.runResult
        def row = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", result.reconciliationRunResultId).useCache(false).one()
        return result + [file1DataManagerPath: row.file1DataManagerPath, file2DataManagerPath: row.file2DataManagerPath,
                         resultDataManagerPath: row.resultDataManagerPath]
    }

    /** One API side through RunExtractSupport.extractApiSource with a stub caller: what the read would be sent. */
    private Map sentBy(String scopeRs, String fileSide, String systemEnumId, boolean keepInSpark) {
        Map sent = null
        Map source = source(scopeRs, fileSide)
        String location = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), "keep-live/sent")
        Map artifact = [location: location, runToken: "sent"]
        // the plan as run#SavedRunDiff builds it for the side, and hands the read and the compare
        Map plan = RunExtractSupport.sideKeep(ec, source, fileSide, RunExtractSupport.resolveExtractConnector(ec, source), keepInSpark)
        RunExtractSupport.extractApiSource(ec, source, fileSide, fileSide, artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, { String name, Map params ->
            sent = new LinkedHashMap(params)
            return [fileLocation: DataManagerSupport.childLocation(location, "unused.json"), recordCount: 0]
        }, null, plan)
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(systemEnumId, sent.systemEnumId)
        return sent + [plan: plan]
    }

    /** The read as run#SavedRunDiff sent it until now: the source's filters, resolved the way it resolved them. */
    private File beforeRead(String configId, String systemEnumId, Map source, String fileName) {
        String location = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), "keep-live/before")
        Map out = KeepLiveTestExtracts.extract(ec, [projConfigId: configId, systemEnumId: systemEnumId, outputLocation: location,
                fileName: fileName, sourceFilters: ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)])
        return new File(DataManagerSupport.resolveDirectoryFile(ec, location, false), out.fileName as String)
    }

    private Map compareAndWrite(String ruleSetId, File file1, File file2, Map keepBySide, Map labels, String tag) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope").parameters([
                ruleSetId: ruleSetId, compareScopeId: ruleSetId, file1Location: file1.absolutePath, file2Location: file2.absolutePath,
                file1Name: file1.name, file2Name: file2.name, file1Label: labels.file1Label, file2Label: labels.file2Label,
                sparkAppName: "KeepLiveParityTests"] + (keepBySide ? [keepBySide: keepBySide] : [:])).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        try {
            File dir = new File(dataManagerRoot, "keep-live/out-${tag}")
            dir.deleteDir()
            Map written = ReconciliationServices.writeDiffDatasetOutput(ec, (Dataset) out.diffDf, dir.absolutePath, "diff.json", "diff.json",
                    [timestamp: "fixed", ruleSetId: out.ruleSetId, compareScopeId: out.compareScopeId, file1Label: labels.file1Label,
                     file2Label: labels.file2Label],
                    [totalDifferences: out.differenceCount], (List) out.validationErrors, (List) out.processingWarnings)
            // the compare's outputs, its frames and the two new Keep outputs aside (asserted on their own)
            new File(dir, "result.json").setText(JsonOutput.toJson(new TreeMap(out.findAll { k, v ->
                !(v instanceof Dataset) && !(k in ["persistedSources", "setAsideBySide", "keepSummaryBySide"]) })), "UTF-8")
            File diffFile = new File(written.diffLocation as String)
            return ["diff.json": diffFile.bytes, "result.json": new File(dir, "result.json").bytes, diffFile: diffFile, out: out,
                    setAsideBySide: out.setAsideBySide]
        } finally {
            ReconciliationServices.unpersistDatasets((List) (out?.persistedSources ?: []))
        }
    }

    /** [key, conclusion] per finding, from each side's presence: the conclude pass's own evidence (KeepArtifacts, side files). */
    private List conclusions(File diffFile, File file1, File file2) {
        Map labels = RunConclusionStep.documentLabels(diffFile)
        List out = []
        RunConclusionSupport.concludeEach([diffFile: diffFile, file1Label: labels.file1Label, file2Label: labels.file2Label,
                singleSided: false, rules: PRESENCE_RULES,
                file1: [extractFile: file1, keyFields: ["orderId"], idNormalizer: null, keyReliable: true, evidence: [:]],
                file2: [extractFile: file2, keyFields: ["orderId"], idNormalizer: null, keyReliable: true, evidence: [:]]]) { Map row, Map c ->
            out << [row.primaryId as String, c.code as String]
        }
        return out.sort { it[0] }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private Map call(String configId) {
        List<Map> calls = KeepLiveTestExtracts.CALLS.findAll { it.projConfigId == configId }
        assertEquals(1, calls.size(), "one read of ${configId}: ${KeepLiveTestExtracts.CALLS*.projConfigId}")
        return calls[0]
    }

    private File extractOf(Map sent) {
        return new File(DataManagerSupport.resolveDirectoryFile(ec, sent.outputLocation as String, false), sent.fileName as String)
    }

    private File runDiffFile(Map run) {
        return DataManagerSupport.resolveDataManagerFile(ec, run.resultDataManagerPath, false)
    }

    private static File sideFile(File extract) {
        return new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
    }

    private static List<Map> sideEntries(File side) {
        return (List<Map>) json(side).records
    }

    private Map source(String ruleSetId, String fileSide) {
        return ec.entity.find("darpan.rule.RuleSetCompareSource").condition([compareScopeId: ruleSetId, fileSide: fileSide])
                .useCache(false).one().getMap()
    }

    private Map connectorOf(String systemEnumId) {
        return SourceSystemConnectorSupport.resolve(ec, systemEnumId)
    }

    private static Map order(String id, String channel, String name, Object total) {
        Map out = [orderId: id, channel: channel] as Map
        if (name != null) out.orderName = name
        out.total = total
        return out
    }

    private static String connector(String id, String service, String sidecar, String extra = "") {
        return """<darpan.reconciliation.SourceSystemConnector systemEnumId="${id}" extractServiceName="${service}"
                expectedSourceConfigType="KL_TYPE" configParameterName="projConfigId" dateFromParameterName="windowStart"
                dateToParameterName="windowEnd" preserveWindowInstants="Y" filterParameterName="sourceFilters"
                excludedRecordsSidecar="${sidecar}" enabled="Y" ${extra}/>
            <moqui.basic.Enumeration enumId="${id}" enumTypeId="DarpanSystemSource" description="${id} (made up)"/>"""
    }

    private static String api(String systemEnumId, String configId) {
        return """systemEnumId="${systemEnumId}" sourceTypeEnumId="AUT_SRC_API" sourceConfigId="${configId}" sourceConfigType="KL_TYPE" fileTypeEnumId="DftJson\""""
    }

    private static String savedRun(String id, Map<String, String> sides) {
        return """<darpan.rule.RuleSet ruleSetId="${id}" ruleSetName="${id}" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="${id}" ruleSetId="${id}" objectType="ORDER" companyUserGroupId="${A}" description="${id}"/>
            """ + sides.collect { String side, String attrs ->
            """<darpan.rule.RuleSetCompareSource compareScopeId="${id}" fileSide="${side}" ${attrs}
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>"""
        }.join("\n")
    }

    private static String filter(String id, String side, int seq, String field, String operator, String values) {
        return """<darpan.rule.RuleSetCompareSourceFilter compareScopeId="${id}" fileSide="${side}" sequenceNum="${seq}"
                fieldExpression="\$.records[*].${field}" operator="${operator}" filterValues="${values}" companyUserGroupId="${A}"/>"""
    }

    private static Map json(File f) {
        return (Map) new JsonSlurper().parse(f, "UTF-8")
    }
}
