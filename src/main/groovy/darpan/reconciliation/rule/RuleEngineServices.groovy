package reconciliation.rule

import groovy.json.JsonSlurper
import groovy.transform.Field
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap

import static reconciliation.rule.RuleConditionParser.parseCondition

@Field final def logger = LoggerFactory.getLogger("darpan.reconciliation.rule.RuleEngineServices")
@Field final Map<String, Map> RULESET_CACHE = new ConcurrentHashMap<>()

String normalize(Object value) { value?.toString()?.trim() }

boolean normalizeBool(Object value, boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}

Long toLong(Object value) {
    if (value == null) return null
    if (value instanceof Number) return ((Number) value).longValue()
    String raw = normalize(value)
    if (!raw) return null
    try {
        return raw.toBigDecimal().longValue()
    } catch (Exception ignored) {
        return null
    }
}

String sanitizeRuleName(String raw) {
    String cleaned = normalize(raw)?.replaceAll(/[^A-Za-z0-9 _.-]/, "_")
    return cleaned ?: "Unnamed Rule"
}

String sanitizePackagePart(String raw) {
    String cleaned = normalize(raw)?.toLowerCase()?.replaceAll(/[^a-z0-9_]/, "_")?.replaceAll(/_+/, "_")
    if (!cleaned) cleaned = "default_ruleset"
    if (!(cleaned[0] ==~ /[a-z_]/)) cleaned = "rs_${cleaned}"
    return cleaned
}

String sanitizeIdToken(String raw, String fallbackPrefix) {
    String cleaned = normalize(raw)?.toUpperCase()?.replaceAll(/[^A-Z0-9_]/, "_")
    cleaned = cleaned?.replaceAll(/_+/, "_")?.replaceAll(/^_+|_+$/, "")
    if (!cleaned) cleaned = fallbackPrefix
    if (!(cleaned[0] ==~ /[A-Z]/)) cleaned = "${fallbackPrefix}_${cleaned}"
    if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60)
    return cleaned
}

String validateProvidedId(String providedId, String label) {
    String normalized = normalize(providedId)
    if (!normalized) return null
    if (!(normalized ==~ /[A-Za-z0-9_.:-]+/)) {
        throw new IllegalArgumentException("${label} contains invalid characters: ${normalized}")
    }
    return normalized
}

String normalizeEnabled(Object value) {
    return normalizeBool(value, true) ? "Y" : "N"
}

String resolveTenantToken(ExecutionContext ec) {
    String tenantId = normalize(ec?.context?.tenantId)
    return sanitizeIdToken(tenantId ?: "DEFAULT", "TENANT")
}

String buildCacheKey(ExecutionContext ec, String ruleSetId) {
    String tenantId = resolveTenantToken(ec)
    return "${tenantId}::${ruleSetId}"
}

void invalidateRuleSetCache(ExecutionContext ec, String ruleSetId) {
    if (ruleSetId) {
        RULESET_CACHE.remove(buildCacheKey(ec, ruleSetId))
        return
    }
    RULESET_CACHE.clear()
}

List fetchActiveRules(ExecutionContext ec, String ruleSetId) {
    return (ec.entity.find("darpan.rule.Rule")
            .condition("ruleSetId", ruleSetId)
            .condition("enabled", "Y")
            .orderBy(["sequenceNum", "ruleId"])
            .useCache(false)
            .list() ?: []) as List
}

long resolveNextSequence(ExecutionContext ec, String ruleSetId) {
    List rules = (ec.entity.find("darpan.rule.Rule")
            .condition("ruleSetId", ruleSetId)
            .orderBy(["-sequenceNum"])
            .limit(1)
            .useCache(false)
            .list() ?: []) as List
    long maxSeq = (rules && rules[0]?.sequenceNum != null) ? ((rules[0].sequenceNum as Number).longValue()) : 0L
    return maxSeq + 10L
}

String makeRuleSignature(EntityValue ruleSet, List rules) {
    String ruleSetStamp = [
            normalize(ruleSet?.ruleSetId),
            normalize(ruleSet?.ruleSetName),
            normalize(ruleSet?.version),
            normalize(ruleSet?.lastUpdatedDate)
    ].join("|")
    String ruleStamp = rules.collect { rule ->
        [
                normalize(rule.ruleId),
                normalize(rule.sequenceNum),
                normalize(rule.enabled),
                normalize(rule.ruleText),
                normalize(rule.ruleLogic),
                normalize(rule.lastUpdatedDate)
        ].join("|")
    }.join("||")
    return "${ruleSetStamp}@@${ruleStamp}"
}

