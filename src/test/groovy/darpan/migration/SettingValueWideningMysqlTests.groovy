package darpan.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * OC124' (DAR-OPS-029 S27): DARPAN_SETTING_VALUE_WIDE on real MySQL (opt-in: mysqlTest, DARPAN_TEST_DB_* as in
 * MysqlLoginTestSupport). A column shaped like the old text-medium on mysql8 (VARCHAR(255) NOT NULL) widens to
 * VARCHAR(4095) with NOT NULL and its collation kept, its rows untouched, a 2,000-character value then fits, and a second run
 * does nothing. Plain JDBC, no Moqui.
 */
@Tag("mysql")
class SettingValueWideningMysqlTests {
    static final String TABLE = "SETTING_VALUE_WIDENING_MYSQL_TEST"
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
    void aVarchar255ValueWidensOnceKeepingNotNullCollationAndRows() {
        conn.createStatement().execute("CREATE TABLE ${TABLE} (SETTING_KEY VARCHAR(255) PRIMARY KEY, SETTING_VALUE VARCHAR(255) NOT NULL)")
        conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('errors.email.to', 'ops@example.com')")
        Map before = column()
        assertEquals(["varchar(255)", "NO"], [before.type, before.nullable])
        // The bug (OC124'): a setting past 255 characters (errors.email.ignorePatterns ships 1,363) cannot be saved.
        assertThrows(java.sql.SQLException) {
            conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('errors.email.ignorePatterns', '${'p' * 2000}')")
        }

        Map dry = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", true)
        assertEquals("Would run: ALTER TABLE ${TABLE} MODIFY SETTING_VALUE VARCHAR(4095) NOT NULL".toString(), dry.message)
        assertEquals("varchar(255)", column().type, "a dry run changes nothing")

        Map ran = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", false)
        assertTrue(ran.changed as boolean, ran.message as String)
        Map after = column()
        assertEquals(["varchar(4095)", "NO", before.collation], [after.type, after.nullable, after.collation], "NOT NULL and collation kept")
        conn.createStatement().execute("INSERT INTO ${TABLE} VALUES ('errors.email.ignorePatterns', '${'p' * 2000}')")
        assertEquals("ops@example.com", scalar("SELECT SETTING_VALUE FROM ${TABLE} WHERE SETTING_KEY = 'errors.email.to'"), "rows kept")

        Map again = SettingValueWidening.widen(conn, TABLE, "SETTING_VALUE", false)
        assertFalse(again.changed as boolean, again.message as String)
    }

    private Map column() {
        ResultSet rs = conn.createStatement().executeQuery("SELECT COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${TABLE}' AND COLUMN_NAME = 'SETTING_VALUE'")
        try {
            assertTrue(rs.next())
            return [type: rs.getString(1), nullable: rs.getString(2), collation: rs.getString(3)]
        } finally { rs.close() }
    }

    private Object scalar(String sql) {
        ResultSet rs = conn.createStatement().executeQuery(sql)
        try { rs.next(); return rs.getObject(1) } finally { rs.close() }
    }
}
