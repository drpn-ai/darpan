package darpan.admin

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
import org.moqui.entity.EntityValue

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem

/**
 * DAR-BE-305: admin.ScheduleAdminServices job services through the real service engine, authz ON, as a real
 * DARPAN_SUPER_ADMIN on a thread of its own. Edge cases: a framework job is read-only, a cron that parses but bursts under
 * the job's floor saves nothing, a parameter off the service or of the wrong type saves nothing, an edit survives a seed reload,
 * resuming a deleting job needs a preview that still matches today, a reset restores the shipped defaults, and only a super
 * admin gets in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleAdminServicesSmokeTests {
    private static final String APPLY = "component://darpan/data/zzScheduleJobDefaultsApply.xml"
    private static final List<String> JOB_SEEDS = ["AuditSeedData", "ReconciliationJobSeedData", "TwoFactorSeedData",
            "TenantConfigSeedData"].collect { "component://darpan/data/${it}.xml".toString() }
    private static final String S = "admin.ScheduleAdminServices."
    private static final String EXTRACTS = "purge_ReconciliationExtractFiles_daily"
    private static final String SCAN = "scan_ReconciliationAutomations_5m"
    private static final String RETENTION = "run_DataRetention_daily"
    private static final String FRAMEWORK = "SCHJ_framework_job"
    private static final String TENANT = "SCHJ_T"
    private static final String SUPER_ID = "SCHJ_SUPER"
    private static final String SUPER = "schj.super"
    private static final String TENANT_ADMIN_ID = "SCHJ_TADMIN"
    private static final String TENANT_ADMIN = "schj.tadmin"
    private static final String DADMIN_ID = "SCHJ_DADMIN"
    private static final String DADMIN = "schj.dadmin"
    private static final String PLAIN_ID = "SCHJ_PLAIN"
    private static final String PLAIN = "schj.plain"
    private static final long DAY = 86_400_000L

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "schedule-admin-services")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/PasswordResetEmailSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, *(JOB_SEEDS + [APPLY]))
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem(ec) {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: "Sched Co",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() {
        System.clearProperty("darpan.retention.agentQueryLog.days")
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetFixtures() {
        asSystem(ec) {
            ReconciliationSmokeTestSupport.loadSeedData(ec, *(JOB_SEEDS + [APPLY]))
            // back to the shipped state: unedited, defaults applied, no extra parameter rows
            ec.entity.find("moqui.service.job.ServiceJob").condition("defaultCronExpression", org.moqui.entity.EntityCondition.NOT_EQUAL, null).useCache(false).list().each {
                it.editedDate = null; it.editedByUserId = null; it.update()
            }
            ec.entity.find("moqui.service.job.ServiceJobParameter").condition("jobName", SCAN).condition("parameterName", "sparkAppName").deleteAll()
            ec.entity.find("moqui.service.job.ServiceJobParameter").condition("defaultValue", org.moqui.entity.EntityCondition.NOT_EQUAL, null).useCache(false).list().each {
                it.editedDate = null; it.editedByUserId = null; it.update()
            }
            ec.service.sync().name("schedule.ScheduleServices.apply#JobDefaults").call()
            ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: FRAMEWORK, description: "a framework job",
                    serviceName: "reconciliation.ReconciliationAutomationServices.scan#DueAutomations", cronExpression: "0 0 1 * * ?", paused: "N"]).createOrUpdate()
            ec.entity.find("moqui.service.job.ServiceJobRunLock").deleteAll()
            ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobName", EXTRACTS).deleteAll()
            ec.entity.find("darpan.agent.AgentQueryLog").condition("companyUserGroupId", TENANT).deleteAll()
        }
        System.clearProperty("darpan.retention.agentQueryLog.days")
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
    void clearProps() { System.clearProperty("darpan.retention.agentQueryLog.days") }

    // ------------------------------------------------------------------ list

    @Test
    void theListShowsEverySeededJobWithItsDefaultsAndNextRuns() {
        asSystem(ec) {
            ec.entity.makeValue("moqui.service.job.ServiceJobRun").setAll([jobRunId: "SCHJ_RUN_1", jobName: EXTRACTS,
                    startTime: new Timestamp(System.currentTimeMillis() - 3600_000L), endTime: new Timestamp(System.currentTimeMillis() - 3500_000L), hasError: "N"]).createOrUpdate()
        }
        List<Map> jobs = ok(svc("get#ServiceJobs", [:])).result.jobs
        List<Map> mine = jobs.findAll { it.defaultCronExpression != null }
        assertEquals(12, mine.size(), mine*.jobName.toString())   // the 12 jobs of the four job seeds loaded here (OpsSeedData's two are not loaded)
        mine.each { Map j ->
            assertTrue(j.editable, j.jobName)
            assertFalse(j.edited, j.jobName)
            assertEquals(j.defaultCronExpression, j.cronExpression, j.jobName)
            assertEquals(3, j.nextRuns.size(), j.jobName)
            assertNotNull(j.minIntervalMinutes, j.jobName)
        }
        Map retention = mine.find { it.jobName == RETENTION }
        assertEquals("Y", retention.paused)
        assertTrue(retention.resumeNeedsPreview)
        assertFalse(mine.find { it.jobName == SCAN }.resumeNeedsPreview)
        Map scan = mine.find { it.jobName == SCAN }
        Map limit = scan.parameters.find { it.parameterName == "limit" }
        assertEquals([parameterName: "limit", value: "100", defaultValue: "100", edited: false, type: "Integer"], limit)
        Map extracts = mine.find { it.jobName == EXTRACTS }
        assertNotNull(extracts.lastRun.startTime)
        assertEquals("N", extracts.lastRun.hasError)
        assertNull(scan.lastRun)
        Map fw = jobs.find { it.jobName == FRAMEWORK }
        assertFalse(fw.editable)
        assertNull(fw.defaultCronExpression)
        assertEquals("0 0 1 * * ?", fw.cronExpression)
    }

    @Test
    void aCronCheckShowsTheNextRunsAndTheTightestGapAndSavesNothing() {
        Map fine = ok(svc("preview#JobSchedule", [jobName: EXTRACTS, cronExpression: "0 0 4 * * ?"])).result
        assertNull(fine.problem)
        assertEquals(5, fine.nextRuns.size())
        assertEquals(1440, fine.smallestGapMinutes)
        Map burst = ok(svc("preview#JobSchedule", [jobName: EXTRACTS, cronExpression: "0 * 3 * * ?"])).result
        assertTrue(burst.problem.contains("at most every 360 minutes"), burst.problem)
        assertEquals("0 15 2 * * ?", job(EXTRACTS).cronExpression)
        assertEquals([], auditRows())
    }

    // ------------------------------------------------------------------ edit

    @Test
    void aFrameworkJobIsListedButNeverEdited() {
        Map out = svc("update#ServiceJob", [jobName: FRAMEWORK, cronExpression: "0 0 4 * * ?"])
        assertTrue(out.hasError)
        assertTrue(out.errors.any { it.contains("cannot be edited") }, out.errors.toString())
        assertEquals("0 0 1 * * ?", job(FRAMEWORK).cronExpression)
        assertTrue(svc("reset#ServiceJob", [jobName: FRAMEWORK]).hasError)
        assertTrue(svc("update#ServiceJob", [jobName: "nope_job", paused: "Y"]).errors.any { it.contains("No job") })
        assertEquals([], auditRows())
    }

    @Test
    void aCronEditSavesMarksItAuditsItAndSurvivesASeedReload() {
        Map out = ok(svc("update#ServiceJob", [jobName: EXTRACTS, cronExpression: " 0 0 4 * * ? "])).result
        assertEquals("0 0 4 * * ?", out.job.cronExpression)
        assertTrue(out.job.edited)
        EntityValue row = job(EXTRACTS)
        assertEquals("0 0 4 * * ?", row.cronExpression)
        assertEquals(SUPER_ID, row.editedByUserId)
        assertNotNull(row.editedDate)
        assertEquals("N", row.paused)
        List audit = auditRows()
        assertEquals(1, audit.size())
        assertEquals("admin.ScheduleAdminServices.update#ServiceJob", audit[0].serviceName)
        assertEquals(EXTRACTS, audit[0].targetId)
        assertEquals("cron: 0 15 2 * * ? -> 0 0 4 * * ?", audit[0].detailText)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)
        assertEquals("0 0 4 * * ?", job(EXTRACTS).cronExpression)
        // saying the same thing again changes nothing and writes no second audit row
        ok(svc("update#ServiceJob", [jobName: EXTRACTS, cronExpression: "0 0 4 * * ?"]))
        assertEquals(1, auditRows().size())
    }

    @Test
    void aCronThatBurstsOrNeverRunsOrIsBlankSavesNothing() {
        ["0 * 3 * * ?"  : "this job may run at most every 360 minutes.",
         "0 0 0 30 2 ?" : "This cron never runs within 400 days.",
         "* * * * *"    : "Not a valid Quartz cron",
         "   "          : "The cron is blank; pause the job instead of clearing its cron.",
         "0 0 2,3 * * ?": "this job may run at most every 360 minutes."].each { String cron, String reason ->
            Map out = svc("update#ServiceJob", [jobName: EXTRACTS, cronExpression: cron, paused: "Y"])
            assertTrue(out.hasError, "'${cron}' must be refused")
            assertTrue(out.errors.any { it.contains(reason) }, "'${cron}': expected '${reason}', got ${out.errors}")
        }
        EntityValue row = job(EXTRACTS)
        assertEquals("0 15 2 * * ?", row.cronExpression)
        assertEquals("N", row.paused, "a refused edit saves none of its parts")
        assertNull(row.editedDate)
        assertEquals([], auditRows())
    }

    @Test
    void aParameterOffTheServiceOrOfTheWrongTypeOrBlankSavesNothing() {
        [[[bogus: "1"], "bogus is not a parameter of reconciliation.ReconciliationAutomationServices.scan#DueAutomations; it takes "],
         [[limit: "abc"], "limit must be Integer; abc is not."],
         [[limit: ""], "limit is blank; reset it to its default instead."],
         [[limit: "  "], "limit is blank; reset it to its default instead."]].each { List c ->
            Map p = (Map) c[0]
            String reason = (String) c[1]
            Map out = svc("update#ServiceJob", [jobName: SCAN, cronExpression: "0 0/10 * * * ?", parameters: p])
            assertTrue(out.hasError, "${p} must be refused")
            assertTrue(out.errors.any { it.contains(reason) }, "${p}: expected '${reason}', got ${out.errors}")
        }
        EntityValue row = job(SCAN)
        assertEquals("0 0/5 * * * ?", row.cronExpression, "the cron in the same call is not saved either")
        assertNull(row.editedDate)
        assertEquals("100", param(SCAN, "limit").parameterValue)
        assertEquals([], auditRows())
        assertTrue(svc("update#ServiceJob", [jobName: SCAN, parameters: [bogus: "1"]]).errors.any { it.contains("bogus") })
        assertTrue(svc("update#ServiceJob", [jobName: SCAN, parameters: [limit: "abc"]]).errors.any { it.contains("Integer") })
    }

    @Test
    void aParameterEditIsMarkedAuditedAndSurvivesAReload() {
        Map out = ok(svc("update#ServiceJob", [jobName: SCAN, parameters: [limit: "50"]])).result
        assertEquals("50", out.job.parameters.find { it.parameterName == "limit" }.value)
        assertTrue(out.job.parameters.find { it.parameterName == "limit" }.edited)
        assertEquals("50", param(SCAN, "limit").parameterValue)
        assertEquals(SUPER_ID, param(SCAN, "limit").editedByUserId)
        assertEquals("limit: 100 -> 50", auditRows()[0].detailText)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)
        assertEquals("50", param(SCAN, "limit").parameterValue)
        assertEquals("100", param(SCAN, "limit").defaultValue)
        // a parameter the service takes but the seed has no row for is created, with no default
        ok(svc("update#ServiceJob", [jobName: SCAN, parameters: [sparkAppName: "darpan-scan"]]))
        assertEquals("darpan-scan", param(SCAN, "sparkAppName").parameterValue)
        assertNull(param(SCAN, "sparkAppName").defaultValue)
    }

    @Test
    void aCronChangeCountsAsARunMadeNowSoTheRunnerDoesNotCatchUpAtOnce() {
        Timestamp before = new Timestamp(System.currentTimeMillis() - 1000L)
        asSystem(ec) { ec.entity.find("moqui.service.job.ServiceJobRunLock").condition("jobName", EXTRACTS).deleteAll() }
        ok(svc("update#ServiceJob", [jobName: EXTRACTS, paused: "Y"]))
        assertNull(lock(EXTRACTS), "a pause change is not a cron change")
        ok(svc("update#ServiceJob", [jobName: EXTRACTS, cronExpression: "0 0 4 * * ?"]))
        assertNotNull(lock(EXTRACTS))
        assertFalse(lock(EXTRACTS).lastRunTime.before(before), "lastRunTime is now, so a time already past today is not a missed run")
        // an existing lock keeps its jobRunId and gets a new lastRunTime
        asSystem(ec) {
            ec.entity.makeValue("moqui.service.job.ServiceJobRun").setAll([jobRunId: "SCHJ_RUN", jobName: EXTRACTS]).createOrUpdate()
            ec.entity.makeValue("moqui.service.job.ServiceJobRunLock").setAll([jobName: EXTRACTS, jobRunId: "SCHJ_RUN", lastRunTime: new Timestamp(0L)]).update()
        }
        ok(svc("update#ServiceJob", [jobName: EXTRACTS, cronExpression: "0 0 5 * * ?"]))
        assertEquals("SCHJ_RUN", lock(EXTRACTS).jobRunId)
        assertFalse(lock(EXTRACTS).lastRunTime.before(before))
        // a reset that moves the cron back does the same
        asSystem(ec) { ec.entity.makeValue("moqui.service.job.ServiceJobRunLock").setAll([jobName: EXTRACTS, lastRunTime: new Timestamp(0L)]).update() }
        ok(svc("reset#ServiceJob", [jobName: EXTRACTS]))
        assertFalse(lock(EXTRACTS).lastRunTime.before(before))
    }

    @Test
    void thePurgeFolderAndPeriodAreNotJobParameters() {
        String service = "reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles"
        ["outputLocation": "not editable here", "retentionDays": "is a setting"].each { String name, String why ->
            Map out = [:]
            asSystem(ec) { out = ec.service.sync().name("schedule.ScheduleServices.check#JobParameter")
                    .parameters([serviceName: service, parameterName: name, value: "x"]).call() }
            assertTrue((out.problem as String)?.contains(why), "${name}: ${out.problem}")
        }
        Map extract = [:]
        asSystem(ec) { extract = ec.service.sync().name("schedule.ScheduleServices.check#JobParameter")
                .parameters([serviceName: "reconciliation.ReconciliationGenericServices.purge#ExtractFiles", parameterName: "retentionDays", value: "3"]).call() }
        assertTrue((extract.problem as String)?.contains("is a setting, not a job parameter. Set it in Settings (darpan.purge.extract.days)"),
                "the extract purge's period: ${extract.problem}")
    }

    @Test
    void aBooleanParameterTakesOnlyTrueFalseYOrN() {
        String service = "reconciliation.ReconciliationGenericServices.reconcile#GenericFiles"
        Closure<String> problem = { String v ->
            Map out = [:]
            asSystem(ec) { out = ec.service.sync().name("schedule.ScheduleServices.check#JobParameter")
                    .parameters([serviceName: service, parameterName: "hasHeader", value: v]).call() }
            return out.problem as String
        }
        ["true", "FALSE", "y", "N", " Y "].each { assertNull(problem(it), it) }
        ["maybe", "1", "yes", ""].each { assertNotNull(problem(it), "'${it}'") }
    }

    @Test
    void aResetTouchesOnlyItsOwnJob() {
        String other = "sweep_RuleChildTenantStamps_hourly"
        ok(svc("update#ServiceJob", [jobName: SCAN, cronExpression: "0 0/10 * * * ?"]))
        // a shipped default moved on for a different, unedited job; resetting the scan job must not apply it
        asSystem(ec) { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: other, defaultCronExpression: "0 0 5 * * ?"]).update() }
        try {
            ok(svc("reset#ServiceJob", [jobName: SCAN]))
            assertEquals("0 15 * * * ?", job(other).cronExpression)
        } finally {
            asSystem(ec) { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: other, defaultCronExpression: "0 15 * * * ?"]).update() }
        }
    }

    // ------------------------------------------------------------------ pause and resume

    @Test
    void pausingNeedsNoPreviewAndResumingAJobWithoutOneNeedsNone() {
        Map out = ok(svc("update#ServiceJob", [jobName: SCAN, paused: "Y"])).result
        assertEquals("Y", out.job.paused)
        assertEquals("paused: N -> Y", auditRows()[0].detailText)
        ok(svc("update#ServiceJob", [jobName: SCAN, paused: "N"]))
        assertEquals("N", job(SCAN).paused)
        assertTrue(svc("update#ServiceJob", [jobName: SCAN, paused: "maybe"]).hasError)
    }

    @Test
    void resumeNeedsAFreshPreview() {
        System.setProperty("darpan.retention.agentQueryLog.days", "30")
        asSystem(ec) { (1..300).each { n ->
            ec.entity.makeValue("darpan.agent.AgentQueryLog").setAll([agentQueryLogId: "SCHJ_Q_${n}".toString(), companyUserGroupId: TENANT,
                    outcome: "OK", createdDate: new Timestamp(System.currentTimeMillis() - 400L * DAY)]).createOrUpdate()
        } }
        // no preview
        Map none = svc("update#ServiceJob", [jobName: RETENTION, paused: "N"])
        assertTrue(none.hasError)
        assertTrue(none.errors.any { it.contains("Preview first") }, none.errors.toString())
        assertEquals("Y", job(RETENTION).paused)
        // a preview far below today's count
        Map low = svc("update#ServiceJob", [jobName: RETENTION, paused: "N", previewedRows: 10L])
        assertTrue(low.hasError)
        assertTrue(low.errors.any { it.contains("The numbers changed since your preview; preview again.") }, low.errors.toString())
        assertEquals("Y", job(RETENTION).paused)
        assertNull(job(RETENTION).editedDate)
        assertEquals([], auditRows())
        assertEquals(300, ec.entity.find("darpan.agent.AgentQueryLog").condition("companyUserGroupId", TENANT).disableAuthz().count(), "nothing was deleted")
        // matching, and a little above (inside the 100 row tolerance)
        Map go = ok(svc("update#ServiceJob", [jobName: RETENTION, paused: "N", previewedRows: 250L])).result
        assertEquals("N", go.job.paused)
        assertEquals("N", job(RETENTION).paused)
        assertEquals(SUPER_ID, job(RETENTION).editedByUserId)
        assertEquals("paused: Y -> N (preview showed 250 rows, today's dry run counts 300)", auditRows()[0].detailText)
        assertEquals(300, ec.entity.find("darpan.agent.AgentQueryLog").condition("companyUserGroupId", TENANT).disableAuthz().count(), "resuming deletes nothing itself")
    }

    @Test
    void pausingARetentionJobNeedsNoPreview() {
        ok(svc("update#ServiceJob", [jobName: RETENTION, paused: "N", previewedRows: 0L]))   // nothing to delete: any preview of 0 passes
        assertEquals("N", job(RETENTION).paused)
        ok(svc("update#ServiceJob", [jobName: RETENTION, paused: "Y"]))
        assertEquals("Y", job(RETENTION).paused)
    }

    // ------------------------------------------------------------------ reset

    @Test
    void aResetRestoresTheDefaultsAndClearsTheMarker() {
        ok(svc("update#ServiceJob", [jobName: SCAN, cronExpression: "0 0/10 * * * ?", paused: "Y", parameters: [limit: "50", sparkAppName: "x"]]))
        Map out = ok(svc("reset#ServiceJob", [jobName: SCAN])).result
        EntityValue row = job(SCAN)
        assertEquals("0 0/5 * * * ?", row.cronExpression)
        assertEquals("N", row.paused)
        assertNull(row.editedDate)
        assertNull(row.editedByUserId)
        assertFalse(out.job.edited)
        assertEquals("100", param(SCAN, "limit").parameterValue)
        assertNull(param(SCAN, "limit").editedDate)
        assertNull(param(SCAN, "sparkAppName"), "a parameter with no default is deleted")
        List audit = auditRows()
        assertEquals("admin.ScheduleAdminServices.reset#ServiceJob", audit.last().serviceName)
        assertEquals("cron: 0 0/10 * * * ? -> 0 0/5 * * * ?; paused: Y -> N; limit: 50 -> 100; sparkAppName: x -> (removed)", audit.last().detailText)
        // a reset of an unedited job changes nothing and writes no audit row
        int before = auditRows().size()
        ok(svc("reset#ServiceJob", [jobName: SCAN]))
        assertEquals(before, auditRows().size())
    }

    @Test
    void aPartialResetRestoresOnlyTheNamedParameters() {
        ok(svc("update#ServiceJob", [jobName: SCAN, cronExpression: "0 0/10 * * * ?", parameters: [limit: "50"]]))
        ok(svc("reset#ServiceJob", [jobName: SCAN, parameterNames: ["limit"]]))
        assertEquals("100", param(SCAN, "limit").parameterValue)
        assertNull(param(SCAN, "limit").editedDate)
        assertEquals("0 0/10 * * * ?", job(SCAN).cronExpression, "the cron edit stays")
        assertNotNull(job(SCAN).editedDate)
        assertTrue(svc("reset#ServiceJob", [jobName: SCAN, parameterNames: ["bogus"]]).hasError)
    }

    @Test
    void aResetThatWouldResumeADeletingJobNeedsAPreviewToo() {
        ok(svc("update#ServiceJob", [jobName: RETENTION, paused: "N", previewedRows: 0L]))
        ok(svc("update#ServiceJob", [jobName: RETENTION, paused: "Y"]))
        // default is paused, so a reset pauses it: no preview needed
        ok(svc("reset#ServiceJob", [jobName: RETENTION]))
        assertEquals("Y", job(RETENTION).paused)
        // flip the shipped default to running: a reset would resume
        asSystem(ec) { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: RETENTION, defaultPaused: "N"]).update() }
        try {
            ok(svc("update#ServiceJob", [jobName: RETENTION, cronExpression: "0 30 4 * * ?"]))
            Map out = svc("reset#ServiceJob", [jobName: RETENTION])
            assertTrue(out.hasError)
            assertTrue(out.errors.any { it.contains("Preview first") }, out.errors.toString())
            assertEquals("0 30 4 * * ?", job(RETENTION).cronExpression)
            ok(svc("reset#ServiceJob", [jobName: RETENTION, previewedRows: 0L]))
            assertEquals("N", job(RETENTION).paused)
        } finally {
            asSystem(ec) { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: RETENTION, defaultPaused: "Y"]).update() }
        }
    }

    // ------------------------------------------------------------------ authz

    @Test
    void onlyASuperAdminGetsIn() {
        ["get#ServiceJobs": [:], "preview#JobSchedule": [jobName: EXTRACTS, cronExpression: "0 0 4 * * ?"],
         "update#ServiceJob": [jobName: EXTRACTS, cronExpression: "0 0 4 * * ?"], "reset#ServiceJob": [jobName: EXTRACTS]].each { String method, Map params ->
            [PLAIN, TENANT_ADMIN, DADMIN].each { String who ->
                Map out = svc(method, params, who)
                // refused by the admin.* artifact fence, or else by the service's own super-admin check: no other error counts
                assertTrue(out.authzDenied != null || out.errors.contains(AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE),
                        "${method} must refuse ${who}: ${out.authzDenied} ${out.errors}")
                // and nothing is read out on the way
                assertFalse(out.result?.jobs as boolean, "${method} leaks no job to ${who}: ${out.result?.jobs}")
                assertNull(out.result?.nextRuns, "${method} leaks no next runs to ${who}")
                assertNull(out.result?.job, "${method} returns no job to ${who}")
            }
        }
        assertEquals("0 15 2 * * ?", job(EXTRACTS).cronExpression)
        assertNull(job(EXTRACTS).editedDate)
        assertEquals([], auditRows())
    }

    // ------------------------------------------------------------------ helpers

    private EntityValue job(String name) {
        return ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", name).disableAuthz().useCache(false).one()
    }

    private EntityValue lock(String name) {
        return ec.entity.find("moqui.service.job.ServiceJobRunLock").condition("jobName", name).disableAuthz().useCache(false).one()
    }

    private EntityValue param(String jobName, String parameterName) {
        return ec.entity.find("moqui.service.job.ServiceJobParameter").condition("jobName", jobName).condition("parameterName", parameterName)
                .disableAuthz().useCache(false).one()
    }

    private List auditRows() {
        return ec.entity.find("darpan.admin.AdminAuditLog").orderBy("auditTimestamp").disableAuthz().useCache(false).list()
    }

    private Map svc(String method, Map params, String username = SUPER) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(S + method).parameters(new LinkedHashMap(params)).call()
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
        } as Runnable, "schedule-admin-smoke")
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
}
