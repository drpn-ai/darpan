package darpan.migration

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

/**
 * DAR-BE-424 (Aditi 2026-11-28: "Remove it"): the registered migration NSRESTLET_DROP. The NetSuite RESTlet connection type
 * had nothing that ran it once the inventory services were deleted (DAR-OPS-029 S41c), and its code is gone, so this removes
 * what it left in the database:
 * <ol>
 *   <li>its sharing rows (CONFIG_TENANT_ACCESS and SOURCE_CONFIG_ENDPOINT_ACCESS of type SCFG_NS_RESTLET), then the type's
 *       enumeration row and the tenant filter row on the deleted entity;</li>
 *   <li>the NS_RESTLET_CONFIG_ID column of RULE_SET_COMPARE_SOURCE and RECONCILIATION_AUTOMATION_SOURCE, each foreign key on
 *       it first (MySQL refuses to drop a column a key still uses; the key's index goes with the column);</li>
 *   <li>the NS_RESTLET_CONFIG table, with the {@code __TABLE_BOOTSTRAP__} placeholder row the old bootstrap data left in it;</li>
 *   <li>NS_AUTH_CONFIG.NETSUITE_ACCOUNT, which only the RESTlet save read.</li>
 * </ol>
 * Every run, dry or real, first reports what the database holds: RESTlet configs per tenant (and how many are active), the
 * rule-set and automation sides still naming one, the audit checks over those, and the sharing rows. A real run REFUSES,
 * naming the ids, while an active rule set or an active automation still names a RESTlet: that side reads nothing now, and
 * someone should move it to a NetSuite connection first. A paused or archived one does not block; its dead reference goes.
 * Nothing runs while the entity model still defines the table or a column (a database whose code has not shipped yet).
 * Idempotent: what is gone is skipped, so a second run has nothing to do. The caller's transaction is suspended around the
 * statements, as OrphanSchemaDrops does: MySQL refuses DDL on a connection enlisted in an XA transaction.
 */
class NsRestletDrop {

    static final String TYPE = "SCFG_NS_RESTLET"
    static final String TABLE = "NS_RESTLET_CONFIG"
    static final String COLUMN = "NS_RESTLET_CONFIG_ID"
    /** The disabled, tenantless row data/hotfix/NsRestletConfigTableBootstrapData.xml upserted on every deploy until S36b. */
    static final String BOOTSTRAP_ID = "__TABLE_BOOTSTRAP__"
    static final List<String> SOURCE_TABLES = ["RULE_SET_COMPARE_SOURCE", "RECONCILIATION_AUTOMATION_SOURCE"].asImmutable()
    static final String GROUP_ENTITY = "darpan.reconciliation.ReconciliationRun"

