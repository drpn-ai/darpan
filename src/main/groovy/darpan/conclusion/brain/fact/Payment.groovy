package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.ToString

/** DAR-BE-151. One OPP row of an order: its tender, its state without the PAYMENT_ prefix (SETTLED, REFUNDED, ...), its amount. */
@CompileStatic
@ToString(includes = ["tender", "state", "amount"], includePackage = false)
class Payment {
    Order order
    String tender
    String state
    BigDecimal amount

    /** The sum of payment rows' amounts (a rule only calls it on rows it has checked carry one). */
    static BigDecimal sum(Collection rows) {
        BigDecimal total = BigDecimal.ZERO
        (rows ?: []).each { Object p -> total = total.add(((Payment) p).amount) }
        return total
    }
}
