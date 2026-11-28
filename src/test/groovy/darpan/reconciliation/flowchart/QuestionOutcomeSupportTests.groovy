package darpan.reconciliation.flowchart

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/** DAR-UI-048. What "yes" and "no" mean for each kind of question. */
class QuestionOutcomeSupportTests {

    @TempDir Path tmp

    File keys(String name, List<String> ks) { File f = tmp.resolve(name).toFile(); QuestionKeyFiles.write(f, ks); return f }

    /** The exact line layout writeDiffDatasetOutput produces and DiffDocumentStreamSupport reads. */
    File doc(String name, List<String> rowJson) {
        File f = tmp.resolve(name).toFile()
        StringBuilder sb = new StringBuilder("{\n\"metadata\":{},\n")
        if (!rowJson) {
            sb << "\"differences\":[]\n}\n"
        } else {
            sb << "\"differences\":[\n"
            rowJson.eachWithIndex { String r, int i -> sb << r << (i < rowJson.size() - 1 ? ",\n" : "]\n") }
            sb << "}\n"
        }
        f.text = sb.toString()
        return f
    }

    Map split(Map m) { return QuestionOutcomeSupport.split([outDir: tmp.toFile(), token: "q"] + m) }

    @Test
    void theStartIsAllYes() {
        Map r = split(questionRole: "START", scopeMode: "EVALUATE", file1KeysFile: keys("f1", ["K1", "K2"]),
                resultDocument: doc("d", ['{"type":"FINDING","id":"K1"}', '{"type":"FINDING","id":"K2"}']))
        assertEquals(2L, r.yesCount); assertEquals(0L, r.noCount)
        assertEquals(["K1", "K2"] as Set, QuestionKeyFiles.read(r.yesFile as File))
    }

    @Test
    void compareNoIsEveryFile1KeyWithAFinding() {
        Map r = split(scopeMode: "COMPARE", file1KeysFile: keys("f1", ["K1", "K2", "K3"]),
                resultDocument: doc("d", ['{"type":"MISSING_IN_FILE_2","id":"K1"}',
                                          '{"type":"MISSING_IN_FILE_1","id":"K9"}']))
        assertEquals(["K1"] as Set, QuestionKeyFiles.read(r.noFile as File))
        assertEquals(["K2", "K3"] as Set, QuestionKeyFiles.read(r.yesFile as File))
    }

    @Test
    void bothRowShapesCountAsNo() {
        Map r = split(scopeMode: "COMPARE", file1KeysFile: keys("f1", ["K1", "K2", "K3"]),
                resultDocument: doc("d", ['{"type":"MISSING_IN_FILE_2","id":"K1"}',
                                          '{"diffType":"FIELD_MISMATCH","primaryId":"K2","field":"total"}']))
        assertEquals(["K1", "K2"] as Set, QuestionKeyFiles.read(r.noFile as File))
        assertEquals(1L, r.yesCount)
    }

    @Test
    void evaluateChildAsksAboutTheParentsKeys() {
        Map r = split(scopeMode: "EVALUATE", inputKeysFile: keys("in", ["K1", "K2", "K3"]),
                resultDocument: doc("d", ['{"type":"FINDING","id":"K2"}', '{"type":"FINDING","id":"K7"}']))
        assertEquals(["K2"] as Set, QuestionKeyFiles.read(r.noFile as File))
        assertEquals(["K1", "K3"] as Set, QuestionKeyFiles.read(r.yesFile as File))
    }

    @Test
    void evaluateChildWithNoDocumentIsAllYes() {
        Map r = split(scopeMode: "EVALUATE", inputKeysFile: keys("in", ["K1", "K2"]), resultDocument: null)
        assertEquals(2L, r.yesCount); assertEquals(0L, r.noCount)
    }

    @Test
    void topLevelEvaluateIsAllNo() {
        Map r = split(scopeMode: "EVALUATE", resultDocument: doc("d", ['{"type":"FINDING","id":"K4"}']))
        assertEquals(0L, r.yesCount); assertEquals(1L, r.noCount)
    }

    @Test
    void compareChildReportsParentKeysItNeverSaw() {
        Map r = split(scopeMode: "COMPARE", inputKeysFile: keys("in", ["K1", "K2", "K5"]),
                file1KeysFile: keys("f1", ["K1", "K2"]), resultDocument: doc("d", []))
        assertEquals(1L, r.unaskedCount)
        assertEquals(["K5"] as Set, QuestionKeyFiles.read(r.unaskedFile as File))
        assertEquals(2L, r.yesCount)
    }

    @Test
    void nothingUnaskedWritesNoUnaskedFile() {
        Map r = split(scopeMode: "COMPARE", file1KeysFile: keys("f1", ["K1"]), resultDocument: doc("d", []))
        assertNull(r.unaskedFile)
        assertEquals(0L, r.unaskedCount)
    }

    /**
     * DAR-OPS-029 S08, lens E1 (conservation): a child is asked about its parent's branch, and every key of that branch
     * ends in exactly one of yes, no and unasked: nothing dropped, nothing counted twice. A finding about a key outside
     * the branch (K9, K8) never enters it.
     */
    @Test
    void everyKeyOfTheParentsBranchEndsInExactlyOneOfYesNoOrUnasked() {
        List<String> branch = ["K1", "K2", "K3", "K4", "K5"]
        Map compare = split(scopeMode: "COMPARE", inputKeysFile: keys("branchC", branch), file1KeysFile: keys("f1C", ["K1", "K2", "K3"]),
                resultDocument: doc("dC", ['{"type":"MISSING_IN_FILE_2","id":"K2"}', '{"type":"MISSING_IN_FILE_1","id":"K9"}']))
        assertConserved(branch, compare)

        Map evaluate = split(scopeMode: "EVALUATE", inputKeysFile: keys("branchE", branch),
                resultDocument: doc("dE", ['{"type":"FINDING","id":"K4"}', '{"type":"FINDING","id":"K8"}']))
        assertConserved(branch, evaluate)
        assertEquals(0L, evaluate.unaskedCount)
    }

    private static void assertConserved(List<String> branch, Map r) {
        Set<String> yes = QuestionKeyFiles.read(r.yesFile as File), no = QuestionKeyFiles.read(r.noFile as File)
        Set<String> unasked = r.unaskedFile ? QuestionKeyFiles.read(r.unaskedFile as File) : ([] as Set)
        assertEquals(branch as Set, yes + no + unasked, "every branch key lands somewhere")
        assertEquals(branch.size(), yes.size() + no.size() + unasked.size(), "and in one place only")
        assertEquals(branch.size() as long, (r.yesCount as long) + (r.noCount as long) + (r.unaskedCount as long), "the counts add up")
    }
}
