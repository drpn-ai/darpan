package darpan.facade.settings

import darpan.facade.common.FacadeSupport

class LlmSettingsSupport {
    static final Set<String> ALLOWED_PROVIDERS = ["OPENAI", "GEMINI"] as Set<String>
    static final Map<String, Map<String, String>> DEFAULT_BY_PROVIDER = [
            OPENAI: [
                    baseUrl   : "https://api.openai.com",
                    model     : "gpt-4.1-mini",
                    timeout   : "45",
                    keyEnv    : "OPENAI_API_KEY",
                    modelEnv  : "OPENAI_MODEL",
                    baseUrlEnv: "OPENAI_API_BASE_URL",
                    timeoutEnv: "OPENAI_TIMEOUT_SECONDS"
            ],
            GEMINI: [
                    baseUrl   : "https://generativelanguage.googleapis.com",
                    model     : "gemini-2.0-flash",
                    timeout   : "45",
                    keyEnv    : "GEMINI_API_KEY",
                    modelEnv  : "GEMINI_MODEL",
                    baseUrlEnv: "GEMINI_API_BASE_URL",
                    timeoutEnv: "GEMINI_TIMEOUT_SECONDS"
            ]
    ]

    static String normalizeProvider(Object rawProvider) {
        String provider = FacadeSupport.normalize(rawProvider)?.toUpperCase()
        return ALLOWED_PROVIDERS.contains(provider) ? provider : "OPENAI"
    }

    static String normalizeTimeout(Object rawTimeout, String defaultTimeout) {
        String timeout = FacadeSupport.normalize(rawTimeout) ?: defaultTimeout
        return timeout ==~ /\d+/ ? timeout : defaultTimeout
    }

    static String resolveFallbackProperty(def ec, String propertyName, String defaultValue) {
        return (ec?.resource?.properties?.get(propertyName) ?: defaultValue)?.toString()
    }

    static Map<String, Object> resolveProviderContext(def ec, Object rawProvider) {
        def activeProviderRec = ec?.entity?.find("moqui.service.message.SystemMessageRemote")
                ?.condition("systemMessageRemoteId", "RULE_WORKSPACE_LLM_ACTIVE")
                ?.useCache(false)
                ?.one()
        String activeProvider = normalizeProvider(activeProviderRec?.username)
        String selectedProvider = FacadeSupport.normalize(rawProvider) ? normalizeProvider(rawProvider) : activeProvider
        Map<String, String> defaults = DEFAULT_BY_PROVIDER[selectedProvider]

        def llmRemote = ec?.entity?.find("moqui.service.message.SystemMessageRemote")
                ?.condition("systemMessageRemoteId", "${selectedProvider}_RULE_WORKSPACE")
                ?.useCache(false)
                ?.one()

        String providerKey = selectedProvider.toLowerCase()
        String fallbackModel = (System.getenv(defaults.modelEnv) ?:
                resolveFallbackProperty(ec, "darpan.${providerKey}.model", defaults.model))?.toString()
        String fallbackBaseUrl = (System.getenv(defaults.baseUrlEnv) ?:
                resolveFallbackProperty(ec, "darpan.${providerKey}.baseUrl", defaults.baseUrl))?.toString()
        String fallbackTimeout = normalizeTimeout(System.getenv(defaults.timeoutEnv) ?:
                resolveFallbackProperty(ec, "darpan.${providerKey}.timeoutSeconds", defaults.timeout), defaults.timeout)

        String fallbackKey = System.getenv(defaults.keyEnv)?.toString()
        if (!fallbackKey && selectedProvider == "OPENAI") {
            fallbackKey = resolveFallbackProperty(ec, "darpan.openai.apiKey", "")
        }

        return [
                activeProvider     : activeProvider,
                selectedProvider   : selectedProvider,
                defaults           : defaults,
                llmRemote          : llmRemote,
                fallbackModel      : fallbackModel,
                fallbackBaseUrl    : fallbackBaseUrl,
                fallbackTimeout    : fallbackTimeout,
                fallbackKey        : fallbackKey,
                fallbackKeyEnvName : defaults.keyEnv,
        ]
    }

