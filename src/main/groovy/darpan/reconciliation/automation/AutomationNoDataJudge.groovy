package darpan.reconciliation.automation

import static darpan.common.ValueSupport.normalize

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). How a window that compared nothing is judged: a quiet day (every
 * side read or kept nothing), a run error (one side empty, or a side whose Keep kept nothing two runs running), or neither
 * (a verify, whose empty side is its answer), and each side's read and kept counts. Pure: no reads, no writes.
 */
class AutomationNoDataJudge {

    /**
     * DAR-BE-390 (Aditi 2026-11-27: "going forward send a nothing to compare message in this case", MEPHISTO's Returns check
     * on Thanksgiving read HotWax 0, Shopify 0 and told nobody). A NO_DATA window is a quiet day when every side it reads
     * read 0 (an evaluate reads its first side only); one side empty or kept nothing is a run error, not this.
     */
    static final String NOTICE_RUN_ERROR = "RUN_ERROR", NOTICE_QUIET_DAY = "QUIET_DAY"

    /** DAR-BE-390. Who a NO_DATA close tells: NOTICE_RUN_ERROR (a did-not-run kind), NOTICE_QUIET_DAY, or null (a verify, or a shape neither is: nobody). */
    static String noDataNotice(String errorKind, Object verifying, Map file1Result, Map file2Result, Map reconcileResult, boolean evaluateMode) {
        if (errorKind != null) return NOTICE_RUN_ERROR
        return !verifying && quietDay(file1Result, file2Result, reconcileResult, evaluateMode) ? NOTICE_QUIET_DAY : null
    }

    /**
     * DAR-BE-393: the shape of a quiet day, a side (an evaluate: its first) that ended with nothing, read or kept. Whether that
     * side is a run error instead is the finding's (noDataFinding), which noDataNotice asks first.
     */
    static boolean quietDay(Map file1Result, Map file2Result, Map reconcileResult, boolean evaluateMode) {
        Closure nothing = { Map result, String sideKey ->
            (keepGiven(result, reconcileResult, sideKey) ?: 0) == 0 || (result?.recordCount != null && (result.recordCount as Integer) == 0)
        }
        return evaluateMode ? nothing(file1Result, "file1") : (nothing(file1Result, "file1") || nothing(file2Result, "file2"))
    }

    /** DAR-BE-393. "Shopify 59, HotWax 0": each side's name with what it kept (its recordCount; nothing read is 0). */
    static String quietDayCounts(List<String> names, List<Map> results) {
        return (0..<names.size()).collect { int i -> "${names[i]} ${(results[i]?.recordCount as Integer) ?: 0}" }.join(", ")
    }

    /**
     * DAR-BE-015. Why a NO_DATA window did not really run, or null when it is a quiet day (both sides read nothing). One side
     * read nothing while the other read records: that side is empty. A side read records and Keep kept none: it kept nothing.
     */
    static String noDataReason(String side1Label, Integer read1, Integer kept1, String side2Label, Integer read2, Integer kept2) {
        return noDataFinding(side1Label, read1, kept1, side2Label, read2, kept2)?.reason
    }

    /**
     * DAR-BE-015. The did-not-run finding of a NO_DATA window: [kind: ONE_SIDE_EMPTY | KEPT_NOTHING, reason], or null for a quiet day.
     * DAR-BE-393: a side that read nothing beside a side whose Keep kept nothing is a quiet day (a store-scoped check on a day the
     * store sold nothing: the other side's read is every store's). A side that kept nothing is a quiet day the first time after a
     * run that kept records on that side (priorKept: [file1, file2], that run's kept counts); it alerts from the second day.
     */
    static Map<String, String> noDataFinding(String side1Label, Integer read1, Integer kept1, String side2Label, Integer read2, Integer kept2,
                                             Map priorKept = null) {
        int r1 = read1 ?: 0
        int r2 = read2 ?: 0
        if (r1 == 0 && r2 == 0) return null
        if (r1 == 0) return kept2 == 0 ? null : [kind: "ONE_SIDE_EMPTY", reason: "The ${side1Label} side read no records.".toString()]
        if (r2 == 0) return kept1 == 0 ? null : [kind: "ONE_SIDE_EMPTY", reason: "The ${side2Label} side read no records.".toString()]
        if (kept1 != null && kept1 == 0 && !keptLastRun(priorKept, "file1")) {
            return [kind: "KEPT_NOTHING", reason: "The ${side1Label} side kept 0 of ${String.format('%,d', r1)} records.".toString()]
        }
        if (kept2 != null && kept2 == 0 && !keptLastRun(priorKept, "file2")) {
            return [kind: "KEPT_NOTHING", reason: "The ${side2Label} side kept 0 of ${String.format('%,d', r2)} records.".toString()]
        }
        return null
    }

    private static boolean keptLastRun(Map priorKept, String sideKey) {
        return ((priorKept?.get(sideKey) as Integer) ?: 0) > 0
    }

