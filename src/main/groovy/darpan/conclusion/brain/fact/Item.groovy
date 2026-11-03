package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.TupleConstructor

/** DAR-BE-186. An order carries an item of a product (once per order and product in a world). */
@CompileStatic
@TupleConstructor
@EqualsAndHashCode
class Item {
    Order order
    Product product
}
