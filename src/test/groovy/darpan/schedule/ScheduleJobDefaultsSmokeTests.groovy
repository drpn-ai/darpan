package darpan.schedule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import javax.xml.parsers.DocumentBuilderFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-305. Seeds state a job's defaults only; schedule.ScheduleServices.apply#JobDefaults (the last data file of every
 * load) copies them onto the live columns of jobs nobody edited. A production edit survives a seed reload; a changed
 * default reaches an unedited job.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class ScheduleJobDefaultsSmokeTests {
    private static final String APPLY = "component://darpan/data/zzScheduleJobDefaultsApply.xml"
    /** Every data file that seeds a ServiceJob (seedRowsNeverStateLiveColumns counts them, so a new one cannot slip past). */
    private static final List<String> JOB_SEEDS = ["AuditSeedData", "OpsSeedData", "ReconciliationJobSeedData", "TwoFactorSeedData",
            "TenantConfigSeedData"].collect { "component://darpan/data/${it}.xml".toString() }
    /** The ServiceJob rows those files seed: 1 audit, 2 ops, 9 reconciliation, 1 two-factor, 1 tenant config. */
    private static final int JOB_COUNT = 14
    private static final String EXTRACTS = "purge_ReconciliationExtractFiles_daily"
    private static final String SCAN = "scan_ReconciliationAutomations_5m"

    private ExecutionContext ec
    private Path backendRoot

    @BeforeAll
    void setup() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "schedule-job-defaults")
        // TwoFactorSeedData also seeds email templates that need the SYSTEM email server (PasswordResetEmailSeedData)
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/PasswordResetEmailSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, *(JOB_SEEDS + [APPLY]))
    }

    @AfterAll
    void tearDown() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    private List<EntityValue> darpanJobs() {
        asSystem { ec.entity.find("moqui.service.job.ServiceJob").condition("defaultCronExpression", org.moqui.entity.EntityCondition.NOT_EQUAL, null).list() }
    }

    @Test
    void freshLoadSchedulesEveryJob() {
        List<EntityValue> jobs = darpanJobs()
        assertEquals(JOB_COUNT, jobs.size(), jobs*.jobName.toString())
        jobs.each { EntityValue j ->
            assertEquals(j.defaultCronExpression, j.cronExpression, j.jobName)
            assertEquals(j.defaultPaused ?: "N", j.paused, j.jobName)
            assertNotNull(j.minIntervalMinutes, j.jobName)
            assertNull(j.editedDate, j.jobName)
        }
        assertEquals("Y", ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "run_DataRetention_daily").one().paused)
        assertEquals("retention.RetentionServices.dryRun#Retention",
                ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "run_DataRetention_daily").one().resumePreviewService)
        List<EntityValue> params = asSystem { ec.entity.find("moqui.service.job.ServiceJobParameter").condition("defaultValue", org.moqui.entity.EntityCondition.NOT_EQUAL, null).list() }
        assertEquals(1, params.size(), "the scan limit; the purge periods are settings (DAR-BE-306)")
        params.each { assertEquals(it.defaultValue, it.parameterValue, it.parameterName) }
    }

    @Test
    void anEditSurvivesASeedReload() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        try {
            asSystem {
                ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: EXTRACTS, cronExpression: "0 0 4 * * ?", paused: "Y", editedDate: now]).update()
                ec.entity.makeValue("moqui.service.job.ServiceJobParameter").setAll([jobName: SCAN, parameterName: "limit", parameterValue: "50", editedDate: now]).update()
            }
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)
            EntityValue job = ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", EXTRACTS).one()
            assertEquals("0 0 4 * * ?", job.cronExpression)
            assertEquals("Y", job.paused)
            assertEquals("50", ec.entity.find("moqui.service.job.ServiceJobParameter").condition("jobName", SCAN).condition("parameterName", "limit").one().parameterValue)
            // and the shipped default moved on underneath without touching the edit
            assertEquals("0 15 2 * * ?", job.defaultCronExpression)
        } finally {
            // leave the jobs unedited for the other tests (a reset: clear the marker, let the defaults apply)
            asSystem {
                ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: EXTRACTS, editedDate: null]).update()
                ec.entity.makeValue("moqui.service.job.ServiceJobParameter").setAll([jobName: SCAN, parameterName: "limit", editedDate: null]).update()
                ec.service.sync().name("schedule.ScheduleServices.apply#JobDefaults").call()
            }
        }
    }

    @Test
    void aSeedLoadNeverResumesADeletingJob() {
        String retention = "run_DataRetention_daily"
        asSystem { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: retention, defaultPaused: "N"]).update() }
        try {
            asSystem { ec.service.sync().name("schedule.ScheduleServices.apply#JobDefaults").call() }
            assertEquals("Y", ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", retention).one().paused)
        } finally {
            asSystem { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: retention, defaultPaused: "Y"]).update() }
        }
    }

    @Test
    void aChangedDefaultReachesAnUneditedJob() {
        String job = "sweep_RuleChildTenantStamps_hourly"
        try {
            asSystem { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: job, defaultCronExpression: "0 0 5 * * ?"]).update() }
            Map out = asSystem { ec.service.sync().name("schedule.ScheduleServices.apply#JobDefaults").call() }
            assertEquals("0 0 5 * * ?", ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", job).one().cronExpression)
            assertEquals(1, out.jobsUpdated)
            assertEquals(0, out.parametersUpdated)
            // a second call changes nothing
            assertEquals(0, asSystem { ec.service.sync().name("schedule.ScheduleServices.apply#JobDefaults").call() }.jobsUpdated)
        } finally {
            // restore the shipped default for later tests
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)
        }
        assertEquals("0 15 * * * ?", ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", job).one().cronExpression)
    }

    /** DAR-OPS-029 S34 (OC62 step 2): prod loaded sweep_RuleChildTenantStamps_hourly running (defaultPaused N, paused N).
     *  The seed now ships defaultPaused="Y" and states no live column, so the next seed load must pause it through
     *  apply#JobDefaults, as long as nobody edited the job, and leave it unedited. */
    @Test
    void aJobRetiredByItsDefaultIsPausedByTheNextSeedLoad() {
        String job = "sweep_RuleChildTenantStamps_hourly"
        Closure<Map> row = { ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", job).one() }
        try {
            asSystem { ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: job, defaultPaused: "N", paused: "N"]).update() }
            assertEquals("N", row().paused, "precondition: the job runs, as loaded on prod before the retirement")

            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)

            assertEquals("Y", row().defaultPaused, "the seed load carries the new default")
            assertEquals("Y", row().paused, "apply#JobDefaults pauses an unedited job whose default moved to paused")
            assertNull(row().editedDate, "pausing by default is not an edit")
            assertEquals("0 15 * * * ?", row().cronExpression)
        } finally {
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml", APPLY)
        }
    }

    @Test
    void seedRowsNeverStateLiveColumns() {
        // allow-list over every loaded type (seed, seed-initial, install, ext*, darpan-seed...); only fixtures are skipped
        int checked = 0
        int checkedParameters = 0
        dataFiles().each { File f ->
            def doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            if (doc.documentElement.getAttribute("type").endsWith("fixture")) return
            checked += checkAttributes(f, doc, "moqui.service.job.ServiceJob") { String a ->
                // DAR-BE-340: expireLockTime is a shipped value (no Darpan surface edits it), so a seed states it.
                a in ["jobName", "description", "serviceName", "minIntervalMinutes", "resumePreviewService", "expireLockTime"] || a.startsWith("default") }
            checkedParameters += checkAttributes(f, doc, "moqui.service.job.ServiceJobParameter") { String a ->
                a in ["jobName", "parameterName", "defaultValue"] }
        }
        assertEquals(JOB_COUNT, checked, "every seeded job is scanned, and JOB_SEEDS names every file that seeds one")
        assertEquals(1, checkedParameters, "the scan limit is the one seeded job parameter")
    }

    private static int checkAttributes(File f, def doc, String element, Closure<Boolean> allowed) {
        def nodes = doc.getElementsByTagName(element)
        for (int i = 0; i < nodes.length; i++) {
            def attrs = nodes.item(i).attributes
            for (int j = 0; j < attrs.length; j++) {
                String a = attrs.item(j).nodeName
                assertTrue(allowed.call(a), "${f.name}: ${element} states ${a}; seeds state defaults only")
            }
        }
        return nodes.length
    }

    @Test
    void applyFileLoadsLast() {
        List<String> names = dataFiles()*.name
        String apply = "zzScheduleJobDefaultsApply.xml"
        assertTrue(names.contains(apply))
        names.findAll { it != apply }.each { assertTrue(it.compareTo(apply) < 0, "${it} must sort before ${apply}") }
        assertTrue(names.contains("upgrade-data.xml"))
    }

    private List<File> dataFiles() {
        File dir = backendRoot.resolve("runtime/component/darpan/data").toFile()
        dir.listFiles().findAll { it.isFile() && it.name.endsWith(".xml") }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }
}
