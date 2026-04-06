import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

PilotAccessSupport.requireSuperAdmin(ec, "Pilot settings are restricted to super-admin users.")

Set<String> allowedProviders = ["OPENAI", "GEMINI"] as Set<String>
Map defaultByProvider = [
    OPENAI: [
        baseUrl: "https://api.openai.com",
        model: "gpt-4.1-mini",
        timeout: "45",
        keyEnv: "OPENAI_API_KEY",
        modelEnv: "OPENAI_MODEL",
        baseUrlEnv: "OPENAI_API_BASE_URL",
        timeoutEnv: "OPENAI_TIMEOUT_SECONDS"
    ],
    GEMINI: [
        baseUrl: "https://generativelanguage.googleapis.com",
        model: "gemini-2.0-flash",
        timeout: "45",
        keyEnv: "GEMINI_API_KEY",
        modelEnv: "GEMINI_MODEL",
        baseUrlEnv: "GEMINI_API_BASE_URL",
        timeoutEnv: "GEMINI_TIMEOUT_SECONDS"
    ]
]

if (!ec.message.hasError()) {
    String providerFromParam = FacadeSupport.normalize(llmProvider)?.toUpperCase()
    def activeProviderRec = ec.entity.find("moqui.service.message.SystemMessageRemote")
        .condition("systemMessageRemoteId", "RULE_WORKSPACE_LLM_ACTIVE")
        .useCache(false)
        .one()
    String activeProvider = FacadeSupport.normalize(activeProviderRec?.username)?.toUpperCase()
    if (!allowedProviders.contains(activeProvider)) activeProvider = "OPENAI"

    String selectedProvider = allowedProviders.contains(providerFromParam) ? providerFromParam : activeProvider
    Map defaults = defaultByProvider[selectedProvider]

    def llmRemote = ec.entity.find("moqui.service.message.SystemMessageRemote")
        .condition("systemMessageRemoteId", selectedProvider + "_RULE_WORKSPACE")
        .useCache(false)
        .one()

    String fallbackModel = (System.getenv(defaults.modelEnv) ?: ec.resource.properties["darpan.${selectedProvider.toLowerCase()}.model"] ?: defaults.model)?.toString()
    String fallbackBaseUrl = (System.getenv(defaults.baseUrlEnv) ?: ec.resource.properties["darpan.${selectedProvider.toLowerCase()}.baseUrl"] ?: defaults.baseUrl)?.toString()
    String fallbackTimeout = (System.getenv(defaults.timeoutEnv) ?: ec.resource.properties["darpan.${selectedProvider.toLowerCase()}.timeoutSeconds"] ?: defaults.timeout)?.toString()
    String fallbackKey = System.getenv(defaults.keyEnv)?.toString()
    if (!fallbackKey && selectedProvider == "OPENAI") {
        fallbackKey = (ec.resource.properties["darpan.openai.apiKey"] ?: "").toString()
    }

    llmSettings = [
        activeProvider: activeProvider,
        llmProvider: selectedProvider,
        llmModel: llmRemote?.username ?: fallbackModel,
        llmBaseUrl: llmRemote?.sendUrl ?: fallbackBaseUrl,
        llmTimeoutSeconds: (llmRemote?.internalAppCode ?: fallbackTimeout)?.toString(),
        llmEnabled: llmRemote?.remoteAttributes ?: "Y",
        hasStoredLlmApiKey: !!FacadeSupport.normalize(llmRemote?.password),
        hasFallbackLlmApiKey: !!FacadeSupport.normalize(fallbackKey) && !FacadeSupport.normalize(llmRemote?.password),
        fallbackLlmKeyEnvName: defaults.keyEnv,
    ]
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
