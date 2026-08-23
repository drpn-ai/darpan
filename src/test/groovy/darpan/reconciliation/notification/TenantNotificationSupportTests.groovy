package darpan.reconciliation.notification

import darpan.facade.reconciliation.ExchangePairVerificationSupport
import darpan.facade.reconciliation.MissingDiffVerificationSupport
import darpan.facade.reconciliation.ReturnPresenceVerificationSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * I6: partitionAuditNotes must recognize every verification pass's always-emitted "show your work"
 * sentence, or that pass's runs are permanently misclassified WITH ISSUES even when all-clear.
 *
 * Before this fix, ReturnPresenceVerificationSupport.AUDIT_NOTE_PREFIX ("Return presence check: ")
 * was absent from the classifier's prefix list — see TenantNotificationSupport.partitionAuditNotes
 * — so every returns run, all-clear ones included, had its always-on audit note fall into
 * "warnings" and the chat header read "completed WITH ISSUES" every single time.
 */
class TenantNotificationSupportTests {

    @Test
    void anAllClearReturnPresenceAuditNoteIsNotClassifiedAsAWarning() {
        String note = "${ReturnPresenceVerificationSupport.AUDIT_NOTE_PREFIX}3 matched, 0 missing in Shopify, 0 missing in OMS, 0 pending (younger than 3h).".toString()

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([note])

        assertEquals(0, partitioned.warnings.size(), "the always-on audit note must never read as an issue: ${partitioned.warnings}")
        assertEquals(1, partitioned.auditNotes.size())
        assertTrue(partitioned.auditNotes.contains(note))
    }

    @Test
    void aGenuineReturnPresenceFailureStillClassifiesAsAWarning() {
        // Deliberately NOT the exact AUDIT_NOTE_PREFIX literal ("Return presence check: ") — a real
        // failure from this pass must still land in "warnings", the same distinction
        // ExchangePairVerificationSupport's own warnings rely on ("confirming"/"could not"/
        // "skipped:" rather than an immediate colon after the opening words).
        String failure = "Return presence check could not write diff rows: disk full"

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([failure])

        assertEquals(1, partitioned.warnings.size(), "a genuine failure must still surface as a warning: ${partitioned}")
        assertEquals(0, partitioned.auditNotes.size())
    }

    @Test
    void stillClassifiesExchangeAndMissingDiffAuditNotesCorrectly() {
        // Regression guard: extending the prefix list to include returns must not disturb the two
        // pre-existing producers.
        String exchangeNote = "${ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX}0 Shopify exchange(s) in window — 0 matched in OMS, 0 confirmed by lookup, 0 missing from OMS, 0 pending (younger than 3h).".toString()
        String missingDiffNote = "${MissingDiffVerificationSupport.AUDIT_NOTE_PREFIX}0 of 0 differences confirmed present and removed.".toString()

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([exchangeNote, missingDiffNote])

        assertEquals(0, partitioned.warnings.size())
        assertEquals(2, partitioned.auditNotes.size())
    }

    @Test
    void blankAndNullEntriesAreIgnored() {
        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([null, "", "   "])

        assertEquals(0, partitioned.warnings.size())
        assertEquals(0, partitioned.auditNotes.size())
    }

    // ─── Slack provider support ──────────────────────────────────────────────────────────────────
    //
    // The host pin is an SSRF control, not cosmetic validation: the webhook URL is supplied by a
    // tenant admin and posted to by the server. The response contract is separate and equally
    // load-bearing — see interpretSlackResponse.

    private static final String VALID_SLACK_URL = "https://hooks.slack.com/services/T-EXAMPLE/B-EXAMPLE/placeholder-not-a-real-secret"
    private static final String VALID_GOOGLE_URL = "https://chat.googleapis.com/v1/spaces/AAAA/messages?key=k&token=t"

    @Test
    void aWellFormedSlackWebhookUrlPassesValidation() {
        assertNull(TenantNotificationSupport.validateSlackWebhookUrl(VALID_SLACK_URL))
    }

    @Test
    void slackValidationPinsTheHost() {
        // The whole point of the pin: an admin-supplied URL must not be able to aim the server at
        // internal infrastructure or an attacker-controlled collector.
        assertNotNull(TenantNotificationSupport.validateSlackWebhookUrl(
                "https://hooks.slack.com.evil.example/services/T-EXAMPLE/B-EXAMPLE/placeholder"))
        assertNotNull(TenantNotificationSupport.validateSlackWebhookUrl(
                "https://169.254.169.254/services/T-EXAMPLE/B-EXAMPLE/placeholder"))
        assertNotNull(TenantNotificationSupport.validateSlackWebhookUrl(
                "http://hooks.slack.com/services/T-EXAMPLE/B-EXAMPLE/placeholder"))
    }

    @Test
    void slackValidationRejectsATruncatedUrl() {
        // A URL cut short on paste passes a bare "/services/" prefix check and then fails only at
        // delivery time, hours later, with nothing shown in settings.
        assertNotNull(TenantNotificationSupport.validateSlackWebhookUrl(
                "https://hooks.slack.com/services/T-EXAMPLE"))
        assertNotNull(TenantNotificationSupport.validateSlackWebhookUrl(
                "https://hooks.slack.com/services/T-EXAMPLE/B-EXAMPLE"))
    }

