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

    // DAR-OPS-029 (lens E4, with S01b's OC140, cc7b59ce): the compare trims every leading and trailing character up to U+0020
    // (tab, CR, LF and the rest, as String.trim), so the key rebuilt here does too, or the evidence misses it.
    @Test
    void trimsEdgeWhitespaceLikeTheCompare() {
        assertEquals("K1\u001F01", ConclusionKeySupport.compareKey([o: " K1\t", u: "01 "], ["o", "u"], null))
        assertEquals("K2", ConclusionKeySupport.compareKey([o: "K2\r"], ["o"], null))
        assertEquals("M2", ConclusionKeySupport.compareKey([o: "M2\n"], ["o"], null))
        assertNull(ConclusionKeySupport.compareKey([o: " \t "], ["o"], null))
    }

    // DAR-OPS-029 (lens E4, with S01b's cf60f0e8): a numeric key reads the same in both passes. Spark writes a double key
    // integral below 1e15 as a whole number and any other as its shortest decimal; a decimal parsed here (BigDecimal from a
    // JSON literal, or a Double/Float) is written the same way. A string, an Integer and a Long stay exactly as they are.
    @Test
    void aDecimalKeyReadsAsSparkWritesIt() {
        assertEquals("7", ConclusionKeySupport.compareKey([id: new BigDecimal("7.0")], ["id"], null))
        assertEquals("1.5", ConclusionKeySupport.compareKey([id: new BigDecimal("1.50")], ["id"], null))
        assertEquals("12345678901", ConclusionKeySupport.compareKey([id: 12345678901.0d], ["id"], null))
        assertEquals("2.25", ConclusionKeySupport.compareKey([id: 2.25f], ["id"], null))
        assertEquals("7.0", ConclusionKeySupport.compareKey([id: "7.0"], ["id"], null), "a string key is its text")
        assertEquals("7\u001F12345678901", ConclusionKeySupport.compareKey([o: 7, u: 12345678901L], ["o", "u"], null))
        // Security review: an exponent is never expanded into digits; past 16 digits a key keeps its own text.
        assertEquals("1E+999999999", ConclusionKeySupport.compareKey([id: new BigDecimal("1E999999999")], ["id"], null))
        assertEquals("1E-999999999", ConclusionKeySupport.compareKey([id: new BigDecimal("1E-999999999")], ["id"], null))
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
