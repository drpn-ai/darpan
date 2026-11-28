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

    // DAR-OPS-029 S03a (lens E4): Spark's trim (CompareDatasetSupport.applyIdNormalizer) takes off spaces only, so a tab or
    // line break at a key's edge stays in the compare's key and must stay in the key rebuilt here, or the evidence misses it.
    @Test
    void trimsOnlySpacesLikeSpark() {
        assertEquals("M1\t\u001F01", ConclusionKeySupport.compareKey([o: " M1\t ", u: "01 "], ["o", "u"], null))
        assertEquals("M2\n", ConclusionKeySupport.compareKey([o: "M2\n"], ["o"], null))
        assertNull(ConclusionKeySupport.compareKey([o: "   "], ["o"], null))
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
