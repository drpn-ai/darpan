package darpan.audit

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals

/**
 * DAR-BE-320. The shared morning read's key and stored result, without Moqui (the single flight itself is proved by
 * AuditMorningsSmokeTests.twoChecksOfOneMorningReadingTheSameSourceShareOneRead).
 */
class AuditSharedReadSupportTests {

    @AfterEach
    void reset() {
        AuditSharedReadSupport.resetForTests()
    }

    /** Two checks share a read only when everything the read depends on agrees: one day or one query apart, they do not. */
    @Test
    void theKeyHoldsEverythingTheReadDependsOn() {
        Timestamp from = Timestamp.valueOf("2026-11-01 00:00:00"), to = Timestamp.valueOf("2026-11-26 00:00:00")
        Timestamp start = Timestamp.valueOf("2026-11-25 00:00:00")
        List<Object> r10 = ["GORJANA", "extract#NetSuiteOrders", "NETSUITE_SUITEQL", '{"parameters":{"nsSuiteQlSourceQueryId":"100256"}}',
                            from, to, start]
        assertEquals(AuditSharedReadSupport.key(r10), AuditSharedReadSupport.key(new ArrayList<Object>(r10)), "R11 reading the same query")
        assertEquals(64, AuditSharedReadSupport.key(r10).length())
        assertNotEquals(AuditSharedReadSupport.key(r10), AuditSharedReadSupport.key(r10[0..5] + [Timestamp.valueOf("2026-11-24 00:00:00")]),
                "a check that lagged a day reads on its own")
        assertNotEquals(AuditSharedReadSupport.key(r10), AuditSharedReadSupport.key(r10[0..2] + ['{"parameters":{"nsSuiteQlSourceQueryId":"100257"}}'] + r10[4..6]),
                "another query")
        assertNotEquals(AuditSharedReadSupport.key(["A", null, "B"]), AuditSharedReadSupport.key(["A", "B", null]), "a part never slides into the next")
    }

    /** The read report takes a side's read window as dates: they survive the stored JSON; other values stay as they were. */
    @Test
    void aStoredResultKeepsItsDates() {
        Timestamp from = Timestamp.valueOf("2026-11-01 00:00:00")
        Map stored = (Map) new JsonSlurper().parseText(JsonOutput.toJson(AuditSharedReadSupport.withDatesKept(
                [readWindowFrom: from, recordCount: 12, requestMetadata: [calls: [suiteQl: 30]], fileLocation: "x"])))
        Map back = AuditSharedReadSupport.withDatesRestored(stored)
        assertEquals(from, back.readWindowFrom)
        assertEquals(12, back.recordCount)
        assertEquals([calls: [suiteQl: 30]], back.requestMetadata)
        assertEquals([:], AuditSharedReadSupport.withDatesRestored(null))
    }
}
