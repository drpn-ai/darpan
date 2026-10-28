package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * DAR-BE-151. A compare's diff row in its order's world: which kind of check (an R1 order line, an R2 return), its key,
 * its bucket, and what it is about. netsuiteHasOrderLine: R1's NetSuite side has a line for the order.
 */
@CompileStatic
@ToString(includes = ["kind", "key"], includePackage = false)
class Finding {
    static final String ORDER_LINE = "orderLine"
    static final String RETURN = "return"

    String key
    String kind
    String bucket
    Order order
    Return ret
    boolean netsuiteHasOrderLine
}
