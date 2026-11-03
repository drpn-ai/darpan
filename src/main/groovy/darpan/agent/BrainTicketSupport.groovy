package darpan.agent

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** DAR-BE-159. Small pieces the brain change ticket services share: the allowed values, the idempotency hash, redacted JSON. */
class BrainTicketSupport {

    static final Set<String> CATEGORIES = ["THRESHOLD_OR_WINDOW", "EXCLUSION", "STATEMENT_SCOPE", "FIX_WORDING", "CAUSE_STANCE",
            "NEW_CHECK_EXISTING_READ", "NEW_OR_WIDER_CAUSE", "MISSING_FIELD", "NEW_SYSTEM_OR_PACK", "PRODUCT_BUG", "DISPLAY"] as Set
    static final Set<String> STATUSES = ["RECEIVED", "ACCEPTED", "DECLINED", "SHIPPED"] as Set
    static final int INTERMEDIATE = 1023
    static final int MEDIUM = 255

    static String sha256(String text) {
        return MessageDigest.getInstance("SHA-256").digest((text ?: "").getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /** JSON of the value with every customer-data leaf blanked; null for null. */
    static String redactedJson(Object value) {
        return value == null ? null : JsonOutput.toJson(AgentRedaction.redactLeaves(value))
    }

    /** The ticket as the UI and staff read it. */
    static Map toMap(def row) {
        return [ticketId: row.ticketId, companyUserGroupId: row.companyUserGroupId, conversationId: row.conversationId,
                category: row.category, verbatimText: row.verbatimText, refsJson: row.refsJson, evidenceJson: row.evidenceJson,
                placementJson: row.placementJson, status: row.status, statusReason: row.statusReason,
                shippedVersion: row.shippedVersion, createdByUserId: row.createdByUserId, createdDate: row.createdDate]
    }
}
