package darpan.audit

import darpan.common.BoundedDecimal
import darpan.reconciliation.core.ReconciliationServices

import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A finding's records as the audit-run JSON shows them (darpan.audit.report/v1 and darpan.audit.finding/v1): keyed by
 * root system and record type, each with the few facts it takes to see what happened, read from the record's own
 * stored data (AuditFindingRecord.recordData), never from a lookup. Pure: no reads, no Moqui.
 *
 * DAR-OPS-029 S10: split out of AuditReportAssembler (records, facts, money, system keys).
 */
class AuditRecordFacts {

    static final Map<String, String> SYSTEM_KEYS = [OMS: "OMS", NETSUITE: "NetSuite", SHOPIFY: "Shopify"].asImmutable()

    /** The JSON key for a ROOT system enum (OMS, NETSUITE, SHOPIFY); an unmapped root uses its enumId. */
    static String systemKey(String rootEnumId) {
        return SYSTEM_KEYS[rootEnumId] ?: rootEnumId
    }

    /**
     * A record key as people and agents read it. A composite compare key (order id + item seq id) is joined by an
     * invisible unit separator; it reads as order/item. The ledger keeps the raw key, which closing matches against the
     * examined keys.
     */
    static String displayKey(String key) {
        return key?.replace(ReconciliationServices.COMPOSITE_KEY_DELIMITER, "/")
    }

    /**
     * records.<System>.<recordType>: [ {id, internalId, status} ], systems and types in stored order. DAR-UI-080: plus what
     * it takes to see what happened, from the record's data (recordFacts), when known; and, in a finding's own document
     * (withData), the data itself: every field the reads gave.
     */
    static Map recordsJson(List<Map> recordRows, boolean withData = false, ZoneId zone = ZoneId.of("UTC")) {
        Map<String, Map<String, List>> out = new LinkedHashMap<>()
        recordRows.each { Map r ->
            Map<String, List> byType = out.computeIfAbsent(systemKey(r.systemEnumId as String)) { new LinkedHashMap<>() }
            Map data = recordData(r.recordData)
            Map facts = recordFacts(data, r.recordType as String, zone)
            Object factStatus = facts.remove("status")
            Map record = [id: r.recordId, internalId: r.internalId ?: null, status: r.recordStatus ?: factStatus ?: null]
            record.putAll(facts.findAll { it.value != null })
            if (withData && data) record.data = data
            byType.computeIfAbsent(r.recordType as String) { [] } << record
        }
        return out
    }

    /** AuditFindingRecord.recordData: the record's fields as JSON; anything else reads as none. */
    static Map recordData(Object stored) {
        if (stored instanceof Map) return (Map) stored
        String text = stored?.toString()?.trim()
        if (!text) return null
        try {
            Object parsed = new groovy.json.JsonSlurper().parseText(text)
            return parsed instanceof Map ? (Map) parsed : null
        } catch (groovy.json.JsonException ignored) {
            return null
        }
    }

    /**
     * DAR-BE-372 rule 2. Every read field that looks like a status, an amount or a date, by what the finding card makes of
     * it. recordFacts reads the fed ones; `notOnCard` ones are known and left off on purpose. FindingCardRulesTests fails on
     * a field in a sample read that is in neither, so a new read field is placed before a card can drop it.
     */
    static final Map<String, List<String>> CARD_FIELDS = [
            status      : ["displayFulfillmentStatus", "statusId", "lineState", "displayFinancialStatus"],
            hotwaxStatus: ["omsItemStatusId", "omsOrderStatusId"],   // an item's own, else its order's (orderStatus)
            amount      : ["grandTotal", "totalPrice", "orderGrandTotal", "returnTotal", "lineAmount", "returnCredit"],
            day         : ["omsOrderDate", "orderDate", "createdAt", "tranDate", "returnDate", "entryDate", "closedAt"],
            notOnCard   : ["status", "cancelledAt", "processedAt", "updatedAt", "currentTotalPriceSet", "currentTotalTaxSet",
                           "subtotalPriceSet", "totalPriceSet", "totalRefunded", "openReturnOnOrderStatus"],
    ].asImmutable()

