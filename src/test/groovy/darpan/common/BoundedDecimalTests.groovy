package darpan.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-OPS-029 (OC219, security). A record's number is written as text without expanding it: plain text spells a decimal's
 * digits out, so 1E999999999 would be a billion characters, and stripping 100E2147483647's zeros overflows an int scale.
 */
@Timeout(10)
class BoundedDecimalTests {

    @Test
    void aNumberAnIntScaleCannotStripIsNotANumberToWriteAndThrowsNothing() {
        assertNull(BoundedDecimal.of(new BigDecimal("100E2147483647")))
        assertEquals("1.00E+2147483649", BoundedDecimal.plainText(new BigDecimal("100E2147483647")))
    }

    // Red by construction, not run red: before OC219 each of these would allocate about 2 GB on a shared test lane.
    @Test
    void aHugeExponentIsNeverExpandedIntoDigits() {
        assertNull(BoundedDecimal.of(new BigDecimal("1E999999999")))
        assertNull(BoundedDecimal.of("1E-999999999"))
        assertEquals("1E+999999999", BoundedDecimal.plainText(new BigDecimal("1E999999999")))
        assertEquals("1E+999999999", FactText.flat([a: new BigDecimal("1E999999999")]).a)
        assertNull(darpan.audit.AuditRecordFacts.recordFacts([grandTotal: "1E999999999"], "order").orderTotal, "no amount, not a billion digits")
        assertEquals("1E+999999999", darpan.audit.AuditRecordFacts.amountText(new BigDecimal("1E999999999"), "USD"))
    }

    @Test
    void anOrdinaryNumberReadsAsBefore() {
        assertEquals("7", BoundedDecimal.plainText(new BigDecimal("7.0")))
        assertEquals("1.5", BoundedDecimal.plainText(new BigDecimal("1.50")))
        assertEquals("12345678901", BoundedDecimal.plainText(12345678901.0d))
        assertEquals("24", FactText.flat([a: new BigDecimal("24.00")]).a)
        assertEquals("NaN", BoundedDecimal.plainText(Double.NaN))
        assertEquals("371.54", darpan.audit.AuditRecordFacts.recordFacts([grandTotal: "371.54"], "order").orderTotal.amount)
    }

    @Test
    void anOverflowingKeyOrFactNeverAbortsItsPass() {
        assertEquals("1.00E+2147483649", FactText.flat([a: new BigDecimal("100E2147483647")]).a)
        assertEquals("1.00E+2147483649",
                darpan.reconciliation.conclusion.ConclusionKeySupport.compareKey([id: new BigDecimal("100E2147483647")], ["id"], null))
    }
}
