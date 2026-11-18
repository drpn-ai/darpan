package darpan.retention

import darpan.admin.AuditLogTestSupport
import darpan.config.DarpanSettings
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-306. Retention and purge periods as data: instance settings plus per-tenant rows, edited by a real DARPAN_SUPER_ADMIN
 * through admin.RetentionAdminServices (authz ON, a thread of its own). Edge cases: a tenant's own period wins over the instance's
 * for that tenant only (forever included), a preview of one tenant touches one tenant, a bad period saves nothing, shortening a
 * period while the job runs needs a fresh preview, the generic settings editor leaves retention keys alone, and the migration moves
 * an edited job parameter into the setting exactly once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionPeriodAdminSmokeTests {
    private static final String APPLY = "component://darpan/data/zzScheduleJobDefaultsApply.xml"
    private static final List<String> JOB_SEEDS = ["AuditSeedData", "ReconciliationJobSeedData", "TwoFactorSeedData",
            "ScriptJobSeedData", "TenantConfigSeedData"].collect { "component://darpan/data/${it}.xml".toString() }
    private static final String R = "admin.RetentionAdminServices."
    private static final String A = "RPA_A"
    private static final String B = "RPA_B"
    private static final String C = "RPA_C"
    private static final String QUERY_LOG = RetentionSupport.AGENT_QUERY_LOG
    private static final String QL_KEY = "darpan.retention.agentQueryLog.days"
    private static final String JOB = "run_DataRetention_daily"
    private static final String SUPER_ID = "RPA_SUPER"
    private static final String SUPER = "rpa.super"
    private static final String TENANT_ADMIN_ID = "RPA_TADMIN"
    private static final String TENANT_ADMIN = "rpa.tadmin"
    private static final String DADMIN_ID = "RPA_DADMIN"
    private static final String DADMIN = "rpa.dadmin"
    private static final String PLAIN_ID = "RPA_PLAIN"
    private static final String PLAIN = "rpa.plain"
    private static final long DAY = 86_400_000L
    private static final String MIGRATION = "retention.RetentionMigrations.move#PurgeJobRetentionParameters"
    private static final String GENERATED = "purge_ReconciliationGeneratedFiles_daily"
    private static final String EXTRACTS = "purge_ReconciliationExtractFiles_daily"
    private static final String REAPER = "sweep_StuckReconciliationRuns_10m"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "retention-period-admin")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/PasswordResetEmailSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, *(JOB_SEEDS + [APPLY]))
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")   // run status enums for the run-result rows
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            [A, B, C].each { String t ->
                ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: t, description: "${t} Co".toString(),
                        groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            }
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void resetFixtures() {
        asSystem {
            ec.entity.find("darpan.config.DarpanSetting").deleteAll()
            ec.entity.find("darpan.retention.TenantRetentionPeriod").deleteAll()
            ec.entity.find("darpan.agent.AgentQueryLog").deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("companyUserGroupId", org.moqui.entity.EntityCondition.IN, [A, B, C]).deleteAll()
            ec.entity.find("moqui.service.job.ServiceJobParameter").condition("parameterName", "retentionDays").deleteAll()
            ec.entity.find("moqui.service.job.ServiceJobParameter").condition("parameterName", "staleMinutes").deleteAll()
            setJobPaused("Y")
            [A, B, C].each { String t ->
                [5, 20, 50].each { int age -> queryLog("${t}_Q${age}".toString(), t, age) }
                [20, 50].each { int age -> runResult("${t}_RR${age}".toString(), t, age) }
            }
        }
        DarpanSettings.expire()
        [[SUPER_ID, SUPER], [TENANT_ADMIN_ID, TENANT_ADMIN], [DADMIN_ID, DADMIN], [PLAIN_ID, PLAIN]].each { id, name ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: id, username: name, userFullName: name,
                    emailAddress: "${name}@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
        }
        [[SUPER_ID, "DARPAN_SUPER_ADMIN"], [TENANT_ADMIN_ID, "DARPAN_TENANT_ADMIN"], [DADMIN_ID, "DARPAN_ADMIN"]].each { id, group ->
            if (!ec.entity.find("moqui.security.UserGroupMember").condition([userGroupId: group, userId: id])
                    .disableAuthz().useCache(false).count()) {
                ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: group, userId: id,
                        fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
            }
        }
        AuditLogTestSupport.wipe(ec, "darpan.admin.AdminAuditLog")
        ec.message.clearErrors()
    }

    @AfterEach
    void clearProps() {
        System.clearProperty(QL_KEY)
        DarpanSettings.expire()
    }

    // ------------------------------------------------------------------ the sweep, per tenant

    @Test
    void tenantPeriodsWinAndStayInTheirTenant() {
        setSetting(QL_KEY, "30")
        setSetting("darpan.retention.runResult.days", "30")
        asSystem {
            tenantPeriod(A, 10)
            tenantPeriod(B, null)   // keep forever, whatever the instance says
            // the other table has different tenant rows, so a bug that ignores tableKey shows: A keeps run results forever
            // while its query-log period is 10, B's run results go at 10 while its query logs are forever
            tenantPeriod(A, null, RetentionSupport.RUN_RESULT)
            tenantPeriod(B, 10, RetentionSupport.RUN_RESULT)
        }
        Map out = runAsJob("run#Retention", [:])
        assertEquals(["${A}_RR20".toString(), "${A}_RR50".toString()], runIds(A), "A keeps run results forever")
        assertEquals([], runIds(B), "B's run results go at 10 days")
        assertEquals(["${C}_RR20".toString()], runIds(C), "C follows the instance's 30 days")
        assertEquals(["${A}_Q5".toString()], logIds(A), "A's own 10 days: the 20 and 50 day rows go")
        assertEquals(["${B}_Q20".toString(), "${B}_Q5".toString(), "${B}_Q50".toString()], logIds(B), "B keeps forever: nothing goes")
        assertEquals(["${C}_Q20".toString(), "${C}_Q5".toString()], logIds(C), "C follows the instance's 30 days: only the 50 day row goes")
        Map table = (out.tables as List<Map>).find { it.table == QUERY_LOG }
        assertEquals(30, table.periodDays)
        assertEquals([(A): 2L, (C): 1L], table.byTenant)
        assertEquals([(A): 10, (B): null], table.byTenantPeriod)
        assertEquals(RetentionSupport.DELETED, table.status)
    }

    @Test
    void aTenantPeriodAloneDeletesEvenWhenTheInstanceKeepsForever() {
        asSystem { tenantPeriod(A, 10) }
        Map out = runAsJob("run#Retention", [:])
        assertEquals(["${A}_Q5".toString()], logIds(A))
        assertEquals(3, logIds(B).size(), "no instance period and no row: kept")
        assertEquals(3, logIds(C).size())
        assertEquals(RetentionSupport.DELETED, (out.tables as List<Map>).find { it.table == QUERY_LOG }.status)
    }

    @Test
    void aDryRunOfOneTenantsPeriodsPreviewsThatTenantOnly() {
        Map one = ok(svc("dryRun#Retention", [tenantUserGroupId: A, tenantPeriodDays: [(QUERY_LOG): 10]])).result
        Map table = (one.tables as List<Map>).find { it.table == QUERY_LOG }
        assertEquals(2L, table.rows)
        assertEquals([(A): 2L], table.byTenant)
        assertEquals([(A): 10], table.byTenantPeriod)
        assertEquals(3L, count("darpan.agent.AgentQueryLog", [companyUserGroupId: [A]]), "a dry run deletes nothing")

        // inherit previews following the instance period; forever previews nothing
        setSetting(QL_KEY, "30")
        asSystem { tenantPeriod(A, 10) }
        Map inherit = ok(svc("dryRun#Retention", [tenantUserGroupId: A, tenantPeriodDays: [(QUERY_LOG): "inherit"]])).result
        assertEquals(1L, (inherit.tables as List<Map>).find { it.table == QUERY_LOG }.rows)
        Map forever = ok(svc("dryRun#Retention", [tenantUserGroupId: A, tenantPeriodDays: [(QUERY_LOG): "forever"]])).result
        assertEquals(0L, (forever.tables as List<Map>).find { it.table == QUERY_LOG }.rows)

        // one tenant's what-if needs that tenant, and a bad value is refused
        assertTrue(svc("dryRun#Retention", [tenantPeriodDays: [(QUERY_LOG): 10]]).errors.any { it.contains("tenantUserGroupId") })
        assertTrue(svc("dryRun#Retention", [tenantUserGroupId: A, tenantPeriodDays: [(QUERY_LOG): 0]]).hasError)
        assertTrue(svc("dryRun#Retention", [periodDays: [(QUERY_LOG): "inherit"]]).hasError, "the instance has nothing to inherit")
    }

    // ------------------------------------------------------------------ writing the periods

    @Test
    void aBadPeriodIsRefusedAndNothingIsSaved() {
        [0, 3651, -5, "inherit", "abc", "", null, 12.5].each { Object v ->
            Map out = svc("update#RetentionPeriods", [periods: [(QUERY_LOG): v]])
            assertTrue(out.hasError, "'${v}' must be refused at instance level")
        }
        assertTrue(svc("update#RetentionPeriods", [periods: [NOT_A_TABLE: 30]]).errors.any { it.contains("Unknown retention table") })
        assertTrue(svc("update#RetentionPeriods", [periods: [:]]).hasError)
        assertTrue(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): 0]]).hasError)
        assertTrue(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): 3651]]).hasError)
        assertTrue(svc("update#RetentionPeriods", [tenantUserGroupId: "RPA_NOPE", periods: [(QUERY_LOG): 30]]).errors.any { it.contains("No tenant RPA_NOPE.") })
        assertTrue(svc("update#RetentionPeriods", [tenantUserGroupId: "DARPAN_ADMIN", periods: [(QUERY_LOG): 30]]).hasError, "a group that is not a tenant")
        assertEquals(0, count("darpan.config.DarpanSetting"))
        assertEquals(0, count("darpan.retention.TenantRetentionPeriod"))
        assertEquals([], auditRows())
    }

    @Test
    void anInstancePeriodIsSavedAuditedAndForeverRemovesIt() {
        Map saved = ok(svc("update#RetentionPeriods", [periods: [(QUERY_LOG): 90]])).result
        assertEquals(true, saved.ok)
        assertEquals([[table: QUERY_LOG, key: QL_KEY, mode: "days", before: null, beforeSource: "INSTANCE", after: 90]], saved.changed)
        assertEquals(90, DarpanSettings.optionalIntValue(QL_KEY))
        Map entry = (saved.retention.instance as List<Map>).find { it.table == QUERY_LOG }
        assertEquals([90, "ROW"], [entry.periodDays, entry.source])
        assertEquals(["${QL_KEY}: null -> 90".toString()], auditRows()*.detailText)
        assertEquals(R + "update#RetentionPeriods", auditRows()[0].serviceName)
        assertEquals(SUPER_ID, ec.entity.find("darpan.config.DarpanSetting").condition("settingKey", QL_KEY).disableAuthz().useCache(false).one().lastChangedByUserId)

        // saying the same again changes and audits nothing
        ok(svc("update#RetentionPeriods", [periods: [(QUERY_LOG): "90"]]))
        assertEquals(1, auditRows().size())

        Map forever = ok(svc("update#RetentionPeriods", [periods: [(QUERY_LOG): "forever"]])).result
        assertNull(DarpanSettings.optionalIntValue(QL_KEY))
        assertEquals(0, count("darpan.config.DarpanSetting"))
        assertNull((forever.retention.instance as List<Map>).find { it.table == QUERY_LOG }.periodDays)
        assertEquals("${QL_KEY}: 90 -> default null".toString(), auditRows()[1].detailText)

        // a JVM property that pins the period cannot be undone by a row delete: say so
        System.setProperty(QL_KEY, "45")
        DarpanSettings.expire()
        Map pinned = svc("update#RetentionPeriods", [periods: [(QUERY_LOG): "forever"]])
        assertTrue(pinned.errors.any { it.contains("pinned") }, "${pinned.errors}")
    }

    @Test
    void aTenantPeriodIsItsOwnRowAndInheritDropsIt() {
        Map saved = ok(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): 10]])).result
        assertEquals([10], saved.changed*.after)
        Map row = tenantRow(A)
        assertEquals([10, SUPER_ID], [row.periodDays, row.lastChangedByUserId])
        assertEquals([A], saved.retention.tenants*.tenantUserGroupId)
        assertEquals(1, auditRows().size())
        assertEquals(["TenantRetentionPeriod", A, R + "update#RetentionPeriods"], [auditRows()[0].targetType, auditRows()[0].targetId, auditRows()[0].serviceName])
        assertEquals("${QUERY_LOG}: instance forever -> 10d".toString(), auditRows()[0].detailText)
        assertEquals(0, count("darpan.config.DarpanSetting"), "a tenant period never touches the instance setting")

        ok(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): "forever"]]))
        assertNotNull(tenantRow(A), "forever is a row with no days")
        assertNull(tenantRow(A).periodDays)
        assertEquals("${QUERY_LOG}: 10d -> forever".toString(), auditRows()[1].detailText)

        setSetting(QL_KEY, "60")
        ok(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): "inherit"]]))
        assertNull(tenantRow(A), "inherit drops the row")
        assertEquals("${QUERY_LOG}: forever -> inherit (instance 60d)".toString(), auditRows()[2].detailText)

        ok(svc("update#RetentionPeriods", [tenantUserGroupId: A, periods: [(QUERY_LOG): "inherit"]]))
        assertEquals(3, auditRows().size(), "inheriting what is already inherited changes and audits nothing")
        assertEquals(0, ((Map) ok(svc("get#RetentionPeriods", [:])).result.retention).tenants.size())
    }

    @Test
    void getShowsTheInstanceTenantsPurgeDaysBatchSizeAndTheJob() {
        setSetting(QL_KEY, "90")
        asSystem { tenantPeriod(B, null) }
        Map got = ok(svc("get#RetentionPeriods", [:])).result.retention
        assertEquals(4, got.instance.size())
        assertEquals([90, "ROW"], got.instance.find { it.table == QUERY_LOG }.with { [it.periodDays, it.source] })
        assertEquals(["DEFAULT"], got.instance.findAll { it.table != QUERY_LOG }*.source.unique())
        assertEquals([[tenantUserGroupId: B, table: QUERY_LOG, periodDays: null]], got.tenants.collect { it.subMap(["tenantUserGroupId", "table", "periodDays"]) })
        assertEquals([15, 7, 500, JOB, "Y"], [got.generatedOutputDays, got.extractDays, got.batchSize, got.jobName, got.jobPaused])
    }

    @Test
    void shorteningAPeriodWhileTheJobRunsNeedsAFreshPreviewAndLengtheningDoesNot() {
        setSetting(QL_KEY, "400")
        asSystem {
            (1..120).each { int n -> queryLog("RPA_BULK_${n}".toString(), A, 50) }
            setJobPaused("N")
        }
        // 3 fixture rows (one per tenant) + 120 are older than 30 days
        Map none = svc("update#RetentionPeriods", [periods: [(QUERY_LOG): 30]])
        assertTrue(none.errors.any { it.contains("Preview first: making a period shorter") }, "${none.errors}")
        assertEquals(400, DarpanSettings.optionalIntValue(QL_KEY))
        Map low = svc("update#RetentionPeriods", [periods: [(QUERY_LOG): 30], previewedRows: 5L])
        assertTrue(low.errors.any { it.contains("The numbers changed since your preview; preview again.") }, "${low.errors}")
        assertEquals(400, DarpanSettings.optionalIntValue(QL_KEY))
        assertEquals([], auditRows())

        // the number the same dry run shows is what passes
        Map shown = ok(svc("dryRun#Retention", [periodDays: [(QUERY_LOG): 30]])).result
        long total = (shown.tables as List<Map>).sum { it.rows } as long
        assertEquals(123L, total)
        Map go = ok(svc("update#RetentionPeriods", [periods: [(QUERY_LOG): 30], previewedRows: total])).result
        assertEquals(30, DarpanSettings.optionalIntValue(QL_KEY))
        assertEquals(["${QL_KEY}: 400 -> 30 (preview showed 123 rows, today's dry run counts 123)".toString()], auditRows()*.detailText)
        assertTrue((go.previewNote as String).contains("123"))
        assertEquals(9L + 120L, count("darpan.agent.AgentQueryLog"), "saving deletes nothing itself: only the job does")

        // lengthening and an unchanged period need no preview
        ok(svc("update#RetentionPeriods", [periods: [(QUERY_LOG): 60]]))
        assertEquals(60, DarpanSettings.optionalIntValue(QL_KEY))
    }

    @Test
    void foreverToDaysAndATenantsShorterPeriodAlsoNeedAPreviewWhileTheJobRuns() {
        asSystem { tenantPeriod(B, null); setJobPaused("N") }
        Map none = svc("update#RetentionPeriods", [tenantUserGroupId: B, periods: [(QUERY_LOG): 30]])
        assertTrue(none.errors.any { it.contains("Preview first") }, "${none.errors}")
        assertNull(tenantRow(B).periodDays)
        // the preview of that one tenant counts that tenant only: B's 50 day row
        Map shown = ok(svc("dryRun#Retention", [tenantUserGroupId: B, tenantPeriodDays: [(QUERY_LOG): 30]])).result
        assertEquals(1L, (shown.tables as List<Map>).sum { it.rows })
        ok(svc("update#RetentionPeriods", [tenantUserGroupId: B, periods: [(QUERY_LOG): 30], previewedRows: 1L]))
        assertEquals(30, tenantRow(B).periodDays)
        assertTrue(auditRows()[0].detailText.contains("preview showed 1 rows, today's dry run counts 1"), auditRows()[0].detailText)

        // forever is never a deletion: no preview
        ok(svc("update#RetentionPeriods", [tenantUserGroupId: B, periods: [(QUERY_LOG): "forever"]]))
        assertNull(tenantRow(B).periodDays)
        // while the job is paused, shortening needs none
        asSystem { setJobPaused("Y") }
        ok(svc("update#RetentionPeriods", [tenantUserGroupId: B, periods: [(QUERY_LOG): 7]]))
        assertEquals(7, tenantRow(B).periodDays)
    }

    // ------------------------------------------------------------------ who may, and the generic editor

    @Test
    void onlyASuperAdminGetsIn() {
        ["get#RetentionPeriods": [:], "update#RetentionPeriods": [periods: [(QUERY_LOG): 30]],
         "dryRun#Retention": [:]].each { String method, Map params ->
            [PLAIN, TENANT_ADMIN, DADMIN].each { String who ->
                Map out = svc(method, params, who)
                assertTrue(out.authzDenied != null || out.hasError, "${method} must refuse ${who}")
            }
        }
        assertEquals(0, count("darpan.config.DarpanSetting"))
        assertEquals(0, count("darpan.retention.TenantRetentionPeriod"))
        assertEquals([], auditRows())
    }

    @Test
    void theGenericSettingsEditorLeavesARetentionKeyToTheRetentionService() {
        ["update#Setting": [settingKey: QL_KEY, value: "30"], "reset#Setting": [settingKey: QL_KEY]].each { String method, Map params ->
            Map out = svc("admin.SettingAdminServices.${method}".toString(), params)
            assertTrue(out.errors.any { it.contains("managed by retention") && it.contains("Retention") }, "${method}: ${out.errors}")
        }
        assertEquals(0, count("darpan.config.DarpanSetting"))
        // the list says which keys are managed, and shows an unset optional key as unset
        Map listed = ok(svc("admin.SettingAdminServices.get#Settings", [:])).result.settings.find { it.key == QL_KEY }
        assertEquals(["retention", true, null, null], [listed.managedBy, listed.optional, listed.effectiveValue, listed.defaultValue])
        // the purge days and batch size shrink nothing a person has not been shown a count for, and stay editable
        ok(svc("admin.SettingAdminServices.update#Setting", [settingKey: "darpan.purge.extract.days", value: "5"]))
        assertEquals(5, DarpanSettings.intValue("darpan.purge.extract.days"))
        ok(svc("admin.SettingAdminServices.update#Setting", [settingKey: "darpan.retention.batchSize", value: "200"]))
        assertEquals(200, RetentionSupport.batchSize())
    }

    @Test
    void anOptionalSettingIsUnsetNotZeroAndOnlyReadsThroughItsOwnGetter() {
        assertNull(DarpanSettings.optionalIntValue(QL_KEY))
        assertNull(DarpanSettings.problem(QL_KEY, ""), "blank means unset for an optional key")
        assertNotNull(DarpanSettings.problem(QL_KEY, "0"))
        assertNotNull(DarpanSettings.problem(QL_KEY, "3651"))
        assertNull(DarpanSettings.problem(QL_KEY, "3650"))
        assertThrows(IllegalArgumentException) { DarpanSettings.intValue(QL_KEY) }
        assertThrows(IllegalArgumentException) { DarpanSettings.optionalIntValue("darpan.retention.batchSize") }
        assertNotNull(DarpanSettings.problem("darpan.retention.batchSize", ""), "a required key still refuses blank")
        System.setProperty(QL_KEY, "45")
        DarpanSettings.expire()
        assertEquals(45, DarpanSettings.optionalIntValue(QL_KEY))
    }

    @Test
    void theJobEditorLeavesAPurgePeriodToSettings() {
        [[EXTRACTS, "darpan.purge.extract.days"], [GENERATED, "darpan.purge.generatedOutput.days"]].each { String job, String key ->
            [0, 5].each { Object v ->
                Map out = svc("admin.ScheduleAdminServices.update#ServiceJob", [jobName: job, parameters: [retentionDays: v]])
                assertTrue(out.errors.any { it.contains("Set it in Settings (${key})") }, "${job} ${v}: ${out.errors}")
            }
        }
        assertEquals(0L, count("moqui.service.job.ServiceJobParameter", [parameterName: ["retentionDays"]]))
        assertEquals([], auditRows())
    }

    // ------------------------------------------------------------------ the migration

    @Test
    void anEditedPurgeParameterMovesIntoTheSettingOnceAndTheRetiredStaleRowGoes() {
        asSystem {
            jobParam(GENERATED, "retentionDays", "10")
            jobParam(EXTRACTS, "retentionDays", "7")      // the shipped value: not an edit
            jobParam(REAPER, "staleMinutes", "30")
        }
        Map dry = migrate(true)
        assertEquals(3, dry.rowsUpdated)
        assertEquals(3L, count("moqui.service.job.ServiceJobParameter", [parameterName: ["retentionDays", "staleMinutes"]]), "a dry run writes nothing")
        assertEquals(0, count("darpan.config.DarpanSetting"))

        Map first = migrate(false)
        assertEquals(3, first.rowsUpdated)
        assertEquals(10, DarpanSettings.intValue("darpan.purge.generatedOutput.days"))
        assertEquals(7, DarpanSettings.intValue("darpan.purge.extract.days"))
        assertEquals(["darpan.purge.generatedOutput.days"], settingKeys(), "only the edited one is stored")
        assertEquals(0L, count("moqui.service.job.ServiceJobParameter", [parameterName: ["retentionDays", "staleMinutes"]]))
        assertEquals(1, auditRows().size())
        assertEquals("darpan.purge.generatedOutput.days: 15 -> 10 (moved from the ${GENERATED} job parameter (DAR-BE-306))".toString(), auditRows()[0].detailText)

        Map second = migrate(false)
        assertEquals(0, second.rowsUpdated, "a second run changes nothing")
        assertEquals(["darpan.purge.generatedOutput.days"], settingKeys())
        assertEquals(1, auditRows().size())
    }

    @Test
    void aSettingAlreadySetWinsAndAnInvalidParameterIsLeftInPlace() {
        setSetting("darpan.purge.extract.days", "12")
        asSystem {
            jobParam(EXTRACTS, "retentionDays", "10")
            jobParam(GENERATED, "retentionDays", "400")   // over the 365 day maximum of the setting
        }
        Map out = migrate(false)
        assertEquals(1, out.rowsUpdated)
        assertEquals(12, DarpanSettings.intValue("darpan.purge.extract.days"), "the setting someone chose is not overwritten")
        assertEquals(15, DarpanSettings.intValue("darpan.purge.generatedOutput.days"))
        assertEquals(1L, count("moqui.service.job.ServiceJobParameter", [parameterName: ["retentionDays"]]), "the invalid one stays")
        assertTrue((out.messages as List<String>).any { it.contains("left in place") }, "${out.messages}")
    }

    @Test
    void aParameterThatIsNotAWholeNumberIsLeftInPlaceAndReported() {
        asSystem { jobParam(GENERATED, "retentionDays", "10.0") }
        Map out = migrate(false)
        assertEquals(0, out.rowsUpdated)
        assertEquals(1L, count("moqui.service.job.ServiceJobParameter", [parameterName: ["retentionDays"]]))
        assertEquals(0, count("darpan.config.DarpanSetting"))
        assertTrue((out.messages as List<String>).any { it.contains("left in place: not a whole number") }, "${out.messages}")
    }

    // ------------------------------------------------------------------ helpers

    private Map migrate(boolean dryRun) {
        Map out = ec.service.sync().name(MIGRATION).parameters([dryRun: dryRun]).disableAuthz().call()
        assertTrue(out.ok as boolean, "${out}")
        ec.message.clearErrors()
        return out
    }

    private void jobParam(String job, String name, String value) {
        ec.entity.makeValue("moqui.service.job.ServiceJobParameter").setAll([jobName: job, parameterName: name, parameterValue: value]).createOrUpdate()
    }

    private void setJobPaused(String paused) {
        ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", JOB).useCache(false).one().with { it.paused = paused; it.update() }
    }

    private void queryLog(String id, String tenant, int ageDays) {
        ec.entity.makeValue("darpan.agent.AgentQueryLog").setAll([agentQueryLogId: id, companyUserGroupId: tenant, outcome: "OK",
                createdDate: new Timestamp(System.currentTimeMillis() - ageDays * DAY)]).createOrUpdate()
    }

    private void tenantPeriod(String tenant, Integer days, String table = QUERY_LOG) {
        ec.entity.makeValue("darpan.retention.TenantRetentionPeriod").setAll([tenantUserGroupId: tenant, tableKey: table, periodDays: days]).createOrUpdate()
    }

    private void runResult(String id, String tenant, int ageDays) {
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult").setAll([reconciliationRunResultId: id, companyUserGroupId: tenant,
                statusEnumId: "AUT_STAT_SUCCESS", createdDate: new Timestamp(System.currentTimeMillis() - ageDays * DAY)]).createOrUpdate()
    }

    private List<String> runIds(String tenant) {
        return asSystem { ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("companyUserGroupId", tenant).orderBy("reconciliationRunResultId").useCache(false).list()*.reconciliationRunResultId }
    }

    private void setSetting(String key, String value) {
        asSystem { ec.entity.makeValue("darpan.config.DarpanSetting").setAll([settingKey: key, settingValue: value]).createOrUpdate() }
        DarpanSettings.expire()
    }

    private Map tenantRow(String tenant) {
        Map row = asSystem { ec.entity.find("darpan.retention.TenantRetentionPeriod").condition([tenantUserGroupId: tenant, tableKey: QUERY_LOG]).useCache(false).one()?.getMap() }
        return row == null ? null : new LinkedHashMap(row + [periodDays: row.periodDays == null ? null : (row.periodDays as Integer)])
    }

    private List<String> logIds(String tenant) {
        return asSystem { ec.entity.find("darpan.agent.AgentQueryLog").condition("companyUserGroupId", tenant).orderBy("agentQueryLogId").useCache(false).list()*.agentQueryLogId }
    }

    private long count(String entity, Map where = [:]) {
        return asSystem {
            def find = ec.entity.find(entity).useCache(false)
            where.each { String k, Object v -> find.condition(k, org.moqui.entity.EntityCondition.IN, v) }
            return find.count()
        }
    }

    private List<String> settingKeys() {
        return asSystem { ec.entity.find("darpan.config.DarpanSetting").useCache(false).list()*.settingKey }
    }

    private List auditRows() {
        return ec.entity.find("darpan.admin.AdminAuditLog").orderBy("auditTimestamp").disableAuthz().useCache(false).list()
    }

    /** The scheduled job's way in: its own thread, nobody signed in. */
    private Map runAsJob(String verbNoun, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                holder.result = tec.service.sync().name("retention.RetentionServices.${verbNoun}".toString()).parameters(new LinkedHashMap(params)).disableAuthz().call()
                holder.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "retention-job")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        assertTrue((holder.errors as List).isEmpty(), "${verbNoun}: ${holder.errors}")
        return holder.result as Map
    }

    /** A service as a signed-in user on a thread of its own, authz on. A bare method name is an admin.RetentionAdminServices one. */
    private Map svc(String method, Map params, String username = SUPER) {
        String name = method.contains(".") ? method : R + method
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(name).parameters(new LinkedHashMap(params)).call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors) + (tec.message.validationErrors*.message ?: [])
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "retention-admin-smoke")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static Map ok(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
        return out
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }
}
