package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-063 — an audit's settings gear and play button, through the facade, in the ACTIVE tenant. R1's check reads one
 * system (an EVALUATE run, which save#Automation cannot schedule); R2's two checks share one two-sided run, so they share
 * one automation. Another tenant's audit reads as not found.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation)
class AuditSettingsSmokeTests {

    private static final String FACADE = "facade.AuditFacadeServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TSET_OTHER"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-settings")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml", "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            insert("darpan.audit.Audit", [auditId: "${OTHER}_ONLY".toString(), companyUserGroupId: OTHER, auditCode: "other-only",
                    auditName: "Other only", periodEnumId: "AUDP_MONTH"])
            [MINE, OTHER].each { String t ->
                insert("darpan.auth.TenantSetting", [companyUserGroupId: t, timeZone: "America/Los_Angeles"])
                insert("darpan.audit.Audit", [auditId: "${t}_SH".toString(), companyUserGroupId: t, auditCode: "system-health",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            }
            statement("R1", 1L, "An order HotWax completed or cancelled does not stay open in NetSuite.")
            statement("R2", 2L, "Every order paid in Shopify has its payment in HotWax.")
            run("TSET_RS1", "EVALUATE", [[side: "FILE_1", system: "NETSUITE_SUITEQL", key: "tranId"]])
            run("TSET_RS2", "COMPARE", [[side: "FILE_1", system: "SHOPIFY", key: "legacyResourceId"],
                                        [side: "FILE_2", system: "OMS_GQL", key: "externalId"]])
            // DAR-UI-064: a run's source filter must reach the automation made for it (it reads its own filter rows).
            insert("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "TSET_RS2_SCOPE", fileSide: "FILE_2", sequenceNum: 1L,
                    fieldExpression: "statusId", operator: "INCLUDE_IN", filterValues: "ORDER_COMPLETED", companyUserGroupId: MINE])
            check("TSET_OPEN", "open-in-netsuite", "TSET_RS1", "R1")
            check("TSET_PAID", "paid-without-opp", "TSET_RS2", "R2")
            check("TSET_PAID2", "paid-without-opp-2", "TSET_RS2", "R2")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    @Test
    @Order(1)
    void settingsShowEachStatementsChecksAndTheRunEachReads() {
        Map out = facade("get#AuditSettings", [audit: "system-health"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map settings = (Map) out.settings
        assertEquals([id: "system-health", name: "Month close", period: "month"], settings.audit)
        assertEquals([active: false, time: "06:00", timeZone: "America/Los_Angeles", mixed: false, automations: 0, checksWithoutRun: 3],
                settings.schedule, "no automation yet: paused at 06:00 in the tenant's zone")
        assertEquals(["R1", "R2"], settings.statements*.code)
        assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).withDayOfMonth(1).toString(), settings.playableFrom,
                "a monthly audit plays from the 1st while no earlier month is extended")
        assertEquals([[id: "paid-without-opp", label: "paid-without-opp", savedRunId: "TSET_RS2", runName: "TSET_RS2 run"],
                      [id: "paid-without-opp-2", label: "paid-without-opp-2", savedRunId: "TSET_RS2", runName: "TSET_RS2 run"]],
                settings.statements[1].checks)
    }

    @Test
    @Order(2)
    void aWrongInputWritesNothing() {
        Map bad = facade("save#AuditSettings", [audit: "system-health", name: "System health",
                schedule: [active: true, time: "25:00", timeZone: "America/New_York"]])
        assertTrue(bad.hadError as boolean)
        assertTrue((bad.errorText as String).contains("HH:mm"), bad.errorText as String)
        Map stranger = facade("save#AuditSettings", [audit: "system-health", statements: [[id: "${OTHER}_R1", text: "x"]]])
        assertTrue(stranger.hadError as boolean)
        assertEquals("Month close", (facade("get#AuditSettings", [audit: "system-health"]).settings as Map).audit.name, "nothing written")
    }

    @Test
    @Order(3)
    void savingRenamesRewordsTurnsOffAndSchedulesEveryCheck() {
        Map out = facade("save#AuditSettings", [audit: "system-health", name: "System health",
                statements: [[id: "${MINE}_R2".toString(), text: "Every paid Shopify order has its payment in HotWax.", active: true]],
                schedule: [active: true, time: "07:30", timeZone: "America/New_York"]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map settings = (Map) out.settings
        assertEquals("System health", settings.audit.name)
        assertEquals("Every paid Shopify order has its payment in HotWax.", settings.statements[1].text)
        assertEquals([active: true, time: "07:30", timeZone: "America/New_York", mixed: false, automations: 2, checksWithoutRun: 0],
                settings.schedule, "one automation per run: R2's two checks share one")

        Map paid = checkRow("TSET_PAID"), open = checkRow("TSET_OPEN")
        assertEquals(paid.automationId, checkRow("TSET_PAID2").automationId)
        def automation = row("darpan.reconciliation.ReconciliationAutomation", "automationId", paid.automationId)
        assertEquals(["0 30 7 * * ?", "America/New_York", "Y", "TSET_RS2", "TSET_RS2_SCOPE"],
                ["scheduleExpr", "windowTimeZone", "isActive", "savedRunId", "compareScopeId"].collect { automation.get(it) })
        assertNotNull(automation.nextScheduledFireTime)
        assertEquals(["FILE_1", "FILE_2"], sources(paid.automationId)*.fileSide)
        assertEquals(["FILE_1"], sources(open.automationId)*.fileSide, "the one-system run gets one source")
        // Each side names the config its run reads (the rule set's sourceConfigId), under the connector's own parameter: a
        // tenant with several NetSuite queries or OMS configs has no "single active" one to fall back on.
        Map<String, Map> params = sources(paid.automationId).collectEntries { [(it.fileSide): new groovy.json.JsonSlurper().parseText(it.safeMetadataJson as String).parameters] }
        assertEquals([FILE_1: [shopifyAuthConfigId: "cfg"], FILE_2: [omsRestSourceConfigId: "cfg"]], params)
        assertEquals([nsSuiteQlSourceQueryId: "cfg"], new groovy.json.JsonSlurper().parseText(sources(open.automationId)[0].safeMetadataJson as String).parameters)
        List filters = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter").condition("automationId", paid.automationId)
                .disableAuthz().useCache(false).list()
        assertEquals([["FILE_2", "statusId", "INCLUDE_IN", "ORDER_COMPLETED"]],
                filters.collect { [it.fileSide, it.fieldExpression, it.operator, it.filterValues] }, "the run's filter is the automation's filter")
    }

    @Test
    @Order(4)
    void aStatementTurnedOffLeavesTheReport() {
        Map out = facade("save#AuditSettings", [audit: "system-health", statements: [[id: "${MINE}_R1".toString(), active: false]]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([false, true], (out.settings as Map).statements*.active)
        Map report = facade("get#AuditReport", [audit: "system-health"])
        assertFalse(report.hadError as boolean, report.errorText as String)
        List codes = ((Map) report.report).statements.collect { it.id ?: it.code }
        assertFalse(codes.contains("R1"), "R1 is off: ${codes}")
        facade("save#AuditSettings", [audit: "system-health", statements: [[id: "${MINE}_R1".toString(), active: true]]])
    }

    @Test
    @Order(5)
    void playRefusesDaysItCannotRecord() {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).toString()
        String tomorrow = java.time.LocalDate.parse(today).plusDays(1).toString()
        Map future = facade("run#Audit", [audit: "system-health", fromDate: today, thruDate: tomorrow])
        assertTrue(future.hadError as boolean)
        assertTrue((future.errorText as String).contains("has not ended"), future.errorText as String)
        String lastMonthDay = java.time.LocalDate.parse(today).withDayOfMonth(1).minusDays(1).toString()
        Map ended = facade("run#Audit", [audit: "system-health", fromDate: lastMonthDay, thruDate: lastMonthDay])
        assertTrue(ended.hadError as boolean)
        assertTrue((ended.errorText as String).contains("has ended"), ended.errorText as String)
        Map backwards = facade("run#Audit", [audit: "system-health", fromDate: today, thruDate: lastMonthDay])
        assertTrue(backwards.hadError as boolean)
    }

    @Test
    @Order(6)
    void anotherTenantsAuditIsNotFound() {
        ["get#AuditSettings": [audit: "other-only"], "save#AuditSettings": [audit: "other-only", name: "x"],
         "run#Audit": [audit: "other-only", fromDate: "2026-10-01", thruDate: "2026-10-01"]].each { String svc, Map params ->
            Map out = facade(svc, params)
            assertTrue(out.hadError as boolean, svc)
            assertTrue((out.errorText as String).contains("was not found"), "${svc}: ${out.errorText}")
        }
        assertEquals("Other only", row("darpan.audit.Audit", "auditId", "${OTHER}_ONLY").auditName, "the other tenant's audit is untouched")
    }

    // DAR-UI-078 (Aditi: "is it possible to create these audits from UI.. if not we need to be able to").
    @Test
    @Order(7)
    void anAuditIsMadeInTheAppByNameAndPeriod() {
        Map out = facade("create#Audit", [name: "Month close 2", period: "month"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([id: "month-close-2", name: "Month close 2", period: "month"], out.audit)
        def made = ec.entity.find("darpan.audit.Audit").condition("auditCode", "month-close-2").disableAuthz().useCache(false).list()
        assertEquals([[MINE, "AUDP_MONTH"]], made.collect { [it.companyUserGroupId, it.periodEnumId] }, "the active tenant's own")
        // DAR-BE-135: minted, not tenant + code, which collided across tenants (DOLLS + "kill x" vs DOLLS_KILL + "x").
        assertTrue((made[0].auditId as String) ==~ /\d+/, made[0].auditId as String)
        assertTrue(facade("get#Audits").audits*.id.contains("month-close-2"))

        Map again = facade("create#Audit", [name: "month close 2", period: "day"])
        assertTrue(again.hadError as boolean, "one code per tenant")
        assertTrue(facade("create#Audit", [name: "Weekly", period: "week"]).hadError as boolean, "day, month or quarter")
        assertTrue(facade("create#Audit", [name: " -- ", period: "day"]).hadError as boolean, "a name with no letters or digits")
        assertTrue(facade("create#Audit", [name: "New", period: "day"]).hadError as boolean, "/audits/new is the create page")
    }

    @Test
    @Order(8)
    void aStatementIsAddedWithTheNextCodeAndCanTakeACheck() {
        Map first = facade("add#AuditStatement", [audit: "month-close-2", text: "Every order that is fulfilled is in NetSuite.",
                category: "orders", weight: "firm"])
        assertFalse(first.hadError as boolean, first.errorText as String)
        assertEquals([code: "R1", text: "Every order that is fulfilled is in NetSuite.", active: true, checks: []],
                first.statement.subMap(["code", "text", "active", "checks"]))
        Map second = facade("add#AuditStatement", [audit: "month-close-2", text: "Every return reaches NetSuite.", category: "returns"])
        assertEquals("R2", second.statement.code, "the next code")
        def row = ec.entity.find("darpan.audit.AuditStatement").condition("statementId", second.statement.id).disableAuthz().useCache(false).one()
        assertEquals([MINE, "AUDC_RETURNS", "AUDSW_DERIVED", 2L], [row.companyUserGroupId, row.categoryEnumId, row.weightEnumId, row.sequenceNum],
                "weight derived unless said firm")
        assertEquals(["R1", "R2"], (facade("get#AuditSettings", [audit: "month-close-2"]).settings as Map).statements*.code)

        assertTrue(facade("add#AuditStatement", [audit: "month-close-2", text: " ", category: "orders"]).hadError as boolean, "needs text")
        assertTrue(facade("add#AuditStatement", [audit: "month-close-2", text: "x", category: "inventory"]).hadError as boolean, "a known category")
        assertTrue(facade("add#AuditStatement", [audit: "other-only", text: "x", category: "orders"]).hadError as boolean,
                "another tenant's audit is not found")
    }

    // DAR-UI-085 (Aditi: "when an audit is running... i cannot see any indication"). Order(3) made both automations.
    @Test
    @Order(9)
    void progressShowsEachRunInFlightWithItsChecksAndNothingOfAnotherTenant() {
        Map idle = facade("get#AuditProgress", [audit: "system-health"])
        assertFalse(idle.hadError as boolean, idle.errorText as String)
        assertEquals([checks: 3, activeChecks: 0, active: []], idle.progress, "nothing in flight")

        String paid = checkRow("TSET_PAID").automationId
        String open = checkRow("TSET_OPEN").automationId
        long now = System.currentTimeMillis()
        insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "TSET_PROG_RR", companyUserGroupId: MINE,
                savedRunId: "TSET_RS2", statusEnumId: "AUT_STAT_RUNNING", currentStage: "EXTRACT_FILE2", progressPercent: 40L,
                startedDate: new java.sql.Timestamp(now - 3 * 60_000L), lastHeartbeatDate: new java.sql.Timestamp(now - 60_000L)])
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "TSET_PROG_X1", automationId: paid,
                companyUserGroupId: MINE, statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "TSET_PROG_RR",
                startedDate: new java.sql.Timestamp(now - 3 * 60_000L), retryCount: 0L])
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "TSET_PROG_X2", automationId: open,
                companyUserGroupId: MINE, statusEnumId: "AUT_STAT_PENDING", retryCount: 1L, createdDate: new java.sql.Timestamp(now)])
        // Another tenant's row naming one of these automations must not count: every read carries the tenant condition.
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "TSET_PROG_X3", automationId: open,
                companyUserGroupId: OTHER, statusEnumId: "AUT_STAT_RUNNING", retryCount: 0L])

        Map progress = facade("get#AuditProgress", [audit: "system-health"]).progress as Map
        assertEquals(3, progress.checks)
        assertEquals(3, progress.activeChecks, "R2's two checks ride one automation, R1's on the other")
        assertEquals(["running", "queued"], progress.active*.state, "running first")
        Map running = progress.active[0] as Map
        assertEquals(["paid-without-opp", "paid-without-opp-2"], running.checks)
        assertEquals("TSET_RS2 run", running.run)
        assertEquals(["EXTRACT_FILE2", 40L, "healthy", 1], [running.stage, running.progressPercent as Long, running.health, running.attempt])
        assertTrue((running.elapsedMs as Long) >= 3 * 60_000L, "elapsed from the run's start: ${running.elapsedMs}")
        Map queued = progress.active[1] as Map
        assertEquals([["open-in-netsuite"], 2, null], [queued.checks, queued.attempt, queued.health], "a retry waits; no health yet")

        assertTrue(facade("get#AuditProgress", [audit: "other-only"]).hadError as boolean, "another tenant's audit is not found")
    }

    private Map facade(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private void statement(String code, Long seq, String text) {
        [MINE, OTHER].each { String t ->
            insert("darpan.audit.AuditStatement", [statementId: "${t}_${code}".toString(), companyUserGroupId: t, auditId: "${t}_SH".toString(),
                    statementCode: code, categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: seq, statementText: text])
        }
    }

    private void run(String ruleSetId, String mode, List<Map> sides) {
        insert("darpan.rule.RuleSet", [ruleSetId: ruleSetId, ruleSetName: "${ruleSetId} run".toString(), version: "1.0", companyUserGroupId: MINE])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "${ruleSetId}_SCOPE".toString(), ruleSetId: ruleSetId,
                scopeMode: mode, objectType: "ORDER", companyUserGroupId: MINE])
        sides.each { Map s ->
            insert("darpan.rule.RuleSetCompareSource", [compareScopeId: "${ruleSetId}_SCOPE".toString(), fileSide: s.side,
                    systemEnumId: s.system, sourceTypeEnumId: "AUT_SRC_API", fileTypeEnumId: "DftJson",
                    recordRootExpression: "records", primaryIdExpression: s.key, sourceConfigId: "cfg", companyUserGroupId: MINE])
        }
    }

    private void check(String checkId, String code, String ruleSetId, String statementCode) {
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: MINE, checkCode: code, label: code,
                savedRunId: ruleSetId, graceDays: 1L])
        insert("darpan.audit.AuditStatementCheck", [checkId: checkId, statementId: "${MINE}_${statementCode}".toString(),
                companyUserGroupId: MINE, sequenceNum: 1L])
    }

    private Map checkRow(String checkId) { row("darpan.audit.AuditCheck", "checkId", checkId).getMap() }

    private def row(String entity, String field, Object value) {
        return ec.entity.find(entity).condition(field, value).disableAuthz().useCache(false).one()
    }

    private List sources(Object automationId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", automationId)
                .orderBy("fileSide").disableAuthz().useCache(false).list()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }
}
