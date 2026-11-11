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
    /**
     * DAR-BE-151: the finding buckets the packs' statements read (BrainPackGuardTests holds the pack to it). Only these rows
     * are handed to the brain, so a run of another bucket (prod gorjana R1, Nov 1-9: 24,635 billed lines HotWax's read
     * does not cover) cannot use up its cap before it reaches its own rows.
     */
    static final Set<String> BUCKETS = (["MISSING_FROM_FILE_2"] as Set<String>).asImmutable()
    static final int BATCH_FINDINGS = 500
    /** Test seam: ids per question call when above 0; otherwise each question's catalogue batch size. */
    static int fetchBatch = 0

    /**
     * args: kind, tenantId, findings ([key, bucket, sides] each), fetchQuestion(question, ids) -> id -> record, labelFor(code);
     * catalog (BrainQuestionCatalog entries, default all of them).
     */
    static Map run(Map args) {
        String kind = (String) args.kind
        String tenantId = (String) args.tenantId
        Closure fetchQuestion = (Closure) args.fetchQuestion
        Closure labelFor = (Closure) (args.labelFor ?: { String c -> c })
        BrainEngine engine = BrainEngine.forPacks((List<String>) (args.packs ?: PILOT_PACKS))
        List<List<Map>> batches = batches(kind, (List<Map>) (args.findings ?: []))
        List<BrainQuestionCatalog.Entry> catalog = (List<BrainQuestionCatalog.Entry>) (args.catalog ?: BrainQuestionCatalog.ENTRIES)
        // DAR-BE-225: each question's answers by subject id, one map per catalogue entry.
        Map<String, Map<String, Map>> answers = catalog.collectEntries { [(it.name): [:]] } as Map<String, Map<String, Map>>
        // DAR-BE-227: answers the records already carry, so the worlds never ask them.
        Map<String, Map<String, Map>> fromFacts = answersInRecords(kind, (List<Map>) (args.findings ?: []), catalog)
        fromFacts.each { String q, Map<String, Map> byId -> answers.get(q).putAll(byId) }
        int answeredFromFacts = (fromFacts.values()*.size().sum() ?: 0) as int
        // DAR-BE-228: per question, what this pass did with it.
        Map<String, Map<String, Integer>> ledger = catalog.collectEntries {
            [(it.name): [asked: 0, reused: 0, dropped: 0, answeredFromFacts: (fromFacts.get(it.name)?.size() ?: 0), failed: 0, batches: 0]]
        } as Map<String, Map<String, Integer>>
        Closure<Void> tally = { String q, String what, int n -> if (n && ledger.containsKey(q)) ledger.get(q).put(what, ledger.get(q).get(what) + n) }
        List<String> warnings = []

        List<Map> round = batches.collect { fire(engine, kind, it, answers) }
        int asked = 0, reused = 0, dropped = 0
        Map<String, Integer> ranks = BrainQuestions.mayEnableRanks(engine)
        // A question that failed for a subject is not asked again in a later round of the same pass.
        Set<String> failed = [] as Set
        // DAR-BE-192: an answer can raise a further question (a replacement's return, then its RMA), so questions are asked
        // in rounds until a round has nothing new to ask, at most the catalogue's deepest depth (DAR-BE-225). A world is
        // fired again only when it gained one.
        int rounds = BrainQuestionCatalog.deepest(catalog)
        for (int r = 0; r < rounds; r++) {
            List<Ask> asks = (List<Ask>) round.collectMany { (List) it.asks }
            Map<Object, Cause> best = [:]
            round.each { best.putAll((Map) it.bestCauses) }
            Map plan = BrainAskPlanner.plan(asks, best, tenantId, ranks, fromFacts)
            reused += (plan.reused ?: 0) as int
            answeredFromFacts += (plan.fromFacts ?: 0) as int
            ((Map<String, Map<String, Integer>>) (plan.byQuestion ?: [:])).each { String q, Map<String, Integer> c ->
                tally(q, "reused", c.reused ?: 0); tally(q, "dropped", c.dropped ?: 0); tally(q, "answeredFromFacts", c.fromFacts ?: 0)
            }
            dropped += (plan.dropped ?: 0) as int
            Set<String> gained = [] as Set
            ((Map<String, Map>) plan.answered).each { String q, Map byId ->
                Map<String, Map> store = answers.get(q)
                if (store == null) return
                byId.each { k, v -> if (!store.containsKey(k)) { store.put((String) k, (Map) v); gained.add("${q}|${k}".toString()) } }
            }
            ((Map<String, List<String>>) plan.toFetch).each { String question, List<String> planned ->
                BrainQuestionCatalog.Entry entry = BrainQuestionCatalog.entry(question, catalog)
                if (entry == null) {
                    warnings.add("Question ${question} is not in the catalogue; not asked".toString())
                    return
                }
                List<String> allIds = planned.findAll { !failed.contains("${question}|${it}".toString()) }
                // Bounded calls; a failed batch fails alone and is not remembered.
                allIds.collate(fetchBatch > 0 ? fetchBatch : entry.batchSize).each { List<String> ids ->
                    tally(question, "batches", 1)
                    try {
                        Map found = (Map) (fetchQuestion.call(question, ids) ?: [:])
                        ids.each { String id ->
                            Map answer = (Map) found.get(id)
                            BrainAnswerStore.put(tenantId, question, id, answer)
                            answers.get(question).put(id, answer)
                            gained.add("${question}|${id}".toString())
                        }
                        asked += ids.size()
                        tally(question, "asked", ids.size())
                    } catch (Throwable t) {
                        ids.each { failed.add("${question}|${it}".toString()) }
                        tally(question, "failed", ids.size())
                        warnings.add("Question ${question} not answered for ${ids.size()} subject(s): ${t.message ?: t.class.simpleName}".toString())
                    }
                }
            }
            if (!gained) break
            round = batches.withIndex().collect { List<Map> batch, int i ->
                touches(kind, batch, answers, catalog) ? fire(engine, kind, batch, answers) : round[i]
            }
        }
        Map<String, Map> byKey = [:]
        round.each { Map fired ->
            ((List<Conclusion>) fired.conclusions).each { Conclusion c -> byKey.put(c.finding.key, conclusion(engine, c, labelFor)) }
        }
        return [byKey: byKey, asked: asked, reused: reused, dropped: dropped, answeredFromFacts: answeredFromFacts,
                questions: ledger.findAll { String q, Map<String, Integer> c -> c.values().any { it } }, warnings: warnings]
    }

    /**
     * DAR-BE-227. Per question, the answers the findings' own records carry: an entry's answeredBy field, present and not
     * empty, keyed by the finding's order or product id. A Shopify finding names no HotWax order, so it carries none.
     */
    static Map<String, Map<String, Map>> answersInRecords(String kind, List<Map> findings, List<BrainQuestionCatalog.Entry> catalog) {
        Map<String, Map<String, Map>> out = [:]
        catalog.findAll { it.answeredBy && it.subject in ["Order", "Product"] }.each { BrainQuestionCatalog.Entry e ->
            findings.each { Map f ->
                Map record = (Map) (((Map) (((Map) (f?.sides ?: [:])).FILE_1 ?: [:])).record ?: [:])
                Object value = record.get(e.answeredBy)
                if (value == null || (value instanceof CharSequence && !value.toString().trim())) return
                String id = e.subject == "Product" ? productIdOf(kind, f) : kind == "shopifyOrder" ? null : orderIdOf(kind, f)
                if (id) out.computeIfAbsent(e.name, { [:] }).put(id, [(e.answeredBy): value])
            }
        }
        return out
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

    private static Map fire(BrainEngine engine, String kind, List<Map> batch, Map<String, Map<String, Map>> answers) {
        Map<String, Object> world = [:]
        return engine.fire(batch.collectMany {
            BrainFactAdapter.facts(kind, it, answers.get(BrainFactAdapter.ORDER_PAYMENTS), world,
                    answers.get(BrainFactAdapter.PRODUCT_NETSUITE_ID), answers)
        } as List<Object>)
    }

    /**
     * A batch is fired again when an answer is about one of its findings' subjects: its order (an Order question), its
     * product, or its Shopify order. A ReplacementReturn's answers reach the batch through its order's replacementReturn
     * answer, which keeps it firing.
     */
    private static boolean touches(String kind, List<Map> batch, Map<String, Map<String, Map>> answers,
                                   List<BrainQuestionCatalog.Entry> catalog) {
        List<BrainQuestionCatalog.Entry> answered = catalog.findAll { answers.get(it.name) }
        if (!answered) return false
        return batch.any { Map f ->
            String o = orderIdOf(kind, f)
            String p = productIdOf(kind, f)
            answered.any { BrainQuestionCatalog.Entry e ->
                Map<String, Map> byId = answers.get(e.name)
                e.subject == "Order" ? byId.containsKey(o) :
                        e.subject == "Product" ? byId.containsKey(p) :
                                e.subject == "ShopifyOrder" ? kind == "shopifyOrder" && byId.containsKey(o) : false
            }
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
        // DAR-BE-204: a Shopify order's world is keyed by its Shopify id until HotWax answers for it.
        Object id = kind == "orderLine" ? record.omsOrderId : kind == "shopifyOrder" ? record.id : record.orderId
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