String stripDrlHeaders(String drlText) {
    if (!drlText) return ""
    return drlText.readLines().findAll { String line ->
        String trimmed = line?.trim()?.toLowerCase()
        return !(trimmed?.startsWith("package ") || trimmed?.startsWith("import ") || trimmed?.startsWith("global "))
    }.join("\n").trim()
}

String buildRuleBlock(String ruleName, String condition, int salience) {
    return """
rule "${sanitizeRuleName(ruleName)}"
    salience ${salience}
when
    \$m : Map(${condition})
then
    List _matchedRuleIds = (List) \$m.get("_matchedRuleIds");
    if (_matchedRuleIds == null) {
        _matchedRuleIds = new ArrayList();
        \$m.put("_matchedRuleIds", _matchedRuleIds);
    }
    if (!_matchedRuleIds.contains("${sanitizeRuleName(ruleName)}")) _matchedRuleIds.add("${sanitizeRuleName(ruleName)}");
    if (!results.contains(\$m)) results.add(\$m);
end
"""
}

String buildRuleSetDrl(String tenantId, String ruleSetId, List rules, List<String> warnings) {
    String packageName = "reconciliation.rules.${sanitizePackagePart(tenantId)}.${sanitizePackagePart(ruleSetId)}"
    StringBuilder drl = new StringBuilder()
    drl.append("package ${packageName}\n")
    drl.append("import java.util.Map\n")
    drl.append("import java.util.List\n")
    drl.append("import java.util.ArrayList\n")
    drl.append("global java.util.List results\n\n")

    int generatedRules = 0
    rules.eachWithIndex { rule, idx ->
        String ruleName = normalize(rule.ruleId) ?: "RULE_${idx + 1}"
        String ruleLogic = normalize(rule.ruleLogic)
        String ruleText = normalize(rule.ruleText)
        int salience = 10000 - idx

        if (ruleLogic) {
            String trimmed = ruleLogic.trim()
            if (trimmed.toLowerCase().startsWith("rule ") || trimmed.contains("\nrule ")) {
                String normalizedBlock = stripDrlHeaders(trimmed)
                if (normalizedBlock && normalizedBlock.toLowerCase().contains("rule ")) {
                    drl.append(normalizedBlock).append("\n\n")
                    generatedRules++
                } else {
                    warnings.add("Rule ${ruleName} DRL block had no executable rule after header cleanup.")
                }
            } else {
                drl.append(buildRuleBlock(ruleName, trimmed, salience)).append("\n")
                generatedRules++
            }
            return
        }

        if (!ruleText) {
            warnings.add("Rule ${ruleName} has no ruleLogic or ruleText and was skipped.")
            return
        }

        String parsed = parseCondition(ruleText)
        if (!parsed) {
            warnings.add("Rule ${ruleName} ruleText could not be parsed and was skipped: ${ruleText}")
            return
        }
        drl.append(buildRuleBlock(ruleName, parsed, salience)).append("\n")
        generatedRules++
    }

    if (generatedRules == 0) {
        throw new IllegalArgumentException("RuleSet ${ruleSetId} has no executable active rules")
    }
    return drl.toString()
}

Map getOrBuildContainer(ExecutionContext ec, String ruleSetId, boolean useCache = true, boolean forceRebuild = false) {
    if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")

    EntityValue ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()
    if (!ruleSet) throw new IllegalArgumentException("RuleSet ${ruleSetId} not found")

    List rules = fetchActiveRules(ec, ruleSetId)
    if (!rules) throw new IllegalArgumentException("RuleSet ${ruleSetId} has no active rules")

    String tenantId = resolveTenantToken(ec)
    String signature = makeRuleSignature(ruleSet, rules)
    String cacheKey = buildCacheKey(ec, ruleSetId)

    Map cached = RULESET_CACHE[cacheKey]
    if (!forceRebuild && useCache && cached != null && signature == cached.signature && cached.container != null) {
        return [
                container: cached.container,
                drlText  : cached.drlText,
                ruleCount: cached.ruleCount,
                warnings : cached.warnings ?: []
        ]
    }

    List<String> warnings = []
    String drlText = buildRuleSetDrl(tenantId, ruleSetId, rules, warnings)

    KieServices ks = KieServices.Factory.get()
    KieFileSystem kfs = ks.newKieFileSystem()
    String drlPath = "src/main/resources/${sanitizePackagePart(tenantId)}_${sanitizePackagePart(ruleSetId)}.drl"
    kfs.write(drlPath, drlText)

    KieBuilder kb = ks.newKieBuilder(kfs)
    kb.buildAll()

    if (kb.getResults().hasMessages(Message.Level.ERROR)) {
        List<String> errors = (kb.getResults().getMessages(Message.Level.ERROR) ?: [])
                .collect { Message m -> m?.text ?: m?.toString() }
                .findAll { it }
        String errSummary = errors ? errors.join(" | ") : kb.getResults().toString()
        throw new IllegalArgumentException("Drools build errors for ${ruleSetId}: ${errSummary}")
    }

    KieContainer container = ks.newKieContainer(kb.getKieModule().getReleaseId())
    Map entry = [
            signature: signature,
            drlText  : drlText,
            ruleCount: rules.size(),
            warnings : warnings,
            container: container
    ]
    RULESET_CACHE[cacheKey] = entry

    return [
            container: container,
            drlText  : drlText,
            ruleCount: rules.size(),
            warnings : warnings
    ]
}

