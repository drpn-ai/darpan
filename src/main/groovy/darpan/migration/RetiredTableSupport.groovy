package darpan.migration

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

/**
 * DAR-BE-318 3b-2c. Drops a table whose entity definition was retired: Moqui never drops a table, and a retired table whose
 * foreign key points at a live one keeps blocking that table's deletes (ReconciliationAutomationSourceFilter →
 * ReconciliationAutomationSource). The table is named by its migration, never by a caller's input.
 *
 * The drop runs as Moqui runs its own table changes (EntityDbMeta.runSqlUpdate): in a new thread with no transaction, because
 * MySQL refuses DDL inside an XA transaction and commits implicitly around it.
 */
class RetiredTableSupport {

    /**
     * [tableFound, rowCount, dropped]. A dry run, or a table that is not there, changes nothing; a second run finds no table.
     */
    static Map<String, Object> dropRetiredTable(def ec, String groupName, String tableName, boolean dryRun) {
        if (!(tableName ==~ /[A-Z][A-Z0-9_]*/)) throw new IllegalArgumentException("Not a table name: ${tableName}")
        return (Map<String, Object>) ec.transaction.runRequireNew(60, "Error dropping retired table ${tableName}", false, true, {
            Connection con = ec.entity.getConnection(groupName)
            try {
                String found = actualName(con, tableName)
                if (found == null) return [tableFound: false, rowCount: 0L, dropped: false]
                long rows = count(con, found)
                if (dryRun) return [tableFound: true, rowCount: rows, dropped: false]
                Statement st = con.createStatement()
                try { st.executeUpdate("DROP TABLE ${found}".toString()) } finally { st.close() }
                return [tableFound: true, rowCount: rows, dropped: true]
            } finally {
                con.close()
            }
        })
    }

    /**
     * The table as the database spells it (upper case as Moqui creates it, or lower case on a lower-casing database), in the
     * connection's own database only: a null catalog would let MySQL answer with a same-named table of another database.
     */
    private static String actualName(Connection con, String tableName) {
        DatabaseMetaData meta = con.metaData
        for (String candidate : [tableName, tableName.toLowerCase(Locale.ROOT)]) {
            ResultSet rs = meta.getTables(con.catalog, null, candidate, ["TABLE"] as String[])
            try {
                if (rs.next()) return rs.getString("TABLE_NAME")
            } finally {
                rs.close()
            }
        }
        return null
    }

    private static long count(Connection con, String table) {
        Statement st = con.createStatement()
        try {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ${table}".toString())
            try { return rs.next() ? rs.getLong(1) : 0L } finally { rs.close() }
        } finally {
            st.close()
        }
    }
}
