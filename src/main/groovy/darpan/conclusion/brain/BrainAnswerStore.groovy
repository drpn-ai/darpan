package darpan.conclusion.brain

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-151 (design D7). Answers to the brain's questions, per tenant, question and subject id, shared by every check
 * concluded in this JVM while they are fresh: R2 reuses the payments R1 asked for minutes earlier. A stored null is
 * "asked, nothing found". Tenants never share an answer: the tenant is part of every key, and an answer without one is
 * not kept.
 *
 * <p>DAR-BE-225/226: an answer lives for its question's freshness class (BrainQuestionCatalog): `run` for what a fix or
 * the next hour can change, as long as one audit play (OmsGqlLookupCache's ID TTL); `stable` for what changes on the
 * scale of a day, such as a product's NetSuite id. The expiry is fixed when the answer is stored. At the cap, expired
 * entries go first, then the oldest tenth.</p>
 */
class BrainAnswerStore {

    static final long RUN_TTL_MILLIS = 15 * 60_000L
    static final long STABLE_TTL_MILLIS = 6 * 60 * 60_000L
    static final Map<String, Long> TTL_MILLIS = [(BrainQuestionCatalog.FRESH_RUN)   : RUN_TTL_MILLIS,
                                                 (BrainQuestionCatalog.FRESH_STABLE): STABLE_TTL_MILLIS].asImmutable()
    static final int MAX_ENTRIES = 100_000
    /** Test seams. */
    static Closure<Long> clock = { System.currentTimeMillis() }
    static int maxEntries = MAX_ENTRIES

    private static final Map<String, Map> ANSWERS = new ConcurrentHashMap<>()

    private static String key(String tenantId, String question, String subjectId) { "${tenantId}|${question}|${subjectId}".toString() }

    /** A question's time to live; a question the catalogue does not hold is `run`. */
    static long ttlFor(String question) {
        String freshness = BrainQuestionCatalog.entry(question)?.freshness
        return TTL_MILLIS.get(freshness) ?: RUN_TTL_MILLIS
    }

    static boolean has(String tenantId, String question, String subjectId) {
        String k = key(tenantId, question, subjectId)
        Map entry = ANSWERS.get(k)
        if (entry == null) return false
        if ((clock.call() as long) > (entry.expiresAt as long)) {
            ANSWERS.remove(k)
            return false
        }
        return true
    }

    static Map get(String tenantId, String question, String subjectId) {
        return has(tenantId, question, subjectId) ? (Map) ANSWERS.get(key(tenantId, question, subjectId)).answer : null
    }

    static void put(String tenantId, String question, String subjectId, Map answer) {
        if (!tenantId || !question || !subjectId) return
        long now = clock.call() as long
        if (ANSWERS.size() >= maxEntries) evict(now)
        ANSWERS.put(key(tenantId, question, subjectId), [answer: answer, at: now, expiresAt: now + ttlFor(question)])
    }

    private static void evict(long now) {
        ANSWERS.entrySet().removeIf { now > (it.value.expiresAt as long) }
        if (ANSWERS.size() < maxEntries) return
        int drop = Math.max(1, ANSWERS.size().intdiv(10))
        ANSWERS.entrySet().sort { it.value.at as long }.take(drop).each { ANSWERS.remove(it.key) }
    }

    static void clear() { ANSWERS.clear() }
}
