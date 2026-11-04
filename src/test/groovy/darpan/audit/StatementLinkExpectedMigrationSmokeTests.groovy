package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-196. Migration STATEMENT_LINK_EXPECTED, shaped on prod gorjana's R1 (2026-11-04): the link listed the causes
 * that count, so the ones it left out (a grace cause, a brain-expected cause) become the run's expected rules, a HIDE rule
 * stays hidden, and the list is cleared. A run two links read with different lists is a conflict, left untouched; another
 * tenant's rows are its own. Dry run first, then the run, then a second run that changes nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementLinkExpectedMigrationSmokeTests {

    private static final String T = "TSLE"
    private static final String T2 = "TSLE2"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "statement-link-expected")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Conclusion"])
            ["TSLE_SHIPPED", "TSLE_GRACE", "TSLE_GIVEN_BACK", "TSLE_RETIRED", "TSLE_A", "TSLE_B"].each {
                insert("moqui.basic.Enumeration", [enumId: it, enumTypeId: "DarpanConclusion", enumCode: it, description: it])
            }
            [T, T2].each { String t ->
                insert("moqui.security.UserGroup", [userGroupId: t, description: t])
                insert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
                ["R1", "R2", "R3"].each { String code ->
                    insert("darpan.audit.AuditStatement", [statementId: "${t}_${code}".toString(), companyUserGroupId: t,
                            auditId: "${t}_MC".toString(), statementCode: code, categoryEnumId: "AUDC_RETURNS",
                            weightEnumId: "AUDSW_FIRM", statementText: code])
                }
                // R1's run: a fix cause, a grace cause, a brain-expected cause, a hidden cause.
                run(t, "${t}_RS1", [[10L, "TSLE_SHIPPED", null, null], [20L, "TSLE_GRACE", null, null],
                                    [5L, "TSLE_GIVEN_BACK", null, "BRAIN"], [30L, "TSLE_RETIRED", "HIDE", null]])
                check(t, "${t}_C1", "${t}_RS1", "${t}_R1", "TSLE_SHIPPED,UNEXPLAINED")
            }
            // A run two of T's links read with different lists: a conflict.
            run(T, "TSLE_RS2", [[10L, "TSLE_A", null, null], [20L, "TSLE_B", null, null]])
            check(T, "TSLE_C2", "TSLE_RS2", "TSLE_R2", "TSLE_A")
            check(T, "TSLE_C3", "TSLE_RS2", "TSLE_R3", "TSLE_B")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theLeftOutCausesBecomeTheRunsExpectedOnesOnceAndOnlyOnce() {
        Map dry = migrate(true)
        assertEquals([4, 2], [dry.rowsUpdated, dry.linksCleared], "${dry.messages}")
        assertEquals([null, null], [outcome("${T}_RS1", 20L), outcome("${T2}_RS1", 20L)], "a dry run writes nothing")
        assertTrue((dry.messages as List).any { it.toString().contains("TSLE_RS2") && it.toString().contains("left as it is") }, "${dry.messages}")

        Map done = migrate(false)
        assertEquals([4, 2], [done.rowsUpdated, done.linksCleared], "${done.messages}")
        [T, T2].each { String t ->
            assertEquals([null, "EXPECTED", "EXPECTED", "HIDE"],
                    [outcome("${t}_RS1", 10L), outcome("${t}_RS1", 20L), outcome("${t}_RS1", 5L), outcome("${t}_RS1", 30L)], t)
            assertEquals(null, link("${t}_R1", "${t}_C1").conclusionCodes, t)
        }
        assertEquals([null, null], [outcome("TSLE_RS2", 10L), outcome("TSLE_RS2", 20L)], "a conflicting run is untouched")
        assertEquals(["TSLE_A", "TSLE_B"], [link("TSLE_R2", "TSLE_C2").conclusionCodes, link("TSLE_R3", "TSLE_C3").conclusionCodes])

        Map again = migrate(false)
        assertEquals([0, 0], [again.rowsUpdated, again.linksCleared], "a second run changes nothing: ${again.messages}")
    }

    private Map migrate(boolean dryRun) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return ec.service.sync().name("audit.AuditLedgerServices.migrate#StatementLinkExpected").parameters([dryRun: dryRun]).call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void run(String t, String ruleSetId, List<List> rules) {
        insert("darpan.rule.RuleSet", [ruleSetId: ruleSetId, ruleSetName: ruleSetId, version: "1.0", companyUserGroupId: t])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "${ruleSetId}_S".toString(), ruleSetId: ruleSetId,
                scopeMode: "COMPARE", objectType: "ORDER", companyUserGroupId: t])
        rules.each { List r ->
            insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "${ruleSetId}_S".toString(), sequenceNum: r[0],
                    conclusionEnumId: r[1], outcome: r[2], engine: r[3], appliesToBucket: "MISSING_FROM_FILE_2", companyUserGroupId: t])
        }
    }

    private void check(String t, String checkId, String ruleSetId, String statementId, String codes) {
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: t, checkCode: checkId.toLowerCase().replace('_', '-'),
                label: checkId, savedRunId: ruleSetId])
        insert("darpan.audit.AuditStatementCheck", [statementId: statementId, checkId: checkId, companyUserGroupId: t,
                sequenceNum: 1L, diffTypes: "MISSING_IN_FILE_2", conclusionCodes: codes])
    }

    private String outcome(String ruleSetId, Long seq) {
        return ec.entity.find("darpan.rule.RuleSetConclusionRule").condition("compareScopeId", "${ruleSetId}_S".toString())
                .condition("sequenceNum", seq).disableAuthz().useCache(false).one()?.outcome
    }

    private Map link(String statementId, String checkId) {
        return ec.entity.find("darpan.audit.AuditStatementCheck").condition("statementId", statementId).condition("checkId", checkId)
                .disableAuthz().useCache(false).one().getMap()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
