package reconciliation.rule

import darpan.common.DarpanEntityConstants
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.sanitizeDisplayName
import static reconciliation.rule.RuleConditionParser.parseCondition

class RuleEngineSupport {
    private static final int MAX_ID_LENGTH = 60
    private static final Logger logger = LoggerFactory.getLogger(RuleEngineSupport.class)
    private static final Set<String> JAVA_KEYWORDS = [
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while"
    ] as Set<String>

    static final ConcurrentMap<String, Map<String, Object>> RULESET_CACHE = new ConcurrentHashMap<>()

    // The five imports and one global that buildRuleSetDrl always emits in the DRL header.
    // Used to distinguish standard declarations (stripped from raw blocks) from custom ones (hoisted to header).
    private static final Set<String> STANDARD_DRL_IMPORTS = [
            "import java.util.map",
            "import java.util.list",
            "import java.util.arraylist",
            "import java.util.objects",
            "import reconciliation.rule.rulediffsupport"
    ] as Set<String>

    static String sanitizeIdToken(String raw, String fallbackPrefix) {
        String fallback = normalize(fallbackPrefix)?.toUpperCase(Locale.ROOT)?.replaceAll(/[^A-Z0-9_]/, "_") ?: "ID"
        String cleaned = normalize(raw)?.toUpperCase(Locale.ROOT)?.replaceAll(/[^A-Z0-9_]/, "_")
        cleaned = cleaned?.replaceAll(/_+/, "_")?.replaceAll(/^_+|_+$/, "")
        if (!cleaned) cleaned = fallback
        if (!(cleaned[0] ==~ /[A-Z]/)) cleaned = "${fallback}_${cleaned}"
        if (cleaned.length() > MAX_ID_LENGTH) cleaned = cleaned.substring(0, MAX_ID_LENGTH)
        return cleaned
    }

    static boolean invalidateRuleSetCache(def ec, String ruleSetId) {
        String normalizedRuleSetId = normalize(ruleSetId)
        if (normalizedRuleSetId) {
            RULESET_CACHE.remove(buildCacheKey(resolveTenantToken(ec), normalizedRuleSetId))
            return true
        }
        logger.warn("invalidateRuleSetCache called with no ruleSetId — clearing entire ruleset cache ({} entries across all tenants)", RULESET_CACHE.size())
        RULESET_CACHE.clear()
        return true
    }

