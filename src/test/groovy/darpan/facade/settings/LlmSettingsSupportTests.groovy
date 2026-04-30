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
                OPENAI_RULE_WORKSPACE    : [password: "stored-key", username: "stored-model", sendUrl: "https://stored.example", internalAppCode: "30"]
        ])
        def ec = new Expando(entity: entity, resource: new Expando(properties: [:]))

        Map<String, Object> payload = LlmSettingsSupport.buildSavePayload(ec, "openai", null, null, null, "abc", false)

        assertEquals("OPENAI", payload.provider)
        assertEquals("stored-key", payload.llmRemoteMap.password)
        assertEquals("stored-model", payload.llmRemoteMap.username)
        assertEquals("https://stored.example", payload.llmRemoteMap.sendUrl)
        assertEquals("45", payload.llmRemoteMap.internalAppCode)
        assertEquals("N", payload.llmRemoteMap.remoteAttributes)
        assertEquals("OPENAI", payload.llmActiveMap.username)
        assertTrue(payload.llmSettings.hasStoredLlmApiKey as boolean)
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
