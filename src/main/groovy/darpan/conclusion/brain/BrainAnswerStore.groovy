package darpan.conclusion.brain

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-151 (design D7). Answers to the brain's questions, per tenant, question and subject id, shared by every check
 * concluded in this JVM while they are fresh: R2 reuses the payments R1 asked for minutes earlier. A stored null is
 * "asked, nothing found". Only the `run` freshness class exists in the pilot: what a fix or the next hour can change
 * lives as long as one audit play (OmsGqlLookupCache's ID TTL). Bounded like that cache.
 */
class BrainAnswerStore {

    static final long RUN_TTL_MILLIS = 15 * 60_000L
    static final int MAX_ENTRIES = 100_000
    /** Test seam. */
    static Closure<Long> clock = { System.currentTimeMillis() }

    private static final Map<String, Map> ANSWERS = new ConcurrentHashMap<>()

    private static String key(String tenantId, String question, String subjectId) { "${tenantId}|${question}|${subjectId}".toString() }

    static boolean has(String tenantId, String question, String subjectId) {
        Map entry = ANSWERS.get(key(tenantId, question, subjectId))
        if (entry == null) return false
        if ((clock.call() as long) - (entry.at as long) > RUN_TTL_MILLIS) {
            ANSWERS.remove(key(tenantId, question, subjectId))
            return false
        }
        return true
    }

    static Map get(String tenantId, String question, String subjectId) {
        return has(tenantId, question, subjectId) ? (Map) ANSWERS.get(key(tenantId, question, subjectId)).answer : null
    }

    static void put(String tenantId, String question, String subjectId, Map answer) {
        if (!tenantId || !question || !subjectId) return
        if (ANSWERS.size() >= MAX_ENTRIES) {
            long now = clock.call() as long
            ANSWERS.entrySet().removeIf { (now - (it.value.at as long)) > RUN_TTL_MILLIS }
            if (ANSWERS.size() >= MAX_ENTRIES) ANSWERS.clear()
        }
        ANSWERS.put(key(tenantId, question, subjectId), [answer: answer, at: clock.call()])
    }

    static void clear() { ANSWERS.clear() }
}
