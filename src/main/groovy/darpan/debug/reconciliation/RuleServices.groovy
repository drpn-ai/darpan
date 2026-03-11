package debug.reconciliation

import groovy.transform.Field
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.moqui.context.ExecutionContext
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

import static debug.reconciliation.RuleConditionParser.parseCondition

@Field final def logger = LoggerFactory.getLogger("debug.reconciliation.RuleServices")
@Field final Map<String, Map> RULESET_CACHE = new ConcurrentHashMap<>()

def normalize(Object value) { value?.toString()?.trim() }

def normalizeBool(Object value, boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}

def sanitizeRuleName(String raw) {
    String cleaned = normalize(raw)?.replaceAll(/[^A-Za-z0-9 _.-]/, "_")
    return cleaned ?: "Unnamed Rule"
}

def sanitizePackagePart(String raw) {
    String cleaned = normalize(raw)?.toLowerCase()?.replaceAll(/[^a-z0-9_]/, "_")?.replaceAll(/_+/, "_")
    if (!cleaned) cleaned = "default_ruleset"
    if (!(cleaned[0] ==~ /[a-z_]/)) cleaned = "rs_${cleaned}"
    return cleaned
}

def fetchActiveRules(ExecutionContext ec, String ruleSetId) {
    return (ec.entity.find("darpan.rule.Rule")
            .condition("ruleSetId", ruleSetId)
            .condition("enabled", "Y")
            .orderBy(["sequenceNum", "ruleId"])
            .useCache(false)
            .list() ?: []) as List
}

def makeRuleSignature(List rules) {
    return rules.collect { rule ->
        [
                normalize(rule.ruleId),
                normalize(rule.sequenceNum),
                normalize(rule.enabled),
                normalize(rule.ruleText),
                normalize(rule.ruleLogic),
                normalize(rule.lastUpdatedDate)
        ].join("|")
    }.join("||")
}

def buildRuleBlockFromCondition(Map rule, String parsedCondition, int salience) {
    String ruleId = sanitizeRuleName(normalize(rule.ruleId) ?: "rule_${salience}")
    return """
rule "${ruleId}"
    salience ${salience}
when
    \$m : Map(${parsedCondition})
then
    List _matchedRuleIds = (List) \$m.get("_matchedRuleIds");
    if (_matchedRuleIds == null) {
        _matchedRuleIds = new ArrayList();
        \$m.put("_matchedRuleIds", _matchedRuleIds);
    }
    if (!_matchedRuleIds.contains("${ruleId}")) _matchedRuleIds.add("${ruleId}");
    if (!results.contains(\$m)) results.add(\$m);
end
"""
}

def stripDrlHeaders(String drlText) {
    if (!drlText) return ""
    return drlText.readLines().findAll { String line ->
        String trimmed = line?.trim()?.toLowerCase()
        return !(trimmed?.startsWith("package ") || trimmed?.startsWith("import ") || trimmed?.startsWith("global "))
    }.join("\n").trim()
}

def buildRuleSetDrl(String ruleSetId, List rules, List<String> warnings) {
    String packageName = "reconciliation.rules.${sanitizePackagePart(ruleSetId)}"
    StringBuilder drl = new StringBuilder()
    drl.append("package ${packageName}\n")
    drl.append("import java.util.Map\n")
    drl.append("import java.util.List\n")
    drl.append("import java.util.ArrayList\n")
    drl.append("global java.util.List results\n\n")

    int generatedRules = 0
    rules.eachWithIndex { rule, idx ->
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
                    warnings.add("Rule ${normalize(rule.ruleId) ?: '(no id)'} DRL block had no executable rule after header cleanup.")
                }
            } else {
                String ruleId = sanitizeRuleName(normalize(rule.ruleId) ?: "rule_${idx + 1}")
                drl.append("""
rule "${ruleId}"
    salience ${salience}
when
    \$m : Map(${trimmed})
then
    if (!results.contains(\$m)) results.add(\$m);
end

""")
                generatedRules++
            }
            return
        }

        if (!ruleText) {
            warnings.add("Rule ${normalize(rule.ruleId) ?: '(no id)'} has no ruleLogic or ruleText and was skipped.")
            return
        }

        String parsed = parseCondition(ruleText)
        if (!parsed) {
            warnings.add("Rule ${normalize(rule.ruleId) ?: '(no id)'} ruleText could not be parsed and was skipped: ${ruleText}")
            return
        }
        drl.append(buildRuleBlockFromCondition((Map) rule, parsed, salience)).append("\n")
        generatedRules++
    }

    if (generatedRules == 0) {
        throw new IllegalArgumentException("RuleSet ${ruleSetId} has no executable active rules")
    }

    return drl.toString()
}

