package darpan.email

/**
 * DAR-BE-303: realistic sample data for the two audit emails (DARPAN_AUDIT_RUN, DARPAN_AUDIT_DIGEST). A real send passes
 * a finished body; the admin preview and the render tests pass these instead and the screen builds the body from them,
 * through the same copy blocks, so an edited or draft block shows in the preview. Made-up names and ids (Gorjana).
 */
class AuditEmailSamples {
    static final String BASE = "https://app.drpn.ai"
    private static final String RMA = "Return has an RMA, no credit memo"

    /** The run update's fact sheet (AuditRunFacts.build's shape): findings, two causes, a check that did not run. */
    static Map runFacts() {
        return [tenant: [id: "GORJANA", name: "Gorjana"], audit: [id: "month-close", name: "Month close"],
                period: "2026-11", date: "2026-11-10", window: [from: "2026-11-01", thru: "2026-11-10"],
                totals: [open: 57, newToday: 3, fixedToday: 1],
                rules : [[id: "R2", text: "Every order is billed in NetSuite. More words.", open: 56, newToday: 3, fixedToday: 1, checksNotRun: [], checksTotal: 1],
                         [id: "R9", text: "Every Shopify order is in NetSuite.", open: 0, newToday: 0, fixedToday: 0, checksNotRun: ["shopify-vs-netsuite"], checksTotal: 1],
                         [id: "R10", text: "Every return is credited.", open: 1, newToday: 0, fixedToday: 0, checksNotRun: [], checksTotal: 1]],
                groups: [[rule: "R2", cause: "Unexplained", count: 30, fixText: null, ids: []],
                         [rule: "R2", cause: "Payment failed", count: 24, fixText: "Ask the customer to pay again.", ids: ["M201 #GOR201", "M202 #GOR202"]],
                         [rule: "R2", cause: "Line not billed", count: 2, fixText: "Bill the line in NetSuite.", ids: ["M300 #GOR300"]],
                         [rule: "R10", cause: RMA, count: 1, fixText: "Issue the credit memo.", ids: ["RX-1 #GOR900"]]]]
    }

    /** The writer's words: a headline and one fix sentence. */
    static Map runWords() { return [headline: "Mostly payments this morning.", fixes: [("R10|" + RMA): "Credit each return."]] }

    /** One morning's darpan.audit.report/v1, cut down to the keys the digest reads. */
    static Map digestReport() {
        return [report    : [audit: [id: "month-close", name: "Month close"], tenant: [id: "GORJANA"], date: "2026-10-22", period: "2026-10"],
                health    : [complete: true, summary: "Every configured check ran over complete reads."],
                summary   : [open: 3, newToday: 1, fixedToday: 1],
                categories: [
                        orders : [label: "Orders", counts: [open: 2], statements: [
                                R2: [id: "R2", findingsOmitted: 0, findings: [
                                        [id: "F-1", headline: "Invoice INV1 has no Etail id", state: "open", ageDays: 6],
                                        [id: "F-2", headline: "Invoice INV2 has no Etail id", state: "open", ageDays: 0],
                                        [id: "F-9", headline: "Invoice INV9 has no Etail id", state: "fixed", ageDays: 3]]]]],
                        returns: [label: "Returns", counts: [open: 1], statements: [
                                R8: [id: "R8", findingsOmitted: 0, findings: [
                                        [id: "F-3", headline: "Return RX-1 has no RMA in NetSuite", state: "open", ageDays: 1]]]]]]]
    }
}
