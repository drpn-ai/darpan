package darpan.facade.settings

import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class SettingsFacadeSupportTests {

    @Test
    void validateJsonObjectTextRejectsArraysAndBadJson() {
        assertNull(SettingsFacadeSupport.validateJsonObjectText('{"header":"value"}', "Headers JSON"))
        assertEquals("Headers JSON must be a JSON object.", SettingsFacadeSupport.validateJsonObjectText('["value"]', "Headers JSON"))
        assertTrue(SettingsFacadeSupport.validateJsonObjectText('{"broken"', "Headers JSON")?.startsWith("Headers JSON is invalid:"))
    }

    @Test
    void deduplicateEnumOptionsPrefersCanonicalSystemIds() {
        List<Map<String, Object>> options = [
                [enumId: "DarSysOms", enumCode: "OMS", description: "OMS", sequenceNum: 1, label: "OMS"],
                [enumId: "OMS", enumCode: "HOTWAX", description: "HotWax", sequenceNum: 1, label: "HotWax"],
                [enumId: "HOTWAX", enumCode: "HOTWAX", description: "HotWax", sequenceNum: 1, label: "HotWax"],
                [enumId: "DarSysShopify", enumCode: "SHOPIFY", description: "Shopify", sequenceNum: 2, label: "SHOPIFY"],
                [enumId: "SHOPIFY", enumCode: "SHOPIFY", description: "Shopify", sequenceNum: 2, label: "SHOPIFY"],
                [enumId: "NETSUITE", enumCode: "NETSUITE", description: "NetSuite", sequenceNum: 3, label: "NETSUITE"],
        ]

        List<Map<String, Object>> deduplicated = SettingsFacadeSupport.deduplicateEnumOptions("DarpanSystemSource", options)

        assertEquals(3, deduplicated.size())
        assertIterableEquals(["OMS", "SHOPIFY", "NETSUITE"], deduplicated.collect { it.enumId })
        assertIterableEquals(["HOTWAX", "SHOPIFY", "NETSUITE"], deduplicated.collect { it.enumCode })
        assertIterableEquals(["HotWax", "SHOPIFY", "NETSUITE"], deduplicated.collect { it.label })
    }

    @Test
    void googleChatWebhookValidationAndMaskingKeepSecretsOutOfResponses() {
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/AAQAayYEtUA/messages?key=test-key&token=test-token"

        assertNull(TenantNotificationSupport.validateGoogleChatWebhookUrl(webhookUrl))
        assertEquals(
                "https://chat.googleapis.com/v1/spaces/AAQA...EtUA/messages?key=...&token=...",
                TenantNotificationSupport.maskGoogleChatWebhookUrl(webhookUrl)
        )
        assertEquals("Google Chat webhook URL must use https.",
                TenantNotificationSupport.validateGoogleChatWebhookUrl("http://chat.googleapis.com/v1/spaces/test/messages?key=a&token=b"))
        assertEquals("Google Chat webhook URL must use chat.googleapis.com.",
                TenantNotificationSupport.validateGoogleChatWebhookUrl("https://example.com/v1/spaces/test/messages?key=a&token=b"))
        assertEquals("Google Chat webhook URL must include key and token query parameters.",
                TenantNotificationSupport.validateGoogleChatWebhookUrl("https://chat.googleapis.com/v1/spaces/test/messages?key=a"))
    }

}