def getOrBuildContainer(ExecutionContext ec, String ruleSetId) {
    if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")

    def ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()
    if (!ruleSet) throw new IllegalArgumentException("RuleSet ${ruleSetId} not found")

    List rules = fetchActiveRules(ec, ruleSetId)
    if (!rules) throw new IllegalArgumentException("RuleSet ${ruleSetId} has no active rules")

    String signature = makeRuleSignature(rules)
    Map cached = RULESET_CACHE[ruleSetId]
    if (cached != null && signature == cached.signature && cached.container != null) {
        return [
                container: cached.container,
                drlText  : cached.drlText,
                ruleCount: cached.ruleCount,
                warnings : []
        ]
    }

    List<String> warnings = []
    String drlText = buildRuleSetDrl(ruleSetId, rules, warnings)

    KieServices ks = KieServices.Factory.get()
    KieFileSystem kfs = ks.newKieFileSystem()
    kfs.write("src/main/resources/${sanitizePackagePart(ruleSetId)}.drl", drlText)

    KieBuilder kb = ks.newKieBuilder(kfs)
    kb.buildAll()
    if (kb.getResults().hasMessages(Message.Level.ERROR)) {
        throw new IllegalArgumentException("Drools build errors for ${ruleSetId}: ${kb.getResults()}")
    }

    KieContainer container = ks.newKieContainer(kb.getKieModule().getReleaseId())
    RULESET_CACHE[ruleSetId] = [
            signature: signature,
            drlText  : drlText,
            ruleCount: rules.size(),
            container: container
    ]

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
    logger.info("Compiling ruleSet={}", ruleSetId)
    try {
        Map compiled = getOrBuildContainer(ec, ruleSetId)
        return [
                kieContainer: compiled.container,
                ruleCount   : compiled.ruleCount,
                drlText     : compiled.drlText,
                warnings    : compiled.warnings,
                error       : null
        ]
    } catch (Exception e) {
        logger.error("Failed compiling ruleSet={}", ruleSetId, e)
        return [error: e.message]
    }
}

def executeRules() {
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
                warnings      : []
        ]
    }

    try {
        Map compiled = getOrBuildContainer(ec, ruleSetId)
        KieContainer container = compiled.container as KieContainer
        List warnings = ((compiled.warnings ?: []) as List).collect { it?.toString() }

        List<Map> facts = dataList.collect { Object row ->
            if (row instanceof Map) return (Map) row
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

def executeRuleSetJson() {
    String jsonData = normalize(context.jsonData)
    if (!jsonData) {
        context.dataList = []
        context.returnAllFacts = normalizeBool(context.returnAllFacts, false)
        return executeRules()
    }

    try {
        Object parsed = new groovy.json.JsonSlurper().parseText(jsonData)
        List<Map> dataList = []
        if (parsed instanceof List) {
            dataList = (parsed as List).collect { Object row ->
                (row instanceof Map) ? (Map) row : [value: row]
            }
        } else if (parsed instanceof Map) {
            dataList.add(parsed as Map)
        } else {
            dataList.add([value: parsed])
        }
        context.dataList = dataList
        context.returnAllFacts = normalizeBool(context.returnAllFacts, false)
        return executeRules()
    } catch (Exception e) {
        return [error: "Invalid JSON Data: ${e.message}"]
    }
}
