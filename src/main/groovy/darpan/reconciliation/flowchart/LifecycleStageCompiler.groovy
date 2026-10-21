package darpan.reconciliation.flowchart

/**
 * DAR-UI-050. A lifecycle is a start record and the stages it should reach, in order. This compiles that list
 * into the question tree the walker already runs, so a person picks stages from dropdowns and never sees a
 * question, a branch or a rule.
 *
 * Every place a record can stop is the records ONE question finds (its "no"), so every place opens its records
 * on the ordinary run result page:
 *  - stage k asks "is stage k missing?" (ABSENT) of the records that reached stage k-1;
 *  - beside the last stage, "has it reached the last stage?" (PRESENT) finds the complete records;
 *  - under stage k's "no", for each later stage j: "has it reached j?" (PRESENT) finds those that skipped ahead
 *    to j, and "is j missing too?" (ABSENT) carries the rest on, or, at the last stage, finds those that stopped;
 *  - after stage k (not the last), "did an exception excuse it?" (EXCUSED) takes the records an exception let past
 *    stage k without it (Store Pickup orders with no fulfillment) onto their own track, so the next stage sees only
 *    records that reached k; on that track, "is j missing?" for each later stage finds where they stopped.
 * So every record lands in exactly one place, and every place has a name.
 */
class LifecycleStageCompiler {

    static final String ABSENT = "ABSENT"
    static final String PRESENT = "PRESENT"
    /** The question (and its role) that sets a stage's excused records apart; the spine steps over it. */
    static final String EXCUSED = "EXCUSED"

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

    /**
     * DAR-UI-052. Stages that are no single NetSuite type: reached when ANY of several records exists (a return is
     * settled by a refund, an invoice or a replacement order). Each needs its stageReach anyOf, which names only
     * RECORD_TYPES, so these keys never reach SuiteQL and a lifecycle never starts from one.
     */
    static final Map<String, Map<String, String>> COMPOSITE_STAGES = [
            Settled: [name: "Settlement", plural: "Settlements", past: "Settled", neg: "never settled"],
    ].asImmutable()

    static Map<String, String> words(String recordType) {
        Map<String, String> w = RECORD_TYPES[recordType] ?: COMPOSITE_STAGES[recordType]
        if (w == null) throw new IllegalArgumentException("'${recordType}' is not a record type a lifecycle can use.")
        return w
    }

    static boolean isComposite(String stage) { COMPOSITE_STAGES.containsKey(stage) }

    /**
     * Ordered question specs: key, parentKey, parentBranch, questionRole, recordType (START) or stageRecordType
     * with check (ABSENT / PRESENT), runName, and noOutcomeLabel where the question's records are a place.
     * Order matters: a stage comes before the questions beside it, so the first YES child is always the next stage.
     */
    static List<Map> compile(String startRecordType, List<String> stages) {
        if (isComposite(startRecordType)) throw new IllegalArgumentException("A lifecycle starts from records, not from '${startRecordType}'.")
        Map<String, String> start = words(startRecordType)
        if (!stages) throw new IllegalArgumentException("A lifecycle needs at least one stage.")
        List<Map<String, String>> s = stages.collect { words(it) }
        if (stages.toSet().size() != stages.size()) throw new IllegalArgumentException("A stage can appear only once.")
        if (stages.contains(startRecordType)) throw new IllegalArgumentException("A stage cannot be the start record itself.")

        List<Map> out = [[key: "start", parentKey: null, parentBranch: null, questionRole: "START",
                          recordType: startRecordType, runName: start.plural]]
        int n = stages.size()
        for (int k = 0; k < n; k++) {
            String parent = k == 0 ? "start" : "x${k - 1}".toString()
            boolean last = k == n - 1
            out << [key: "s${k}".toString(), parentKey: parent, parentBranch: "YES", stageRecordType: stages[k], check: ABSENT,
                    runName: s[k].name, noOutcomeLabel: last ? stoppedName(s, k) : null]
            if (last) {
                out << [key: "done", parentKey: parent, parentBranch: "YES", stageRecordType: stages[k], check: PRESENT,
                        runName: s[k].name, noOutcomeLabel: completeName(s)]
            } else {
                out << [key: "x${k}".toString(), parentKey: "s${k}".toString(), parentBranch: "YES", questionRole: EXCUSED,
                        stageRecordType: stages[k], check: EXCUSED, runName: s[k].name, ruleLabel: excusedName(s, k)]
            }
        }
        // The excused track: a record let past stage k by an exception stops at the first later stage it is missing.
        for (int k = 0; k < n - 1; k++) {
            String parent = "x${k}".toString(), branch = "NO"
            for (int j = k + 1; j < n; j++) {
                String key = "x${k}.not${j}".toString()
                out << [key: key, parentKey: parent, parentBranch: branch, stageRecordType: stages[j], check: ABSENT,
                        runName: s[j].name, noOutcomeLabel: "${excusedName(s, k)}, ${s[j].neg}".toString()]
                parent = key
                branch = "YES"
            }
        }
        for (int k = 0; k < n - 1; k++) {
            String parent = "s${k}".toString()
            for (int j = k + 1; j < n; j++) {
                boolean lastAsk = j == n - 1
                out << [key: "s${k}.has${j}".toString(), parentKey: parent, parentBranch: "NO", stageRecordType: stages[j],
                        check: PRESENT, runName: s[j].name, noOutcomeLabel: "${s[j].past}, ${s[k].neg}".toString()]
                String missing = "s${k}.not${j}".toString()
                out << [key: missing, parentKey: parent, parentBranch: "NO", stageRecordType: stages[j],
                        check: ABSENT, runName: s[j].name, noOutcomeLabel: lastAsk ? stoppedName(s, k) : null]
                parent = missing
            }
        }
        return out
    }

    /** A lifecycle is named for its records ("Sales Orders"); its stages and their order are on its page. */
    static String lifecycleName(String startRecordType) {
        return words(startRecordType).plural.split(' ').collect { it.capitalize() }.join(' ')
    }

    /** "Fulfillment not expected": the place a person's exceptions name in their own words on the board. */
    static String excusedName(List<Map<String, String>> s, int k) { return "${s[k].name} not expected".toString() }

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
