package darpan.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class ValueSupportTests {
    @Test
    void normalizeTrimsValuesAndPreservesNullOrBlank() {
        assertEquals("KREWE_SHOPIFY", ValueSupport.normalize(" KREWE_SHOPIFY "))
        assertEquals("42", ValueSupport.normalize(42))
        assertEquals("", ValueSupport.normalize("   "))
        assertNull(ValueSupport.normalize(null))
    }

    @Test
    void normalizeBlankToNullCollapsesWhitespaceOnlyText() {
        assertEquals("orders", ValueSupport.normalizeBlankToNull(" orders "))
        assertEquals("0", ValueSupport.normalizeBlankToNull(0))
        assertNull(ValueSupport.normalizeBlankToNull("   "))
        assertNull(ValueSupport.normalizeBlankToNull(null))
    }

    @Test
    void normalizedCaseUsesTrimmedTextAndRootLocale() {
        assertEquals("orders", ValueSupport.normalizeLower(" Orders "))
        assertEquals("ORDERS", ValueSupport.normalizeUpper(" Orders "))
        assertNull(ValueSupport.normalizeLower(null))
        assertEquals("", ValueSupport.normalizeUpper("   "))
    }

    @Test
    void stringifyPreservesUntrimmedTextAndNull() {
        assertEquals(" orders ", ValueSupport.stringify(" orders "))
        assertEquals("10", ValueSupport.stringify(10))
        assertNull(ValueSupport.stringify(null))
    }

    @Test
    void valuesDifferUsesObjectEquality() {
        assertFalse(ValueSupport.valuesDiffer("10", "10"))
        assertTrue(ValueSupport.valuesDiffer("10", 10))
        assertTrue(ValueSupport.valuesDiffer(null, ""))
        assertFalse(ValueSupport.valuesDiffer(null, null))
    }

    @Test
    void numericNormalizersHandleNumbersTextAndDefaults() {
        assertEquals(25, ValueSupport.normalizeInt(" 25 ", 0))
        assertEquals(10, ValueSupport.normalizeInt(10.8G, 0))
        assertEquals(7, ValueSupport.normalizeInt("not-a-number", 7))
        assertEquals(200, ValueSupport.boundedInt("999", 20, 1, 200))
        assertEquals(1, ValueSupport.boundedInt("-5", 20, 1, 200))
        assertEquals(123L, ValueSupport.normalizeLong(" 123 ", null))
        assertNull(ValueSupport.normalizeLong("nope", null))
    }

    @Test
    void boolNormalizerHandlesCommonIndicatorsAndDefaults() {
        assertTrue(ValueSupport.normalizeBool("Y", false))
        assertTrue(ValueSupport.normalizeBool(" true ", false))
        assertFalse(ValueSupport.normalizeBool("off", true))
        assertTrue(ValueSupport.normalizeBool("unknown", true))
    }

    @Test
    void fileTokenSanitizerReplacesInvalidCharactersAndUsesFallback() {
        assertEquals("orders_2026.csv", ValueSupport.sanitizeFileToken(" orders 2026.csv ", "file"))
        assertEquals("orders__.json", ValueSupport.sanitizeFileToken("orders:?.json", "file"))
        assertEquals("file", ValueSupport.sanitizeFileToken("   ", "file"))
        assertEquals("file", ValueSupport.sanitizeFileToken(null, "file"))
    }

    @Test
    void pathFileNameSanitizerUsesLastPathPart() {
        assertEquals("orders_2026.csv", ValueSupport.sanitizePathFileName("../tmp/orders 2026.csv", "file"))
        assertEquals("file", ValueSupport.sanitizePathFileName("", "file"))
    }

    @Test
    void fileNameFromPathReturnsLastPathPartWithoutSanitizing() {
        assertEquals("orders 2026.csv", ValueSupport.fileNameFromPath("../tmp/orders 2026.csv"))
        assertNull(ValueSupport.fileNameFromPath(" "))
    }

    @Test
    void displayNameSanitizerAllowsSpacesAndUsesFallback() {
        assertEquals("Rule 1 _draft_", ValueSupport.sanitizeDisplayName(" Rule 1 <draft> ", "Unnamed Rule"))
        assertEquals("Unnamed Rule", ValueSupport.sanitizeDisplayName("   ", "Unnamed Rule"))
    }

    @Test
    void recordReadersHandleMapsAndEntityLikeObjects() {
        assertEquals("KREWE", ValueSupport.readString([tenant: " KREWE "], "tenant"))
        assertEquals("value", ValueSupport.readString(new Expando(field: " value "), "field"))
        assertNull(ValueSupport.readField(null, "field"))
        assertEquals("", ValueSupport.readString([field: " "], "field"))
    }
}