    /**
     * DAR-UI-080 (Aditi: "the minimal amount of information needed to draw a meaningful conclusion"). A record's facts
     * from its data, under the fields Darpan's reads give them: its order's name and HotWax status (a HotWax item's
     * omsOrderName and omsOrderStatusId, a return's orderName), a return's channel and AfterShip warranty, its order's total
     * (an order's grandTotal in HotWax, totalPrice in Shopify, else the orderGrandTotal an item or return carries) and a
     * return's own (returnTotal), a NetSuite order's customer
     * deposits (status without NetSuite's "Customer Deposit : "), and a Shopify order's fulfillment status when its read
     * gave one, else the record's own (statusId), with its payment status apart (paymentStatus, DAR-BE-360); its order's date and a return's own (a
     * Shopify return's closedAt when it has no other, DAR-BE-359), as the tenant's calendar day (zone); its customer, an
     * item's SKU and its location (HotWax facilityId and its name, NetSuite lineLocation). DAR-UI-081: a return held behind
     * another (R8 "Exchange held by open return") names the return holding it (heldBy) and its exchange order, with
     * whether that order reached NetSuite. Codes read as words (ORDER_COMPLETED -> Completed); data keeps them.
     */
    static Map recordFacts(Map data, String recordType, ZoneId zone = ZoneId.of("UTC")) {
        if (!data) return [:]
        Closure<String> text = { String field -> data.get(field) instanceof Map || data.get(field) instanceof List ? null : (data.get(field)?.toString()?.trim() ?: null) }
        Closure<Map> amount = { String value ->
            // OC219: bounded, so an amount like 1E999999999 is not scaled into a billion digits; it shows no amount.
            BigDecimal number = value ? BoundedDecimal.of(value) : null
            number != null ? money(number, text("currencyUomId") ?: text("currencyCode")) : null
        }
        String fulfilled = text("displayFulfillmentStatus")
        List<Map> deposits = ((data.get("deposits") instanceof List ? data.get("deposits") : []) as List).findAll { it instanceof Map }
                .collect { Map d -> [id: d.tranId, amount: d.amount, status: d.status?.toString()?.replaceFirst(/^.* : /, '')] }
        String heldBy = text("openReturnOnOrderId")
        String exchange = text("exchangeOrderName") ?: text("exchangeOrderId")
        return [status     : fulfilled ? words(fulfilled) : statusWords(text("statusId")),
                paymentStatus: paymentStatus(data, text, deposits),
                orderName  : text("omsOrderName") ?: (recordType == "return" ? text("orderName") : recordType == "order" ? text("name") : null),
                orderStatus: statusWords(text("omsOrderStatusId")),
                // DAR-BE-372 rule 2. HotWax's own word for this record, carried on another system's row (an R10 NetSuite
                // line's omsItemStatusId), so the HotWax cell says it although no HotWax record was kept.
                hotwaxStatus: statusWords(text("omsItemStatusId")),
                customer   : text("customerName") ?: personName(data.get("billToCustomer")),
                sku        : text("sku"),
                location   : text("facilityId") ?: text("lineLocation"),
                locationName: text("facilityName"),
                // DAR-UI-081 (Aditi: "add order sales channel and shipping method", "add the IF id"): NetSuite's channel
                // (custbody_hc_sales_channel), HotWax's ship group method, a NetSuite line's item fulfillments.
                salesChannel: text("salesChannel") ?: channelWords(text("sourceName") ?: text("salesChannelEnumId")),
                // DAR-BE-372: an R10 NetSuite line carries its HotWax item's method (omsItemShipmentMethodTypeId).
                shipMethod : text("shipmentMethodTypeId") ?: text("omsItemShipmentMethodTypeId") ?: shipMethods(data.get("shipGroups")),
                fulfillments: (data.get("fulfillments") instanceof List ? ((List) data.get("fulfillments")).collect { it?.toString() }.findAll() : []) ?: null,
                orderDate  : dayOf(text("omsOrderDate") ?: text("orderDate") ?: (recordType == "order" ? text("createdAt") : null) ?:
                        text("tranDate"), zone)?.toString(),
                returnDate : recordType == "return" ? dayOf(text("returnDate") ?: text("entryDate") ?: text("closedAt"), zone)?.toString() : null,
                heldBy     : heldBy ? [id: heldBy, status: statusWords(text("openReturnOnOrderStatus"))] : null,
                exchange   : exchange ? [id: exchange, netsuiteId: text("exchangeOrderNetsuiteId")] : null,
                channel    : text("returnChannelEnumId"),
                warrantyId : text("warrantyId"),
                // Aditi: "order and return totals are missing": an order's own total, else the one its item or return carries.
                orderTotal : amount(text("grandTotal") ?: text("totalPrice") ?: text("orderGrandTotal")),
                returnTotal: recordType == "return" ? amount(text("returnTotal")) : null,
                // DAR-BE-372 rule 2. A line's own amount (NetSuite lineAmount), for a finding about one line.
                lineTotal  : amount(text("lineAmount")),
                deposits   : deposits ?: null,
                lines      : orderLines(data)] + (recordType == AuditAutomationSupport.ORDER_LINE ? orderLineFacts(data, text, amount) : [:])
    }

