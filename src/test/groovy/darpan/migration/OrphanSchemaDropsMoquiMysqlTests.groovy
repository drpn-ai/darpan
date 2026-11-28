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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029. ORPHAN_SCHEMA_DROPS as prod runs it: Moqui on MySQL (mysql8 conf, Bitronix XA) with the service called inside
 * an active transaction, the way run#Migration calls it (MySQL refuses DDL on a connection enlisted in an XA transaction,
 * prod 3.5.1). The two reconciliation orphans are made as an old database has them, one with its foreign key to
 * RECONCILIATION_RUN. MySQL's DROP COLUMN runs through the same seam on a scratch table, since the real column entries wait
 * for their fields to leave the model. Opt-in: `./gradlew :runtime:component:darpan:mysqlTest` with DARPAN_TEST_DB_* as in
 * MysqlLoginTestSupport. The database persists between runs, so setup makes its own starting state.
 */
@Tag("mysql")
class OrphanSchemaDropsMoquiMysqlTests {

    static ExecutionContext ec
    static final String SVC = "migration.MigrationServices.migrate#OrphanSchemaDrops"
    static final String SCRATCH = "S35_ORPHAN_SCRATCH"

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent, "orphan-schema-drops")
        ec.entity.find(OrphanSchemaDrops.GROUP_ENTITY).disableAuthz().count()   // Moqui creates RECONCILIATION_RUN on first use
        withConnection { Connection c ->
            ["RUN_DATA_DOCUMENT", "RUN_SYSTEM_INSTANCE", SCRATCH].each { sql(c, "DROP TABLE IF EXISTS ${it}") }
            // MySQL takes a foreign key only between columns of the same type, charset and collation: copy the run id's own.
            String runTable = OrphanSchemaDrops.storedName(c, "RECONCILIATION_RUN", null)
            String runId = c.prepareStatement('''SELECT CONCAT(COLUMN_TYPE, ' CHARACTER SET ', CHARACTER_SET_NAME, ' COLLATE ', COLLATION_NAME)
                    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'RECONCILIATION_RUN_ID' ''')
                    .with { it.setString(1, runTable); def rs = it.executeQuery(); rs.next() ? rs.getString(1) : null }
            assertTrue(runId != null, "test precondition: ${runTable}.RECONCILIATION_RUN_ID exists")
            sql(c, "CREATE TABLE RUN_DATA_DOCUMENT (RECONCILIATION_RUN_ID ${runId} NOT NULL, DATA_DOCUMENT_ID VARCHAR(40) NOT NULL, " +
                    "PRIMARY KEY (RECONCILIATION_RUN_ID, DATA_DOCUMENT_ID), CONSTRAINT S35_RDD_RUN FOREIGN KEY (RECONCILIATION_RUN_ID) " +
                    "REFERENCES ${runTable} (RECONCILIATION_RUN_ID))")
            sql(c, "CREATE TABLE RUN_SYSTEM_INSTANCE (RECONCILIATION_RUN_ID VARCHAR(40) NOT NULL, SYSTEM_MESSAGE_REMOTE_ID VARCHAR(40) NOT NULL)")
            sql(c, "INSERT INTO RUN_SYSTEM_INSTANCE VALUES ('R1', 'SMR1')")
            sql(c, "CREATE TABLE ${SCRATCH} (ID VARCHAR(40) PRIMARY KEY, OLD_HINT VARCHAR(255))")
            sql(c, "INSERT INTO ${SCRATCH} VALUES ('A', 'x'), ('B', NULL)")
        }
    }

    @AfterAll
    static void teardown() {
        if (ec != null) withConnection { Connection c -> sql(c, "DROP TABLE IF EXISTS ${SCRATCH}") }
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theServiceInsideATransactionDropsTheOrphanTablesOnce() {
        Map dry = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: true]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List<String> dryWords = dry.messages as List<String>
        assertTrue(dryWords.any { it.startsWith("RUN_DATA_DOCUMENT: would run DROP TABLE RUN_DATA_DOCUMENT (0 row(s)") }, "${dryWords}")
        assertTrue(dryWords.any { it.startsWith("RUN_SYSTEM_INSTANCE: would run DROP TABLE RUN_SYSTEM_INSTANCE (1 row(s)") }, "${dryWords}")
        assertTrue(dryWords.any { it.startsWith("TENANT_CHAT_SPACE.GOOGLE_CHAT_WEBHOOK_URL: ") && !it.contains("would run") }, "${dryWords}")
        assertTrue(hasTable("RUN_DATA_DOCUMENT") && hasTable("RUN_SYSTEM_INSTANCE"), "a dry run changes nothing")

        Map ran = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(ran.ok as boolean, ran.toString())
        assertFalse(hasTable("RUN_DATA_DOCUMENT") || hasTable("RUN_SYSTEM_INSTANCE"), "${ran.messages}")
        assertTrue(hasTable("RECONCILIATION_RUN"), "the table the orphan referenced stays")

        Map again = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue((again.messages as List<String>).findAll { it.startsWith("RUN_") }
                .every { it.endsWith("not in this database; nothing to do.") }, "${again.messages}")
        assertEquals(0, again.rowsUpdated, "a second run drops nothing: ${again.messages}")
    }

    @Test
    void mysqlDropsAColumnTheModelNoLongerHas() {
        Map out = null
        withConnection { Connection c ->
            out = OrphanSchemaDrops.apply(c, [[table: SCRATCH, column: "OLD_HINT", why: "test"]], [(SCRATCH): ["ID"] as Set], false)
        }
        assertEquals([], out.errors)
        assertEquals(1, out.dropped, "${out.messages}")
        assertEquals("${SCRATCH}.OLD_HINT: ran ALTER TABLE ${SCRATCH} DROP COLUMN OLD_HINT (1 value(s)).".toString(), out.messages[0])
        withConnection { Connection c -> assertEquals(null, OrphanSchemaDrops.storedName(c, SCRATCH, "OLD_HINT")) }
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

    private static boolean hasTable(String table) {
        boolean found = false
        withConnection { Connection c -> found = OrphanSchemaDrops.storedName(c, table, null) != null }
        return found
    }

    private static void sql(Connection c, String statement) { c.createStatement().withCloseable { it.execute(statement) } }

    /** A connection outside any transaction (DDL and metadata reads). */
    private static void withConnection(Closure work) {
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName(OrphanSchemaDrops.GROUP_ENTITY))
            try { work.call(c) } finally { c.close() }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }
}
