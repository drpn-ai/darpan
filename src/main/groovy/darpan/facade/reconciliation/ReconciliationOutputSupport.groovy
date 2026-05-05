package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

class ReconciliationOutputSupport {
    static final List<String> LEGACY_CSV_COLUMNS = ["type", "id", "presentIn", "missingIn", "note", "data"]
    static final List<String> RULESET_CSV_COLUMNS = ["diffType", "primaryId", "field", "file1Value", "file2Value", "presentIn", "missingIn", "ruleId", "severity", "message", "data"]
    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_SUCCEEDED = "AUT_STAT_SUCCESS"
    static final String STATUS_FAILED = "AUT_STAT_FAILED"
    static final String STATUS_NO_DATA = "AUT_STAT_NO_DATA"
    static final String STATUS_SKIPPED_DUPLICATE = "AUT_STAT_SKIP_DUP"
    protected static final Set<String> ACTIVE_STATUSES = [STATUS_PENDING, STATUS_RUNNING] as Set
    protected static final Map<String, String> STATUS_LABELS = [
            (STATUS_PENDING)          : "Pending execution",
            (STATUS_RUNNING)          : "Running",
            (STATUS_SUCCEEDED)        : "Succeeded",
            (STATUS_FAILED)           : "Failed",
            (STATUS_NO_DATA)          : "No input data available",
            (STATUS_SKIPPED_DUPLICATE): "Skipped duplicate",
    ]
    protected static final Logger logger = LoggerFactory.getLogger(ReconciliationOutputSupport.class)

    static boolean isSupportedOutputFile(String fileName) {
        String lower = sourceFormatForFile(fileName)
        return lower == "json" || lower == "csv"
    }

    static boolean isGeneratedResultFile(String fileName) {
        String normalized = fileName?.toLowerCase() ?: ""
        String basename = normalized.tokenize("/\\") ? normalized.tokenize("/\\").last() : normalized
        int extensionIndex = basename.lastIndexOf(".")
        String nameRoot = extensionIndex > 0 ? basename.substring(0, extensionIndex) : basename
        return isSupportedOutputFile(basename) && (nameRoot == "result" || nameRoot.endsWith("_result") || nameRoot.endsWith("-result"))
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

    static boolean isSafeReadableArtifactPath(def ec, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (safePath == null || !isSupportedOutputFile(safePath)) return false
        if (!safePath.contains("/")) return isSafeOutputPath(safePath)
        return resolveRunResultForArtifactPath(ec, safePath) != null
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

    static File resolveGeneratedOutputArtifactFile(def ec, Object rawFileName) {
        File resultFile = resolveGeneratedOutputFile(ec, rawFileName)
        if (resultFile != null) return resultFile

        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath?.contains("/") || !isSupportedOutputFile(safePath)) return null
        if (resolveRunResultForArtifactPath(ec, safePath) == null) return null

        File dataManagerFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        return dataManagerFile?.exists() && dataManagerFile.isFile() ? dataManagerFile : null
    }

    static boolean canAccessGeneratedOutputFile(def ec, File file, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath?.contains("/")) return true

        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false

        String outputCompanyUserGroupId = resolveGeneratedOutputTenantUserGroupId(ec, file, safePath)
        return outputCompanyUserGroupId && outputCompanyUserGroupId == activeTenantUserGroupId
    }

    static def resolveRunResultForArtifactPath(def ec, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath?.contains("/")) return null

