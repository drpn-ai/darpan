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
    /** An Order (orderPayments, replacementReturn), a Product (productNetsuiteId, DAR-BE-186) or a ReplacementReturn (returnRma, returnMemo, DAR-BE-192). */
    Object subject
    /** DAR-BE-186: the order whose best cause decides whether asking can still change anything; null: the subject. */
    Order forOrder
}
