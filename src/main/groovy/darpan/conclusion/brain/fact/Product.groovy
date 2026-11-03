package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * DAR-BE-186. An OMS product, one per product id in a world: its SKU as the item read gave it and, once the
 * productNetsuiteId question is answered, its NETSUITE_PRODUCT_ID (null: HotWax has none for it).
 */
@CompileStatic
@EqualsAndHashCode(includes = ["productId"])
@ToString(includes = ["productId", "sku"], includePackage = false)
class Product {
    String productId
    String sku
    String netsuiteProductId
}
