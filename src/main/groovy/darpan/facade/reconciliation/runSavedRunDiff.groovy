import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime

def toTimestampValue = { Object rawValue ->
    if (rawValue == null) return null
    if (rawValue instanceof Timestamp) return rawValue
    if (rawValue instanceof Date) return new Timestamp(rawValue.time)

    String normalized = FacadeSupport.normalize(rawValue)
    if (!normalized) return null
    try {
        return Timestamp.from(Instant.parse(normalized))
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(normalized)
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(normalized.replace("T", " "))
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(LocalDateTime.parse(normalized))
    } catch (Exception ignored) {
    }
    throw new IllegalArgumentException("Invalid timestamp '${normalized}'")
}

String savedRunIdValue = FacadeSupport.normalize(savedRunId)
String inputFile1Name = file1Name != null ? ReconciliationOutputSupport.sanitizeUploadFileName(file1Name as String, "file1") : null
String inputFile2Name = file2Name != null ? ReconciliationOutputSupport.sanitizeUploadFileName(file2Name as String, "file2") : null
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String requestedFile1SystemEnumId = FacadeSupport.normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = FacadeSupport.normalize(file2SystemEnumId)
boolean hasHeaderValue = FacadeSupport.normalizeBool(hasHeader, true)
String windowStartLocalDateValue = FacadeSupport.normalize(windowStartLocalDate)
String windowEndLocalDateValue = FacadeSupport.normalize(windowEndLocalDate)
Timestamp requestedWindowStartDateValue = null
Timestamp requestedWindowEndDateValue = null
Timestamp windowStartDateValue = null
Timestamp windowEndDateValue = null

try {
    requestedWindowStartDateValue = toTimestampValue(windowStartDate)
    requestedWindowEndDateValue = toTimestampValue(windowEndDate)
    windowStartDateValue = requestedWindowStartDateValue
    windowEndDateValue = requestedWindowEndDateValue
    if (windowStartDateValue && windowEndDateValue) {
        Map<String, Object> normalizedApiWindow = ReconciliationApiWindowSupport.normalizeSavedRunApiWindow(
                ec,
                windowStartDateValue,
                windowEndDateValue,
                windowStartLocalDateValue,
                windowEndLocalDateValue
        )
        windowStartDateValue = (Timestamp) normalizedApiWindow.windowStartDate
        windowEndDateValue = (Timestamp) normalizedApiWindow.windowEndDate
    }
} catch (IllegalArgumentException e) {
    ec.message.addError(e.message)
}

if (!savedRunIdValue) ec.message.addError("savedRunId is required")
if ((requestedFile1SystemEnumId && !requestedFile2SystemEnumId) || (!requestedFile1SystemEnumId && requestedFile2SystemEnumId)) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be provided together when overriding saved run defaults.")
}

if (!ec.message.hasError()) {
    TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for reconciliation runs.")
}

