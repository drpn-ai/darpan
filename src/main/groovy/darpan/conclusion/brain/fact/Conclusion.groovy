package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor

/** DAR-BE-151. A statement's rule concluded a finding with a subject's best cause. */
@CompileStatic
@TupleConstructor
class Conclusion {
    Finding finding
    Cause cause

    static Conclusion of(Finding finding, Cause cause) { return new Conclusion(finding, cause) }
}
