package darpan.reconciliation.flowchart

/**
 * DAR-UI-050. A lifecycle is a start record and the stages it should reach, in order. This compiles that list
 * into the question tree the walker already runs, and names every ending of the tree, so a person picks stages
 * from dropdowns and never sees a question, a branch or a rule.
 *
 * The tree: stage k+1 hangs on stage k's "yes"; on stage k's "no" hang "did it reach a later stage?" questions,
 * one per later stage, each on the previous one's "no". So every record lands in exactly one place:
 * complete, stopped before a stage, or reached a later stage having skipped an earlier one.
 */
class LifecycleStageCompiler {

    /** NetSuite transaction types a lifecycle may use, in plain words. The keys double as the SuiteQL whitelist. */
    static final Map<String, Map<String, String>> RECORD_TYPES = [
            SalesOrd: [name: "Sales order", plural: "Sales orders", past: "Ordered", neg: "never ordered"],
            ItemShip: [name: "Fulfillment", plural: "Fulfillments", past: "Shipped", neg: "never shipped"],
            CustInvc: [name: "Invoice", plural: "Invoices", past: "Billed", neg: "never billed"],
            CashSale: [name: "Cash sale", plural: "Cash sales", past: "Sold for cash", neg: "never sold for cash"],
            RtnAuth : [name: "Return authorization", plural: "Return authorizations", past: "Returned", neg: "never returned"],
            ItemRcpt: [name: "Item receipt", plural: "Item receipts", past: "Received", neg: "never received"],
            CustCred: [name: "Credit memo", plural: "Credit memos", past: "Credited", neg: "never credited"],
            CustRfnd: [name: "Refund", plural: "Refunds", past: "Refunded", neg: "never refunded"],
    ].asImmutable()

    static Map<String, String> words(String recordType) {
        Map<String, String> w = RECORD_TYPES[recordType]
        if (w == null) throw new IllegalArgumentException("'${recordType}' is not a record type a lifecycle can use.")
        return w
    }

    /**
     * Ordered question specs: key, parentKey, parentBranch, questionRole, recordType (START) or
     * stageRecordType, runName, and the names of the endings: noOutcomeLabel / yesOutcomeLabel, set only
     * where that branch has no question under it.
     */
    static List<Map> compile(String startRecordType, List<String> stages) {
        Map<String, String> start = words(startRecordType)
        if (!stages) throw new IllegalArgumentException("A lifecycle needs at least one stage.")
        List<Map<String, String>> s = stages.collect { words(it) }
        if (stages.toSet().size() != stages.size()) throw new IllegalArgumentException("A stage can appear only once.")
        if (stages.contains(startRecordType)) throw new IllegalArgumentException("A stage cannot be the start record itself.")

        List<Map> out = [[key: "start", parentKey: null, parentBranch: null, questionRole: "START",
                          recordType: startRecordType, runName: start.plural]]
        int n = stages.size()
        for (int k = 0; k < n; k++) {
            boolean last = k == n - 1
            out << [key: "s${k}".toString(), parentKey: k == 0 ? "start" : "s${k - 1}".toString(), parentBranch: "YES",
                    stageRecordType: stages[k], runName: s[k].name,
                    yesOutcomeLabel: last ? completeName(s) : null,
                    noOutcomeLabel: last ? stoppedName(s, k) : null]
        }
        for (int k = 0; k < n; k++) {
            if (k == n - 1) continue
            // On stage k's "no": did the record reach stage j anyway? yes = skipped stage k; no = ask the next one.
            for (int j = k + 1; j < n; j++) {
                boolean lastAsk = j == n - 1
                out << [key: "s${k}.why${j}".toString(), parentKey: j == k + 1 ? "s${k}".toString() : "s${k}.why${j - 1}".toString(),
                        parentBranch: "NO", stageRecordType: stages[j], runName: s[j].name,
                        yesOutcomeLabel: "${s[j].past}, ${s[k].neg}".toString(),
                        noOutcomeLabel: lastAsk ? stoppedName(s, k) : null]
            }
        }
        return out
    }

    static String lifecycleName(String startRecordType, List<String> stages) {
        return "${words(startRecordType).plural}: ${stages.collect { words(it).past.toLowerCase() }.join(' → ')}".toString()
    }

    private static String completeName(List<Map<String, String>> s) {
        List<String> p = s.collect { it.past.toLowerCase() }
        String joined = p.size() > 1 ? p.subList(0, p.size() - 1).join(", ") + " and " + p.last() : p[0]
        return joined.capitalize()
    }

    private static String stoppedName(List<Map<String, String>> s, int k) {
        if (k > 0) return "${s[k - 1].past}, ${s[k].neg}".toString()
        if (s.size() == 1) return s[0].neg.capitalize()
        if (s.size() == 2) return "${s[0].neg.capitalize()}, ${s[1].neg}".toString()
        return "${s[0].neg.capitalize()}, nothing after".toString()
    }
}
