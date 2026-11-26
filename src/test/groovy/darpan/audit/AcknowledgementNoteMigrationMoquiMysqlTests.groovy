package darpan.audit

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
 * DAR-UI-152. The registered migration as prod runs it: Moqui on MySQL (mysql8 conf, Bitronix XA, as prod) with the
 * service called inside an active transaction, the way run#Migration calls it. Prod 3.5.1's run failed here with
 * "XAER_RMFAIL ... global transaction is in the ACTIVE state": MySQL refuses DDL on a connection enlisted in an XA
 * transaction, which neither the dry run nor the plain-JDBC AcknowledgementNoteMigrationMysqlTests could show.
 * Opt-in: `./gradlew :runtime:component:darpan:mysqlTest` with DARPAN_TEST_DB_* as in MysqlLoginTestSupport.
 */
@Tag("mysql")
class AcknowledgementNoteMigrationMoquiMysqlTests {

    static ExecutionContext ec
    static final String SVC = "audit.AuditLedgerServices.migrate#AcknowledgementNoteNullable"

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent, "ack-note-migration")
        // Moqui creates the table on first use; then make the column NOT NULL, as a database made before 3.4.16 has it.
        ec.entity.find(AcknowledgementNoteMigration.ENTITY).disableAuthz().count()
        withConnection { Connection c ->
            c.createStatement().execute("UPDATE ${table()} SET NOTE = '' WHERE NOTE IS NULL")
            c.createStatement().execute("ALTER TABLE ${table()} MODIFY NOTE VARCHAR(4095) NOT NULL")
        }
    }

    @AfterAll
    static void teardown() {
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theServiceInsideATransactionMakesTheColumnNullableOnce() {
        assertEquals("NO", nullable(), "starts NOT NULL")

        Map dry = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: true]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals("Would run: ALTER TABLE ${table()} MODIFY NOTE VARCHAR(4095) NULL".toString(), (dry.messages as List)[0])
        assertEquals("NO", nullable(), "a dry run changes nothing")

        Map ran = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(ran.ok as boolean, ran.toString())
        assertEquals("YES", nullable(), "nullable after the run")

        Map again = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue((again.messages as List)[0].toString().contains("already allows no value"), again.toString())
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

    private static String table() { ec.entity.getEntityDefinition(AcknowledgementNoteMigration.ENTITY).getFullTableName() }

    private static String nullable() {
        String out = null
        withConnection { Connection c ->
            def ps = c.prepareStatement("""SELECT IS_NULLABLE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'NOTE'""")
            ps.setString(1, table())
            def rs = ps.executeQuery()
            out = rs.next() ? rs.getString(1) : null
        }
        return out
    }

    /** A connection outside any transaction (DDL and information_schema reads). */
    private static void withConnection(Closure work) {
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName(AcknowledgementNoteMigration.ENTITY))
            try { work.call(c) } finally { c.close() }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }
}
