package darpan.agent

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** DAR-BE-159. Small pieces the brain change ticket services share: the allowed values, the idempotency hash, redacted JSON. */
class BrainTicketSupport {

    static final Set<String> CATEGORIES = ["THRESHOLD_OR_WINDOW", "EXCLUSION", "STATEMENT_SCOPE", "FIX_WORDING", "CAUSE_STANCE",
            "NEW_CHECK_EXISTING_READ", "NEW_OR_WIDER_CAUSE", "MISSING_FIELD", "NEW_SYSTEM_OR_PACK", "PRODUCT_BUG", "DISPLAY"] as Set
    static final Set<String> STATUSES = ["RECEIVED", "ACCEPTED", "DECLINED", "SHIPPED"] as Set
    /** The only moves a ticket makes. */
    static final Map<String, List<String>> MOVES = [RECEIVED: ["ACCEPTED", "DECLINED"], ACCEPTED: ["SHIPPED", "DECLINED"],
            DECLINED: [], SHIPPED: []]
    static final int INTERMEDIATE = 1023
    static final int MEDIUM = 255
    static final int LONG = 60000

    static String sha256(String text) {
        return MessageDigest.getInstance("SHA-256").digest((text ?: "").getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /** The idempotency key: sha256 of the normalised, trimmed text. */
    static String textHash(String text) { return sha256(AgentRedaction.normalize(text ?: "").trim()) }

    /** JSON of the value with every customer-data leaf blanked (typed data only); null for null. */
    static String redactedJson(Object value) {
        return value == null ? null : JsonOutput.toJson(AgentRedaction.redactTyped(value))
    }

    /** Creates the row in its own transaction; on a lost race (unique index) answers the winner's id. Null with an error otherwise. */
    static String insertOnce(def ec, Map fields) {
        try {
            ec.transaction.runRequireNew(null, "Could not create the ticket.", { ec.entity.makeValue("darpan.agent.BrainChangeTicket").setAll(fields).create() })
        } catch (Exception e) {
            String winner = existingId(ec, fields.companyUserGroupId as String, fields.conversationId as String, fields.textHash as String)
            if (winner != null) return winner
            throw e
        }
        return fields.ticketId as String
    }

    static String existingId(def ec, String tenantId, String conversationId, String textHash) {
        def row = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.BrainChangeTicket", "brain ticket idempotency: tenant compared in the condition").condition("companyUserGroupId", tenantId)
                .condition("conversationId", conversationId).condition("textHash", textHash).useCache(false).one()
        return row?.ticketId as String
    }

    /** The ticket as the UI and staff read it. */
    static Map toMap(def row) {
        return [ticketId: row.ticketId, companyUserGroupId: row.companyUserGroupId, conversationId: row.conversationId,
                category: row.category, verbatimText: row.verbatimText, refsJson: row.refsJson, evidenceJson: row.evidenceJson,
                placementJson: row.placementJson, status: row.status, statusReason: row.statusReason,
                shippedVersion: row.shippedVersion, createdByUserId: row.createdByUserId, createdDate: row.createdDate]
    }
}
