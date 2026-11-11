package darpan.conclusion.brain

/**
 * DAR-BE-151 (design D5). The brain's questions. What a question can unlock is read from the packs themselves: a rule
 * that @asks a question names the causes it @mayEnable, and those causes' @rank give the ceiling above which asking
 * cannot change anything. So the brain knows what every question might decide without a second copy of the ranks.
 * A question is answered through the scope's conclusionLookupsJson entry of the same name (its connector and config).
 */
class BrainQuestions {

    static Map<String, Integer> mayEnableRanks(BrainEngine engine) {
        Map<String, Map<String, Object>> meta = engine.ruleMetadata()
        Map<String, Integer> rankByCode = [:]
        meta.values().each { Map m -> if (m.concludes != null && m.rank != null) rankByCode.put(text(m.concludes), text(m.rank) as int) }
        Map<String, Integer> out = [:]
        meta.values().each { Map m ->
            if (m.asks == null) return
            String question = text(m.asks)
            int ceiling = text(m.mayEnable)?.split(",")?.collect { rankByCode.get(it.trim()) ?: 0 }?.max() ?: 0
            out.put(question, Math.max(out.get(question) ?: 0, ceiling))
        }
        return out
    }

    static String lookupNameFor(String question) { return question }

    /** The read a question takes when its scope names no lookup for it (DAR-BE-225: declared in BrainQuestionCatalog). */
    static Map defaultLookup(String question, String hotwaxSourceConfigId) {
        if (!hotwaxSourceConfigId) return null
        return BrainQuestionCatalog.entry(question)?.defaultLookup?.call(hotwaxSourceConfigId)
    }

    private static String text(Object value) { value?.toString()?.replaceAll(/^"|"$/, "")?.trim() }
}
