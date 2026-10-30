package darpan.knowledge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** Pure unit tests (no Moqui) for the in-memory Lucene knowledge index. Entries are synthetic. */
class KnowledgeIndexTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30)
    private KnowledgeIndex index

    private static Map entry(String key, String title, List<String> systems, String kind, List<String> tags, String body,
                             LocalDate reviewBy = LocalDate.of(2027, 6, 1)) {
        return [key: key, tier: 'product', systems: systems, kind: kind, tags: tags, source: 'probe', owner: 'Jo Doe',
                reviewBy: reviewBy, supersedes: null, title: title, body: body] as Map
    }

    @BeforeEach
    void setUp() {
        index = KnowledgeIndex.build([
                entry('oms-gql-query-cost-cap', 'OMS GraphQL caps one query at 1000 cost', ['hotwax'], 'limit',
                        ['graphql', 'QUERY_COST_BUDGET'], 'A single query over the cost cap is rejected. ' + ('x' * 400)),
                entry('ns-suiteql-row-limit', 'SuiteQL returns at most 1000 rows', ['netsuite'], 'limit',
                        ['suiteql', 'pagination'], 'Page with offset when more rows are needed.'),
                entry('ns-status-codes', 'NetSuite status codes are single letters', ['netsuite'], 'gotcha',
                        ['status'], 'Billed is B, pending billing is F.'),
                entry('shopify-order-window', 'Shopify hides orders older than sixty days', ['shopify'], 'gotcha',
                        ['orders', 'window'], 'Request the read all orders scope.'),
                entry('audit-rule-two', 'Audit rule for open returns', ['hotwax', 'netsuite'], 'rule',
                        ['R2', 'returns'], 'Returns open past the window are flagged.'),
                entry('stale-note', 'Expired returns note', ['hotwax'], 'rule',
                        ['returns'], 'This one is past its review date.', LocalDate.of(2026, 10, 29)),
        ])
    }

    @AfterEach
    void tearDown() { index?.close() }

    private List<String> keys(List<Map> hits) { hits.collect { it.get('key') as String } }

    @Test
    void findsByWords() {
        List<Map> hits = index.search('query cost cap', null, null, 5, TODAY)
        assertFalse(hits.isEmpty())
        assertEquals('oms-gql-query-cost-cap', hits.get(0).get('key'))
        assertEquals('product', hits.get(0).get('tier'))
    }

    @Test
    void findsAnExactCode() {
        assertEquals('oms-gql-query-cost-cap', index.search('QUERY_COST_BUDGET', null, null, 5, TODAY).get(0).get('key'))
        assertEquals('audit-rule-two', index.search('R2', null, null, 5, TODAY).get(0).get('key'))
    }

    @Test
    void filtersBySystemAndKind() {
        List<Map> ns = index.search('status rows returns', 'netsuite', null, 20, TODAY)
        assertFalse(ns.isEmpty())
        ns.each { assertTrue(['ns-suiteql-row-limit', 'ns-status-codes', 'audit-rule-two'].contains(it.get('key'))) }
        List<Map> limits = index.search('query rows cap', null, 'limit', 20, TODAY)
        assertEquals(['oms-gql-query-cost-cap', 'ns-suiteql-row-limit'] as Set, keys(limits) as Set)
    }

    @Test
    void excludesExpired() {
        assertFalse(keys(index.search('returns', null, null, 20, TODAY)).contains('stale-note'))
        assertTrue(keys(index.search('returns', null, null, 20, LocalDate.of(2026, 10, 29))).contains('stale-note'))
    }

    @Test
    void limitsAndSnippets() {
        assertTrue(index.search('returns query rows orders status', null, null, 0, TODAY).size() <= 5)
        assertTrue(index.search('returns query rows orders status', null, null, 50, TODAY).size() <= 20)
        index.search('query cost cap', null, null, 5, TODAY).each { assertTrue(((String) it.get('snippet')).length() <= 300) }
        assertEquals(300, ((String) index.search('query cost cap', null, null, 5, TODAY).get(0).get('snippet')).length())
    }

    @Test
    void emptyAndStopwordQueries() {
        ['', '   ', 'the and of', '?!', null].each { String q ->
            assertEquals([], index.search(q, null, null, 5, TODAY))
        }
    }

    @Test
    void getReturnsTheWholeEntry() {
        assertTrue(((String) index.get('oms-gql-query-cost-cap').get('body')).length() > 300)
        assertNull(index.get('nope'))
        assertNotNull(index.get('ns-status-codes'))
        assertEquals(6, index.size())
    }

    /** A default locale whose lower-casing differs (Turkish dotless i) changes no filter or code match. */
    @Test
    void lowerCasingIgnoresTheDefaultLocale() {
        Locale before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag('tr-TR'))
        KnowledgeIndex turkish = null
        try {
            turkish = KnowledgeIndex.build([entry('imap-limit', 'IMAP pages stop at fifty', ['IMAP'], 'LIMIT', ['IMAP_PAGE'], 'Paging stops.')])
            assertEquals(['imap-limit'], turkish.search('pages', 'imap', 'limit', 5, TODAY)*.key)
            assertEquals(['imap-limit'], turkish.search('IMAP_PAGE', null, null, 5, TODAY)*.key)
        } finally {
            turkish?.close()
            Locale.setDefault(before)
        }
    }
}