    /**
     * DAR-BE-360 (Aditi 2026-11-25: "add payment status to all orders and refunds", in each system's own words), from what
     * the record's read gave, never a lookup: Shopify's financial status; a HotWax order's payment preferences, each status
     * once ("Settled, Refunded"), "No payment" when it has none; a HotWax return's credit (read when the run asks it,
     * DAR-BE-192), "Refunded" above zero; a NetSuite order's or line's customer deposits, each status once. Else none.
     */
    private static String paymentStatus(Map data, Closure<String> text, List<Map> deposits) {
        if (text("displayFinancialStatus")) return words(text("displayFinancialStatus"))
        Object opps = data.get("paymentPreferences")
        if (opps instanceof List && opps) {
            String said = ((List) opps).findAll { it instanceof Map && it.statusId }
                    .collect { words(((Map) it).statusId.toString().replaceFirst(/^PAYMENT_/, '')) }.unique().join(", ")
            if (said) return said
        }
        if (text("hasPaymentPreference") == "N") return "No payment"
        if (text("returnCredit")) {
            try { return new BigDecimal(text("returnCredit")) > 0 ? "Refunded" : "Not refunded" } catch (NumberFormatException ignored) { }
        }
        return deposits.collect { it.status }.findAll().unique().join(", ") ?: null
    }

    /**
     * DAR-UI-099. Another line of the finding's order: how many units the read held of it, whether the run set it aside
     * (a filter: an item not yet completed, a NetSuite payment line), and for a line with no HotWax line id its item, type
     * and amount (NetSuite "Store Credit - Redemption", Payment, -89.30). A NetSuite line's status is its line state.
     */
    private static Map orderLineFacts(Map data, Closure<String> text, Closure<Map> amount) {
        Map facts = [quantity: data.get("units") instanceof Number ? data.get("units") : null,
                     setAside: text("presence") == "EXCLUDED",
                     item    : text("itemName"),
                     itemType: text("itemType"),
                     amount  : amount(text("lineAmount"))]
        if (!text("statusId") && text("lineState")) facts.status = words(text("lineState"))
        return facts
    }

    /**
     * DAR-UI-080. A record's date as the tenant's calendar day (2026-10-21): epoch millis (an OMS return's returnDate), an
     * ISO instant (GraphQL orderDate, Shopify createdAt), a date already written as one, or NetSuite's M/d/yyyy (DAR-BE-372). Anything else is no date.
     */
    /**
     * The calendar day a record's date names, in zone, or null: epoch millis, an ISO timestamp with an offset or Z,
     * YYYY-MM-DD (with or without a time after it, read as written), or NetSuite's M/d/yyyy. DAR-OPS-029 S99-016: the one
     * parser for finding facts and the judged window (AuditRecentReadSupport.judged), so both read the same day.
     */
    static LocalDate dayOf(Object value, ZoneId zone) {
        String text = value?.toString()?.trim()
        if (!text) return null
        try {
            if (text ==~ /\d{10,}/) return Instant.ofEpochMilli(text as long).atZone(zone).toLocalDate()
            if (text ==~ /\d{4}-\d{2}-\d{2}T.+/) return OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate()
            if (text ==~ /\d{4}-\d{2}-\d{2}.*/) return LocalDate.parse(text.substring(0, 10))
            // DAR-BE-372. NetSuite's tranDate as its SuiteQL gives it: 11/7/2026 (M/d/yyyy).
            def us = text =~ /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/
            if (us.find()) return LocalDate.of(us.group(3) as int, us.group(1) as int, us.group(2) as int)
        } catch (DateTimeException | NumberFormatException ignored) { }
        return null
    }