def compileRuleSet() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleSetId = normalize(context.ruleSetId)
    boolean useCache = normalizeBool(context.useCache, false)
    boolean forceRebuild = normalizeBool(context.forceRebuild, true)

    logger.info("Compiling ruleSet={} useCache={} forceRebuild={}", ruleSetId, useCache, forceRebuild)
    try {
        Map compiled = getOrBuildContainer(ec, ruleSetId, useCache, forceRebuild)
        List warnings = (compiled.warnings ?: []) as List
        if (warnings) {
            ec.message.addMessage("RuleSet ${ruleSetId} compiled with ${warnings.size()} warning(s).")
        } else {
            ec.message.addMessage("RuleSet ${ruleSetId} compiled successfully.")
        }
        return [
                kieContainer: compiled.container,
                ruleCount   : compiled.ruleCount,
                drlText     : compiled.drlText,
                warnings    : warnings,
                error       : null
        ]
    } catch (Exception e) {
        logger.error("Failed compiling ruleSet={}", ruleSetId, e)
        return [error: e.message]
    }
}

def executeRuleSet() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleSetId = normalize(context.ruleSetId)
    List dataList = (context.dataList instanceof List) ? (List) context.dataList : []
    boolean returnAllFacts = normalizeBool(context.returnAllFacts, false)

    if (!dataList) {
        return [
                results       : [],
                matchedResults: [],
                firedRuleCount: 0,
                ruleCount     : 0,
                warnings      : [],
                error         : null
        ]
    }

    try {
        Map compiled = getOrBuildContainer(ec, ruleSetId, true, false)
        KieContainer container = compiled.container as KieContainer
        List warnings = ((compiled.warnings ?: []) as List).collect { it?.toString() }

        List<Map> facts = dataList.collect { Object row ->
            if (row instanceof Map) return new LinkedHashMap((Map) row)
            return [value: row]
        }

        List matchedResults = []
        KieSession session = container.newKieSession()
        try {
            session.setGlobal("results", matchedResults)
            facts.each { Map fact -> session.insert(fact) }
            int fired = session.fireAllRules()
            List output = returnAllFacts ? facts : matchedResults

            logger.info("Executed ruleSet={} facts={} fired={} returnAllFacts={}", ruleSetId, facts.size(), fired, returnAllFacts)
            return [
                    results       : output,
                    matchedResults: matchedResults,
                    firedRuleCount: fired,
                    ruleCount     : compiled.ruleCount,
                    warnings      : warnings,
                    error         : null
            ]
        } finally {
            try { session?.dispose() } catch (Exception ignored) {}
        }
    } catch (Exception e) {
        logger.error("Rule execution failed for ruleSet={}", ruleSetId, e)
        return [error: "Rule execution failed: ${e.message}"]
    }
}

def executeRules() {
    return executeRuleSet()
}

def executeRuleSetJson() {
    String jsonData = normalize(context.jsonData)
    if (!jsonData) {
        context.dataList = []
        context.returnAllFacts = normalizeBool(context.returnAllFacts, false)
        return executeRuleSet()
    }

    try {
        Object parsed = new JsonSlurper().parseText(jsonData)
        List<Map> dataList = []
        if (parsed instanceof List) {
            dataList = (parsed as List).collect { Object row ->
                (row instanceof Map) ? new LinkedHashMap((Map) row) : [value: row]
            }
        } else if (parsed instanceof Map) {
            dataList.add(new LinkedHashMap((Map) parsed))
        } else {
            dataList.add([value: parsed])
        }
        context.dataList = dataList
        context.returnAllFacts = normalizeBool(context.returnAllFacts, false)
        return executeRuleSet()
    } catch (Exception e) {
        return [error: "Invalid JSON Data: ${e.message}"]
    }
}

