package darpan.reconciliation.flowchart

/**
 * DAR-UI-048. Walks a run's questions parent-first, handing each child its parent's branch keys.
 *
 * Moqui-free: the pipeline call and the row writes are injected, so every rule of the walk is tested
 * without a database. A question's row is created only when its turn comes (the stuck-run reaper fails
 * rows left PENDING for 120 minutes, and a long chain would otherwise trip it).
 *
 * Nothing thrown by one question escapes the walk (final review I2): that question fails with the
 * reason, its subtree is NOT_RUN, its siblings still run, and every question in the walk ends with a
 * row — so a chart can never look "in progress" forever because the walk died between questions.
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
    static final String REASON_COULD_NOT_RUN = "This question could not run: "
    static final String REASON_COULD_NOT_FINISH = "This question could not finish: "

    /**
     * Args: questions, workDir, runQuestion, recordSkipped, recordCounts, and optionally recordFailure
     * (String runResultId, String reason) for a question whose row exists but which failed afterwards,
     * and onQuestionStart (Map question) called just before a question runs (automations heartbeat here).
     */
    static Map walk(Map args) {
        List<Map> order = RunFlowchartTree.walkOrder((List<Map>) args.questions)
        File workDir = (File) args.workDir
        workDir.mkdirs()
        Closure<Map> runQuestion = (Closure<Map>) args.runQuestion
        Closure<String> recordSkipped = (Closure<String>) args.recordSkipped
        Closure recordCounts = (Closure) args.recordCounts
        Closure recordFailure = (Closure) args.recordFailure
        Closure onQuestionStart = (Closure) args.onQuestionStart

        Map<String, Map> outcomes = [:]
        boolean cancelled = false
        boolean failed = false

        Closure<String> skip = { Map q, String parentRunResultId, String status, String reason ->
            try {
                return recordSkipped.call(q, parentRunResultId, status, reason)
            } catch (Throwable ignored) {
                return null
            }
        }

        order.each { Map q ->
            String id = q.reconciliationRunId
            String parentId = q.parentReconciliationRunId
            Map parent = parentId ? outcomes[parentId] : null
            String parentRunResultId = parent?.runResultId

            if (cancelled) {
                outcomes[id] = [runResultId: skip(q, parentRunResultId, CANCELLED, REASON_CANCELLED), statusEnumId: CANCELLED]
                return
            }
            if (parent && parent.statusEnumId in [FAILED, NOT_RUN, CANCELLED]) {
                outcomes[id] = [runResultId: skip(q, parentRunResultId, NOT_RUN, REASON_NOT_RUN), statusEnumId: NOT_RUN]
                return
            }
            File branchKeys = null
            if (parent) {
                long branchCount = q.parentBranch == RunFlowchartTree.BRANCH_NO ? (parent.noCount ?: 0L) as long : (parent.yesCount ?: 0L) as long
                if (parent.skipped == true || branchCount == 0L) {
                    outcomes[id] = [runResultId: skip(q, parentRunResultId, NO_DATA, REASON_NOTHING), statusEnumId: NO_DATA, skipped: true]
                    return
                }
                branchKeys = (File) (q.parentBranch == RunFlowchartTree.BRANCH_NO ? parent.noFile : parent.yesFile)
            }

            String ranRowId = null
            try {
                onQuestionStart?.call(q)
                File file1Keys = new File(workDir, "${id}-file1-keys.txt")
                Map ran = runQuestion.call(q, [parentRunResultId      : parentRunResultId,
                                               file1IncludeIdsLocation: branchKeys?.absolutePath,
                                               file1KeysOutLocation   : file1Keys.absolutePath])
                ranRowId = ran.runResultId as String
                String status = ran.statusEnumId as String
                Map outcome = [runResultId: ranRowId, statusEnumId: status]
                if (status in [SUCCESS, NO_DATA]) {
                    Map split = QuestionOutcomeSupport.split([questionRole  : q.questionRole, scopeMode: q.scopeMode,
                                                              inputKeysFile : branchKeys, file1KeysFile: file1Keys,
                                                              resultDocument: (File) ran.resultDocument, outDir: workDir, token: id])
                    outcome.putAll(split)
                    recordCounts.call(ranRowId, split.yesCount as long, split.noCount as long, split.unaskedCount as long)
                } else if (status == CANCELLED) {
                    cancelled = true
                } else {
                    failed = true
                }
                outcomes[id] = outcome
            } catch (Throwable t) {
                failed = true
                String detail = t.message ?: t.class.simpleName
                if (ranRowId && recordFailure != null) {
                    try { recordFailure.call(ranRowId, REASON_COULD_NOT_FINISH + detail) } catch (Throwable ignored) { }
                    outcomes[id] = [runResultId: ranRowId, statusEnumId: FAILED]
                } else {
                    outcomes[id] = [runResultId: skip(q, parentRunResultId, FAILED, REASON_COULD_NOT_RUN + detail), statusEnumId: FAILED]
                }
            }
        }
        String overall = cancelled ? CANCELLED : failed ? FAILED : SUCCESS
        return [outcomes: outcomes, statusEnumId: overall]
    }
}