def findEnum = { String enumId ->
    if (!enumId) return null
    return ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", enumId)
            .useCache(true)
            .one()
}
def enumLabel = { String enumId ->
    return FacadeSupport.enumLabel(findEnum(enumId) ?: [enumId: enumId])
}
def requireUploadInput = { String filePrefix, String inputName, String inputText ->
    if (!inputName) ec.message.addError("${filePrefix}Name is required")
    if (!inputText) ec.message.addError("${filePrefix}Text is required")
}
def sideToken = { String fileSide ->
    fileSide == ReconciliationSavedRunSupport.FILE_SIDE_1 ? "file1" : "file2"
}
def isApiSource = { Object source ->
    FacadeSupport.normalize(source?.sourceTypeEnumId) == ReconciliationSavedRunSupport.SOURCE_TYPE_API
}
def sourceLabel = { Object source, String fallback ->
    enumLabel(FacadeSupport.normalize(source?.systemEnumId) ?: fallback)
}
def runInternalService = { String serviceName, Map params ->
    def call = ec.service.sync()
            .name(serviceName)
            .parameters((params ?: [:]).findAll { entry -> entry.value != null })
    if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
    return (call.call() ?: [:]) as Map
}
def resolveOutputFile = { Map serviceResult ->
    File outputFile = null
    String diffLocationValue = FacadeSupport.normalize(serviceResult?.diffLocation)
    if (diffLocationValue) {
        outputFile = diffLocationValue.startsWith("/") ?
                new File(diffLocationValue) :
                ec.resource.getLocationReference(diffLocationValue)?.getFile()
    }
    if ((outputFile == null || !outputFile.exists()) && serviceResult?.diffFileName) {
        String diffFileNameValue = FacadeSupport.normalize(serviceResult.diffFileName)
        if (diffFileNameValue?.contains("/")) {
            outputFile = DataManagerSupport.resolveDataManagerFile(ec, diffFileNameValue, false)
        }
        if (outputFile == null || !outputFile.exists()) {
            String scopedOutputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
            File outputDir = ec.resource.getLocationReference(scopedOutputLocation)?.getFile()
            if (outputDir != null) outputFile = new File(outputDir, diffFileNameValue)
        }
    }
    return outputFile
}
def buildGeneratedOutputDescriptor = { Map serviceResult ->
    File outputFile = resolveOutputFile(serviceResult)
    long sizeBytes = outputFile?.exists() ? outputFile.length() : 0L
    Timestamp createdDate = outputFile?.exists() ?
            new Timestamp(outputFile.lastModified()) :
            (Timestamp) ec.user.nowTimestamp
    Map<String, Object> outputDocument = [:]
    if (outputFile?.exists() && ReconciliationOutputSupport.sourceFormatForFile(outputFile.name) == "json") {
        try {
            outputDocument = ReconciliationOutputSupport.parseGeneratedOutputText(outputFile.getText("UTF-8"))
        } catch (Exception ignored) {
            outputDocument = [:]
        }
    }
    Map descriptor = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
            serviceResult?.diffFileName as String,
            outputDocument,
            sizeBytes,
            createdDate
    )
    if (serviceResult?.reconciliationRunResultId) {
        descriptor.reconciliationRunResultId = serviceResult.reconciliationRunResultId
    }
    return descriptor
}
def persistRunResult = { Map<String, Object> fields ->
    String resultPath = DataManagerSupport.normalizeRelativePath(fields.resultDataManagerPath)
    if (!resultPath) return null

    return ec.transaction.runUseOrBegin(30, "Error saving reconciliation run result", {
        def runResultValue = ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
        runResultValue.savedRunId = FacadeSupport.normalize(fields.savedRunId)
        runResultValue.savedRunType = FacadeSupport.normalize(fields.savedRunType)
        runResultValue.reconciliationRunId = FacadeSupport.normalize(fields.reconciliationRunId)
        runResultValue.reconciliationMappingId = FacadeSupport.normalize(fields.reconciliationMappingId)
        runResultValue.ruleSetId = FacadeSupport.normalize(fields.ruleSetId)
        runResultValue.compareScopeId = FacadeSupport.normalize(fields.compareScopeId)
        runResultValue.companyUserGroupId = FacadeSupport.normalize(fields.companyUserGroupId) ?: TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        runResultValue.createdByUserId = TenantAccessSupport.currentUserId(ec)
        runResultValue.file1Name = FacadeSupport.normalize(fields.file1Name)
        runResultValue.file1DataManagerPath = DataManagerSupport.normalizeRelativePath(fields.file1DataManagerPath)
        runResultValue.file2Name = FacadeSupport.normalize(fields.file2Name)
        runResultValue.file2DataManagerPath = DataManagerSupport.normalizeRelativePath(fields.file2DataManagerPath)
        runResultValue.resultDataManagerPath = resultPath
        runResultValue.reconciliationType = FacadeSupport.normalize(fields.reconciliationType)
        runResultValue.differenceCount = fields.differenceCount
        runResultValue.onlyInFile1Count = fields.onlyInFile1Count
        runResultValue.onlyInFile2Count = fields.onlyInFile2Count
        runResultValue.createdDate = ec.user.nowTimestamp
        runResultValue.setSequencedIdPrimary()
        runResultValue.create()
        return runResultValue.reconciliationRunResultId
    })
}
def buildRunArtifactContext = { String runId ->
    String runToken = DataManagerSupport.safeToken(runId, "run")
    String timestamp = DataManagerSupport.formatRunTimestamp(ec)
    String runArtifactLocation = DataManagerSupport.resolveReconciliationRunLocation(ec, runToken, timestamp)
    File runArtifactDir = DataManagerSupport.resolveDirectoryFile(ec, runArtifactLocation, true)
    if (runArtifactDir == null) {
        throw new IllegalStateException("Unable to resolve reconciliation run data-manager directory: ${runArtifactLocation}")
    }
    return [runToken: runToken, location: runArtifactLocation]
}
def sourceResultForLocation = { Object source, String fileSide, String fileNameValue, String location, Integer recordCount = null ->
    def locationRef = ec.resource.getLocationReference(location)
    File locationFile = locationRef?.getFile()
    return [
            fileLocation   : locationFile?.getAbsolutePath() ?: location,
            dataManagerPath: DataManagerSupport.relativeDataManagerPath(ec, locationFile),
            fileName       : fileNameValue,
            fileTypeEnumId : FacadeSupport.normalize(source?.fileTypeEnumId) ?: "DftJson",
            schemaFileName : FacadeSupport.normalize(source?.schemaFileName),
            recordCount    : recordCount,
            fileSide       : fileSide,
    ].findAll { entry -> entry.value != null } as Map<String, Object>
}
def stageTextInput = { Object source, String fileSide, String inputName, String inputText, Map artifactContext ->
    String token = sideToken(fileSide)
    String safeName = ReconciliationOutputSupport.sanitizeUploadFileName(inputName, token)
    String location = DataManagerSupport.childLocation(
            artifactContext.location as String,
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, safeName)
    )
    DataManagerSupport.writeText(ec, location, inputText)
    return sourceResultForLocation(source, fileSide, safeName, location, null)
}
def formatApiWindow = { Timestamp timestamp ->
    timestamp?.toInstant()?.toString()
}
def resolveShopifyTimeZone = { String configId, String label ->
    def config = ec.entity.find("darpan.shopify.ShopifyAuthConfig")
            .condition("shopifyAuthConfigId", configId)
            .useCache(false)
            .one()
    if (config) {
        TenantAccessSupport.requireTenantRecordAccess(
                ec,
                config,
                "${label} Shopify auth config '${configId}' was not found.",
                "${label} Shopify auth config '${configId}' is not available in your active tenant."
        )
    }
    return FacadeSupport.normalize(config?.timeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
}
def resolveSourceApiWindow = { Object rawTimeZone ->
    ReconciliationApiWindowSupport.normalizeCalendarWindow(
            requestedWindowStartDateValue,
            requestedWindowEndDateValue,
            rawTimeZone,
            windowStartLocalDateValue,
            windowEndLocalDateValue
    )
}
def extractHotWaxSource = { Object source, String fileSide, String label, Map artifactContext ->
    String configId = FacadeSupport.normalize(source?.sourceConfigId)
    if (!configId) {
        ec.message.addError("${label} API source requires a HotWax OMS source config.")
        return [:]
    }
    Map extraction = runInternalService("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders", [
            omsRestSourceConfigId: configId,
            windowStart          : formatApiWindow(windowStartDateValue),
            windowEnd            : formatApiWindow(windowEndDateValue),
            outputLocation       : DataManagerSupport.childLocation(artifactContext.location as String, "${sideToken(fileSide)}-api"),
    ])
    ((List) (extraction.errors ?: [])).each { Object error -> ec.message.addError("${label}: ${error}") }
    if (ec.message.hasError()) return [:]

    String extractedLocation = FacadeSupport.normalize(extraction.fileLocation)
    if (!extractedLocation) {
        ec.message.addError("${label} API did not return an output file for the selected time period.")
        return [:]
    }
    return sourceResultForLocation(source, fileSide, FacadeSupport.normalize(extraction.fileName) ?: "${sideToken(fileSide)}-api.json",
            extractedLocation, FacadeSupport.normalizeInt(extraction.recordCount, null))
}
def normalizeShopifyOrderRecord = { Map<String, Object> record ->
    Map<String, Object> normalizedRecord = new LinkedHashMap(record ?: [:])
    String gid = FacadeSupport.normalize(normalizedRecord.id)
    String legacyId = FacadeSupport.normalize(normalizedRecord.legacyResourceId)
    if (!legacyId && gid) {
        def matcher = gid =~ /(\d+)$/
        if (matcher.find()) legacyId = matcher.group(1)
    }
    if (gid) normalizedRecord.shopifyGid = gid
    if (legacyId) {
        normalizedRecord.legacyResourceId = legacyId
        normalizedRecord.id = legacyId
    }
    return normalizedRecord
}
def shopifyCompareId = { Map<String, Object> record ->
    FacadeSupport.normalize(record?.id) ?: FacadeSupport.normalize(record?.legacyResourceId) ?: FacadeSupport.normalize(record?.shopifyGid)
}
def shopifyOrderSelection = {
    return """id
      legacyResourceId
      name
      createdAt
      updatedAt
      processedAt
      email
      cancelledAt
      totalPrice
      displayFinancialStatus
      displayFulfillmentStatus
      currencyCode
      currentTotalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      currentTotalTaxSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      totalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }
      subtotalPriceSet {
        shopMoney {
          amount
          currencyCode
        }
      }"""
}
def buildShopifyOrderDateWindowQuery = {
    return """query DarpanShopifyOrdersByDateWindow(\$search: String, \$after: String) {
  orders(first: 100, after: \$after, query: \$search) {
    edges {
      cursor
      node {
        ${shopifyOrderSelection()}
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}"""
}
def scanShopifyOrdersByDateWindow = { String configId, String label, Collection<String> allowedIds, Timestamp sourceWindowStartDate, Timestamp sourceWindowEndDate ->
    Map<String, Map<String, Object>> recordsById = [:]
    List<String> searchQueries = []
    Set<String> allowedIdSet = allowedIds ?
            (allowedIds.collect { Object id -> FacadeSupport.normalize(id) }.findAll { String id -> id } as LinkedHashSet<String>) :
            null
    List<Map<String, String>> dateFilterSets = [
            [field: "created_at"],
    ]
    int maxPages = 20
    String windowStartText = formatApiWindow(sourceWindowStartDate)
    String windowEndText = formatApiWindow(sourceWindowEndDate)
    String queryDocument = buildShopifyOrderDateWindowQuery()

    for (Map<String, String> dateFilterSet : dateFilterSets) {
        String afterCursor = null
        int pageCount = 0
        boolean hasMorePages = false
        while (pageCount < maxPages) {
            pageCount++
            String searchQuery = "${dateFilterSet.field}:>=${windowStartText} ${dateFilterSet.field}:<${windowEndText}"
            if (searchQuery) searchQueries.add(searchQuery)
            Map executeResult = runInternalService("facade.ShopifyFacadeServices.execute#ShopifyGraphql", [
                    shopifyAuthConfigId: configId,
                    queryDocument      : queryDocument,
                    variables          : [search: searchQuery, after: afterCursor],
            ])
            if (ec.message.hasError()) return [recordsById: recordsById, searchQueries: searchQueries, dateFilterSets: dateFilterSets, queriedStatuses: []]

            Map graphqlResult = executeResult.graphqlResult instanceof Map ? (Map) executeResult.graphqlResult : [:]
            if (graphqlResult.ok == false) {
                ((List) (graphqlResult.errors ?: ["${label} GraphQL request failed."])).each { Object error ->
                    ec.message.addError("${label}: ${error}")
                }
                return [recordsById: recordsById, searchQueries: searchQueries, dateFilterSets: dateFilterSets, queriedStatuses: []]
            }

            Map data = graphqlResult.data instanceof Map ? (Map) graphqlResult.data : [:]
            Map orders = data.orders instanceof Map ? (Map) data.orders : [:]
            List edges = orders.edges instanceof Collection ? (List) orders.edges : []
            edges.each { Object edge ->
                Object node = edge instanceof Map ? ((Map) edge).node : null
                if (node instanceof Map) {
                    Map<String, Object> record = normalizeShopifyOrderRecord((Map<String, Object>) node)
                    String recordId = shopifyCompareId(record)
                    if (recordId && (allowedIdSet == null || allowedIdSet.contains(recordId))) recordsById[recordId] = record
                }
            }

            Map pageInfo = orders.pageInfo instanceof Map ? (Map) orders.pageInfo : [:]
            hasMorePages = pageInfo.hasNextPage == true
            if (!hasMorePages) break
            afterCursor = FacadeSupport.normalize(pageInfo.endCursor)
            if (!afterCursor) break
        }
        if (hasMorePages && pageCount >= maxPages) {
            ec.message.addError("${label} API returned more than ${maxPages} pages for ${dateFilterSet.field} in the selected time period. Choose a smaller time period.")
            return [recordsById: recordsById, searchQueries: searchQueries, dateFilterSets: dateFilterSets, queriedStatuses: []]
        }
    }

    return [recordsById: recordsById, searchQueries: searchQueries, dateFilterSets: dateFilterSets, queriedStatuses: []]
}
def extractShopifySource = { Object source, String fileSide, String label, Map artifactContext ->
    String configId = FacadeSupport.normalize(source?.sourceConfigId)
    if (!configId) {
        ec.message.addError("${label} API source requires a Shopify auth config.")
        return [:]
    }

    String sourceTimeZone = resolveShopifyTimeZone(configId, label)
    if (ec.message.hasError()) return [:]
    Map<String, Object> sourceApiWindow = resolveSourceApiWindow(sourceTimeZone)
    Timestamp sourceWindowStartDate = (Timestamp) sourceApiWindow.windowStartDate
    Timestamp sourceWindowEndDate = (Timestamp) sourceApiWindow.windowEndDate
    String windowStartText = formatApiWindow(sourceWindowStartDate)
    String windowEndText = formatApiWindow(sourceWindowEndDate)

    Map dateWindowScan = scanShopifyOrdersByDateWindow(configId, label, null, sourceWindowStartDate, sourceWindowEndDate)
    if (ec.message.hasError()) return [:]
    Map<String, Map<String, Object>> recordsById = dateWindowScan.recordsById instanceof Map ?
            (Map<String, Map<String, Object>>) dateWindowScan.recordsById :
            [:]
    List<Map<String, Object>> records = new ArrayList(recordsById.values())

    String token = sideToken(fileSide)
    String fileNameValue = ReconciliationOutputSupport.sanitizeUploadFileName("${label}-orders-api.json", "${token}-api.json")
    String location = DataManagerSupport.childLocation(
            DataManagerSupport.childLocation(artifactContext.location as String, "${token}-api"),
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, fileNameValue)
    )
    DataManagerSupport.writeText(ec, location, JsonOutput.toJson([
            metadata: [
                    sourceType                         : "SHOPIFY_GRAPHQL_ORDERS",
                    extractionMode                     : "DATE_FILTER",
                    shopifyAuthConfigId                : configId,
                    sourceTimeZone                     : sourceApiWindow.timeZone,
                    calendarDateNormalized             : sourceApiWindow.calendarDateNormalized,
                    filterFields                       : ((List) (dateWindowScan.dateFilterSets ?: [])).collect { Map dateFilterSet -> dateFilterSet.field },
                    searchQueries                      : ((List) (dateWindowScan.searchQueries ?: [])).unique(),
                    windowStartUtc                     : windowStartText,
                    windowEndUtc                       : windowEndText,
                    extractedRecordCount               : records.size(),
            ],
            records : records,
    ]))
    return sourceResultForLocation(source, fileSide, fileNameValue, location, records.size())
}
def extractApiSource = { Object source, String fileSide, Map artifactContext ->
    String label = sourceLabel(source, fileSide)
    String sourceConfigType = FacadeSupport.normalize(source?.sourceConfigType)
    switch (sourceConfigType) {
        case ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST:
            return extractHotWaxSource(source, fileSide, label, artifactContext)
        case ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH:
            return extractShopifySource(source, fileSide, label, artifactContext)
        default:
            ec.message.addError("${label} API source type '${sourceConfigType ?: "unknown"}' is not supported for manual saved-run execution.")
            return [:]
    }
}
def writeRuleSetOutput = { Map serviceResult, Map savedRun, String file1Label, String file2Label, Map artifactContext ->
    Map output = ReconciliationServices.writeDiffDatasetOutput(
            ec,
            serviceResult.diffDf,
            artifactContext.location as String,
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, "result", "result.json"),
            "${artifactContext.runToken}-diff.json",
            [
                    timestamp              : ec.user.nowTimestamp?.toString(),
                    file1Label             : file1Label,
                    file2Label             : file2Label,
                    file1Type              : serviceResult.file1Type,
                    file2Type              : serviceResult.file2Type,
                    reconciliation         : "RULESET",
                    companyUserGroupId     : savedRun.companyUserGroupId,
                    savedRunId             : savedRun.savedRunId,
                    savedRunName           : savedRun.runName,
                    savedRunType           : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                    ruleSetId              : savedRun.ruleSetId,
                    compareScopeId         : savedRun.compareScopeId,
                    compareScopeDescription: serviceResult.compareScopeDescription ?: savedRun.compareScopeDescription,
            ],
            [
                    totalDifferences             : serviceResult.differenceCount,
                    onlyInFile1Count             : serviceResult.missingInFile2Count,
                    onlyInFile2Count             : serviceResult.missingInFile1Count,
                    missingObjectDifferenceCount : serviceResult.missingObjectDifferenceCount,
                    ruleDifferenceCount          : serviceResult.ruleDifferenceCount,
            ],
            (List) (serviceResult.validationErrors ?: []),
            (List) (serviceResult.processingWarnings ?: [])
    )
    serviceResult.diffLocation = output.diffLocation
    serviceResult.diffFileName = output.diffFileName
    return output
}

