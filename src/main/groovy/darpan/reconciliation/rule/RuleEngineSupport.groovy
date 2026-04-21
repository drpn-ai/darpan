package reconciliation.rule

import java.util.concurrent.ConcurrentHashMap

class RuleEngineSupport {
    static final Map<String, Map> RULESET_CACHE = new ConcurrentHashMap<>()

    static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    static Map<String, Object> validateProvidedId(String providedId, String label) {
        String normalized = normalize(providedId)
        if (!normalized) return [value: null, error: null]
        if (!(normalized ==~ /[A-Za-z0-9_.:-]+/)) {
            return [value: null, error: "${label} contains invalid characters: ${normalized}"]
        }
        return [value: normalized, error: null]
    }

    static String sanitizeIdToken(String raw, String fallbackPrefix) {
        String cleaned = normalize(raw)?.toUpperCase()?.replaceAll(/[^A-Z0-9_]/, "_")
        cleaned = cleaned?.replaceAll(/_+/, "_")?.replaceAll(/^_+|_+$/, "")
        if (!cleaned) cleaned = fallbackPrefix
        if (!(cleaned[0] ==~ /[A-Z]/)) cleaned = "${fallbackPrefix}_${cleaned}"
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60)
        return cleaned
    }

    static String resolveTenantToken(def ec) {
        String tenantId = normalize(ec?.context?.tenantId)
        return sanitizeIdToken(tenantId ?: "DEFAULT", "TENANT")
    }

    static String buildCacheKey(def ec, String ruleSetId) {
        return "${resolveTenantToken(ec)}::${ruleSetId}"
    }

    static boolean invalidateRuleSetCache(def ec, String ruleSetId) {
        String normalizedRuleSetId = normalize(ruleSetId)
        if (normalizedRuleSetId) {
            RULESET_CACHE.remove(buildCacheKey(ec, normalizedRuleSetId))
            return true
        }
        RULESET_CACHE.clear()
        return true
    }

    static Map<String, Object> resolveRuleSetId(def ec, String requestedId, String ruleSetName) {
        Map<String, Object> provided = validateProvidedId(requestedId, "ruleSetId")
        if (provided.error) return [ruleSetId: null, error: provided.error]
        if (provided.value) return [ruleSetId: provided.value, error: null]

        String base = sanitizeIdToken(ruleSetName ?: "RULE_SET", "RS")
        if (!base.startsWith("RS_")) base = "RS_${base}"
        String candidate = base
        int suffix = 1

        while (ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", candidate).disableAuthz().useCache(false).one() != null) {
            String suffixText = "_${suffix}"
            int maxBaseLen = Math.max(1, 60 - suffixText.length())
            String shortBase = base.length() > maxBaseLen ? base.substring(0, maxBaseLen) : base
            candidate = shortBase + suffixText
            suffix++
        }

        return [ruleSetId: candidate, error: null]
    }
}
