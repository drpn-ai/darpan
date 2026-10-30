package darpan.knowledge

import org.junit.jupiter.api.Test

import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** Pure unit tests (no Moqui) for the knowledge entry file parser. Entries are synthetic. */
class KnowledgeEntryFileTests {

    private static final String ORIGIN = 'product/hotwax/x.md'

    private static String entry(Map<String, String> overrides = [:], String body = 'A short body.', boolean heading = true) {
        Map<String, String> h = [id: 'oms-gql-cost-cap', tier: 'product', system: 'hotwax, shopify', kind: 'gotcha',
                                 tags: 'graphql, cost', source: 'probe 2026-10-01', owner: 'Jo Doe',
                                 reviewBy: '2027-01-31', supersedes: 'old-key']
        h.putAll(overrides)
        StringBuilder sb = new StringBuilder('---\n')
        h.each { String k, String v -> if (v != null) sb.append(k).append(': ').append(v).append('\n') }
        sb.append('---\n')
        if (heading) sb.append('# OMS GraphQL caps one query at 1000 cost\n')
        sb.append(body).append('\n')
        return sb.toString()
    }

    @Test
    void parsesPromotedTo() {
        assertEquals('some-product-key', KnowledgeEntryFile.parse(entry([promotedTo: 'some-product-key']), ORIGIN).get('promotedTo'))
    }

    @Test
    void parsesAFullEntry() {
        Map m = KnowledgeEntryFile.parse(entry(), ORIGIN)
        assertEquals('oms-gql-cost-cap', m.get('key'))
        assertEquals('product', m.get('tier'))
        assertEquals(['hotwax', 'shopify'], m.get('systems'))
        assertEquals('gotcha', m.get('kind'))
        assertEquals(['graphql', 'cost'], m.get('tags'))
        assertEquals('probe 2026-10-01', m.get('source'))
        assertEquals('Jo Doe', m.get('owner'))
        assertEquals(LocalDate.of(2027, 1, 31), m.get('reviewBy'))
        assertEquals('old-key', m.get('supersedes'))
        assertNull(m.get('promotedTo'))
        assertEquals('OMS GraphQL caps one query at 1000 cost', m.get('title'))
        assertEquals('A short body.', m.get('body'))
    }

    @Test
    void ownerDefaults() {
        Map m = KnowledgeEntryFile.parse(entry([owner: null]), ORIGIN)
        assertEquals('Aditi Patel', m.get('owner'))
    }

    @Test
    void rejectsMissingRequired() {
        List<String> texts = [entry([id: null]), entry([tier: null]), entry([source: null]), entry([reviewBy: null]),
                              entry([:], 'body', false)]
        for (String t : texts) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException) { KnowledgeEntryFile.parse(t, ORIGIN) }
            assertTrue(e.message.startsWith(ORIGIN), e.message)
        }
    }

    @Test
    void rejectsBadTierDateAndLength() {
        List<String> texts = [entry([tier: 'shared']), entry([reviewBy: '31/01/2027']),
                              entry([:], 'x' * (KnowledgeEntryFile.MAX_BODY_CHARS + 1)), 'no header at all']
        for (String t : texts) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException) { KnowledgeEntryFile.parse(t, ORIGIN) }
            assertTrue(e.message.startsWith(ORIGIN), e.message)
        }
        // exactly at the limit passes
        KnowledgeEntryFile.parse(entry([:], 'x' * KnowledgeEntryFile.MAX_BODY_CHARS), ORIGIN)
    }

    @Test
    void rejectsValuesLongerThanTheirColumn() {
        Map<String, String> tooLong = [id: 'k' * (KnowledgeEntryFile.MAX_SHORT_CHARS + 1),
                                       supersedes: 's' * (KnowledgeEntryFile.MAX_SHORT_CHARS + 1),
                                       promotedTo: 'p' * (KnowledgeEntryFile.MAX_SHORT_CHARS + 1)]
        tooLong.each { String field, String value ->
            IllegalArgumentException e = assertThrows(IllegalArgumentException) { KnowledgeEntryFile.parse(entry([(field): value]), ORIGIN) }
            assertTrue(e.message.startsWith(ORIGIN + ': ' + field + ' is '), e.message)
        }
        String longTitle = entry([:]).replace('# OMS GraphQL caps one query at 1000 cost', '# ' + 't' * (KnowledgeEntryFile.MAX_MEDIUM_CHARS + 1))
        IllegalArgumentException e = assertThrows(IllegalArgumentException) { KnowledgeEntryFile.parse(longTitle, ORIGIN) }
        assertTrue(e.message.startsWith(ORIGIN + ': title is 256 characters, limit 255'), e.message)
        // a tenant entry is a row, so its source column caps it too; a product file never is
        String longSource = 'x' * (KnowledgeEntryFile.MAX_MEDIUM_CHARS + 1)
        IllegalArgumentException tenant = assertThrows(IllegalArgumentException) {
            KnowledgeEntryFile.parse(entry([tier: 'tenant', source: longSource]), ORIGIN)
        }
        assertTrue(tenant.message.startsWith(ORIGIN + ': source is 256 characters, limit 255'), tenant.message)
        KnowledgeEntryFile.parse(entry([source: longSource]), ORIGIN)
        // exactly at the limits passes
        KnowledgeEntryFile.parse(entry([id: 'k' * KnowledgeEntryFile.MAX_SHORT_CHARS]).replace('# OMS GraphQL caps one query at 1000 cost',
                '# ' + 't' * KnowledgeEntryFile.MAX_MEDIUM_CHARS), ORIGIN)
    }
}
