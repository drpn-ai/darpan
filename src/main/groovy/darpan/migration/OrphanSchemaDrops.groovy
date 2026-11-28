package darpan.migration

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement

/**
 * DAR-OPS-029 (Aditi 2026-11-28: "dropping anything can be a migration"). Moqui creates tables and columns but never drops
 * one, so deleting an entity or a field leaves its table or column behind. The registered migration ORPHAN_SCHEMA_DROPS
 * (migrate#OrphanSchemaDrops) drops the ones listed in DROPS, under four rules:
 * <ol>
 *   <li>only what this database has (JDBC metadata, matched in any case);</li>
 *   <li>never a table an entity in the live model still maps, or a column still a field of its table's entity: an entry
 *       stays inert until the code that deletes its entity or field has shipped;</li>
 *   <li>never a table another table still references by foreign key (named, as an error);</li>
 *   <li>never while an entry's gate counts a row that still needs the column (the count is in every run's words).</li>
 * </ol>
 * A dry run lists each statement with the table's row count or the column's value count. Idempotent: what is gone is
 * skipped, so a second run has nothing to do. A new orphan is one more DROPS entry. Once the migration has a SUCCESS row
 * the all-pending sweep skips it, so after the release that deletes the new entry's entity or field it is run again by name:
 * {@code darpan-admin --target <env> migrations run ORPHAN_SCHEMA_DROPS --dry-run}, then the same with {@code --force}. An
 * entry whose entity or field is still in the model is reported and kept, so running before that release is harmless.
 */
class OrphanSchemaDrops {

    /** table, optional column, why (the deletion that orphaned it), optional gate (a COUNT(*) that must be 0) + gateWords. */
    static final List<Map<String, String>> DROPS = [
            [table: "RUN_DATA_DOCUMENT", why: "darpan.reconciliation.RunDataDocument, deleted in DAR-OPS-029 S35 (nothing read it)"],
            [table: "RUN_SYSTEM_INSTANCE", why: "darpan.reconciliation.RunSystemInstance, deleted in DAR-OPS-029 S35 (nothing read it)"],
            [table: "AUTH_SECRET", why: "darpan.auth.AuthSecret, deleted in DAR-OPS-029 S23 (its one row, the passkey decoy key, is unused)"],
            [table: "PASSKEY_SIGN_IN_CHALLENGE", column: "USERNAME_HINT",
             why: "PasskeySignInChallenge.usernameHint, deleted in DAR-OPS-029 S23"],
            [table: "TENANT_CHAT_SPACE", column: "GOOGLE_CHAT_WEBHOOK_URL",
             why: "TenantChatSpace.googleChatWebhookUrl, superseded by webhookUrl in 1.5.0 (DAR-OPS-029 S09a)",
             gate: "SELECT COUNT(*) FROM TENANT_CHAT_SPACE WHERE GOOGLE_CHAT_WEBHOOK_URL IS NOT NULL AND GOOGLE_CHAT_WEBHOOK_URL <> '' " +
                     "AND (WEBHOOK_URL IS NULL OR WEBHOOK_URL = '')",
             gateWords: "chat space(s) whose only webhook is still in GOOGLE_CHAT_WEBHOOK_URL (copy each into webhookUrl first)"],
            [table: "SOURCE_SYSTEM_CONNECTOR", column: "EXCHANGE_STATE_LOOKUP_SERVICE_NAME",
             why: "SourceSystemConnector.exchangeStateLookupServiceName, unread since July 2026 (DAR-OPS-029 S42c)"],
            [table: "SOURCE_SYSTEM_CONNECTOR", column: "CONFIG_ID_RESOLVER_SERVICE_NAME",
             why: "SourceSystemConnector.configIdResolverServiceName, deleted in DAR-OPS-029 S07b"],
            [table: "SOURCE_SYSTEM_CONNECTOR", column: "VALIDATION_SERVICE_NAME",
             why: "SourceSystemConnector.validationServiceName, deleted in DAR-OPS-029 S07b"],
            [table: "SLACK_POSTED_MESSAGE", why: "darpan.reconciliation.SlackPostedMessage, deleted in DAR-OPS-029 S09b (Aditi D22)"],
            [table: "HOTWAX_OMS_REST_SOURCE_CONFIG", column: "ORDERS_PATH",
             why: "HotWaxOmsRestSourceConfig.ordersPath, unread since the OMS REST path retired (DAR-OPS-029 S99)"],
            [table: "HOTWAX_OMS_REST_SOURCE_CONFIG", column: "RETURNS_PAGE_SIZE",
             why: "HotWaxOmsRestSourceConfig.returnsPageSize, unread since the OMS REST path retired (DAR-OPS-029 S99)"],
    ].asImmutable()

    /** The connection's entity group: every DROPS table lives in Darpan's own database. */
    static final String GROUP_ENTITY = "darpan.reconciliation.ReconciliationRun"

