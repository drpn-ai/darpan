package darpan.script

import darpan.facade.reconciliation.ReconciliationOutputSupport

/**
 * DAR-BE-164. What the facade needs around ScriptSupervisor: a TenantScript row as the UI reads it, and the input a
 * run gets. The CRUD itself is XML actions (service/facade/TenantScriptFacadeServices.xml).
 */
class TenantScriptSupport {

    static final String SOURCE_SAMPLE = 'SAMPLE'
    static final String SOURCE_RUN_RESULT = 'RUN_RESULT'
    static final List<String> INPUT_SOURCES = [SOURCE_SAMPLE, SOURCE_RUN_RESULT].asImmutable()

    /** A RUN_RESULT input carries at most this many differences (four pages of the result reader). */
    static final int MAX_RUN_RESULT_DIFFERENCES = 2_000

    static String inputSourceOf(Object source) {
        String value = source?.toString()?.trim()?.toUpperCase(Locale.ROOT) ?: SOURCE_SAMPLE
        return INPUT_SOURCES.contains(value) ? value : null
    }

    /**
     * One difference as a script reads it. The result file keeps each record's fields as JSON text (record.data);
     * a script has no JSON parser, so the fields arrive parsed: d.record.data.itemsBackordered, not a string.
     */
    static Object scriptRow(Object row) {
        if (!(row instanceof Map) || !(((Map) row).get('record') instanceof Map)) return row
        Map record = new LinkedHashMap((Map) ((Map) row).get('record'))
        record.put('data', darpan.facade.reconciliation.DiffDetailClassifier.parseDiffData(record.get('data')))
        Map out = new LinkedHashMap((Map) row)
        out.put('record', record)
        return out
    }

    static Map<String, Object> toScriptMap(def row) {
        if (row == null) return null
        return [
                tenantScriptId : row.tenantScriptId,
                scriptName     : row.scriptName,
                description    : row.description,
                scriptProfile  : row.scriptProfile,
                inputSource    : row.inputSource,
                scriptBody     : row.scriptBody,
                sampleInputJson: row.sampleInputJson,
                isActive       : row.isActive != 'N',
                createdByUserId: row.createdByUserId,
                createdDate    : row.createdDate,
                lastUpdatedDate: row.lastUpdatedDate,
        ]
    }

    /**
     * The input for one run, read BEFORE the script starts (the evaluation itself touches no database). Returns
     * {input, inputSummary} or null with an error on ec.message.
     *  - SAMPLE: the JSON text given, else the saved sample.
     *  - RUN_RESULT: a finished run's differences, read through the same tenant-checked reader as the result page:
     *    {fileName, summary, metadata, totalDifferences, truncated, differences: [...]}.
     */
    static Map<String, Object> runInput(def ec, String inputSource, String inputJson, String savedSampleJson, String fileName) {
        if (inputSource == SOURCE_RUN_RESULT) {
            if (!fileName?.trim()) {
                ec.message.addError('Choose a run result for the script to read.')
                return null
            }
            List differences = []
            Map first = null
            int pageIndex = 0
            int pageSize = ReconciliationOutputSupport.MAX_DIFF_PAGE_SIZE
            while (differences.size() < MAX_RUN_RESULT_DIFFERENCES) {
                Map page = ReconciliationOutputSupport.getGeneratedOutputDifferences(ec, fileName, pageIndex, pageSize,
                        null, 'all', null, pageIndex == 0, null)
                if (ec.message.hasError()) return null
                if (first == null) first = page
                List rows = (page.differences instanceof List) ? (List) page.differences : []
                differences.addAll(rows.take(MAX_RUN_RESULT_DIFFERENCES - differences.size()).collect { scriptRow(it) })
                pageIndex++
                if (rows.size() < pageSize || pageIndex >= ((page.pageCount ?: 0) as int)) break
            }
            int total = ((first?.totalDifferences ?: differences.size()) as int)
            Map input = [
                    fileName        : fileName,
                    summary         : first?.summary ?: [:],
                    metadata        : first?.metadata ?: [:],
                    totalDifferences: total,
                    truncated       : total > differences.size(),
                    differences     : differences,
            ]
            return [input: input, inputSummary: [source: SOURCE_RUN_RESULT, fileName: fileName, records: differences.size(),
                                                 totalDifferences: total, truncated: total > differences.size()]]
        }
        String json = inputJson != null ? inputJson : savedSampleJson
        try {
            Object input = ScriptSupervisor.parseInputJson(json)
            return [input: input, inputSummary: [source: SOURCE_SAMPLE, characters: (json ?: '').length()]]
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
    }
}
