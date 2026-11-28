package darpan.admin.system

import groovy.transform.CompileStatic

import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-ADMIN-017. The admin app's read-only SQL runner (admin.SystemAdminServices.get#SqlQueryResult): the Moqui tools
 * app's SqlRunner screen, closed since DAR-BE-264, made safe for a super admin on prod. Three layers keep it read-only,
 * because a keyword check alone is never enough:
 * <ol>
 *   <li>{@link #statementRefusal}: one statement, starting SELECT/WITH/SHOW/EXPLAIN/DESCRIBE, with no forbidden word and
 *       no secret column name anywhere in the text. No SQL parser: the whole text is scanned, string literals and comments
 *       included, so there is no quoting rule to fool (MySQL's executable comments, backslash modes). A literal that holds
 *       a forbidden word or a ';' is refused too; rewrite it (CHAR(59), LIKE '%upd%te%').</li>
 *   <li>{@link #run}: read-only, autocommit off, and always rolled back. On MySQL a DDL statement commits on its own and
 *       slips out of that transaction, which is why layer 1 exists.</li>
 *   <li>The connection comes from the darpan_sql_readonly datasource (MoquiConf.xml): a DB user with SELECT grants only.
 *       {@link #readOnlySourceProblem} fails closed when it is not set up or uses the app's own DB user, because Moqui's
 *       getDatasourceFactory quietly falls back to the transactional datasource for a group it does not know.</li>
 * </ol>
 * Tables holding password hashes, login keys or token hashes are refused by name ({@link #SECRET_TABLES}); their columns
 * are refused by name too and masked by name in a result, as a second net. Fields marked encrypt="true" are ciphertext
 * in the database already, and the runner never decrypts. The real boundary is the read-only DB user's grants (the
 * spec's ops step).
 */
@CompileStatic
class SqlReadOnlyRunner {
    static final String READ_ONLY_GROUP = "darpan_sql_readonly"
    static final int DEFAULT_MAX_ROWS = 500
    static final int MAX_ROWS_CAP = 5000
    static final int TIMEOUT_SECONDS = 30
    static final int MAX_SQL_CHARS = 20_000
    static final int MAX_CELL_CHARS = 2000
    static final String MASK = "••••"

    static final List<String> LEADING = ["SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC"].asImmutable()

    /** Words a read never needs. Matched as whole words (letters, digits, _ and $ are word characters). */
    static final List<String> FORBIDDEN = ["INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "DROP", "ALTER", "CREATE",
            "TRUNCATE", "RENAME", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "PREPARE", "DEALLOCATE", "HANDLER", "LOAD",
            "LOCK", "UNLOCK", "INTO", "OUTFILE", "DUMPFILE", "LOAD_FILE", "SHUTDOWN", "KILL", "FLUSH", "PURGE", "OPTIMIZE",
            "REPAIR", "INSTALL", "UNINSTALL", "COMMIT", "ROLLBACK", "SAVEPOINT", "XA", "GET_LOCK", "RELEASE_LOCK"].asImmutable()
    private static final Pattern FOR_SHARE = Pattern.compile('(?i)\\bFOR\\s+SHARE\\b')

    /**
     * Tables holding an unencrypted password hash, salt, login key, token hash or OAuth state (database names). A column
     * can be read without naming it (a UNION takes the first branch's labels, a derived-table column list renames it, a
     * whole-row expression carries it), but never without naming its table, so these are refused whole: people are read
     * with `darpan-admin users` / `user`. SqlReadOnlyRunnerTests.everySecretTableIsRefused scans every entity definition
     * and fails when a new one is missing here. The read-only DB user gets no grant on them either.
     */
    static final Set<String> SECRET_TABLES = (["USER_ACCOUNT", "USER_LOGIN_KEY", "USER_PASSWORD_HISTORY", "PASSWORD_RESET_CODE",
            "CLI_DEVICE_CODE", "CLI_SESSION", "SLACK_O_AUTH_STATE", "TWO_FACTOR_RECOVERY_CODE", "SIGN_IN_CHALLENGE",
            "PASSKEY_SIGN_IN_CHALLENGE", "GQL_API_KEY", "SHOPIFY_INSTALL_STATE"] as Set<String>).asImmutable()

    /** Secret tables, the secret columns themselves, and MySQL's own account table and hash column. */
    static final Set<String> SECRET_NAMES = ((SECRET_TABLES + ["CURRENT_PASSWORD", "RESET_PASSWORD", "PASSWORD_SALT",
            "PASSWORD_HINT", "LOGIN_KEY", "LOGIN_KEY_HASH", "ISSUED_LOGIN_KEY_HASH", "TOKEN_HASH", "KEY_HASH", "DEVICE_CODE_HASH",
            "CODE_HASH", "CODE_SALT", "STATE_TOKEN", "MYSQL", "AUTHENTICATION_STRING"]) as Set<String>).asImmutable()
    /** Masked when they come back as a column (PASSWORD is UserPasswordHistory's hash; elsewhere it is ciphertext). */
    private static final Set<String> MASKED_COLUMNS = ((SECRET_NAMES + ["PASSWORD"]) as Set<String>).asImmutable()

    private static final Pattern WORD = Pattern.compile('[A-Za-z0-9_$]+')
    private static final Pattern FIRST_WORD = Pattern.compile('^\\s*([A-Za-z]+)')

    /** Null when the statement may run, else why not (one sentence, no code prefix). */
    static String statementRefusal(String sql) {
        if (sql == null || !sql.trim()) return "The statement is empty."
        if (sql.length() > MAX_SQL_CHARS) return "The statement is longer than 20,000 characters."
        if (sql.contains(";")) return "One statement only: remove every ';' (in a literal, use CHAR(59))."
        Matcher first = FIRST_WORD.matcher(sql)
        String leading = first.find() ? first.group(1).toUpperCase() : ""
        if (!(leading in LEADING)) return "The statement must start with SELECT, WITH, SHOW, EXPLAIN or DESCRIBE."
        Set<String> words = new LinkedHashSet<>()
        Matcher m = WORD.matcher(sql)
        while (m.find()) words.add(m.group().toUpperCase())
        List<String> forbidden = FORBIDDEN.findAll { it in words }
        if (FOR_SHARE.matcher(sql).find()) forbidden.add("FOR SHARE")
        if (forbidden) return "A read never needs ${forbidden.join(', ')}.".toString()
        List<String> secrets = SECRET_NAMES.findAll { it in words }.sort()
        if (secrets) return "It names a secret column or table (${secrets.join(', ')}).".toString()
        return null
    }

    static int clampMaxRows(Integer maxRows) {
        if (maxRows == null) return DEFAULT_MAX_ROWS
        return Math.max(1, Math.min(MAX_ROWS_CAP, maxRows))
    }

    /**
     * Null when darpan_sql_readonly is initialized with a DB user of its own, else why not. Takes Moqui's
     * EntityFacadeImpl.getDataSourcesInfo() list ([group, detail: [user, ...]]); a disabled datasource is not in it.
     */
    static String readOnlySourceProblem(List<Map> dataSourcesInfo) {
        Map ro = dataSourcesInfo.find { it.group == READ_ONLY_GROUP }
        if (ro == null) return "The read-only datasource (${READ_ONLY_GROUP}) is not set up on this instance.".toString()
        String roUser = ((ro.detail as Map)?.user ?: "") as String
        String appUser = (((dataSourcesInfo.find { it.group == "transactional" }?.detail) as Map)?.user ?: "") as String
        if (!roUser || roUser.equalsIgnoreCase(appUser))
            return "The read-only datasource (${READ_ONLY_GROUP}) must use its own SELECT-only DB user, not the app's own DB user.".toString()
        return null
    }

    /**
     * Runs one already-checked statement read-only and rolls it back. The caller owns the connection (and closes it);
     * it comes back with autocommit on and read-only off. Returns [columns, rows, rowCount, truncated, maskedColumns,
     * elapsedMs], or [errorCode: SQL_TIMEOUT|SQL_FAILED, errorMessage, elapsedMs].
     */
    static Map<String, Object> run(Connection con, String sql, int maxRows, int timeoutSeconds) {
        long started = System.currentTimeMillis()
        Statement st = null
        try {
            con.setReadOnly(true)
            con.setAutoCommit(false)
            st = con.createStatement()
            st.setQueryTimeout(timeoutSeconds)
            st.setMaxRows(maxRows + 1)
            if (!st.execute(sql)) return failure("SQL_FAILED", "The statement returned no rows.", started)
            return read(st.getResultSet(), maxRows, started)
        } catch (SQLTimeoutException e) {
            return failure("SQL_TIMEOUT", "Stopped after ${timeoutSeconds} seconds.".toString(), started)
        } catch (SQLException e) {
            if (e.getSQLState() == "57014") return failure("SQL_TIMEOUT", "Stopped after ${timeoutSeconds} seconds.".toString(), started)
            return failure("SQL_FAILED", e.getMessage() ?: e.toString(), started)
        } finally {
            if (st != null) try { st.close() } catch (SQLException ignored) { /* closing */ }
            try { con.rollback() } catch (SQLException ignored) { /* nothing to roll back */ }
            try { con.setAutoCommit(true) } catch (SQLException ignored) { /* the pool resets it */ }
            try { con.setReadOnly(false) } catch (SQLException ignored) { /* the pool resets it */ }
        }
    }

    private static Map<String, Object> read(ResultSet rs, int maxRows, long started) {
        try {
            ResultSetMetaData md = rs.getMetaData()
            int count = md.getColumnCount()
            List<String> columns = []
            List<Boolean> masked = []
            for (int i = 1; i <= count; i++) {
                columns.add(md.getColumnLabel(i))
                masked.add(md.getColumnName(i)?.toUpperCase() in MASKED_COLUMNS || md.getColumnLabel(i)?.toUpperCase() in MASKED_COLUMNS)
            }
            List<List<Object>> rows = []
            boolean truncated = false
            while (rs.next()) {
                if (rows.size() >= maxRows) { truncated = true; break }
                List<Object> row = new ArrayList<>(count)
                for (int i = 1; i <= count; i++) row.add(masked[i - 1] ? (Object) MASK : cell(rs.getObject(i)))
                rows.add(row)
            }
            List<String> maskedColumns = []
            for (int i = 0; i < count; i++) if (masked[i]) maskedColumns.add(columns[i])
            return [columns: columns, rows: rows, rowCount: rows.size(), truncated: truncated, maskedColumns: maskedColumns,
                    elapsedMs: System.currentTimeMillis() - started] as Map<String, Object>
        } finally {
            rs.close()
        }
    }

    /** A cell as JSON can carry it: text cut at MAX_CELL_CHARS, binary as its size, dates as text. */
    static Object cell(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value
        if (value instanceof byte[]) return "[binary ${((byte[]) value).length} bytes]".toString()
        if (value instanceof Blob) return "[binary ${((Blob) value).length()} bytes]".toString()
        if (value instanceof Clob) {
            Clob clob = (Clob) value
            return cut(clob.getSubString(1L, (int) Math.min(clob.length(), (long) MAX_CELL_CHARS + 1)))
        }
        if (value instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) value).toString()
        return cut(value.toString())
    }

    private static String cut(String text) {
        return text.length() > MAX_CELL_CHARS ? text.substring(0, MAX_CELL_CHARS) + "…" : text
    }

    private static Map<String, Object> failure(String code, String message, long started) {
        return [errorCode: code, errorMessage: message, elapsedMs: System.currentTimeMillis() - started] as Map<String, Object>
    }
}
