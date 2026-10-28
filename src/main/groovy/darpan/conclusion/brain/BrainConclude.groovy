package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.Order

/**
 * DAR-BE-151. One compare scope's brain pass at the conclude seam. Findings are grouped into worlds by order (so one
 * order is one fact) and fired in batches; the asks of every batch go to the ask planner once; what it fetches is
 * stored and the asking worlds are fired again. A finding the brain concludes gets {code, label, checks, path}; the
 * rest are left to the tree. A failed question is a warning and is not remembered.
 */
class BrainConclude {

    static final List<String> PILOT_PACKS = ["oms-netsuite"]
    static final int BATCH_FINDINGS = 500

    /** args: kind, tenantId, findings ([key, bucket, sides] each), fetchQuestion(question, ids) -> id -> record, labelFor(code). */
    static Map run(Map args) {
        String kind = (String) args.kind
        String tenantId = (String) args.tenantId
        Closure fetchQuestion = (Closure) args.fetchQuestion
        Closure labelFor = (Closure) (args.labelFor ?: { String c -> c })
        BrainEngine engine = BrainEngine.forPacks((List<String>) (args.packs ?: PILOT_PACKS))
        List<List<Map>> batches = batches(kind, (List<Map>) (args.findings ?: []))
        Map<String, Map> answers = [:]
        List<String> warnings = []

        List<Map> round = batches.collect { fire(engine, kind, it, answers) }
        List<Ask> asks = (List<Ask>) round.collectMany { (List) it.asks }
        Map<Object, Cause> best = [:]
        round.each { best.putAll((Map) it.bestCauses) }
        Map plan = BrainAskPlanner.plan(asks, best, tenantId, BrainQuestions.mayEnableRanks(engine))
        ((Map<String, Map>) plan.answered).each { String q, Map byId -> if (q == BrainQuestions.ORDER_PAYMENTS) answers.putAll(byId) }
        int asked = 0
        ((Map<String, List<String>>) plan.toFetch).each { String question, List<String> ids ->
            try {
                Map found = (Map) (fetchQuestion.call(question, ids) ?: [:])
                ids.each { String id ->
                    Map answer = (Map) found.get(id)
                    BrainAnswerStore.put(tenantId, question, id, answer)
                    if (question == BrainQuestions.ORDER_PAYMENTS) answers.put(id, answer)
                }
                asked += ids.size()
            } catch (Throwable t) {
                warnings.add("Question ${question} not answered for ${ids.size()} subject(s): ${t.message ?: t.class.simpleName}".toString())
            }
        }
        if (answers) {
            round = batches.withIndex().collect { List<Map> batch, int i ->
                batch.any { answers.containsKey(orderIdOf(kind, it)) } ? fire(engine, kind, batch, answers) : round[i]
            }
        }
        Map<String, Map> byKey = [:]
        round.each { Map fired ->
            ((List<Conclusion>) fired.conclusions).each { Conclusion c -> byKey.put(c.finding.key, conclusion(engine, c, labelFor)) }
        }
        return [byKey: byKey, asked: asked, reused: plan.reused, dropped: plan.dropped, warnings: warnings]
    }

    /** Findings of one order stay in one batch, so its world is whole. */
    private static List<List<Map>> batches(String kind, List<Map> findings) {
        List<List<Map>> out = []
        List<Map> current = []
        findings.groupBy { orderIdOf(kind, it) ?: "\u0000${it.key}" }.values().each { List<Map> group ->
            if (current && current.size() + group.size() > BATCH_FINDINGS) { out.add(current); current = [] }
            current.addAll(group)
        }
        if (current) out.add(current)
        return out
    }

    private static Map fire(BrainEngine engine, String kind, List<Map> batch, Map<String, Map> answers) {
        Map<String, Order> world = [:]
        return engine.fire(batch.collectMany { BrainFactAdapter.facts(kind, it, answers, world) } as List<Object>)
    }

    private static String orderIdOf(String kind, Map finding) {
        Map record = (Map) (((Map) (((Map) (finding?.sides ?: [:])).FILE_1 ?: [:])).record ?: [:])
        Object id = kind == "orderLine" ? record.omsOrderId : record.orderId
        return id?.toString()?.trim() ?: null
    }

    /** The existing conclusion shape; path is the because chain, the cause first. */
    private static Map conclusion(BrainEngine engine, Conclusion c, Closure labelFor) {
        String label = labelFor.call(c.cause.code)?.toString() ?: c.cause.code
        List<Map> checks = c.cause.because.collect { Object fact ->
            Map<String, String> meta = engine.factMetadata(fact) ?: [label: fact.getClass().simpleName, value: null]
            [label: meta.label, value: fill(meta.value, fact), passed: true]
        }
        return [code: c.cause.code, label: label, checks: checks, question: null,
                path: [[code: c.cause.code, label: label, checks: checks, expected: c.cause.expected, rank: c.cause.rank]]]
    }

    /** "{taken} of {order.total}" against the fact's fields. */
    static String fill(String template, Object fact) {
        if (!template) return null
        return template.replaceAll(/\{([A-Za-z0-9_.]+)\}/) { List m ->
            Object v = fact
            for (String seg : (m[1] as String).split("\\.")) { v = v == null ? null : v."${seg}" }
            v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros().toPlainString() : (v?.toString() ?: "")
        }
    }
}
