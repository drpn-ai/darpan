package darpan.reconciliation.notification

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-108: streak celebrations shaped per chat provider. Moqui-free, so it runs in the unitTest pool. */
class StreakCelebrationSupportTests {

    private static final String BASE = "https://app.drpn.ai"
    private static final String GOOGLE_URL = "https://chat.googleapis.com/v1/spaces/AAAA/messages?key=k&token=t"
    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/XXXX"

    @AfterEach
    void resetHooks() {
        TenantNotificationSupport.resetDeliveryHook()
        SlackApiClient.resetTransportHook()
    }

    @Test
    void everyCatalogEntryNamesAGifATitleAndAltText() {
        StreakCelebrationSupport.CATALOG.each { String key, Map<String, String> entry ->
            assertTrue(entry.file.endsWith(".gif"), key)
            assertTrue(entry.title as boolean, key)
            assertTrue(entry.altText as boolean, key)
        }
    }

    @Test
    void celebrationPointsAtTheAppsStreaksFolder() {
        Map c = StreakCelebrationSupport.celebration(BASE + "/", "MONTH_DOZE")
        assertEquals("https://app.drpn.ai/streaks/darpan-doze.gif", c.imageUrl)
        assertEquals("Month Doze", c.title)
    }

    @Test
    void noBaseUrlOrUnknownKeyMeansNoPicture() {
        assertNull(StreakCelebrationSupport.celebration(null, "MONTH_DOZE"))
        assertNull(StreakCelebrationSupport.celebration("  ", "MONTH_DOZE"))
        assertNull(StreakCelebrationSupport.celebration(BASE, "NOT_A_BADGE"))
    }

    @Test
    void aPlainPayloadPassesThroughUntouched() {
        Map<String, Object> plain = [text: "gorjana · Returns · clean."]
        assertSame(plain, StreakCelebrationSupport.forGoogleChat(plain))
        assertSame(plain, StreakCelebrationSupport.forSlackWebhook(plain))
        assertNull(StreakCelebrationSupport.slackBlocks(plain))
    }

    @Test
    void googleChatGetsACardsV2ImageAndNeverTheCelebrationKey() {
        Map<String, Object> shaped = StreakCelebrationSupport.forGoogleChat(celebrating("MONTH_DOZE"))
        assertEquals("gorjana · Returns · clean. 30 days in a row.", shaped.text)
        assertFalse(shaped.containsKey("celebration"), "Google Chat rejects unknown fields with HTTP 400")
        Map image = shaped.cardsV2[0].card.sections[0].widgets[0].image as Map
        assertEquals("https://app.drpn.ai/streaks/darpan-doze.gif", image.imageUrl)
        assertTrue((image.altText as String).contains("Month Doze"))
    }

    @Test
    void slackGetsTheTextAsABlockThenTheImage() {
        Map<String, Object> shaped = StreakCelebrationSupport.forSlackWebhook(celebrating("BIG_HAUL"))
        assertFalse(shaped.containsKey("celebration"))
        assertEquals("gorjana · Returns · clean. 30 days in a row.", shaped.text, "text stays as the notification preview")
        List blocks = shaped.blocks as List
        assertEquals("section", blocks[0].type)
        assertEquals("image", blocks[1].type)
        assertEquals("https://app.drpn.ai/streaks/darpan-haul.gif", blocks[1].image_url)
        assertTrue(blocks[1].alt_text as boolean, "Slack refuses an image block without alt_text")
        assertEquals("Big Haul", blocks[1].title.text)
    }

    @Test
    void deliveryShapesEachDestinationForItsOwnProvider() {
        List<Map<String, Object>> seen = []
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> seen << payload; return [ok: true] }
        Map<String, Object> payload = celebrating("QUARTER_CLEAN")

        TenantNotificationSupport.deliverToDestination(
                [deliveryMode: TenantNotificationSupport.DELIVERY_WEBHOOK, webhookUrl: GOOGLE_URL,
                 chatProviderEnumId: TenantNotificationSupport.PROVIDER_GOOGLE], payload)
        TenantNotificationSupport.deliverToDestination(
                [deliveryMode: TenantNotificationSupport.DELIVERY_WEBHOOK, webhookUrl: SLACK_URL,
                 chatProviderEnumId: TenantNotificationSupport.PROVIDER_SLACK], payload)

        assertTrue(seen[0].containsKey("cardsV2") && !seen[0].containsKey("blocks"))
        assertTrue(seen[1].containsKey("blocks") && !seen[1].containsKey("cardsV2"))
        assertTrue(payload.containsKey("celebration"), "the shared payload must not be mutated between destinations")
    }

    @Test
    void theSlackBotSendsBlocksAsAJsonString() {
        Map captured = null
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            captured = params
            return [statusCode: 200, body: '{"ok":true,"channel":"C123","ts":"1.2"}']
        }
        List blocks = StreakCelebrationSupport.slackBlocks(celebrating("GLORIOUSLY_BORING"))
        assertTrue(SlackApiClient.postMessage("xoxb-test", "C123", "hello", blocks).ok as boolean)
        assertTrue(captured.blocks instanceof String, "form encoding needs blocks as a JSON string")
        assertEquals("image", new JsonSlurper().parseText(captured.blocks as String)[1].type)

        SlackApiClient.postMessage("xoxb-test", "C123", "hello")
        assertFalse(captured.containsKey("blocks"), "a text-only message must be sent exactly as before")
    }

    private static Map<String, Object> celebrating(String key) {
        return [text       : "gorjana · Returns · clean. 30 days in a row.",
                celebration: StreakCelebrationSupport.celebration(BASE, key)]
    }
}
