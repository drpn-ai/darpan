package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.ExtractReplaySupport
import groovy.io.FileType
import org.moqui.entity.EntityCondition
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
        if (!safePath) return false

        String activeTenantUserGroupId = normalize(TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        if (!activeTenantUserGroupId) return false

        // DAR-BE-280: a bare name (legacy flat layout) is the tenant's only if it sits in the tenant's own
        // output folder or one of the tenant's run rows names it; the data-manager root is shared.
        if (!safePath.contains("/")) return isOwnBareOutputFile(ec, file, safePath)

        String outputCompanyUserGroupId = resolveGeneratedOutputTenantUserGroupId(ec, file, safePath)
        return outputCompanyUserGroupId && outputCompanyUserGroupId == activeTenantUserGroupId
    }

    protected static boolean isOwnBareOutputFile(def ec, File file, String bareName) {
        File ownOutputDir = ec?.resource?.getLocationReference(TenantAccessSupport.resolveGenericOutputLocation(ec))?.getFile()
        if (file != null && ownOutputDir != null && file.canonicalFile.parentFile == ownOutputDir.canonicalFile) return true

        def finder = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
        return finder != null && finder.condition("resultDataManagerPath", bareName).useCache(false).count() > 0
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
        String stampedTenantUserGroupId = readStampedTenantUserGroupId(ec, file, safePath)
        if (runFolderTenantUserGroupId) {
            // DAR-BE-269: no run result names this file, so the folder's owner is whichever run holds that
            // id NOW. After an id is issued twice (a database restore while result folders survive), that is
            // another tenant's run. A file stamped for a different tenant has no owner: the stamp can only
            // deny, never grant, so a forged stamp still opens nothing.
            if (stampedTenantUserGroupId && stampedTenantUserGroupId != runFolderTenantUserGroupId) {
                logger.warn("[tenant-scope] result file {} is stamped for a tenant other than its run folder's owner; access denied", safePath)
                return null
            }
            return runFolderTenantUserGroupId
        }
        return stampedTenantUserGroupId
    }

    private static String readStampedTenantUserGroupId(def ec, File file, String safePath) {
        File tenantSourceFile = OutputPathSupport.isGeneratedResultFile(safePath) ? file : (OutputPathSupport.resolveSourceArtifactResultFile(ec, safePath) ?: file)
        Map<String, Object> outputDocument = OutputDescriptorSupport.readOutputDocumentHeader(tenantSourceFile)
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

    /**
     * With savedRunIdFilter (DAR-BE-146), the run rows are filtered in the query and only that run's
     * folder is walked, so one run's history no longer costs a walk of every run's files.
     */
    static List<Map<String, Object>> listGeneratedOutputFiles(def ec, String savedRunIdFilter = null) {
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
            // The same id fields, in the same order, that a row's descriptor names its saved run by.
            if (savedRunIdFilter) {
                def conditions = ec.entity.conditionFactory
                resultFinder.condition(conditions.makeCondition(["savedRunId", "reconciliationMappingId", "ruleSetId"].collect { String field ->
                    conditions.makeCondition(field, EntityCondition.EQUALS, savedRunIdFilter)
                }, EntityCondition.OR))
            }
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
        // A run's files sit under reconciliation-runs/<its id>/. An id that is not one plain folder name
        // names no folder there, so a request's savedRunId can never walk outside it.
        if (savedRunIdFilter) {
            runsRoot = savedRunIdFilter ==~ /[A-Za-z0-9_.\-]+/ && !(savedRunIdFilter in [".", ".."]) && runsRoot != null ?
                    new File(runsRoot, savedRunIdFilter) : null
        }
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

        // DAR-BE-146: a result document runs to GBs, so it is read only for a row this page returns.
        // Before paging, one is read only to answer a search, or for a flat legacy output: a run row or a
        // run folder already names the saved run, and listGeneratedOutputFiles filtered both by it.
        List<Map<String, Object>> rows = (listGeneratedOutputFiles(ec, savedRunIdFilter) ?: [])
                .findAll { Map<String, Object> outputFile ->
                    File file = (File) outputFile.file
                    file != null ? canAccessGeneratedOutputFile(ec, file, outputFile.fileName) :
                            outputFile.runResult != null && canAccessRunResult(ec, outputFile.runResult)
                }
                .sort { Map left, Map right -> Long.compare(OutputDescriptorSupport.outputRowSortTime(right), OutputDescriptorSupport.outputRowSortTime(left)) }
        if (search || savedRunIdFilter) {
            rows = rows.findAll { Map<String, Object> outputFile ->
                boolean runNamed = outputFile.runResult != null || OutputPathSupport.extractRunFolderToken(outputFile.fileName as String)
                (runNamed && !search) || OutputDescriptorSupport.matchesGeneratedOutputDescriptor(describeListedOutput(ec, outputFile), savedRunIdFilter, search)
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                generatedOutputs: PaginationSupport.pageRows(rows, page, size).collect { Map<String, Object> outputFile -> describeListedOutput(ec, outputFile) },
                pagination      : PaginationSupport.pagination(page, size, rows.size()),
        ]
    }

    /** A listed output's descriptor, built once per list: the only place the list reads a result document. */
    private static Map<String, Object> describeListedOutput(def ec, Map<String, Object> outputFile) {
        if (outputFile.descriptor == null) {
            File file = (File) outputFile.file
            Map<String, Object> outputDocument = file != null ? OutputDescriptorSupport.readOutputDocumentHeader(file) : [:]
            outputFile.descriptor = outputFile.runResult ?
                    OutputDescriptorSupport.buildRunResultDescriptor(ec, outputFile.runResult, file, outputDocument) :
                    OutputDescriptorSupport.buildGeneratedOutputDescriptor(outputFile.fileName as String, outputDocument, file.length(), new Timestamp(file.lastModified()))
        }
        return (Map<String, Object>) outputFile.descriptor
    }

    /**
     * The file-name checks every generated-output read starts with: present, safe to read, and a
     * supported format. Adds the error and returns the source format ("" when unsupported).
     */
    private static String checkGeneratedOutputFileName(def ec, String fileNameValue) {
        if (!fileNameValue) ec.message.addError("fileName is required")
        if (!ec.message.hasError() && !OutputPathSupport.isSafeReadableArtifactPath(ec, fileNameValue)) {
            ec.message.addError("fileName is invalid")
        }

        String sourceFormat = OutputPathSupport.sourceFormatForFile(fileNameValue)
        if (!ec.message.hasError() && !sourceFormat) {
            ec.message.addError("Unsupported generated output '${fileNameValue}'.")
        }
        return sourceFormat
    }

    /** The generated output's file when it exists and the active tenant may read it; else adds the error and returns null. */
    private static File accessibleGeneratedOutputFile(def ec, String fileNameValue) {
        File generatedOutputFile = OutputPathSupport.resolveGeneratedOutputArtifactFile(ec, fileNameValue)
        if (generatedOutputFile == null || !generatedOutputFile.exists() || !generatedOutputFile.isFile()
                || !canAccessGeneratedOutputFile(ec, generatedOutputFile, fileNameValue)) {
            ec.message.addError("Generated output '${fileNameValue}' was not found.")
            return null
        }
        return generatedOutputFile
    }

    static Map<String, Object> getGeneratedOutput(def ec, Object fileName, Object format) {
        String fileNameValue = normalize(fileName)
        String requestedFormat = normalizeLower(format) ?: "json"
        Map<String, Object> outputFile = null

        String sourceFormat = checkGeneratedOutputFileName(ec, fileNameValue)

        boolean resultFileRequest = OutputPathSupport.isSafeOutputPath(fileNameValue)
        List<String> availableFormats = resultFileRequest ? OutputPathSupport.availableFormatsForSource(sourceFormat) : [sourceFormat]
        if (!ec.message.hasError() && !availableFormats.contains(requestedFormat)) {
            ec.message.addError("Format '${requestedFormat}' is not available for generated output '${fileNameValue}'.")
        }

        if (!ec.message.hasError()) {
            File generatedOutputFile = accessibleGeneratedOutputFile(ec, fileNameValue)
            if (generatedOutputFile != null) {
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
                                                             Object search, Object includeFacets,
                                                             Object conclusionCode = null) {
        String fileNameValue = normalize(fileName)
        Map<String, Object> result = null

        String sourceFormat = checkGeneratedOutputFileName(ec, fileNameValue)
        // Pagination only applies to the JSON diff *result* document, not raw source artifacts.
        boolean resultFileRequest = OutputPathSupport.isSafeOutputPath(fileNameValue)
        if (!ec.message.hasError() && (sourceFormat != "json" || !resultFileRequest)) {
            ec.message.addError("Generated output '${fileNameValue}' does not support difference pagination.")
        }

        if (!ec.message.hasError()) {
            File generatedOutputFile = accessibleGeneratedOutputFile(ec, fileNameValue)
            if (generatedOutputFile != null) {
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

                def artifactRunResult = resolveRunResultForArtifactPath(ec, fileNameValue)
                // DAR-BE-299: the rule picker names each rule; the run's rules are read tenant-scoped (a run that is
                // not the active tenant's, or whose rules are gone, yields none and the picker keeps the formula).
                List runRules = wantFacets && artifactRunResult?.savedRunId ?
                        ReconciliationSavedRunSupport.collectRuleRows(ec, artifactRunResult.savedRunId) : []

                Map<String, Object> page = DiffDetailClassifier.buildDifferencesPage(
                        document, file1Label, file2Label, bucketList, ruleKey, searchValue,
                        requestedPageIndex, requestedPageSize, wantFacets, normalize(conclusionCode), runRules)
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
                Object runResultCreatedDate = OutputDescriptorSupport.timestampValue(artifactRunResult?.createdDate)
                String runResultId = normalize(artifactRunResult?.reconciliationRunResultId)
                if (runResultCreatedDate != null || runResultId) {
                    metadata = new LinkedHashMap<String, Object>(metadata)
                    if (runResultCreatedDate != null) metadata.put("createdDate", runResultCreatedDate)
                    // DAR-BE-384: the run this result belongs to, so the page can move an old long link to the run's short
                    // address without listing every output the tenant has.
                    if (runResultId) metadata.put("reconciliationRunResultId", runResultId)
                }

                result = [metadata: metadata, summary: summary, outputFile: outputFile]
                result.putAll(page)
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + (result ?: [:])
    }

    /** Accept the buckets filter as a comma-separated string or a list; null/empty means "all buckets". */
    private static List<String> parseBucketSelection(Object buckets) {
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
        if (targetFile == null || !targetFile.exists() || !targetFile.isFile()
                || !canAccessGeneratedOutputFile(ec, targetFile, fileNameToDelete)) {
            logger.warn("Delete requested for missing reconciliation output ${fileNameToDelete}")
            return [
                    deleted        : false,
                    deletedFileName: fileNameToDelete,
                    statusMessage  : "File not found: ${fileNameToDelete}",
            ]
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

    /**
     * DAR-BE-271 (Aditi 2026-11-13: raw extracts kept 7 days, for every tenant; a new run always reads fresh data).
     * Deletes the raw source extracts and their sidecars (exchange manifest, excluded rows, abandoned .partial files)
     * older than the cutoff, and nothing else:
     * <ul>
     *   <li>only under the default reconciliation-runs location, never a custom output location;</li>
     *   <li>only inside an extract folder, {@code <runOrExecutionId>/<yyyyMMdd-HHmmssSSS>} with an optional
     *   {@code -FILE_1}/{@code -FILE_2} and {@code -recheck} suffix (ExtractReplaySupport's layout);</li>
     *   <li>never a generated result file ({@link OutputPathSupport#isGeneratedResultFile}, the files the 15-day
     *   purge#GeneratedOutputFiles owns), even though a saved run writes its result into the same folder.</li>
     * </ul>
     * The scan also descends one level into {@code file1-api}, {@code file2-api} (interactive extracts and copy slices)
     * and {@code lookups} (lookup captures), with the same age rule and the same result-file exclusion.
     * A folder left empty is removed. Database rows (RunResult, AutomationExecution, findings) are untouched; their
     * stored extract paths simply stop resolving, which the download descriptor and replay already report in words.
     */
    static Map<String, Object> purgeExtractFiles(def ec, Integer retentionDays) {
        String runsLocation = DataManagerSupport.resolveReconciliationRunsLocation(ec)
        File runsDir = runsLocation ? ec.resource.getLocationReference(runsLocation)?.getFile() : null
        long cutoffMillis = ec.user.nowTimestamp.getTime() - (retentionDays * 24L * 60L * 60L * 1000L)
        int scanned = 0, deleted = 0, retained = 0, foldersRemoved = 0, inUseFolders = 0
        List<Map> failed = []
        if (runsDir != null && runsDir.isDirectory()) {
            Set<String> inUse = filesActiveRunsRead(ec)
            runsDir.listFiles()?.findAll { it.isDirectory() }?.each { File runDir ->
                runDir.listFiles()?.findAll { it.isDirectory() && EXTRACT_FOLDER.matcher(it.name).matches() }?.each { File extractDir ->
                    // A folder an active run still reads stays whole, sidecars included (S19 QA): a retry checks its files at
                    // plan time, and a purge between that check and its read would take them away.
                    String folderPrefix = extractDir.canonicalPath + File.separator
                    if (inUse.any { it.startsWith(folderPrefix) }) {
                        inUseFolders++
                        return
                    }
                    // DAR-BE-318: interactive extracts and copy slices sit one level deeper, in <ts>/file1-api and file2-api.
                    List<File> scanDirs = [extractDir]
                    extractDir.listFiles()?.findAll { it.isDirectory() && API_EXTRACT_FOLDER.matcher(it.name).matches() }
                            ?.each { scanDirs.add(it) }
                    scanDirs.each { File scanDir ->
                        scanDir.listFiles()?.findAll { it.isFile() }?.each { File file ->
                            String shown = "${runDir.name}/${extractDir.name}/${scanDir == extractDir ? '' : scanDir.name + '/'}${file.name}".toString()
                            if (OutputPathSupport.isGeneratedResultFile(file.name)) return
                            scanned++
                            if (file.lastModified() > cutoffMillis) { retained++; return }
                            try {
                                if (file.delete()) deleted++
                                else failed.add([fileName: shown, errorMessage: "Delete returned false"])
                            } catch (Exception e) {
                                failed.add([fileName: shown, errorMessage: e.message ?: "Delete failed"])
                            }
                        }
                        if (scanDir != extractDir && !scanDir.listFiles() && scanDir.delete()) foldersRemoved++
                    }
                    if (!extractDir.listFiles() && extractDir.delete()) foldersRemoved++
                }
            }
        }
        String statusMessage = "Extract purge complete. Scanned=${scanned}, Deleted=${deleted}, Retained=${retained}, " +
                "FoldersRemoved=${foldersRemoved}, InUseFolders=${inUseFolders}, Failed=${failed.size()}"
        if (failed) logger.warn("Reconciliation extract purge completed with failures: ${statusMessage}")
        else logger.info("Reconciliation extract purge completed: ${statusMessage}")
        return [retentionDays: retentionDays, outputLocation: runsLocation, cutoffTimestamp: cutoffMillis, scannedCount: scanned,
                deletedCount: deleted, retainedCount: retained, foldersRemovedCount: foldersRemoved, inUseFolderCount: inUseFolders,
                failedFiles: failed, statusMessage: statusMessage]
    }

    /**
     * The extract files an active run still reads, as canonical paths: the file1/file2 paths stamped on every tenant's pending
     * or running run result and automation execution. A retry queued against an older execution is a pending execution
     * stamped with that execution's files (ExtractReplaySupport.replayResults).
     */
    private static Set<String> filesActiveRunsRead(def ec) {
        String dataManagerLocation = DataManagerSupport.resolveDataManagerLocation(ec)
        File root = DataManagerSupport.resolveDirectoryFile(ec, dataManagerLocation, false)
        Set<String> paths = [] as Set
        [DarpanEntityConstants.RECONCILIATION_RUN_RESULT, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION].each { String entityName ->
            List rows = TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                    "extract purge: every tenant's active runs keep the extract files they read")
                    .condition("statusEnumId", "in", ACTIVE_STATUSES as List).useCache(false).list() ?: []
            rows.each { row ->
                ["file1DataManagerPath", "file2DataManagerPath"].each { String field ->
                    File file = (File) ExtractReplaySupport.resolveStamped(normalize(row.get(field)), dataManagerLocation, root)?.file
                    if (file != null) paths.add(file.canonicalPath)
                }
            }
        }
        return paths
    }

    /** The folders inside an extract folder that are purged like extracts (DAR-BE-318): interactive extracts and copy slices (file1-api, file2-api) and lookup captures (lookups). */
    static final java.util.regex.Pattern API_EXTRACT_FOLDER = ~/^(file[12]-api|lookups)$/

    /** ExtractReplaySupport's extract folders: a run timestamp, then optionally -FILE_1/-FILE_2, then optionally -recheck. */
    static final java.util.regex.Pattern EXTRACT_FOLDER = ~/^\d{8}-\d{9}(-FILE_[12])?(-recheck)?$/

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

    private static boolean isActiveRunResultStatus(Object rawStatusEnumId) {
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
    // Compatibility delegates: these moved to OutputPathSupport / OutputDescriptorSupport in the MACH P1 decomposition and
    // are kept only for callers in other slices (runSavedRunDiff.groovy, NavigationSearchSupport.groovy,
    // ReconciliationFacadeServices.xml, KeptCopySupportTests). DAR-OPS-029 S21a: the ones only this class's tests called are
    // gone; S99 points the rest at their real homes and deletes these.
    // ==================================================================================================

    static boolean isSafeOutputPath(Object rawFileName) { return OutputPathSupport.isSafeOutputPath(rawFileName) }

    static String sourceFormatForFile(String fileName) { return OutputPathSupport.sourceFormatForFile(fileName) }

    static File resolveGeneratedOutputFile(def ec, Object rawFileName) { return OutputPathSupport.resolveGeneratedOutputFile(ec, rawFileName) }

    static String sanitizeUploadFileName(String rawName, String fallbackBase = "file") { return OutputPathSupport.sanitizeUploadFileName(rawName, fallbackBase) }

    static Map<String, Object> parseGeneratedOutputText(String rawText) { return OutputDescriptorSupport.parseGeneratedOutputText(rawText) }

    static Map<String, Object> buildGeneratedOutputDescriptor(String fileName, Map<String, Object> diffDocument,
            long sizeBytes, Timestamp createdDate) { return OutputDescriptorSupport.buildGeneratedOutputDescriptor(fileName, diffDocument, sizeBytes, createdDate) }
}