    static Map<String, Object> buildReadSettings(def ec, Object rawProvider) {
        Map<String, Object> providerContext = resolveProviderContext(ec, rawProvider)
        def llmRemote = providerContext.llmRemote

        return [
                activeProvider        : providerContext.activeProvider,
                llmProvider           : providerContext.selectedProvider,
                llmModel              : llmRemote?.username ?: providerContext.fallbackModel,
                llmBaseUrl            : llmRemote?.sendUrl ?: providerContext.fallbackBaseUrl,
                llmTimeoutSeconds     : normalizeTimeout(llmRemote?.internalAppCode ?: providerContext.fallbackTimeout,
                        providerContext.defaults.timeout),
                llmEnabled            : llmRemote?.remoteAttributes ?: "Y",
                hasStoredLlmApiKey    : !!FacadeSupport.normalize(llmRemote?.password),
                hasFallbackLlmApiKey  : !!FacadeSupport.normalize(providerContext.fallbackKey) &&
                        !FacadeSupport.normalize(llmRemote?.password),
                fallbackLlmKeyEnvName : providerContext.fallbackKeyEnvName,
        ]
    }

    static Map<String, Object> buildSavePayload(def ec, Object rawProvider, Object llmApiKey, Object llmModel,
            Object llmBaseUrl, Object llmTimeoutSeconds, Object llmEnabled) {
        Map<String, Object> providerContext = resolveProviderContext(ec, rawProvider)
        String provider = providerContext.selectedProvider as String
        def existingProviderRemote = providerContext.llmRemote
        Map<String, String> defaults = providerContext.defaults as Map<String, String>

        String modelToUse = FacadeSupport.normalize(llmModel) ?:
                FacadeSupport.normalize(existingProviderRemote?.username) ?:
                defaults.model
        String baseUrlToUse = FacadeSupport.normalize(llmBaseUrl) ?:
                FacadeSupport.normalize(existingProviderRemote?.sendUrl) ?:
                defaults.baseUrl
        String timeoutToUse = normalizeTimeout(FacadeSupport.normalize(llmTimeoutSeconds) ?:
                FacadeSupport.normalize(existingProviderRemote?.internalAppCode), defaults.timeout)
        String remoteAttributes = FacadeSupport.normalizeBool(llmEnabled, true) ? "Y" : "N"

        Map<String, Object> llmRemoteMap = [
                systemMessageRemoteId : "${provider}_RULE_WORKSPACE",
                description           : "${provider} settings for Rule Workspace DRL generation",
                sendUrl               : baseUrlToUse,
                username              : modelToUse,
                internalAppCode       : timeoutToUse,
                remoteAttributes      : remoteAttributes,
                sendServiceName       : provider,
        ]

        String providedApiKey = FacadeSupport.normalize(llmApiKey)
        if (providedApiKey) {
            llmRemoteMap.password = providedApiKey
        } else if (existingProviderRemote?.password) {
            llmRemoteMap.password = existingProviderRemote.password
        }

        return [
                provider     : provider,
                llmRemoteMap : llmRemoteMap,
                llmActiveMap : [
                        systemMessageRemoteId : "RULE_WORKSPACE_LLM_ACTIVE",
                        description           : "Active provider for Rule Workspace DRL generation",
                        username              : provider,
                        remoteAttributes      : "Y"
                ],
                llmSettings  : [
                        activeProvider     : provider,
                        llmProvider        : provider,
                        llmModel           : modelToUse,
                        llmBaseUrl         : baseUrlToUse,
                        llmTimeoutSeconds  : timeoutToUse,
                        llmEnabled         : remoteAttributes,
                        hasStoredLlmApiKey : !!FacadeSupport.normalize(llmRemoteMap.password),
                ]
        ]
    }
}
