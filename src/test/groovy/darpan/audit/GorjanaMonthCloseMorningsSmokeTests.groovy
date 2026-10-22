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
                "component://darpan/data/GorjanaMonthCloseChecksData.xml",
                "component://darpan/data/GorjanaMonthCloseReplacementChecksData.xml",
                "component://darpan/data/GorjanaMonthCloseWarrantyClaimChecksData.xml",
                "component://darpan/data/GorjanaMonthCloseReturnsChecksData.xml")
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

    /**
     * DAR-BE-094 / DAR-BE-095: one two-sided compare (exchange credit memos against the replacement orders that name
     * them), two field rules, two checks on ONE automation. Each statement takes only its own rule's mismatches.
     *   memo 81000101  replacement under another customer           -> R14
     *   memo 81000102  replacement is the return's original order   -> R13
     *   memo 81000103  neither side has an order id (two sentinels) -> nothing
     *   memo 81000104  its "replacement" is a 2023 order               -> R13 (the year-older rule)
     */
    @Test
    void oneCompareServesR14AndR13ThroughTheirOwnRules() {
        String auto = "GORJANA_AUTO_MEMO_REPLACEMENT"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: exchange credits against replacements", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "GORJANA_MEMO_REPLACEMENT_RS", savedRunType: "ruleset", ruleSetId: "GORJANA_MEMO_REPLACEMENT_RS",
                compareScopeId: "GORJANA_MEMO_REPLACEMENT_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: side], [
                    automationId: auto, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        ["GORJANA_CHK_REPLACEMENT_CUSTOMER", "GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL"].each { String id ->
            def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", id).disableAuthz().useCache(false).one()
            c.set("automationId", auto); c.update()
        }
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean memos = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 4,
                    fileLocation: "component://darpan/data/test/gorjana-${memos ? 'exchange-memos' : 'replacement-orders'}.json".toString(),
                    fileName: "${memos ? 'memos' : 'orders'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "GorjanaMonthCloseMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")

        Closure<List<Map>> findingsOf = { String checkId ->
            ec.entity.find("darpan.audit.AuditFinding").condition("checkId", checkId).disableAuthz().useCache(false).list()*.getMap()
        }
        List<Map> r14 = findingsOf("GORJANA_CHK_REPLACEMENT_CUSTOMER")
        List<Map> r13 = findingsOf("GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL")
        assertEquals([["GORJANA_R14", "81000101", "Credit memo id 81000101 and its replacement order are under different customers"]],
                r14.collect { [it.statementId, it.recordKey, it.headline] })
        assertEquals([["GORJANA_R13", "81000102", "Credit memo id 81000102 names an existing order as its replacement"],
                      ["GORJANA_R13", "81000104", "Credit memo id 81000104 names an existing order as its replacement"]],
                r13.sort { it.recordKey }.collect { [it.statementId, it.recordKey, it.headline] })
        // A FIELD_MISMATCH row carries no record, so the memo is named by the compare key (its internal id), and with no
        // conclusion rules there is no evidence state either.
        assertEquals([["NETSUITE", "creditMemo", "81000101", null, null]], records(r14[0].findingId as String),
                "one record, the memo; the replacement's id is not on the row")
    }

    /**
     * R12 as Aditi set it: every warranty claim has a credit memo for the amount claimed. Four claims: one credited at
     * its amount (65 against 65.00: one amount), one credited above it, one credited a cent below, one with no memo;
     * plus a memo for a return that is no warranty claim, which no statement takes.
     */
    @Test
    void r12FindsAClaimWithNoMemoAndMemosNotAtTheClaimedAmount() {
        String auto = "GORJANA_AUTO_WARRANTY_CLAIM"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: warranty claims against credit memos", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "GORJANA_WARRANTY_CLAIM_RS", savedRunType: "ruleset", ruleSetId: "GORJANA_WARRANTY_CLAIM_RS",
                compareScopeId: "GORJANA_WARRANTY_CLAIM_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: side], [
                    automationId: auto, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        ["GORJANA_CHK_WARRANTY_NO_MEMO", "GORJANA_CHK_WARRANTY_MEMO_AMOUNT"].each { String id ->
            def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", id).disableAuthz().useCache(false).one()
            c.set("automationId", auto); c.update()
        }
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean claims = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 4,
                    fileLocation: "component://darpan/data/test/gorjana-warranty-${claims ? 'claims' : 'claim-memos'}.json".toString(),
                    fileName: "${claims ? 'claims' : 'memos'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "GorjanaMonthCloseMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")

        Closure<List<Map>> findingsOf = { String checkId ->
            ec.entity.find("darpan.audit.AuditFinding").condition("checkId", checkId).disableAuthz().useCache(false).list()
                    *.getMap().sort { it.recordKey }
        }
        List<Map> noMemo = findingsOf("GORJANA_CHK_WARRANTY_NO_MEMO")
        assertEquals([["GORJANA_R12", "M400103", "Warranty claim RMA900103 has no credit memo"]],
                noMemo.collect { [it.statementId, it.recordKey, it.headline] }, "only the claim with no memo; not the stray memo")
        assertEquals([["NETSUITE", "warrantyClaim", "RMA900103", "82000103", null]], records(noMemo[0].findingId as String))

        List<Map> amount = findingsOf("GORJANA_CHK_WARRANTY_MEMO_AMOUNT")
        assertEquals(["M400102", "M400104"], amount*.recordKey, "65 and 65.00 are one amount; above and a cent below are not")
        assertEquals("Credit memo for return M400102 is 70.77; the claim is 65.00", amount[0].headline, "money to the cent")
        assertEquals([65.00, 70.77, 5.77, "USD"], [amount[0].expectedAmount, amount[0].actualAmount, amount[0].differenceAmount,
                amount[0].currencyUomId].collect { it instanceof BigDecimal ? (it as BigDecimal).setScale(2) : it })
        assertEquals([75.44, 75.43, -0.01], [amount[1].expectedAmount, amount[1].actualAmount, amount[1].differenceAmount]
                .collect { (it as BigDecimal).setScale(2) })
        assertEquals(["Warranty claim (RMA total)", "NetSuite credit memo total"], [amount[0].expectedSource, amount[0].actualSource])
    }

    /**
     * R16: a return the OMS cancelled whose NetSuite RMA is still open. Three cancelled returns: one with an open RMA, a
     * numeric Happiness Guarantee one whose RMA ("HG-628301", keyed without the prefix) is open, one with none; and an open
     * RMA for a return nobody cancelled, which no statement takes.
     */
    @Test
    void r16FindsACancelledReturnWhoseRmaIsStillOpen() {
        String auto = "GORJANA_AUTO_CANCELLED_RETURN"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: cancelled returns left open", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "GORJANA_CANCELLED_RETURN_RS", savedRunType: "ruleset", ruleSetId: "GORJANA_CANCELLED_RETURN_RS",
                compareScopeId: "GORJANA_CANCELLED_RETURN_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: "FILE_1"], [
                automationId: auto, fileSide: "FILE_1", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "OMS_RETURNS_GQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: "FILE_2"], [
                automationId: auto, fileSide: "FILE_2", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "GORJANA_CHK_CANCELLED_RETURN_OPEN").disableAuthz().useCache(false).one()
        c.set("automationId", auto); c.update()
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean oms = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 3,
                    fileLocation: "component://darpan/data/test/gorjana-${oms ? 'cancelled-returns' : 'open-rmas'}.json".toString(),
                    fileName: "${oms ? 'returns' : 'rmas'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "GorjanaMonthCloseMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")
        List<Map> found = ec.entity.find("darpan.audit.AuditFinding").condition("checkId", "GORJANA_CHK_CANCELLED_RETURN_OPEN")
                .disableAuthz().useCache(false).list()*.getMap().sort { it.recordKey }
        assertEquals([["GORJANA_R16", "628301", "Return 628301 is cancelled in the OMS; its RMA is Return Authorization : Pending Refund"],
                      ["GORJANA_R16", "M500101", "Return M500101 is cancelled in the OMS; its RMA is Return Authorization : Pending Receipt"]],
                found.collect { [it.statementId, it.recordKey, it.headline] }, "the cancelled one with no RMA and the uncancelled RMA are not findings")
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
