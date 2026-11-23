package darpan.query

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334 review fixes that need no Moqui: the scheduler switch reaches query schedules; recipient lists fit their column. */
class QueryLoadAndDeliveryUnitTests {
    @AfterEach
    void reset() {
        System.clearProperty("scheduled_job_check_time")
        QueryLoad.resetForTests()
    }

    @Test
    void anInstanceWithItsSchedulerOffHoldsQuerySchedules() {
        QueryLoad.probe = { -> [runsQueued: 0, runsRunning: 0, runsCap: 8] }
        assertNull(QueryLoad.busyReason())
        System.setProperty("scheduled_job_check_time", "0")
        assertEquals("scheduled jobs are switched off on this instance", QueryLoad.busyReason())
    }

    @Test
    void aLongRecipientListKeepsWhatFitsAndCountsTheRest() {
        List<String> many = (1..30).collect { "person${it}@example.test" }
        String fitted = QueryDelivery.fitRecipients(many.join(", "))
        assertTrue(fitted.length() <= 255, "${fitted.length()}")
        assertTrue(fitted.startsWith("person1@example.test, person2@example.test"), fitted)
        int kept = fitted.replaceAll(/ \(\+\d+ more\)$/, "").split(", ").size()
        assertTrue(fitted.endsWith("(+${30 - kept} more)".toString()), fitted)
        assertEquals("a@x.test", QueryDelivery.fitRecipients("a@x.test"))
    }
}
