package darpan.audit

import darpan.reconciliation.support.SharedMoqui
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * DAR-OPS-029 S13, OC74. A play of past days (DAR-BE-178) records mornings out of order. The rule: a finding's current
 * state, close fields and evidence follow its LATEST check event by eventDate (ties by eventStamp). A back-dated morning
 * writes its own history row, so the past report for that day reads right, but never overturns a later day.
 * lastSeenDate only moves forward and firstFoundDate only back. Own record keys per test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AuditLedgerBackdatedPlaySmokeTests {

    private static final String SVC = "audit.AuditLedgerServices"
    private static final String TENANT = "TLBD"
    private static final String CHECK = "TLBD_CHECK"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-ledger-backdated")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        insert("moqui.security.UserGroup", [userGroupId: TENANT, description: TENANT])
        insert("darpan.audit.Audit", [auditId: "TLBD_AUDIT", companyUserGroupId: TENANT, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.AuditStatement", [statementId: "TLBD_R4", companyUserGroupId: TENANT, auditId: "TLBD_AUDIT",
                statementCode: "R4", categoryEnumId: "AUDC_REFUNDS_CREDITS", weightEnumId: "AUDSW_DERIVED", statementText: "R4 text"])
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: TENANT, checkCode: "backdated", label: "backdated"])
        insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TLBD_R4", companyUserGroupId: TENANT])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    /** The case S10's QA found: a play of 10-21 run after the 10-22 morning had fixed the finding. */
    @Test
    void aBackdatedReopenWritesItsDayButTheLaterFixStands() {
        String id = (record("2026-10-20", "BD-1", "Found on the 20th").findingIds as List)[0]
        close("2026-10-22", ["BD-1"])
        assertEquals("AUDFS_FIXED", row(id).stateEnumId, "set-up: fixed on the 22nd")
        Timestamp closedAt = row(id).closedStamp as Timestamp

        record("2026-10-21", "BD-1", "Seen again on the 21st")

        Map f = row(id)
        assertEquals(["AUDFS_FIXED", closedAt, CHECK], [f.stateEnumId, f.closedStamp, f.closedById], "the 22nd's fix stands")
        // On the 21st the finding had not been fixed yet (that was the 22nd), so the honest row is "still open", not the
        // "reopened" the old code wrote.
        assertEquals([["AUDFE_FOUND", "2026-10-20"], ["AUDFE_STILL_OPEN", "2026-10-21"], ["AUDFE_FIXED", "2026-10-22"]],
                history(id), "the 21st reads open in its own report")
        assertEquals("Found on the 20th", f.headline, "an older read never replaces the evidence")
    }

    /** The mirror: a back-dated close of a finding that a later morning still reported. */
    @Test
    void aBackdatedCloseWritesItsDayButTheLaterSightingStands() {
        String id = (record("2026-10-20", "BD-2", "Found").findingIds as List)[0]
        record("2026-10-22", "BD-2", "Still there on the 22nd")

        Map out = close("2026-10-21", ["BD-2"])

        assertEquals(0, out.closedCount, "nothing is closed now: the 22nd still reports it")
        Map f = row(id)
        assertEquals(["AUDFS_OPEN", null], [f.stateEnumId, f.closedStamp])
        assertEquals([["AUDFE_FOUND", "2026-10-20"], ["AUDFE_FIXED", "2026-10-21"], ["AUDFE_STILL_OPEN", "2026-10-22"]], history(id))
    }

    /** An older read of an open finding: a morning of history, never newer evidence replaced or dates moved the wrong way. */
    @Test
    void aBackdatedSightingKeepsTheNewerEvidenceAndOnlyWidensTheDates() {
        String id = (record("2026-10-20", "BD-3", "Read on the 20th").findingIds as List)[0]
        record("2026-10-22", "BD-3", "Read on the 22nd")

        record("2026-10-21", "BD-3", "Read on the 21st")
        Map f = row(id)
        assertEquals(["Read on the 22nd", Date.valueOf("2026-10-22"), Date.valueOf("2026-10-20"), 3L],
                [f.headline, f.lastSeenDate, f.firstFoundDate, f.timesSeen as Long])
        assertEquals([["AUDFE_FOUND", "2026-10-20"], ["AUDFE_STILL_OPEN", "2026-10-21"], ["AUDFE_STILL_OPEN", "2026-10-22"]], history(id))

        record("2026-10-19", "BD-3", "Read on the 19th")
        f = row(id)
        assertEquals([Date.valueOf("2026-10-19"), "Read on the 22nd", "AUDFS_OPEN"], [f.firstFoundDate, f.headline, f.stateEnumId],
                "an earlier morning moves the first-found day back, nothing else")
    }

    // -----------------------------------------------------------------------------------------

    private Map record(String day, String recordKey, String headline) {
        Map out = ec.service.sync().name("${SVC}.record#CheckFindings").parameters([companyUserGroupId: TENANT, checkId: CHECK,
                reportDate: Date.valueOf(day), period: "2026-10", checkStamp: nextStamp(),
                findings: [[statementCode: "R4", recordKey: recordKey, headline: headline,
                            records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: recordKey]]]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private Map close(String day, List<String> examined) {
        Map out = ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: TENANT, checkId: CHECK,
                reportDate: Date.valueOf(day), period: "2026-10", checkStamp: nextStamp(),
                checkStatusEnumId: "AUDCS_RAN", examinedRecordKeys: examined, reportedFindingIds: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    /** Plays run in wall-clock order even when their days do not: each call gets a stamp one second later. */
    private int stampSeq = 0

    private Timestamp nextStamp() {
        return new Timestamp(Timestamp.valueOf("2026-10-28 06:10:00").time + 1000L * (stampSeq++))
    }

    private Map row(String findingId) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("findingId", findingId).useCache(false).one().getMap()
    }

    private List<List<String>> history(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", findingId)
                .orderBy(["eventDate", "eventStamp"]).useCache(false).list()
                .collect { [it.eventTypeEnumId as String, it.eventDate.toString()] }
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
