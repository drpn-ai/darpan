package darpan.script

import darpan.facade.reconciliation.DiffDetailClassifier
import darpan.facade.reconciliation.ReconciliationOutputSupport
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext

/** What the tenant script services need around ScriptSupervisor: a script as the UI reads it, and a run's input. */
@CompileStatic
class TenantScriptSupport {
    static final String SOURCE_SAMPLE = 'SAMPLE'
    static final String SOURCE_RUN_RESULT = 'RUN_RESULT'
    /** a RUN_RESULT input carries at most this many differences, four pages of the result reader */
    static final int MAX_RUN_RESULT_DIFFERENCES = 2_000

    private static final List<String> SCRIPT_FIELDS = ['tenantScriptId', 'scriptName', 'description', 'scriptProfile',
            'inputSource', 'scriptBody', 'sampleInputJson', 'createdByUserId', 'createdDate', 'lastUpdatedDate']

    static String inputSourceOf(Object source) {
        String value = source?.toString()?.trim()?.toUpperCase(Locale.ROOT) ?: SOURCE_SAMPLE
        return value in [SOURCE_SAMPLE, SOURCE_RUN_RESULT] ? value : null
    }

    static Map<String, Object> toScriptMap(Map row) {
        if (row == null) return null
        Map<String, Object> script = SCRIPT_FIELDS.collectEntries { String field -> [(field): row.get(field)] } as Map<String, Object>
        script.put('isActive', row.get('isActive') != 'N')
        return script
    }

    /**
     * The input for one run, read before the script starts since a script touches no database: {input, inputSummary},
     * or null with an error on ec.message. SAMPLE reads the JSON given, else the saved sample.
     */
    static Map<String, Object> runInput(ExecutionContext ec, String inputSource, String inputJson, String savedSampleJson, String fileName) {
        if (inputSource == SOURCE_RUN_RESULT) return runResultInput(ec, fileName)
        String json = inputJson != null ? inputJson : savedSampleJson
        try {
            return [input: ScriptSupervisor.parseInputJson(json), inputSummary: [source: SOURCE_SAMPLE, characters: (json ?: '').length()]] as Map<String, Object>
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
    }

    /** A difference with its record's fields parsed: a script has no JSON parser, so record.data arrives as a map. */
    static Object withParsedData(Object difference) {
        if (!(difference instanceof Map) || !(((Map) difference).get('record') instanceof Map)) return difference
        Map record = new LinkedHashMap((Map) ((Map) difference).get('record'))
        record.put('data', DiffDetailClassifier.parseDiffData(record.get('data')))
        Map parsed = new LinkedHashMap((Map) difference)
        parsed.put('record', record)
        return parsed
    }

    /** A finished run's differences, read through the same tenant-checked reader as the result page. */
    private static Map<String, Object> runResultInput(ExecutionContext ec, String fileName) {
        if (!fileName?.trim()) {
            ec.message.addError('Choose a run result for the script to read.')
            return null
        }
        int pageSize = ReconciliationOutputSupport.MAX_DIFF_PAGE_SIZE
        List differences = []
        Map<String, Object> firstPage = null
        for (int pageIndex = 0; differences.size() < MAX_RUN_RESULT_DIFFERENCES; pageIndex++) {
            Map<String, Object> page = ReconciliationOutputSupport.getGeneratedOutputDifferences(ec, fileName, pageIndex, pageSize,
                    null, 'all', null, pageIndex == 0, null)
            if (ec.message.hasError()) return null
            if (firstPage == null) firstPage = page
            List rows = page.get('differences') instanceof List ? (List) page.get('differences') : []
            for (Object row : rows.take(MAX_RUN_RESULT_DIFFERENCES - differences.size())) differences.add(withParsedData(row))
            if (rows.size() < pageSize || pageIndex + 1 >= ((page.get('pageCount') ?: 0) as int)) break
        }

        int total = (firstPage?.get('totalDifferences') ?: differences.size()) as int
        boolean truncated = total > differences.size()
        Map input = [fileName: fileName, summary: firstPage?.get('summary') ?: [:], metadata: firstPage?.get('metadata') ?: [:],
                totalDifferences: total, truncated: truncated, differences: differences]
        return [input: input, inputSummary: [source: SOURCE_RUN_RESULT, fileName: fileName, records: differences.size(),
                totalDifferences: total, truncated: truncated]] as Map<String, Object>
    }
}
