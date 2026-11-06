package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Answered
import darpan.conclusion.brain.fact.Finding
import darpan.conclusion.brain.fact.Item
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.Payment
import darpan.conclusion.brain.fact.Product
import darpan.conclusion.brain.fact.ReplacementReturn
import darpan.conclusion.brain.fact.Return

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-151. A finding as RunConclusionSupport builds it ([key, bucket, sides]) to the brain's typed facts, plus the
 * answers already known for its order. One Order per order id within a WORLD (design D3): a second finding about an order
 * reuses it, adding only what it knows (its NetSuite id, its total), and neither it nor its payments are returned again,
 * since they are already in the session. Without a world, the call is its own. An order is never invented for a record
 * that names none. Pure: no Moqui, no Drools.
 */
class BrainFactAdapter {

    static final String ORDER_PAYMENTS = "orderPayments"
    /** DAR-BE-186: is an item's product known to NetSuite (its NETSUITE_PRODUCT_ID in HotWax)? Asked by product id. */
    static final String PRODUCT_NETSUITE_ID = "productNetsuiteId"
    /** DAR-BE-192: the return a replacement order was made for (HotWax), asked by the replacement's order id. */
    static final String REPLACEMENT_RETURN = "replacementReturn"
    /** DAR-BE-192: that return's NetSuite RMA and credit memo, asked by return id. */
    static final String RETURN_RMA = "returnRma"
    static final String RETURN_MEMO = "returnMemo"
    /** DAR-BE-151 (Aditi 2026-11-06): an exchange's original order's NetSuite sales order, asked by its HotWax order id. */
    static final String ORIGINAL_IN_NETSUITE = "originalInNetsuite"

    /**
     * The record fields facts() reads, per RuleSetCompareScope.conclusionBrain and side. The extract must keep them, or a
     * projection strips them (an R2 return's orderId) and the brain asks and concludes nothing.
     */
    static List<String> recordFieldsFor(Object conclusionBrain, String fileSide) {
        if (fileSide != "FILE_1") return []
        switch (conclusionBrain?.toString()?.trim()) {
            case "ORDER_LINE": return ["omsOrderId", "orderGrandTotal", "productId", "sku", "orderName"]
            // DAR-BE-192: an R7 order, its name (a replacement's EXC- / WTY-) and its HotWax status.
            case "ORDER": return ["orderId", "orderName", "grandTotal", "statusId"]
            case "RETURN": return ["returnId", "orderId", "orderNetsuiteId", "orderGrandTotal"]
            default: return []
        }
    }

    /**
     * world: one Order per order id and, DAR-BE-186, one Product per product id (under "product|" + id) and one Item per
     * order and product, so a second finding reuses them. productAnswers: productNetsuiteId answers by product id.
     */
    static List<Object> facts(String kind, Map finding, Map<String, Map> answersByOrderId, Map<String, ?> world = null,
                              Map<String, Map> productAnswers = null, Map<String, Map<String, Map>> moreAnswers = null) {
        Map sides = (Map) (finding?.sides ?: [:])
        Map record = (Map) (((Map) (sides.FILE_1 ?: [:])).record ?: [:])
        List<Object> facts = []
        Map<String, Object> orders = world != null ? (Map<String, Object>) world : [:]
        Closure<Order> orderFor = { String orderId, Object total, String netsuiteOrderId, String name = null, String statusId = null ->
            if (!orderId) return null
            Order order = (Order) orders.get(orderId)
            if (order != null) {
                if (order.netsuiteOrderId == null) order.netsuiteOrderId = netsuiteOrderId
                if (order.total == null) order.total = number(total)
                if (order.name == null) order.name = name
                if (order.statusId == null) order.statusId = statusId
            } else {
                order = new Order(orderId: orderId, total: number(total), netsuiteOrderId: netsuiteOrderId, name: name, statusId: statusId)
                orders.put(orderId, order)
                facts.add(order)
                addAnswer(facts, order, answersByOrderId)
                addReplacementReturn(facts, order, moreAnswers, orders)
            }
            return order
        }

        Finding f = new Finding(key: normalize(finding?.key), kind: kind, bucket: normalize(finding?.bucket))
        if (kind == Finding.ORDER_LINE) {
            f.order = orderFor(normalize(record.omsOrderId), record.orderGrandTotal, null, normalize(record.orderName))
            f.netsuiteHasOrderLine = ((Map) (sides.FILE_2 ?: [:])).firstFieldPresence != "ABSENT"
            String productId = normalize(record.productId)
            if (f.order != null && productId) {
                Product product = (Product) orders.get("product|" + productId)
                if (product == null) {
                    product = new Product(productId: productId, sku: normalize(record.sku))
                    orders.put("product|" + productId, product)
                    facts.add(product)
                    addProductAnswer(facts, product, productAnswers)
                } else if (product.sku == null) {
                    product.sku = normalize(record.sku)
                }
                String itemKey = "item|${f.order.orderId}|${productId}".toString()
                if (!orders.containsKey(itemKey)) {
                    Item item = new Item(f.order, product)
                    orders.put(itemKey, item)
                    facts.add(item)
                }
            }
        } else if (kind == Finding.ORDER) {
            // DAR-BE-192: an R7 order: HotWax approved it and NetSuite has no sales order for it (MISSING_FROM_FILE_2).
            f.order = orderFor(normalize(record.orderId), record.grandTotal, null, normalize(record.orderName), normalize(record.statusId))
        } else if (kind == Finding.RETURN) {
            Order order = orderFor(normalize(record.orderId), record.orderGrandTotal, normalize(record.orderNetsuiteId))
            if (order != null) {
                f.ret = new Return(returnId: normalize(record.returnId), order: order)
                f.order = order
                facts.add(f.ret)
            }
        }
        facts.add(0, f)
        return facts
    }