    /**
     * The registered migration. The caller's transaction is suspended around the DDL: run#Migration runs inside one, and
     * MySQL refuses DDL on a connection enlisted in an active XA transaction (prod 3.5.1, AcknowledgementNoteMigration).
     */
    static Map run(def ec, boolean dryRun) {
        Map<String, Set<String>> model = modelColumns(ec)
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(GROUP_ENTITY))
            try {
                return apply(conn, DROPS, model, dryRun)
            } finally {
                conn.close()
            }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }

    /**
     * Every table the live entity model maps (upper case) -> the columns its entities define there (upper case). A name with
     * no definition (an extend-entity of an entity no component defines, e.g. org.apache.ofbiz.product.facility.Facility on
     * the MySQL test stack) maps no table and is skipped: getAllNonViewEntityNames would throw on it.
     */
    static Map<String, Set<String>> modelColumns(def ec) {
        Map<String, Set<String>> model = [:]
        for (String name in ec.entity.getAllEntityNames()) {
            def ed
            try {
                ed = ec.entity.getEntityDefinition(name)
            } catch (org.moqui.entity.EntityNotFoundException ignored) {
                continue
            }
            if (ed == null || ed.isViewEntity) continue
            Set<String> columns = model.computeIfAbsent((ed.getTableName() as String).toUpperCase()) { new HashSet<String>() }
            for (String field in ed.getAllFieldNames()) columns.add((ed.getColumnName(field) as String).toUpperCase())
        }
        return model
    }

    /**
     * Applies the four rules to each entry: [messages, errors, dropped]. An entry kept by the model or its gate is not an
     * error (it waits); a table still referenced by a foreign key is, since the model says nothing should point at it.
     */
    static Map apply(Connection conn, List<Map<String, String>> drops, Map<String, Set<String>> model, boolean dryRun) {
        List<String> messages = []
        List<String> errors = []
        int dropped = 0
        for (Map<String, String> drop in drops) {
            String table = storedName(conn, drop.table, null)
            String label = drop.column ? "${drop.table}.${drop.column}" : drop.table
            if (table == null) { messages << "${label}: not in this database; nothing to do.".toString(); continue }
            String sql
            String size
            if (drop.column) {
                String column = storedName(conn, table, drop.column)
                if (column == null) { messages << "${label}: not in this database; nothing to do.".toString(); continue }
                Long waiting = drop.gate ? count(conn, drop.gate) : 0L
                if (model.get(drop.table.toUpperCase())?.contains(drop.column.toUpperCase())) {
                    messages << ("${label}: still a field in the entity model; kept." +
                            (drop.gate ? " Gate: ${waiting} ${drop.gateWords}." : "")).toString()
                    continue
                }
                if (waiting > 0) { messages << "${label}: kept, ${waiting} ${drop.gateWords}.".toString(); continue }
                sql = "ALTER TABLE ${table} DROP COLUMN ${column}"
                size = "${count(conn, "SELECT COUNT(*) FROM ${table} WHERE ${column} IS NOT NULL")} value(s)"
            } else {
                if (model.containsKey(drop.table.toUpperCase())) {
                    messages << "${label}: an entity in the model still maps it; kept.".toString()
                    continue
                }
                List<String> referrers = referrers(conn, table)
                if (referrers) {
                    errors << "${label}: still referenced by a foreign key from ${referrers.join(', ')}; not dropped.".toString()
                    continue
                }
                sql = "DROP TABLE ${table}"
                size = "${count(conn, "SELECT COUNT(*) FROM ${table}")} row(s)"
            }
            if (dryRun) {
                messages << "${label}: would run ${sql} (${size}; ${drop.why}).".toString()
            } else {
                Statement st = conn.createStatement()
                try { st.execute(sql) } finally { st.close() }
                dropped++
                messages << "${label}: ran ${sql} (${size}).".toString()
            }
        }
        return [messages: messages, errors: errors, dropped: dropped]
    }

    /**
     * The table (column null) or column name as this database stores it, upper, lower or as given; null when absent. Looked
     * up in the connection's own catalog and schema (MySQL's driver searches every database on the server for a null
     * catalog), and compared exactly: metadata names are LIKE patterns, where an underscore matches any character.
     */
    static String storedName(Connection conn, String table, String column) {
        DatabaseMetaData md = conn.metaData
        for (String t in [table, table.toUpperCase(), table.toLowerCase()].unique()) {
            ResultSet rs = column == null ? md.getTables(conn.catalog, conn.schema, t, ["TABLE"] as String[]) :
                    md.getColumns(conn.catalog, conn.schema, t, null)
            try {
                while (rs.next()) {
                    if (column == null) {
                        if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) return rs.getString("TABLE_NAME")
                    } else if (rs.getString("TABLE_NAME").equalsIgnoreCase(table) && rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                        return rs.getString("COLUMN_NAME")
                    }
                }
            } finally { rs.close() }
        }
        return null
    }

    /** Other tables with a foreign key to {@code table}. */
    private static List<String> referrers(Connection conn, String table) {
        Set<String> found = new TreeSet<>()
        ResultSet rs = conn.metaData.getExportedKeys(conn.catalog, conn.schema, table)
        try {
            while (rs.next()) {
                String from = rs.getString("FKTABLE_NAME")
                if (!from.equalsIgnoreCase(table)) found << from
            }
        } finally { rs.close() }
        return found as List<String>
    }

    private static long count(Connection conn, String sql) {
        Statement st = conn.createStatement()
        try {
            ResultSet rs = st.executeQuery(sql)
            try { return rs.next() ? rs.getLong(1) : 0L } finally { rs.close() }
        } finally { st.close() }
    }
}
