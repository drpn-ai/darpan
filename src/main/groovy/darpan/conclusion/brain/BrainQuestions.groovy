package darpan.conclusion.brain

/**
 * DAR-BE-151 (design D5). The brain's questions. What a question can unlock is read from the packs themselves: a rule
 * that @asks a question names the causes it @mayEnable, and those causes' @rank give the ceiling above which asking
 * cannot change anything. So the brain knows what every question might decide without a second copy of the ranks.
 * A question is answered through the scope's conclusionLookupsJson entry of the same name (its connector and config).
 */
class BrainQuestions {

    static final String ORDER_PAYMENTS = BrainFactAdapter.ORDER_PAYMENTS
    static final String PRODUCT_NETSUITE_ID = BrainFactAdapter.PRODUCT_NETSUITE_ID

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

    /**
     * The read a question takes when its scope names no lookup for it, from the scope's own HotWax config (the FILE_1
     * source's): orderPayments reads the order's raw OPP rows by OMS order id; productNetsuiteId (DAR-BE-186) the named
     * products' NETSUITE_PRODUCT_ID by product id. Null for a question with no default.
     */
    static Map defaultLookup(String question, String hotwaxSourceConfigId) {
        if (!hotwaxSourceConfigId) return null
        // DAR-BE-186: the products named by id, each with its NETSUITE_PRODUCT_ID (extract#HotWaxOmsGqlProducts lookupKeys).
        if (question == PRODUCT_NETSUITE_ID) {
            return [name: PRODUCT_NETSUITE_ID, subject: "FILE_1", idField: "productId", systemEnumId: "OMS_PRODUCTS_GQL",
                    sourceConfigId: hotwaxSourceConfigId, keyField: "productId", parameters: [:]]
        }
        if (question != ORDER_PAYMENTS) return null
        return [name: ORDER_PAYMENTS, subject: "FILE_1", idField: "orderId", systemEnumId: "OMS_GQL", sourceConfigId: hotwaxSourceConfigId,
                keyField: "orderId", parameters: [keepRecordFields: ["orderId", "grandTotal", "paymentPreferences"]]]
    }

    private static String text(Object value) { value?.toString()?.replaceAll(/^"|"$/, "")?.trim() }
}
