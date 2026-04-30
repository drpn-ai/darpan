package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.sql.Timestamp

class ReconciliationOutputSupport {
    static final List<String> LEGACY_CSV_COLUMNS = ["type", "id", "presentIn", "missingIn", "note", "data"]
    static final List<String> RULESET_CSV_COLUMNS = ["diffType", "primaryId", "field", "file1Value", "file2Value", "presentIn", "missingIn", "ruleId", "severity", "message", "data"]

    static boolean isSupportedOutputFile(String fileName) {
        String lower = sourceFormatForFile(fileName)
        return lower == "json" || lower == "csv"
    }

    static boolean isGeneratedResultFile(String fileName) {
        String normalized = fileName?.toLowerCase() ?: ""
        return isSupportedOutputFile(normalized) && normalized.contains("_result")
    }

    static String sourceFormatForFile(String fileName) {
        String normalized = fileName?.toLowerCase() ?: ""
        if (normalized.endsWith(".csv")) return "csv"
        if (normalized.endsWith(".json")) return "json"
        return ""
    }

    static boolean isSafeOutputPath(Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (safePath == null || !isSupportedOutputFile(safePath)) return false
        if (safePath.contains("/")) return isGeneratedResultFile(safePath)
        return true
    }

    static File resolveGeneratedOutputFile(def ec, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath) return null

        File dataManagerFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        if (dataManagerFile?.exists() && dataManagerFile.isFile() && isGeneratedResultFile(dataManagerFile.name)) {
            return dataManagerFile
        }

        if (!safePath.contains("/")) {
            File legacyOutputDir = ec?.resource?.getLocationReference(TenantAccessSupport.resolveGenericOutputLocation(ec))?.getFile()
            File legacyFile = legacyOutputDir != null ? new File(legacyOutputDir, safePath) : null
            if (legacyFile?.exists() && legacyFile.isFile() && isSupportedOutputFile(legacyFile.name)) return legacyFile
        }