    /** DAR-BE-015. The finding a NO_DATA close persists: none for a verify, which reads one side by design (its empty side is its answer). */
    static Map<String, String> closeFinding(Object verifying, String label1, String label2, Map file1Result, Map file2Result,
                                            Map reconcileResult, boolean evaluateMode, Map priorKept = null) {
        return verifying ? null : windowNoDataFinding(label1, label2, file1Result, file2Result, reconcileResult, evaluateMode, priorKept)
    }

    /**
     * DAR-BE-015. The did-not-run finding of a NO_DATA window (persisted as the execution's noDataKind and errorMessage). A side's
     * read is what its recent read returned, or the records Keep was given when more (an audit re-check appends records to a side
     * whose recent read was empty); its kept count is its recordCount, which the post-Keep path has already set from Keep. An
     * evaluate reads one side by design: only its side 1 can have kept nothing.
     */
    static Map<String, String> windowNoDataFinding(String label1, String label2, Map file1Result, Map file2Result, Map reconcileResult,
                                                   boolean evaluateMode, Map priorKept = null) {
        Integer read1 = keepGiven(file1Result, reconcileResult, "file1")
        Integer read2 = keepGiven(file2Result, reconcileResult, "file2")
        Integer kept1 = file1Result?.recordCount as Integer
        Integer kept2 = file2Result?.recordCount as Integer
        if (evaluateMode) {
            return read1 > 0 && kept1 == 0 ? noDataFinding(label1, read1, kept1, label2, 1, null, priorKept) : null
        }
        return noDataFinding(label1, read1, kept1, label2, read2, kept2, priorKept)
    }

    /** DAR-BE-393. True when a side read records and kept none (so whether it alerts depends on the side's last run). */
    static boolean keptNothingSide(Map file1Result, Map file2Result, Map reconcileResult) {
        return [[file1Result, "file1"], [file2Result, "file2"]].any { List pair ->
            Map result = (Map) pair[0]
            result != null && (keepGiven(result, reconcileResult, (String) pair[1]) ?: 0) > 0 && (result.recordCount as Integer) == 0
        }
    }

    /**
     * DAR-BE-393. One side read nothing, so the compare (where a Spark Keep runs) is skipped: the side that read records is kept
     * here first, keptCount(fileSide) answering its kept count (null when its Keep could not run: the side stays as read). Its
     * recordCount becomes what Keep kept, as after a compare; its readCount stays. Nothing happens when both sides or neither read.
     */
    static void keepTheReadSide(Map file1Result, Map file2Result, Closure keptCount) {
        int read1 = (file1Result?.readCount ?: file1Result?.recordCount ?: 0) as int
        int read2 = (file2Result?.readCount ?: file2Result?.recordCount ?: 0) as int
        if ((read1 == 0) == (read2 == 0)) return
        Map side = read1 == 0 ? file2Result : file1Result
        Integer kept = keptCount.call(read1 == 0 ? AutomationExecutionSupport.FILE_SIDE_2 : AutomationExecutionSupport.FILE_SIDE_1) as Integer
        if (kept == null) return
        if (side.readCount == null) side.readCount = side.recordCount
        side.recordCount = kept
        side.dataAvailable = kept > 0
    }

    private static Integer keepGiven(Map result, Map reconcileResult, String sideKey) {
        int read = (result?.readCount as Integer) ?: 0
        Object summary = reconcileResult?.keepSummaryBySide instanceof Map ? ((Map) reconcileResult.keepSummaryBySide).get(sideKey) : null
        if (summary instanceof Map) {
            int given = (((Map) summary).get("keptCount") as Integer ?: 0) + (((Map) summary).get("setAsideCount") as Integer ?: 0)
            read = Math.max(read, given)
        }
        return read
    }

    static boolean hasData(Map<String, Object> sourceResult) {
        return sourceResult?.dataAvailable == true && normalize(sourceResult?.fileLocation)
    }

    /**
     * DAR-BE-318 (plan 3b-2b, Task 14): the kept count of each side whose Keep ran in Spark at the compare
     * (keepSummaryBySide), keyed FILE_1/FILE_2. Empty when no side kept in Spark.
     */
    static Map<String, Integer> keptCountsBySide(Map<String, Object> reconcileResult) {
        Map summaries = reconcileResult?.keepSummaryBySide instanceof Map ? (Map) reconcileResult.keepSummaryBySide : [:]
        Map<String, Integer> counts = [:]
        [(AutomationExecutionSupport.FILE_SIDE_1): "file1", (AutomationExecutionSupport.FILE_SIDE_2): "file2"].each { String side, String prefix ->
            Object kept = summaries.get(prefix) instanceof Map ? ((Map) summaries.get(prefix)).get("keptCount") : null
            if (kept instanceof Number) counts.put(side, ((Number) kept).intValue())
        }
        return counts
    }
}
