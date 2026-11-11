package darpan.conclusion.brain

import groovy.transform.Immutable

import static darpan.conclusion.brain.BrainFactAdapter.OMS_ORDER_BY_SHOPIFY_ID
import static darpan.conclusion.brain.BrainFactAdapter.ORDER_PAYMENTS
import static darpan.conclusion.brain.BrainFactAdapter.ORIGINAL_IN_NETSUITE
import static darpan.conclusion.brain.BrainFactAdapter.PRODUCT_NETSUITE_ID
import static darpan.conclusion.brain.BrainFactAdapter.REPLACEMENT_RETURN
import static darpan.conclusion.brain.BrainFactAdapter.RETURN_MEMO
import static darpan.conclusion.brain.BrainFactAdapter.RETURN_RMA

/**
 * DAR-BE-225. Every question the brain can ask, declared once. A pack rule that @asks a question must find it here, and
 * every entry must be asked by some rule (BrainQuestionCatalogTests). The conclude loop keeps its answers by these names,
 * fires a batch again by the entry's subject, and asks in as many rounds as the deepest entry's depth.
 *
 * <ul>
 *   <li>subject: the fact the question is about (fact package simple name); its id is the ask planner's subject id.</li>
 *   <li>batchSize: ids per question call.</li>
 *   <li>freshness: how long an answer is reused (BrainAnswerStore.TTL_MILLIS): `run` or `stable`.</li>
 *   <li>depth: the longest chain of answers that can come before the question is asked, plus one. A Shopify order's HotWax
 *       order (1), then that order's replacement return (2), then the return's RMA (3).</li>
 *   <li>defaultLookup: the read taken when the scope names no lookup of the question's name, from the scope's HotWax
 *       config (the FILE_1 source's); null for a question that has none.</li>
 * </ul>
 */
class BrainQuestionCatalog {

    @Immutable(knownImmutableClasses = [Closure])
    static class Entry {
        String name
        String subject
        int batchSize
        String freshness
        int depth
        /** hotwaxSourceConfigId -> lookup map, or null. */
        Closure<Map> defaultLookup
    }

    static final String FRESH_RUN = "run"
    /** DAR-BE-226: what changes on the scale of a day. */
    static final String FRESH_STABLE = "stable"
    static final int BATCH = 500

    static final List<Entry> ENTRIES = [
            new Entry(name: OMS_ORDER_BY_SHOPIFY_ID, subject: "ShopifyOrder", batchSize: BATCH, freshness: FRESH_RUN, depth: 1,
                    // DAR-BE-204: through the scope's lookup of this name only; a Shopify-paired scope has no HotWax source.
                    defaultLookup: { String config -> null }),
            new Entry(name: ORDER_PAYMENTS, subject: "Order", batchSize: BATCH, freshness: FRESH_RUN, depth: 2,
                    // The order's raw OPP rows by OMS order id.
                    defaultLookup: { String config ->
                        [name: ORDER_PAYMENTS, subject: "FILE_1", idField: "orderId", systemEnumId: "OMS_GQL", sourceConfigId: config,
                         keyField: "orderId", parameters: [keepRecordFields: ["orderId", "grandTotal", "paymentPreferences"]]]
                    }),
            // DAR-BE-226: whether a product is in NetSuite rarely changes within a day; stable.
            new Entry(name: PRODUCT_NETSUITE_ID, subject: "Product", batchSize: BATCH, freshness: FRESH_STABLE, depth: 2,
                    // DAR-BE-186: the products named by id, each with its NETSUITE_PRODUCT_ID (extract#HotWaxOmsGqlProducts lookupKeys).
                    defaultLookup: { String config ->
                        [name: PRODUCT_NETSUITE_ID, subject: "FILE_1", idField: "productId", systemEnumId: "OMS_PRODUCTS_GQL",
                         sourceConfigId: config, keyField: "productId", parameters: [:]]
                    }),
            new Entry(name: REPLACEMENT_RETURN, subject: "Order", batchSize: BATCH, freshness: FRESH_RUN, depth: 2,
                    // DAR-BE-192: the return a replacement was made for, by the replacement's order id (the returns read's key).
                    defaultLookup: { String config ->
                        [name: REPLACEMENT_RETURN, subject: "FILE_1", idField: "orderId", systemEnumId: "OMS_RETURNS_GQL",
                         sourceConfigId: config, keyField: "replacementOrderId", parameters: [:]]
                    }),
            // NetSuite is read through the scope's own lookups of these names.
            new Entry(name: RETURN_RMA, subject: "ReplacementReturn", batchSize: BATCH, freshness: FRESH_RUN, depth: 3,
                    defaultLookup: { String config -> null }),
            new Entry(name: RETURN_MEMO, subject: "ReplacementReturn", batchSize: BATCH, freshness: FRESH_RUN, depth: 3,
                    defaultLookup: { String config -> null }),
            new Entry(name: ORIGINAL_IN_NETSUITE, subject: "Order", batchSize: BATCH, freshness: FRESH_RUN, depth: 3,
                    defaultLookup: { String config -> null }),
    ].asImmutable()

    static Entry entry(String name, List<Entry> entries = ENTRIES) { entries.find { it.name == name } }

    /** The rounds a conclude pass asks in. */
    static int deepest(List<Entry> entries = ENTRIES) { (entries*.depth.max() ?: 0) as int }
}
