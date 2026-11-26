package darpan.audit

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

/**
 * DAR-UI-152 (Aditi 2026-11-26: "typing something doesnt need to be required"). An acknowledgement's note became optional,
 * so AuditFindingAcknowledgement.note is no longer not-null. Moqui creates new columns but never relaxes an existing one, so
 * a database made before 3.4.15 keeps NOT NULL until this runs. Idempotent: a nullable column is left alone.
 */
class AcknowledgementNoteMigration {

    static final String ENTITY = "darpan.audit.AuditFindingAcknowledgement"
    static final String FIELD = "note"

    /** The registered migration: AuditFindingAcknowledgement.note allows no value. */
    static Map run(def ec, boolean dryRun) {
        def ed = ec.entity.getEntityDefinition(ENTITY)
        Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(ENTITY))
        try {
            return allowNull(conn, ed.getFullTableName() as String, ed.getColumnName(FIELD) as String, dryRun)
        } finally {
            conn.close()
        }
    }

    /**
     * Makes one column nullable: [changed, statement, message]. Reads the column through JDBC metadata; a nullable column,
     * or a dry run, writes nothing. The statement is the database's own: MySQL/MariaDB MODIFY with the column's own type,
     * H2 ALTER COLUMN ... SET NULL, PostgreSQL DROP NOT NULL. Any other database is refused in words.
     */
    static Map allowNull(Connection conn, String table, String column, boolean dryRun) {
        DatabaseMetaData md = conn.metaData
        Map col = columnInfo(md, table, column)
        if (col == null) return [changed: false, message: "Column ${table}.${column} was not found.".toString(), error: true]
        if (col.nullable) return [changed: false, message: "${table}.${column} already allows no value; nothing to do.".toString()]
        String product = (md.databaseProductName ?: "").toLowerCase()
        String sql = product.contains("mysql") || product.contains("mariadb") ?
                "ALTER TABLE ${table} MODIFY ${column} ${col.typeName} NULL" :
                product.contains("h2") ? "ALTER TABLE ${table} ALTER COLUMN ${column} SET NULL" :
                product.contains("postgres") ? "ALTER TABLE ${table} ALTER COLUMN ${column} DROP NOT NULL" : null
        if (sql == null) {
            return [changed: false, error: true, message: "${md.databaseProductName} is not a database this migration knows; make ${table}.${column} nullable by hand.".toString()]
        }
        if (dryRun) return [changed: false, statement: sql, message: "Would run: ${sql}".toString()]
        Statement st = conn.createStatement()
        try { st.execute(sql) } finally { st.close() }
        return [changed: true, statement: sql, message: "Ran: ${sql}".toString()]
    }

    /** {nullable, typeName} of one column, matched as stored, upper or lower case (MySQL keeps the case it was made in). */
    private static Map columnInfo(DatabaseMetaData md, String table, String column) {
        for (List<String> names in [[table, column], [table.toUpperCase(), column.toUpperCase()], [table.toLowerCase(), column.toLowerCase()]]) {
            ResultSet rs = md.getColumns(null, null, names[0], names[1])
            try {
                if (rs.next()) return [nullable: rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable, typeName: rs.getString("TYPE_NAME")]
            } finally { rs.close() }
        }
        return null
    }
}
