package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

/** DAR-BE-151. A question was answered about a subject (also when nothing was found), so it is not asked again. */
@CompileStatic
@TupleConstructor
@EqualsAndHashCode
class Answered {
    String question
    Order subject
}