    /**
     * DAR-BE-192: an answered replacement carries the return it was made for (or none: asked, nothing found), and that return
     * what NetSuite answered for it: its RMA and its credit memo (none: asked, NetSuite has none).
     */
    private static void addReplacementReturn(List<Object> facts, Order order, Map<String, Map<String, Map>> more,
                                             Map<String, Object> orders) {
        Map<String, Map> byOrder = more?.get(REPLACEMENT_RETURN)
        if (byOrder == null || !byOrder.containsKey(order.orderId)) return
        facts.add(new Answered(REPLACEMENT_RETURN, order))
        Map a = byOrder.get(order.orderId)
        String returnId = normalize(a?.returnId)
        if (!returnId) return
        ReplacementReturn r = new ReplacementReturn(replacement: order, returnId: returnId, statusId: normalize(a.statusId),
                channel: normalize(a.returnChannelEnumId), credit: number(a.returnCredit), entered: day(a.entryDate))
        r.original = originalOf(facts, normalize(a.orderId), order, more, orders)
        Map<String, Map> rmas = more.get(RETURN_RMA)
        if (rmas != null && rmas.containsKey(returnId)) {
            Map x = rmas.get(returnId)
            r.rmaTranId = normalize(x?.tranId)
            r.rmaStatus = normalize(x?.status)
            facts.add(new Answered(RETURN_RMA, r))
        }
        Map<String, Map> memos = more.get(RETURN_MEMO)
        if (memos != null && memos.containsKey(returnId)) {
            Map x = memos.get(returnId)
            r.memoTranId = normalize(x?.tranId)
            r.memoAmount = number(x?.amount)
            facts.add(new Answered(RETURN_MEMO, r))
        }
        facts.add(r)
    }

    /**
     * DAR-BE-151: the order a replacement's return was raised against (one Order per id in the world, so an original that
     * is itself a finding is the same fact) and, once asked, its NetSuite sales order (none: asked, NetSuite has none).
     */
    private static Order originalOf(List<Object> facts, String originalId, Order replacement, Map<String, Map<String, Map>> more,
                                    Map<String, Object> orders) {
        if (!originalId || originalId == replacement.orderId) return null
        Order original = (Order) orders.get(originalId)
        if (original == null) {
            original = new Order(orderId: originalId)
            orders.put(originalId, original)
            facts.add(original)
        }
        Map<String, Map> answers = more?.get(ORIGINAL_IN_NETSUITE)
        if (answers != null && answers.containsKey(originalId) && !facts.any { it instanceof Answered &&
                ((Answered) it).question == ORIGINAL_IN_NETSUITE && ((Answered) it).subject == original }) {
            Map so = answers.get(originalId)
            original.netsuiteTranId = normalize(so?.tranId)
            original.netsuiteStatus = normalize(so?.status)
            facts.add(new Answered(ORIGINAL_IN_NETSUITE, original))
        }
        return original
    }

    /** Epoch millis (or text) as YYYY-MM-DD in UTC; null when it is neither. */
    private static String day(Object v) {
        if (v instanceof Number) return java.time.Instant.ofEpochMilli(((Number) v).longValue()).toString().substring(0, 10)
        String t = normalize(v)
        return t && t ==~ /\d{4}-\d{2}-\d{2}.*/ ? t.substring(0, 10) : (t ==~ /\d{10,}/ ? day(t as long) : null)
    }

    /** DAR-BE-186: an answered product carries its NETSUITE_PRODUCT_ID, or none (asked, nothing found or no id). */
    private static void addProductAnswer(List<Object> facts, Product product, Map<String, Map> productAnswers) {
        if (productAnswers == null || !productAnswers.containsKey(product.productId)) return
        Map answer = productAnswers.get(product.productId)
        product.netsuiteProductId = normalize(answer?.netsuiteProductId)
        if (product.sku == null) product.sku = normalize(answer?.internalName)
        facts.add(new Answered(PRODUCT_NETSUITE_ID, product))
    }

    /** Amounts lose trailing zeros so "181" and "181.00" are the same amount to a rule. */
    /** An answer whose read filled its slots may have cut a row off: it marks the order answered and adds no payments. */
    private static void addAnswer(List<Object> facts, Order order, Map<String, Map> answersByOrderId) {
        if (answersByOrderId == null || !answersByOrderId.containsKey(order.orderId)) return
        facts.add(new Answered(ORDER_PAYMENTS, order))
        Map answer = answersByOrderId.get(order.orderId)
        Object rows = answer?.paymentPreferences
        if (!(rows instanceof List) || answer.paymentPreferenceSlotsFull == true) return
        if (order.total == null) order.total = number(answer.grandTotal)
        order.paymentsComplete = true
        ((List) rows).each { Object row ->
            Map r = (Map) row
            String state = normalize(r?.statusId)
            facts.add(new Payment(order: order, tender: normalize(r?.paymentMethodTypeId),
                    state: state?.startsWith("PAYMENT_") ? state.substring(8) : state, amount: number(r?.maxAmount)?.stripTrailingZeros()))
        }
    }

    static BigDecimal number(Object value) {
        if (value == null) return null
        if (value instanceof BigDecimal) return (BigDecimal) value
        try { return new BigDecimal(value.toString().trim()) } catch (NumberFormatException ignored) { return null }
    }
}
