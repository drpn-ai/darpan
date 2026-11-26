package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-152. The migration that lets an acknowledgement have no note, against a scratch in-memory H2 table: a dry run
 * writes nothing, the run relaxes NOT NULL and keeps the rows, a second run finds nothing to do. No Moqui.
 */
class AcknowledgementNoteMigrationTests {

    @Test
    void notNullBecomesNullableOnceAndRowsStay() {
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:acknote;DB_CLOSE_DELAY=-1")
        try {
            conn.createStatement().execute("CREATE TABLE AUDIT_FINDING_ACKNOWLEDGEMENT (FINDING_ID VARCHAR(40) PRIMARY KEY, NOTE CLOB NOT NULL)")
            conn.createStatement().execute("INSERT INTO AUDIT_FINDING_ACKNOWLEDGEMENT VALUES ('F-1', 'Known')")

            Map dry = AcknowledgementNoteMigration.allowNull(conn, "AUDIT_FINDING_ACKNOWLEDGEMENT", "NOTE", true)
            assertEquals([false, "Would run: ALTER TABLE AUDIT_FINDING_ACKNOWLEDGEMENT ALTER COLUMN NOTE SET NULL"], [dry.changed, dry.message])
            assertFalse(insertsNull(conn), "a dry run changes nothing")

            Map ran = AcknowledgementNoteMigration.allowNull(conn, "AUDIT_FINDING_ACKNOWLEDGEMENT", "NOTE", false)
            assertTrue(ran.changed as boolean)
            assertTrue(insertsNull(conn), "a note may now be absent")
            assertEquals("Known", scalar(conn, "SELECT NOTE FROM AUDIT_FINDING_ACKNOWLEDGEMENT WHERE FINDING_ID = 'F-1'"), "rows kept")

            Map again = AcknowledgementNoteMigration.allowNull(conn, "AUDIT_FINDING_ACKNOWLEDGEMENT", "NOTE", false)
            assertEquals([false, "AUDIT_FINDING_ACKNOWLEDGEMENT.NOTE already allows no value; nothing to do."], [again.changed, again.message])
            assertTrue(AcknowledgementNoteMigration.allowNull(conn, "NO_SUCH_TABLE", "NOTE", false).error as boolean)
        } finally {
            conn.createStatement().execute("DROP ALL OBJECTS")
            conn.close()
        }
    }

    /** Prod 3.4.16's dry run read MySQL's column as VARCHAR and wrote "MODIFY NOTE VARCHAR NULL", which MySQL refuses. */
    @Test
    void mySqlRestatesTheColumnsWholeType() {
        assertEquals("ALTER TABLE AUDIT_FINDING_ACKNOWLEDGEMENT MODIFY NOTE VARCHAR(4095) NULL",
                AcknowledgementNoteMigration.statementFor("MySQL", "AUDIT_FINDING_ACKNOWLEDGEMENT", "NOTE", "VARCHAR", 4095))
        assertEquals("ALTER TABLE T MODIFY NOTE LONGTEXT NULL", AcknowledgementNoteMigration.statementFor("MySQL", "T", "NOTE", "LONGTEXT", 2147483647))
        assertEquals("ALTER TABLE T MODIFY NOTE VARCHAR(255) NULL", AcknowledgementNoteMigration.statementFor("MariaDB", "T", "NOTE", "varchar", 255))
        assertEquals("ALTER TABLE T ALTER COLUMN NOTE DROP NOT NULL", AcknowledgementNoteMigration.statementFor("PostgreSQL", "T", "NOTE", "text", 0))
        assertEquals(null, AcknowledgementNoteMigration.statementFor("Oracle", "T", "NOTE", "CLOB", 0))
    }

    private static boolean insertsNull(Connection conn) {
        try {
            conn.createStatement().execute("INSERT INTO AUDIT_FINDING_ACKNOWLEDGEMENT VALUES ('F-null', NULL)")
            conn.createStatement().execute("DELETE FROM AUDIT_FINDING_ACKNOWLEDGEMENT WHERE FINDING_ID = 'F-null'")
            return true
        } catch (java.sql.SQLException ignored) {
            return false
        }
    }

    private static String scalar(Connection conn, String sql) {
        def rs = conn.createStatement().executeQuery(sql)
        rs.next()
        return rs.getString(1)
    }
}