        return null
    }

    static boolean canAccessGeneratedOutputFile(def ec, File file, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath?.contains("/")) return true

        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false

        String outputCompanyUserGroupId = resolveGeneratedOutputTenantUserGroupId(ec, file, safePath)
        return outputCompanyUserGroupId && outputCompanyUserGroupId == activeTenantUserGroupId
    }

    static String resolveGeneratedOutputTenantUserGroupId(def ec, File file, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath) return null

        def runResult = ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
                ?.condition("resultDataManagerPath", safePath)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        String runResultTenantUserGroupId = normalize(runResult?.companyUserGroupId)
        if (runResultTenantUserGroupId) return runResultTenantUserGroupId

        Map<String, Object> outputDocument = parseOutputDocument(file)
        return normalize(outputDocument?.metadata instanceof Map ? outputDocument.metadata.companyUserGroupId : null)
    }

    static List<Map<String, Object>> listGeneratedOutputFiles(def ec) {
        List<Map<String, Object>> outputFiles = []
        Set<String> seenFileNames = [] as Set

        def resultFinder = ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
        if (resultFinder != null) {
            String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
            if (activeTenantUserGroupId) resultFinder.condition("companyUserGroupId", activeTenantUserGroupId)
            if (resultFinder.metaClass.respondsTo(resultFinder, "disableAuthz")) resultFinder.disableAuthz()
            if (resultFinder.metaClass.respondsTo(resultFinder, "useCache", Boolean)) resultFinder.useCache(false)
            (resultFinder.list() ?: []).each { runResult ->
                String fileName = DataManagerSupport.normalizeRelativePath(runResult.resultDataManagerPath)
                File file = resolveGeneratedOutputFile(ec, fileName)
                if (fileName && file?.exists() && file.isFile() && seenFileNames.add(fileName)) {
                    outputFiles.add([
                            file     : file,
                            fileName : fileName,
                            runResult: runResult,
                    ])
                }
            }
        }

        File runsRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveReconciliationRunsLocation(ec), false)
        if (runsRoot?.exists()) {
            runsRoot.eachFileRecurse(FileType.FILES) { File file ->
                if (isGeneratedResultFile(file.name)) {
                    String fileName = DataManagerSupport.relativeDataManagerPath(ec, file)
                    if (fileName && seenFileNames.add(fileName)) {
                        outputFiles.add([
                                file    : file,
                                fileName: fileName
                        ])
                    }
                }
            }
        }

        File legacyOutputDir = ec?.resource?.getLocationReference(TenantAccessSupport.resolveGenericOutputLocation(ec))?.getFile()
        if (legacyOutputDir?.exists()) {
            (legacyOutputDir.listFiles() ?: [] as File[])
                    .findAll { File file -> file.isFile() && isSupportedOutputFile(file.name) }
                    .each { File file ->
                        if (seenFileNames.add(file.name)) {
                            outputFiles.add([
                                    file    : file,
                                    fileName: file.name
                            ])
                        }
                    }
        }

        return outputFiles
    }

    static Map<String, Object> parseOutputDocument(File file) {
        if (file == null || !file.exists() || !file.isFile()) return [:]
        if (sourceFormatForFile(file.name) != "json") return [:]
        try {
            return parseGeneratedOutputText(file.getText("UTF-8"))
        } catch (Exception ignored) {
            return [:]
        }
    }

    static List<String> availableFormatsForSource(String sourceFormat) {
        switch ((sourceFormat ?: "").toLowerCase()) {
            case "json":
                return ["json", "csv"]
            case "csv":
                return ["csv"]
            default:
                return []
        }
    }

    static String sanitizeUploadFileName(String rawName, String fallbackBase = "file") {
        String normalized = rawName?.toString()?.trim()
        if (!normalized) return fallbackBase

        String stripped = normalized.tokenize("/\\") ? normalized.tokenize("/\\").last() : normalized
        String safe = stripped.replaceAll(/[^A-Za-z0-9._-]/, "_")
        return safe ?: fallbackBase
    }

    static Map<String, Object> parseGeneratedOutputText(String rawText) {
        if (!(rawText?.trim())) return [:]
        def parsed = new JsonSlurper().parseText(rawText)
        return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
    }

    static Map<String, Object> buildGeneratedOutputDescriptor(String fileName, Map<String, Object> diffDocument,
            long sizeBytes, Timestamp createdDate) {
        Map metadata = diffDocument?.metadata instanceof Map ? (Map) diffDocument.metadata : [:]
        Map summary = diffDocument?.summary instanceof Map ? (Map) diffDocument.summary : [:]
        String sourceFormat = sourceFormatForFile(fileName)

        return [
                fileName                : fileName,
                sourceFormat            : sourceFormat,
                availableFormats        : availableFormatsForSource(sourceFormat),
                preferredDownloadFormat : availableFormatsForSource(sourceFormat).contains("csv") ? "csv" : sourceFormat,
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
        ]
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
                descriptor?.reconciliationType
        ].any { value ->
            String normalizedValue = normalize(value)?.toLowerCase()
            normalizedValue?.contains(normalizedSearch)
        }
    }

    static String deriveDownloadFileName(String sourceFileName, String requestedFormat) {
        String format = (requestedFormat ?: "").toLowerCase()
        if (!format) return sourceFileName

        String normalizedFileName = sanitizeUploadFileName(sourceFileName, "reconciliation-output")
        int extensionIndex = normalizedFileName.lastIndexOf(".")
        String baseName = extensionIndex > 0 ? normalizedFileName.substring(0, extensionIndex) : normalizedFileName
        return "${baseName}.${format}"
    }

    static String contentTypeForFormat(String requestedFormat) {
        switch ((requestedFormat ?: "").toLowerCase()) {
            case "csv":
                return "text/csv; charset=UTF-8"
            case "json":
            default:
                return "application/json; charset=UTF-8"
        }
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
            return RULESET_CSV_COLUMNS
        }
        return LEGACY_CSV_COLUMNS
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

    protected static Long normalizeLong(Object value) {
        if (value == null) return null
        if (value instanceof Number) return ((Number) value).longValue()
        try {
            return Long.parseLong(value.toString().trim())
        } catch (Exception ignored) {
            return null
        }
    }

    protected static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    protected static String csvEscape(String rawValue) {
        String safeValue = rawValue ?: ""
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }
}