    /** The registered migration's body. */
    static Map run(def ec, boolean dryRun) {
        Set<String> modeled = modeled(ec)
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(GROUP_ENTITY))
            try {
                return apply(conn, modeled, dryRun)
            } finally {
                conn.close()
            }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }

    /** Which of this migration's targets the live entity model still defines (by the label apply() reports them under). */
    static Set<String> modeled(def ec) {
        Set<String> out = new TreeSet<>()
        if (ec.entity.isEntityDefined("darpan.reconciliation.NsRestletConfig")) out << TABLE
        if (ec.entity.getEntityDefinition("darpan.rule.RuleSetCompareSource").isField("nsRestletConfigId")) out << "RULE_SET_COMPARE_SOURCE.${COLUMN}".toString()
        if (ec.entity.getEntityDefinition("darpan.reconciliation.ReconciliationAutomationSource").isField("nsRestletConfigId")) {
            out << "RECONCILIATION_AUTOMATION_SOURCE.${COLUMN}".toString()
        }
        if (ec.entity.getEntityDefinition("darpan.reconciliation.NsAuthConfig").isField("netsuiteAccount")) out << "NS_AUTH_CONFIG.NETSUITE_ACCOUNT"
        return out
    }

    /** [messages, errors, changed]: the report, then (real run, nothing modeled, nothing live) the statements. */
    static Map apply(Connection conn, Set<String> modeled, boolean dryRun) {
        List<String> messages = []
        List<String> errors = []
        String table = storedName(conn, TABLE, null)
        Map<String, String> sourceColumns = SOURCE_TABLES.collectEntries { String t ->
            String stored = storedName(conn, t, null)
            [(t): stored ? storedName(conn, stored, COLUMN) : null]
        }

        // ---- what the database holds (checks 1-4 of the prod read, at the moment it runs) ----
        if (table) {
            List<List> perTenant = rows(conn, "SELECT COMPANY_USER_GROUP_ID, COUNT(*), " +
                    "SUM(CASE WHEN IS_ACTIVE = 'N' THEN 0 ELSE 1 END) FROM ${table} GROUP BY COMPANY_USER_GROUP_ID ORDER BY COMPANY_USER_GROUP_ID")
            long total = perTenant.sum(0L) { (it[1] as Number).longValue() } as long
            long placeholder = count(conn, "SELECT COUNT(*) FROM ${table} WHERE NS_RESTLET_CONFIG_ID = ?", [BOOTSTRAP_ID])
            messages << ("${TABLE}: ${total} RESTlet config(s)" + (perTenant ? ": " + perTenant.collect { List r ->
                "${r[0] ?: '(no tenant)'} ${r[1]} (${r[2] ?: 0} active)" }.join(", ") : "") +
                    (placeholder ? "; ${placeholder} of them the ${BOOTSTRAP_ID} placeholder (disabled, no tenant; the old table-bootstrap data)" : "") +
                    ".").toString()
        } else {
            messages << "${TABLE}: not in this database.".toString()
        }
        List<Map> live = []
        Set<String> referencingRuleSets = new TreeSet<>()
        Set<String> referencingAutomations = new TreeSet<>()
        if (sourceColumns.RULE_SET_COMPARE_SOURCE) {
            List<List> sides = rows(conn, "SELECT cs.RULE_SET_ID, s.FILE_SIDE, s.${sourceColumns.RULE_SET_COMPARE_SOURCE}, " +
                    "CASE WHEN COALESCE(rs.IS_ACTIVE, 'Y') <> 'N' AND COALESCE(rs.IS_ARCHIVED, 'N') <> 'Y' THEN 'Y' ELSE 'N' END " +
                    "FROM RULE_SET_COMPARE_SOURCE s JOIN RULE_SET_COMPARE_SCOPE cs ON cs.COMPARE_SCOPE_ID = s.COMPARE_SCOPE_ID " +
                    "LEFT JOIN RULE_SET rs ON rs.RULE_SET_ID = cs.RULE_SET_ID " +
                    "WHERE s.${sourceColumns.RULE_SET_COMPARE_SOURCE} IS NOT NULL ORDER BY cs.RULE_SET_ID, s.FILE_SIDE")
            sides.each { List r ->
                referencingRuleSets << (r[0] as String)
                if (r[3] == 'Y') live << [kind: "rule set", id: r[0], side: r[1], restlet: r[2]]
            }
            messages << "Rule-set sides naming a RESTlet: ${describe(sides)}.".toString()
        }
        if (sourceColumns.RECONCILIATION_AUTOMATION_SOURCE) {
            List<List> sides = rows(conn, "SELECT s.AUTOMATION_ID, s.FILE_SIDE, s.${sourceColumns.RECONCILIATION_AUTOMATION_SOURCE}, " +
                    "CASE WHEN COALESCE(a.IS_ACTIVE, 'Y') = 'Y' AND COALESCE(a.IS_ARCHIVED, 'N') <> 'Y' THEN 'Y' ELSE 'N' END " +
                    "FROM RECONCILIATION_AUTOMATION_SOURCE s LEFT JOIN RECONCILIATION_AUTOMATION a ON a.AUTOMATION_ID = s.AUTOMATION_ID " +
                    "WHERE s.${sourceColumns.RECONCILIATION_AUTOMATION_SOURCE} IS NOT NULL ORDER BY s.AUTOMATION_ID, s.FILE_SIDE")
            sides.each { List r ->
                referencingAutomations << (r[0] as String)
                if (r[3] == 'Y') live << [kind: "automation", id: r[0], side: r[1], restlet: r[2]]
            }
            messages << "Automation sides naming a RESTlet: ${describe(sides)}.".toString()
        }
        if ((referencingRuleSets || referencingAutomations) && storedName(conn, "AUDIT_CHECK", null)) {
            List<String> ids = (referencingRuleSets + referencingAutomations) as List<String>
            String marks = ids.collect { '?' }.join(", ")
            List<List> checks = rows(conn, "SELECT CHECK_CODE, COMPANY_USER_GROUP_ID FROM AUDIT_CHECK WHERE SAVED_RUN_ID IN (${marks}) " +
                    "OR AUTOMATION_ID IN (${marks}) ORDER BY COMPANY_USER_GROUP_ID, CHECK_CODE", ids + ids)
            messages << ("Audit checks over those: " + (checks ? checks.collect { "${it[1]}/${it[0]}" }.join(", ") : "none") + ".").toString()
        }
        Map<String, String> shareTables = [CONFIG_TENANT_ACCESS: "tenant share(s)", SOURCE_CONFIG_ENDPOINT_ACCESS: "endpoint decision(s)"]
        shareTables.each { String t, String words ->
            if (storedName(conn, t, null)) {
                messages << "${t}: ${count(conn, "SELECT COUNT(*) FROM ${t} WHERE CONFIG_TYPE_ENUM_ID = ?", [TYPE])} RESTlet ${words}.".toString()
            }
        }

        // ---- the gates ----
        if (modeled) {
            messages << "Still defined in the entity model: ${modeled.join(', ')}. Nothing changed; run this after the release that deletes them.".toString()
            return [messages: messages, errors: errors, changed: 0]
        }
        if (live) {
            errors << ("Refused: ${live.size()} active side(s) still read a RESTlet, which nothing runs now. Move each to a NetSuite " +
                    "connection (or pause it) first: " + live.collect { "${it.kind} ${it.id} ${it.side} (${it.restlet})" }.join("; ") + ".").toString()
            return [messages: messages, errors: errors, changed: 0]
        }

        // ---- the statements, in dependency order ----
        List<String> plan = []
        shareTables.keySet().each { String t ->
            String stored = storedName(conn, t, null)
            if (stored && count(conn, "SELECT COUNT(*) FROM ${stored} WHERE CONFIG_TYPE_ENUM_ID = ?", [TYPE]) > 0) {
                plan << "DELETE FROM ${stored} WHERE CONFIG_TYPE_ENUM_ID = '${TYPE}'".toString()
            }
        }
        String filterTable = storedName(conn, "ENTITY_FILTER", null)
        if (filterTable && count(conn, "SELECT COUNT(*) FROM ${filterTable} WHERE ENTITY_FILTER_ID = ?", ["DARPAN_SCOPE_NS_RESTLET"]) > 0) {
            plan << "DELETE FROM ${filterTable} WHERE ENTITY_FILTER_ID = 'DARPAN_SCOPE_NS_RESTLET'".toString()
        }
        String enumTable = storedName(conn, "ENUMERATION", null)
        if (enumTable && count(conn, "SELECT COUNT(*) FROM ${enumTable} WHERE ENUM_ID = ?", [TYPE]) > 0) {
            plan << "DELETE FROM ${enumTable} WHERE ENUM_ID = '${TYPE}'".toString()
        }
        boolean mysql = conn.metaData.databaseProductName.toLowerCase().contains("mysql")
        sourceColumns.each { String t, String column ->
            if (!column) return
            String stored = storedName(conn, t, null)
            foreignKeysOn(conn, stored, column).each { String fk ->
                plan << (mysql ? "ALTER TABLE ${stored} DROP FOREIGN KEY ${fk}" : "ALTER TABLE ${stored} DROP CONSTRAINT ${fk}").toString()
            }
            plan << "ALTER TABLE ${stored} DROP COLUMN ${column}".toString()
        }
        if (table) plan << "DROP TABLE ${table}".toString()
        String authTable = storedName(conn, "NS_AUTH_CONFIG", null)
        String account = authTable ? storedName(conn, authTable, "NETSUITE_ACCOUNT") : null
        if (account) plan << "ALTER TABLE ${authTable} DROP COLUMN ${account}".toString()

        if (!plan) {
            messages << "Nothing left to remove.".toString()
            return [messages: messages, errors: errors, changed: 0]
        }
        if (dryRun) {
            plan.each { messages << "Would run: ${it}".toString() }
            return [messages: messages, errors: errors, changed: 0]
        }
        Statement st = conn.createStatement()
        try {
            plan.each { String sql ->
                st.execute(sql)
                messages << "Ran: ${sql}".toString()
            }
        } finally {
            st.close()
        }
        return [messages: messages, errors: errors, changed: plan.size()]
    }

    private static String describe(List<List> sides) {
        if (!sides) return "none"
        return sides.collect { List r -> "${r[0]} ${r[1]} (${r[2]}${r[3] == 'Y' ? ', active' : ', paused or archived'})" }.join(", ")
    }

    /** The table (column null) or column name as this database stores it, matched in any case; null when absent. */
    static String storedName(Connection conn, String table, String column) {
        DatabaseMetaData md = conn.metaData
        for (String t in [table, table.toUpperCase(), table.toLowerCase()].unique()) {
            ResultSet rs = column == null ? md.getTables(conn.catalog, conn.schema, t, ["TABLE"] as String[]) :
                    md.getColumns(conn.catalog, conn.schema, t, null)
            try {
                while (rs.next()) {
                    if (!rs.getString("TABLE_NAME").equalsIgnoreCase(table)) continue
                    if (column == null) return rs.getString("TABLE_NAME")
                    if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) return rs.getString("COLUMN_NAME")
                }
            } finally { rs.close() }
        }
        return null
    }

    /** The foreign keys of {@code table} that use {@code column}, by whatever name the database gave them. */
    static List<String> foreignKeysOn(Connection conn, String table, String column) {
        Set<String> found = new TreeSet<>()
        ResultSet rs = conn.metaData.getImportedKeys(conn.catalog, conn.schema, table)
        try {
            while (rs.next()) {
                if (rs.getString("FKCOLUMN_NAME").equalsIgnoreCase(column) && rs.getString("FK_NAME")) found << rs.getString("FK_NAME")
            }
        } finally { rs.close() }
        return found as List<String>
    }

    private static long count(Connection conn, String sql, List params = []) {
        List<List> out = rows(conn, sql, params)
        return out ? (out[0][0] as Number).longValue() : 0L
    }

    private static List<List> rows(Connection conn, String sql, List params = []) {
        def ps = conn.prepareStatement(sql)
        try {
            params.eachWithIndex { Object p, int i -> ps.setObject(i + 1, p) }
            ResultSet rs = ps.executeQuery()
            try {
                int n = rs.metaData.columnCount
                List<List> out = []
                while (rs.next()) out << (1..n).collect { int i -> rs.getObject(i) }
                return out
            } finally { rs.close() }
        } finally { ps.close() }
    }
}
