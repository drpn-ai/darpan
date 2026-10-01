package darpan.reconciliation.flowchart

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-048. The walk, with a fake pipeline: order, key hand-off, skips, failure, cancel. */
class RunFlowchartWalkerTests {

    @TempDir Path tmp

    static Map q(String id, Map m = [:]) {
        return [reconciliationRunId: id, parentReconciliationRunId: null, parentBranch: null, questionRole: null,
                runSequence: null, isActive: null, ruleSetId: "${id}_RS".toString(), scopeMode: "EVALUATE",
                file1Signature: "SIG"] + m
    }

    /** START returns K1..K4; A finds K2; A1 (yes of A) finds K3; B (no of A) finds nothing. */
    static List<Map> chart() {
        return [q("S", [questionRole: "START"]),
                q("A", [parentReconciliationRunId: "S", parentBranch: "YES", runSequence: 1]),
                q("A1", [parentReconciliationRunId: "A", parentBranch: "YES"]),
                q("B", [parentReconciliationRunId: "A", parentBranch: "NO"])]
    }

    Map fakeDoc(List<String> ids) {
        File f = File.createTempFile("doc", ".json", tmp.toFile())
        f.text = ids ? "{\n\"differences\":[\n" + ids.collect { '{"type":"FINDING","id":"' + it + '"}' }.join(",\n") + "]\n}\n"
                : "{\n\"differences\":[]\n}\n"
        return [file: f]
    }

    final Map no = [:]

    Map walk(List<Map> qs, Map<String, Map> behaviour, List calls, List skipped, Map yes) {
        return RunFlowchartWalker.walk([
                questions: qs, workDir: tmp.toFile(),
                runQuestion: { Map question, Map call ->
                    String id = question.reconciliationRunId
                    calls << [id: id, include: call.file1IncludeIdsLocation ? QuestionKeyFiles.read(new File(call.file1IncludeIdsLocation as String)) : null,
                              parent: call.parentRunResultId]
                    Map b = behaviour[id] ?: [:]
                    if (b.throws) throw new IllegalStateException(b.throws as String)
                    if (b.file1) QuestionKeyFiles.write(new File(call.file1KeysOutLocation as String), b.file1 as List)
                    return [runResultId: "RR_${id}".toString(), statusEnumId: b.status ?: "AUT_STAT_SUCCESS",
                            resultDocument: b.containsKey("findings") ? fakeDoc(b.findings as List).file : null]
                },
                recordSkipped: { Map question, String parentRunResultId, String status, String reason ->
                    skipped << [id: question.reconciliationRunId, status: status, parent: parentRunResultId]
                    return "RR_SKIP_${question.reconciliationRunId}".toString()
                },
                recordCounts: { String runResultId, long y, long n, long u -> yes[runResultId] = y; no[runResultId] = n },
        ])
    }

    @Test
    void parentsKeysReachTheChildOnTheRightBranch() {
        List calls = [], skipped = []; Map yes = [:]
        Map r = walk(chart(), [S: [file1: ["K1", "K2", "K3", "K4"], findings: ["K1", "K2", "K3", "K4"]],
                               A: [findings: ["K2"]], A1: [findings: ["K3"]], B: [findings: []]], calls, skipped, yes)
        assertEquals(["S", "A", "A1", "B"], calls*.id)
        assertNull(calls[0].include)
        assertEquals(["K1", "K2", "K3", "K4"] as Set, calls[1].include)
        assertEquals(["K1", "K3", "K4"] as Set, calls[2].include)   // yes of A
        assertEquals(["K2"] as Set, calls[3].include)               // no of A
        assertEquals("RR_A", calls[2].parent)
        assertEquals(4L, yes["RR_S"]); assertEquals(3L, yes["RR_A"]); assertEquals(2L, yes["RR_A1"]); assertEquals(1L, yes["RR_B"])
        assertEquals(0L, no["RR_S"]); assertEquals(1L, no["RR_A"]); assertEquals(1L, no["RR_A1"]); assertEquals(0L, no["RR_B"])
        assertEquals("AUT_STAT_SUCCESS", r.statusEnumId)
        assertEquals([], skipped)
    }

    @Test
    void anEmptyBranchSkipsItsChildrenAsNothingToAsk() {
        List calls = [], skipped = []; Map yes = [:]
        walk(chart(), [S: [file1: ["K1"], findings: ["K1"]], A: [findings: []]], calls, skipped, yes)
        assertEquals(["S", "A", "A1"], calls*.id)            // A's no branch is empty
        assertEquals([[id: "B", status: "AUT_STAT_NO_DATA", parent: "RR_A"]], skipped)
    }

    @Test
    void aFailureMarksItsSubtreeNotRunAndSiblingsStillRun() {
        List<Map> qs = chart() + [q("C", [parentReconciliationRunId: "S", parentBranch: "YES", runSequence: 2])]
        List calls = [], skipped = []; Map yes = [:]
        Map r = walk(qs, [S: [file1: ["K1"], findings: ["K1"]], A: [status: "AUT_STAT_FAILED"], C: [findings: []]], calls, skipped, yes)
        assertEquals(["S", "A", "C"], calls*.id)
        assertEquals(["A1", "B"] as Set, skipped.findAll { it.status == "AUT_STAT_NOT_RUN" }*.id as Set)
        assertEquals("AUT_STAT_FAILED", r.statusEnumId)
    }

