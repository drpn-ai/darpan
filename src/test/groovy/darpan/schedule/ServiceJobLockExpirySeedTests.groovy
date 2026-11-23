package darpan.schedule

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-340 (Aditi 2026-11-23: "can we reduce this time... 6 hrs?"). A task killed mid-run leaves its ServiceJobRunLock
 * holding a dead run id; Moqui ignores that lock only after the job's expireLockTime, 1440 minutes when unset. On
 * 2026-11-23 the 15:42 IST task stop held sweep_StuckReconciliationRuns_10m for a day, so six dead runs were never
 * reaped. Every job Darpan ships names its own expiry, at most 6 hours.
 */
class ServiceJobLockExpirySeedTests {
    private static final int MAX_MINUTES = 360

    @Test
    void everyShippedJobLetsADeadLockGoWithinSixHours() {
        Map<String, String> expiry = [:]
        new File("data").listFiles().findAll { it.name.endsWith(".xml") }.each { File f ->
            new XmlSlurper().parse(f).children().findAll { it.name() == "moqui.service.job.ServiceJob" }.each { job ->
                expiry.put(job.@jobName.toString(), job.@expireLockTime.toString())
            }
        }
        assertTrue(expiry.containsKey("sweep_StuckReconciliationRuns_10m"), "the seeds were not read: ${expiry.keySet()}")
        Map<String, String> wrong = expiry.findAll { String name, String minutes ->
            !(minutes ==~ /\d+/) || (minutes as int) < 1 || (minutes as int) > MAX_MINUTES
        }
        assertEquals([:], wrong, "jobs whose expireLockTime is missing or over ${MAX_MINUTES} minutes")
    }
}
