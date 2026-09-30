package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/** DAR-UI-044. The conclude pass must key records exactly as the Spark compare did, or evidence misses. */
class ConclusionKeySupportTests {

    @Test
    void trimsAndJoinsLikeSpark() {
        assertEquals("M1\u001F01", ConclusionKeySupport.compareKey([omsOrderId: " M1 ", orderItemSeqId: "01"],
                ["omsOrderId", "orderItemSeqId"], null))
    }

    @Test
    void appliesTheSourceNormalizer() {
        assertEquals("16445", ConclusionKeySupport.compareKey([id: "gid://shopify/LineItem/16445"], ["id"], "SHOPIFY_GID_TAIL"))
        assertEquals("abc", ConclusionKeySupport.compareKey([id: "ABC"], ["id"], "CASE_FOLD"))
    }

    @Test
    void numbersKeyAsTheirStringForm() {
        assertEquals("7\u001F1", ConclusionKeySupport.compareKey([o: 7, u: 1], ["o", "u"], null))
    }

    @Test
    void storedJsonPathExpressionsReduceToTheRecordField() {
        assertEquals("M1", ConclusionKeySupport.compareKey([omsOrderId: "M1"], ['$.records[*].omsOrderId'], null))
    }

    @Test
    void aBlankFieldHasNoKey() {
        assertNull(ConclusionKeySupport.compareKey([a: "x", b: " "], ["a", "b"], null))
        assertNull(ConclusionKeySupport.compareKey([a: "x"], ["a", "b"], null))
    }

    @Test
    void firstFieldIsTheOrderPart() {
        assertEquals("M1", ConclusionKeySupport.firstField("M1\u001FNONE"))
        assertEquals("M1", ConclusionKeySupport.firstField("M1"))
    }

    // Final review I1: Spark keys a composite field by its OWN inline normalizer, and a legacy key by
    // idValueNormalizer ?: inline, both through resolveIdNormalizer's aliases.
    @Test
    void anInlineNormalizerKeysTheFieldLikeSpark() {
        assertEquals("123\u001F1", ConclusionKeySupport.compareKey(
                [lineItemId: "gid://shopify/LineItem/123", unitOrdinal: 1], ["lineItemId|SHOPIFY_GID_TAIL", "unitOrdinal"], null))
    }

    @Test
    void theConfiguredNormalizerWinsAndAcceptsSparkAliases() {
        assertEquals("abc", ConclusionKeySupport.compareKey([id: "ABC"], ["id|SHOPIFY_GID_TAIL"], "case-fold"))
        assertEquals("abc", ConclusionKeySupport.compareKey([id: "ABC"], ["id"], "LOWER"))
    }

    @Test
    void onlyAPlainTopLevelFieldIsIndexable() {
        assertEquals("omsOrderId", ConclusionKeySupport.plainField('$.records[*].omsOrderId|CASE_FOLD'))
        assertEquals("omsOrderId", ConclusionKeySupport.plainField("omsOrderId"))
        assertNull(ConclusionKeySupport.plainField("node.id"))
        assertNull(ConclusionKeySupport.plainField('$.lines[*].id'))
    }
}
