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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S13. The findings ledger's invariants that AuditLedgerServicesSmokeTests does not pin:
 * one finding per (tenant, check, statement, period, record) held by the database key and not only by the code,
 * a rerun or a second close never writing twice, record#CheckRun's status (which gates every close) derived from its
 * reads, and the finding a moved check keeps chosen the same way on every database. Own record keys per test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AuditLedgerInvariantsSmokeTests {

    private static final String SVC = "audit.AuditLedgerServices"
    private static final String TENANT = "TLINV"
    private static final String OTHER = "TLINV_OTHER"
    private static final String CHECK = "TLINV_CHECK"      // tests R4 and R9

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-ledger-invariants")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        [TENANT, OTHER].each { insert("moqui.security.UserGroup", [userGroupId: it, description: it]) }
        insert("darpan.audit.Audit", [auditId: "TLINV_AUDIT", companyUserGroupId: TENANT, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        ["R1", "R4", "R9"].each { String code ->
            insert("darpan.audit.AuditStatement", [statementId: "TLINV_${code}".toString(), companyUserGroupId: TENANT,
                    auditId: "TLINV_AUDIT", statementCode: code, categoryEnumId: "AUDC_REFUNDS_CREDITS",
                    weightEnumId: "AUDSW_DERIVED", statementText: "${code} text".toString()])
        }
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: TENANT, checkCode: "inv", label: "invariants"])
        ["TLINV_R4", "TLINV_R9"].each { link(CHECK, it) }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    // ---- one finding per problem -------------------------------------------------------------

    @Test
    void theDatabaseRefusesASecondFindingForTheSameRecordStatementAndPeriod() {
        String id = (record(CHECK, "2026-10-07", [finding("R4", "KEY-1")]).findingIds as List)[0]
        Map twin = row(id) + [findingId: "F-TLINV-TWIN"]
        // Control: the same row under another period is valid, so only the key can refuse the twin.
        assertEquals(null, tryCreate(twin + [period: "2026-09"]), "control row must be insertable")
        Throwable refused = tryCreate(twin)
        assertNotNull(refused, "AUDF_TENANT_KEY must refuse a second row: the key, not only the code, holds one finding")
        String chain = causes(refused)
        assertTrue(chain.toUpperCase().contains("AUDF_TENANT_KEY"), "refused by the finding key, not something else: ${chain}")
        assertEquals(1L, countByKey("KEY-1"))
    }

    // ---- close judges each finding, not each record --------------------------------------------

    /** A record open under R4 and R9 whose R9 problem is fixed: still reported under R4, its R9 finding must close. */
    @Test
    void aRecordStillBrokenUnderOneStatementClosesItsFixedOtherStatement() {
        List ids = record(CHECK, "2026-10-07", [finding("R4", "PAIR-1"), finding("R9", "PAIR-1")]).findingIds as List
        String r4 = ids[0], r9 = ids[1]
        Map today = record(CHECK, "2026-10-08", [finding("R4", "PAIR-1")])
        Map out = closeWith("2026-10-08", [examinedRecordKeys: ["PAIR-1"], reportedRecordKeys: ["PAIR-1"],
                reportedFindingIds: today.findingIds])
        assertEquals([r9], out.closedFindingIds, "R9 no longer reported: fixed; R4 still reported: open")
        assertEquals(["AUDFS_OPEN", "AUDFS_FIXED"], [row(r4).stateEnumId, row(r9).stateEnumId])
    }

    /** DAR-UI-063: a statement turned off records no findings, so a run never judged its open ones: none reads as fixed. */
    @Test
    void aTurnedOffStatementsOpenFindingsAreNotClosedAsFixed() {
        insert("darpan.audit.AuditCheck", [checkId: "TLINV_OFF", companyUserGroupId: TENANT, checkCode: "inv-off", label: "off"])
        insert("darpan.audit.AuditStatement", [statementId: "TLINV_OFF_R5", companyUserGroupId: TENANT, auditId: "TLINV_AUDIT",
                statementCode: "R5", categoryEnumId: "AUDC_REFUNDS_CREDITS", weightEnumId: "AUDSW_DERIVED", statementText: "R5 text"])
        link("TLINV_OFF", "TLINV_OFF_R5")
        link("TLINV_OFF", "TLINV_R1")
        String id = (record("TLINV_OFF", "2026-10-07", [finding("R5", "OFF-1")]).findingIds as List)[0]
        insert("darpan.audit.AuditStatement", row("TLINV_OFF_R5", "darpan.audit.AuditStatement", "statementId") + [isActive: "N"])

        Map out = ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: TENANT,
                checkId: "TLINV_OFF", reportDate: Date.valueOf("2026-10-08"), checkStamp: Timestamp.valueOf("2026-10-08 06:12:00"),
                checkStatusEnumId: "AUDCS_RAN", examinedRecordKeys: ["OFF-1"], reportedRecordKeys: [], reportedFindingIds: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)

        assertEquals(0, out.closedCount, "not judged is not fixed")
        assertEquals("AUDFS_OPEN", row(id).stateEnumId)
        assertEquals(["AUDFE_FOUND"], events(id)*.eventTypeEnumId)
    }

    /**
     * A statement the run could not judge (S11: its link names a rule the run no longer has, so no row can match) is
     * named by the caller in unjudgedStatementIds: its open findings stay open; the judged statement's still close.
     */
    @Test
    void aStatementTheRunCouldNotJudgeKeepsItsOpenFindings() {
        List ids = record(CHECK, "2026-10-07", [finding("R4", "UNJ-1"), finding("R9", "UNJ-1")]).findingIds as List
        String r4 = ids[0], r9 = ids[1]
        Map out = closeWith("2026-10-08", [examinedRecordKeys: ["UNJ-1"], reportedRecordKeys: [], reportedFindingIds: [],
                unjudgedStatementIds: ["TLINV_R9"]])
        assertEquals([r4], out.closedFindingIds, "R4 was judged and not reported: fixed; R9 was not judged")
        assertEquals(["AUDFS_FIXED", "AUDFS_OPEN"], [row(r4).stateEnumId, row(r9).stateEnumId])
        assertEquals(["AUDFE_FOUND"], events(r9)*.eventTypeEnumId)
    }

    /** timesSeen counts mornings: reported, closed by a later run, then reported again the same morning is one morning. */
    @Test
    void aSameMorningReopenDoesNotCountTheMorningTwice() {
        String id = (record(CHECK, "2026-10-07", [finding("R4", "REOPEN-1")]).findingIds as List)[0]
        closeWith("2026-10-07", [examinedRecordKeys: ["REOPEN-1"], reportedRecordKeys: [], reportedFindingIds: []])
        assertEquals("AUDFS_FIXED", row(id).stateEnumId)
        Map again = record(CHECK, "2026-10-07", [finding("R4", "REOPEN-1")])
        assertEquals(1, again.reopenedCount, "it did break again: reopened")
        assertEquals(1L, row(id).timesSeen as Long, "still one morning")
    }

    @Test
    void theSameRecordTwiceInOneCallIsOneFinding() {
        Map out = record(CHECK, "2026-10-07", [finding("R4", "TWICE-1"), finding("R4", "TWICE-1")])
        assertEquals([1, 1], [out.foundCount, out.refreshedCount], "the second is the first, refreshed")
        assertEquals(1, (out.findingIds as List).toSet().size())
        assertEquals(1L, countByKey("TWICE-1"))
        assertEquals(["AUDFE_FOUND"], events((out.findingIds as List)[0] as String)*.eventTypeEnumId)
    }

    @Test
    void aSecondCloseOfTheSameMorningClosesNothingMore() {
        String id = (record(CHECK, "2026-10-07", [finding("R4", "CLOSE2-1")]).findingIds as List)[0]
        assertEquals(1, close("2026-10-08", "AUDCS_RAN", ["CLOSE2-1"]).closedCount)
        assertEquals(0, close("2026-10-08", "AUDCS_RAN", ["CLOSE2-1"]).closedCount, "a replayed close is a no-op")
        assertEquals(["AUDFE_FOUND", "AUDFE_FIXED"], events(id)*.eventTypeEnumId, "one fixed event, not two")
    }

    /**
     * A check that leaves two statements while the same record is open under both keeps ONE finding: the one found first,
     * so the record's first-found day and history survive. The older one is deliberately the later statement code and the
     * larger id, so neither index order nor primary-key order picks it by accident.
     */
    @Test
    void aCheckLeavingTwoStatementsKeepsTheOldestFindingOnEveryDatabase() {
        insert("darpan.audit.AuditCheck", [checkId: "TLINV_MOVE", companyUserGroupId: TENANT, checkCode: "inv-move", label: "moves"])
        ["TLINV_R1", "TLINV_R9"].each { link("TLINV_MOVE", it) }
        String newer = (record("TLINV_MOVE", "2026-10-08", [finding("R1", "MOVE2-1")]).findingIds as List)[0]
        String older = (record("TLINV_MOVE", "2026-10-07", [finding("R9", "MOVE2-1")]).findingIds as List)[0]
        assertTrue(older > newer, "set-up: the older finding has the larger id")
        ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", "TLINV_MOVE").disableAuthz().deleteAll()
        link("TLINV_MOVE", "TLINV_R4")

        Map out = record("TLINV_MOVE", "2026-10-09", [finding("R4", "MOVE2-1")])

        assertEquals([older], out.findingIds, "the finding first found moves, with its history")
        assertEquals(["TLINV_R4", "AUDFS_OPEN", Date.valueOf("2026-10-07")],
                [row(older).statementId, row(older).stateEnumId, row(older).firstFoundDate])
        assertEquals("AUDFS_FIXED", row(newer).stateEnumId, "the newer copy closes")
        assertEquals(1L, ec.entity.find("darpan.audit.AuditFinding").condition("recordKey", "MOVE2-1")
                .condition("stateEnumId", "AUDFS_OPEN").count(), "one open finding per problem")
    }

    // ---- D11: rows no statement owns are kept on the morning's run, never dropped ----------------

    @Test
    void rowsOutsideEveryRuleAreKeptOnTheMorningsRunAndARerunClearsThem() {
        checkRun("2026-10-14", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        Map outside = [count: 2, rows: [[recordKey: "OUT-1", diffType: "MISSING_IN_FILE_2", bucket: "MISSING", note: "no rule",
                                         records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: "OUT-1"]]],
                                        [recordKey: "OUT-2", diffType: "FIELD_MISMATCH", bucket: "RULE", note: "no rule"]]]
        record(CHECK, "2026-10-14", [finding("R4", "OUTK-1")], [outsideRules: outside])
        Map run = morningRun("2026-10-14")
        assertEquals(2L, run.outsideRulesCount as Long)
        List stored = new groovy.json.JsonSlurper().parseText(run.outsideRulesJson as String) as List
        assertEquals(["OUT-1", "OUT-2"], stored*.recordKey)
        assertEquals("OMS", stored[0].records[0].systemEnumId, "records carry the ROOT system, as AuditFindingRecord does")

        checkRun("2026-10-14", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        run = morningRun("2026-10-14")
        assertEquals([null, null], [run.outsideRulesCount, run.outsideRulesJson], "a rerun never shows the last run's rows")

        record(CHECK, "2026-10-14", [], [outsideRules: [count: 0, rows: []]])
        run = morningRun("2026-10-14")
        assertEquals([0L, "[]"], [run.outsideRulesCount as Long, run.outsideRulesJson], "none is 0 and [], not null")
    }

    @Test
    void moreThanTwoHundredRowsKeepsTheFirstTwoHundredAndTheTrueCount() {
        checkRun("2026-10-15", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        List rows = (1..250).collect { [recordKey: "BIG-${it}".toString(), diffType: "FIELD_MISMATCH", bucket: "RULE", note: "x"] }
        record(CHECK, "2026-10-15", [], [outsideRules: [count: 900, rows: rows]])
        Map run = morningRun("2026-10-15")
        List kept = new groovy.json.JsonSlurper().parseText(run.outsideRulesJson as String) as List
        assertEquals([900L, 200, "BIG-1", "BIG-200"], [run.outsideRulesCount as Long, kept.size(), kept[0].recordKey, kept[-1].recordKey])
    }

    @Test
    void aCountBelowItsRowsIsRefusedAndNothingIsWritten() {
        checkRun("2026-10-16", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        callExpectingError("record#CheckFindings", base("2026-10-16") + [findings: [finding("R4", "OUTBAD-1")],
                outsideRules: [count: 1, rows: [[recordKey: "A"], [recordKey: "B"]]]], "outsideRules needs a count")
        assertEquals(0L, countByKey("OUTBAD-1"), "validated before any finding is written")
        assertEquals(null, morningRun("2026-10-16").outsideRulesCount)
    }

    @Test
    void withoutACheckRunOrWithoutTheParameterNothingIsKept() {
        record(CHECK, "2026-10-17", [], [outsideRules: [count: 1, rows: [[recordKey: "VERIFY-1"]]]])
        assertEquals(0L, ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK)
                .condition("reportDate", Date.valueOf("2026-10-17")).count(), "a verify writes no check run, and this writes none")
        checkRun("2026-10-18", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        record(CHECK, "2026-10-18", [])
        assertEquals(null, morningRun("2026-10-18").outsideRulesCount, "an old caller leaves it null")
    }

    // ---- record#CheckRun: the status every close is gated on ----------------------------------

    @Test
    void aCheckRunIsRanOnlyWhenEveryReadIsComplete() {
        assertEquals("AUDCS_RAN", checkRun("2026-10-01", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE"),
                read("NETSUITE_SUITEQL", "AUDSR_COMPLETE")]).checkStatusEnumId)

        Map partial = checkRun("2026-10-02", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE"),
                read("NETSUITE_SUITEQL", "AUDSR_PARTIAL") + [reason: "page 4 timed out"]])
        assertEquals("AUDCS_PARTIAL", partial.checkStatusEnumId, "one partial read makes the check partial")
        assertTrue((partial.reason as String).contains("was partial: page 4 timed out"), partial.reason as String)

        assertEquals("AUDCS_PARTIAL", checkRun("2026-10-03", [read("OMS_RETURNS_GQL", "AUDSR_FAILED")]).checkStatusEnumId)
        assertEquals("AUDCS_NOT_RUN", checkRun("2026-10-04", []).checkStatusEnumId, "no read at all is not a clean run")
        assertEquals("AUDCS_NOT_RUN", checkRun("2026-10-05", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")],
                [notRunReason: "No saved run is configured"]).checkStatusEnumId, "a not-run reason wins over complete reads")
    }

    @Test
    void aSecondCheckRunTheSameMorningReplacesTheFirstAndItsReads() {
        Map first = checkRun("2026-10-11", [read("OMS_RETURNS_GQL", "AUDSR_PARTIAL"), read("NETSUITE_SUITEQL", "AUDSR_COMPLETE")])
        Map second = checkRun("2026-10-11", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")])
        assertEquals(first.checkRunId, second.checkRunId, "one run per check, period and morning")
        List runs = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK)
                .condition("reportDate", Date.valueOf("2026-10-11")).useCache(false).list()
        assertEquals(1, runs.size())
        assertEquals("AUDCS_RAN", runs[0].statusEnumId)
        List reads = ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", second.checkRunId).useCache(false).list()
        assertEquals([["OMS", "OMS_RETURNS_GQL"]], reads.collect { [it.systemEnumId, it.endpointEnumId] },
                "the reads are replaced, stored under the root system")
    }

    @Test
    void aBadReadOrABackwardWindowIsRefusedAndWritesNothing() {
        callExpectingError("record#CheckRun", base("2026-10-12") + [sourceReads: [read("OMS_RETURNS_GQL", "AUDSR_DONE")]],
                "is not an AuditSourceRead status")
        callExpectingError("record#CheckRun", base("2026-10-12") + [sourceReads: [read("NOT_A_SYSTEM", "AUDSR_COMPLETE")]],
                "Unknown system NOT_A_SYSTEM")
        callExpectingError("record#CheckRun", base("2026-10-12") + [windowFromDate: Date.valueOf("2026-10-12"),
                windowThruDate: Date.valueOf("2026-10-02"), sourceReads: [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")]], "runs backwards")
        assertEquals(0L, ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK)
                .condition("reportDate", Date.valueOf("2026-10-12")).count())
    }

    @Test
    void anotherTenantCannotRecordARunOfThisTenantsCheck() {
        callExpectingError("record#CheckRun", base("2026-10-13") + [companyUserGroupId: OTHER,
                sourceReads: [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE")]], "does not belong to tenant ${OTHER}")
        assertEquals(0L, ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK)
                .condition("reportDate", Date.valueOf("2026-10-13")).count())
    }

    // -----------------------------------------------------------------------------------------

    private static Map finding(String statementCode, String recordKey) {
        return [statementCode: statementCode, recordKey: recordKey, headline: "Finding ${recordKey}".toString(),
                records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: recordKey]]]
    }

    private static Map read(String systemEnumId, String statusEnumId) {
        return [systemEnumId: systemEnumId, statusEnumId: statusEnumId, recordCount: 10]
    }

    private static Map base(String day) {
        return [companyUserGroupId: TENANT, checkId: CHECK, reportDate: Date.valueOf(day)]
    }

    private Map record(String checkId, String day, List findings, Map extra = [:]) {
        Map out = ec.service.sync().name("${SVC}.record#CheckFindings").parameters([companyUserGroupId: TENANT, checkId: checkId,
                reportDate: Date.valueOf(day), checkStamp: Timestamp.valueOf("${day} 06:10:00"), findings: findings] + extra).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private Map morningRun(String day) {
        return ec.entity.find("darpan.audit.AuditCheckRun").condition("companyUserGroupId", TENANT).condition("checkId", CHECK)
                .condition("reportDate", Date.valueOf(day)).useCache(false).one().getMap()
    }

    private Map close(String day, String status, List<String> examined) {
        Map out = ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: TENANT, checkId: CHECK,
                reportDate: Date.valueOf(day), checkStamp: Timestamp.valueOf("${day} 06:12:00"), checkStatusEnumId: status,
                examinedRecordKeys: examined, reportedRecordKeys: []]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private Map closeWith(String day, Map keys) {
        Map out = ec.service.sync().name("${SVC}.close#ResolvedFindings").parameters([companyUserGroupId: TENANT, checkId: CHECK,
                reportDate: Date.valueOf(day), checkStamp: Timestamp.valueOf("${day} 18:00:00"), checkStatusEnumId: "AUDCS_RAN"] + keys).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    /** Creates a finding row in its own transaction, rolled back either way; answers what refused it, or null. */
    private Throwable tryCreate(Map fields) {
        Throwable refused = null
        boolean began = ec.transaction.begin(60)
        try {
            ec.entity.makeValue("darpan.audit.AuditFinding").setAll(fields).create()
        } catch (Throwable t) {
            refused = t
        } finally {
            ec.transaction.rollback(began, "test row, never kept", null)
        }
        return refused
    }

    private static String causes(Throwable t) {
        List<String> messages = []
        for (Throwable c = t; c != null && messages.size() < 10; c = c.cause) messages << String.valueOf(c.message)
        return messages.join(" | ")
    }

    private Map row(String id, String entityName, String pkName) {
        return ec.entity.find(entityName).condition(pkName, id).useCache(false).one().getMap()
    }

    private Map checkRun(String day, List reads, Map extra = [:]) {
        Map out = ec.service.sync().name("${SVC}.record#CheckRun").parameters(base(day) + [sourceReads: reads] + extra).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private void callExpectingError(String service, Map parameters, String expected) {
        ec.service.sync().name("${SVC}.${service}").parameters(parameters).call()
        assertTrue(ec.message.hasError(), "expected ${service} to be refused (${expected})")
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        assertTrue(errors.contains(expected), errors)
    }

    private Map row(String findingId) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("findingId", findingId).useCache(false).one().getMap()
    }

    private List events(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", findingId)
                .orderBy("eventStamp").useCache(false).list()
    }

    private long countByKey(String recordKey) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("recordKey", recordKey).count()
    }

    private void link(String checkId, String statementId) {
        insert("darpan.audit.AuditStatementCheck", [checkId: checkId, statementId: statementId, companyUserGroupId: TENANT])
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
