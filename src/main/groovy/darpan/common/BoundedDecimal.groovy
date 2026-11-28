package darpan.common

/**
 * DAR-OPS-029 (OC219, security). A number from a record, written as text without expanding it. A decimal's plain text spells
 * its digits out, so 1E999999999 is a billion characters, and stripping 100E2147483647's trailing zeros overflows its int
 * scale (ArithmeticException). Every place that writes or scales a record's number goes through here. A leaf: it imports
 * nothing, so core, judge, audit and conclusions share it.
 */
class BoundedDecimal {

    /** Digits a number may have either side of the point to be written or scaled: past 2^53 a double has no exact digits. */
    static final int MAX_DIGITS = 16

    /**
     * raw as a BigDecimal, trailing zeros stripped, when it is a number of at most MAX_DIGITS digits either side of the point;
     * null otherwise (not a number, NaN or Infinity, an exponent too large to strip or to write).
     */
    static BigDecimal of(Object raw) {
        if (raw == null) return null
        BigDecimal value
        try {
            value = (raw instanceof BigDecimal ? (BigDecimal) raw : new BigDecimal(raw.toString().trim())).stripTrailingZeros()
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null
        }
        long integerDigits = (long) value.precision() - (long) value.scale()
        return integerDigits > MAX_DIGITS || value.scale() > MAX_DIGITS || value.scale() < -MAX_DIGITS ? null : value
    }

    /** raw's plain text when bounded (integral as a whole number, otherwise its shortest exact decimal); else raw's own text. */
    static String plainText(Object raw) {
        BigDecimal value = of(raw)
        return value != null ? value.toPlainString() : raw?.toString()
    }
}
