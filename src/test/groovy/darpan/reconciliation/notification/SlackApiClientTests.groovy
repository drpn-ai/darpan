package darpan.reconciliation.notification

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The Slack Web API reports failure with HTTP 200 and {@code {"ok": false, "error": "..."}}, so the
 * status code alone can never decide whether a notification was delivered. These tests pin that
 * contract, the actionable/transient split that decides what an operator is told, and the ts
 * handle that a later chat.update depends on.
 */
class SlackApiClientTests {

    @AfterEach
    void clearHook() { SlackApiClient.resetTransportHook() }

    @Test
    void parametersAreFormEncodedBecauseSlackSilentlyIgnoresAJsonBodyOnSomeMethods() {
        // The bug this pins: conversations.list does not read a JSON body and does not say so. It
        // answers ok:true with a plausible channel list, having ignored every parameter and applied
        // its defaults — so the picker silently never offered a private channel. Measured live
        // 2026-08-24: JSON body -> 0 private channels, form-encoded -> 1.
        String encoded = SlackApiClient.formEncode([
                types           : "public_channel,private_channel",
                exclude_archived: true,
                limit           : 200,
        ])
        assertTrue(encoded.contains("types=public_channel%2Cprivate_channel"),
                "types must survive encoding, comma included: ${encoded}")
        assertTrue(encoded.contains("exclude_archived=true"))
        assertTrue(encoded.contains("limit=200"))
        assertFalse(encoded.startsWith("{"), "a JSON body is silently ignored by some methods")
    }

    @Test
    void formEncodingSkipsNullsAndEscapesValues() {
        String encoded = SlackApiClient.formEncode([channel: "C1", cursor: null, text: "a b&c=d"])
        assertFalse(encoded.contains("cursor"), "a null cursor must not be sent as the string 'null'")
        assertTrue(encoded.contains("text=a+b%26c%3Dd"),
                "unescaped & or = would truncate or forge later parameters: ${encoded}")
    }

    @Test
    void anOkTrueBodyIsTheOnlySuccess() {
        Map<String, Object> outcome = SlackApiClient.interpretResponse(200, '{"ok":true,"channel":"C1","ts":"1712.45"}')
        assertTrue(outcome.ok as boolean)
        assertNull(outcome.errorCode)
        assertFalse(outcome.transient as boolean)
    }

    @Test
    void http200WithOkFalseIsAFailureNotADelivery() {
        // The headline trap. Every one of these arrives as HTTP 200.
        ["channel_not_found", "not_in_channel", "is_archived", "token_revoked", "missing_scope"].each { String code ->
            Map<String, Object> outcome = SlackApiClient.interpretResponse(200, """{"ok":false,"error":"${code}"}""")
            assertFalse(outcome.ok as boolean, "HTTP 200 + ${code} must not read as delivered")
            assertEquals(code, outcome.errorCode)
            assertTrue(SlackApiClient.isActionable(code), "${code} needs a human, not a retry")
            assertFalse(outcome.transient as boolean, "${code} must not be classified transient")
        }
    }

    @Test
    void transientFailuresAreSeparatedFromMisconfiguration() {
        // A rate-limited run and a deleted channel both fail, but only one is the operator's problem.
        // Conflating them is how a blip gets debugged as a permissions bug.
        Map<String, Object> limited = SlackApiClient.interpretResponse(200, '{"ok":false,"error":"rate_limited"}')
        assertTrue(limited.transient as boolean)
        assertFalse(SlackApiClient.isActionable("rate_limited"))

        Map<String, Object> serverSide = SlackApiClient.interpretResponse(503, '{"ok":false,"error":"service_unavailable"}')
        assertTrue(serverSide.transient as boolean)
    }

    @Test
    void aBareHttp429IsRateLimitingEvenWithNoBody() {
        // Slack's 429 carries no JSON; without this branch it would surface as "unreadable response"
        // and read like a Darpan bug.
        Map<String, Object> outcome = SlackApiClient.interpretResponse(429, "")
        assertFalse(outcome.ok as boolean)
        assertEquals("rate_limited", outcome.errorCode)
        assertTrue(outcome.transient as boolean)
    }

    @Test
    void anUnreadableBodyIsAFailureNotAnOptimisticSuccess() {
        Map<String, Object> outcome = SlackApiClient.interpretResponse(200, "<html>gateway</html>")
        assertFalse(outcome.ok as boolean)
        assertEquals("unparseable_response", outcome.errorCode)
    }

    @Test
    void everyActionableErrorGetsAnOperatorSentenceNotARawCode() {
        SlackApiClient.ACTIONABLE_ERRORS.each { String code ->
            String message = SlackApiClient.describeError(code)
            assertFalse(message.contains(code),
                    "${code} still leaks the raw code to the operator: ${message}")
            assertTrue(message.length() > 20, "${code} has no usable explanation")
        }
    }

    @Test
    void notInChannelNamesTheFixTheAdminCanActuallyPerform() {
        assertTrue(SlackApiClient.describeError("not_in_channel").contains("Invite @Darpan"))
    }

