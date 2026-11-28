package darpan.judge

import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-318 plan 2, Task 7 (D9). Judges a check's pair facts with its two moqui-rules decisions (spec §5, §6):
 *  1. Must hold (firstMatchOnly N): every outcome of kind JOK_FINDING it gives for a pair is one failed promise.
 *  2. Explains (firstMatchOnly Y): run on the same fact plus claim = the promise's outcome id. The first match by priority
 *     is the cause; nothing matching is the decision's UNEXPLAINED outcome. A JOK_HIDDEN cause drops the finding.
 * One finding per failed promise per pair, in record#CheckFindings' shape, naming the rule that found the promise and the
 * rule that explained it.
 *
 * Runs on the request thread as the tenant user with authz ON (rulings P2-7, P2-9): every read is the tenant's, so another
 * tenant's check or decision reads as "does not exist". It writes nothing; the caller records the findings. Groovy because
 * it batches facts across service calls; every entity read is in judge.JudgeConfigServices.get#CheckJudgeConfig.
 */
class JudgeSupport {
    private static final Logger logger = LoggerFactory.getLogger(JudgeSupport.class)

    static final String UNEXPLAINED = "UNEXPLAINED"
    static final String FINDING = "JOK_FINDING"
    static final String HIDDEN = "JOK_HIDDEN"
    static final String CLAIM_FIELD = "claim"