    static Map<String, Object> compileRuleSet(def ec, String ruleSetId, boolean useCache, boolean forceRebuild) {
        logger.info("Compiling ruleSet={} useCache={} forceRebuild={}", ruleSetId, useCache, forceRebuild)
        try {
            Map compiled = getOrBuildContainer(ec, ruleSetId, useCache, forceRebuild)
            List warnings = (compiled.warnings ?: []) as List
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

    static Map<String, Object> executeRuleSetFacts(def ec, String ruleSetId, List rows, boolean returnAllFacts) {
        try {
            Map compiled = getOrBuildContainer(ec, ruleSetId, true, false)
            KieContainer container = compiled.container as KieContainer
            List warnings = ((compiled.warnings ?: []) as List).collect { it?.toString() }

            List<Map> facts = rows.collect { Object row ->
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
                disposeQuietly(session)
            }
        } catch (Exception e) {
            logger.error("Rule execution failed for ruleSet={}", ruleSetId, e)
            return [error: "Rule execution failed: ${e.message}"]
        }
    }

    static Map<String, Object> executeMatchedPairFacts(def ec, String ruleSetId, List rows) {
        try {
            Map compiled = getOrBuildContainer(ec, ruleSetId, true, false)
            KieContainer container = compiled.container as KieContainer
            List<String> warnings = ((compiled.warnings ?: []) as List).collect { it?.toString() }.findAll { it }

            List<Map> facts = rows.collect { Object row ->
                (row instanceof Map) ? new LinkedHashMap((Map) row) : [value: row]
            }

            List matchedResults = []
            KieSession session = container.newKieSession()
            try {
                session.setGlobal("results", matchedResults)
                facts.each { Map fact -> session.insert(fact) }
                int fired = session.fireAllRules()

                List<Map> diffResults = []
                facts.each { Map fact ->
                    List<Map> normalizedDiffs = RuleDiffSupport.extractNormalizedDiffs(fact)
                    if (normalizedDiffs) {
                        diffResults.addAll(normalizedDiffs)
                        return
                    }

                    List matchedRuleIds = (fact.get("_matchedRuleIds") instanceof List) ? (List) fact.get("_matchedRuleIds") : []
                    if (matchedRuleIds) {
                        String compareScopeId = normalize(fact.compareScopeId)
                        String primaryId = normalize(fact.primaryId)
                        warnings.add("RuleSet ${ruleSetId} matched compareScope=${compareScopeId ?: '(unknown)'} primaryId=${primaryId ?: '(unknown)'} but emitted no diff rows.")
                    }
                }

                logger.info("Executed matched-pair rules ruleSet={} facts={} fired={} diffRows={}",
                        ruleSetId, facts.size(), fired, diffResults.size())
                return [
                        diffResults   : diffResults,
                        matchedResults: matchedResults,
                        firedRuleCount: fired,
                        ruleCount     : compiled.ruleCount,
                        warnings      : warnings.unique(),
                        error         : null
                ]
            } finally {
                disposeQuietly(session)
            }
        } catch (Exception e) {
            logger.error("Matched-pair rule execution failed for ruleSet={}", ruleSetId, e)
            return [error: "Matched-pair rule execution failed: ${e.message}"]
        }
    }

    private static Map<String, Object> getOrBuildContainer(def ec, String ruleSetId, boolean useCache = true, boolean forceRebuild = false) {
        if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")

        EntityValue ruleSet = ec.entity.find(DarpanEntityConstants.RULE_SET)
                .condition("ruleSetId", ruleSetId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (!ruleSet) throw new IllegalArgumentException("RuleSet ${ruleSetId} not found")

        List rules = fetchActiveRules(ec, ruleSetId)
        if (!rules) throw new IllegalArgumentException("RuleSet ${ruleSetId} has no active rules")

        String tenantId = resolveTenantToken(ec)
        String signature = makeRuleSignature(ruleSet, rules)
        String cacheKey = buildCacheKey(tenantId, ruleSetId)

        Map cached = RULESET_CACHE[cacheKey]
        if (!forceRebuild && useCache && cached != null && signature == cached.signature && cached.container != null) {
            return [
                    container: cached.container,
                    drlText  : cached.drlText,
                    ruleCount: cached.ruleCount,
                    warnings : new ArrayList((List) (cached.warnings ?: []))
            ]
        }

        List<String> warnings = []
        String sanitizedRuleSetId = sanitizePackagePart(ruleSetId)
        String packageName = "reconciliation.rules.${sanitizePackagePart(tenantId)}.${sanitizedRuleSetId}"
        String drlText = buildRuleSetDrl(ruleSetId, packageName, rules, warnings)

        KieServices ks = KieServices.Factory.get()
        KieFileSystem kfs = ks.newKieFileSystem()
        String drlPath = "src/main/resources/${packageName.replace('.', '/')}/${sanitizedRuleSetId}.drl"
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
                warnings : new ArrayList(warnings),
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

    private static List fetchActiveRules(def ec, String ruleSetId) {
        return (ec.entity.find("darpan.rule.Rule")
                .condition("ruleSetId", ruleSetId)
                .condition("enabled", "Y")
                .orderBy(["sequenceNum", "ruleId"])
                .disableAuthz()
                .useCache(false)
                .list() ?: []) as List
    }

    private static String makeRuleSignature(EntityValue ruleSet, List rules) {
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
        // Hash to a fixed-width token so cache equality checks are O(1) regardless of
        // ruleset size, and so signature storage in RULESET_CACHE doesn't grow with rule content.
        return sha256("${ruleSetStamp}@@${ruleStamp}")
    }

    private static String sha256(String raw) {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((raw ?: "").getBytes("UTF-8"))
        return hash.encodeHex().toString()
    }

    private static String buildRuleSetDrl(String ruleSetId, String packageName, List rules, List<String> warnings) {
        // Hoist any custom imports declared in raw DRL blocks into the shared file header.
        // Drools requires all imports to appear before the first rule definition, so they cannot
        // remain inside the rule blocks themselves.
        Set<String> customImports = collectCustomDrlImports(rules)

        StringBuilder drl = new StringBuilder()
        drl.append("package ${packageName}\n")
        drl.append("import java.util.Map\n")
        drl.append("import java.util.List\n")
        drl.append("import java.util.ArrayList\n")
        drl.append("import java.util.Objects\n")
        drl.append("import reconciliation.rule.RuleDiffSupport\n")
        customImports.each { String imp -> drl.append("${imp}\n") }
        drl.append("global java.util.List results\n\n")

        int generatedRules = 0
        boolean hasRawBlocks = false
        boolean hasParsedRules = false

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
                        hasRawBlocks = true
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
            hasParsedRules = true
        }

        if (hasRawBlocks && hasParsedRules) {
            warnings.add("RuleSet ${ruleSetId} mixes raw DRL blocks (author-defined salience) and parsed ruleText rules (sequence-based salience). Verify that execution ordering across both rule types is intentional.")
        }

        if (generatedRules == 0) {
            throw new IllegalArgumentException("RuleSet ${ruleSetId} has no executable active rules")
        }
        return drl.toString()
    }

    // Scans ruleLogic blocks for import declarations that are not part of the standard DRL
    // header emitted by buildRuleSetDrl, and returns them for hoisting into the file header.
    private static Set<String> collectCustomDrlImports(List rules) {
        Set<String> customImports = new LinkedHashSet<>()
        rules.each { rule ->
            String ruleLogic = normalize(rule.ruleLogic)
            if (!ruleLogic) return
            String lower = ruleLogic.toLowerCase()
            if (!(lower.startsWith("rule ") || lower.contains("\nrule "))) return
            ruleLogic.readLines().each { String line ->
                String lineLower = line?.trim()?.toLowerCase() ?: ""
                if (lineLower.startsWith("import ") && !STANDARD_DRL_IMPORTS.contains(lineLower)) {
                    customImports.add(line.trim())
                }
            }
        }
        return customImports
    }

    private static String buildRuleBlock(String ruleName, String condition, int salience) {
        String safeRuleName = sanitizeRuleName(ruleName)
        return """
rule "${safeRuleName}"
    salience ${salience}
when
    \$m : Map(${condition})
then
    List _matchedRuleIds = (List) \$m.get("_matchedRuleIds");
    if (_matchedRuleIds == null) {
        _matchedRuleIds = new ArrayList();
        \$m.put("_matchedRuleIds", _matchedRuleIds);
    }
    if (!_matchedRuleIds.contains("${safeRuleName}")) _matchedRuleIds.add("${safeRuleName}");
    if (!results.contains(\$m)) results.add(\$m);
end
"""
    }

    private static String stripDrlHeaders(String drlText) {
        if (!drlText) return ""
        return drlText.readLines().findAll { String line ->
            String trimmed = line?.trim()?.toLowerCase()
            return !(trimmed?.startsWith("package ") || trimmed?.startsWith("import ") || trimmed?.startsWith("global "))
        }.join("\n").trim()
    }

    private static String sanitizeRuleName(String raw) {
        return sanitizeDisplayName(raw, "Unnamed Rule")
    }

    private static String sanitizePackagePart(String raw) {
        String cleaned = normalize(raw)?.toLowerCase()?.replaceAll(/[^a-z0-9_]/, "_")?.replaceAll(/_+/, "_")
        if (!cleaned) cleaned = "default_ruleset"
        if (!(cleaned[0] ==~ /[a-z_]/)) cleaned = "rs_${cleaned}"
        if (JAVA_KEYWORDS.contains(cleaned)) cleaned = "rs_${cleaned}"
        return cleaned
    }

    private static String resolveTenantToken(def ec) {
        String tenantId = normalize(ec?.context?.tenantId)
        return sanitizeIdToken(tenantId ?: "DEFAULT", "TENANT")
    }

    private static String buildCacheKey(String tenantId, String ruleSetId) {
        return "${tenantId}::${normalize(ruleSetId)}"
    }

    private static void disposeQuietly(KieSession session) {
        try {
            session?.dispose()
        } catch (Exception ignored) {
            // Best effort cleanup; the service response should preserve the original rule result.
        }
    }
}