    @Test
    void anUnknownErrorCodeStillSurfacesTheCode() {
        // The inverse of the rule above: with no sentence to offer, the raw code is better than
        // silence, because it is what a support thread will search for.
        assertTrue(SlackApiClient.describeError("some_new_slack_error").contains("some_new_slack_error"))
    }

    @Test
    void postMessageReturnsTheTsHandleUpdateDependsOn() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            assertEquals("chat.postMessage", method)
            assertEquals("C123", params.channel)
            return [statusCode: 200, body: '{"ok":true,"channel":"C123","ts":"1712345678.000100"}']
        }
        Map<String, Object> outcome = SlackApiClient.postMessage("xoxb-test", "C123", "hello")
        assertTrue(outcome.ok as boolean)
        assertEquals("1712345678.000100", outcome.ts)
        assertEquals("C123", outcome.channelId)
    }

    @Test
    void postMessageFailureCarriesTheOperatorMessage() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, body: '{"ok":false,"error":"not_in_channel"}']
        }
        Map<String, Object> outcome = SlackApiClient.postMessage("xoxb-test", "C123", "hello")
        assertFalse(outcome.ok as boolean)
        assertNull(outcome.ts)
        assertTrue((outcome.operatorMessage as String).contains("Invite @Darpan"))
    }

    @Test
    void channelListFlagsPrivateChannelsTheBotIsNotIn() {
        // chat:write.public covers public channels the bot never joined; a private channel it is not
        // in fails at the first run with not_in_channel, long after the admin has moved on.
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            assertEquals("conversations.list", method)
            return [statusCode: 200, body: '''{"ok":true,"channels":[
                {"id":"C1","name":"ops","is_private":false,"is_member":false},
                {"id":"G2","name":"finance-private","is_private":true,"is_member":false},
                {"id":"G3","name":"joined-private","is_private":true,"is_member":true}],
                "response_metadata":{"next_cursor":"dXNlcjpVMDYxTkZUVDI="}}''']
        }
        Map<String, Object> outcome = SlackApiClient.listConversations("xoxb-test", null, null)
        assertTrue(outcome.ok as boolean)
        List<Map<String, Object>> channels = (List<Map<String, Object>>) outcome.channels
        assertEquals(3, channels.size())
        assertEquals(false, channels[0].isPrivate)
        assertEquals(true, channels[1].isPrivate)
        assertEquals(false, channels[1].isMember)
        assertEquals(true, channels[2].isMember)
        assertEquals("dXNlcjpVMDYxTkZUVDI=", outcome.nextCursor)
    }

    @Test
    void allPagesAreDrainedBecauseOneLooksCompleteWhenItIsNot() {
        // Slack filters AFTER taking a page, so page one comes back short while next_cursor still
        // points at more. Stopping there shows an arbitrary subset the operator cannot tell is partial.
        int calls = 0
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            calls++
            return calls == 1
                    ? [statusCode: 200, body: '{"ok":true,"channels":[{"id":"C1","name":"one"}],"response_metadata":{"next_cursor":"PAGE2"}}']
                    : [statusCode: 200, body: '{"ok":true,"channels":[{"id":"C2","name":"two"}],"response_metadata":{"next_cursor":""}}']
        }
        Map<String, Object> outcome = SlackApiClient.listAllConversations("xoxb-test")
        assertTrue(outcome.ok as boolean)
        assertEquals(2, ((List) outcome.channels).size())
        assertEquals(2, outcome.pagesFetched)
        assertEquals(false, outcome.truncated)
    }

    @Test
    void hittingThePageCapIsReportedRatherThanSilentlySwallowed() {
        // A silent cap reads exactly like "that is all the channels there are".
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, body: '{"ok":true,"channels":[{"id":"C1","name":"x"}],"response_metadata":{"next_cursor":"MORE"}}']
        }
        Map<String, Object> outcome = SlackApiClient.listAllConversations("xoxb-test")
        assertEquals(SlackApiClient.MAX_CONVERSATION_PAGES, outcome.pagesFetched)
        assertEquals(true, outcome.truncated)
    }

    @Test
    void aFailureMidPaginationIsReturnedNotPartiallySwallowed() {
        int calls = 0
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            calls++
            return calls == 1
                    ? [statusCode: 200, body: '{"ok":true,"channels":[{"id":"C1","name":"one"}],"response_metadata":{"next_cursor":"PAGE2"}}']
                    : [statusCode: 200, body: '{"ok":false,"error":"token_revoked"}']
        }
        Map<String, Object> outcome = SlackApiClient.listAllConversations("xoxb-test")
        assertFalse(outcome.ok as boolean)
        assertEquals("token_revoked", outcome.errorCode)
    }

    @Test
    void channelListReportsNoCursorOnTheLastPage() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, body: '{"ok":true,"channels":[],"response_metadata":{"next_cursor":""}}']
        }
        assertNull(SlackApiClient.listConversations("xoxb-test", null, null).nextCursor)
    }

    @Test
    void aRevokedTokenDuringChannelListingIsReportedAsReconnect() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, body: '{"ok":false,"error":"token_revoked"}']
        }
        Map<String, Object> outcome = SlackApiClient.listConversations("xoxb-dead", null, null)
        assertFalse(outcome.ok as boolean)
        assertTrue((outcome.operatorMessage as String).contains("Reconnect"))
    }
}
