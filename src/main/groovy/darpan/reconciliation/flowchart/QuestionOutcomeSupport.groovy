package darpan.reconciliation.flowchart

import darpan.facade.reconciliation.DiffDocumentStreamSupport

/**
 * DAR-UI-048. Splits the records one question asked about into yes and no. See the table in the plan
 * and the spec's Amendment A2. Reads the result document line by line (documents reach GB scale) and
 * holds one key set in memory at a time.
 */
class QuestionOutcomeSupport {

    static Map split(Map args) {
        String role = args.questionRole as String
        String mode = args.scopeMode as String
        File outDir = (File) args.outDir
        String token = args.token as String
        Set<String> input = args.inputKeysFile ? QuestionKeyFiles.read((File) args.inputKeysFile) : null
        Set<String> file1 = args.file1KeysFile ? QuestionKeyFiles.read((File) args.file1KeysFile) : null
        Set<String> findings = findingKeys((File) args.resultDocument)

        Set<String> asked
        Set<String> no
        Set<String> unasked = [] as Set
        if (role == RunFlowchartTree.ROLE_START) {
            asked = file1 ?: ([] as Set)
            no = [] as Set
        } else if (mode == RunFlowchartTree.MODE_COMPARE) {
            asked = file1 ?: ([] as Set)
            no = asked.findAll { findings.contains(it) } as Set
            if (input != null) unasked = input.findAll { !asked.contains(it) } as Set
        } else if (input != null) {
            asked = input
            no = asked.findAll { findings.contains(it) } as Set
        } else {
            asked = findings
            no = findings
        }
        Set<String> yes = (role == RunFlowchartTree.ROLE_START) ? asked :
                (mode != RunFlowchartTree.MODE_COMPARE && input == null) ? ([] as Set) :
                        asked.findAll { !no.contains(it) } as Set

        File yesFile = new File(outDir, "${token}-yes.txt")
        File noFile = new File(outDir, "${token}-no.txt")
        long yesCount = QuestionKeyFiles.write(yesFile, yes)
        long noCount = QuestionKeyFiles.write(noFile, no)
        File unaskedFile = null
        if (unasked) {
            unaskedFile = new File(outDir, "${token}-unasked.txt")
            QuestionKeyFiles.write(unaskedFile, unasked)
        }
        return [yesFile: yesFile, noFile: noFile, unaskedFile: unaskedFile,
                yesCount: yesCount, noCount: noCount, unaskedCount: (long) unasked.size()]
    }

    static Set<String> findingKeys(File resultDocument) {
        Set<String> keys = new HashSet<String>()
        if (resultDocument == null || !resultDocument.isFile()) return keys
        DiffDocumentStreamSupport.forEachRow(resultDocument) { Map row ->
            Object key = row.get("primaryId") ?: row.get("id")
            if (key != null && key.toString().trim()) keys.add(key.toString().trim())
        }
        return keys
    }
}
