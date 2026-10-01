package darpan.reconciliation.flowchart

/**
 * DAR-UI-048. A run is a tree of yes/no questions. This class knows the tree's rules and the order it is
 * walked in, and nothing else: no Moqui, no Spark, no system names.
 *
 * Messages are sentences a person can act on; the save service returns them verbatim.
 */
class RunFlowchartTree {

    static final String BRANCH_YES = "YES"
    static final String BRANCH_NO = "NO"
    static final String ROLE_START = "START"
    static final String MODE_COMPARE = "COMPARE"
    static final String MODE_EVALUATE = "EVALUATE"

    static List<String> validate(List<Map> questions) {
        List<String> errors = []
        Map<String, Map> byId = (questions ?: []).collectEntries { [(it.reconciliationRunId as String): it] }
        List<Map> starts = byId.values().findAll { it.questionRole == ROLE_START } as List<Map>
        if (starts.size() > 1) errors << "A run has one start; this one has ${starts.size()}.".toString()

        byId.values().each { Map q ->
            String id = q.reconciliationRunId
            String parentId = q.parentReconciliationRunId
            String branch = q.parentBranch
            if (q.questionRole == ROLE_START) {
                if (parentId) errors << "The start cannot hang from another question (${id}).".toString()
                return
            }
            if (parentId && !byId.containsKey(parentId)) {
                errors << "Question ${id} hangs from ${parentId}, which is not a question in this run.".toString()
            }
            if (parentId && !(branch in [BRANCH_YES, BRANCH_NO])) {
                errors << "Question ${id} must say whether it follows the parent's yes or no.".toString()
            }
            if (!parentId && branch) {
                errors << "Question ${id} names a branch but has no parent.".toString()
            }
            Map parent = parentId ? byId[parentId] : null
            if (parent?.questionRole == ROLE_START && branch == BRANCH_NO) {
                errors << "The start has no 'no'; question ${id} must follow its yes.".toString()
            }
            // Final review I5: on the same system the key can be compared by name, and a child that
            // builds it differently could never line up with its parent's keys. Across systems the field
            // names differ by design, so nothing is checked.
            if (parent && q.file1System && q.file1System == parent.file1System && q.file1Key != parent.file1Key) {
                errors << ("Question ${id} starts from the same system as ${parentId} but builds its key " +
                        "differently (${q.file1Key} vs ${parent.file1Key}), so its records could never line up.").toString()
            }
            if (!parentId && starts) {
                errors << "Question ${id} must hang from the start.".toString()
            }
            if (parent && parent.questionRole != ROLE_START && !parent.parentReconciliationRunId &&
                    parent.scopeMode == MODE_EVALUATE && branch == BRANCH_YES) {
                errors << ("Question ${parentId} is a one-source question at the top of the run, so it has no list " +
                        "of records that passed; ${id} cannot follow its yes. Put both under a start.").toString()
            }
        }

        byId.keySet().each { String id ->
            Set<String> seen = [id] as Set
            String cursor = byId[id]?.parentReconciliationRunId
            while (cursor && byId.containsKey(cursor)) {
                if (!seen.add(cursor)) {
                    errors << "Question ${id} is part of a loop; a question cannot be its own ancestor.".toString()
                    break
                }
                cursor = byId[cursor].parentReconciliationRunId
            }
        }

        List<String> topSignatures = byId.values().findAll { !it.parentReconciliationRunId }
                .collect { it.file1Signature as String }.unique()
        if (!starts && topSignatures.size() > 1) {
            errors << "Every question at the top of a run must start from the same records.".toString()
        }
        return errors.unique()
    }

    static List<Map> walkOrder(List<Map> questions) {
        Map<String, List<Map>> childrenByParent = [:].withDefault { [] }
        (questions ?: []).each { Map q -> childrenByParent[(q.parentReconciliationRunId ?: "") as String] << q }
        List<Map> ordered = []
        Closure visit
        visit = { String parentKey ->
            childrenByParent[parentKey]
                    .findAll { it.isActive != "N" }
                    .sort { Map a, Map b ->
                        (a.questionRole == ROLE_START ? 0 : 1) <=> (b.questionRole == ROLE_START ? 0 : 1) ?:
                                ((a.runSequence ?: Integer.MAX_VALUE) as Integer) <=> ((b.runSequence ?: Integer.MAX_VALUE) as Integer) ?:
                                (a.reconciliationRunId as String) <=> (b.reconciliationRunId as String)
                    }
                    .each { Map q ->
                        ordered << q
                        visit(q.reconciliationRunId as String)
                    }
        }
        visit("")
        return ordered
    }
}