    /**
     * DAR-UI-081 (Aditi: "all findings should have basic order info... maintain some consistency"). The same facts
     * under each system's own names: a customer as HotWax's billToCustomer {firstName, lastName}; a sales channel as
     * Shopify's sourceName (pos, web) or HotWax's salesChannelEnumId (POS_SALES_CHANNEL); a ship method from HotWax's
     * ship groups.
     */
    private static String personName(Object person) {
        if (!(person instanceof Map)) return null
        String name = [((Map) person).firstName, ((Map) person).lastName].collect { it?.toString()?.trim() }.findAll().join(" ")
        return name ?: null
    }

    private static String channelWords(String code) {
        String bare = code?.replaceFirst(/(?i)_?(SALES_)?CHANNEL$/, '')?.trim()
        if (!bare) return null
        return bare.equalsIgnoreCase("pos") ? "POS" : words(bare)
    }

    private static String shipMethods(Object shipGroups) {
        List<String> methods = ((shipGroups instanceof List ? shipGroups : []) as List).findAll { it instanceof Map }
                .collect { ((Map) it).shipmentMethodTypeId?.toString()?.trim() }.findAll().unique()
        return methods ? methods.join(", ") : null
    }

    /**
     * DAR-BE-368 (Aditi: "include the line item details, expecially the SKU", "show both shopify and HW"). An order's lines
     * as its read gave them, each {id, sku, name, quantity, status}: a Shopify order's lineItems (the read asked
     * withLineItems), id its line id; a HotWax order's orderItems (the read asked orderItems), id the Shopify line id HotWax
     * keeps as the item's externalId, so one line pairs across the two. Null when the read gave none.
     */
    static List<Map> orderLines(Map data) {
        if (data?.get("lineItems") instanceof List) {
            return ((List) data.get("lineItems")).findAll { it instanceof Map }.collect { Map l ->
                [id: l.id?.toString(), sku: l.sku ?: null, name: l.name ?: null, quantity: l.quantity,
                 status: shopifyLineStatus(l.unfulfilledQuantity, l.currentQuantity != null ? l.currentQuantity : l.quantity)]
            }
        }
        if (data?.get("orderItems") instanceof List) {
            return ((List) data.get("orderItems")).findAll { it instanceof Map }.collect { Map i ->
                [id: (i.externalId ?: i.orderItemSeqId)?.toString(), sku: i.sku ?: null, name: null, quantity: i.quantity,
                 status: statusWords(i.statusId as String)]
            }
        }
        return null
    }

    /** A Shopify line's state from its quantities: Removed (none left), Fulfilled, Unfulfilled, or "1 of 2 unfulfilled". */
    private static String shopifyLineStatus(Object unfulfilled, Object current) {
        BigDecimal u = decimalOrNull(unfulfilled)
        BigDecimal q = decimalOrNull(current)
        if (u == null || q == null) return null
        if (q.signum() == 0) return "Removed"
        if (u.signum() == 0) return "Fulfilled"
        if (u >= q) return "Unfulfilled"
        return "${u.stripTrailingZeros().toPlainString()} of ${q.stripTrailingZeros().toPlainString()} unfulfilled".toString()
    }

    /** OC219: bounded (BoundedDecimal); a quantity too large to write is no quantity. */
    private static BigDecimal decimalOrNull(Object value) {
        return BoundedDecimal.of(value)
    }

    /** ORDER_COMPLETED, RETURN_REQUESTED, ITEM_COMPLETED -> "Completed", "Requested"; null stays null. */
    private static String statusWords(String code) {
        return code ? words(code.replaceFirst(/^(ORDER|RETURN|ITEM)_/, '')) : null
    }

    /** PARTIALLY_REFUNDED -> "Partially refunded". */
    private static String words(String code) {
        String w = code.replace('_', ' ').toLowerCase()
        return w.substring(0, 1).toUpperCase() + w.substring(1)
    }

    /** Money: amount at the currency's scale as a string, sign included. Null when there is no amount. */
    static Map money(BigDecimal amount, String currency) {
        if (amount == null) return null
        return [amount: amountText(amount, currency), currency: currency]
    }

    static String amountText(BigDecimal amount, String currency) {
        // OC219: setScale on an unbounded exponent would build its digits; such an amount keeps its own text.
        if (BoundedDecimal.of(amount) == null) return amount?.toString()
        int scale = 2
        try { if (currency) scale = Currency.getInstance(currency).defaultFractionDigits } catch (IllegalArgumentException ignored) { }
        return amount.setScale(Math.max(scale, 0), RoundingMode.HALF_UP).toPlainString()
    }
}
