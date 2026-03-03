import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

def logger = LoggerFactory.getLogger("darpan.rule.GenerateDrlFromPrompt")

def normalize = { Object value -> value?.toString()?.trim() }

def fallbackRuleName = { String baseName ->
    String normalizedName = (baseName ?: "Generated Rule")
            .replaceAll("[^A-Za-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim()
    if (!normalizedName) normalizedName = "Generated Rule"
    if (normalizedName.length() > 60) normalizedName = normalizedName.substring(0, 60).trim()
    return normalizedName
}

def escapeForDrlString = { String raw ->
    if (!raw) return ""
    String escaped = raw.replace('\r', ' ').replace('\n', ' ').trim()
    escaped = escaped.replace("\\", "\\\\").replace('"', '\\"')
    if (escaped.length() > 160) escaped = escaped.substring(0, 160)
    return escaped
}

def ensureDrlHeader = { String drlTextToFix ->
    String text = normalize(drlTextToFix)
    if (!text) return text

    if (!text.toLowerCase().contains("package ")) {
        text = "package darpan.rule\n\n${text}"
    }

    if (!text.contains("import java.util.Map")) {
        if (text.toLowerCase().startsWith("package ")) {
            int firstNewLine = text.indexOf('\n')
            if (firstNewLine >= 0) {
                String packageLine = text.substring(0, firstNewLine + 1).trim()
                String remainder = text.substring(firstNewLine + 1).trim()
                text = "${packageLine}\n\nimport java.util.Map\n\n${remainder}"
            } else {
                text = "${text}\n\nimport java.util.Map"
            }
        } else {
            text = "import java.util.Map\n\n${text}"
        }
    }

    return text
}

def buildFallbackDrl = { String promptTextToUse ->
    String ruleName = fallbackRuleName(promptTextToUse)
    String promptLiteral = escapeForDrlString(promptTextToUse)
    return """package darpan.rule

import java.util.Map

rule \"${ruleName}\"
when
    \$m : Map()
then
    // TODO: replace with business conditions from the prompt.
    \$m.put(\"ruleGeneratedFromPrompt\", \"${promptLiteral}\")
end
"""
}

def errorSummaryFromBody = { String body ->
    String raw = normalize(body)
    if (!raw) return null
    try {
        def parsed = new JsonSlurper().parseText(raw)
        String parsedMessage = normalize(parsed?.error?.message ?: parsed?.message)
        if (parsedMessage) return parsedMessage
    } catch (Exception ignored) {
    }
    String clipped = raw.replaceAll("[\\r\\n\\t]+", " ").trim()
    if (clipped.length() > 180) clipped = clipped.substring(0, 180)
    return clipped
}

def normalizeGeminiModelName = { String modelName ->
    String clean = normalize(modelName)
    if (!clean) return clean
    return clean.replaceFirst("^models/", "")
}

def resolveGeminiModel = { HttpClient client, String baseUrl, String apiKey, String preferredModel, int timeoutSeconds, List<String> warnings ->
    String preferred = normalizeGeminiModelName(preferredModel)
    try {
        String listEndpoint = "${baseUrl}/v1beta/models?key=${URLEncoder.encode(apiKey, 'UTF-8')}"
        HttpRequest listRequest = HttpRequest.newBuilder()
                .uri(URI.create(listEndpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .GET()
                .build()
        HttpResponse<String> listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString())
        if (listResponse.statusCode() < 200 || listResponse.statusCode() > 299) return preferred

        Map listMap = (Map) new JsonSlurper().parseText(listResponse.body() ?: "{}")
        List available = (listMap?.models instanceof List) ? (List) listMap.models : []
        if (!available) return preferred

        List<Map> withGenerate = available.findAll { modelEntry ->
            def methods = modelEntry?.supportedGenerationMethods
            return (methods instanceof Collection) && methods.contains("generateContent")
        }
        if (!withGenerate) return preferred

        List<String> names = withGenerate.collect { modelEntry -> normalizeGeminiModelName(modelEntry?.name?.toString()) }.findAll { it }
        if (!names) return preferred

        if (preferred && names.contains(preferred)) return preferred

        List<String> orderedPreferred = [
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-1.5-flash"
        ]
        for (String candidate : orderedPreferred) {
            if (names.contains(candidate)) {
                if (preferred && preferred != candidate) {
                    warnings.add("Gemini model ${preferred} unavailable; using ${candidate}.")
                }
                return candidate
            }
        }

        String flashName = names.find { n -> n.toLowerCase().contains("flash") }
        if (flashName) {
            if (preferred && preferred != flashName) warnings.add("Gemini model ${preferred} unavailable; using ${flashName}.")
            return flashName
        }

        String first = names[0]
        if (preferred && preferred != first) warnings.add("Gemini model ${preferred} unavailable; using ${first}.")
        return first
    } catch (Exception ignored) {
        return preferred
    }
}

String promptText = normalize(prompt)
if (!promptText) {
    throw new IllegalArgumentException("prompt is required")
}

String tenantToUse = normalize(tenantId) ?: "DEFAULT"
String ruleIdToUse = normalize(ruleId)

Map<String, Map> providerConfigs = [
        OPENAI: [
                label      : "OpenAI",
                remoteId   : "OPENAI_RULE_WORKSPACE",
                defaultModel: "gpt-4.1-mini",
                defaultBaseUrl: "https://api.openai.com",
                keyEnv     : "OPENAI_API_KEY",
                modelEnv   : "OPENAI_MODEL",
                baseUrlEnv : "OPENAI_API_BASE_URL",
                timeoutEnv : "OPENAI_TIMEOUT_SECONDS",
                keyProp    : "darpan.openai.apiKey",
                modelProp  : "darpan.openai.model",
                baseUrlProp: "darpan.openai.baseUrl",
                timeoutProp: "darpan.openai.timeoutSeconds"
        ],
        GEMINI: [
                label      : "Gemini",
                remoteId   : "GEMINI_RULE_WORKSPACE",
                defaultModel: "gemini-2.0-flash",
                defaultBaseUrl: "https://generativelanguage.googleapis.com",
                keyEnv     : "GEMINI_API_KEY",
                modelEnv   : "GEMINI_MODEL",
                baseUrlEnv : "GEMINI_API_BASE_URL",
                timeoutEnv : "GEMINI_TIMEOUT_SECONDS",
                keyProp    : "darpan.gemini.apiKey",
                modelProp  : "darpan.gemini.model",
                baseUrlProp: "darpan.gemini.baseUrl",
                timeoutProp: "darpan.gemini.timeoutSeconds"
        ]
]

String activeProvider = normalize(
        ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", "RULE_WORKSPACE_LLM_ACTIVE")
                .useCache(false)
                .one()?.username
)?.toUpperCase()
if (!providerConfigs.containsKey(activeProvider)) activeProvider = "OPENAI"

Map providerConfig = providerConfigs[activeProvider]
def llmSettings = ec.entity.find("moqui.service.message.SystemMessageRemote")
        .condition("systemMessageRemoteId", providerConfig.remoteId)
        .useCache(false)
        .one()

String providerLabel = providerConfig.label
String llmEnabled = (normalize(llmSettings?.remoteAttributes) ?: "Y").toUpperCase()
String model = normalize(llmSettings?.username) ?: normalize(System.getenv(providerConfig.modelEnv) ?: ec.resource.properties[providerConfig.modelProp]) ?: providerConfig.defaultModel
String baseUrl = normalize(llmSettings?.sendUrl) ?: normalize(System.getenv(providerConfig.baseUrlEnv) ?: ec.resource.properties[providerConfig.baseUrlProp]) ?: providerConfig.defaultBaseUrl
baseUrl = baseUrl.replaceAll('/+$', '')
String apiKey = normalize(llmSettings?.password) ?: normalize(System.getenv(providerConfig.keyEnv) ?: ec.resource.properties[providerConfig.keyProp])
String timeoutSecondsRaw = normalize(llmSettings?.internalAppCode) ?: normalize(System.getenv(providerConfig.timeoutEnv) ?: ec.resource.properties[providerConfig.timeoutProp])
int timeoutSeconds = timeoutSecondsRaw?.isInteger() ? Math.max((timeoutSecondsRaw as Integer), 10) : 45

String ruleContextText = normalize(ruleContext)
Object parsedRuleContext = null
List<String> warningList = []

if (ruleContextText) {
    try {
        parsedRuleContext = new JsonSlurper().parseText(ruleContextText)
    } catch (Exception ignored) {
        warningList.add("ruleContext is not valid JSON; ignored for LLM generation.")
    }
}

if (llmEnabled == "N") {
    warningList.add("${providerLabel} generation is disabled in Settings -> LLM; returned template DRL.")
    drlText = buildFallbackDrl(promptText)
    explanation = "Template DRL returned because LLM generation is disabled."
    warnings = warningList
    return
}

if (!apiKey) {
    warningList.add("${providerLabel} API key is not configured in Settings -> LLM (or ${providerConfig.keyEnv} env); returned template DRL.")
    drlText = buildFallbackDrl(promptText)
    explanation = "Template DRL returned because LLM configuration is missing."
    warnings = warningList
    return
}

String systemPrompt = '''
You convert natural-language rule requirements into Drools DRL.
Return only JSON with fields:
- drlText: string
- explanation: string
- warnings: array of strings

Rules for drlText:
1) Must be valid DRL with package darpan.rule.
2) Include import java.util.Map.
3) Facts are maps matched as $m : Map(...) and fields accessed as this["fieldName"].
4) Include deterministic when conditions and concrete then actions.
5) Do not include markdown fences or text outside JSON.
6) If input is ambiguous, still generate best-effort DRL and mention ambiguity in warnings.
'''.trim()

String contextJsonForPrompt = parsedRuleContext != null ? JsonOutput.prettyPrint(JsonOutput.toJson(parsedRuleContext)) : "{}"
String userPrompt = """
Tenant: ${tenantToUse}
Rule Id: ${ruleIdToUse ?: "NEW"}
Provider: ${providerLabel}

Natural language requirement:
${promptText}

Context JSON:
${contextJsonForPrompt}
""".trim()

try {
    logger.info("Generating DRL via provider={} tenant={} ruleId={} model={} promptChars={} hasContext={}",
            activeProvider, tenantToUse, ruleIdToUse ?: "NEW", model, promptText.length(), parsedRuleContext != null)

    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build()

    String llmContent = null

    if (activeProvider == "OPENAI") {
        Map requestBody = [
                model          : model,
                temperature    : 0.1,
                response_format: [type: "json_object"],
                messages       : [
                        [role: "system", content: systemPrompt],
                        [role: "user", content: userPrompt]
                ]
        ]

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer ${apiKey}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(requestBody)))
                .build()

        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() > 299) {
            String errorSummary = errorSummaryFromBody(httpResponse.body())
            warningList.add("${providerLabel} request failed with HTTP ${httpResponse.statusCode()}${errorSummary ? ' (' + errorSummary + ')' : ''}; returned template DRL.")
            logger.warn("LLM DRL generation failed provider={} status={} tenant={} model={}", activeProvider, httpResponse.statusCode(), tenantToUse, model)
            drlText = buildFallbackDrl(promptText)
            explanation = "Template DRL returned because LLM request failed."
            warnings = warningList
            return
        }

        Map responseMap = (Map) new JsonSlurper().parseText(httpResponse.body() ?: "{}")
        Object firstChoice = (responseMap?.choices instanceof List && responseMap.choices) ? responseMap.choices[0] : null
        llmContent = normalize(firstChoice?.message?.content)

        if (!llmContent && firstChoice?.message?.content instanceof List) {
            llmContent = firstChoice.message.content
                    .collect { part -> part?.text ?: part?.toString() }
                    .findAll { part -> part }
                    .join("\n")
        }
    } else if (activeProvider == "GEMINI") {
        String resolvedGeminiModel = resolveGeminiModel(httpClient, baseUrl, apiKey, model, timeoutSeconds, warningList)
        String modelForEndpoint = normalizeGeminiModelName(resolvedGeminiModel) ?: "gemini-2.0-flash"

        Map requestBody = [
                contents        : [[parts: [[text: "${systemPrompt}\n\n${userPrompt}"]]]],
                generationConfig: [temperature: 0.1, responseMimeType: "application/json"]
        ]

        String endpoint = "${baseUrl}/v1beta/models/${URLEncoder.encode(modelForEndpoint, 'UTF-8')}:generateContent?key=${URLEncoder.encode(apiKey, 'UTF-8')}"
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(requestBody)))
                .build()

        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() > 299) {
            String errorSummary = errorSummaryFromBody(httpResponse.body())
            warningList.add("${providerLabel} request failed with HTTP ${httpResponse.statusCode()}${errorSummary ? ' (' + errorSummary + ')' : ''}; returned template DRL.")
            logger.warn("LLM DRL generation failed provider={} status={} tenant={} model={}", activeProvider, httpResponse.statusCode(), tenantToUse, model)
            drlText = buildFallbackDrl(promptText)
            explanation = "Template DRL returned because LLM request failed."
            warnings = warningList
            return
        }

        Map responseMap = (Map) new JsonSlurper().parseText(httpResponse.body() ?: "{}")
        String blockReason = normalize(responseMap?.promptFeedback?.blockReason)
        if (blockReason) warningList.add("${providerLabel} prompt feedback: ${blockReason}")

        def firstCandidate = (responseMap?.candidates instanceof List && responseMap.candidates) ? responseMap.candidates[0] : null
        if (firstCandidate?.content?.parts instanceof List) {
            llmContent = firstCandidate.content.parts
                    .collect { part -> part?.text ?: part?.toString() }
                    .findAll { part -> part }
                    .join("\n")
        }
    } else {
        warningList.add("Unsupported LLM provider ${activeProvider}; returned template DRL.")
        drlText = buildFallbackDrl(promptText)
        explanation = "Template DRL returned because the selected provider is unsupported."
        warnings = warningList
        return
    }

    llmContent = normalize(llmContent)
    if (!llmContent) {
        warningList.add("${providerLabel} response was empty; returned template DRL.")
        drlText = buildFallbackDrl(promptText)
        explanation = "Template DRL returned because LLM response was empty."
        warnings = warningList
        return
    }

    Map generatedMap = (Map) new JsonSlurper().parseText(llmContent)
    String generatedDrl = normalize(generatedMap?.drlText)
    String generatedExplanation = normalize(generatedMap?.explanation) ?: "Generated from natural-language prompt."

    if (generatedMap?.warnings instanceof Collection) {
        generatedMap.warnings.each { warningValue ->
            String warningText = normalize(warningValue)
            if (warningText) warningList.add(warningText)
        }
    }

    if (!generatedDrl) {
        warningList.add("LLM did not return drlText; returned template DRL.")
        drlText = buildFallbackDrl(promptText)
        explanation = "Template DRL returned because LLM output was missing DRL text."
        warnings = warningList
        return
    }

    generatedDrl = ensureDrlHeader(generatedDrl)
    String drlLower = generatedDrl.toLowerCase()
    if (!(drlLower.contains("rule ") && drlLower.contains("when") && drlLower.contains("then") && drlLower.contains("end"))) {
        warningList.add("Generated DRL was incomplete; returned template DRL.")
        drlText = buildFallbackDrl(promptText)
        explanation = "Template DRL returned because generated DRL failed structural checks."
        warnings = warningList
        return
    }

    drlText = generatedDrl
    explanation = generatedExplanation
    warnings = warningList
} catch (Throwable t) {
    warningList.add("LLM generation error (${t.class.simpleName}); returned template DRL.")
    logger.warn("LLM DRL generation error provider={} tenant={} model={} message={}", activeProvider, tenantToUse, model, t.toString())
    drlText = buildFallbackDrl(promptText)
    explanation = "Template DRL returned because LLM generation failed."
    warnings = warningList
}
