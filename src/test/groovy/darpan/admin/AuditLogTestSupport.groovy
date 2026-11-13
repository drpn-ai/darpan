package darpan.admin

import groovy.transform.CompileDynamic

/**
 * DAR-BE-265. AdminAuditLog and DataExportLog are create-only: the entity facade refuses every delete. Tests that need a
 * clean trail between cases clear the table with plain SQL here, the way only the database owner could in production.
 */
@CompileDynamic
class AuditLogTestSupport {

    static final List<String> CREATE_ONLY = ["darpan.admin.AdminAuditLog", "darpan.admin.DataExportLog"]

    static void wipe(def ec, String entityName = "darpan.admin.AdminAuditLog") {
        def ed = ec.entity.getEntityDefinition(entityName)
        String group = ec.entity.getEntityGroupName(entityName)
        ec.transaction.runRequireNew(30, "Test wipe of ${entityName}".toString()) {
            def statement = ec.entity.getConnection(group).createStatement()
            try { statement.executeUpdate("DELETE FROM ${ed.getFullTableName()}".toString()) } finally { statement.close() }
        }
    }
}
