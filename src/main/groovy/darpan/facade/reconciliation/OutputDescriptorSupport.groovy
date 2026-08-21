package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.sql.Timestamp

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeLong

/**
 * Descriptor building, output/artifact document parsing, date-range derivation, and CSV rendering
 * for reconciliation generated outputs. Extracted from {@link ReconciliationOutputSupport}
 * (MACH P1 decomposition) with zero behavior change — that class remains the facade orchestrator
 * (and keeps thin static delegates for external callers) and owns the status constants referenced
 * here; path/file resolution helpers live in {@link OutputPathSupport}.
 */
class OutputDescriptorSupport {

    static Map<String, Object> parseOutputDocument(File file) {
        if (file == null || !file.exists() || !file.isFile()) return [:]
        if (OutputPathSupport.sourceFormatForFile(file.name) != "json") return [:]
        try {
            return parseGeneratedOutputText(file.getText("UTF-8"))
        } catch (Exception ignored) {
            return [:]
        }
    }

    static Map<String, Object> parseGeneratedOutputText(String rawText) {
        if (!(rawText?.trim())) return [:]
        def parsed = new JsonSlurper().parseText(rawText)
        return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
    }

    /** Source extract artifacts can be multi-GB, so only this bounded prefix may ever be read from them. */
    static final int ARTIFACT_HEADER_SCAN_CHARS = 64 * 1024

