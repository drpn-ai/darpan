package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-373. get#AuditRecheckProgress takes verify#AuditFindings' checkedSince back as the page holds it: JSON gives the
 * page epoch millis, so `since` arrives as a Long (prod 2026-11-26: "No signature of method ... progress ... Long").
 * Pure: no Moqui.
 */
class AuditRecheckSinceTests {

    @Test
    void sinceReadsAsTheSameInstantWhateverShapeItCameBackIn() {
        Timestamp at = new Timestamp(1795677894000L)
        assertEquals(at, AuditFindingsVerifySupport.sinceStamp(1795677894000L), "a Long, as the page sends it")
        assertEquals(at, AuditFindingsVerifySupport.sinceStamp(at))
        assertEquals(at, AuditFindingsVerifySupport.sinceStamp("1795677894000"))
        assertEquals(at, AuditFindingsVerifySupport.sinceStamp(at.toInstant().toString()))
        assertNull(AuditFindingsVerifySupport.sinceStamp(null))
        assertNull(AuditFindingsVerifySupport.sinceStamp("not a time"))
    }
}
