package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

/** DAR-BE-151. A rule wants a question answered about a subject; the ask planner decides whether it is asked. */
@CompileStatic
@TupleConstructor
@EqualsAndHashCode
class Ask {
    String question
    Order subject
}
