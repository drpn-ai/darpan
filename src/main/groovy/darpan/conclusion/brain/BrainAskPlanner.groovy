package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause

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
        (asks ?: []).each { Ask ask ->
            String id = ask.subject?.orderId
            if (!id || !seen.add("${ask.question}|${id}".toString())) return
            Cause best = bestCauses?.get(ask.subject)
            if (best != null && best.rank >= (mayEnableRanks?.get(ask.question) ?: Integer.MAX_VALUE)) {
                dropped++
                return
            }
            if (BrainAnswerStore.has(tenantId, ask.question, id)) {
                answered.computeIfAbsent(ask.question, { [:] }).put(id, BrainAnswerStore.get(tenantId, ask.question, id))
                reused++
                return
            }
            toFetch.computeIfAbsent(ask.question, { new LinkedHashSet<String>() }).add(id)
        }
        return [toFetch : toFetch.collectEntries { String q, Set ids -> [(q): ids as List<String>] },
                answered: answered, dropped: dropped, reused: reused]
    }
}
