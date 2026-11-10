package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * DAR-BE-204. A Shopify order a check starts from (Dolls Kill: every Shopify order is in NetSuite), one per Shopify id in a
 * world, as the Shopify orders read gave it: financial status, source, original and current total, subtotal after
 * discounts. order: its HotWax order, once the omsOrderByShopifyId question is answered (null after the answer: HotWax
 * has no order with this Shopify id).
 */
@CompileStatic
@EqualsAndHashCode(includes = ["shopifyId"])
@ToString(includes = ["shopifyId", "name"], includePackage = false)
class ShopifyOrder {
    String shopifyId
    String name
    /** displayFinancialStatus: PAID, PENDING, EXPIRED, PARTIALLY_REFUNDED, ... */
    String financialStatus
    String sourceName
    BigDecimal total
    BigDecimal currentTotal
    BigDecimal subtotal
    /** hasPaymentTransaction (DAR-BE-064): Y when Shopify holds a successful sale, capture or authorization. */
    String hasPaymentTransaction
    Order order
}