def mapping = null
if (!ec.message.hasError()) {
    mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", savedRunIdValue)
            .useCache(false)
            .one()
}

if (!ec.message.hasError() && mapping != null) {
    TenantAccessSupport.requireTenantRecordAccess(ec, mapping, "Saved run '${savedRunIdValue}' was not found.",
            "Saved run '${savedRunIdValue}' is not available in your active tenant.")
}

if (!ec.message.hasError() && mapping != null) {
    requireUploadInput("file1", inputFile1Name, file1TextValue)
    requireUploadInput("file2", inputFile2Name, file2TextValue)
}

if (!ec.message.hasError() && mapping != null) {
    Map legacyResult = ec.service.sync()
            .name("facade.ReconciliationFacadeServices.run#GenericDiff")
            .parameters([
                    reconciliationMappingId: savedRunIdValue,
                    file1Name              : inputFile1Name,
                    file1Text              : file1TextValue,
                    file2Name              : inputFile2Name,
                    file2Text              : file2TextValue,
                    file1SystemEnumId      : requestedFile1SystemEnumId,
                    file2SystemEnumId      : requestedFile2SystemEnumId,
                    hasHeader              : hasHeaderValue,
                    sparkMaster            : sparkMaster,
                    sparkAppName           : sparkAppName ?: "SavedRunDiff"
            ])
            .call()

    Map legacyRunResult = (legacyResult?.runResult ?: [:]) as Map
    Map generatedOutput = legacyRunResult.generatedOutput instanceof Map ? (Map) legacyRunResult.generatedOutput : [:]
    runResult = [
            savedRunId             : mapping.reconciliationMappingId,
            runName                : mapping.mappingName,
            runType                : ReconciliationSavedRunSupport.RUN_TYPE_MAPPING,
            reconciliationMappingId: mapping.reconciliationMappingId,
            reconciliationRunResultId: legacyRunResult.reconciliationRunResultId,
            ruleSetId              : null,
            compareScopeId         : null,
            compareScopeDescription: null,
            file1Name              : legacyRunResult.file1Name,
            file2Name              : legacyRunResult.file2Name,
            file1SystemEnumId      : legacyRunResult.file1SystemEnumId,
            file1SystemLabel       : legacyRunResult.file1SystemLabel,
            file2SystemEnumId      : legacyRunResult.file2SystemEnumId,
            file2SystemLabel       : legacyRunResult.file2SystemLabel,
            validationErrors       : (legacyRunResult.validationErrors ?: []) as List,
            processingWarnings     : (legacyRunResult.processingWarnings ?: []) as List,
            generatedOutput        : generatedOutput,
    ]
}