        for (String fieldName : ["resultDataManagerPath", "file1DataManagerPath", "file2DataManagerPath"]) {
            def finder = ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
            if (finder == null) continue
            def runResult = finder.condition(fieldName, safePath)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (runResult != null) return runResult
        }
        return null
    }

    static String resolveGeneratedOutputTenantUserGroupId(def ec, File file, Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (!safePath) return null

        def runResult = resolveRunResultForArtifactPath(ec, safePath)
        String runResultTenantUserGroupId = normalize(runResult?.companyUserGroupId)
        if (runResultTenantUserGroupId) return runResultTenantUserGroupId

        Map<String, Object> outputDocument = parseOutputDocument(file)
        return normalize(outputDocument?.metadata instanceof Map ? outputDocument.metadata.companyUserGroupId : null)
    }

    static List<Map<String, Object>> listGeneratedOutputFiles(def ec) {
        List<Map<String, Object>> outputFiles = []
        Set<String> seenFileNames = [] as Set
        Set<String> seenRunResultIds = [] as Set

        def resultFinder = ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
        if (resultFinder != null) {
            String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
            if (activeTenantUserGroupId) resultFinder.condition("companyUserGroupId", activeTenantUserGroupId)
            if (resultFinder.metaClass.respondsTo(resultFinder, "disableAuthz")) resultFinder.disableAuthz()
            if (resultFinder.metaClass.respondsTo(resultFinder, "useCache", Boolean)) resultFinder.useCache(false)
            (resultFinder.list() ?: []).each { runResult ->
                String fileName = DataManagerSupport.normalizeRelativePath(runResult.resultDataManagerPath)
                File file = fileName ? resolveGeneratedOutputFile(ec, fileName) : null
                if (fileName && file?.exists() && file.isFile() && seenFileNames.add(fileName)) {
                    outputFiles.add([
                            file       : file,
                            fileName   : fileName,
                            runResult  : runResult,
                            createdDate: timestampValue(runResult.createdDate) ?: new Timestamp(file.lastModified()),
                    ])
                } else if (!fileName && shouldListRunResultWithoutFile(runResult) &&
                        seenRunResultIds.add(normalize(runResult.reconciliationRunResultId) ?: "${normalize(runResult.savedRunId)}:${normalize(runResult.createdDate)}")) {
                    outputFiles.add([
                            file       : null,
                            fileName   : "",
                            runResult  : runResult,
                            createdDate: timestampValue(runResult.createdDate ?: runResult.startedDate ?: runResult.lastUpdatedDate),
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
                                file       : file,
                                fileName   : fileName,
                                createdDate: new Timestamp(file.lastModified()),
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
                                    file       : file,
                                    fileName   : file.name,
                                    createdDate: new Timestamp(file.lastModified()),
                            ])
                        }
                    }
        }

        return outputFiles
    }

    static Map<String, Object> listGeneratedOutputs(def ec, Object savedRunId, Object reconciliationMappingId,
            Object query, Object pageIndex, Object pageSize) {
        int page = Math.max(0, pageIndex instanceof Number ? pageIndex.intValue() :
                (pageIndex?.toString()?.trim()?.isInteger() ? pageIndex.toString().trim().toInteger() : 0))
        int size = Math.max(1, Math.min(200, pageSize instanceof Number ? pageSize.intValue() :
                (pageSize?.toString()?.trim()?.isInteger() ? pageSize.toString().trim().toInteger() : 20)))
        String savedRunIdFilter = ((savedRunId ?: reconciliationMappingId)?.toString()?.trim())
        String search = ((query)?.toString()?.trim())?.toLowerCase()

        List<Map<String, Object>> rows = []
        (listGeneratedOutputFiles(ec) ?: [])
                .sort { Map left, Map right -> Long.compare(outputRowSortTime(right), outputRowSortTime(left)) }
                .each { Map outputFile ->
                    File file = (File) outputFile.file
                    String fileName = outputFile.fileName as String
                    if (file != null && canAccessGeneratedOutputFile(ec, file, fileName)) {
                        Map<String, Object> outputDocument = parseOutputDocument(file)
                        Map<String, Object> descriptor = outputFile.runResult ?
                                buildRunResultDescriptor(ec, outputFile.runResult, file, outputDocument) :
                                buildGeneratedOutputDescriptor(fileName, outputDocument, file.length(), new Timestamp(file.lastModified()))

                        if (matchesGeneratedOutputDescriptor(descriptor, savedRunIdFilter, search)) rows.add(descriptor)
                    } else if (file == null && outputFile.runResult != null && canAccessRunResult(ec, outputFile.runResult)) {
                        Map<String, Object> descriptor = buildRunResultDescriptor(ec, outputFile.runResult, null, [:])
                        if (matchesGeneratedOutputDescriptor(descriptor, savedRunIdFilter, search)) rows.add(descriptor)
                    }
                }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                generatedOutputs: pageRows(rows, page, size),
                pagination      : pagination(page, size, rows.size()),
        ]
    }

    static Map<String, Object> getGeneratedOutput(def ec, Object fileName, Object format) {
        String fileNameValue = ((fileName)?.toString()?.trim())
        String requestedFormat = (((format)?.toString()?.trim()) ?: "json").toLowerCase()
        Map<String, Object> outputFile = null

        if (!fileNameValue) ec.message.addError("fileName is required")
        if (!ec.message.hasError() && !isSafeReadableArtifactPath(ec, fileNameValue)) {
            ec.message.addError("fileName is invalid")
        }

        String sourceFormat = sourceFormatForFile(fileNameValue)
        if (!ec.message.hasError() && !sourceFormat) {
            ec.message.addError("Unsupported generated output '${fileNameValue}'.")
        }

        boolean resultFileRequest = isSafeOutputPath(fileNameValue)
        List<String> availableFormats = resultFileRequest ? availableFormatsForSource(sourceFormat) : [sourceFormat]
        if (!ec.message.hasError() && !availableFormats.contains(requestedFormat)) {
            ec.message.addError("Format '${requestedFormat}' is not available for generated output '${fileNameValue}'.")
        }

        if (!ec.message.hasError()) {
            File generatedOutputFile = resolveGeneratedOutputArtifactFile(ec, fileNameValue)
            if (generatedOutputFile == null || !generatedOutputFile.exists() || !generatedOutputFile.isFile()) {
                ec.message.addError("Generated output '${fileNameValue}' was not found.")
            } else if (!canAccessGeneratedOutputFile(ec, generatedOutputFile, fileNameValue)) {
                ec.message.addError("Generated output '${fileNameValue}' is not available in your active tenant.")
            } else {
                String rawText = generatedOutputFile.getText("UTF-8")
                String contentText = rawText
                Map<String, Object> outputDocument = sourceFormat == "json" ? parseGeneratedOutputText(rawText) : [:]
                if (requestedFormat == "csv" && sourceFormat == "json" && resultFileRequest) {
                    contentText = renderDifferencesCsv(outputDocument)
                }

                String downloadSourceName = resultFileRequest ? fileNameValue : (sourceArtifactDisplayName(ec, fileNameValue) ?: fileNameValue)
                outputFile = [
                        fileName        : fileNameValue,
                        downloadFileName: deriveDownloadFileName(downloadSourceName, requestedFormat),
                        sourceFormat    : sourceFormat,
                        format          : requestedFormat,
                        contentType     : contentTypeForFormat(requestedFormat),
                        contentText     : contentText,
                ]
                Map<String, Object> sourceDetails = buildGeneratedOutputSourceDetails(ec, fileNameValue, outputDocument)
                if (sourceDetails) outputFile.sourceDetails = sourceDetails
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [outputFile: outputFile]
    }

    static Map<String, Object> deleteGeneratedOutputFile(def ec, Object filename) {
        String fileNameToDelete = normalize(filename)
        if (!fileNameToDelete) {
            throw new IllegalArgumentException("filename is required")
        }
        if (!isSafeOutputPath(fileNameToDelete)) {
            throw new IllegalArgumentException("Invalid filename")
        }

        File targetFile = resolveGeneratedOutputFile(ec, fileNameToDelete)
        if (targetFile == null || !targetFile.exists() || !targetFile.isFile()) {
            logger.warn("Delete requested for missing reconciliation output ${fileNameToDelete}")
            return [
                    deleted        : false,
                    deletedFileName: fileNameToDelete,
                    statusMessage  : "File not found: ${fileNameToDelete}",
            ]
        }
        if (!canAccessGeneratedOutputFile(ec, targetFile, fileNameToDelete)) {
            throw new IllegalArgumentException("Generated output '${fileNameToDelete}' is not available in your active tenant.")
        }

        boolean deletedOk = targetFile.delete()
        if (deletedOk) {
            logger.info("Deleted generated reconciliation output ${fileNameToDelete}")
        } else {
            logger.warn("Failed to delete generated reconciliation output ${fileNameToDelete}")
        }

        return [
                deleted        : deletedOk,
                deletedFileName: fileNameToDelete,
                statusMessage  : deletedOk ? "Deleted ${fileNameToDelete}" : "Unable to delete ${fileNameToDelete}",
        ]
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

    static Map<String, Object> buildGeneratedOutputSourceDetails(def ec, Object rawFileName, Map<String, Object> outputDocument) {
        def runResult = resolveRunResultForArtifactPath(ec, rawFileName)
        if (runResult == null) return null

        Map<String, Object> metadata = outputDocument?.metadata instanceof Map ? (Map<String, Object>) outputDocument.metadata : [:]
        Map<String, Object> file1Document = parseArtifactDocument(ec, runResult.file1DataManagerPath)
        Map<String, Object> file2Document = parseArtifactDocument(ec, runResult.file2DataManagerPath)
        def automationExecution = resolveAutomationExecutionForRunResult(ec, runResult)

        Map<String, Object> dateRange =
                firstDateRange(metadata) ?:
                dateRangeFromExecution(automationExecution) ?:
                firstDateRange(file1Document?.metadata instanceof Map ? (Map<String, Object>) file1Document.metadata : [:]) ?:
                firstDateRange(file2Document?.metadata instanceof Map ? (Map<String, Object>) file2Document.metadata : [:])

        List<Map<String, Object>> files = [
                sourceFileDescriptor(runResult, "file1", normalize(metadata.file1Label ?: metadata.json1Label) ?: "File 1"),
                sourceFileDescriptor(runResult, "file2", normalize(metadata.file2Label ?: metadata.json2Label) ?: "File 2"),
        ].findAll { it != null } as List<Map<String, Object>>

        if (!files && !dateRange) return null

        boolean isApiMode = isApiSourceDetailsMode(metadata, dateRange)
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

    protected static Map<String, Object> parseArtifactDocument(def ec, Object rawPath) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawPath)
        if (!safePath || sourceFormatForFile(safePath) != "json") return [:]

        File artifactFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        if (artifactFile == null || !artifactFile.exists() || !artifactFile.isFile()) return [:]

        try {
            return parseGeneratedOutputText(artifactFile.getText("UTF-8"))
        } catch (Exception ignored) {
            return [:]
        }
    }

    protected static def resolveAutomationExecutionForRunResult(def ec, def runResult) {
        String resultId = normalize(runResult?.reconciliationRunResultId)
        String resultPath = DataManagerSupport.normalizeRelativePath(runResult?.resultDataManagerPath)
        def finder = ec?.entity?.find("darpan.reconciliation.ReconciliationAutomationExecution")
        if (finder == null) return null

        if (resultId) {
            def execution = finder.condition("reconciliationRunResultId", resultId)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (execution != null) return execution
        }

        if (!resultPath) return null
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("resultDataManagerPath", resultPath)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    protected static Map<String, Object> sourceFileDescriptor(def runResult, String side, String label) {
        boolean file1 = side == "file1"
        String filePath = DataManagerSupport.normalizeRelativePath(file1 ? runResult?.file1DataManagerPath : runResult?.file2DataManagerPath)
        String fileName = normalize(file1 ? runResult?.file1Name : runResult?.file2Name) ?: fileNameFromPath(filePath)
        if (!filePath && !fileName) return null

        String sourceFormat = sourceFormatForFile(fileName ?: filePath)
        return [
                side            : side,
                label           : label,
                fileName        : fileName ?: filePath,
                filePath        : filePath,
                downloadFileName: fileName ?: fileNameFromPath(filePath),
                sourceFormat    : sourceFormat,
                canDownload     : Boolean.valueOf(filePath && sourceFormat),
        ].findAll { entry -> entry.value != null && entry.value != "" } as Map<String, Object>
    }

    protected static String fileNameFromPath(Object rawPath) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawPath)
        if (!safePath) return ""
        return safePath.tokenize("/\\") ? safePath.tokenize("/\\").last() : safePath
    }

    protected static String sourceArtifactDisplayName(def ec, Object rawPath) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawPath)
        def runResult = resolveRunResultForArtifactPath(ec, safePath)
        if (runResult == null) return ""
        if (safePath == DataManagerSupport.normalizeRelativePath(runResult.file1DataManagerPath)) {
            return normalize(runResult.file1Name)
        }
        if (safePath == DataManagerSupport.normalizeRelativePath(runResult.file2DataManagerPath)) {
            return normalize(runResult.file2Name)
        }
        return ""
    }

    protected static Map<String, Object> firstDateRange(Map<String, Object> metadata) {
        if (!metadata) return null
        String start = normalize(
                metadata.windowStart ?:
                metadata.windowStartUtc ?:
                metadata.windowStartDate ?:
                metadata.childWindowStart ?:
                metadata.childWindowStartDate ?:
                metadata.fromDate ?:
                metadata.dateFrom
        )
        String end = normalize(
                metadata.windowEnd ?:
                metadata.windowEndUtc ?:
                metadata.windowEndDate ?:
                metadata.childWindowEnd ?:
                metadata.childWindowEndDate ?:
                metadata.toDate ?:
                metadata.dateTo
        )
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
                statusEnumId            : STATUS_SUCCEEDED,
                statusLabel             : STATUS_LABELS[STATUS_SUCCEEDED],
                resultAvailable         : true,
        ]
    }

    static Map<String, Object> buildRunResultDescriptor(def ec, def runResult, File resultFile,
            Map<String, Object> outputDocument) {
        String fileName = DataManagerSupport.normalizeRelativePath(runResult?.resultDataManagerPath)
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

        String statusEnumId = normalize(runResult?.statusEnumId) ?: STATUS_SUCCEEDED
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

    static boolean isActiveRunResultStatus(Object rawStatusEnumId) {
        ACTIVE_STATUSES.contains(normalize(rawStatusEnumId))
    }

    protected static boolean shouldListRunResultWithoutFile(def runResult) {
        return normalize(runResult?.reconciliationRunResultId) && isActiveRunResultStatus(runResult?.statusEnumId)
    }

    protected static boolean canAccessRunResult(def ec, def runResult) {
        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false
        String resultTenantUserGroupId = normalize(runResult?.companyUserGroupId)
        return resultTenantUserGroupId && resultTenantUserGroupId == activeTenantUserGroupId
    }

    protected static long outputRowSortTime(Map<String, Object> outputFile) {
        File file = outputFile?.file instanceof File ? (File) outputFile.file : null
        if (file?.exists()) return file.lastModified()
        Timestamp timestamp = timestampValue(outputFile?.createdDate ?: outputFile?.runResult?.createdDate ?:
                outputFile?.runResult?.startedDate ?: outputFile?.runResult?.lastUpdatedDate)
        return timestamp?.time ?: 0L
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

        return STATUS_LABELS[normalizedStatusEnumId] ?: normalizedStatusEnumId
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

    protected static Map<String, Object> pagination(int page, int size, int totalCount) {
        return [
                pageIndex : page,
                pageSize  : size,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
        ]
    }

    protected static List<Map<String, Object>> pageRows(List<Map<String, Object>> rows, int page, int size) {
        int totalCount = rows.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return rows.subList(fromIndex, toIndex)
    }

    protected static String csvEscape(String rawValue) {
        String safeValue = rawValue ?: ""
        return "\"${safeValue.replace("\"", "\"\"")}\""
    }
}
