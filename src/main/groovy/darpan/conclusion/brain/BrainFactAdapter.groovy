package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Answered
import darpan.conclusion.brain.fact.Finding
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.Payment
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

    /**
     * The record fields facts() reads, per RuleSetCompareScope.conclusionBrain and side. The extract must keep them, or a
     * projection strips them (an R2 return's orderId) and the brain asks and concludes nothing.
     */
    static List<String> recordFieldsFor(Object conclusionBrain, String fileSide) {
        if (fileSide != "FILE_1") return []
        switch (conclusionBrain?.toString()?.trim()) {
            case "ORDER_LINE": return ["omsOrderId", "orderGrandTotal"]
            case "RETURN": return ["returnId", "orderId", "orderNetsuiteId", "orderGrandTotal"]
            default: return []
        }
    }

    static List<Object> facts(String kind, Map finding, Map<String, Map> answersByOrderId, Map<String, Order> world = null) {
        Map sides = (Map) (finding?.sides ?: [:])
        Map record = (Map) (((Map) (sides.FILE_1 ?: [:])).record ?: [:])
        List<Object> facts = []
        Map<String, Order> orders = world != null ? world : [:]
        Closure<Order> orderFor = { String orderId, Object total, String netsuiteOrderId ->
            if (!orderId) return null
            Order order = orders.get(orderId)
            if (order != null) {
                if (order.netsuiteOrderId == null) order.netsuiteOrderId = netsuiteOrderId
                if (order.total == null) order.total = number(total)
            } else {
                order = new Order(orderId: orderId, total: number(total), netsuiteOrderId: netsuiteOrderId)
                orders.put(orderId, order)
                facts.add(order)
                addAnswer(facts, order, answersByOrderId)
            }
            return order
        }

        Finding f = new Finding(key: normalize(finding?.key), kind: kind, bucket: normalize(finding?.bucket))
        if (kind == Finding.ORDER_LINE) {
            f.order = orderFor(normalize(record.omsOrderId), record.orderGrandTotal, null)
            f.netsuiteHasOrderLine = ((Map) (sides.FILE_2 ?: [:])).firstFieldPresence != "ABSENT"
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
