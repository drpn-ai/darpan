package darpan.migration

import darpan.facade.auth.support.MysqlLoginTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext

import java.nio.file.Paths
import java.sql.Connection

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-424. NSRESTLET_DROP as prod runs it: Moqui on MySQL (mysql8 conf, Bitronix XA) with the service called inside an
 * active transaction, the way run#Migration calls it (MySQL refuses DDL on a connection enlisted in an XA transaction). The
 * code no longer defines the RESTlet table or columns, so setup lays them out as prod and SM have them: NS_RESTLET_CONFIG with
 * the __TABLE_BOOTSTRAP__ placeholder, the two NS_RESTLET_CONFIG_ID columns with their foreign keys (RSCSR_NSREST,
 * RECAUTSRC_NSREST; MySQL will not drop a column a key uses), NS_AUTH_CONFIG.NETSUITE_ACCOUNT and the SCFG_NS_RESTLET
 * enumeration row. Opt-in: `./gradlew :runtime:component:darpan:mysqlTest` with DARPAN_TEST_DB_* as in MysqlLoginTestSupport.
 * The database persists between runs, so setup makes its own starting state.
 */
@Tag("mysql")
class NsRestletDropMoquiMysqlTests {

    static ExecutionContext ec
    static final String SVC = "migration.MigrationServices.migrate#NsRestletDrop"
    static final Map<String, String> SOURCES = [RULE_SET_COMPARE_SOURCE: "RSCSR_NSREST", RECONCILIATION_AUTOMATION_SOURCE: "RECAUTSRC_NSREST"]

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent, "nsrestlet-drop")
        // Moqui creates each table on first use.
        ["darpan.rule.RuleSetCompareSource", "darpan.reconciliation.ReconciliationAutomationSource", "darpan.reconciliation.NsAuthConfig",
         "moqui.basic.Enumeration", "darpan.auth.ConfigTenantAccess", "darpan.reconciliation.SourceConfigEndpointAccess",
         "moqui.security.EntityFilter", "darpan.rule.RuleSet", "darpan.rule.RuleSetCompareScope",
         "darpan.reconciliation.ReconciliationAutomation"].each {
            ec.entity.find(it).disableAuthz().count()
        }
        withConnection { Connection c ->
            SOURCES.each { String table, String fk ->
                String stored = NsRestletDrop.storedName(c, table, null)
                if (NsRestletDrop.storedName(c, stored, "NS_RESTLET_CONFIG_ID")) {
                    // Every key on the column, whatever its name: a database made by an older model can hold an
                    // auto-named one (RULE_SET_COMPARE_SOURCE_ibfk_2 on the shared test database).
                    NsRestletDrop.foreignKeysOn(c, stored, "NS_RESTLET_CONFIG_ID").each { sql(c, "ALTER TABLE ${stored} DROP FOREIGN KEY ${it}") }
                    sql(c, "ALTER TABLE ${stored} DROP COLUMN NS_RESTLET_CONFIG_ID")
                }
            }
            sql(c, "DROP TABLE IF EXISTS NS_RESTLET_CONFIG")
            String authTable = NsRestletDrop.storedName(c, "NS_AUTH_CONFIG", null)
            if (!NsRestletDrop.storedName(c, authTable, "NETSUITE_ACCOUNT")) sql(c, "ALTER TABLE ${authTable} ADD COLUMN NETSUITE_ACCOUNT VARCHAR(63)")

            // MySQL takes a foreign key only between columns of the same type, charset and collation: copy a source id column's.
            String idType = columnType(c, NsRestletDrop.storedName(c, "RULE_SET_COMPARE_SOURCE", null), "COMPARE_SCOPE_ID")
            sql(c, "CREATE TABLE NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID ${idType} NOT NULL PRIMARY KEY, COMPANY_USER_GROUP_ID ${idType}, " +
                    "IS_ACTIVE CHAR(1))")
            sql(c, "INSERT INTO NS_RESTLET_CONFIG VALUES ('__TABLE_BOOTSTRAP__', NULL, 'N')")
            SOURCES.each { String table, String fk ->
                String stored = NsRestletDrop.storedName(c, table, null)
                sql(c, "ALTER TABLE ${stored} ADD COLUMN NS_RESTLET_CONFIG_ID ${idType}")
                sql(c, "ALTER TABLE ${stored} ADD CONSTRAINT ${fk} FOREIGN KEY (NS_RESTLET_CONFIG_ID) REFERENCES NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID)")
            }
            // A second key MySQL names itself (…_ibfk_N), as an older schema can carry: the run finds keys by column.
            sql(c, "ALTER TABLE ${NsRestletDrop.storedName(c, 'RULE_SET_COMPARE_SOURCE', null)} ADD FOREIGN KEY (NS_RESTLET_CONFIG_ID) " +
                    "REFERENCES NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID)")
        }
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "DarpanSharedConfigType", description: "test"]).createOrUpdate()
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "SCFG_NS_RESTLET", enumTypeId: "DarpanSharedConfigType",
                    description: "NetSuite Restlet config"]).createOrUpdate()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    static void teardown() {
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theServiceInsideATransactionReportsThenDropsKeysColumnsAndTheTableOnce() {
        Map dry = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: true]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List<String> dryWords = dry.messages as List<String>
        assertTrue(dryWords.any { it.startsWith("NS_RESTLET_CONFIG: 1 RESTlet config(s): (no tenant) 1 (0 active); 1 of them the __TABLE_BOOTSTRAP__") },
                "${dryWords}")
        assertTrue(dryWords.contains("Rule-set sides naming a RESTlet: none."), "${dryWords}")
        assertTrue(dryWords.any { it ==~ /Would run: ALTER TABLE \w+ DROP FOREIGN KEY RSCSR_NSREST/ }, "MySQL's own syntax: ${dryWords}")
        assertTrue(dryWords.any { it ==~ /Would run: ALTER TABLE \w+ DROP FOREIGN KEY RECAUTSRC_NSREST/ }, "${dryWords}")
        assertTrue(dryWords.any { it ==~ /(?i)Would run: ALTER TABLE \w+ DROP FOREIGN KEY \w+_ibfk_\d+/ }, "the key MySQL named: ${dryWords}")
        assertNotNull(hasColumn("RULE_SET_COMPARE_SOURCE", "NS_RESTLET_CONFIG_ID"), "a dry run changes nothing")

        Map ran = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(ran.ok as boolean, ran.toString())
        assertNull(hasColumn("RULE_SET_COMPARE_SOURCE", "NS_RESTLET_CONFIG_ID"), "${ran.messages}")
        assertNull(hasColumn("RECONCILIATION_AUTOMATION_SOURCE", "NS_RESTLET_CONFIG_ID"), "${ran.messages}")
        assertNull(hasColumn("NS_AUTH_CONFIG", "NETSUITE_ACCOUNT"), "${ran.messages}")
        assertNull(hasTable("NS_RESTLET_CONFIG"), "${ran.messages}")
        assertNull(ec.entity.find("moqui.basic.Enumeration").condition("enumId", "SCFG_NS_RESTLET").disableAuthz().useCache(false).one())

        Map again = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(0, again.rowsUpdated, "a second run changes nothing: ${again.messages}")
    }

    /** As run#Migration: the service runs inside a transaction the caller began. */
    private static Map inTransaction(Closure<Map> work) {
        boolean began = ec.transaction.begin(null)
        try {
            Map out = work.call()
            ec.transaction.commit(began)
            return out
        } catch (Throwable t) {
            ec.transaction.rollback(began, "test", t)
            throw t
        }
    }

    private static String hasTable(String table) {
        String found = null
        withConnection { Connection c -> found = NsRestletDrop.storedName(c, table, null) }
        return found
    }

    private static String hasColumn(String table, String column) {
        String found = null
        withConnection { Connection c -> found = NsRestletDrop.storedName(c, NsRestletDrop.storedName(c, table, null), column) }
        return found
    }

    private static String columnType(Connection c, String table, String column) {
        def ps = c.prepareStatement('''SELECT CONCAT(COLUMN_TYPE, ' CHARACTER SET ', CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME)
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?''')
        try {
            ps.setString(1, table)
            ps.setString(2, column)
            def rs = ps.executeQuery()
            String type = rs.next() ? rs.getString(1) : null
            assertNotNull(type, "test precondition: ${table}.${column} exists")
            return type
        } finally { ps.close() }
    }

    private static void sql(Connection c, String statement) { c.createStatement().withCloseable { it.execute(statement) } }

    /** A connection outside any transaction (DDL and metadata reads). */
    private static void withConnection(Closure work) {
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName(NsRestletDrop.GROUP_ENTITY))
            try { work.call(c) } finally { c.close() }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }
}
