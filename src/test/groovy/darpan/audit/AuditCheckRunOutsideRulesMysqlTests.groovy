package darpan.audit

import darpan.facade.auth.support.MysqlLoginTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext

import java.nio.file.Paths
import java.sql.Connection
import java.sql.Date

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 D11 (S13). AuditCheckRun.outsideRulesJson on MySQL, as prod has it: Moqui adds the column at startup
 * (entity_add_missing_startup), and it must be LONGTEXT, because 200 rows of JSON do not fit text-long's VARCHAR(4095)
 * (MySQL strict mode refuses the write). H2 has no such limit, so only this test can see it.
 * Opt-in: `./gradlew :runtime:component:darpan:mysqlTest` with DARPAN_TEST_DB_* as in MysqlLoginTestSupport.
 */
@Tag("mysql")
class AuditCheckRunOutsideRulesMysqlTests {

    static ExecutionContext ec
    static final String SVC = "audit.AuditLedgerServices"
    static final String RUN = "OR${System.currentTimeMillis() % 100000000}"   // the database outlives a run
    static final String TENANT = "${RUN}_T".toString()
    static final String CHECK = "${RUN}_C".toString()

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent, "outside-rules")
        ec.artifactExecution.disableAuthz()   // set-up and ledger calls as the scheduled check runs them; own JVM (forkEvery 1)
        ["component://darpan/data/AuditSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml"].each {
            ec.entity.makeDataLoader().location(it).load()
        }
        insert("moqui.security.UserGroup", [userGroupId: TENANT, description: TENANT])
        insert("darpan.audit.Audit", [auditId: "${RUN}_A".toString(), companyUserGroupId: TENANT, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.AuditStatement", [statementId: "${RUN}_R4".toString(), companyUserGroupId: TENANT,
                auditId: "${RUN}_A".toString(), statementCode: "R4", categoryEnumId: "AUDC_REFUNDS_CREDITS",
                weightEnumId: "AUDSW_DERIVED", statementText: "R4 text"])
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: TENANT, checkCode: "outside", label: "outside"])
        insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "${RUN}_R4".toString(), companyUserGroupId: TENANT])
    }

    @AfterAll
    static void teardown() {
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void twoHundredOutsideRowsFitTheColumnOnMysql() {
        assertEquals("longtext", columnType("OUTSIDE_RULES_JSON"), "added at startup as LONGTEXT, not VARCHAR(4095)")

        Map base = [companyUserGroupId: TENANT, checkId: CHECK, reportDate: Date.valueOf("2026-10-14")]
        ec.service.sync().name("${SVC}.record#CheckRun").parameters(base + [sourceReads: [[systemEnumId: "OMS_RETURNS_GQL",
                statusEnumId: "AUDSR_COMPLETE", recordCount: 250]]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List rows = (1..200).collect { [recordKey: "ORDER-${it}".toString(), diffType: "FIELD_MISMATCH", bucket: "RULE",
                note: "No statement owns this row: the rule that raised it is not linked to any statement.",
                records: [[systemEnumId: "OMS", recordType: "orderItem", recordId: "ORDER-${it}-1".toString()]]] }
        ec.service.sync().name("${SVC}.record#CheckFindings").parameters(base + [findings: [],
                outsideRules: [count: 250, rows: rows]]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)

        Map run = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK).disableAuthz().useCache(false).one().getMap()
        String json = run.outsideRulesJson as String
        assertTrue(json.length() > 4095, "the payload is past text-long's VARCHAR(4095): ${json.length()} chars")
        assertEquals(250L, run.outsideRulesCount as Long)
        assertEquals(200, (new groovy.json.JsonSlurper().parseText(json) as List).size(), "kept whole, not truncated")
    }

    private static void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    private static String columnType(String column) {
        String table = ec.entity.getEntityDefinition("darpan.audit.AuditCheckRun").getFullTableName()
        Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.audit.AuditCheckRun"))
        try {
            def ps = c.prepareStatement("""SELECT DATA_TYPE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?""")
            ps.setString(1, table)
            ps.setString(2, column)
            def rs = ps.executeQuery()
            return rs.next() ? rs.getString(1) : null
        } finally {
            c.close()
        }
    }
}