    /**
     * Bounded replacement for the retired full-artifact parse: reads at most
     * {@link #ARTIFACT_HEADER_SCAN_CHARS} from the artifact and extracts only a leading top-level
     * {@code metadata} object. Returns an empty map when the file is missing, not JSON, or the
     * metadata object is absent or unclosed within the scan window.
     */
    protected static Map<String, Object> readArtifactMetadataHeader(def ec, Object rawPath) {
        String safePath = OutputPathSupport.normalizeDataManagerRelativePath(ec, rawPath)
        if (!safePath || OutputPathSupport.sourceFormatForFile(safePath) != "json") return [:]

        File artifactFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        if (artifactFile == null || !artifactFile.exists() || !artifactFile.isFile()) return [:]

        try {
            char[] buffer = new char[ARTIFACT_HEADER_SCAN_CHARS]
            int read = 0
            artifactFile.withReader("UTF-8") { Reader reader ->
                while (read < buffer.length) {
                    int count = reader.read(buffer, read, buffer.length - read)
                    if (count < 0) break
                    read += count
                }
            }
            String metadataJson = extractJsonObjectForKey(new String(buffer, 0, Math.max(read, 0)), '"metadata"')
            if (!metadataJson) return [:]
            def parsed = new JsonSlurper().parseText(metadataJson)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    /** Quote/escape-aware extraction of the {@code {...}} value following keyToken; null if absent or unclosed. */
    protected static String extractJsonObjectForKey(String head, String keyToken) {
        int keyIndex = head != null ? head.indexOf(keyToken) : -1
        if (keyIndex < 0) return null
        int cursor = keyIndex + keyToken.length()
        while (cursor < head.length() && (head[cursor] == ":" || head[cursor].trim().isEmpty())) cursor++
        if (cursor >= head.length() || head[cursor] != "{") return null

        boolean inString = false
        boolean escaped = false
        int depth = 0
        for (int i = cursor; i < head.length(); i++) {
            String c = head[i]
            if (escaped) { escaped = false; continue }
            if (c == "\\") { if (inString) escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == "{") depth++
            else if (c == "}") { depth--; if (depth == 0) return head.substring(cursor, i + 1) }
        }
        return null
    }

    static Map<String, Object> buildGeneratedOutputSourceDetails(def ec, Object rawFileName, Map<String, Object> outputDocument) {
        def runResult = ReconciliationOutputSupport.resolveRunResultForArtifactPath(ec, rawFileName)
        if (runResult == null) return buildGeneratedOutputSourceDetailsFromArtifactFolder(ec, rawFileName, outputDocument)

        Map<String, Object> metadata = outputDocument?.metadata instanceof Map ? (Map<String, Object>) outputDocument.metadata : [:]
        return buildRunResultSourceDetails(ec, runResult, metadata)
    }

    /** Normalized result-file path for a run row, empty until WRITE_OUTPUT sets resultDataManagerPath. */
    static String resolveRunResultFileName(def ec, def runResult) {
        return OutputPathSupport.normalizeDataManagerRelativePath(ec, runResult?.resultDataManagerPath)
    }

    /**
     * Source details straight off a ReconciliationRunResult row, usable mid-run before any output
     * document exists. When metadata carries no labels the descriptor omits label entirely —
     * darpan-ui falls back to its per-side system labels, which beats baking in a "File 1"
     * placeholder here. File descriptors appear as extract stages populate the file paths on
     * the run row, which is what lets the live run view show files incrementally.
     */
    static Map<String, Object> buildRunResultSourceDetails(def ec, def runResult, Map<String, Object> metadata) {
        if (runResult == null) return null
        metadata = metadata ?: [:]
        def automationExecution = resolveAutomationExecutionForRunResult(ec, runResult)

        // Lazy chain: the run row's own window comes first because it is the only source that exists
        // for the whole life of the run -- the diff document is not written until WRITE_OUTPUT, and a
        // manually started run has no execution row at all. Artifact headers stay last, and
        // readArtifactMetadataHeader never reads past its bounded prefix.
        Map<String, Object> dateRange = firstDateRange(metadata) ?: dateRangeFromRunResult(runResult) ?:
                dateRangeFromExecution(automationExecution) ?:
                firstDateRange(readArtifactMetadataHeader(ec, runResult.file1DataManagerPath)) ?:
                firstDateRange(readArtifactMetadataHeader(ec, runResult.file2DataManagerPath))

        List<Map<String, Object>> files = [
                sourceFileDescriptor(ec, runResult, "file1", normalize(metadata.file1Label ?: metadata.json1Label)),
                sourceFileDescriptor(ec, runResult, "file2", normalize(metadata.file2Label ?: metadata.json2Label)),
        ].findAll { it != null } as List<Map<String, Object>>

        if (!files && !dateRange) return null

        boolean isApiMode = isApiSourceDetailsMode(metadata, dateRange) ||
                files.any { Map<String, Object> fileDescriptor -> (fileDescriptor.filePath as String)?.contains("-api/") }
        Map<String, Object> sourceDetails = [
                mode : isApiMode ? "API" : "FILES",
                files: files,
        ]
        if (dateRange) sourceDetails.dateRange = dateRange
        return sourceDetails
    }

    protected static Map<String, Object> buildGeneratedOutputSourceDetailsFromArtifactFolder(def ec, Object rawFileName,
            Map<String, Object> outputDocument) {
        String resultPath = OutputPathSupport.normalizeDataManagerRelativePath(ec, rawFileName)
        if (!resultPath?.contains("/") || !OutputPathSupport.isGeneratedResultFile(resultPath)) return null

        String runFolderPath = OutputPathSupport.parentPath(resultPath)
        if (!runFolderPath) return null

        Map<String, Object> metadata = outputDocument?.metadata instanceof Map ? (Map<String, Object>) outputDocument.metadata : [:]
        List<Map<String, Object>> files = [
                sourceFileDescriptorFromRunFolder(ec, runFolderPath, "file1", normalize(metadata.file1Label ?: metadata.json1Label) ?: "File 1"),
                sourceFileDescriptorFromRunFolder(ec, runFolderPath, "file2", normalize(metadata.file2Label ?: metadata.json2Label) ?: "File 2"),
        ].findAll { it != null } as List<Map<String, Object>>

        // Same lazy/bounded constraint as the run-result path: header prefix only, never a full parse.
        Map<String, Object> dateRange = firstDateRange(metadata) ?: files.findResult { Map<String, Object> fileDescriptor ->
            firstDateRange(readArtifactMetadataHeader(ec, fileDescriptor.filePath))
        }

        if (!files && !dateRange) return null

        boolean isApiMode = isApiSourceDetailsMode(metadata, dateRange) ||
                files.any { Map<String, Object> fileDescriptor -> (fileDescriptor.filePath as String)?.contains("-api/") }
        Map<String, Object> sourceDetails = [
                mode : isApiMode ? "API" : "FILES",
                files: files,
        ]
        if (dateRange) sourceDetails.dateRange = dateRange
        return sourceDetails
    }

    protected static boolean isApiSourceDetailsMode(Map<String, Object> metadata, Map<String, Object> dateRange) {
        String metadataMode = normalize(metadata?.inputMode ?: metadata?.sourceMode ?: metadata?.mode)?.toUpperCase()
        return dateRange != null || metadataMode?.contains("API") == true
    }

    protected static def resolveAutomationExecutionForRunResult(def ec, def runResult) {
        String resultId = normalize(runResult?.reconciliationRunResultId)
        List<String> resultPathCandidates = OutputPathSupport.dataManagerPathCandidates(ec, runResult?.resultDataManagerPath)
        def finder = ec?.entity?.find(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
        if (finder == null) return null

        if (resultId) {
            def execution = TenantScopedFinder.findTenantScoped(ec,
                    DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                    .condition("reconciliationRunResultId", resultId)
                    .useCache(false)
                    .one()
            if (execution != null) return execution
        }

        if (resultPathCandidates) {
            def execution = TenantScopedFinder.findTenantScoped(ec,
                    DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                    .condition("resultDataManagerPath", "in", resultPathCandidates)
                    .useCache(false)
                    .one()
            if (execution != null) return execution
        }
        return null
    }

    protected static Map<String, Object> sourceFileDescriptor(def ec, def runResult, String side, String label) {
        boolean file1 = side == "file1"
        String filePath = OutputPathSupport.normalizeDataManagerRelativePath(ec, file1 ? runResult?.file1DataManagerPath : runResult?.file2DataManagerPath)
        String fileName = normalize(file1 ? runResult?.file1Name : runResult?.file2Name) ?: fileNameFromPath(filePath)
        if (!filePath && !fileName) return null

        String sourceFormat = OutputPathSupport.sourceFormatForFile(fileName ?: filePath)
        return [
                side            : side,
                label           : label,
                fileName        : fileName ?: filePath,
                filePath        : filePath,
                downloadFileName: fileName ?: fileNameFromPath(filePath),
                sourceFormat    : sourceFormat,
                canDownload     : (filePath && sourceFormat) as Boolean,
        ].findAll { entry -> entry.value != null && entry.value != "" } as Map<String, Object>
    }

    protected static Map<String, Object> sourceFileDescriptorFromRunFolder(def ec, String runFolderPath, String side, String label) {
        File sourceFile = OutputPathSupport.resolveSourceArtifactFile(ec, runFolderPath, side)
        if (sourceFile == null) return null

        String filePath = DataManagerSupport.relativeDataManagerPath(ec, sourceFile)
        String sourceFormat = OutputPathSupport.sourceFormatForFile(sourceFile.name)
        return [
                side            : side,
                label           : label,
                fileName        : sourceFile.name,
                filePath        : filePath,
                downloadFileName: sourceFile.name,
                sourceFormat    : sourceFormat,
                canDownload     : (filePath && sourceFormat) as Boolean,
        ].findAll { entry -> entry.value != null && entry.value != "" } as Map<String, Object>
    }

    protected static Map<String, Object> firstDateRange(Map<String, Object> metadata) {
        if (!metadata) return null
        String start = normalize(
                metadata.windowStart ?:
                metadata.windowStartUtc ?:
                metadata.windowStartDate ?:
                metadata.childWindowStart ?:
                metadata.childWindowStartDate ?:
                isoFromEpochMillis(metadata.windowStartEpochMillis) ?:
                metadata.fromDate ?:
                metadata.dateFrom
        )
        String end = normalize(
                metadata.windowEnd ?:
                metadata.windowEndUtc ?:
                metadata.windowEndDate ?:
                metadata.childWindowEnd ?:
                metadata.childWindowEndDate ?:
                isoFromEpochMillis(metadata.windowEndEpochMillis) ?:
                metadata.toDate ?:
                metadata.dateTo
        )
        return start || end ? [start: start, end: end].findAll { entry -> entry.value } as Map<String, Object> : null
    }

    protected static String isoFromEpochMillis(Object value) {
        Long millis = normalizeLong(value)
        if (millis == null) return null
        try {
            return new Date(millis).toInstant().toString()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * The API window this run actually used, stamped onto the run row at beginRun. Runs created
     * before that column existed read null here and fall through to the older sources, so no
     * backfill is needed.
     */
    protected static Map<String, Object> dateRangeFromRunResult(def runResult) {
        if (runResult == null) return null
        String start = normalize(runResult.windowStartDate)
        String end = normalize(runResult.windowEndDate)
        return start || end ? [start: start, end: end].findAll { entry -> entry.value } as Map<String, Object> : null
    }

    protected static Map<String, Object> dateRangeFromExecution(def execution) {
        if (execution == null) return null
        String start = normalize(execution.childWindowStartDate ?: execution.windowStartDate)
        String end = normalize(execution.childWindowEndDate ?: execution.windowEndDate)
        return start || end ? [start: start, end: end].findAll { entry -> entry.value } as Map<String, Object> : null
    }

    static Map<String, Object> buildGeneratedOutputDescriptor(String fileName, Map<String, Object> diffDocument,
            long sizeBytes, Timestamp createdDate) {
        Map metadata = diffDocument?.metadata instanceof Map ? (Map) diffDocument.metadata : [:]
        Map summary = diffDocument?.summary instanceof Map ? (Map) diffDocument.summary : [:]
        String sourceFormat = OutputPathSupport.sourceFormatForFile(fileName)

        return [
                fileName                : fileName,
                sourceFormat            : sourceFormat,
                availableFormats        : OutputPathSupport.availableFormatsForSource(sourceFormat),
                preferredDownloadFormat : OutputPathSupport.availableFormatsForSource(sourceFormat).contains("csv") ? "csv" : sourceFormat,
                companyUserGroupId      : normalize(metadata.companyUserGroupId),
                savedRunId              : normalize(metadata.savedRunId ?: metadata.reconciliationMappingId ?: metadata.ruleSetId),
                savedRunName            : normalize(metadata.savedRunName ?: metadata.reconciliationMappingName ?: metadata.ruleSetName),
                savedRunType            : normalize(metadata.savedRunType ?: (normalize(metadata.ruleSetId) ? "ruleset" : (normalize(metadata.reconciliationMappingId) ? "mapping" : null))),
                reconciliationMappingId : normalize(metadata.reconciliationMappingId),
                mappingName             : normalize(metadata.reconciliationMappingName),
                ruleSetId               : normalize(metadata.ruleSetId),
                compareScopeId          : normalize(metadata.compareScopeId),
                compareScopeDescription : normalize(metadata.compareScopeDescription),
                reconciliationType      : normalize(metadata.reconciliation ?: metadata.reconciliationType),
                file1Label              : normalize(metadata.file1Label ?: metadata.json1Label),
                file2Label              : normalize(metadata.file2Label ?: metadata.json2Label),
                totalDifferences        : normalizeLong(summary.totalDifferences ?: summary.differenceCount),
                onlyInFile1Count        : normalizeLong(summary.onlyInFile1Count ?: summary.onlyInJson1Count),
                onlyInFile2Count        : normalizeLong(summary.onlyInFile2Count ?: summary.onlyInJson2Count),
                createdDate             : createdDate,
                sizeBytes               : sizeBytes,
                statusEnumId            : ReconciliationOutputSupport.STATUS_SUCCEEDED,
                statusLabel             : ReconciliationOutputSupport.STATUS_LABELS[ReconciliationOutputSupport.STATUS_SUCCEEDED],
                resultAvailable         : true,
        ]
    }

    static Map<String, Object> buildRunResultDescriptor(def ec, def runResult, File resultFile,
            Map<String, Object> outputDocument) {
        String fileName = OutputPathSupport.normalizeDataManagerRelativePath(ec, runResult?.resultDataManagerPath)
        boolean resultAvailable = fileName && resultFile?.exists() && resultFile.isFile()
        Map<String, Object> descriptor = resultAvailable ?
                buildGeneratedOutputDescriptor(fileName, outputDocument ?: [:], resultFile.length(), new Timestamp(resultFile.lastModified())) :
                [
                        fileName               : "",
                        sourceFormat           : "",
                        availableFormats       : [],
                        preferredDownloadFormat: null,
                        createdDate            : timestampValue(runResult?.createdDate ?: runResult?.startedDate ?: runResult?.lastUpdatedDate),
                        sizeBytes              : 0L,
                        resultAvailable        : false,
                ]

        String statusEnumId = normalize(runResult?.statusEnumId) ?: ReconciliationOutputSupport.STATUS_SUCCEEDED
        descriptor.reconciliationRunResultId = normalize(runResult?.reconciliationRunResultId)
        descriptor.companyUserGroupId = descriptor.companyUserGroupId ?: normalize(runResult?.companyUserGroupId)
        descriptor.savedRunId = descriptor.savedRunId ?: normalize(runResult?.savedRunId ?: runResult?.reconciliationMappingId ?: runResult?.ruleSetId)
        descriptor.savedRunType = descriptor.savedRunType ?: normalize(runResult?.savedRunType)
        descriptor.reconciliationMappingId = descriptor.reconciliationMappingId ?: normalize(runResult?.reconciliationMappingId)
        descriptor.ruleSetId = descriptor.ruleSetId ?: normalize(runResult?.ruleSetId)
        descriptor.compareScopeId = descriptor.compareScopeId ?: normalize(runResult?.compareScopeId)
        descriptor.reconciliationType = descriptor.reconciliationType ?: normalize(runResult?.reconciliationType)
        descriptor.totalDifferences = descriptor.totalDifferences ?: normalizeLong(runResult?.differenceCount)
        descriptor.onlyInFile1Count = descriptor.onlyInFile1Count ?: normalizeLong(runResult?.onlyInFile1Count)
        descriptor.onlyInFile2Count = descriptor.onlyInFile2Count ?: normalizeLong(runResult?.onlyInFile2Count)
        descriptor.createdDate = descriptor.createdDate ?: timestampValue(runResult?.createdDate ?: runResult?.startedDate ?: runResult?.lastUpdatedDate)
        descriptor.startedDate = timestampValue(runResult?.startedDate)
        descriptor.completedDate = timestampValue(runResult?.completedDate)
        descriptor.lastUpdatedDate = timestampValue(runResult?.lastUpdatedDate)
        descriptor.statusEnumId = statusEnumId
        descriptor.statusLabel = resolveStatusLabel(ec, statusEnumId)
        // Live-run fields maintained by RunObservability; null on legacy/terminal rows so the UI
        // can distinguish "no live detail" from a zero-progress run.
        descriptor.currentStage = normalize(runResult?.currentStage)
        Object progressPercent = runResult?.progressPercent
        descriptor.progressPercent = progressPercent != null ? (progressPercent as Integer) : null
        descriptor.resultAvailable = resultAvailable
        return descriptor
    }

    static boolean matchesGeneratedOutputDescriptor(Map<String, Object> descriptor, String savedRunId, String search) {
        String savedRunIdFilter = normalize(savedRunId)
        String descriptorSavedRunId = normalize(descriptor?.savedRunId ?: descriptor?.reconciliationMappingId ?: descriptor?.ruleSetId)
        if (savedRunIdFilter && descriptorSavedRunId != savedRunIdFilter) return false

        String normalizedSearch = normalize(search)?.toLowerCase()
        if (!normalizedSearch) return true

        return [
                descriptor?.fileName,
                descriptor?.savedRunId,
                descriptor?.savedRunName,
                descriptor?.savedRunType,
                descriptor?.reconciliationMappingId,
                descriptor?.mappingName,
                descriptor?.ruleSetId,
                descriptor?.compareScopeId,
                descriptor?.compareScopeDescription,
                descriptor?.file1Label,
                descriptor?.file2Label,
                descriptor?.reconciliationType,
                descriptor?.statusEnumId,
                descriptor?.statusLabel
        ].any { value ->
            String normalizedValue = normalize(value)?.toLowerCase()
            normalizedValue?.contains(normalizedSearch)
        }
    }

    protected static String resolveStatusLabel(def ec, String statusEnumId) {
        String normalizedStatusEnumId = normalize(statusEnumId)
        if (!normalizedStatusEnumId) return null

        try {
            def enumValue = ec?.entity?.find("moqui.basic.Enumeration")
                    ?.condition("enumId", normalizedStatusEnumId)
                    ?.useCache(true)
                    ?.one()
            if (enumValue) return FacadeSupport.enumLabel(enumValue)
        } catch (Exception ignored) {
        }

        return ReconciliationOutputSupport.STATUS_LABELS[normalizedStatusEnumId] ?: normalizedStatusEnumId
    }

    protected static long outputRowSortTime(Map<String, Object> outputFile) {
        File file = outputFile?.file instanceof File ? (File) outputFile.file : null
        if (file?.exists()) return file.lastModified()
        Timestamp timestamp = timestampValue(outputFile?.createdDate ?: outputFile?.runResult?.createdDate ?:
                outputFile?.runResult?.startedDate ?: outputFile?.runResult?.lastUpdatedDate)
        return timestamp?.time ?: 0L
    }

    static String renderDifferencesCsv(Map<String, Object> diffDocument) {
        List<Map<String, Object>> differences = ((diffDocument?.differences ?: []) as List)
                .collect { it instanceof Map ? (Map<String, Object>) it : [:] }
        List<String> csvColumns = selectCsvColumns(differences)

        StringBuilder csv = new StringBuilder(csvColumns.join(","))
        if (!differences.isEmpty()) csv.append("\n")

        differences.eachWithIndex { Map<String, Object> difference, int index ->
            List<String> values = csvColumns.collect { String columnName ->
                Object rawValue = extractCsvValue(difference, columnName)
                if (columnName == "data" && rawValue != null && !(rawValue instanceof CharSequence)) {
                    rawValue = JsonOutput.toJson(rawValue)
                }
                csvEscape(rawValue?.toString() ?: "")
            }
            csv.append(values.join(","))
            if (index + 1 < differences.size()) csv.append("\n")
        }

        return csv.toString()
    }

    protected static List<String> selectCsvColumns(List<Map<String, Object>> differences) {
        Map<String, Object> firstDifference = differences ? (differences[0] ?: [:]) : [:]
        if (firstDifference.containsKey("diffType") || firstDifference.containsKey("primaryId")) {
            return ReconciliationOutputSupport.RULESET_CSV_COLUMNS
        }
        return ReconciliationOutputSupport.LEGACY_CSV_COLUMNS
    }

    protected static Object extractCsvValue(Map<String, Object> difference, String columnName) {
        switch (columnName) {
            case "diffType":
                return difference.diffType ?: difference.type
            case "primaryId":
                return difference.primaryId ?: difference.id
            case "message":
                return difference.message ?: difference.note
            default:
                return difference[columnName]
        }
    }

    protected static Timestamp timestampValue(Object value) {
        if (value == null) return null
        if (value instanceof Timestamp) return (Timestamp) value
        if (value instanceof Date) return new Timestamp(value.time)
        try {
            return Timestamp.valueOf(value.toString())
        } catch (Exception ignored) {
            return null
        }
    }

    // CSV formula-injection guard: Excel / Google Sheets / Numbers execute cell content beginning with
    // =, +, -, @, tab, or CR as a formula (HYPERLINK, cmd|, etc.) when opening the CSV — even though it
    // is RFC-4180-valid text. Prefix such cells with a leading single quote so spreadsheet apps treat
    // them as text. This is the OWASP-recommended defense. We keep the outer "..." wrapping and the
    // "" double-quote escape; only the *content* gets the leading apostrophe.
    private static final Set<Character> CSV_FORMULA_TRIGGERS = ['='.charAt(0), '+'.charAt(0), '-'.charAt(0),
                                                                '@'.charAt(0), '\t'.charAt(0), '\r'.charAt(0)] as Set

    protected static String csvEscape(String rawValue) {
        String safeValue = rawValue ?: ""
        if (!safeValue.isEmpty() && CSV_FORMULA_TRIGGERS.contains(safeValue.charAt(0))) {
            safeValue = "'" + safeValue
        }
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }
}