    /**
     * factBatches: lists of facts as PairFactSupport.buildFact returns them ([factTypeId, recordId: compareId, fact]).
     * statementsByClaim: "mustHoldRuleSetId|outcomeId" to AuditStatement.statementCode (from claimRuleSetId/claimOutcomeId).
     * warnings: when given, gets one line per promise skipped for having no statement, and per outcome with no meaning
     * (DecisionOutcome), each with its pair count, and (OC30) one line counting the pairs with a side missing (SET_ASIDE or
     * ABSENT) that broke no promise while the Must hold decision reads no presence field of that side: moqui-rules reads two
     * missing fields as equal, so such a decision cannot see that side's missing record, and it must never pass as silently
     * clean. The same lines are logged at WARN.
     *
     * Each finding: statementCode, recordKey (the compare id), headline (the promise's label), conclusionEnumId (null),
     * diagnosisText (the cause's label), claimRuleSetId, claimRuleId, claimOutcomeId, causeRuleSetId, causeRuleId (null when
     * the cause is UNEXPLAINED by no match), causeOutcomeId, outcomeKindEnumId (the cause's kind: JOK_FINDING or JOK_EXPECTED),
     * fixText, fixOwner and fixWhereEnumId (the cause's).
     *
     * Findings are in compare id order, and within a pair in the Must hold decision's outcome order, whatever order the pairs
     * arrive in (the matched frame comes back from Spark's repartition in no stable order), so judging the same pairs twice gives
     * the same list. Warnings are in key order for the same reason.
     *
     * Throws IllegalStateException, naming the check or decision, when judging cannot start or a decision call fails.
     */
    static List<Map> judge(def ec, String checkId, Iterator<List<Map>> factBatches, Map statementsByClaim, List<String> warnings = null) {
        if (ec.artifactExecution.authzDisabled) {
            throw new IllegalStateException("Judging check ${checkId} needs authz on: the judge runs as the tenant user".toString())
        }
        Map config = call(ec, "judge.JudgeConfigServices.get#CheckJudgeConfig", [checkId: checkId], "Judging check ${checkId}")
        String mustHold = config.mustHoldRuleSetId as String
        String explains = config.explainsRuleSetId as String
        Map decisions = (config.decisions ?: [:]) as Map
        Map outcomes = (config.outcomes ?: [:]) as Map
        refuseWrongShape(checkId, mustHold, explains, decisions)
        Set<String> readFields = new HashSet<String>((((decisions.get(mustHold) as Map).readFields ?: []) as List).collect { it as String })
        int missingUnjudged = 0

        Map<String, Integer> unlinked = new TreeMap<>()
        Map<String, Integer> unmeant = new TreeMap<>()
        List<Map> findings = []
        while (factBatches.hasNext()) {
            List<Map> batch = factBatches.next()
            if (!batch) continue
            List<Map> promiseAnswers = decide(ec, mustHold, "Must hold", batch)

            List<Map> failed = []
            batch.eachWithIndex { Map fact, int index ->
                Map answer = promiseAnswers[index]
                boolean brokePromise = false
                for (Object outcome in (answer.outcomes ?: [])) {
                    String outcomeId = outcome as String
                    String key = mustHold + "|" + outcomeId
                    Map meaning = outcomes.get(key) as Map
                    if (meaning == null) { count(unmeant, key); continue }
                    if (meaning.outcomeKindEnumId != FINDING) continue
                    brokePromise = true
                    String statementCode = statementsByClaim?.get(key) as String
                    if (!statementCode) { count(unlinked, key); continue }
                    Map claimRule = (answer.matchedRules as List<Map>)?.find { it.outcomeId == outcomeId }
                    failed.add([fact: fact, outcomeId: outcomeId, meaning: meaning, statementCode: statementCode,
                                claimRuleId: claimRule?.ruleId])
                }
                if (!brokePromise && unreadMissingSide((Map) fact.fact, readFields)) missingUnjudged++
            }
            if (!failed) continue

            List<Map> claimFacts = failed.collect { Map promise ->
                Map fact = new LinkedHashMap((promise.fact as Map).fact as Map)
                fact.put(CLAIM_FIELD, promise.outcomeId)
                [factTypeId: (promise.fact as Map).factTypeId, recordId: "${(promise.fact as Map).recordId}|${promise.outcomeId}".toString(), fact: fact]
            }
            List<Map> causeAnswers = decide(ec, explains, "Explains", claimFacts)
            failed.eachWithIndex { Map promise, int index ->
                Map answer = causeAnswers[index]
                List answered = (answer.outcomes ?: []) as List
                String causeId = answered ? answered[0] as String : UNEXPLAINED
                Map causeRule = answered ? (answer.matchedRules as List<Map>)?.find { it.outcomeId == causeId } : null
                String causeKey = explains + "|" + causeId
                Map cause = outcomes.get(causeKey) as Map
                if (cause == null) count(unmeant, causeKey)
                // A cause with no meaning is shown, never hidden: a missing row must not drop a finding silently.
                String kind = (cause?.outcomeKindEnumId ?: FINDING) as String
                if (kind == HIDDEN) return
                Map meaning = promise.meaning as Map
                findings.add([statementCode: promise.statementCode, recordKey: (promise.fact as Map).recordId as String,
                              headline: meaning.label, conclusionEnumId: null, diagnosisText: cause?.label ?: causeId,
                              claimRuleSetId: mustHold, claimRuleId: promise.claimRuleId, claimOutcomeId: promise.outcomeId,
                              causeRuleSetId: explains, causeRuleId: causeRule?.ruleId, causeOutcomeId: causeId,
                              outcomeKindEnumId: kind, fixText: cause?.fixText, fixOwner: cause?.fixOwner,
                              fixWhereEnumId: cause?.fixWhereEnumId])
            }
        }

        List<String> lines = []
        if (missingUnjudged) {
            lines.add(("Must hold decision ${mustHold} of check ${checkId} does not read the presence of a missing side: " +
                    "${missingUnjudged} ${missingUnjudged == 1 ? 'pair' : 'pairs'} with that side missing (ABSENT or SET_ASIDE) " +
                    "broke no promise, so a missing record reads as clean").toString())
        }
        unlinked.each { String key, Integer n ->
            lines.add("Promise ${key} of check ${checkId} has no statement: ${n} findings skipped".toString()) }
        unmeant.each { String key, Integer n ->
            String reading = key.startsWith(mustHold + "|") ? "so it is no promise: ${n} pairs" : "so it is shown as a finding: ${n} findings"
            lines.add("Outcome ${key} of check ${checkId} has no meaning (DecisionOutcome), ${reading}".toString()) }
        lines.each { logger.warn(it) }
        if (warnings != null) warnings.addAll(lines)
        findings.sort(true, BY_COMPARE_ID)   // stable: a pair's findings keep their outcome order
        return findings
    }

