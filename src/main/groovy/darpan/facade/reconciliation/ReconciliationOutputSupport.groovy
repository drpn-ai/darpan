package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.io.FileType
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeLower

/**
 * Facade orchestrator for reconciliation generated outputs: list/get/delete/purge operations plus
 * the tenant/ownership gate cluster. Path/artifact/file resolution lives in
 * {@link OutputPathSupport}; descriptor building, document parsing, and CSV rendering live in
 * {@link OutputDescriptorSupport} (MACH P1 decomposition, zero behavior change). Thin static
 * delegates are kept at the bottom of this class for external callers of moved methods.
 */
class ReconciliationOutputSupport {
    // Audit #21 — bounds for server-side diff pagination (get#GeneratedOutputDifferences).
    static final int DEFAULT_DIFF_PAGE_SIZE = 50
    static final int MAX_DIFF_PAGE_SIZE = 500
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

    static boolean canAccessGeneratedOutputFile(def ec, File file, Object rawFileName) {
        String safePath = OutputPathSupport.normalizeDataManagerRelativePath(ec, rawFileName)
        if (!safePath?.contains("/")) return true

        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false

        String outputCompanyUserGroupId = resolveGeneratedOutputTenantUserGroupId(ec, file, safePath)
        return outputCompanyUserGroupId && outputCompanyUserGroupId == activeTenantUserGroupId
    }

    static def resolveRunResultForArtifactPath(def ec, Object rawFileName) {
        List<String> pathCandidates = OutputPathSupport.dataManagerPathCandidates(ec, rawFileName)
        if (pathCandidates.isEmpty()) return null

        // Ownership resolver: must scan all tenants to determine who owns a given file path.
        // Callers (canAccessGeneratedOutputFile) compare the resolved owner against the active
        // tenant — if we scoped here we'd miss foreign run-results and fail open.
        // Guard: ec.entity.find() may return null in lightweight validation contexts (e.g.
        // isSafeReadableArtifactPath called with a stub that has no entity backing).
        for (String fieldName : ["resultDataManagerPath", "file1DataManagerPath", "file2DataManagerPath"]) {
            def finder = ec?.entity?.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
            if (finder == null) continue
            // findGlobalUnscoped intent: global cross-tenant read for ownership; caller gates access.
            logger.debug("[tenant-scope] GLOBAL unscoped read of {} (fieldName={}): path-ownership resolver — caller enforces tenant access check after lookup",
                    DarpanEntityConstants.RECONCILIATION_RUN_RESULT, fieldName)
            def runResult = finder.disableAuthz()
                    .condition(fieldName, "in", pathCandidates)
                    .useCache(false)
                    .one()
            if (runResult != null) return runResult
        }
        return null
    }

    static String resolveGeneratedOutputTenantUserGroupId(def ec, File file, Object rawFileName) {
        String safePath = OutputPathSupport.normalizeDataManagerRelativePath(ec, rawFileName)
        if (!safePath) return null

        def runResult = resolveRunResultForArtifactPath(ec, safePath)
        String runResultTenantUserGroupId = normalize(runResult?.companyUserGroupId)
        if (runResultTenantUserGroupId) return runResultTenantUserGroupId

        // Audit 2026-06-11 #2/#20: before falling back to any tenant value parsed from the file itself
        // (which is attacker-influenceable if a file can be placed in the shared output tree), derive
        // ownership authoritatively from the entity that owns this run folder — the run id embedded in
        // the path keys a RuleSet / mapping / run-result, each carrying companyUserGroupId. The in-file
        // value below now triggers only for a genuinely orphaned file with no owning entity at all.
        String runFolderTenantUserGroupId = resolveTenantFromRunFolderEntity(ec, safePath)
        if (runFolderTenantUserGroupId) return runFolderTenantUserGroupId

        File tenantSourceFile = OutputPathSupport.isGeneratedResultFile(safePath) ? file : (OutputPathSupport.resolveSourceArtifactResultFile(ec, safePath) ?: file)
        Map<String, Object> outputDocument = OutputDescriptorSupport.parseOutputDocument(tenantSourceFile)
        return normalize(outputDocument?.metadata instanceof Map ? outputDocument.metadata.companyUserGroupId : null)
    }

