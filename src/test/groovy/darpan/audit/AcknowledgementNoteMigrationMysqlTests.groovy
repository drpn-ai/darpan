package darpan.audit

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-152. ACK_NOTE_NULLABLE on real MySQL (opt-in: `./gradlew :runtime:component:darpan:mysqlTest`, DARPAN_TEST_DB_*
 * as in MysqlLoginTestSupport). The H2 tests never ran the MySQL branch, and prod 3.4.16's dry run showed it would send
 * "MODIFY NOTE VARCHAR NULL", which MySQL refuses. Here the real statement runs against a column shaped like Moqui's
 * text-long on mysql8 (VARCHAR(4095) NOT NULL) and a LONGTEXT one: each becomes nullable with its type, size and collation
 * kept and its rows untouched, and a second run does nothing. Plain JDBC, no Moqui.
 */
@Tag("mysql")
class AcknowledgementNoteMigrationMysqlTests {

    static final String TABLE = "ACK_NOTE_MIGRATION_MYSQL_TEST"
    Connection conn

    private static String env(String name, String fallback) {
        String value = System.getenv(name)
        return (value == null || value.isEmpty()) ? fallback : value
    }

    @BeforeEach
    void connect() {
        String url = "jdbc:mysql://${env('DARPAN_TEST_DB_HOST', '127.0.0.1')}:${env('DARPAN_TEST_DB_PORT', '3306')}/" +
                "${env('DARPAN_TEST_DB_NAME', 'darpan_test')}?useSSL=false&allowPublicKeyRetrieval=true"
        // Fallback is the local test-fixture credential MysqlLoginTestSupport uses, not a deployed secret.
        conn = DriverManager.getConnection(url, env('DARPAN_TEST_DB_USER', 'darpan_test'), env('DARPAN_TEST_DB_PASSWORD', 'Darpan_Test@2026'))
        conn.createStatement().execute("DROP TABLE IF EXISTS ${TABLE}")
    }

    @AfterEach
    void drop() {
        conn?.createStatement()?.execute("DROP TABLE IF EXISTS ${TABLE}")
        conn?.close()
    }

    @Test
    void aVarcharNoteBecomesNullableWithItsSizeKept() {
        nullableOnceAndKept("VARCHAR(4095)", "varchar(4095)", "MODIFY NOTE VARCHAR(4095) NULL")
    }

    @Test
    void aLongtextNoteBecomesNullable() {
        nullableOnceAndKept("LONGTEXT", "longtext", "MODIFY NOTE LONGTEXT NULL")
    }

    private void nullableOnceAndKept(String ddlType, String columnType, String expectedTail) {
        conn.createStatement().execute("CREATE TABLE ${TABLE} (FINDING_ID VARCHAR(40) PRIMARY KEY, NOTE ${ddlType} NOT NULL)")
        conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('F-1', 'Known')")
        Map before = column()
        assertEquals([columnType, "NO"], [before.type, before.nullable])

        Map dry = AcknowledgementNoteMigration.allowNull(conn, TABLE, "NOTE", true)
        assertEquals("Would run: ALTER TABLE ${TABLE} ${expectedTail}".toString(), dry.message)
        assertFalse(insertsNull(), "a dry run changes nothing")

        Map ran = AcknowledgementNoteMigration.allowNull(conn, TABLE, "NOTE", false)
        assertTrue(ran.changed as boolean, ran.message as String)
        Map after = column()
        assertEquals([columnType, "YES", before.collation], [after.type, after.nullable, after.collation], "type, size and collation kept")
        assertTrue(insertsNull(), "a note may now be absent")
        assertEquals("Known", scalar("SELECT NOTE FROM ${TABLE} WHERE FINDING_ID = 'F-1'"), "rows kept")

        Map again = AcknowledgementNoteMigration.allowNull(conn, TABLE, "NOTE", false)
        assertFalse(again.changed as boolean, again.message as String)
    }

    private Map column() {
        def rs = conn.prepareStatement("""SELECT COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'NOTE'""").with { it.setString(1, TABLE); it.executeQuery() }
        assertTrue(rs.next())
        return [type: rs.getString(1), nullable: rs.getString(2), collation: rs.getString(3)]
    }

    private boolean insertsNull() {
        try {
            conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('F-null', NULL)")
            conn.createStatement().execute("DELETE FROM ${TABLE} WHERE FINDING_ID = 'F-null'")
            return true
        } catch (SQLException ignored) {
            return false
        }
    }

    private String scalar(String sql) {
        def rs = conn.createStatement().executeQuery(sql)
        rs.next()
        return rs.getString(1)
    }
}
