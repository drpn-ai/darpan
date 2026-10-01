package darpan.reconciliation.flowchart

/**
 * DAR-UI-048. Walks a run's questions parent-first, handing each child its parent's branch keys.
 *
 * Moqui-free: the pipeline call and the two row writes are injected, so every rule of the walk is
 * tested without a database. A question's row is created only when its turn comes (the stuck-run
 * reaper fails rows left PENDING for 120 minutes, and a long chain would otherwise trip it).
 */
class RunFlowchartWalker {

    static final String SUCCESS = "AUT_STAT_SUCCESS"
    static final String FAILED = "AUT_STAT_FAILED"
    static final String NO_DATA = "AUT_STAT_NO_DATA"
    static final String CANCELLED = "AUT_STAT_CANCELLED"
    static final String NOT_RUN = "AUT_STAT_NOT_RUN"

    static final String REASON_NOT_RUN = "A question above this one did not finish."
    static final String REASON_CANCELLED = "Run cancelled by an operator."
    static final String REASON_NOTHING = "Nothing to ask: the previous question passed no records this way."

    static Map walk(Map args) {
        List<Map> order = RunFlowchartTree.walkOrder((List<Map>) args.questions)
        File workDir = (File) args.workDir
        workDir.mkdirs()
        Closure<Map> runQuestion = (Closure<Map>) args.runQuestion
        Closure<String> recordSkipped = (Closure<String>) args.recordSkipped
        Closure recordYesCount = (Closure) args.recordYesCount

        Map<String, Map> outcomes = [:]
        boolean cancelled = false
        boolean failed = false

        order.each { Map q ->
            String id = q.reconciliationRunId
            String parentId = q.parentReconciliationRunId
            Map parent = parentId ? outcomes[parentId] : null
            String parentRunResultId = parent?.runResultId

            if (cancelled) {
                outcomes[id] = [runResultId: recordSkipped.call(q, parentRunResultId, CANCELLED, REASON_CANCELLED), statusEnumId: CANCELLED]
                return
            }
            if (parent && parent.statusEnumId in [FAILED, NOT_RUN, CANCELLED]) {
                outcomes[id] = [runResultId: recordSkipped.call(q, parentRunResultId, NOT_RUN, REASON_NOT_RUN), statusEnumId: NOT_RUN]
                return
            }
            File branchKeys = null
            if (parent) {
                boolean parentSkipped = parent.skipped == true
                long branchCount = q.parentBranch == RunFlowchartTree.BRANCH_NO ? (parent.noCount ?: 0L) as long : (parent.yesCount ?: 0L) as long
                if (parentSkipped || branchCount == 0L) {
                    outcomes[id] = [runResultId: recordSkipped.call(q, parentRunResultId, NO_DATA, REASON_NOTHING),
                                    statusEnumId: NO_DATA, skipped: true]
                    return
                }
                branchKeys = (File) (q.parentBranch == RunFlowchartTree.BRANCH_NO ? parent.noFile : parent.yesFile)
            }

            File file1Keys = new File(workDir, "${id}-file1-keys.txt")
            Map ran = runQuestion.call(q, [parentRunResultId      : parentRunResultId,
                                           file1IncludeIdsLocation: branchKeys?.absolutePath,
                                           file1KeysOutLocation   : file1Keys.absolutePath])
            String status = ran.statusEnumId as String
            Map outcome = [runResultId: ran.runResultId, statusEnumId: status]
            if (status in [SUCCESS, NO_DATA]) {
                Map split = QuestionOutcomeSupport.split([questionRole  : q.questionRole, scopeMode: q.scopeMode,
                                                          inputKeysFile : branchKeys, file1KeysFile: file1Keys,
                                                          resultDocument: (File) ran.resultDocument, outDir: workDir, token: id])
                outcome.putAll(split)
                recordYesCount.call(ran.runResultId as String, split.yesCount as long)
            } else if (status == CANCELLED) {
                cancelled = true
            } else {
                failed = true
            }
            outcomes[id] = outcome
        }
        String overall = cancelled ? CANCELLED : failed ? FAILED : SUCCESS
        return [outcomes: outcomes, statusEnumId: overall]
    }
}
