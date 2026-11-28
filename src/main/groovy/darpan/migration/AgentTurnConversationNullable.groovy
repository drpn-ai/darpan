package darpan.migration

import darpan.audit.AcknowledgementNoteMigration

import java.sql.Connection

/**
 * DAR-OPS-029 OC186 (Aditi 2026-11-28: "Record it"). A one-shot writer's request is spend on the tenant's ledger, an
 * AgentTurn row with no conversation, so AgentTurn.conversationId is no longer not-null. Moqui creates new columns but never
 * relaxes an existing one, so a database made before keeps NOT NULL until this runs. Schema only, no row changes.
 * Idempotent: a nullable column is left alone. The statement and its MySQL lessons (MODIFY restates a sized type; DDL runs
 * outside the caller's XA transaction) are AcknowledgementNoteMigration's, reused rather than copied.
 */
class AgentTurnConversationNullable {

    static final String ENTITY = "darpan.agent.AgentTurn"
    static final String FIELD = "conversationId"

    /** The registered migration: [changed, statement, message, error]. The caller's transaction is suspended around the DDL. */
    static Map run(def ec, boolean dryRun) {
        def ed = ec.entity.getEntityDefinition(ENTITY)
        boolean suspended = ec.transaction.isTransactionInPlace() ? ec.transaction.suspend() : false
        try {
            Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(ENTITY))
            try {
                return AcknowledgementNoteMigration.allowNull(conn, ed.getFullTableName() as String, ed.getColumnName(FIELD) as String, dryRun)
            } finally {
                conn.close()
            }
        } finally {
            if (suspended) ec.transaction.resume()
        }
    }
}
