package darpan.facade.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class FacadeSupportTests {
    @Test
    void normalizeTrimsValuesAndPreservesNull() {
        assertEquals("KREWE_SHOPIFY", FacadeSupport.normalize(" KREWE_SHOPIFY "))
        assertEquals("42", FacadeSupport.normalize(42))
        assertNull(FacadeSupport.normalize(null))
    }

    @Test
    void normalizeIntAcceptsNumbersTextAndDefaultValues() {
        assertEquals(0, FacadeSupport.normalizeInt(0, 0))
        assertEquals(25, FacadeSupport.normalizeInt(" 25 ", 0))
        assertEquals(7, FacadeSupport.normalizeInt(null, 7))
        assertEquals(7, FacadeSupport.normalizeInt("not-a-number", 7))
    }

    @Test
    void normalizeBoolAcceptsCommonIndicatorsAndDefaultValues() {
        assertTrue(FacadeSupport.normalizeBool("Y", false))
        assertTrue(FacadeSupport.normalizeBool(" true ", false))
        assertFalse(FacadeSupport.normalizeBool("N", true))
        assertFalse(FacadeSupport.normalizeBool("off", true))
        assertTrue(FacadeSupport.normalizeBool(null, true))
        assertFalse(FacadeSupport.normalizeBool("unknown", false))
    }
}
