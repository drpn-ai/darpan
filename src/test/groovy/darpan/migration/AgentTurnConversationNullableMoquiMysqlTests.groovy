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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 OC186. AGENT_TURN_CONVERSATION_NULLABLE as prod runs it: Moqui on MySQL (mysql8 conf, Bitronix XA) with the
 * service called inside an active transaction, the way run#Migration calls it (the XA lesson of ACK_NOTE_NULLABLE, 3.5.1).
 * Then a writer's ledger row (an AgentTurn with no conversation) is written, which a database made before cannot hold.
 * Opt-in: `./gradlew :runtime:component:darpan:mysqlTest` with DARPAN_TEST_DB_* as in MysqlLoginTestSupport.
 */
@Tag("mysql")
class AgentTurnConversationNullableMoquiMysqlTests {

    static ExecutionContext ec
    static final String SVC = "migration.MigrationServices.migrate#AgentTurnConversationNullable"

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent, "agent-turn-conversation")
        // Moqui creates the table nullable from today's entity; a database made before has the column NOT NULL.
        ec.entity.find(AgentTurnConversationNullable.ENTITY).disableAuthz().count()
        withConnection { Connection c ->
            c.createStatement().execute("DELETE FROM ${table()} WHERE ${column()} IS NULL")
            c.createStatement().execute("ALTER TABLE ${table()} MODIFY ${column()} VARCHAR(40) NOT NULL")
        }
    }

    @AfterAll
    static void teardown() {
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theServiceInsideATransactionMakesTheColumnNullableOnceAndAWriterRowFits() {
        assertEquals("NO", nullable(), "starts NOT NULL, as a database made before has it")

        Map dry = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: true]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals("Would run: ALTER TABLE ${table()} MODIFY ${column()} VARCHAR(40) NULL".toString(), (dry.messages as List)[0])
        assertEquals("NO", nullable(), "a dry run changes nothing")

        Map ran = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(ran.ok as boolean, ran.toString())
        assertEquals("YES", nullable(), "nullable after the run")

        Map again = inTransaction { ec.service.sync().name(SVC).parameters([dryRun: false]).call() }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue((again.messages as List)[0].toString().contains("already allows no value"), again.toString())

        // The row OC186 writes: a writer's request, the tenant's, with no conversation, person or grant.
        String turnId = null
        inTransaction {
            def row = ec.entity.makeValue(AgentTurnConversationNullable.ENTITY).setAll([companyUserGroupId: "DARPAN_TEST_TENANT",
                    role: "AUDIT_RUN_WORDS", model: "claude-haiku-5-5", requestCount: 1L, costMicros: 12L, outcome: "WRITER"])
            row.setSequencedIdPrimary()
            row.create()
            turnId = row.turnId
            return [:]
        }
        assertNotNull(ec.entity.find(AgentTurnConversationNullable.ENTITY).condition("turnId", turnId).disableAuthz().useCache(false).one())
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

    private static String table() { ec.entity.getEntityDefinition(AgentTurnConversationNullable.ENTITY).getFullTableName() }

    private static String column() { ec.entity.getEntityDefinition(AgentTurnConversationNullable.ENTITY).getColumnName(AgentTurnConversationNullable.FIELD) }

    private static String nullable() {
        String out = null
        withConnection { Connection c ->
            def ps = c.prepareStatement("""SELECT IS_NULLABLE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?""")
            ps.setString(1, table())
            ps.setString(2, column())
            def rs = ps.executeQuery()
            out = rs.next() ? rs.getString(1) : null
        }
        return out
    }

    /** A connection outside any transaction (DDL and information_schema reads). */
    private static void withConnection(Closure work) {
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName(AgentTurnConversationNullable.ENTITY))
            try { work.call(c) } finally { c.close() }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }
}
