package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/** DAR-BE-151. An OMS order, one per order id in a session. paymentsComplete: its payment rows were read whole. */
@CompileStatic
@EqualsAndHashCode(includes = ["orderId"])
@ToString(includes = ["orderId"], includePackage = false)
class Order {
    String orderId
    /** Null when the record had no total or one that is not a number. */
    BigDecimal total
    String netsuiteOrderId
    boolean paymentsComplete
}
