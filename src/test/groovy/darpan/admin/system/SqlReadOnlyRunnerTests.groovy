package darpan.admin.system

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-ADMIN-017. The read-only SQL runner's guards, on a plain in-memory H2 (no Moqui): the statement check (layer 1),
 * the rolled-back read-only transaction (layer 2, proven with an H2 SELECT that writes), the row cap, the timeout,
 * masking, cell shaping, and the fail-closed datasource check (layer 3's wiring).
 */
class SqlReadOnlyRunnerTests {
    private Connection con

    @BeforeEach
    void open() {
        con = DriverManager.getConnection("jdbc:h2:mem:sqlro_${UUID.randomUUID()};DB_CLOSE_DELAY=-1".toString(), "sa", "")
        def st = con.createStatement()
        st.execute("CREATE TABLE ITEM (ID INT PRIMARY KEY, NAME VARCHAR(5000), CURRENT_PASSWORD VARCHAR(100), DATA VARBINARY(10), CREATED TIMESTAMP)")
        (1..10).each { st.execute("INSERT INTO ITEM VALUES (${it}, 'item ${it}', 'hash-${it}', X'0102', TIMESTAMP '2026-11-28 10:00:00')".toString()) }
        st.close()
    }

    @AfterEach
    void close() { con?.close() }

    @Test
    @DisplayName("statement check: plain reads pass, including words that only contain a forbidden word")
    void readsPass() {
        [
            "SELECT * FROM ITEM",
            "  select id from item where name like 'a%'",
            "WITH x AS (SELECT 1 AS n) SELECT n FROM x",
            "SHOW TABLES",
            "EXPLAIN SELECT 1",
            "DESCRIBE ITEM",
            "DESC ITEM",
            "SELECT last_updated_stamp, created_date FROM t WHERE status_id = 'DELETED'",
            "SELECT REPLACE(name, 'a', 'b') FROM t ORDER BY id DESC",
        ].each { assertNull(SqlReadOnlyRunner.statementRefusal(it), it) }
    }

    @Test
    @DisplayName("statement check: empty, too long, a second statement, a stray ;, writes, locks, files, secrets are refused")
    void refusals() {
        assertTrue(SqlReadOnlyRunner.statementRefusal(null).contains("empty"))
        assertTrue(SqlReadOnlyRunner.statementRefusal("   ").contains("empty"))
        assertTrue(SqlReadOnlyRunner.statementRefusal("SELECT '" + "x" * 20_001 + "'").contains("20,000"))
        [
            "SELECT 1; SELECT 2",
            "SELECT 1;",
            "SELECT 'a;b'",                      // a ; anywhere, even in a literal: no parser to fool
        ].each { assertTrue(SqlReadOnlyRunner.statementRefusal(it).contains(";"), it) }
        [
            "DROP TABLE ITEM",
            "DELETE FROM ITEM",
            "/* hi */ SELECT 1",
            "CALL x()",
        ].each { assertTrue(SqlReadOnlyRunner.statementRefusal(it).contains("must start with"), it) }
        [
            "SELECT * FROM ITEM INTO OUTFILE '/tmp/x'": "INTO",
            "SELECT * INTO DUMPFILE '/tmp/x' FROM ITEM": "INTO",
            "SELECT * FROM ITEM FOR UPDATE": "UPDATE",
            "SELECT * FROM ITEM FOR SHARE": "FOR SHARE",
            "SELECT * FROM ITEM LOCK IN SHARE MODE": "LOCK",
            "WITH x AS (SELECT 1) DELETE FROM ITEM": "DELETE",
            "SELECT LOAD_FILE('/etc/passwd')": "LOAD_FILE",
            "EXPLAIN ANALYZE UPDATE ITEM SET NAME = 'x'": "UPDATE",
            "SELECT GET_LOCK('x', 10)": "GET_LOCK",
            "SELECT 1 /*! , (INSERT) */": "INSERT",
        ].each { sql, word -> assertTrue(SqlReadOnlyRunner.statementRefusal(sql)?.contains(word), "${sql} -> ${SqlReadOnlyRunner.statementRefusal(sql)}") }
        [
            "SELECT CONCAT(current_password, '') FROM user_account",
            "SELECT `LOGIN_KEY` FROM x",
            "SELECT * FROM user_password_history",
            "select token_hash from t",
            // security review 2026-11-28: a column can be read without naming it (a UNION takes the first branch's labels, a
            // derived-table column list renames, a whole-row expression carries it), but never without naming its table.
            "SELECT 1, 2, 3, 4 UNION SELECT * FROM USER_ACCOUNT",
            "SELECT * FROM (SELECT * FROM cli_session) AS t (a, b, c)",
            "SELECT JSON_OBJECTAGG(k.LOGIN_KEY_HASH, 1) FROM CLI_SESSION k",
            "SELECT * FROM gql_api_key",
            "SELECT * FROM SLACK_O_AUTH_STATE",
            "SELECT authentication_string FROM mysql.user",
        ].each { assertTrue(SqlReadOnlyRunner.statementRefusal(it)?.contains("secret"), it) }
    }

    @Test
    @DisplayName("drift guard: every table with an unencrypted hash, salt, password or token column is refused by name")
    void everySecretTableIsRefused() {
        File root = [new File("").absoluteFile, new File("../../..").absoluteFile, new File("darpan-backend").absoluteFile]
                .find { new File(it, "runtime/component/darpan").isDirectory() && new File(it, "framework/entity").isDirectory() }
        assertNotNull(root, "backend root not found from ${new File('').absolutePath}")
        List<File> files = (new File(root, "framework/entity").listFiles() as List<File>) +
                new File(root, "runtime/component").listFiles().collectMany { new File(it, "entity").listFiles() as List<File> ?: [] }
        def secretField = ~/^(currentPassword|resetPassword|passwordHint|password|loginKey|stateToken|\w*(?<!credentialId|query|text|options|content|plan)Hash|\w*Salt)$/
        Map<String, List<String>> found = [:]
        files.findAll { it.name.endsWith(".xml") }.each { File f ->
            def matcher = f.text =~ /(?s)<entity\s+entity-name="([^"]+)"[^>]*>(.*?)<\/entity>/
            matcher.each { m ->
                List<String> fields = ((m[2] as String) =~ /<field name="([^"]+)"([^>]*)>/).collect { it }
                        .findAll { !(it[2] as String).contains('encrypt="true"') && (it[1] as String) ==~ secretField }.collect { it[1] as String }
                if (fields) found[camelToTable(m[1] as String)] = fields
            }
        }
        assertTrue(found.containsKey("USER_ACCOUNT") && found.containsKey("CLI_SESSION"), "the scan finds the known ones: ${found}")
        Map missing = found.findAll { table, fields -> !(table in SqlReadOnlyRunner.SECRET_NAMES) }
        assertTrue(missing.isEmpty(), "add these tables to SqlReadOnlyRunner.SECRET_TABLES (and keep them out of the read-only DB user's grants): ${missing}")
    }

    private static String camelToTable(String name) {
        name.collect { it == it.toUpperCase() && it != it.toLowerCase() ? "_" + it : it.toUpperCase() }.join("").replaceFirst('^_', '')
    }

    @Test
    @DisplayName("a read returns columns, rows and counts; the row cap marks truncated; maxRows is clamped")
    void readAndCap() {
        Map all = SqlReadOnlyRunner.run(con, "SELECT ID, NAME FROM ITEM ORDER BY ID", 500, 30)
        assertNull(all.errorCode, "${all}")
        assertEquals(["ID", "NAME"], all.columns)
        assertEquals(10, all.rowCount)
        assertEquals([1, "item 1"], all.rows[0])
        assertEquals(false, all.truncated)

        Map capped = SqlReadOnlyRunner.run(con, "SELECT ID FROM ITEM ORDER BY ID", 3, 30)
        assertEquals(3, capped.rowCount)
        assertEquals([[1], [2], [3]], capped.rows)
        assertEquals(true, capped.truncated)

        Map exact = SqlReadOnlyRunner.run(con, "SELECT ID FROM ITEM", 10, 30)
        assertEquals(false, exact.truncated, "exactly maxRows rows is not truncated")

        assertEquals(500, SqlReadOnlyRunner.clampMaxRows(null))
        assertEquals(1, SqlReadOnlyRunner.clampMaxRows(0))
        assertEquals(1, SqlReadOnlyRunner.clampMaxRows(-5))
        assertEquals(5000, SqlReadOnlyRunner.clampMaxRows(1_000_000))
    }

    @Test
    @DisplayName("a SELECT that writes (H2 data-change delta table) is rolled back: the guard holds even past the keyword check")
    void writeThroughSelectIsRolledBack() {
        Map out = SqlReadOnlyRunner.run(con, "SELECT ID FROM FINAL TABLE (INSERT INTO ITEM (ID, NAME) VALUES (99, 'sneaky'))", 500, 30)
        // H2 may refuse it on the read-only connection, or run it inside the transaction that is then rolled back:
        // either way nothing stays.
        def rs = con.createStatement().executeQuery("SELECT COUNT(*) FROM ITEM WHERE ID = 99")
        rs.next()
        assertEquals(0, rs.getInt(1), "the write did not survive: ${out}")
        assertTrue(con.autoCommit, "the connection goes back to the pool as it came (autocommit on)")
        assertFalse(con.readOnly, "and not read-only")
    }

    @Test
    @DisplayName("over the timeout: SQL_TIMEOUT; a DB error: SQL_FAILED with the DB's message")
    void timeoutAndFailure() {
        Map slow = SqlReadOnlyRunner.run(con, "SELECT COUNT(*) FROM SYSTEM_RANGE(1, 1000000) a, SYSTEM_RANGE(1, 1000000) b WHERE a.X + b.X < 0", 500, 1)
        assertEquals("SQL_TIMEOUT", slow.errorCode, "${slow}")

        Map bad = SqlReadOnlyRunner.run(con, "SELECT * FROM NO_SUCH_TABLE", 500, 30)
        assertEquals("SQL_FAILED", bad.errorCode)
        assertTrue((bad.errorMessage as String).toUpperCase().contains("NO_SUCH_TABLE"), bad.errorMessage as String)
    }

    @Test
    @DisplayName("secret columns are masked even through SELECT *; binary, long text and timestamps are shaped for JSON")
    void maskingAndCells() {
        Map out = SqlReadOnlyRunner.run(con, "SELECT * FROM ITEM WHERE ID = 1", 500, 30)
        assertEquals(["CURRENT_PASSWORD"], out.maskedColumns)
        Map row = [out.columns, out.rows[0]].transpose().collectEntries()
        assertEquals(SqlReadOnlyRunner.MASK, row.CURRENT_PASSWORD)
        assertEquals("[binary 2 bytes]", row.DATA)
        assertEquals("2026-11-28 10:00:00.0", row.CREATED)

        con.createStatement().execute("UPDATE ITEM SET NAME = '${'y' * 4000}' WHERE ID = 2".toString())
        Map longText = SqlReadOnlyRunner.run(con, "SELECT NAME FROM ITEM WHERE ID = 2", 500, 30)
        String cell = longText.rows[0][0]
        assertEquals(SqlReadOnlyRunner.MAX_CELL_CHARS + 1, cell.length())
        assertTrue(cell.endsWith("…"))

        Map nulls = SqlReadOnlyRunner.run(con, "SELECT NULL AS N", 500, 30)
        assertNull(nulls.rows[0][0])
    }

    @Test
    @DisplayName("fail closed: no read-only datasource, or one on the app's own DB user, is refused; a separate user passes")
    void readOnlySource() {
        List tx = [[group: "transactional", detail: [url: "jdbc:h2:x", user: "app"]]]
        assertTrue(SqlReadOnlyRunner.readOnlySourceProblem(tx).contains("not set up"))
        assertTrue(SqlReadOnlyRunner.readOnlySourceProblem(tx + [[group: "darpan_sql_readonly", detail: [uri: "jdbc:h2:x", user: "APP"]]]).contains("own DB user"))
        assertTrue(SqlReadOnlyRunner.readOnlySourceProblem(tx + [[group: "darpan_sql_readonly", detail: [uri: "jdbc:h2:x", user: ""]]]).contains("own DB user"))
        assertNull(SqlReadOnlyRunner.readOnlySourceProblem(tx + [[group: "darpan_sql_readonly", detail: [uri: "jdbc:h2:x", user: "darpan_ro"]]]))
    }
}