    private static final String PRESENCE_SUFFIX = "_presence"

    /**
     * True when a side of the pair fact is SET_ASIDE or ABSENT and the decision reads no line on that side's presence field
     * (PairFactSupport.buildFact names it <prefix>_presence; a side key named presence is renamed, so the name is the side's).
     */
    private static boolean unreadMissingSide(Map fact, Set<String> readFields) {
        return (fact ?: [:]).any { Object k, Object v ->
            String field = k as String
            field.endsWith(PRESENCE_SUFFIX) && (v == PairFactSupport.ABSENT || v == PairFactSupport.SET_ASIDE) && !readFields.contains(field)
        }
    }

    private static final Comparator<Map> BY_COMPARE_ID = { Map a, Map b -> (a.recordKey as String) <=> (b.recordKey as String) } as Comparator<Map>

    /** Ruling P2-17 and the brief: judging refuses a decision of the wrong shape before it runs one. */
    private static void refuseWrongShape(String checkId, String mustHold, String explains, Map decisions) {
        if (!mustHold) throw new IllegalStateException("Check ${checkId} has no Must hold decision".toString())
        if (!explains) throw new IllegalStateException("Check ${checkId} has no Explains decision".toString())
        Map mustHoldShape = decisions.get(mustHold) as Map
        if (mustHoldShape == null) throw new IllegalStateException("Must hold decision ${mustHold} of check ${checkId} does not exist".toString())
        Map explainsShape = decisions.get(explains) as Map
        if (explainsShape == null) throw new IllegalStateException("Explains decision ${explains} of check ${checkId} does not exist".toString())
        if (mustHoldShape.firstMatchOnly == "Y") {
            throw new IllegalStateException(("Must hold decision ${mustHold} of check ${checkId} has firstMatchOnly Y: every failed " +
                    "promise must surface, so it must be N").toString())
        }
        if (explainsShape.firstMatchOnly != "Y") {
            throw new IllegalStateException(("Explains decision ${explains} of check ${checkId} must have firstMatchOnly Y: the first " +
                    "cause by priority wins").toString())
        }
        if (!((explainsShape.outcomeIds ?: []) as List).contains(UNEXPLAINED)) {
            throw new IllegalStateException("Explains decision ${explains} of check ${checkId} does not list the outcome ${UNEXPLAINED}".toString())
        }
    }

    /** One evaluate#RuleSet call; one answer per fact, in input order, or a loud error naming the decision. */
    private static List<Map> decide(def ec, String ruleSetId, String stage, List<Map> facts) {
        Map out = call(ec, "moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: ruleSetId, facts: facts],
                "${stage} decision ${ruleSetId}")
        List<Map> answers = (out.decisions ?: []) as List<Map>
        if (answers.size() != facts.size()) {
            throw new IllegalStateException(("${stage} decision ${ruleSetId} gave ${answers.size()} answers for ${facts.size()} pairs: " +
                    "it must read the pair fact type alone").toString())
        }
        facts.eachWithIndex { Map fact, int index ->
            if (answers[index].recordId as String != fact.recordId as String) {
                throw new IllegalStateException("${stage} decision ${ruleSetId} answered out of order at pair ${fact.recordId}".toString())
            }
        }
        return answers
    }

    /**
     * One service call that fails loudly, naming what it was for. An error already in ec.message stops it before it runs (a
     * Moqui service call would skip silently and return null) and is named as earlier, never blamed on this call.
     */
    @PackageScope
    static Map call(def ec, String serviceName, Map params, String what) {
        if (ec.message.hasError()) throw new IllegalStateException("${what}: stopped by an earlier error: ${ec.message.errorsString}".toString())
        Map out = ec.service.sync().name(serviceName).parameters(params).call() as Map
        if (ec.message.hasError()) throw new IllegalStateException("${what}: ${ec.message.errorsString}".toString())
        return out ?: [:]
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.put(key, (counts.get(key) ?: 0) + 1)
    }
}