    // Audit 2026-06-11 #2/#20: the run folder under reconciliation-runs/ is named after the owning run
    // id (savedRunId / ruleSetId / mappingId). Resolve the tenant from that owning entity — an
    // authoritative DB value — rather than trusting file content. Returns null when no owning entity
    // is found (genuinely orphaned artifact), leaving the caller's last-resort fallback in charge.
    protected static String resolveTenantFromRunFolderEntity(def ec, String safePath) {
        String runToken = OutputPathSupport.extractRunFolderToken(safePath)
        if (!runToken || ec?.entity == null) return null

        // Ownership resolver — must scan all tenants to resolve the authoritative owner of a
        // run folder by its token. Callers use the returned value to gate access.
        List<Map<String, Object>> runResults = (TenantScopedFinder.findGlobalUnscoped(ec,
                DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                "ownership resolver — caller enforces tenant access check with the returned companyUserGroupId")
                .condition("savedRunId", runToken).useCache(false).list() ?: []) as List<Map<String, Object>>
        String fromRunResult = normalize(runResults.collect { normalize(it.companyUserGroupId) }.find { it })
        if (fromRunResult) return fromRunResult

        def ruleSet = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RULE_SET,
                "ownership resolver — caller enforces tenant access check with the returned companyUserGroupId")
                .condition("ruleSetId", runToken).useCache(false).one()
        String fromRuleSet = normalize(ruleSet?.companyUserGroupId)
        if (fromRuleSet) return fromRuleSet