String generateRuleSetId(ExecutionContext ec, String requestedId, String ruleSetName) {
    String provided = validateProvidedId(requestedId, "ruleSetId")
    if (provided) return provided

    String base = sanitizeIdToken(ruleSetName ?: "RULE_SET", "RS")
    if (!base.startsWith("RS_")) base = "RS_${base}"
    String candidate = base

    int suffix = 1
    while (ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", candidate).useCache(false).one() != null) {
        String suffixText = "_${suffix}"
        int maxBaseLen = Math.max(1, 60 - suffixText.length())
        String shortBase = base.length() > maxBaseLen ? base.substring(0, maxBaseLen) : base
        candidate = shortBase + suffixText
        suffix++
    }
    return candidate
}

String generateRuleId(ExecutionContext ec, String requestedId, String ruleSetId, long sequenceNum) {
    String provided = validateProvidedId(requestedId, "ruleId")
    if (provided) return provided

    String base = sanitizeIdToken("${ruleSetId}_R_${sequenceNum}", "RULE")

    String candidate = base
    int suffix = 1
    while (ec.entity.find("darpan.rule.Rule").condition("ruleId", candidate).useCache(false).one() != null) {
        String suffixText = "_${suffix}"
        int maxBaseLen = Math.max(1, 60 - suffixText.length())
        String shortBase = base.length() > maxBaseLen ? base.substring(0, maxBaseLen) : base
        candidate = shortBase + suffixText
        suffix++
    }
    return candidate
}

def saveRuleSet() {
    ExecutionContext ec = context.ec as ExecutionContext
    String requestedRuleSetId = normalize(context.ruleSetId)
    String ruleSetName = normalize(context.ruleSetName)
    String description = normalize(context.description)
    String version = normalize(context.version) ?: "1.0"
    String explosionPath = normalize(context.explosionPath)
    String primaryKeyPath = normalize(context.primaryKeyPath)
    Timestamp nowTs = new Timestamp(System.currentTimeMillis())

    String ruleSetId = generateRuleSetId(ec, requestedRuleSetId, ruleSetName)
    EntityValue existing = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()

    if (existing == null) {
        ec.entity.makeValue("darpan.rule.RuleSet")
                .set("ruleSetId", ruleSetId)
                .set("ruleSetName", ruleSetName ?: ruleSetId)
                .set("description", description)
                .set("version", version)
                .set("explosionPath", explosionPath)
                .set("primaryKeyPath", primaryKeyPath)
                .set("createdDate", nowTs)
                .set("lastUpdatedDate", nowTs)
                .create()
        ec.message.addMessage("Created RuleSet ${ruleSetId}.")
        invalidateRuleSetCache(ec, ruleSetId)
        return [ruleSetId: ruleSetId, created: true, updated: false]
    }

    existing.set("ruleSetName", ruleSetName ?: normalize(existing.ruleSetName) ?: ruleSetId)
    existing.set("description", description)
    existing.set("version", version)
    existing.set("explosionPath", explosionPath)
    existing.set("primaryKeyPath", primaryKeyPath)
    existing.set("lastUpdatedDate", nowTs)
    existing.update()

    ec.message.addMessage("Updated RuleSet ${ruleSetId}.")
    invalidateRuleSetCache(ec, ruleSetId)
    return [ruleSetId: ruleSetId, created: false, updated: true]
}

