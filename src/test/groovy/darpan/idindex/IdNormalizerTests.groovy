package darpan.idindex

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: one normalisation for writing and searching, so every spelling a person pastes finds the same row. */
class IdNormalizerTests {

    @Test
    void "the spellings of one id normalise to the same value"() {
        assertEquals("GOR123", IdNormalizer.normalize("#GOR123"))
        assertEquals("GOR123", IdNormalizer.normalize("gor123"))
        assertEquals("GOR123", IdNormalizer.normalize("  #gor123 "))
    }

    @Test
    void "a Shopify gid is reduced to its number"() {
        assertEquals("5551234", IdNormalizer.normalize("gid://shopify/Order/5551234"))
        assertEquals("77", IdNormalizer.normalize("gid://shopify/Refund/77?x=1"))
        assertEquals("5551234", IdNormalizer.normalize(5551234L))
    }

    @Test
    void "a line key keeps its separator"() {
        assertEquals("M1213101/01", IdNormalizer.normalize("  M1213101/01 "))
    }

    @Test
    void "blank, null and oversized values are not ids"() {
        assertNull(IdNormalizer.normalize(""))
        assertNull(IdNormalizer.normalize("   "))
        assertNull(IdNormalizer.normalize("#"))
        assertNull(IdNormalizer.normalize(null))
        assertNull(IdNormalizer.normalize("x" * 300))
    }

    @Test
    void "only one leading hash is dropped"() {
        assertEquals("#12", IdNormalizer.normalize("##12"))
    }
}
