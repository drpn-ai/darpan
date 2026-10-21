package darpan.audit

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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-089 — the findings ledger's writers, record#CheckFindings and close#ResolvedFindings, against real
 * entities. Each test uses its own record keys, so the class-wide database never couples two tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLedgerServicesSmokeTests {

    private static final String SVC = "audit.AuditLedgerServices"
    private static final String GORJANA = "GORJANA"
    private static final String KREWE = "KREWE"
    private static final String CHECK = "TLED_CHECK"        // graceDays 1, tests R4 and R9
    private static final String CHECK_NO_GRACE = "TLED_CHECK0"
    private static final String KREWE_CHECK = "TLED_CHECK_K"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-ledger")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        [GORJANA, KREWE].each { insert("moqui.security.UserGroup", [userGroupId: it, description: it]) }
        insert("darpan.audit.Audit", [auditId: "TLED_AUDIT", companyUserGroupId: GORJANA, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.Audit", [auditId: "TLED_AUDIT2", companyUserGroupId: GORJANA, auditCode: "daily-ops", auditName: "Daily ops", periodEnumId: "AUDP_DAY"])
        insert("darpan.audit.Audit", [auditId: "TLED_K_AUDIT", companyUserGroupId: KREWE, auditCode: "month-close", auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        ["R1", "R4", "R9"].each { String code ->
            insert("darpan.audit.AuditStatement", [statementId: "TLED_${code}".toString(), companyUserGroupId: GORJANA, auditId: "TLED_AUDIT",
                    statementCode: code, categoryEnumId: "AUDC_REFUNDS_CREDITS", weightEnumId: "AUDSW_DERIVED", statementText: "${code} text".toString()])
        }
        // The second audit has its own R4: codes are unique within an audit, not across the tenant.
        insert("darpan.audit.AuditStatement", [statementId: "TLED_R4_OPS", companyUserGroupId: GORJANA, auditId: "TLED_AUDIT2",
                statementCode: "R4", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", statementText: "ops R4"])
        insert("darpan.audit.AuditCheck", [checkId: "TLED_CHECK_DUP", companyUserGroupId: GORJANA, checkCode: "both-r4", label: "both R4s"])
        [["TLED_CHECK_DUP", "TLED_R4"], ["TLED_CHECK_DUP", "TLED_R4_OPS"]].each { List<String> link ->
            insert("darpan.audit.AuditStatementCheck", [checkId: link[0], statementId: link[1], companyUserGroupId: GORJANA])
        }
        insert("darpan.audit.AuditStatement", [statementId: "TLED_K_R4", companyUserGroupId: KREWE, auditId: "TLED_K_AUDIT",
                statementCode: "R4", categoryEnumId: "AUDC_REFUNDS_CREDITS", weightEnumId: "AUDSW_FIRM", statementText: "krewe R4"])
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: GORJANA, checkCode: "returns-vs-credit-memos",
                label: "OMS returns against NetSuite credit memos", graceDays: 1L])
        insert("darpan.audit.AuditCheck", [checkId: CHECK_NO_GRACE, companyUserGroupId: GORJANA, checkCode: "no-grace", label: "no grace"])
        insert("darpan.audit.AuditCheck", [checkId: KREWE_CHECK, companyUserGroupId: KREWE, checkCode: "krewe", label: "krewe"])
        [[CHECK, "TLED_R4"], [CHECK, "TLED_R9"], [CHECK_NO_GRACE, "TLED_R4"]].each { List<String> link ->
            insert("darpan.audit.AuditStatementCheck", [checkId: link[0], statementId: link[1], companyUserGroupId: GORJANA])
        }
        insert("darpan.audit.AuditStatementCheck", [checkId: KREWE_CHECK, statementId: "TLED_K_R4", companyUserGroupId: KREWE])
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
    void aFindingIsFoundStaysOpenIsFixedAndReopensKeepingItsId() {
        Map day1 = record(CHECK, "2026-10-07", [finding("R4", "LIFE-1")])
        assertEquals(1, day1.foundCount)
        String id = (day1.findingIds as List)[0]
        assertTrue(id.startsWith("F-"), "the stored id is the JSON id: ${id}")
        Map f = row(id)
        assertEquals("AUDFS_PENDING", f.stateEnumId, "inside the one-day grace age on the morning it is found")
        assertEquals(Date.valueOf("2026-10-07"), f.firstFoundDate)
        assertEquals(1L, f.timesSeen as Long)

        Map day2 = record(CHECK, "2026-10-08", [finding("R4", "LIFE-1")])
        assertEquals([1, 0], [day2.stillOpenCount, day2.foundCount])
        f = row(id)
        assertEquals("AUDFS_OPEN", f.stateEnumId, "past the grace age")
        assertEquals([Date.valueOf("2026-10-08"), 2L], [f.lastSeenDate, f.timesSeen as Long])

        record(CHECK, "2026-10-09", [])
        Map closed = close(CHECK, "2026-10-09", "AUDCS_RAN", ["LIFE-1"])
        assertEquals([id], closed.closedFindingIds)
        f = row(id)
        assertEquals(["AUDFS_FIXED", "AUDAT_CHECK", CHECK], [f.stateEnumId, f.closedByTypeEnumId, f.closedById])
        assertTrue(f.closedStamp != null)

        Map day4 = record(CHECK, "2026-10-10", [finding("R4", "LIFE-1")])
        assertEquals([id], day4.findingIds, "reported again: the same finding, not a new one")
        assertEquals(1, day4.reopenedCount)
        f = row(id)
        assertEquals("AUDFS_OPEN", f.stateEnumId)
        assertEquals(Date.valueOf("2026-10-07"), f.firstFoundDate, "reopening keeps the first-found day")
        assertEquals(3L, f.timesSeen as Long)
        assertNull(f.closedStamp)
        assertNull(f.closedById)

        List events = events(id)
        assertEquals(["AUDFE_FOUND", "AUDFE_STILL_OPEN", "AUDFE_FIXED", "AUDFE_REOPENED"], events*.eventTypeEnumId)
        assertEquals(["2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10"].collect { Date.valueOf(it) }, events*.eventDate)
        assertEquals([GORJANA] as Set, events*.companyUserGroupId as Set)
        assertEquals([CHECK] as Set, events*.byId as Set)
    }

    @Test
    void aPartialOrSkippedCheckClosesNothing() {
        String id = (record(CHECK, "2026-10-07", [finding("R4", "PART-1")]).findingIds as List)[0]
        ["AUDCS_PARTIAL", "AUDCS_NOT_RUN"].each { String status ->
            Map out = close(CHECK, "2026-10-08", status, ["PART-1"])
            assertEquals(0, out.closedCount, status)
            assertTrue((out.skippedReason as String)?.contains(status), "says why nothing closed: ${out.skippedReason}")
        }
        assertEquals("AUDFS_PENDING", row(id).stateEnumId)
        assertEquals(["AUDFE_FOUND"], events(id)*.eventTypeEnumId)
    }

    @Test
    void aRecordTheCheckDidNotLookAtStaysOpen() {
        String id = (record(CHECK_NO_GRACE, "2026-10-07", [finding("R4", "UNSEEN-1")]).findingIds as List)[0]
        assertEquals(0, close(CHECK_NO_GRACE, "2026-10-08", "AUDCS_RAN", ["SOMETHING-ELSE"]).closedCount)
        assertEquals("AUDFS_OPEN", row(id).stateEnumId)
    }

    @Test
    void withNoGraceAgeAFindingIsOpenTheMorningItIsFound() {
        String id = (record(CHECK_NO_GRACE, "2026-10-07", [finding("R4", "NOGRACE-1")]).findingIds as List)[0]
        assertEquals("AUDFS_OPEN", row(id).stateEnumId)
    }

    @Test
    void aSecondRunTheSameMorningRefreshesTheEvidenceAndWritesNoEvent() {
        String id = (record(CHECK, "2026-10-07", [finding("R4", "RERUN-1")]).findingIds as List)[0]
        Map again = finding("R4", "RERUN-1") + [headline: "Refreshed headline",
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "creditMemo", recordId: "CM-2"]]]
        Map out = record(CHECK, "2026-10-07", [again])
        assertEquals([1, 0, 0], [out.refreshedCount, out.foundCount, out.stillOpenCount])
        Map f = row(id)
        assertEquals(["Refreshed headline", 1L], [f.headline, f.timesSeen as Long])
        assertEquals(["AUDFE_FOUND"], events(id)*.eventTypeEnumId)
        assertEquals(["CM-2"], records(id)*.recordId, "the latest read replaces the records")
    }

    @Test
    void recordsStoreTheRootSystemAndTheEvidenceIsKept() {
        Map f = finding("R4", "EVID-1") + [expectedAmount: "160.23", actualAmount: "160.22", currencyUomId: "USD",
                expectedSource: "Shopify refund transactions", actualSource: "NetSuite credit memo total",
                ruleText: "Credit memo total equals the refund given, to the cent.",
                records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: "M258615", recordStatus: "Completed"],
                          [systemEnumId: "NETSUITE_SUITEQL", recordType: "creditMemo", recordId: "CM-88213", internalId: 51000002L, recordStatus: "Open"]],
                checks: [[label: "NetSuite has a credit memo for the return", passed: true, value: "CM-88213"],
                         [label: "Memo total equals the refund", passed: false, value: "160.22 vs 160.23"]]]
        String id = (record(CHECK, "2026-10-07", [f]).findingIds as List)[0]
        Map stored = row(id)
        assertEquals(new BigDecimal("-0.01"), (stored.differenceAmount as BigDecimal).stripTrailingZeros(), "actual - expected")
        assertEquals(["OMS", "NETSUITE"], records(id)*.systemEnumId, "root systems, never the endpoints")
        assertEquals(["01", "02"], records(id)*.recordSeqId)
        assertEquals("51000002", records(id)[1].internalId)
        List checks = ec.entity.find("darpan.audit.AuditFindingCondition").condition("findingId", id)
                .orderBy("sequenceNum").useCache(false).list()
        assertEquals(["Y", "N"], checks*.passed)
        assertEquals("160.22 vs 160.23", checks[1].checkValue)
    }

    @Test
    void oneRecordBreakingTwoStatementsIsTwoFindings() {
        Map out = record(CHECK, "2026-10-07", [finding("R4", "TWO-1"), finding("R9", "TWO-1")])
        assertEquals(2, out.foundCount)
        assertEquals(2, (out.findingIds as List).toSet().size())
    }

    @Test
    void aStatementTheCheckDoesNotTestIsRefusedAndNothingIsWritten() {
        Map out = recordExpectingError(CHECK, [finding("R4", "REFUSE-1"), finding("R1", "REFUSE-1")])
        assertTrue(out.error.contains("does not test statement R1"), out.error as String)
        assertEquals(0L, countByKey("REFUSE-1"), "validation runs before any write")
    }

    @Test
    void aCheckWhoseStatementsShareACodeIsRefused() {
        Map out = recordExpectingError("TLED_CHECK_DUP", [finding("R4", "DUP-1")])
        assertTrue(out.error.contains("tests two statements coded R4"), out.error as String)
        assertEquals(0L, countByKey("DUP-1"))
    }

    @Test
    void anUnknownSystemIsRefusedAndNothingIsWritten() {
        Map bad = finding("R4", "REFUSE-2") + [records: [[systemEnumId: "NOT_A_SYSTEM", recordType: "return", recordId: "x"]]]
        Map out = recordExpectingError(CHECK, [bad])
        assertTrue(out.error.contains("Unknown system NOT_A_SYSTEM"), out.error as String)
        assertEquals(0L, countByKey("REFUSE-2"))
    }

    @Test
    void anotherTenantsCheckIsRefusedByBothWriters() {
        ec.service.sync().name("${SVC}.record#CheckFindings").parameters([companyUserGroupId: KREWE, checkId: CHECK,
                reportDate: Date.valueOf("2026-10-07"), findings: [finding("R4", "FOREIGN-1")]]).call()
        assertTrue(ec.message.hasError(), "KREWE must not write findings under GORJANA's check")
        assertTrue(ec.message.errorsString.contains("does not belong to tenant KREWE"), ec.message.errorsString)
        ec.message.clearErrors()
        assertEquals(0L, countByKey("FOREIGN-1"))

        ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: KREWE, checkId: CHECK,
                reportDate: Date.valueOf("2026-10-08"), checkStatusEnumId: "AUDCS_RAN", examinedRecordKeys: ["LIFE-1"]]).call()
        assertTrue(ec.message.hasError(), "KREWE must not close GORJANA's findings")
        ec.message.clearErrors()
    }

    // -----------------------------------------------------------------------------------------

    private static Map finding(String statementCode, String recordKey) {
        return [statementCode: statementCode, recordKey: recordKey, headline: "Finding ${recordKey}".toString(),
                records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: recordKey]],
                checks: [[label: "a check", passed: false, value: "x"]]]
    }

    private Map record(String checkId, String day, List findings) {
        Map out = ec.service.sync().name("${SVC}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), checkStamp: Timestamp.valueOf("${day} 06:10:00"), findings: findings]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private Map recordExpectingError(String checkId, List findings) {
        ec.service.sync().name("${SVC}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf("2026-10-07"), findings: findings]).call()
        assertTrue(ec.message.hasError(), "expected the call to be refused")
        String error = ec.message.errorsString
        ec.message.clearErrors()
        return [error: error]
    }

    private Map close(String checkId, String day, String status, List<String> examined) {
        Map out = ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), checkStamp: Timestamp.valueOf("${day} 06:12:00"), checkStatusEnumId: status,
                examinedRecordKeys: examined]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private Map row(String findingId) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("findingId", findingId).useCache(false).one().getMap()
    }

    private List events(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", findingId)
                .orderBy("eventStamp").useCache(false).list()
    }

    private List records(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("findingId", findingId)
                .orderBy("recordSeqId").useCache(false).list()
    }

    private long countByKey(String recordKey) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("recordKey", recordKey).count()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
