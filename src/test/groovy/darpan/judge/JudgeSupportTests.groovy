package darpan.judge

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 2, Task 7 (D9). The judge runs a check's Must hold decision (every match is a failed promise) and then its
 * Explains decision on the same pair with claim set (first match by priority wins, nothing matching is UNEXPLAINED).
 * Everything runs on the request thread as the tenant user, authz ON, under a facade grant (P2-9), exactly as in production.
 *
 * The fixture (tenant A): Must hold NOT_CLOSED (file1_status = open, priority 30), TOO_SMALL (file1_amount = 5, 20), UNLINKED
 * (file1_amount = 9, 10; no statement) and NOTED (file1_status = closed, 5; kind EXPECTED, so no promise). Explains NOISE
 * (file2_status = hide, 40, HIDDEN), WAITING (claim NOT_CLOSED and file2_status = pending, 30, EXPECTED), BAD_DATA (claim
 * TOO_SMALL, 20, FINDING) and UNEXPLAINED (no rule).
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeSupportTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final List<String> WATCHED = ["rules.RuleSet", "rules.RuleSetFactType", "rules.RuleSetOutcome", "rules.RuleSetRule",
            "rules.RuleSetCondition", "rules.RuleSetAction", "rules.FactType", "rules.FactField", "darpan.judge.CheckDecision",
            "darpan.judge.DecisionOutcome", "darpan.audit.AuditCheck", "darpan.audit.AuditFinding"]

    private ExecutionContext ec
    private String checkA
    private String factTypeId
    private Map keyNames
    private String mustHold
    private String explains
    private Map<String, String> ruleIds = [:]
    private Map<String, String> statementsByClaim

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "judge-support")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml", "component://darpan/data/JudgeSeedData.xml",
                "component://darpan/data/MappingSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        actAs(EDITOR_USERNAME, A)
        checkA = seedCheck(A, "judge-pairs")
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType",
                [checkId: checkA, sampleKeys: [file1: ["status", "amount"], file2: ["status"]]]))
        factTypeId = generated.factTypeId
        keyNames = generated.keyNames

        mustHold = createDecision("N", ["NOT_CLOSED", "TOO_SMALL", "UNLINKED", "NOTED"])
        ruleIds.notClosed = storeRule(mustHold, "not closed", 30, [line("01", "file1_status", "open")], "NOT_CLOSED")
        ruleIds.tooSmall = storeRule(mustHold, "too small", 20, [line("01", "file1_amount", "5")], "TOO_SMALL")
        ruleIds.unlinked = storeRule(mustHold, "unlinked", 10, [line("01", "file1_amount", "9")], "UNLINKED")
        ruleIds.noted = storeRule(mustHold, "noted", 5, [line("01", "file1_status", "closed")], "NOTED")
        meaning(mustHold, "NOT_CLOSED", "JOK_FINDING", "The order is still open")
        meaning(mustHold, "TOO_SMALL", "JOK_FINDING", "The amount is too small")
        meaning(mustHold, "UNLINKED", "JOK_FINDING", "No statement makes this promise")
        meaning(mustHold, "NOTED", "JOK_EXPECTED", "Closed, noted only")

        explains = createDecision("Y", ["NOISE", "WAITING", "BAD_DATA", "UNEXPLAINED"])
        ruleIds.noise = storeRule(explains, "noise", 40, [line("01", "file2_status", "hide")], "NOISE")
        ruleIds.waiting = storeRule(explains, "waiting", 30, [line("01", "claim", "NOT_CLOSED"), line("02", "file2_status", "pending")], "WAITING")
        ruleIds.badData = storeRule(explains, "bad data", 20, [line("01", "claim", "TOO_SMALL")], "BAD_DATA")
        meaning(explains, "NOISE", "JOK_HIDDEN", "Test noise")
        meaning(explains, "WAITING", "JOK_EXPECTED", "Waiting for the item to come back", "Wait a week", "ops", "NETSUITE")
        meaning(explains, "BAD_DATA", "JOK_FINDING", "The amount was keyed wrong", "Fix the amount", "finance", "SHOPIFY")
        meaning(explains, "UNEXPLAINED", "JOK_FINDING", "Unexplained")
        decide(A, checkA, mustHold, explains)

        // The audit and statements record#CheckFindings needs; each statement names its promise.
        insert("darpan.audit.Audit", [auditId: "JST_AUDIT", companyUserGroupId: A, auditCode: "judge", auditName: "Judge", periodEnumId: "AUDP_MONTH"])
        [["J1", "NOT_CLOSED"], ["J2", "TOO_SMALL"]].each { List<String> s ->
            insert("darpan.audit.AuditStatement", [statementId: "JST_${s[0]}".toString(), companyUserGroupId: A, auditId: "JST_AUDIT",
                    statementCode: s[0], categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", statementText: "${s[0]} text".toString(),
                    claimRuleSetId: mustHold, claimOutcomeId: s[1]])
            insert("darpan.audit.AuditStatementCheck", [checkId: checkA, statementId: "JST_${s[0]}".toString(), companyUserGroupId: A])
        }
        statementsByClaim = rows("darpan.audit.AuditStatement", [auditId: "JST_AUDIT"]).collectEntries {
            [(it.claimRuleSetId + "|" + it.claimOutcomeId): it.statementCode] } as Map<String, String>
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsA() {
        actAs(EDITOR_USERNAME, A)
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void aPairFailingTwoPromisesGivesTwoFindingsEachWithItsOwnCause() {
        List<Map> p1 = judged().findings.findAll { it.recordKey == "P1" }
        assertEquals(["NOT_CLOSED", "TOO_SMALL"], p1*.claimOutcomeId, "one finding per failing promise, highest priority first")
        assertEquals(["J1", "J2"], p1*.statementCode)
        assertEquals(["WAITING", "BAD_DATA"], p1*.causeOutcomeId, "each promise explained on its own, with claim set")
    }

    @Test
    void aCauseMatchingNothingIsUnexplained() {
        Map p2 = judged().findings.find { it.recordKey == "P2" }
        assertEquals("NOT_CLOSED", p2.claimOutcomeId)
        assertEquals("UNEXPLAINED", p2.causeOutcomeId)
        assertEquals("Unexplained", p2.diagnosisText)
        assertEquals("JOK_FINDING", p2.outcomeKindEnumId)
        assertEquals(explains, p2.causeRuleSetId)
        assertNull(p2.causeRuleId, "no cause rule matched")
    }

    @Test
    void aHiddenCauseDropsTheFindingAndTheFirstMatchByPriorityWins() {
        // P4 fails TOO_SMALL; NOISE (40, hidden) and BAD_DATA (20) both match, and NOISE wins.
        assertEquals([], judged().findings.findAll { it.recordKey == "P4" })
    }

    @Test
    void anExpectedCauseIsKeptAndMarked() {
        List<Map> p1 = judged().findings.findAll { it.recordKey == "P1" }
        assertEquals(["JOK_EXPECTED", "JOK_FINDING"], p1*.outcomeKindEnumId)
    }

    @Test
    void everyFindingCarriesTheFourLineageIdsOfTheRulesThatMatched() {
        List<Map> findings = judged().findings
        assertEquals([["P1", "NOT_CLOSED"], ["P1", "TOO_SMALL"], ["P2", "NOT_CLOSED"]], findings.collect { [it.recordKey, it.claimOutcomeId] })
        assertEquals([mustHold] * 3, findings*.claimRuleSetId)
        assertEquals([ruleIds.notClosed, ruleIds.tooSmall, ruleIds.notClosed], findings*.claimRuleId)
        assertEquals([explains] * 3, findings*.causeRuleSetId)
        assertEquals([ruleIds.waiting, ruleIds.badData, null], findings*.causeRuleId)
    }

    @Test
    void theHeadlineIsThePromiseAndTheDiagnosisAndFixAreTheCause() {
        Map tooSmall = judged().findings.find { it.recordKey == "P1" && it.claimOutcomeId == "TOO_SMALL" }
        assertEquals("The amount is too small", tooSmall.headline)
        assertEquals("The amount was keyed wrong", tooSmall.diagnosisText)
        assertEquals(["Fix the amount", "finance", "SHOPIFY"], [tooSmall.fixText, tooSmall.fixOwner, tooSmall.fixWhereEnumId])
        assertTrue(tooSmall.containsKey("conclusionEnumId"))
        assertNull(tooSmall.conclusionEnumId)
    }

    @Test
    void aPromiseWithNoStatementIsSkippedWithAWarningAndANonFindingOutcomeIsNoPromise() {
        Map out = judged()
        assertEquals([], out.findings.findAll { it.claimOutcomeId in ["UNLINKED", "NOTED"] })
        assertEquals([], out.findings.findAll { it.recordKey == "P3" }, "P3 fails UNLINKED (no statement) and is NOTED (expected)")
        assertEquals(1, out.warnings.size(), "one warning per promise, not per pair: ${out.warnings}")
        String warning = out.warnings[0]
        assertTrue(warning.contains("${mustHold}|UNLINKED") && warning.contains("no statement") && warning.contains("2 "), warning)
    }

    @Test
    void anExplainsDecisionWithoutUnexplainedIsRefusedWhenJudgingStarts() {
        String noUnexplained = createDecision("Y", ["WAITING"])
        String check = seedCheck(A, "no-unexplained")
        decide(A, check, mustHold, noUnexplained)
        assertRefused(judge(check), "Explains decision ${noUnexplained}", "UNEXPLAINED")
    }

    @Test
    void aMustHoldDecisionWithFirstMatchOnlyIsRefused() {
        String firstOnly = createDecision("Y", ["NOT_CLOSED"])
        String check = seedCheck(A, "must-hold-first-only")
        decide(A, check, firstOnly, explains)
        assertRefused(judge(check), "Must hold decision ${firstOnly}", "firstMatchOnly")
    }

    @Test
    void anExplainsDecisionWithoutFirstMatchOnlyIsRefused() {
        String allMatches = createDecision("N", ["UNEXPLAINED"])
        String check = seedCheck(A, "explains-all-matches")
        decide(A, check, mustHold, allMatches)
        assertRefused(judge(check), "Explains decision ${allMatches}", "firstMatchOnly")
    }

    @Test
    void judgingWithAuthzOffIsRefused() {
        Map out = collect { [findings: JudgeSupport.judge(ec, checkA, facts(), statementsByClaim)] }
        assertRefused(out, "authz")
    }

    @Test
    void tenantBCannotJudgeTenantAsCheckNorUseTenantAsDecisions() {
        Map before = snapshot()
        actAs(EDITOR_USERNAME, B)
        Map refused = judge(checkA)
        assertRefused(refused, "Check ${checkA} does not exist")
        assertNull(refused.out?.findings, "no findings")

        // B's own check, its decision rows planted to point at A's decisions: A's decisions read as "does not exist".
        String checkB = seedCheck(B, "borrowed-decisions")
        decide(B, checkB, mustHold, explains)
        Map before2 = snapshot()
        Map borrowed = judge(checkB)
        assertRefused(borrowed, "decision ${mustHold}", "does not exist")
        assertNull(borrowed.out?.findings, "no findings")
        assertEquals(before2, snapshot(), "no row written or changed anywhere")
        before.each { String entityName, List rowsBefore ->
            if (!(entityName in ["darpan.audit.AuditCheck", "darpan.judge.CheckDecision"])) assertEquals(rowsBefore, before2[entityName], entityName)
        }
        assertEquals([A], rows("rules.RuleSet", [ruleSetId: mustHold])*.companyUserGroupId)
    }

    /**
     * DAR-OPS-029 S04, invariant 3 (determinism). Pairs reach the judge in the order Spark's repartition hands them back, which
     * is not stable from run to run. The same pairs in reverse order must give the same findings in the same order, so a replay
     * of one capture writes the same judged-findings.json.
     */
    @Test
    void theSamePairsInAnyOrderGiveTheSameFindings() {
        Map forward = judged()
        Map reversed = judge(checkA, true)
        assertEquals([], reversed.errors)
        assertEquals(forward.findings, reversed.out.findings, "findings follow the compare id, not the order pairs arrive in")
        assertEquals(forward.warnings, reversed.out.warnings)
    }

    /**
     * OC30 (F8, decided 2026-11-28 under the no-hollow-green rule). moqui-rules compares two missing fields as equal, so a Must
     * hold decision that does not read a side's presence cannot see that side's missing record: such a pair breaks no promise.
     * The judging says how many pairs with a side missing broke no promise while that side's presence went unread. P5 lacks
     * file 2 (and is only NOTED, which is no promise); P6 lacks file 1.
     */
    @Test
    void aMustHoldThatDoesNotReadAMissingSidesPresenceSaysHowManyPairsBrokeNoPromise() {
        List<Map> missing = [[compareId: "P5", file1: [status: "closed", amount: "1"], file2: null],
                             [compareId: "P6", file1: null, file2: [status: "open"]]]
        assertEquals(["2 pairs"], presenceWarnings(checkA, missing), "the fixture's Must hold reads no presence at all")

        // Reads file 2's presence only: P5 breaks its promise; P6's missing file 1 still goes unseen.
        String file2Only = createDecision("N", ["NO_FILE_2"])
        storeRule(file2Only, "no file 2", 10, [line("01", "file2_presence", "ABSENT")], "NO_FILE_2")
        meaning(file2Only, "NO_FILE_2", "JOK_FINDING", "File 2 has no record")
        String oneSide = seedCheck(A, "reads-file2-presence")
        decide(A, oneSide, file2Only, explains)
        assertEquals(["1 pair"], presenceWarnings(oneSide, missing), "a decision reading only file 2's presence does not see file 1 missing")

        // Reads both sides' presence: nothing is said.
        String bothSides = createDecision("N", ["NO_FILE_1", "NO_FILE_2"])
        storeRule(bothSides, "no file 2", 10, [line("01", "file2_presence", "ABSENT")], "NO_FILE_2")
        storeRule(bothSides, "no file 1", 20, [line("01", "file1_presence", "ABSENT")], "NO_FILE_1")
        meaning(bothSides, "NO_FILE_2", "JOK_FINDING", "File 2 has no record")
        meaning(bothSides, "NO_FILE_1", "JOK_FINDING", "File 1 has no record")
        String guarded = seedCheck(A, "reads-both-presences")
        decide(A, guarded, bothSides, explains)
        assertEquals([], presenceWarnings(guarded, missing))
    }

    /** No hollow green: a cause that has no meaning (no DecisionOutcome row) is shown as a finding, never hidden, and said. */
    @Test
    void aCauseWithNoMeaningIsShownNotHiddenAndSaysSo() {
        String unmeant = createDecision("Y", ["MYSTERY", "UNEXPLAINED"])
        storeRule(unmeant, "mystery", 30, [line("01", "claim", "NOT_CLOSED")], "MYSTERY")
        meaning(unmeant, "UNEXPLAINED", "JOK_FINDING", "Unexplained")
        String check = seedCheck(A, "unmeant-cause")
        decide(A, check, mustHold, unmeant)
        Map out = judge(check)
        assertEquals([], out.errors)
        List<Map> mystery = out.out.findings.findAll { it.causeOutcomeId == "MYSTERY" }
        assertEquals(["P1", "P2"], mystery*.recordKey, "both NOT_CLOSED pairs are shown")
        assertEquals(["JOK_FINDING"], mystery*.outcomeKindEnumId.unique())
        assertEquals(["MYSTERY"], mystery*.diagnosisText.unique(), "with the cause's id, as it has no label")
        assertTrue(out.out.warnings.contains("Outcome ${unmeant}|MYSTERY of check ${check} has no meaning (DecisionOutcome), so it is shown as a finding: 2 findings".toString()),
                "${out.out.warnings}")
    }

    /**
     * R2 (DAR-OPS-029 S04): an ask or parameter whose fact field name would pass rules.FactField's 40 characters gets a bounded
     * name (cut, then a short hash of the full name), the same on every run, so generate#PairFactType neither fails nor adds a
     * field twice, and buildFact puts the value under that name. A name of 40 or fewer characters is kept as it is.
     */
    @Test
    void aLongAskOrParameterNameGetsTheSameBoundedFieldNameOnEveryRun() {
        String check = seedCheck(A, "long-names")
        insert("darpan.judge.CheckAsk", [checkId: check, askName: "orderInformationFromShopify", companyUserGroupId: A, subjectSide: "FILE_1",
                idField: "orderId", systemEnumId: "SHOPIFY", sourceConfigId: "CFG", keyField: "name", many: "N",
                keepRecordFields: "financialStatusAfterRefund,tag"])
        insert("darpan.judge.CheckParameterSet", [checkId: check, parameterSetId: "PS1", companyUserGroupId: A, label: "default"])
        insert("darpan.judge.CheckParameter", [checkId: check, parameterSetId: "PS1", parameterName: "minimumRefundAmountBeforeAnAlertIsRaised",
                companyUserGroupId: A, parameterValue: "5"])
        Map params = [checkId: check, sampleKeys: [file1: ["status"], file2: ["status"]]]
        Map first = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", params))
        List<String> names = rows("rules.FactField", [factTypeId: first.factTypeId])*.fieldName
        assertTrue(names.every { it.length() <= 40 }, "${names}")
        String askField = PairFactSupport.boundedFieldName("ask_orderInformationFromShopify_financialStatusAfterRefund")
        String paramField = PairFactSupport.boundedFieldName("param_minimumRefundAmountBeforeAnAlertIsRaised")
        assertTrue(askField in names && paramField in names, "${names}")
        assertTrue("ask_orderInformationFromShopify_tag" in names, "a short name is kept as it is: ${names}")

        Map again = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", params))
        assertEquals(first.factTypeId, again.factTypeId)
        assertEquals(names.sort(), rows("rules.FactField", [factTypeId: first.factTypeId])*.fieldName.sort(), "a rerun adds no field")

        Map sideState = [factTypeId: first.factTypeId, file1: [prefix: "file1", setAside: [], keyNames: first.keyNames.file1],
                         file2: [prefix: "file2", setAside: [], keyNames: first.keyNames.file2]]
        Map fact = PairFactSupport.buildFact([compareId: "L1", file1: [status: "open"], file2: null], sideState,
                [minimumRefundAmountBeforeAnAlertIsRaised: "5"],
                ["ask_orderInformationFromShopify_financialStatusAfterRefund": "refunded"]).fact as Map
        assertEquals("refunded", fact[askField])
        assertEquals("5", fact[paramField])
        assertTrue(fact.keySet().every { (it as String).length() <= 40 }, "${fact.keySet()}")
    }

    /** The count in the presence warning for one check's judging of the fixture plus extra pairs; empty when there is none. */
    private List<String> presenceWarnings(String checkId, List<Map> extra) {
        Map out = judge(checkId, false, extra)
        assertEquals([], out.errors)
        return out.out.warnings.findAll { it.contains("does not read the presence of a missing side") }.collect { String w ->
            assertTrue(w.startsWith("Must hold decision ") && w.contains(" of check ${checkId} ".toString()) &&
                    w.endsWith("with that side missing (ABSENT or SET_ASIDE) broke no promise, so a missing record reads as clean"), w)
            (w =~ /: (\d+ pairs?) with/)[0][1] as String
        }
    }

    @Test
    void judgedFindingsRecordWithTheirLineage() {
        List<Map> findings = judged().findings
        Map out = ec.service.sync().name("audit.AuditLedgerServices.record#CheckFindings").parameters([companyUserGroupId: A,
                checkId: checkA, reportDate: Date.valueOf("2026-10-07"), checkStamp: Timestamp.valueOf("2026-10-07 06:10:00"),
                findings: findings]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(3, out.foundCount)
        List saved = (out.findingIds as List).collect { rows("darpan.audit.AuditFinding", [findingId: it])[0] }
        assertEquals(findings.collect { [it.recordKey, it.claimRuleSetId, it.claimRuleId, it.causeRuleSetId, it.causeRuleId] },
                saved.collect { [it.recordKey, it.claimRuleSetId, it.claimRuleId, it.causeRuleSetId, it.causeRuleId] })
        assertEquals(["The order is still open", "The amount is too small", "The order is still open"], saved*.headline)
    }

    // ---------------------------------------------------------------------------------------

    /**
     * The fixture's four pairs (and any extra) through Task 6's batches and buildFact: two per batch, or, reversed, last first
     * three per batch, so the batch boundaries differ too.
     */
    private Iterator<List<Map>> facts(boolean reversed = false, List<Map> extra = []) {
        List<Map> pairs = [
                [compareId: "P1", file1: [status: "open", amount: "5"], file2: [status: "pending"]],
                [compareId: "P2", file1: [status: "open", amount: "9"], file2: [status: "open"]],
                [compareId: "P3", file1: [status: "closed", amount: "9"], file2: [status: "open"]],
                [compareId: "P4", file1: [status: "closed", amount: "5"], file2: [status: "hide"]]]
        pairs = pairs + extra
        if (reversed) pairs = pairs.reverse()
        Map sideState = [factTypeId: factTypeId, file1: [prefix: "file1", setAside: [], keyNames: keyNames.file1],
                         file2: [prefix: "file2", setAside: [], keyNames: keyNames.file2]]
        List<List<Map>> batches = []
        PairFactSupport.batches(pairs.iterator(), reversed ? 3 : 2).each { List<Map> batch ->
            batches.add(batch.collect { PairFactSupport.buildFact(it, sideState, [:], [:]) })
        }
        return batches.iterator()
    }

    private Map judged() {
        Map out = judge(checkA)
        assertEquals([], out.errors)
        return out.out
    }

    private Map judge(String checkId, boolean reversed = false, List<Map> extra = []) {
        return underFacadeGrantDo {
            List<String> warnings = []
            List<Map> findings = JudgeSupport.judge(ec, checkId, facts(reversed, extra), statementsByClaim, warnings)
            return [findings: findings, warnings: warnings]
        }
    }

    private String seedCheck(String tenant, String code) {
        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: tenant, checkCode: code, label: "check ${code}".toString()])
        return checkId
    }

    /** No service writes CheckDecision yet (P2-11), so the rows are inserted as the plan 4 migration will write them. */
    private void decide(String tenant, String checkId, String mustHoldId, String explainsId) {
        insert("darpan.judge.CheckDecision", [checkId: checkId, stageEnumId: "CDS_MUST_HOLD", companyUserGroupId: tenant, ruleSetId: mustHoldId])
        insert("darpan.judge.CheckDecision", [checkId: checkId, stageEnumId: "CDS_EXPLAINS", companyUserGroupId: tenant, ruleSetId: explainsId])
    }

    private String createDecision(String firstMatchOnly, List<String> outcomeIds) {
        Map created = ok(underFacadeGrant("create#rules.RuleSet", [description: "judge decision", firstMatchOnly: firstMatchOnly,
                factTypes: [[factTypeId: factTypeId]], outcomes: outcomeIds.collect { [outcomeId: it] }]))
        assertNotNull(created.ruleSetId)
        return created.ruleSetId as String
    }

    private String storeRule(String ruleSetId, String ruleName, int priority, List<Map> lines, String outcomeId) {
        Map stored = ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: lines, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]))
        assertNotNull(stored.ruleId)
        return stored.ruleId as String
    }

    private Map line(String seq, String fieldName, String compareValue) {
        return [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: fieldName,
                operatorEnumId: "ENTCO_EQUALS", compareValue: compareValue]
    }

    private void meaning(String ruleSetId, String outcomeId, String kind, String label, String fixText = null, String fixOwner = null,
                         String fixWhereEnumId = null) {
        ok(underFacadeGrant("judge.JudgeConfigServices.store#DecisionOutcome", [ruleSetId: ruleSetId, outcomeId: outcomeId,
                outcomeKindEnumId: kind, label: label, fixText: fixText, fixOwner: fixOwner, fixWhereEnumId: fixWhereEnumId]))
    }

    private static Map ok(Map result) {
        assertEquals([], result.errors, "fixture call failed")
        return result.out as Map
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        return underFacadeGrantDo { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
    }

    /** Runs inside a facade service's frame, authz ON, for the logged-in user, as a production facade call does. */
    private Map underFacadeGrantDo(Closure<Map> call) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect(call)
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: errors ? null : out, errors: errors]
    }

    private static void assertRefused(Map result, String... expected) {
        assertTrue(result.errors.any { String error -> expected.every { error?.contains(it.toString()) } },
                "expected a refusal containing ${expected}, got errors ${result.errors}")
    }

    private Map<String, List<Map>> snapshot() {
        return WATCHED.collectEntries { String entityName ->
            [(entityName): ec.entity.find(entityName).disableAuthz().useCache(false).list()
                    .collect { new TreeMap(it.getMap()) }.sort { it.toString() }]
        } as Map<String, List<Map>>
    }

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
