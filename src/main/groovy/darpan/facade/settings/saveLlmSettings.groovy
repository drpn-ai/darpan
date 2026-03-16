import darpan.facade.common.FacadeSupport

Set<String> allowedProviders = ["OPENAI", "GEMINI"] as Set<String>
Map defaultByProvider = [
    OPENAI: [baseUrl: "https://api.openai.com", model: "gpt-4.1-mini", timeout: "45"],
    GEMINI: [baseUrl: "https://generativelanguage.googleapis.com", model: "gemini-2.0-flash", timeout: "45"]
]

String providerInput = FacadeSupport.normalize(llmProvider)?.toUpperCase()
String provider = allowedProviders.contains(providerInput) ? providerInput : "OPENAI"
Map defaults = defaultByProvider[provider]

def existingProviderRemote = ec.entity.find("moqui.service.message.SystemMessageRemote")
    .condition("systemMessageRemoteId", provider + "_RULE_WORKSPACE")
    .useCache(false)
    .one()

String modelToUse = FacadeSupport.normalize(llmModel) ?: FacadeSupport.normalize(existingProviderRemote?.username) ?: defaults.model
String baseUrlToUse = FacadeSupport.normalize(llmBaseUrl) ?: FacadeSupport.normalize(existingProviderRemote?.sendUrl) ?: defaults.baseUrl
String timeoutToUse = FacadeSupport.normalize(llmTimeoutSeconds) ?: FacadeSupport.normalize(existingProviderRemote?.internalAppCode) ?: defaults.timeout
if (!(timeoutToUse ==~ /\d+/)) timeoutToUse = defaults.timeout

Map llmRemoteMap = [
    systemMessageRemoteId: provider + "_RULE_WORKSPACE",
    description: provider + " settings for Rule Workspace DRL generation",
    sendUrl: baseUrlToUse,
    username: modelToUse,
    internalAppCode: timeoutToUse,
    remoteAttributes: FacadeSupport.normalizeBool(llmEnabled, true) ? "Y" : "N",
    sendServiceName: provider
]

String providedApiKey = FacadeSupport.normalize(llmApiKey)
if (providedApiKey) {
    llmRemoteMap.password = providedApiKey
} else if (existingProviderRemote?.password) {
    llmRemoteMap.password = existingProviderRemote.password
}

Map llmActiveMap = [
    systemMessageRemoteId: "RULE_WORKSPACE_LLM_ACTIVE",
    description: "Active provider for Rule Workspace DRL generation",
    username: provider,
    remoteAttributes: "Y"
]

ec.service.sync().name("store#moqui.service.message.SystemMessageRemote").parameters(llmRemoteMap).call()
ec.service.sync().name("store#moqui.service.message.SystemMessageRemote").parameters(llmActiveMap).call()

llmSettings = [
    activeProvider: provider,
    llmProvider: provider,
    llmModel: modelToUse,
    llmBaseUrl: baseUrlToUse,
    llmTimeoutSeconds: timeoutToUse,
    llmEnabled: llmRemoteMap.remoteAttributes,
    hasStoredLlmApiKey: !!FacadeSupport.normalize(llmRemoteMap.password),
]

if (!ec.message.hasError()) {
    ec.message.addMessage("Saved ${provider} provider settings.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
