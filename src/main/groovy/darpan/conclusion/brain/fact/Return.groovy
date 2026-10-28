package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/** DAR-BE-151. An OMS return and the order it came from. */
@CompileStatic
@EqualsAndHashCode(includes = ["returnId"])
@ToString(includes = ["returnId"], includePackage = false)
class Return {
    String returnId
    Order order
}
