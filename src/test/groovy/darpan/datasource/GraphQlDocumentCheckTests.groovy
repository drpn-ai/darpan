package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S15a, OC131 (security). fieldPaths walks a fragment spread once per path, so a short document whose
 * fragments each spread the next one twice expands to 2^levels field paths: 30 levels in a few hundred characters was
 * enough to run the shared JVM out of memory, and ShopifyProtectedFieldGuard runs fieldPaths on every Shopify document a
 * signed-in user's CLI or agent sends. The walk stops at a cap and the document is refused, fail-closed.
 */
class GraphQlDocumentCheckTests {

    /** query { root { ...F1 } } where F_i spreads F_{i+1} twice under two aliases, and the last fragment selects one field. */
    static String doubling(int levels) {
        StringBuilder doc = new StringBuilder("query Q { orders { ...F1 } }\n")
        for (int i = 1; i < levels; i++) {
            doc.append("fragment F${i} on Order { a: lineItems { ...F${i + 1} } b: lineItems { ...F${i + 1} } }\n")
        }
        doc.append("fragment F${levels} on Order { id }\n")
        return doc.toString()
    }

    @Test
    void aDocumentThatExpandsPastTheCapIsRefused() {
        Map report = GraphQlDocumentCheck.fieldPaths(doubling(15))
        assertTrue(((List) report.errors).any { it.toString().contains("more than 10000 fields") }, report.errors.toString())
        assertTrue(((List) report.fields).size() <= 10_001, "the walk stopped at the cap: ${((List) report.fields).size()}")
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void theThirtyLevelAttackDocumentIsRefusedQuickly() {
        // 2^30 paths before the cap: this is the document that ran the JVM out of memory. Now it stops at the cap.
        String attack = doubling(30)
        assertTrue(attack.length() < 4000, "a few kilobytes: ${attack.length()}")
        Map report = GraphQlDocumentCheck.fieldPaths(attack)
        assertEquals(["The query selects more than ${GraphQlDocumentCheck.MAX_FIELD_PATHS} fields.".toString()], report.errors)
    }

    @Test
    void anOrdinaryDocumentStillReportsEveryFieldWithNoError() {
        Map report = GraphQlDocumentCheck.fieldPaths(doubling(4))
        assertEquals([], report.errors)
        assertEquals(["query"], report.operations)
        assertTrue(((List<Map>) report.fields).any { it.path == ["orders", "on:Order", "lineItems", "on:Order", "lineItems", "on:Order", "lineItems", "on:Order", "id"] })
    }
}