def saveRule() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleSetId = validateProvidedId(context.ruleSetId, "ruleSetId")
    if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")

    EntityValue ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()
    if (!ruleSet) throw new IllegalArgumentException("RuleSet ${ruleSetId} not found")

    String requestedRuleId = normalize(context.ruleId)
    String ruleText = normalize(context.ruleText)
    String ruleLogic = normalize(context.ruleLogic)
    String ruleType = normalize(context.ruleType)
    String expression = normalize(context.expression)
    String severity = normalize(context.severity)
    String enabled = normalizeEnabled(context.enabled)
    boolean validateOnSave = normalizeBool(context.validateOnSave, false)
    Long requestedSeq = toLong(context.sequenceNum)

    if (!ruleLogic && !ruleText) throw new IllegalArgumentException("Either ruleLogic or ruleText is required")
    if (!ruleLogic && ruleText && !parseCondition(ruleText)) {
        throw new IllegalArgumentException("ruleText could not be parsed. Use explicit operator syntax (for example: status is 'Pending').")
    }

    EntityValue existingRule = requestedRuleId ? ec.entity.find("darpan.rule.Rule")
            .condition("ruleId", validateProvidedId(requestedRuleId, "ruleId"))
            .useCache(false)
            .one() : null

    if (existingRule != null && normalize(existingRule.ruleSetId) != ruleSetId) {
        throw new IllegalArgumentException("Rule ${requestedRuleId} belongs to RuleSet ${existingRule.ruleSetId}; moving between RuleSets is not supported.")
    }

    long sequenceNum = requestedSeq != null ? requestedSeq : (existingRule ? (toLong(existingRule.sequenceNum) ?: 10L) : resolveNextSequence(ec, ruleSetId))
    String ruleId = generateRuleId(ec, requestedRuleId, ruleSetId, sequenceNum)
    Timestamp nowTs = new Timestamp(System.currentTimeMillis())

    if (existingRule == null) {
        ec.entity.makeValue("darpan.rule.Rule")
                .set("ruleId", ruleId)
                .set("ruleSetId", ruleSetId)
                .set("sequenceNum", sequenceNum)
                .set("ruleText", ruleText)
                .set("ruleLogic", ruleLogic)
                .set("ruleType", ruleType)
                .set("expression", expression)
                .set("severity", severity)
                .set("enabled", enabled)
                .set("createdDate", nowTs)
                .set("lastUpdatedDate", nowTs)
                .create()
    } else {
        existingRule.set("sequenceNum", sequenceNum)
        existingRule.set("ruleText", ruleText)
        existingRule.set("ruleLogic", ruleLogic)
        existingRule.set("ruleType", ruleType)
        existingRule.set("expression", expression)
        existingRule.set("severity", severity)
        existingRule.set("enabled", enabled)
        existingRule.set("lastUpdatedDate", nowTs)
        existingRule.update()
    }

    invalidateRuleSetCache(ec, ruleSetId)
    List warnings = []
    if (validateOnSave && "Y".equals(enabled)) {
        Map compiled = getOrBuildContainer(ec, ruleSetId, false, true)
        warnings = (compiled.warnings ?: []) as List
    }

    ec.message.addMessage("${existingRule == null ? 'Created' : 'Updated'} Rule ${ruleId}.")
    return [ruleId: ruleId, ruleSetId: ruleSetId, sequenceNum: sequenceNum, warnings: warnings]
}

def deleteRule() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleId = validateProvidedId(context.ruleId, "ruleId")
    if (!ruleId) throw new IllegalArgumentException("ruleId is required")

    EntityValue existingRule = ec.entity.find("darpan.rule.Rule")
            .condition("ruleId", ruleId)
            .useCache(false)
            .one()
    if (!existingRule) throw new IllegalArgumentException("Rule ${ruleId} not found")

    String ruleSetId = normalize(existingRule.ruleSetId)
    existingRule.delete()
    invalidateRuleSetCache(ec, ruleSetId)
    ec.message.addMessage("Deleted Rule ${ruleId}.")
    return [ruleId: ruleId, ruleSetId: ruleSetId]
}

def deleteRuleSet() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleSetId = validateProvidedId(context.ruleSetId, "ruleSetId")
    boolean forceDeleteRules = normalizeBool(context.forceDeleteRules, false)
    if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")

    EntityValue ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()
    if (!ruleSet) throw new IllegalArgumentException("RuleSet ${ruleSetId} not found")

    List rules = (ec.entity.find("darpan.rule.Rule")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .list() ?: []) as List

    if (rules && !forceDeleteRules) {
        throw new IllegalArgumentException("RuleSet ${ruleSetId} has ${rules.size()} rule(s). Delete rules first or set forceDeleteRules=true.")
    }

    rules.each { EntityValue ruleVal -> ruleVal.delete() }
    ruleSet.delete()
    invalidateRuleSetCache(ec, ruleSetId)
    ec.message.addMessage("Deleted RuleSet ${ruleSetId}${rules ? ' with ' + rules.size() + ' rule(s)' : ''}.")
    return [ruleSetId: ruleSetId, deletedRuleCount: rules.size()]
}

def clearRuleSetCache() {
    ExecutionContext ec = context.ec as ExecutionContext
    String ruleSetId = normalize(context.ruleSetId)
    if (ruleSetId) {
        invalidateRuleSetCache(ec, ruleSetId)
        ec.message.addMessage("Cleared cache for RuleSet ${ruleSetId}.")
        return [ruleSetId: ruleSetId, cacheCleared: true]
    }
    invalidateRuleSetCache(ec, null)
    ec.message.addMessage("Cleared Rule Engine cache for all RuleSets.")
    return [cacheCleared: true]
}
