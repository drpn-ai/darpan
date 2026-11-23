package darpan.query

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334 review fix that needs no Moqui: a long recipient list fits the export log's column. */
class QueryDeliveryUnitTests {
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
