package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

/** DAR-BE-151. The highest-ranked cause about a subject, derived by one generic rule. */
@CompileStatic
@TupleConstructor
@EqualsAndHashCode
class BestCause {
    Object subject
    Cause cause
}