        def mapping = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING,
                "ownership resolver — caller enforces tenant access check with the returned companyUserGroupId")
                .condition("reconciliationMappingId", runToken).useCache(false).one()
        return normalize(mapping?.companyUserGroupId)
    }

    static List<Map<String, Object>> listGeneratedOutputFiles(def ec) {
        List<Map<String, Object>> outputFiles = []
        Set<String> seenFileNames = [] as Set
        Set<String> seenRunResultIds = [] as Set

        // P0 #4 step 4a: route through TenantScopedFinder (default-deny when no active tenant).
        // Previously the companyUserGroupId condition was applied only when activeTenantUserGroupId
        // was non-null, causing a global fall-open for unauthenticated/no-tenant callers. The finder
        // applies an impossible sentinel condition when no tenant is active so the list is always
        // empty — never all rows (default-deny).
        def resultFinder = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
        if (resultFinder != null) {
            if (resultFinder.metaClass.respondsTo(resultFinder, "useCache", Boolean)) resultFinder.useCache(false)
            (resultFinder.list() ?: []).each { runResult ->
                String fileName = OutputPathSupport.normalizeDataManagerRelativePath(ec, runResult.resultDataManagerPath)
                File file = fileName ? OutputPathSupport.resolveGeneratedOutputFile(ec, fileName) : null
                if (fileName && file?.exists() && file.isFile() && seenFileNames.add(fileName)) {
                    outputFiles.add([
                            file       : file,
                            fileName   : fileName,
                            runResult  : runResult,
                            createdDate: OutputDescriptorSupport.timestampValue(runResult.createdDate) ?: new Timestamp(file.lastModified()),
                    ])
                } else if (!fileName && shouldListRunResultWithoutFile(runResult) &&
                        seenRunResultIds.add(normalize(runResult.reconciliationRunResultId) ?: "${normalize(runResult.savedRunId)}:${normalize(runResult.createdDate)}")) {
                    outputFiles.add([
                            file       : null,
                            fileName   : "",
                            runResult  : runResult,
                            createdDate: OutputDescriptorSupport.timestampValue(runResult.createdDate ?: runResult.startedDate ?: runResult.lastUpdatedDate),
                    ])
                }
            }
        }

        File runsRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveReconciliationRunsLocation(ec), false)
        if (runsRoot?.exists()) {
            runsRoot.eachFileRecurse(FileType.FILES) { File file ->
                if (OutputPathSupport.isGeneratedResultFile(file.name)) {
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
                    .findAll { File file -> file.isFile() && OutputPathSupport.isSupportedOutputFile(file.name) }
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
        int page = boundedInt(pageIndex, 0, 0, Integer.MAX_VALUE)
        int size = boundedInt(pageSize, 20, 1, 200)
        String savedRunIdFilter = normalize(savedRunId ?: reconciliationMappingId)
        String search = normalizeLower(query)

        List<Map<String, Object>> rows = []
        (listGeneratedOutputFiles(ec) ?: [])
                .sort { Map left, Map right -> Long.compare(OutputDescriptorSupport.outputRowSortTime(right), OutputDescriptorSupport.outputRowSortTime(left)) }
                .each { Map outputFile ->
                    File file = (File) outputFile.file
                    String fileName = outputFile.fileName as String
                    if (file != null && canAccessGeneratedOutputFile(ec, file, fileName)) {
                        Map<String, Object> outputDocument = OutputDescriptorSupport.parseOutputDocument(file)
                        Map<String, Object> descriptor = outputFile.runResult ?
                                OutputDescriptorSupport.buildRunResultDescriptor(ec, outputFile.runResult, file, outputDocument) :
                                OutputDescriptorSupport.buildGeneratedOutputDescriptor(fileName, outputDocument, file.length(), new Timestamp(file.lastModified()))

                        if (OutputDescriptorSupport.matchesGeneratedOutputDescriptor(descriptor, savedRunIdFilter, search)) rows.add(descriptor)
                    } else if (file == null && outputFile.runResult != null && canAccessRunResult(ec, outputFile.runResult)) {
                        Map<String, Object> descriptor = OutputDescriptorSupport.buildRunResultDescriptor(ec, outputFile.runResult, null, [:])
                        if (OutputDescriptorSupport.matchesGeneratedOutputDescriptor(descriptor, savedRunIdFilter, search)) rows.add(descriptor)
                    }
                }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                generatedOutputs: PaginationSupport.pageRows(rows, page, size),
                pagination      : PaginationSupport.pagination(page, size, rows.size()),
        ]
    }

    static Map<String, Object> getGeneratedOutput(def ec, Object fileName, Object format) {
        String fileNameValue = normalize(fileName)
        String requestedFormat = normalizeLower(format) ?: "json"
        Map<String, Object> outputFile = null

        if (!fileNameValue) ec.message.addError("fileName is required")
        if (!ec.message.hasError() && !OutputPathSupport.isSafeReadableArtifactPath(ec, fileNameValue)) {
            ec.message.addError("fileName is invalid")
        }

        String sourceFormat = OutputPathSupport.sourceFormatForFile(fileNameValue)
        if (!ec.message.hasError() && !sourceFormat) {
            ec.message.addError("Unsupported generated output '${fileNameValue}'.")
        }

        boolean resultFileRequest = OutputPathSupport.isSafeOutputPath(fileNameValue)
        List<String> availableFormats = resultFileRequest ? OutputPathSupport.availableFormatsForSource(sourceFormat) : [sourceFormat]
        if (!ec.message.hasError() && !availableFormats.contains(requestedFormat)) {
            ec.message.addError("Format '${requestedFormat}' is not available for generated output '${fileNameValue}'.")
        }

        if (!ec.message.hasError()) {
            File generatedOutputFile = OutputPathSupport.resolveGeneratedOutputArtifactFile(ec, fileNameValue)
            if (generatedOutputFile == null || !generatedOutputFile.exists() || !generatedOutputFile.isFile()) {
                ec.message.addError("Generated output '${fileNameValue}' was not found.")
            } else if (!canAccessGeneratedOutputFile(ec, generatedOutputFile, fileNameValue)) {
                ec.message.addError("Generated output '${fileNameValue}' is not available in your active tenant.")
            } else {
                String rawText = generatedOutputFile.getText("UTF-8")
                String contentText = rawText
                Map<String, Object> outputDocument = sourceFormat == "json" ? OutputDescriptorSupport.parseGeneratedOutputText(rawText) : [:]
                if (requestedFormat == "csv" && sourceFormat == "json" && resultFileRequest) {
                    contentText = OutputDescriptorSupport.renderDifferencesCsv(outputDocument)
                }

                String downloadSourceName = resultFileRequest ? fileNameValue : (OutputPathSupport.sourceArtifactDisplayName(ec, fileNameValue) ?: fileNameValue)
                outputFile = [
                        fileName        : fileNameValue,
                        downloadFileName: OutputPathSupport.deriveDownloadFileName(downloadSourceName, requestedFormat),
                        sourceFormat    : sourceFormat,
                        format          : requestedFormat,
                        contentType     : OutputPathSupport.contentTypeForFormat(requestedFormat),
                        contentText     : contentText,
                ]
                Map<String, Object> sourceDetails = OutputDescriptorSupport.buildGeneratedOutputSourceDetails(ec, fileNameValue, outputDocument)
                if (sourceDetails) outputFile.sourceDetails = sourceDetails
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [outputFile: outputFile]
    }

    /**
     * Audit 2026-06-11 #21 — return a BOUNDED page of a JSON diff document's {@code differences}
     * (plus whole-document facets, effective summary, metadata, and a download descriptor WITHOUT the
     * file body) so darpan-ui never has to load/parse/hold the entire diff file. Reuses the exact same
     * tenant access control as {@link #getGeneratedOutput}; differs only in that it parses the file
     * server-side and ships one page instead of {@code contentText}.
     *
     * Filtering (active buckets / rule selector / record-id search) and the facet/summary math are a
     * faithful port of the run-result page; see {@link DiffDetailClassifier}.
     */
    static Map<String, Object> getGeneratedOutputDifferences(def ec, Object fileName, Object pageIndex,
                                                             Object pageSize, Object buckets, Object ruleFilterKey,
                                                             Object search, Object includeFacets) {
        String fileNameValue = normalize(fileName)
        Map<String, Object> result = null

        if (!fileNameValue) ec.message.addError("fileName is required")
        if (!ec.message.hasError() && !OutputPathSupport.isSafeReadableArtifactPath(ec, fileNameValue)) {
            ec.message.addError("fileName is invalid")
        }

        String sourceFormat = OutputPathSupport.sourceFormatForFile(fileNameValue)
        if (!ec.message.hasError() && !sourceFormat) {
            ec.message.addError("Unsupported generated output '${fileNameValue}'.")
        }
        // Pagination only applies to the JSON diff *result* document, not raw source artifacts.
        boolean resultFileRequest = OutputPathSupport.isSafeOutputPath(fileNameValue)
        if (!ec.message.hasError() && (sourceFormat != "json" || !resultFileRequest)) {
            ec.message.addError("Generated output '${fileNameValue}' does not support difference pagination.")
        }

        if (!ec.message.hasError()) {
            File generatedOutputFile = OutputPathSupport.resolveGeneratedOutputArtifactFile(ec, fileNameValue)
            if (generatedOutputFile == null || !generatedOutputFile.exists() || !generatedOutputFile.isFile()) {
                ec.message.addError("Generated output '${fileNameValue}' was not found.")
            } else if (!canAccessGeneratedOutputFile(ec, generatedOutputFile, fileNameValue)) {
                ec.message.addError("Generated output '${fileNameValue}' is not available in your active tenant.")
            } else {
                Map<String, Object> document = OutputDescriptorSupport.parseGeneratedOutputText(generatedOutputFile.getText("UTF-8"))
                Map<String, Object> metadata = (document?.get("metadata") instanceof Map) ?
                        (Map<String, Object>) document.get("metadata") : [:]
                String file1Label = DiffDetailClassifier.normalizeText(metadata.get("file1Label")) ?: "File 1"
                String file2Label = DiffDetailClassifier.normalizeText(metadata.get("file2Label")) ?: "File 2"

                List<String> bucketList = parseBucketSelection(buckets)
                int requestedPageIndex = boundedInt(pageIndex, 0, 0, Integer.MAX_VALUE)
                int requestedPageSize = boundedInt(pageSize, DEFAULT_DIFF_PAGE_SIZE, 1, MAX_DIFF_PAGE_SIZE)
                boolean wantFacets = (includeFacets == null) ? true : (includeFacets as boolean)
                String searchValue = normalize(search)
                String ruleKey = normalize(ruleFilterKey) ?: DiffDetailClassifier.ALL_RULE_FILTER_KEY

                Map<String, Object> page = DiffDetailClassifier.buildDifferencesPage(
                        document, file1Label, file2Label, bucketList, ruleKey, searchValue,
                        requestedPageIndex, requestedPageSize, wantFacets)
                Map<String, Object> summary = DiffDetailClassifier.buildEffectiveSummary(document, file1Label, file2Label)

                Map<String, Object> outputFile = [
                        fileName        : fileNameValue,
                        downloadFileName: OutputPathSupport.deriveDownloadFileName(fileNameValue, "json"),
                        sourceFormat    : sourceFormat,
                        format          : "json",
                        contentType     : OutputPathSupport.contentTypeForFormat("json"),
                ]
                Map<String, Object> sourceDetails = OutputDescriptorSupport.buildGeneratedOutputSourceDetails(ec, fileNameValue, document)
                if (sourceDetails) outputFile.sourceDetails = sourceDetails

                // metadata.timestamp is a zone-less wall clock written in whatever zone the JVM that
                // ran the reconciliation happened to use (see ReconciliationServices
                // .formatMetadataTimestamp). darpan-ui re-parses it with `new Date(string)`, which
                // assumes the BROWSER's zone, then renders it in the tenant's display zone — two
                // stacked guesses that moved a 09:10 UTC run to "Aug 25, 4:40 PM" for an IST viewer
                // on an America/Los_Angeles tenant: a day early and 9h30m out.
                //
                // ReconciliationRunResult.createdDate is a real instant, so it needs no parsing
                // guess at all. Shipping it alongside the string also repairs every run ALREADY on
                // disk, which re-stamping the files going forward can never do. Tenant access was
                // enforced by canAccessGeneratedOutputFile above, so this read is already gated.
                def artifactRunResult = resolveRunResultForArtifactPath(ec, fileNameValue)
                Object runResultCreatedDate = OutputDescriptorSupport.timestampValue(artifactRunResult?.createdDate)
                if (runResultCreatedDate != null) {
                    metadata = new LinkedHashMap<String, Object>(metadata)
                    metadata.put("createdDate", runResultCreatedDate)
                }

                result = [metadata: metadata, summary: summary, outputFile: outputFile]
                result.putAll(page)
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + (result ?: [:])
    }

    /** Accept the buckets filter as a comma-separated string or a list; null/empty means "all buckets". */
    static List<String> parseBucketSelection(Object buckets) {
        if (buckets == null) return null
        List<String> values = []
        if (buckets instanceof CharSequence) {
            buckets.toString().split(",").each { String token ->
                String trimmed = token?.trim()
                if (trimmed) values.add(trimmed)
            }
        } else if (buckets instanceof Iterable) {
            ((Iterable) buckets).each { Object token ->
                String trimmed = token?.toString()?.trim()
                if (trimmed) values.add(trimmed)
            }
        }
        return values.isEmpty() ? null : values
    }

    static Map<String, Object> deleteGeneratedOutputFile(def ec, Object filename) {
        String fileNameToDelete = normalize(filename)
        if (!fileNameToDelete) {
            throw new IllegalArgumentException("filename is required")
        }
        if (!OutputPathSupport.isSafeOutputPath(fileNameToDelete)) {
            throw new IllegalArgumentException("Invalid filename")
        }

        File targetFile = OutputPathSupport.resolveGeneratedOutputFile(ec, fileNameToDelete)
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

    static Map<String, Object> purgeGeneratedOutputFiles(def ec, Integer retentionDays, String outputLocation,
            boolean usingDefaultOutputLocation) {
        def outputDirRef = ec.resource.getLocationReference(outputLocation)
        long cutoffMillis = ec.user.nowTimestamp.getTime() - (retentionDays * 24L * 60L * 60L * 1000L)
        int scanned = 0
        int deletedRows = 0
        int retained = 0
        List<Map> failed = []

        def scanOutputFile = { String fileName, long lastModified, Closure<Boolean> deleteFile ->
            if (!fileName) return
            String lowerName = fileName.toLowerCase()
            if (usingDefaultOutputLocation) {
                if (!OutputPathSupport.isGeneratedResultFile(fileName)) return
            } else if (!(lowerName.endsWith(".csv") || lowerName.endsWith(".json"))) {
                return
            }

            scanned++
            if (lastModified > cutoffMillis) {
                retained++
                return
            }

            try {
                boolean deletedOk = deleteFile.call()
                if (deletedOk) {
                    deletedRows++
                } else {
                    failed.add([fileName: fileName, errorMessage: "Delete returned false"])
                }
            } catch (Exception e) {
                failed.add([fileName: fileName, errorMessage: e.message ?: "Delete failed"])
            }
        }
        def scanEntry = { entry ->
            if (!entry.isFile()) return

            scanOutputFile(entry.getFileName(), entry.getLastModified() ?: 0L) {
                def entryFile = entry.getFile()
                if (entryFile != null) return entryFile.delete()
                if (entry.metaClass.respondsTo(entry, "delete")) return entry.delete()
                return false
            }
        }
        def scanFile = { File file ->
            if (!file.isFile()) return
            scanOutputFile(file.name, file.lastModified()) { -> file.delete() }
        }

        if (outputDirRef != null && outputDirRef.getExists()) {
            File outputDirFile = outputDirRef.getFile()
            if (usingDefaultOutputLocation && outputDirFile?.exists()) {
                outputDirFile.eachFileRecurse(FileType.FILES) { File file ->
                    scanFile(file)
                }
            } else {
                def entries = outputDirRef.getDirectoryEntries() ?: []
                entries.each { entry -> scanEntry(entry) }
            }
        }

        String statusMessage = "Purge complete. Scanned=${scanned}, Deleted=${deletedRows}, Retained=${retained}, Failed=${failed.size()}"
        if (failed) {
            logger.warn("Reconciliation purge completed with failures: ${statusMessage}")
        } else {
            logger.info("Reconciliation purge completed: ${statusMessage}")
        }

        return [
                retentionDays : retentionDays,
                outputLocation: outputLocation,
                cutoffTimestamp: cutoffMillis,
                scannedCount  : scanned,
                deletedCount  : deletedRows,
                retainedCount : retained,
                failedFiles   : failed,
                statusMessage : statusMessage,
        ]
    }

    static boolean isActiveRunResultStatus(Object rawStatusEnumId) {
        ACTIVE_STATUSES.contains(normalize(rawStatusEnumId))
    }

    protected static boolean shouldListRunResultWithoutFile(def runResult) {
        // FAILED rows are listed too: a failed run produces no file, and hiding it made failures
        // invisible in run history (the UI's optimistic Running card ghosted with no failure shown).
        String statusEnumId = normalize(runResult?.statusEnumId)
        return normalize(runResult?.reconciliationRunResultId) &&
                (isActiveRunResultStatus(statusEnumId) || STATUS_FAILED == statusEnumId)
    }

    protected static boolean canAccessRunResult(def ec, def runResult) {
        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false
        String resultTenantUserGroupId = normalize(runResult?.companyUserGroupId)
        return resultTenantUserGroupId && resultTenantUserGroupId == activeTenantUserGroupId
    }

    // ==================================================================================================
    // Compatibility delegates — methods below moved to OutputPathSupport / OutputDescriptorSupport in
    // the MACH P1 decomposition but keep thin static delegates here because they have external callers
    // (runSavedRunDiff.groovy, runGenericDiff.groovy, NavigationSearchSupport.groovy,
    // ReconciliationFacadeServices.xml) and/or are exercised on this class by the characterization
    // harness ReconciliationOutputSupportTests (which must pass unchanged). Signatures are exact copies.
    // ==================================================================================================

    static boolean isSafeOutputPath(Object rawFileName) { return OutputPathSupport.isSafeOutputPath(rawFileName) }

    static boolean isSafeReadableArtifactPath(def ec, Object rawFileName) { return OutputPathSupport.isSafeReadableArtifactPath(ec, rawFileName) }

    static String sourceFormatForFile(String fileName) { return OutputPathSupport.sourceFormatForFile(fileName) }

    static File resolveGeneratedOutputFile(def ec, Object rawFileName) { return OutputPathSupport.resolveGeneratedOutputFile(ec, rawFileName) }

    static File resolveGeneratedOutputArtifactFile(def ec, Object rawFileName) { return OutputPathSupport.resolveGeneratedOutputArtifactFile(ec, rawFileName) }

    static String sanitizeUploadFileName(String rawName, String fallbackBase = "file") { return OutputPathSupport.sanitizeUploadFileName(rawName, fallbackBase) }

    protected static String sourceArtifactDisplayName(def ec, Object rawPath) { return OutputPathSupport.sourceArtifactDisplayName(ec, rawPath) }

    static Map<String, Object> parseGeneratedOutputText(String rawText) { return OutputDescriptorSupport.parseGeneratedOutputText(rawText) }

    static Map<String, Object> buildGeneratedOutputDescriptor(String fileName, Map<String, Object> diffDocument,
            long sizeBytes, Timestamp createdDate) { return OutputDescriptorSupport.buildGeneratedOutputDescriptor(fileName, diffDocument, sizeBytes, createdDate) }

    static Map<String, Object> buildRunResultDescriptor(def ec, def runResult, File resultFile,
            Map<String, Object> outputDocument) { return OutputDescriptorSupport.buildRunResultDescriptor(ec, runResult, resultFile, outputDocument) }

    static boolean matchesGeneratedOutputDescriptor(Map<String, Object> descriptor, String savedRunId, String search) { return OutputDescriptorSupport.matchesGeneratedOutputDescriptor(descriptor, savedRunId, search) }

    static Map<String, Object> buildGeneratedOutputSourceDetails(def ec, Object rawFileName, Map<String, Object> outputDocument) { return OutputDescriptorSupport.buildGeneratedOutputSourceDetails(ec, rawFileName, outputDocument) }

    protected static boolean isApiSourceDetailsMode(Map<String, Object> metadata, Map<String, Object> dateRange) { return OutputDescriptorSupport.isApiSourceDetailsMode(metadata, dateRange) }

    static String renderDifferencesCsv(Map<String, Object> diffDocument) { return OutputDescriptorSupport.renderDifferencesCsv(diffDocument) }
}
