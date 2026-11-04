package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.Product
import darpan.conclusion.brain.fact.ReplacementReturn

/**
 * DAR-BE-151 (design D6). The one place that sees every ask of a conclude pass. An ask is dropped when its subject's best
 * cause already ranks at or above the most its question can unlock; asks are deduped by (question, subject id); an
 * answer still fresh in the store is reused; the rest is what to fetch, per question.
 */
class BrainAskPlanner {

    static Map plan(List<Ask> asks, Map<Object, Cause> bestCauses, String tenantId, Map<String, Integer> mayEnableRanks) {
        Map<String, LinkedHashSet<String>> toFetch = [:]
        Map<String, Map<String, Map>> answered = [:]
        int dropped = 0
        int reused = 0
        Set<String> seen = [] as Set
        Set<String> droppedKeys = [] as Set
        (asks ?: []).each { Ask ask ->
            String id = subjectId(ask.subject)
            if (!id) return
            // DAR-BE-186: a product question is dropped by the best cause of the order that asked it; deduped only after,
            // so another order needing the same product still asks.
            Cause best = bestCauses?.get(ask.forOrder ?: ask.subject)
            if (best != null && best.rank >= (mayEnableRanks?.get(ask.question) ?: Integer.MAX_VALUE)) {
                if (droppedKeys.add("${ask.question}|${id}|${subjectId(ask.forOrder)}".toString())) dropped++
                return
            }
            if (!seen.add("${ask.question}|${id}".toString())) return
            if (BrainAnswerStore.has(tenantId, ask.question, id)) {
                answered.computeIfAbsent(ask.question, { [:] }).put(id, BrainAnswerStore.get(tenantId, ask.question, id))
                reused++
                return
            }
            toFetch.computeIfAbsent(ask.question, { new LinkedHashSet<String>() }).add(id)
        }
        // Sorted: a session hands its facts back in no fixed order, and a run must ask the same way every time.
        return [toFetch : toFetch.collectEntries { String q, Set ids -> [(q): (ids as List<String>).sort()] },
                answered: answered, dropped: dropped, reused: reused]
    }

    /** An order's id, a product's (DAR-BE-186), or a replacement's return's (DAR-BE-192). */
    static String subjectId(Object subject) {
        if (subject instanceof Order) return ((Order) subject).orderId
        if (subject instanceof Product) return ((Product) subject).productId
        if (subject instanceof ReplacementReturn) return ((ReplacementReturn) subject).returnId
        return null
    }
}
