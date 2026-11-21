package darpan.reconciliation.source

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.core.JsonRecordLines
import darpan.reconciliation.core.ReconciliationServices
import groovy.json.JsonOutput
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceFacade

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 10). Keep at ingest: Spark splits a side's records into kept and set aside, and the set-aside
 * artifact is written beside the extract. Made-up system (ACME), hand-built rules in the KeepRules shape. No Moqui: the
 * ExecutionContext is a stub that resolves locations as plain paths.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepStepSupportTests {

    private SparkSession spark = SparkSession.builder()
            .appName("keep-step-support")
            .master("local[1]")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.ansi.enabled", "true")
            .getOrCreate()

    private final ExecutionContext ec = [getResource: { ->
        [getLocationReference: { String location -> null }] as ResourceFacade
    }] as ExecutionContext

    @TempDir
    File dir

    @AfterAll
    void stopSpark() {
        spark?.stop()
    }

    // --- fixtures ---

    private static final List<Map> ID_SPECS = [[idExpr: '$.records[*].id', idNormalizer: null]]

    /** Six records: two kept, one set aside by each tenant reason and one by the built-in. */
    private static List<Map> sixRecords() {
        return [[id: "1", status: "open", channel: "web", lineType: "A", amount: 10],
                [id: "2", status: "Cancelled", channel: "web", amount: 20],
                [id: "3", status: "open", amount: 30],
                [id: "4", status: "open", channel: "pos", amount: 40],
                [id: "5", status: "open", channel: "web", lineType: "X", amount: 50],
                [id: " 6 ", status: "open", channel: "WEB ", amount: 60]].collect { it + [region: "zz-1"] }
    }

    private static Map cmp(String field, String op, List<String> values, Map over = [:]) {
        return [kind: "compare", field: field, text: "GROOVY", op: op, values: values, valueKind: "TEXT", fold: "A_UPPER"] + over
    }

    /** Built-in first, then the tenant's rules as SourceFilterSupport.toKeepRules states them (operator and values carried). */
    private static List<Map> rules() {
        return [[id: "BUILT_IN:1", sequenceNum: 1, label: "Acme payment lines", source: "BUILT_IN",
                 tests: [[test: cmp("lineType", "EQ", ["X"], [text: "FACT", fold: "NONE"]), reason: "BUILT_IN"]]],
                [id: "A:1", sequenceNum: 1, label: "status", source: "TENANT", operator: "EXCLUDE_IN", values: ["cancelled"],
                 tests: [[test: cmp("status", "IN", ["cancelled"]), reason: "VALUE"]]],
                [id: "A:2", sequenceNum: 2, label: "channel", source: "TENANT", operator: "INCLUDE_IN", values: ["web"],
                 tests: [[test: cmp("channel", "IS_NULL", null), reason: "FIELD_ABSENT"],
                         [test: cmp("channel", "NOT_IN", ["web"]), reason: "VALUE"]]],
                [id: "A:3", sequenceNum: 3, label: "region", source: "TENANT", operator: "INCLUDE_PREFIX", values: ["zz"],
                 tests: [[test: cmp("region", "IS_NULL", null), reason: "FIELD_ABSENT"],
                         [test: [kind: "not", of: cmp("region", "STARTS_WITH_ANY", ["zz"])], reason: "VALUE"]]]]
    }

    private static Map sideKeep(List<Map> rules, boolean writeSidecar = false) {
        return [placement: KeepPlanSupport.SPARK, readFilters: [], rules: rules, writeSidecar: writeSidecar,
                fields: KeepRules.fields(rules)]
    }

    private File extract(List records, String name = "acme-orders.json", File folder = dir) {
        folder.mkdirs()
        File file = new File(folder, name)
        file.setText(JsonOutput.toJson([metadata: [system: "ACME"], records: records]), "UTF-8")
        return file
    }

    private Map ingest(File file, Map keep, List<Map> idSpecs = ID_SPECS) {
        return ReconciliationServices.ingestFile(ec, spark, file.absolutePath, "JSON", idSpecs, true, "File 1 (ACME)", [], null, keep)
    }

    private static List<String> ids(Object df) {
        return ((Dataset) df).collectAsList().collect { Row row -> row.getAs("compare_id") as String }.sort()
    }

    private static List<String> rowLines(File file) {
        File rows = KeepArtifacts.rowsFile(file)
        return rows.isFile() ? rows.readLines("UTF-8").findAll { it } : null
    }

    private static File sidecarOf(File file) {
        return new File(file.parentFile, ExcludedRecordsSidecar.fileNameFor(file.name))
    }

    // --- tests ---

    @Test
    void keptAndAsideSplitAndTheSummaryCounts() {
        File file = extract(sixRecords())
        Map out = ingest(file, sideKeep(rules()))

        assertEquals(["1", "6"], ids(out.idDf))
        assertEquals(2L, ((Dataset) out.dataDf).count())
        assertEquals(["2", "3", "4", "5"], ids(out.asideIdDf))
        assertEquals(["1\tA:1", "2\tA:2", "3\tA:2", "4\tBUILT_IN:1"], rowLines(file))

        Map summary = KeepArtifacts.readSummary(file)
        assertEquals(KeepArtifacts.keepFile(file).getText("UTF-8"), JsonOutput.toJson(out.keepSummary))
        assertEquals("acme-orders.json", summary.sourceFileName)
        assertEquals([[id: "BUILT_IN:1", sequenceNum: 1, label: "Acme payment lines", source: "BUILT_IN"],
                      [id: "A:1", sequenceNum: 1, label: "status", source: "TENANT"],
                      [id: "A:2", sequenceNum: 2, label: "channel", source: "TENANT"],
                      [id: "A:3", sequenceNum: 3, label: "region", source: "TENANT"]], summary.rules)
        // every tenant rule, zero counts included, in SourceFilterSupport.applyToRecords' shape; built-ins never appear there
        assertEquals([[sequenceNum: 1, fieldExpression: "status", operator: "EXCLUDE_IN", values: ["cancelled"], excludedCount: 1, fieldAbsentCount: 0],
                      [sequenceNum: 2, fieldExpression: "channel", operator: "INCLUDE_IN", values: ["web"], excludedCount: 1, fieldAbsentCount: 1],
                      [sequenceNum: 3, fieldExpression: "region", operator: "INCLUDE_PREFIX", values: ["zz"], excludedCount: 0, fieldAbsentCount: 0]],
                summary.configuredExclusions)
        assertEquals([[sequenceNum: 1, label: "Acme payment lines", setAsideCount: 1]], summary.builtIn)
        assertEquals(2, summary.keptCount)
        assertEquals(4, summary.setAsideCount)
        assertEquals(1, ((List) out.persisted).size())
    }

    @Test
    void configuredExclusionsAreWhatApplyToRecordsReportsForTheSameFilters() {
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "cancelled, void"],
                [sequenceNum: 2, fieldExpression: "channel", operator: "INCLUDE_IN", filterValues: "web"],
                [sequenceNum: 3, fieldExpression: "region", operator: "INCLUDE_PREFIX", filterValues: "zz"]])
        File file = extract(sixRecords())
        ingest(file, sideKeep(SourceFilterSupport.toKeepRules(parsed)))

        Map today = SourceFilterSupport.applyToRecords(sixRecords(), parsed)
        ExcludedRecordsSidecar.discard((Map) today.excludedCollector)
        assertEquals(JsonOutput.toJson(today.configuredExclusions), JsonOutput.toJson(KeepArtifacts.readSummary(file).configuredExclusions))
    }

    @Test
    void aSideWithNoTenantRulesStillWritesConfiguredExclusionsAsAnEmptyList() {
        File file = extract(sixRecords())
        ingest(file, sideKeep([rules()[0]]))

        Map summary = KeepArtifacts.readSummary(file)
        assertTrue(summary.containsKey("configuredExclusions"))
        assertEquals([], summary.configuredExclusions)
        assertEquals([], KeepArtifacts.configuredExclusions(file))
        assertEquals(1, summary.setAsideCount)
    }

    @Test
    void nothingSetAsideWritesNoRowsAndNoSideFile() {
        File file = extract([[id: "1", status: "open", channel: "web", region: "zz"], [id: "2", status: "open", channel: "Web", region: "ZZ9"]])
        Map out = ingest(file, sideKeep(rules(), true))

        assertEquals(["1", "2"], ids(out.idDf))
        assertEquals([], ids(out.asideIdDf))
        assertNull(rowLines(file))
        assertFalse(sidecarOf(file).exists())
        Map summary = KeepArtifacts.readSummary(file)
        assertEquals(2, summary.keptCount)
        assertEquals(0, summary.setAsideCount)
        assertEquals([0, 0, 0], summary.configuredExclusions*.excludedCount)
        assertEquals([0, 0, 0], summary.configuredExclusions*.fieldAbsentCount)
    }

    @Test
    void overFiftyThousandSetAsideTruncatesTheSideFile() {
        int aside = ExcludedRecordsSidecar.MAX_ENTRIES + 1
        List<Map> records = [[id: "k0", status: "open"]]
        for (int i = 0; i < aside; i++) records.add([id: "c${i}".toString(), status: "cancelled"])
        records.add([id: "k1", status: "open"])
        File file = extract(records)
        List<Map> tenantOnly = [rules()[1]]

        Map out = ingest(file, sideKeep(tenantOnly, true))

        assertEquals(["k0", "k1"], ids(out.idDf))
        assertEquals(aside, rowLines(file).size())
        Map sidecar = new groovy.json.JsonSlurper().parse(sidecarOf(file), "UTF-8") as Map
        assertEquals(true, sidecar.truncated)
        assertEquals(aside, sidecar.total)
        assertEquals(ExcludedRecordsSidecar.MAX_ENTRIES, ((List) sidecar.records).size())
        assertEquals("1:status", ((List<Map>) sidecar.records)[0]._excludedBy)
        assertEquals("c0", ((List<Map>) sidecar.records)[0].id)
        assertEquals(aside, KeepArtifacts.readSummary(file).setAsideCount)
    }

    @Test
    void theSideFileIsWrittenOnlyWhenTheConnectorSaysY() {
        File without = extract(sixRecords(), "acme-orders.json", new File(dir, "n"))
        ingest(without, sideKeep(rules(), false))
        // the summary and rows are always written; the side file is today's per-connector behaviour
        assertFalse(sidecarOf(without).exists())
        assertTrue(KeepArtifacts.keepFile(without).isFile())
        assertEquals(4, rowLines(without).size())

        File with = extract(sixRecords(), "acme-orders.json", new File(dir, "y"))
        ingest(with, sideKeep(rules(), true))
        Map sidecar = ExcludedRecordsSidecar.read(with)
        assertEquals(true, sidecar.present)
        assertEquals(false, sidecar.truncated)
        assertEquals(["2", "3", "4", "5"], ((List<Map>) sidecar.records)*.id)
        assertEquals(["1:status", "2:channel", "2:channel", "1:Acme payment lines"], ((List<Map>) sidecar.records)*._excludedBy)
    }

    private static final String UNKEYED = "9:Unkeyed payment lines"

    /** A side file as an extractor that still drops records writes it: records the extract does not hold. */
    private static File extractorSideFile(File file, boolean truncated = false, Integer total = null) {
        File side = sidecarOf(file)
        side.setText('{"sourceFileName":"acme-orders.json","truncated":' + truncated + ',"total":' + (total ?: 2) + ',"records":[' +
                '{"id":"x1","amount":24.00,"_excludedBy":"' + UNKEYED + '"},{"id":"x2","amount":1.0E+7,"_excludedBy":"' + UNKEYED + '"}]}', "UTF-8")
        return side
    }

    @Test
    void anExtractorSideFileKeepsItsRecordsAndGainsSparksSetAsides() {
        File file = extract(sixRecords())
        File side = extractorSideFile(file)

        ingest(file, sideKeep(rules(), true))

        Map sidecar = new groovy.json.JsonSlurper().parse(side, "UTF-8") as Map
        assertEquals(["x1", "x2", "2", "3", "4", "5"], ((List<Map>) sidecar.records)*.id)
        assertEquals([UNKEYED, UNKEYED, "1:status", "2:channel", "2:channel", "1:Acme payment lines"], ((List<Map>) sidecar.records)*._excludedBy)
        assertEquals(6, sidecar.total)
        assertEquals(false, sidecar.truncated)
        // the extractor's records are copied as written, numbers included
        assertTrue(side.getText("UTF-8").contains('"amount":24.00') && side.getText("UTF-8").contains('"amount":1.0E+7'), side.getText("UTF-8"))
        assertEquals([total: 2, kept: 2, truncated: false], KeepArtifacts.readSummary(file).get(KeepStepSupport.EXTRACTOR_SIDE_FILE))
    }

    @Test
    void reRunningKeepOverItsOwnOutputDoublesNothing() {
        File file = extract(sixRecords())
        File side = extractorSideFile(file)
        ingest(file, sideKeep(rules(), true))
        String once = side.getText("UTF-8")

        // keptValues then ingest (one run), then a replay: each pass takes back only the extractor's part
        KeepStepSupport.keptValues(ec, file, sideKeep(rules(), true), "id")
        ingest(file, sideKeep(rules(), true))
        assertEquals(once, side.getText("UTF-8"))

        // a pass that now sets nothing aside puts the extractor's file back as it was
        List<Map> never = [[id: "A:1", sequenceNum: 1, label: "status", source: "TENANT", operator: "EXCLUDE_IN", values: ["nope"],
                            tests: [[test: cmp("status", "IN", ["nope"]), reason: "VALUE"]]]]
        ingest(file, sideKeep(never, true))
        Map sidecar = new groovy.json.JsonSlurper().parse(side, "UTF-8") as Map
        assertEquals(["x1", "x2"], ((List<Map>) sidecar.records)*.id)
        assertEquals(2, sidecar.total)
        assertFalse(KeepArtifacts.readSummary(file).containsKey(KeepStepSupport.EXTRACTOR_SIDE_FILE))
        assertTrue(side.getText("UTF-8").contains('"amount":24.00'))
    }

    @Test
    void aSideFileOnlyKeepWroteIsRewrittenNotDoubled() {
        File file = extract(sixRecords())
        ingest(file, sideKeep(rules(), true))
        String once = sidecarOf(file).getText("UTF-8")
        ingest(file, sideKeep(rules(), true))

        assertEquals(once, sidecarOf(file).getText("UTF-8"))
        assertEquals(4, (ExcludedRecordsSidecar.read(file).records as List).size())
        assertEquals([total: 0, kept: 0, truncated: false], KeepArtifacts.readSummary(file).get(KeepStepSupport.EXTRACTOR_SIDE_FILE))

        List<Map> never = [[id: "A:1", sequenceNum: 1, label: "status", source: "TENANT", operator: "EXCLUDE_IN", values: ["nope"],
                            tests: [[test: cmp("status", "IN", ["nope"]), reason: "VALUE"]]]]
        ingest(file, sideKeep(never, true))
        assertFalse(sidecarOf(file).exists(), "nothing set aside now and no extractor part: no side file")
    }

    @Test
    void anExtractorFilesTruncationAndTotalCarryOver() {
        File file = extract(sixRecords())
        File side = extractorSideFile(file, true, 60000)

        ingest(file, sideKeep(rules(), true))

        Map sidecar = new groovy.json.JsonSlurper().parse(side, "UTF-8") as Map
        assertEquals(60004, sidecar.total)
        assertEquals(true, sidecar.truncated)
        assertEquals(6, ((List) sidecar.records).size())
        assertEquals([total: 60000, kept: 2, truncated: true], KeepArtifacts.readSummary(file).get(KeepStepSupport.EXTRACTOR_SIDE_FILE))
    }

    @Test
    void setAsideRecordsCarryEveryConclusionFieldOfTheSide() {
        // Aditi: set-aside records carry the projection kept ones do (key, check fields, conclusion fields, Keep fields).
        List<String> conclusionFields = ["orderName", "returnRef", "shipDate"]
        List<Map> records = sixRecords().collect { Map r -> r + [orderName: "#${r.id}".toString(), returnRef: "R-${r.id}".toString(), shipDate: "2026-11-0${r.amount.intdiv(10)}".toString()] }
        File file = extract(records)
        ingest(file, sideKeep(rules(), true))

        List<Map> aside = []
        KeepArtifacts.forEachSetAsideRecord(file) { Map record, String ruleId -> aside.add(record) }
        List<Map> sidecar = (List<Map>) ExcludedRecordsSidecar.read(file).records
        assertEquals(4, aside.size())
        assertEquals(4, sidecar.size())
        aside.each { Map record -> assertTrue(record.keySet().containsAll(conclusionFields), "set aside ${record.id} lacks a conclusion field") }
        sidecar.each { Map record -> assertTrue(record.keySet().containsAll(conclusionFields), "side file ${record.id} lacks a conclusion field") }
        // and exactly the extract's record: nothing Keep added, nothing dropped
        Map byId = records.collectEntries { [(it.id): it] }
        aside.each { Map record -> assertEquals(byId[record.id], record) }
        sidecar.each { Map record -> assertEquals(byId[record.id].keySet() + ["_excludedBy"] as Set, record.keySet()) }
    }

    @Test
    void injectedColumnsNeverReachTheDataFrame() {
        File file = extract(sixRecords())
        Map out = ingest(file, sideKeep(rules()))

        assertEquals(["compare_id"], ((Dataset) out.idDf).columns() as List)
        assertEquals(["compare_id"], ((Dataset) out.asideIdDf).columns() as List)
        StructType data = (StructType) ((Dataset) out.dataDf).schema().apply("data").dataType()
        assertEquals(["amount", "channel", "id", "lineType", "region", "status"], data.fieldNames() as List)

        File plain = extract(sixRecords(), "acme-orders.json", new File(dir, "plain"))
        Map today = ingest(plain, null)
        assertEquals(((Dataset) today.dataDf).schema(), ((Dataset) out.dataDf).schema())

        // apply's own frames drop the injected, row and reason columns too
        Map applied = KeepStepSupport.apply(linesFrame(file, rules()), sideKeep(rules()), file)
        [applied.kept, applied.aside].each { Object df ->
            assertTrue(((Dataset) df).columns().every { String c -> !c.startsWith("_darpan") }, "${((Dataset) df).columns()}")
        }
        ((List<Dataset>) applied.persisted).each { it.unpersist() }
    }

    @Test
    void setAsideRowsAreWrittenInRowOrderAfterAShuffle() {
        List<Map> records = (0..<200).collect { int i -> [id: "r${i}".toString(), status: i % 3 == 0 ? "cancelled" : "open"] }
        File file = extract(records)
        List<Map> tenantOnly = [rules()[1]]
        Dataset shuffled = linesFrame(file, tenantOnly).repartition(4)

        Map applied = KeepStepSupport.apply(shuffled, sideKeep(tenantOnly), file)

        assertEquals((0..<200).findAll { it % 3 == 0 }.collect { "${it}\tA:1".toString() }, rowLines(file))
        ((List<Dataset>) applied.persisted).each { it.unpersist() }
    }

    @Test
    void rulesThatNameNoFieldStillNumberTheirRows() {
        // a decision whose lines were all judged on the driver: a constant, no Keep text column
        List<Map> constant = [[id: "B:R1", sequenceNum: 1, label: "Everything", source: "DECISION",
                               tests: [[test: [kind: "const", holds: true], reason: "OUT_1"]]]]
        File file = extract([[id: "1"], [id: "2"], [id: "3"]])
        Map out = ingest(file, sideKeep(constant))

        assertEquals([], ids(out.idDf))
        assertEquals(["1", "2", "3"], ids(out.asideIdDf))
        assertEquals(["0\tB:R1", "1\tB:R1", "2\tB:R1"], rowLines(file))
        assertEquals([], KeepArtifacts.readSummary(file).configuredExclusions)
    }

    @Test
    void noKeepIsTodaysIngest() {
        File file = extract(sixRecords())
        Map today = ReconciliationServices.ingestFile(ec, spark, file.absolutePath, "JSON", ID_SPECS, true, "File 1 (ACME)", [], null)
        List<Map> notSpark = [null,
                              [placement: KeepPlanSupport.NONE, readFilters: [], rules: [], writeSidecar: false, fields: [] as Set],
                              [placement: KeepPlanSupport.READ, readFilters: [[sequenceNum: 1]], rules: [], writeSidecar: true, fields: ["status"] as Set],
                              sideKeep([], true)]
        notSpark.each { Map keep ->
            Map out = ingest(file, keep)
            assertEquals(["dataDf", "idDf"], (out.keySet() as List).sort(), "${keep?.placement}")
            assertEquals(ids(today.idDf), ids(out.idDf))
            assertEquals(((Dataset) today.dataDf).schema(), ((Dataset) out.dataDf).schema())
            assertEquals(((Dataset) today.dataDf).toJSON().collectAsList().sort(), ((Dataset) out.dataDf).toJSON().collectAsList().sort())
        }
        assertEquals(["1", "2", "3", "4", "5", "6"], ids(today.idDf))
        assertFalse(KeepArtifacts.keepFile(file).exists())
        assertFalse(KeepArtifacts.rowsFile(file).exists())
        assertFalse(sidecarOf(file).exists())
    }

    @Test
    void keepWithoutTheRecordsArrayFailsNamingTheSide() {
        File file = extract(sixRecords())
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException) {
            ingest(file, sideKeep(rules()), [[idExpr: '$.orders[*].id', idNormalizer: null]])
        }
        assertTrue(thrown.message.contains("File 1 (ACME)"), thrown.message)
        assertFalse(KeepArtifacts.keepFile(file).exists())
    }

    @Test
    void anExtractWithNoRecordsWritesAZeroSummary() {
        File file = extract([])
        Map out = ingest(file, sideKeep(rules(), true))

        assertEquals([], ids(out.idDf))
        assertEquals([], ids(out.asideIdDf))
        Map summary = KeepArtifacts.readSummary(file)
        assertEquals(0, summary.keptCount)
        assertEquals(0, summary.setAsideCount)
        assertEquals([1, 2, 3], summary.configuredExclusions*.sequenceNum)
        assertNull(rowLines(file))
        assertFalse(sidecarOf(file).exists())
    }

    @Test
    void aSetAsideRecordWithABlankKeyFieldDoesNotFailTheCompositeKeyCheck() {
        // it never reached the compare before Keep moved into Spark; only a kept record's blank key field fails the run
        List<Map> composite = [[idExpr: '$.records[*].id', idNormalizer: null], [idExpr: '$.records[*].sku', idNormalizer: null]]
        File file = extract([[id: "1", sku: "A", status: "open"], [id: "2", status: "cancelled"], [id: "3", sku: "C", status: "cancelled"]])
        Map out = ingest(file, sideKeep([rules()[1]]), composite)

        assertEquals(["1\u001FA"], ids(out.idDf))
        assertEquals(["3\u001FC"], ids(out.asideIdDf))
        assertEquals(["1\tA:1", "2\tA:1"], rowLines(file))
    }

    @Test
    void aFailedIngestReleasesItsPersistedFrame() {
        List<Map> composite = [[idExpr: '$.records[*].id', idNormalizer: null], [idExpr: '$.records[*].sku', idNormalizer: null]]
        File file = extract([[id: "1", sku: "A", status: "open"], [id: "2", status: "open"]])
        int before = spark.sparkContext().getPersistentRDDs().size()

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException) { ingest(file, sideKeep([rules()[1]]), composite) }

        assertTrue(thrown.message.contains("must all be present"), thrown.message)
        assertEquals(before, spark.sparkContext().getPersistentRDDs().size())
    }

    @Test
    void keptValuesSkipsSetAsideRecords() {
        File file = extract(sixRecords())
        assertEquals(["1", "6"], KeepStepSupport.keptValues(ec, file, sideKeep(rules()), "id") as List)
        assertEquals(["web", "WEB"], KeepStepSupport.keptValues(ec, file, sideKeep(rules()), "channel") as List)
        // no Keep: every record's value, trimmed, blanks skipped, each once in record order
        assertEquals(["1", "2", "3", "4", "5", "6"], KeepStepSupport.keptValues(ec, file, null, "id") as List)
        assertEquals(["open", "Cancelled"], KeepStepSupport.keptValues(ec, file, null, "status") as List)
    }

    /** The records file as ingest reads it, with the Keep text columns for these rules. */
    private Dataset linesFrame(File file, List<Map> rules) {
        List<Map> pathInfos = [[needsExplode: true, arrayPath: "records", fieldPath: "id"]]
        File lines = JsonRecordLines.forRecordArray(file.absolutePath, pathInfos, null, KeepStepSupport.injections(rules))
        return spark.read().json(lines.absolutePath)
    }
}