if (!ec.message.hasError() && mapping == null) {
    Map<String, Object> resolvedRuleSetRun = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunIdValue)
    if (resolvedRuleSetRun.error) {
        ec.message.addError(resolvedRuleSetRun.error as String)
    } else if (resolvedRuleSetRun.savedRun == null) {
        ec.message.addError("Saved run '${savedRunIdValue}' was not found.")
    } else {
        Map<String, Object> savedRun = (Map<String, Object>) resolvedRuleSetRun.savedRun
        Map<String, Object> sourceBySide = (Map<String, Object>) resolvedRuleSetRun.sourceBySide
        def file1Source = sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_1]
        def file2Source = sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_2]
        boolean file1UsesApiSource = isApiSource(file1Source)
        boolean file2UsesApiSource = isApiSource(file2Source)
        boolean hasApiInput = file1UsesApiSource || file2UsesApiSource

        String defaultFile1SystemEnumId = FacadeSupport.normalize(file1Source?.systemEnumId)
        String defaultFile2SystemEnumId = FacadeSupport.normalize(file2Source?.systemEnumId)
        String resolvedFile1SystemEnumId = requestedFile1SystemEnumId ?: defaultFile1SystemEnumId
        String resolvedFile2SystemEnumId = requestedFile2SystemEnumId ?: defaultFile2SystemEnumId
        if (resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
            ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
        }

        if (!ec.message.hasError() && hasApiInput) {
            if (!windowStartDateValue || !windowEndDateValue) {
                ec.message.addError("windowStartDate and windowEndDate are required when a saved-run source is API-backed.")
            } else if (!windowStartDateValue.before(windowEndDateValue)) {
                ec.message.addError("windowStartDate must be before windowEndDate.")
            }
            if (!file1UsesApiSource) requireUploadInput("file1", inputFile1Name, file1TextValue)
            if (!file2UsesApiSource) requireUploadInput("file2", inputFile2Name, file2TextValue)
        } else if (!ec.message.hasError()) {
            requireUploadInput("file1", inputFile1Name, file1TextValue)
            requireUploadInput("file2", inputFile2Name, file2TextValue)
        }

        if (!ec.message.hasError()) {
            String file1Label = enumLabel(resolvedFile1SystemEnumId)
            String file2Label = enumLabel(resolvedFile2SystemEnumId)

            if (hasApiInput) {
                Map artifactContext = buildRunArtifactContext(savedRun.savedRunId as String)
                Map file1Result = file1UsesApiSource ?
                        extractApiSource(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, artifactContext) :
                        stageTextInput(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, inputFile1Name, file1TextValue, artifactContext)
                Map file2Result = !ec.message.hasError() && file2UsesApiSource ?
                        extractApiSource(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, artifactContext) :
                        !ec.message.hasError() ? stageTextInput(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, inputFile2Name, file2TextValue, artifactContext) : [:]

                if (!ec.message.hasError()) {
                    Map serviceResult = runInternalService("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                            ruleSetId          : savedRun.ruleSetId,
                            compareScopeId     : savedRun.compareScopeId,
                            file1Location      : file1Result.fileLocation,
                            file2Location      : file2Result.fileLocation,
                            file1Name          : file1Result.fileName,
                            file2Name          : file2Result.fileName,
                            file1FileTypeEnumId: file1Result.fileTypeEnumId,
                            file2FileTypeEnumId: file2Result.fileTypeEnumId,
                            file1SchemaFileName: file1Result.schemaFileName,
                            file2SchemaFileName: file2Result.schemaFileName,
                            file1Label         : file1Label,
                            file2Label         : file2Label,
                            hasHeader          : hasHeaderValue,
                            sparkMaster        : sparkMaster,
                            sparkAppName       : sparkAppName ?: "SavedRunDiff"
                    ])
                    if (!ec.message.hasError()) {
                        writeRuleSetOutput(serviceResult, savedRun, file1Label, file2Label, artifactContext)
                        String resultDataManagerPath = serviceResult.diffLocation ?
                                (DataManagerSupport.relativeDataManagerPath(ec, new File(serviceResult.diffLocation as String)) ?: serviceResult.diffFileName) :
                                serviceResult.diffFileName
                        String reconciliationRunResultId = persistRunResult([
                                savedRunId           : savedRun.savedRunId,
                                savedRunType         : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                                ruleSetId            : savedRun.ruleSetId,
                                compareScopeId       : savedRun.compareScopeId,
                                companyUserGroupId   : savedRun.companyUserGroupId,
                                file1Name            : file1Result.fileName,
                                file1DataManagerPath : file1Result.dataManagerPath,
                                file2Name            : file2Result.fileName,
                                file2DataManagerPath : file2Result.dataManagerPath,
                                resultDataManagerPath: resultDataManagerPath,
                                reconciliationType   : serviceResult.reconciliationType ?: serviceResult.objectType,
                                differenceCount      : serviceResult.differenceCount,
                                onlyInFile1Count     : serviceResult.missingInFile2Count,
                                onlyInFile2Count     : serviceResult.missingInFile1Count,
                        ])
                        serviceResult.reconciliationRunResultId = reconciliationRunResultId
                        serviceResult.diffFileName = resultDataManagerPath
                        runResult = [
                                savedRunId             : savedRun.savedRunId,
                                runName                : savedRun.runName,
                                runType                : savedRun.runType,
                                reconciliationMappingId: null,
                                reconciliationRunResultId: reconciliationRunResultId,
                                ruleSetId              : savedRun.ruleSetId,
                                compareScopeId         : savedRun.compareScopeId,
                                compareScopeDescription: savedRun.compareScopeDescription,
                                file1Name              : file1Result.fileName,
                                file2Name              : file2Result.fileName,
                                file1SystemEnumId      : resolvedFile1SystemEnumId,
                                file1SystemLabel       : file1Label,
                                file2SystemEnumId      : resolvedFile2SystemEnumId,
                                file2SystemLabel       : file2Label,
                                validationErrors       : (serviceResult.validationErrors ?: []) as List,
                                processingWarnings     : (serviceResult.processingWarnings ?: []) as List,
                                generatedOutput        : buildGeneratedOutputDescriptor(serviceResult),
                        ]
                    }
                }
            } else {
                Map serviceResult = ec.service.sync()
                        .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                        .parameters([
                                ruleSetId        : savedRun.ruleSetId,
                                compareScopeId   : savedRun.compareScopeId,
                                file1Name        : inputFile1Name,
                                file1Text        : file1TextValue,
                                file2Name        : inputFile2Name,
                                file2Text        : file2TextValue,
                                file1SystemEnumId: resolvedFile1SystemEnumId,
                                file2SystemEnumId: resolvedFile2SystemEnumId,
                                hasHeader        : hasHeaderValue,
                                sparkMaster      : sparkMaster,
                                sparkAppName     : sparkAppName ?: "SavedRunDiff"
                        ])
                        .call()

                if (!ec.message.hasError()) {
                    runResult = [
                            savedRunId             : savedRun.savedRunId,
                            runName                : savedRun.runName,
                            runType                : savedRun.runType,
                            reconciliationMappingId: null,
                            reconciliationRunResultId: serviceResult.reconciliationRunResultId,
                            ruleSetId              : savedRun.ruleSetId,
                            compareScopeId         : savedRun.compareScopeId,
                            compareScopeDescription: savedRun.compareScopeDescription,
                            file1Name              : inputFile1Name,
                            file2Name              : inputFile2Name,
                            file1SystemEnumId      : resolvedFile1SystemEnumId,
                            file1SystemLabel       : file1Label,
                            file2SystemEnumId      : resolvedFile2SystemEnumId,
                            file2SystemLabel       : file2Label,
                            validationErrors       : (serviceResult.validationErrors ?: []) as List,
                            processingWarnings     : (serviceResult.processingWarnings ?: []) as List,
                            generatedOutput        : buildGeneratedOutputDescriptor(serviceResult),
                    ]
                }
            }
        }
    }
}

if (!ec.message.hasError() && runResult?.reconciliationRunResultId) {
    TenantNotificationSupport.notifyRunCompleted(ec, (Map<String, Object>) runResult)
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
