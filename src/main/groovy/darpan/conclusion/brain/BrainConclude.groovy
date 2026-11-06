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
    /** Ids per question call, like the tree's lookups (RunConclusionSupport.LOOKUP_BATCH); tunable for tests. */
    static int fetchBatch = 500
    /** DAR-BE-192: question rounds per pass (a replacement's return, then its RMA and memo). */
    static final int MAX_ROUNDS = 3

    /** args: kind, tenantId, findings ([key, bucket, sides] each), fetchQuestion(question, ids) -> id -> record, labelFor(code). */
    static Map run(Map args) {
        String kind = (String) args.kind
        String tenantId = (String) args.tenantId
        Closure fetchQuestion = (Closure) args.fetchQuestion
        Closure labelFor = (Closure) (args.labelFor ?: { String c -> c })
        BrainEngine engine = BrainEngine.forPacks((List<String>) (args.packs ?: PILOT_PACKS))
        List<List<Map>> batches = batches(kind, (List<Map>) (args.findings ?: []))
        Map<String, Map> answers = [:]
        // DAR-BE-186: productNetsuiteId answers, by product id.
        Map<String, Map> productAnswers = [:]
        // DAR-BE-192: replacementReturn (by order id), returnRma and returnMemo (by return id).
        // DAR-BE-151: originalInNetsuite (an exchange's original, by its HotWax order id).
        Map<String, Map<String, Map>> more = [(BrainQuestions.REPLACEMENT_RETURN): [:], (BrainQuestions.RETURN_RMA): [:],
                                              (BrainQuestions.RETURN_MEMO): [:], (BrainQuestions.ORIGINAL_IN_NETSUITE): [:]] as Map<String, Map<String, Map>>
        List<String> warnings = []

        List<Map> round = batches.collect { fire(engine, kind, it, answers, productAnswers, more) }
        int asked = 0, reused = 0, dropped = 0
        Map<String, Integer> ranks = BrainQuestions.mayEnableRanks(engine)
        // A question that failed for a subject is not asked again in a later round of the same pass.
        Set<String> failed = [] as Set
        // DAR-BE-192: an answer can raise a further question (a replacement's return, then its RMA), so questions are asked
        // in rounds until a round has nothing new to ask; MAX_ROUNDS bounds it. A world is fired again only when it gained one.
        for (int r = 0; r < MAX_ROUNDS; r++) {
            List<Ask> asks = (List<Ask>) round.collectMany { (List) it.asks }
            Map<Object, Cause> best = [:]
            round.each { best.putAll((Map) it.bestCauses) }
            Map plan = BrainAskPlanner.plan(asks, best, tenantId, ranks)
            reused += (plan.reused ?: 0) as int
            dropped += (plan.dropped ?: 0) as int
            Set<String> gained = [] as Set
            ((Map<String, Map>) plan.answered).each { String q, Map byId ->
                Map<String, Map> store = answersFor(q, answers, productAnswers, more)
                if (store == null) return
                byId.each { k, v -> if (!store.containsKey(k)) { store.put((String) k, (Map) v); gained.add("${q}|${k}".toString()) } }
            }
            ((Map<String, List<String>>) plan.toFetch).each { String question, List<String> planned ->
                List<String> allIds = planned.findAll { !failed.contains("${question}|${it}".toString()) }
                // Bounded calls; a failed batch fails alone and is not remembered.
                allIds.collate(fetchBatch).each { List<String> ids ->
                    try {
                        Map found = (Map) (fetchQuestion.call(question, ids) ?: [:])
                        ids.each { String id ->
                            Map answer = (Map) found.get(id)
                            BrainAnswerStore.put(tenantId, question, id, answer)
                            answersFor(question, answers, productAnswers, more)?.put(id, answer)
                            gained.add("${question}|${id}".toString())
                        }
                        asked += ids.size()
                    } catch (Throwable t) {
                        ids.each { failed.add("${question}|${it}".toString()) }
                        warnings.add("Question ${question} not answered for ${ids.size()} subject(s): ${t.message ?: t.class.simpleName}".toString())
                    }
                }
            }
            if (!gained) break
            round = batches.withIndex().collect { List<Map> batch, int i ->
                (answers || productAnswers || more.values().any { it }) && touches(kind, batch, answers, productAnswers, more) ?
                        fire(engine, kind, batch, answers, productAnswers, more) : round[i]
            }
        }
        Map<String, Map> byKey = [:]
        round.each { Map fired ->
            ((List<Conclusion>) fired.conclusions).each { Conclusion c -> byKey.put(c.finding.key, conclusion(engine, c, labelFor)) }
        }
        return [byKey: byKey, asked: asked, reused: reused, dropped: dropped, warnings: warnings]
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

    private static Map fire(BrainEngine engine, String kind, List<Map> batch, Map<String, Map> answers, Map<String, Map> productAnswers,
                            Map<String, Map<String, Map>> more) {
        Map<String, Object> world = [:]
        return engine.fire(batch.collectMany { BrainFactAdapter.facts(kind, it, answers, world, productAnswers, more) } as List<Object>)
    }

    /** Where a question's answers are kept; null for a question this pass does not know. */
    private static Map<String, Map> answersFor(String question, Map<String, Map> answers, Map<String, Map> productAnswers,
                                               Map<String, Map<String, Map>> more) {
        if (question == BrainQuestions.ORDER_PAYMENTS) return answers
        if (question == BrainQuestions.PRODUCT_NETSUITE_ID) return productAnswers
        return more?.get(question)
    }

    /** A batch is fired again when any of its orders, products or replacements' returns has an answer. */
    private static boolean touches(String kind, List<Map> batch, Map<String, Map> answers, Map<String, Map> productAnswers,
                                   Map<String, Map<String, Map>> more) {
        Map<String, Map> byReplacement = more?.get(BrainQuestions.REPLACEMENT_RETURN) ?: [:]
        return batch.any { Map f ->
            String o = orderIdOf(kind, f)
            answers.containsKey(o) || productAnswers.containsKey(productIdOf(kind, f)) || byReplacement.containsKey(o)
        }
    }

    /** DAR-BE-186: an R1 line's product id. */
    private static String productIdOf(String kind, Map finding) {
        if (kind != "orderLine") return null
        Map record = (Map) (((Map) (((Map) (finding?.sides ?: [:])).FILE_1 ?: [:])).record ?: [:])
        return record.productId?.toString()?.trim() ?: null
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
