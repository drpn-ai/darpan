package darpan.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * OC124' (DAR-OPS-029 S27): DARPAN_SETTING_VALUE_WIDE on H2, plain JDBC, no Moqui. A VARCHAR(255) NOT NULL column (the old
 * text-medium) widens once to VARCHAR(4095) and stays NOT NULL; the dry run changes nothing; rows are kept; a second run does
 * nothing. The MySQL statement is pinned here and proven on MySQL by SettingValueWideningMysqlTests.
 */
class SettingValueWideningTests {
    static final String TABLE = "SETTING_VALUE_WIDENING_TEST"
    Connection conn

    @BeforeEach
    void connect() {
        conn = DriverManager.getConnection("jdbc:h2:mem:setting_value_widening;DB_CLOSE_DELAY=-1", "sa", "")
        conn.createStatement().execute("DROP TABLE IF EXISTS ${TABLE}")
        conn.createStatement().execute("CREATE TABLE ${TABLE} (SETTING_KEY VARCHAR(255) PRIMARY KEY, SETTING_VALUE VARCHAR(255) NOT NULL)")
        conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('errors.email.to', 'ops@example.com')")
    }

    @AfterEach
    void close() {
        conn?.createStatement()?.execute("DROP TABLE IF EXISTS ${TABLE}")
        conn?.close()
    }

    @Test
    void aNarrowColumnWidensOnceKeepsNotNullAndItsRows() {
        Map dry = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", true)
        assertEquals("Would run: ALTER TABLE ${TABLE} ALTER COLUMN SETTING_VALUE SET DATA TYPE VARCHAR(4095)".toString(), dry.message)
        assertEquals(255, column().size, "a dry run changes nothing")

        Map ran = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", false)
        assertTrue(ran.changed as boolean, ran.message as String)
        assertEquals(4095, column().size)
        assertFalse(column().nullable as boolean, "NOT NULL kept")
        conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('errors.email.ignorePatterns', '${'p' * 2000}')")
        assertEquals("ops@example.com", scalar("SELECT SETTING_VALUE FROM ${TABLE} WHERE SETTING_KEY = 'errors.email.to'"), "rows kept")

        Map again = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", false)
        assertFalse(again.changed as boolean, again.message as String)
        assertTrue((again.message as String).contains("nothing to do"), again.message as String)
    }

    @Test
    void aMissingColumnIsAnErrorNotASilentPass() {
        Map out = SettingValueWidening.widen(conn, TABLE, "NO_SUCH_COLUMN", true)
        assertTrue(out.error as boolean)
        assertFalse(out.changed as boolean)
    }

    @Test
    void eachDatabaseGetsItsOwnStatementAndAnUnknownOneNone() {
        assertEquals("ALTER TABLE T MODIFY C VARCHAR(4095) NOT NULL", SettingValueWidening.statementFor("MySQL", "T", "C", false))
        assertEquals("ALTER TABLE T ALTER COLUMN C TYPE VARCHAR(4095)", SettingValueWidening.statementFor("PostgreSQL", "T", "C", false))
        assertNull(SettingValueWidening.statementFor("Oracle", "T", "C", false))
    }

    private Map column() {
        ResultSet rs = conn.metaData.getColumns(null, null, TABLE, "SETTING_VALUE")
        try {
            assertTrue(rs.next())
            return [size: rs.getInt("COLUMN_SIZE"), nullable: rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable]
        } finally { rs.close() }
    }

    private Object scalar(String sql) {
        ResultSet rs = conn.createStatement().executeQuery(sql)
        try { rs.next(); return rs.getObject(1) } finally { rs.close() }
    }
}