    @Test
    void aBlankWebhookUrlIsNotAValidationError() {
        // Matches the Google validator's long-standing contract: "required" is the caller's job.
        assertNull(TenantNotificationSupport.validateSlackWebhookUrl(null))
        assertNull(TenantNotificationSupport.validateSlackWebhookUrl("   "))
    }

    @Test
    void validateWebhookUrlRoutesToTheProvidersOwnPin() {
        assertNull(TenantNotificationSupport.validateWebhookUrl(
                TenantNotificationSupport.PROVIDER_SLACK, VALID_SLACK_URL))
        assertNull(TenantNotificationSupport.validateWebhookUrl(
                TenantNotificationSupport.PROVIDER_GOOGLE, VALID_GOOGLE_URL))
        // Cross-provider paste — picking Slack and pasting the Google Chat URL, or the reverse — is
        // the likeliest real mistake, and each pin must catch the other's URL.
        assertNotNull(TenantNotificationSupport.validateWebhookUrl(
                TenantNotificationSupport.PROVIDER_SLACK, VALID_GOOGLE_URL))
        assertNotNull(TenantNotificationSupport.validateWebhookUrl(
                TenantNotificationSupport.PROVIDER_GOOGLE, VALID_SLACK_URL))
    }

    @Test
    void anUnknownProviderFailsClosed() {
        // No host pin exists for it, so it must never validate — falling through to "no error" would
        // let an arbitrary URL reach the delivery path.
        assertNotNull(TenantNotificationSupport.validateWebhookUrl("CHAT_PROV_TEAMS", VALID_SLACK_URL))
    }

    @Test
    void aNullProviderResolvesToGoogleChat() {
        // Every chat space written before Slack support has a null provider column; if that read as
        // anything else, existing tenants' notifications would stop dead.
        assertEquals(TenantNotificationSupport.PROVIDER_GOOGLE, TenantNotificationSupport.resolveChatProvider(null))
        assertEquals(TenantNotificationSupport.PROVIDER_GOOGLE, TenantNotificationSupport.resolveChatProvider("  "))
        assertEquals(TenantNotificationSupport.PROVIDER_SLACK,
                TenantNotificationSupport.resolveChatProvider(TenantNotificationSupport.PROVIDER_SLACK))
    }

    @Test
    void webhookUrlResolutionFallsBackToTheLegacyColumn() {
        // A deployment that has not run migrate#ChatSpaceWebhookUrls must keep delivering rather than
        // resolving every space to "not configured" and going silent.
        assertEquals(VALID_GOOGLE_URL, TenantNotificationSupport.resolveWebhookUrl(
                [webhookUrl: null, googleChatWebhookUrl: VALID_GOOGLE_URL]))
        assertEquals(VALID_SLACK_URL, TenantNotificationSupport.resolveWebhookUrl(
                [webhookUrl: VALID_SLACK_URL, googleChatWebhookUrl: null]))
        // New column wins when both are populated — that is the post-migration steady state for a
        // Google space, where both columns are written in lockstep.
        assertEquals(VALID_SLACK_URL, TenantNotificationSupport.resolveWebhookUrl(
                [webhookUrl: VALID_SLACK_URL, googleChatWebhookUrl: VALID_GOOGLE_URL]))
        assertNull(TenantNotificationSupport.resolveWebhookUrl(null))
    }

    @Test
    void aDeadSlackWebhookFailsAndKeepsSlacksReason() {
        // Slack's webhook errors arrive as 400/403/404 with the reason in the body — invalid_token
        // for a revoked hook, no_service for a deleted one, channel_is_archived for a dead channel.
        // The reason must survive into providerMessage: the status code alone tells an operator
        // nothing about which of those happened, and they need different fixes.
        Map<String, Object> invalidToken = TenantNotificationSupport.interpretSlackResponse(403, "invalid_token")
        assertEquals(false, invalidToken.ok)
        assertEquals("invalid_token", invalidToken.providerMessage)

        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(404, "no_service").ok)
        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(400, "channel_is_archived").ok)
        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(400, "invalid_payload").ok)
    }

    @Test
    void a2xxThatIsNotSlacksOkBodyIsNotADelivery() {
        // Belt-and-braces against something that is not Slack answering — a proxy or captive gateway
        // returning 200 with its own page is otherwise indistinguishable from a delivered message.
        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(200, "<html>gateway</html>").ok)
    }

    @Test
    void slackCountsOnlyAnOkBodyAsDelivered() {
        assertEquals(true, TenantNotificationSupport.interpretSlackResponse(200, "ok").ok)
        assertEquals(true, TenantNotificationSupport.interpretSlackResponse(200, "ok\n").ok)
        // An empty 200 is unexplained, and the only body Slack sends on success is "ok" — so it must
        // be loud rather than optimistically counted.
        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(200, "").ok)
        assertEquals(false, TenantNotificationSupport.interpretSlackResponse(200, null).ok)
    }

    @Test
    void slackNonSuccessStatusCarriesTheProviderMessage() {
        Map<String, Object> notFound = TenantNotificationSupport.interpretSlackResponse(404, "no_service")
        assertEquals(false, notFound.ok)
        assertEquals(404, notFound.statusCode)
        assertEquals("no_service", notFound.providerMessage)
    }
}