    @Test
    void cancelStopsTheRestOfTheWalk() {
        List calls = [], skipped = []; Map yes = [:]
        Map r = walk(chart(), [S: [file1: ["K1"], findings: ["K1"]], A: [status: "AUT_STAT_CANCELLED"]], calls, skipped, yes)
        assertEquals(["S", "A"], calls*.id)
        assertEquals(["A1", "B"] as Set, skipped.findAll { it.status == "AUT_STAT_CANCELLED" }*.id as Set)
        assertEquals("AUT_STAT_CANCELLED", r.statusEnumId)
    }

    @Test
    void aChildWithNoFindingsPassesEveryKeyDown() {
        List calls = [], skipped = []; Map yes = [:]
        // A's pipeline extracted nothing (EVALUATE predicate matched no record): NO_DATA, no document.
        walk(chart(), [S: [file1: ["K1", "K2"], findings: ["K1", "K2"]], A: [status: "AUT_STAT_NO_DATA"]], calls, skipped, yes)
        assertEquals(["K1", "K2"] as Set, calls.find { it.id == "A1" }.include)
        assertEquals(2L, yes["RR_A"])
        assertTrue(skipped.any { it.id == "B" && it.status == "AUT_STAT_NO_DATA" })
    }

    @Test
    void noRowIsCreatedBeforeItsQuestionStarts() {
        List events = []
        RunFlowchartWalker.walk([
                questions: chart(), workDir: tmp.toFile(),
                runQuestion: { Map question, Map call ->
                    events << "run:${question.reconciliationRunId}".toString()
                    if (question.questionRole == "START") QuestionKeyFiles.write(new File(call.file1KeysOutLocation as String), ["K1"])
                    return [runResultId: "RR_${question.reconciliationRunId}".toString(), statusEnumId: "AUT_STAT_SUCCESS", resultDocument: null]
                },
                recordSkipped: { Map question, String p, String s, String reason -> events << "skip:${question.reconciliationRunId}".toString(); "X" },
                recordCounts: { String id, long y, long n, long u -> },
        ])
        // Every record call happens at that question's turn in the walk, never in a batch up front.
        assertEquals(["run:S", "run:A", "run:A1", "skip:B"], events)
    }

    @Test
    void aThrowingQuestionFailsAloneWithItsReasonAndSiblingsStillRun() {
        List<Map> qs = chart() + [q("C", [parentReconciliationRunId: "S", parentBranch: "YES", runSequence: 2])]
        List calls = [], skipped = []; Map yes = [:]
        Map r = walk(qs, [S: [file1: ["K1"], findings: ["K1"]], A: [throws: "boom: not authorized"], C: [findings: []]], calls, skipped, yes)
        Map failedA = skipped.find { it.id == "A" }
        assertEquals("AUT_STAT_FAILED", failedA.status)
        assertEquals(["A1", "B"] as Set, skipped.findAll { it.status == "AUT_STAT_NOT_RUN" }*.id as Set)
        assertTrue(calls*.id.contains("C"))
        assertEquals("AUT_STAT_FAILED", r.statusEnumId)
        assertEquals("AUT_STAT_FAILED", r.outcomes["A"].statusEnumId)
    }

    @Test
    void aFailureAfterTheRowExistsFailsThatRowNotANewOne() {
        List failures = []
        Map r = RunFlowchartWalker.walk([
                questions: chart(), workDir: tmp.toFile(),
                runQuestion: { Map question, Map call ->
                    QuestionKeyFiles.write(new File(call.file1KeysOutLocation as String), ["K1"])
                    return [runResultId: "RR_${question.reconciliationRunId}".toString(), statusEnumId: "AUT_STAT_SUCCESS", resultDocument: null]
                },
                recordSkipped: { Map question, String p, String s, String reason -> "SKIP_${question.reconciliationRunId}".toString() },
                recordFailure: { String runResultId, String reason -> failures << [id: runResultId, reason: reason] },
                recordCounts: { String id, long y, long n, long u -> if (id == "RR_A") throw new IllegalStateException("db down") },
        ])
        assertEquals([[id: "RR_A", reason: "This question could not finish: db down"]], failures)
        assertEquals("RR_A", r.outcomes["A"].runResultId)
        assertEquals("AUT_STAT_FAILED", r.outcomes["A"].statusEnumId)
    }

    @Test
    void theStartHookRunsBeforeEveryQuestionThatRuns() {
        List events = []
        RunFlowchartWalker.walk([
                questions: chart(), workDir: tmp.toFile(),
                onQuestionStart: { Map question -> events << "start:${question.reconciliationRunId}".toString() },
                runQuestion: { Map question, Map call ->
                    events << "run:${question.reconciliationRunId}".toString()
                    QuestionKeyFiles.write(new File(call.file1KeysOutLocation as String), ["K1"])
                    return [runResultId: "RR_${question.reconciliationRunId}".toString(), statusEnumId: "AUT_STAT_SUCCESS", resultDocument: null]
                },
                recordSkipped: { Map question, String p, String s, String reason -> "X" },
                recordCounts: { String id, long y, long n, long u -> },
        ])
        assertEquals(["start:S", "run:S", "start:A", "run:A", "start:A1", "run:A1"], events)
    }

    @Test
    void aRecorderThatThrowsNeverEscapesTheWalk() {
        int calls = 0
        Map r = RunFlowchartWalker.walk([
                questions: chart(), workDir: tmp.toFile(),
                runQuestion: { Map question, Map call -> throw new IllegalStateException("x") },
                recordSkipped: { Map question, String p, String s, String reason -> calls++; throw new IllegalStateException("db down") },
                recordCounts: { String id, long y, long n, long u -> },
        ])
        assertEquals("AUT_STAT_FAILED", r.statusEnumId)
        assertEquals(["S", "A", "A1", "B"] as Set, r.outcomes.keySet())
        assertTrue(calls >= 4)
    }
}
