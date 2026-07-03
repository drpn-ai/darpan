package darpan.facade.settings

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class LlmSettingsSupportTests {

    @Test
    void buildReadSettingsUsesActiveProviderAndPropertyFallbacks() {
        EntityFacadeStub entity = new EntityFacadeStub(recordsById: [
                RULE_WORKSPACE_LLM_ACTIVE: [username: "GEMINI"],
                GEMINI_RULE_WORKSPACE    : [password: "stored-gemini-key", remoteAttributes: "N"]
        ])
        def ec = new Expando(
                entity: entity,
                resource: new Expando(properties: [
                        "darpan.gemini.model"         : "gemini-custom",
                        "darpan.gemini.baseUrl"       : "https://gemini.example",
                        "darpan.gemini.timeoutSeconds": "90"
                ])
        )

        Map<String, Object> settings = LlmSettingsSupport.buildReadSettings(ec, null)

        assertEquals("GEMINI", settings.activeProvider)
        assertEquals("GEMINI", settings.llmProvider)
        assertEquals("gemini-custom", settings.llmModel)
        assertEquals("https://gemini.example", settings.llmBaseUrl)
        assertEquals("90", settings.llmTimeoutSeconds)
        assertEquals("N", settings.llmEnabled)
        assertTrue(settings.hasStoredLlmApiKey as boolean)
        assertFalse(settings.containsKey("llmApiKey"))
        assertFalse(settings.containsKey("password"))
        assertEquals("GEMINI_API_KEY", settings.fallbackLlmKeyEnvName)
    }

    @Test
    void buildSavePayloadPreservesStoredApiKeyAndNormalizesTimeout() {
        EntityFacadeStub entity = new EntityFacadeStub(recordsById: [
                RULE_WORKSPACE_LLM_ACTIVE: [username: "GEMINI"],
                OPENAI_RULE_WORKSPACE    : [password: "stored-key", username: "stored-model", sendUrl: "https://api.openai.com", internalAppCode: "30"]
        ])
        MessageStub message = new MessageStub()
        def ec = new Expando(entity: entity, message: message, resource: new Expando(properties: [:]))

        Map<String, Object> payload = LlmSettingsSupport.buildSavePayload(ec, "openai", null, null, null, "abc", false)

        assertFalse(message.hasError())
        assertEquals("OPENAI", payload.provider)
        assertEquals("stored-key", payload.llmRemoteMap.password)
        assertEquals("stored-model", payload.llmRemoteMap.username)
        assertEquals("https://api.openai.com", payload.llmRemoteMap.sendUrl)
        assertEquals("45", payload.llmRemoteMap.internalAppCode)
        assertEquals("N", payload.llmRemoteMap.remoteAttributes)
        assertEquals("OPENAI", payload.llmActiveMap.username)
        assertTrue(payload.llmSettings.hasStoredLlmApiKey as boolean)
    }

    @Test
    void buildSavePayloadAcceptsLegitProviderHttpsUrls() {
        // The two provider hosts the legitimate PWA sends must pass the SSRF policy and persist.
        ["https://api.openai.com": "openai", "https://generativelanguage.googleapis.com": "gemini"].each { url, provider ->
            MessageStub message = new MessageStub()
            def ec = new Expando(entity: new EntityFacadeStub(recordsById: [:]), message: message,
                    resource: new Expando(properties: [:]))
            Map<String, Object> payload = LlmSettingsSupport.buildSavePayload(ec, provider, "sk-test", null, url, "30", true)
            assertFalse(message.hasError(), "Legit provider URL ${url} must be accepted")
            assertEquals(url, payload.llmRemoteMap.sendUrl)
        }
    }

    @Test
    void buildSavePayloadRejectsSsrfAndNonProviderUrls() {
        // HIGH gaps 7,8: metadata IMDS, plain http, non-https schemes, and non-provider hosts must all be
        // rejected with an error and must NOT produce a persistable payload.
        ["http://169.254.169.254/latest/meta-data/",
         "http://api.openai.com",          // http downgrade
         "file:///etc/passwd",
         "gopher://evil.example/x",
         "https://attacker.example.com",   // valid https but not a provider host
         "not-a-url"].each { String badUrl ->
            MessageStub message = new MessageStub()
            def ec = new Expando(entity: new EntityFacadeStub(recordsById: [:]), message: message,
                    resource: new Expando(properties: [:]))
            Map<String, Object> payload = LlmSettingsSupport.buildSavePayload(ec, "openai", "sk-test", null, badUrl, "30", true)
            assertTrue(message.hasError(), "Expected error for ${badUrl}")
            assertTrue(payload.isEmpty(), "Expected empty (non-persistable) payload for ${badUrl}")
        }
    }

    static class MessageStub {
        List<String> errors = []
        void addError(Object error) { errors.add(error?.toString()) }
        boolean hasError() { return !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, Map> recordsById = [:]

        FinderStub find(String entityName) {
            return new FinderStub(recordsById: recordsById)
        }
    }

    static class FinderStub {
        Map<String, Map> recordsById = [:]
        String currentId

        FinderStub condition(String fieldName, Object value) {
            if (fieldName == "systemMessageRemoteId") currentId = value?.toString()
            return this
        }

        FinderStub useCache(boolean useCache) {
            return this
        }

        Object one() {
            return recordsById[currentId]
        }
    }
}
