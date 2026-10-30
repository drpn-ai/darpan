package darpan.knowledge

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** Pure unit tests (no Moqui) for the knowledge leak check. Tenants and values are synthetic. */
class KnowledgeLeakCheckTests {

    private static final List<Map> IDENTIFIERS = [
        [value: 'KB_ACME', kind: 'tenantId', caseSensitive: true],
        [value: 'Acme Goods', kind: 'label', caseSensitive: false],
        [value: 'acme.myshopify.com', kind: 'host', caseSensitive: false],
        [value: 'acme-shop', kind: 'store', caseSensitive: false]
    ] as List<Map>

    @Test
    void shapesCatchPrivateValues() {
        Map<String, String> cases = [
            'contact ops@acme.example now'        : 'email',
            'call +1 415 555 0100 today'          : 'phone',
            'call (415)555-0100 today'            : 'phone',
            'call (415) 555-0100 today'           : 'phone',
            'call 415-555-0100 today'             : 'phone',
            'gid://shopify/Order/5123456789'      : 'shopifyGid',
            'account 1234567_SB1'                 : 'netsuiteAccount',
            'order #AC123456'                     : 'orderName',
            'id M1088731 seen'                    : 'omsOrderId',
            'host acme-oms.hotwax.io'             : 'hostname',
            'refund of 181.00 on AC123456'        : 'amountWithId'
        ]
        cases.each { String text, String shape ->
            assertTrue(KnowledgeLeakCheck.shapeHits(text).contains(shape),
                "expected shape ${shape} in: ${text} but got ${KnowledgeLeakCheck.shapeHits(text)}")
        }
        assertEquals(['email'], KnowledgeLeakCheck.shapeHits('ops@acme.example'))
        assertEquals(['phone'], KnowledgeLeakCheck.shapeHits('+1 415 555 0100'))
        assertEquals(['phone'], KnowledgeLeakCheck.shapeHits('(415)555-0100'))
        assertEquals(['shopifyGid'], KnowledgeLeakCheck.shapeHits('gid://shopify/Order/5123456789'))
        assertEquals(['netsuiteAccount'], KnowledgeLeakCheck.shapeHits('account 1234567_SB1'))
        assertEquals(['omsOrderId'], KnowledgeLeakCheck.shapeHits('M1088731'))
        assertEquals(['hostname'], KnowledgeLeakCheck.shapeHits('acme-oms.hotwax.io'))
    }

    @Test
    void shapesPassGeneralText() {
        [
            'HotWax keeps exchange ids on the original order.',
            'QUERY_COST_BUDGET is 950 per query.',
            'Shopify bulk operations poll every 5 s.',
            'api.shopify.com',
            'suitetalk.api.netsuite.com',
            'reviewed 2026-10-30, version 1.415.555'
        ].each { String text ->
            assertEquals([], KnowledgeLeakCheck.shapeHits(text), text)
        }
    }

    @Test
    void identifierHitsByKind() {
        Set<String> none = [] as Set<String>
        assertEquals(['tenantId'], KnowledgeLeakCheck.identifierHits('KB_ACME joins on sku', IDENTIFIERS, none))
        assertEquals(['label'], KnowledgeLeakCheck.identifierHits('for acme goods the store...', IDENTIFIERS, none))
        assertEquals(['host'], KnowledgeLeakCheck.identifierHits('https://ACME.myshopify.com/admin', IDENTIFIERS, none))
        assertEquals([], KnowledgeLeakCheck.identifierHits('kb_acme', IDENTIFIERS, none))
        assertEquals(['store'], KnowledgeLeakCheck.identifierHits('the ACME-SHOP admin', IDENTIFIERS, none))
        assertEquals([], KnowledgeLeakCheck.identifierHits('the acme-shopper admin', IDENTIFIERS, none), 'store is a whole word')
    }

    @Test
    void allowPhraseSuppressesAFalsePositive() {
        List<Map> ids = [[value: 'Fencepost', kind: 'label', caseSensitive: false]] as List<Map>
        assertEquals([], KnowledgeLeakCheck.identifierHits('watch for fencepost errors in paging', ids, ['fencepost errors'] as Set<String>))
        assertEquals(['label'], KnowledgeLeakCheck.identifierHits('watch for fencepost errors in paging', ids, [] as Set<String>))
        assertEquals(['label'], KnowledgeLeakCheck.identifierHits('Fencepost runs a second store', ids, ['fencepost errors'] as Set<String>),
                'the phrase is removed, the bare label still matches')
    }

    @Test
    void hitsNeverCarryValues() {
        String text = 'KB_ACME and Acme Goods at acme.myshopify.com, ops@acme.example'
        List<String> hits = KnowledgeLeakCheck.identifierHits(text, IDENTIFIERS, [] as Set<String>)
        hits.addAll(KnowledgeLeakCheck.shapeHits(text))
        assertFalse(hits.isEmpty())
        hits.each { String h ->
            IDENTIFIERS.each { Map id ->
                String v = (String) id.get('value')
                assertFalse(h.equalsIgnoreCase(v) || h.toLowerCase().contains(v.toLowerCase()), h)
            }
            assertFalse(h.contains('@'), h)
        }
    }

    @Test
    void nonWordEdgeIdentifiersStillMatch() {
        List<Map> ids = [[value: 'Acme & Co.', kind: 'label', caseSensitive: false]] as List<Map>
        Set<String> none = [] as Set<String>
        assertEquals(['label'], KnowledgeLeakCheck.identifierHits('sold by acme & co. in the US', ids, none))
        // "co." is followed by a word character, so it is a different token and does not match.
        assertEquals([], KnowledgeLeakCheck.identifierHits('acme & co.uk', ids, none))
    }

    @Test
    void blankOrNullIdentifiersAreSkipped() {
        List<Map> ids = [
            [value: null, kind: 'label', caseSensitive: false],
            [value: '  ', kind: 'label', caseSensitive: false],
            [value: 'x', kind: null, caseSensitive: false],
            [value: 'KB_ACME', kind: 'tenantId', caseSensitive: true]
        ] as List<Map>
        assertEquals(['tenantId'], KnowledgeLeakCheck.identifierHits('KB_ACME x', ids, [] as Set<String>))
    }
}
