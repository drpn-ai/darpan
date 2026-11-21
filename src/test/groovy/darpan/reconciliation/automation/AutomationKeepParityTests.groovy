package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b, Task 14. A scheduled run whose side keeps in Spark gives the live results a filtering read gives:
 * the same kept records, findings, counts and side file, and the other side is read by the same keys.
 *
 * Two made-up systems read the same records with the same filter rows (the automation's own, Ruling 3b2b-15):
 * <ul>
 * <li>ACME_READ, whose extract still declares sourceFilters and filters as every real extract does until Tasks 17-23
 * (the stub applies SourceFilterSupport.applyToRecords and writes the side file, as extractors do): today, the "before".</li>
 * <li>ACME_SPARK, whose extract no longer takes filters: it is sent none, writes every record, and the compare's ingest keeps
 * in Spark and writes the set-aside artifact and side file: the "after".</li>
 * </ul>
 * Each run is the real executor (executeAutomation) and the real Spark compare, scheduled: no user, no active tenant. The
 * rows cover an exclude list, an include prefix that sets aside records lacking the field, a number written as 24.00, case
 * and surrounding spaces. A row stamped for another tenant under the Spark automation's id would set aside more records
 * if it were read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationKeepParityTests {
    private static final String TENANT = "KREWE"
    private static final String OTHER_TENANT = "BRAVO"
    private static final String SPARK_EXTRACT = "reconciliation.AutomationKeepTestExtractionServices.extract#SparkKeptRecords"
    private static final String READ_EXTRACT = "reconciliation.AutomationKeepTestExtractionServices.extract#ReadFilteredRecords"
    private static final String LEDGER_EXTRACT = "reconciliation.AutomationKeepTestExtractionServices.extract#LedgerRecords"
    private static final Timestamp FIRE = Timestamp.valueOf("2026-11-21 06:00:00")

    private static final List<Map> ORDERS = [
            [id: "K1", status: "OPEN", region: "NORTH", total: 10.5],
            [id: "K2", status: "VOID", region: "NORTH", total: 11],
            [id: "K3", status: "OPEN", region: "SOUTH", total: 12],
            [id: "K4", status: "OPEN", total: 13],
            [id: "K5", status: "open", region: "NORTH", total: 24.00],
            [id: "K6", status: " cancelled ", region: " north ", total: 7],
            [id: "K7", status: "OPEN", region: "NORTHEAST", total: 8],
            [id: "K8", status: "OPEN", region: "North", total: 9],
    ]
    private static final List<Map> LEDGER = [[id: "K1"], [id: "K2"], [id: "K3"], [id: "K7"], [id: "K9"]]

    private ExecutionContext ec
    private int readSeq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "automation-keep-parity")
        File dataManager = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManager.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManager}")
        new File(dataManager, DataManagerSupport.RECONCILIATION_RUNS_PATH).deleteDir()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seed()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void reset() {
        AutomationExecutionSupport.resetExecutionHooks()
    }

    @Test
    void aScheduledRunKeepingInSparkMatchesTheFilteringRead() {
        List<Map> readCalls = []
        Map before = run("KAP_READ", readCalls)
        List<Map> sparkCalls = []
        Map after = run("KAP_SPARK", sparkCalls)

        // what each read was sent: the filtering read its rows (only its tenant's), the Spark read none and its Keep fields
        Map readSent = readCalls.find { it.service == READ_EXTRACT }.params
        Map sparkSent = sparkCalls.find { it.service == SPARK_EXTRACT }.params
        assertEquals([1, 2, 3], ((List<Map>) readSent.sourceFilters).collect { it.sequenceNum as int })
        assertFalse(sparkSent.containsKey("sourceFilters"))
        assertEquals(["status", "region", "total"], sparkSent.keepRuleFields)
        assertEquals(["status", "region", "total"], sparkSent.readFields)

        // the Spark side's extract holds every record; Keep kept what the filtering read wrote
        assertEquals(ORDERS.size(), records(after.extract).size())
        assertEquals(["K1", "K7", "K8"], records(before.extract)*.id)
        assertEquals(JsonOutput.toJson(records(before.extract)), JsonOutput.toJson(kept(after.extract)))
        assertNotNull(KeepArtifacts.readSummary(after.extract))

        // counts and status
        ["statusEnumId", "differenceCount", "onlyInFile1Count", "onlyInFile2Count", "file1RecordCount", "file2RecordCount"].each { String f ->
            assertEquals(before.execution[f], after.execution[f], f)
        }
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, after.execution.statusEnumId)
        assertEquals(3, after.execution.file1RecordCount)

        // findings
        assertEquals(JsonOutput.toJson(before.document.summary), JsonOutput.toJson(after.document.summary))
        assertEquals(JsonOutput.toJson(before.document.differences), JsonOutput.toJson(after.document.differences))

        // the side file: the same records, in the same order, each with the same _excludedBy
        Map beforeSide = sideFile(before.extract)
        Map afterSide = sideFile(after.extract)
        assertEquals(5, ((List) beforeSide.records).size())
        assertEquals(JsonOutput.toJson(beforeSide), JsonOutput.toJson(afterSide))
    }

    @Test
    void theOtherSideIsReadOnlyByKeptKeys() {
        // Review Focus 1, Ruling 3b2b-11: the ledger side is read by side 1's keys. Side 1's extract now holds its set-aside
        // records too; only the kept ones give keys, as the filtering read's file did.
        List<Map> readCalls = []
        run("KAP_KEYS_READ", readCalls)
        List<Map> sparkCalls = []
        run("KAP_KEYS_SPARK", sparkCalls)

        List<String> readKeys = (List<String>) readCalls.find { it.service == LEDGER_EXTRACT }.params.lookupKeys
        List<String> sparkKeys = (List<String>) sparkCalls.find { it.service == LEDGER_EXTRACT }.params.lookupKeys
        assertEquals(["K1", "K7", "K8"], readKeys)
        assertEquals(readKeys, sparkKeys)
    }

    // ---------------------------------------------------------------------------------------------------------- the run

    /** The automation run once, scheduled; its execution row, its side-1 extract and its result document. */
    private Map run(String automationId, List<Map> calls) {
        ec.user.logoutUser()
        ec.user.loginAnonymousIfNoUser()
        try {
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: automationId, scheduledFireTime: FIRE,
                    sparkMaster: "local[1]", sparkAppName: "AutomationKeepParityTests", liveExtractor: liveExtractor(calls)])
            assertEquals(1, result.executedCount, result.toString())
        } finally {
            ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        }
        def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution").condition("automationId", automationId)
                .disableAuthz().useCache(false).one()
        assertNotNull(execution)
        File extract = RunVerificationSupport.resolveLocationFile(ec, execution.file1DataManagerPath as String)
        File document = DataManagerSupport.resolveDataManagerFile(ec, execution.resultDataManagerPath as String, false)
        assertTrue(document?.isFile(), "result document of ${automationId}: ${execution.resultDataManagerPath}")
        // read now: two runs in one second share the result path, and the next run would overwrite it
        return [execution: new LinkedHashMap(execution.getMap()), extract: extract, document: json(document)]
    }

    /** What a live read answers: the records (by key when asked), the rows it was sent applied and their side file written. */
    private Closure liveExtractor(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            boolean ledger = service == LEDGER_EXTRACT
            List<Map> rows = ledger ? LEDGER : ORDERS
            if (params.lookupKeys) rows = rows.findAll { ((List) params.lookupKeys).contains(it.get(params.lookupKeyField)) }
            Map applied = SourceFilterSupport.applyToRecords(rows, SourceFilterSupport.parseRules((params.sourceFilters ?: []) as List))
            String location = DataManagerSupport.resolveReconciliationRunLocation(ec, params.automationExecutionId as String,
                    "20261121-060000000-${params.fileSide}-${++readSeq}".toString())
            File dir = DataManagerSupport.resolveDirectoryFile(ec, location, true)
            String fileName = ledger ? "ledger.json" : "acme-orders.json"
            new File(dir, fileName).setText(JsonOutput.toJson([metadata: [filters: [configuredExclusions: applied.configuredExclusions]],
                                                               records : applied.records]), "UTF-8")
            if (applied.excludedCollector) ExcludedRecordsSidecar.writeBeside(ec, (Map) applied.excludedCollector, location, fileName, dir)
            List kept = (List) applied.records
            return [fileLocation : DataManagerSupport.childLocation(location, fileName), fileName: fileName, fileTypeEnumId: "DftJson",
                    recordCount  : kept.size(), dataAvailable: !kept.isEmpty()]
        }
    }

    // -------------------------------------------------------------------------------------------------------- the reads

    private static List<Map> records(File extract) {
        return (List<Map>) json(extract).records
    }

    private static List<Map> kept(File extract) {
        List<Map> out = []
        KeepArtifacts.forEachKeptRecord(extract) { Map record -> out << record }
        return out
    }

    private static Map sideFile(File extract) {
        File side = new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
        assertTrue(side.isFile(), "side file beside ${extract}: ${extract.parentFile.list()}")
        return json(side)
    }

    private static Map json(File f) {
        return (Map) new JsonSlurper().parse(f, "UTF-8")
    }

    // ------------------------------------------------------------------------------------------------------------ setup

    private void seed() {
        Closure<String> metadata = { String service, boolean byKeys ->
            JsonOutput.toJson([extractServiceName: service] + (byKeys ? [parameters: [readByOtherSideKeys: true]] : [:]))
                    .replace('"', "&quot;")
        }
        StringBuilder automations = new StringBuilder()
        [KAP_READ: ["ACME_READ", READ_EXTRACT, false], KAP_SPARK: ["ACME_SPARK", SPARK_EXTRACT, false],
         KAP_KEYS_READ: ["ACME_READ", READ_EXTRACT, true], KAP_KEYS_SPARK: ["ACME_SPARK", SPARK_EXTRACT, true]].each { String id, List side1 ->
            automations << """
            <darpan.reconciliation.ReconciliationAutomation automationId="${id}" automationName="${id}" companyUserGroupId="${TENANT}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="KAP_RS" savedRunType="ruleset"
                ruleSetId="KAP_RS" compareScopeId="KAP_SCOPE" relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}"
                relativeWindowCount="1" windowTimeZone="UTC" isActive="N"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${id}" fileSide="FILE_1" companyUserGroupId="${TENANT}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${side1[0]}" fileTypeEnumId="DftJson"
                safeMetadataJson="${metadata(side1[1] as String, false)}"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${id}" fileSide="FILE_2" companyUserGroupId="${TENANT}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="ACME_LEDGER" fileTypeEnumId="DftJson"
                safeMetadataJson="${metadata(LEDGER_EXTRACT, side1[2] as boolean)}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${id}" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].status" operator="EXCLUDE_IN" filterValues="VOID,CANCELLED" companyUserGroupId="${TENANT}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${id}" fileSide="FILE_1" sequenceNum="2"
                fieldExpression="\$.records[*].region" operator="INCLUDE_PREFIX" filterValues="NO" companyUserGroupId="${TENANT}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${id}" fileSide="FILE_1" sequenceNum="3"
                fieldExpression="total" operator="EXCLUDE_IN" filterValues="24.00" companyUserGroupId="${TENANT}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${id}" fileSide="FILE_1" sequenceNum="4"
                fieldExpression="id" operator="EXCLUDE_IN" filterValues="K1,K7,K8" companyUserGroupId="${OTHER_TENANT}"/>"""
        }
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <moqui.security.UserGroup userGroupId="${OTHER_TENANT}" description="Bravo"
                groupTypeEnumId="${TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_SPARK" extractServiceName="${SPARK_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" filterParameterName="sourceFilters"
                excludedRecordsSidecar="Y" enabled="Y"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_READ" extractServiceName="${READ_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" filterParameterName="sourceFilters"
                excludedRecordsSidecar="Y" enabled="Y"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LEDGER" extractServiceName="${LEDGER_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" enabled="Y"/>
            <darpan.rule.RuleSet ruleSetId="KAP_RS" ruleSetName="ACME orders vs ledger" companyUserGroupId="${TENANT}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="KAP_SCOPE" ruleSetId="KAP_RS" objectType="ORDER" companyUserGroupId="${TENANT}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KAP_SCOPE" fileSide="FILE_1" systemEnumId="ACME_SPARK" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="id" companyUserGroupId="${TENANT}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KAP_SCOPE" fileSide="FILE_2" systemEnumId="ACME_LEDGER" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="id" companyUserGroupId="${TENANT}"/>
            ${automations}
        </entity-facade-xml>""").dummyFks(true).load()
    }
}
