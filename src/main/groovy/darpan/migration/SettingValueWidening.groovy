package darpan.migration

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

/**
 * OC124' (DAR-OPS-029 S27). DarpanSetting.settingValue was text-medium, VARCHAR(255) on MySQL, while a STRING setting may
 * hold up to DarpanSettings.STRING_MAX (2000) characters: errors.email.ignorePatterns ships a 1,363-character default, so an
 * admin who edited it saved past the column. The field is text-long now (VARCHAR(4095)). Moqui creates new columns but
 * never widens an existing one, so a database made before this keeps VARCHAR(255) until this runs. Idempotent: a column
 * already 4095 wide or more, or a text type with no length, is left alone. Rows are untouched.
 */
class SettingValueWidening {

    static final String ENTITY = "darpan.config.DarpanSetting"
    static final String FIELD = "settingValue"
    static final int WIDTH = 4095
    private static final Set<String> SIZED_TYPES = ["VARCHAR", "CHARACTER VARYING", "NVARCHAR", "CHAR", "CHARACTER"] as Set<String>

    /**
     * The registered migration. The caller's transaction is suspended around the DDL: run#Migration runs inside one, and MySQL
     * refuses DDL on a connection enlisted in an active XA transaction (AcknowledgementNoteMigration's prod 3.5.1 lesson).
     */
    static Map run(def ec, boolean dryRun) {
        def ed = ec.entity.getEntityDefinition(ENTITY)
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(ENTITY))
            try {
                return widen(conn, ed.getFullTableName() as String, ed.getColumnName(FIELD) as String, dryRun)
            } finally {
                conn.close()
            }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }

    /**
     * Widens one column to VARCHAR(WIDTH), keeping its NOT NULL: [changed, statement, message, error?]. Reads the column
     * through JDBC metadata; a wide enough or unsized column, or a dry run, writes nothing.
     */
    static Map widen(Connection conn, String table, String column, boolean dryRun) {
        DatabaseMetaData md = conn.metaData
        Map col = columnInfo(md, table, column)
        if (col == null) return [changed: false, error: true, message: "Column ${table}.${column} was not found.".toString()]
        String type = (col.typeName as String)?.toUpperCase()
        if (!(type in SIZED_TYPES) || (col.size as int) >= WIDTH) {
            return [changed: false, message: "${table}.${column} is ${type}(${col.size}); nothing to do.".toString()]
        }
        String sql = statementFor(md.databaseProductName as String, table, column, col.nullable as boolean)
        if (sql == null) {
            return [changed: false, error: true, message: "${md.databaseProductName} is not a database this migration knows; widen ${table}.${column} to VARCHAR(${WIDTH}) by hand.".toString()]
        }
        if (dryRun) return [changed: false, statement: sql, message: "Would run: ${sql}".toString()]
        Statement st = conn.createStatement()
        try { st.execute(sql) } finally { st.close() }
        return [changed: true, statement: sql, message: "Ran: ${sql}".toString()]
    }

    /** The statement that widens the column on this database, or null for one this migration does not know. MySQL's MODIFY
     *  restates the whole column, so it repeats NOT NULL; the others change only the type. */
    static String statementFor(String product, String table, String column, boolean nullable) {
        String db = (product ?: "").toLowerCase()
        if (db.contains("mysql") || db.contains("mariadb")) {
            return "ALTER TABLE ${table} MODIFY ${column} VARCHAR(${WIDTH})${nullable ? ' NULL' : ' NOT NULL'}".toString()
        }
        if (db.contains("h2")) return "ALTER TABLE ${table} ALTER COLUMN ${column} SET DATA TYPE VARCHAR(${WIDTH})".toString()
        if (db.contains("postgres")) return "ALTER TABLE ${table} ALTER COLUMN ${column} TYPE VARCHAR(${WIDTH})".toString()
        return null
    }

    /** {nullable, typeName, size} of one column, matched as stored, upper or lower case (MySQL keeps the case it was made in). */
    private static Map columnInfo(DatabaseMetaData md, String table, String column) {
        for (List<String> names in [[table, column], [table.toUpperCase(), column.toUpperCase()], [table.toLowerCase(), column.toLowerCase()]]) {
            ResultSet rs = md.getColumns(null, null, names[0], names[1])
            try {
                if (rs.next()) return [nullable: rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                                       typeName: rs.getString("TYPE_NAME"), size: rs.getInt("COLUMN_SIZE")]
            } finally { rs.close() }
        }
        return null
    }
}
