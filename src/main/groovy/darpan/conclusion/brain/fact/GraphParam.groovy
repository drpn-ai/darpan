package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

/** DAR-BE-229. A tunable value the rules read instead of a literal: a number (money tolerance, share) or a text (name prefixes). */
@CompileStatic
@TupleConstructor
@EqualsAndHashCode
class GraphParam {
    String name
    BigDecimal number
    String text

    /** The case-blind pattern for an order name starting with one of the "|"-separated prefixes in text. */
    String getPrefixRegex() { "(?i)(" + text + ")-.+" }
}
