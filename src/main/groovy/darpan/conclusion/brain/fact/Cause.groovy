package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * DAR-BE-151. Why something about ONE subject (an order, a line, a return) is as it is. Written once per cause; every
 * statement reaches it through its subject. because: the facts that established it, in order.
 */
@CompileStatic
@EqualsAndHashCode(includes = ["subject", "code"])
@ToString(includes = ["code", "rank"], includePackage = false)
class Cause {
    Object subject
    String code
    int rank
    boolean expected
    List<Object> because = []

    static Cause expected(Object subject, String code, int rank, Object... because) {
        return new Cause(subject: subject, code: code, rank: rank, expected: true, because: (because as List<Object>))
    }

    static Cause fix(Object subject, String code, int rank, Object... because) {
        return new Cause(subject: subject, code: code, rank: rank, expected: false, because: (because as List<Object>))
    }
}
