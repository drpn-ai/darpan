package darpan.audit

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-093 end to end: gorjana's R2 check ("every invoice carries the Etail id") as shipped configuration, run by a
 * real audit automation through the real executor, Spark evaluate, conclusion step and ledger, on two mornings.
 * Only the NetSuite read is stubbed, with a fixture per morning.
 *
 *   14 Oct  INV9000002 (no Etail id) and INV9000003 (blank Etail id) are found; pending (grace 1 day)
 *   15 Oct  INV9000002 now carries its Etail id: fixed. INV9000003 still has none: open
 *
 * R2's check reads ONE system, so this is also the scheduled path's single-sided (EVALUATE) run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GorjanaMonthCloseMorningsSmokeTests {

    private static final String TENANT = "GORJANA"
    private static final String AUTOMATION = "GORJANA_AUTO_INVOICE_ETAIL"
    private static final String CHECK = "GORJANA_CHK_INVOICE_ETAIL"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "gorjana-month-close-mornings")
        upsert("moqui.security.UserGroup", [userGroupId: TENANT], [userGroupId: TENANT, description: "gorjana"])
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml",
                "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/TriSystemOrderRunsRuleSetData.xml",
                "component://darpan/data/GorjanaConclusionRulesData.xml",
                "component://darpan/data/GorjanaMonthCloseCatalogData.xml",
                "component://darpan/data/GorjanaMonthCloseChecksData.xml")
        // The loader rolls a file back on a referential failure and still reports a count: prove the rows.
        assertEquals("GORJANA_INVOICE_ETAIL_RS", find("darpan.audit.AuditCheck", [checkId: CHECK])?.savedRunId)
        assertEquals(TENANT, find("darpan.rule.RuleSetConclusionRule",
                [compareScopeId: "GORJANA_INVOICE_ETAIL_SCOPE", sequenceNum: 10L])?.companyUserGroupId)

        // An audit automation is created per environment (DAR-BE-093 prod-owed); this is its shape: one NetSuite
        // source, the R2 rule set, the tenant's zone.
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION], [automationId: AUTOMATION,
                automationName: "Month close: invoices without an Etail id", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "GORJANA_INVOICE_ETAIL_RS", savedRunType: "ruleset", ruleSetId: "GORJANA_INVOICE_ETAIL_RS",
                compareScopeId: "GORJANA_INVOICE_ETAIL_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTOMATION, fileSide: "FILE_1"], [
                automationId: AUTOMATION, fileSide: "FILE_1", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        def check = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", CHECK).disableAuthz().useCache(false).one()
        check.set("automationId", AUTOMATION)
        check.update()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void anInvoiceIsFoundOneMorningAndFixedTheNext() {
        morning("2026-10-14", "component://darpan/data/test/gorjana-invoices-morning-1.json")

        Map<String, Map> first = findingsByRecord()
        assertEquals(["INV9000002", "INV9000003"] as Set, first.keySet(), "the two invoices with no Etail id")
        first.values().each { Map f ->
            assertEquals(["GORJANA_R2", "AUDFS_PENDING", Date.valueOf("2026-10-14")], [f.statementId, f.stateEnumId, f.firstFoundDate],
                    "${f.recordKey}: inside the one-day grace")
        }
        assertEquals("Invoice INV9000002 has no Etail id", first.INV9000002.headline)
        assertEquals([["NETSUITE", "invoice", "INV9000002", "81000002", "Invoice : Open"]], records(first.INV9000002.findingId),
                "the record a person searches by, NetSuite's own key, and its status in NetSuite's words")
        Map run1 = checkRun("2026-10-14")
        assertEquals(["AUDCS_RAN", "2026-10"], [run1.statusEnumId, run1.period])
        assertEquals([["NETSUITE", "AUDSR_COMPLETE", 3L]], reads(run1.checkRunId), "one system read, all three invoices")

        morning("2026-10-15", "component://darpan/data/test/gorjana-invoices-morning-2.json")

        Map<String, Map> second = findingsByRecord()
        assertEquals(["INV9000002", "INV9000003"] as Set, second.keySet(), "a fixed finding stays on the ledger")
        assertEquals("AUDFS_FIXED", second.INV9000002.stateEnumId, "seen with its Etail id, so it closes")
        assertEquals("AUDFS_OPEN", second.INV9000003.stateEnumId, "a day old against a one-day grace")
        assertEquals(Date.valueOf("2026-10-14"), second.INV9000003.firstFoundDate, "first found does not move")
        assertEquals(["AUDFE_FOUND", "AUDFE_FIXED"], events(second.INV9000002.findingId))
        assertEquals(["AUDFE_FOUND", "AUDFE_STILL_OPEN"], events(second.INV9000003.findingId))

        Map report = (Map) ec.service.sync().name("audit.AuditReportServices.assemble#AuditReport").parameters([
                companyUserGroupId: TENANT, auditId: "GORJANA_MONTH_CLOSE", reportDate: Date.valueOf("2026-10-15")])
                .disableAuthz().call()?.document
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map r2 = (Map) ((Map) ((Map) ((Map) report.categories).orders).statements).R2
        List<Map> findings = (List<Map>) r2.findings
        Map open = findings.find { it.records?.NetSuite?.invoice?.getAt(0)?.id == "INV9000003" }
        assertEquals("open", open?.state, "the report reads the ledger: ${findings*.headline}")
        assertEquals([[id: "INV9000003", internalId: "81000003", status: "Invoice : Open"]], open.records.NetSuite.invoice)
        assertNull(findings.find { it.records?.NetSuite?.invoice?.getAt(0)?.id == "INV9000004" }, "an invoice with its Etail id is no finding")
    }

    @Test
    void aSingleSidedAutomationWithASecondSourceIsRefused() {
        String twoSided = "GORJANA_AUTO_INVOICE_ETAIL_2"
        Timestamp now = ec.user.nowTimestamp
        Map automation = find("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION]).getMap()
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: twoSided],
                automation.findAll { it.key != "lastUpdatedStamp" } + [automationId: twoSided])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: twoSided, fileSide: side], [
                    automationId: twoSided, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: twoSided,
                    scheduledFireTime: Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())])
        }
        assertTrue(refused.message.contains("EVALUATE scope, which takes one source"), refused.message)
    }

    /** One scheduled morning at 06:00 in Los Angeles; the NetSuite read returns that morning's fixture. */
    private void morning(String day, String fixture) {
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            assertEquals("FILE_1", source.get("fileSide"), "a single-sided check never reads a second side")
            return [dataAvailable: true, fileLocation: fixture, fileName: "netsuite-invoices.json", fileTypeEnumId: "DftJson",
                    recordCount: fixture.endsWith("1.json") ? 3 : 4]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("${day}T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: AUTOMATION, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "GorjanaMonthCloseMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${day}: ${result}")
        def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("automationId", AUTOMATION).orderBy("-createdDate").disableAuthz().useCache(false).list()[0]
        assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId, "${day}: ${execution.errorMessage}")
    }

    private Map<String, Map> findingsByRecord() {
        return ec.entity.find("darpan.audit.AuditFinding").condition("checkId", CHECK).disableAuthz().useCache(false).list()
                .collectEntries { [(it.recordKey): it.getMap()] }
    }

    private List<List> records(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("findingId", findingId).orderBy("recordSeqId")
                .disableAuthz().useCache(false).list().collect { [it.systemEnumId, it.recordType, it.recordId, it.internalId, it.recordStatus] }
    }

    private List<String> events(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", findingId).orderBy("eventSeqId")
                .disableAuthz().useCache(false).list()*.eventTypeEnumId
    }

    private Map checkRun(String day) {
        return ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK).condition("reportDate", Date.valueOf(day))
                .disableAuthz().useCache(false).one()?.getMap()
    }

    private List<List> reads(String checkRunId) {
        return ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", checkRunId).orderBy("readSeqId")
                .disableAuthz().useCache(false).list().collect { [it.systemEnumId, it.statusEnumId, it.recordCount as Long] }
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    private void upsert(String entityName, Map pk, Map fields) {
        if (find(entityName, pk) == null) ec.entity.makeValue(entityName).setAll(fields).create()
    }
}
