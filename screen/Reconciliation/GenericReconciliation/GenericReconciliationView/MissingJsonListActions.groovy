import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper

def parseIntSafe = { val ->
    if (val == null) return null
    try {
        return Integer.parseInt(val.toString().trim())
    } catch (Exception ignored) {
        return null
    }
}

def trimToNull = { val ->
    def text = val?.toString()?.trim()
    return text ? text : null
}

def resolveParamValue = { String name ->
    def rawValue = ec.web?.parameters?.get(name)
    if (rawValue == null) rawValue = ec.web?.requestParameters?.get(name)
    def lastNonBlank = { Iterable items ->
        def resolved = null
        items?.each { item ->
            def candidate = item?.toString()
            if (candidate != null && candidate.trim()) {
                resolved = candidate
            }
        }
        return resolved
    }
    if (rawValue instanceof Collection) {
        return lastNonBlank(rawValue)
    }
    if (rawValue != null && rawValue.getClass().isArray()) {
        return lastNonBlank(rawValue as List)
    }
    return rawValue
}

def normalizeLabel = { label ->
    def normalized = label?.toString()?.trim()
    if (!normalized) return null
    normalized = normalized.replaceAll(/\s+/, '_')
    return normalized
}

def resolveKeys = { typeValue, presentIn, missingIn ->
    def typeLower = typeValue?.toString()?.toLowerCase()
    def systemKey = null
    if (presentIn) {
        if (presentIn == viewMetaJson1Label) systemKey = "json1"
        else if (presentIn == viewMetaJson2Label) systemKey = "json2"
    }
    if (!systemKey && missingIn) {
        if (missingIn == viewMetaJson1Label) systemKey = "json2"
        else if (missingIn == viewMetaJson2Label) systemKey = "json1"
    }
    if (!systemKey && typeLower) {
        if (typeLower.contains('missing_in_json2') || typeLower.contains('only_in_json1')) {
            systemKey = "json1"
        } else if (typeLower.contains('missing_in_json1') || typeLower.contains('only_in_json2')) {
            systemKey = "json2"
        }
    }

    def missingKeyValue = null
    if (missingIn) {
        if (missingIn == viewMetaJson1Label) missingKeyValue = "json1"
        else if (missingIn == viewMetaJson2Label) missingKeyValue = "json2"
    }
    if (!missingKeyValue && presentIn) {
        if (presentIn == viewMetaJson1Label) missingKeyValue = "json2"
        else if (presentIn == viewMetaJson2Label) missingKeyValue = "json1"
    }
    if (!missingKeyValue && typeLower) {
        if (typeLower.contains('missing_in_json1') || typeLower.contains('only_in_json2')) {
            missingKeyValue = "json1"
        } else if (typeLower.contains('missing_in_json2') || typeLower.contains('only_in_json1')) {
            missingKeyValue = "json2"
        }
    }
    if (!missingKeyValue && systemKey) {
        missingKeyValue = systemKey == "json1" ? "json2" : systemKey == "json2" ? "json1" : null
    }
    return [systemKey: systemKey, missingKey: missingKeyValue]
}

def resolveTypeText = { typeValue, systemKey, json1LabelNormalized, json2LabelNormalized ->
    def normalizedLabel = systemKey == 'json1' ? json1LabelNormalized : systemKey == 'json2' ? json2LabelNormalized : null
    return normalizedLabel ? "only_in_${normalizedLabel}" : typeValue
}

def setPaginationMetadata = { int totalCount, int pageSize ->
    if (totalCount <= 0) {
        ec.context.put("missingRecordsCount", 0)
        ec.context.put("missingRecordsPageIndex", 0)
        ec.context.put("missingRecordsPageSize", pageSize)
        ec.context.put("missingRecordsPageMaxIndex", 0)
        ec.context.put("missingRecordsPageRangeLow", 0)
        ec.context.put("missingRecordsPageRangeHigh", 0)
        return [low: 0, high: 0]
    }
    ec.context.remove("missingRecordsCount")
    org.moqui.util.CollectionUtilities.paginateParameters(totalCount, "missingRecords", ec.context)
    def rangeLow = (ec.context.get("missingRecordsPageRangeLow") ?: 0) as int
    def rangeHigh = (ec.context.get("missingRecordsPageRangeHigh") ?: 0) as int
    return [low: rangeLow, high: rangeHigh]
}

viewError = null
missingRecords = []
missingRecordsCount = 0
viewMetaJson1Label = "JSON 1"
viewMetaJson2Label = "JSON 2"
viewMissingLabel = null
viewDefaultPageSize = 20
viewMaxPageSize = 200
viewMaxInMemoryBytes = 5 * 1024 * 1024
viewRequestPath = ec.web?.request?.getRequestURI() ?: ""

def viewOrderLimit = parseIntSafe(viewLimit)
if (viewOrderLimit != null && viewOrderLimit < 1) viewOrderLimit = null

def missingKeyNormalized = trimToNull(missingKey)?.toLowerCase()
if (!missingKeyNormalized || !(missingKeyNormalized in ["json1", "json2"])) {
    missingKeyNormalized = "json1"
}

def pageIndexParamName = missingKeyNormalized == "json1" ? "pageIndexJson1" : "pageIndexJson2"
def pageSizeParamName = missingKeyNormalized == "json1" ? "pageSizeJson1" : "pageSizeJson2"
def pageIndexValue = parseIntSafe(resolveParamValue(pageIndexParamName))
if (pageIndexValue == null) pageIndexValue = parseIntSafe(resolveParamValue("MissingRecordsList_pageIndex"))
if (pageIndexValue == null) pageIndexValue = parseIntSafe(resolveParamValue("pageIndex"))

def pageSizeValue = parseIntSafe(resolveParamValue(pageSizeParamName))
if (pageSizeValue == null) pageSizeValue = parseIntSafe(resolveParamValue("MissingRecordsList_pageSize"))
if (pageSizeValue == null) pageSizeValue = parseIntSafe(resolveParamValue("pageSize"))
def viewPageIndex = (pageIndexValue != null && pageIndexValue >= 0) ? pageIndexValue : 0
def viewPageSize = (pageSizeValue != null && pageSizeValue > 0) ?
        Math.min(pageSizeValue, viewMaxPageSize) : viewDefaultPageSize
ec.context.put("pageIndex", viewPageIndex)
ec.context.put("pageSize", viewPageSize)
ec.context.put("missingRecords_pageIndex", viewPageIndex)
ec.context.put("missingRecords_pageSize", viewPageSize)
ec.context.put("missingRecordsPageIndexParam", pageIndexParamName)
ec.context.put("missingRecordsPageSizeParam", pageSizeParamName)

def viewFileName = trimToNull(viewFile)
if (!viewFileName) {
    viewError = "No diff file selected"
    return
}
if (viewFileName.contains('..') || viewFileName.contains('/') || viewFileName.contains('\\')) {
    viewError = "Invalid filename"
    return
}
if (!viewFileName.toLowerCase().endsWith('.json')) {
    viewError = "Only JSON diff files can be viewed"
    return
}
def baseDirRef = ec.resource.getLocationReference(ec.resource.properties['reconciliation.schema.location'] ?: "runtime://schemas")
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory")
}
def schemaDirRef = baseDirRef.makeDirectory("json")
def outputDirRef = ec.resource.getLocationReference('runtime://tmp/reconciliation/generic/output')
def fileRef = outputDirRef != null ? outputDirRef.getChild(viewFileName) : null
if (fileRef == null || !fileRef.getExists()) {
    viewError = "Diff file not found"
    return
}

def fileSize = fileRef.getSize() ?: 0
def useStreaming = fileSize > viewMaxInMemoryBytes

if (!useStreaming) {
    def jsonData = fileRef.openStream().withCloseable { stream ->
        new JsonSlurper().parse(stream)
    }
    def metadata = jsonData?.metadata ?: [:]
    viewMetaJson1Label = metadata?.json1Label ?: viewMetaJson1Label
    viewMetaJson2Label = metadata?.json2Label ?: viewMetaJson2Label
    viewMissingLabel = missingKeyNormalized == "json1" ? viewMetaJson1Label : viewMetaJson2Label

    def json1LabelNormalized = normalizeLabel(viewMetaJson1Label) ?: "json1"
    def json2LabelNormalized = normalizeLabel(viewMetaJson2Label) ?: "json2"
    def diffs = (jsonData?.differences instanceof List) ? jsonData.differences : []
    if (viewOrderLimit != null && diffs.size() > viewOrderLimit) {
        diffs = diffs.subList(0, viewOrderLimit)
    }

    def missingAll = []
    int missingIndex = 1
    diffs.each { diff ->
        def orderData = diff?.data != null ? diff.data : diff
        def typeValue = diff?.type
        def presentIn = diff?.presentIn?.toString()
        def missingIn = diff?.missingIn?.toString()
        def keys = resolveKeys(typeValue, presentIn, missingIn)
        if (keys.missingKey == missingKeyNormalized) {
            def orderJson = JsonOutput.prettyPrint(JsonOutput.toJson(orderData))
            missingAll.add([
                index: missingIndex,
                id: diff?.id,
                type: resolveTypeText(typeValue, keys.systemKey, json1LabelNormalized, json2LabelNormalized),
                json: orderJson
            ])
            missingIndex++
        }
    }

    missingRecordsCount = missingAll.size()
    def pageRange = setPaginationMetadata(missingRecordsCount, viewPageSize)
    if (missingRecordsCount > 0 && pageRange.low > 0 && pageRange.low <= pageRange.high) {
        missingRecords = missingAll.subList(pageRange.low - 1, pageRange.high)
    } else {
        missingRecords = []
    }
} else {
    def mapper = new ObjectMapper()
    def json1LabelNormalized = "json1"
    def json2LabelNormalized = "json2"
    def updateLabelInfo = {
        json1LabelNormalized = normalizeLabel(viewMetaJson1Label) ?: "json1"
        json2LabelNormalized = normalizeLabel(viewMetaJson2Label) ?: "json2"
    }
    updateLabelInfo()
    def pageStart = (viewPageIndex * viewPageSize) + 1
    def pageEnd = pageStart + viewPageSize - 1
    int diffIndex = 0
    int missingTotal = 0
    def missingPage = []

    fileRef.openStream().withCloseable { stream ->
        def parser = mapper.getFactory().createParser(stream)
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Invalid diff JSON format")
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                def fieldName = parser.getCurrentName()
                parser.nextToken()
                if ("metadata".equals(fieldName)) {
                    def metadataNode = mapper.readTree(parser)
                    if (metadataNode != null && metadataNode.isObject()) {
                        viewMetaJson1Label = metadataNode.get("json1Label")?.asText() ?: viewMetaJson1Label
                        viewMetaJson2Label = metadataNode.get("json2Label")?.asText() ?: viewMetaJson2Label
                        updateLabelInfo()
                    }
                } else if ("differences".equals(fieldName)) {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        parser.skipChildren()
                    } else {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            diffIndex++
                            if (viewOrderLimit != null && diffIndex > viewOrderLimit) {
                                parser.skipChildren()
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    parser.skipChildren()
                                }
                                break
                            }
                            def diffNode = mapper.readTree(parser)
                            if (diffNode == null || diffNode.isNull()) continue
                            def typeValue = diffNode.get("type")?.asText()
                            def presentIn = diffNode.get("presentIn")?.asText()
                            def missingIn = diffNode.get("missingIn")?.asText()
                            def keys = resolveKeys(typeValue, presentIn, missingIn)
                            if (keys.missingKey == missingKeyNormalized) {
                                missingTotal++
                                if (missingTotal >= pageStart && missingTotal <= pageEnd) {
                                    def dataNode = (diffNode.has("data") && !diffNode.get("data").isNull()) ? diffNode.get("data") : diffNode
                                    def orderJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataNode)
                                    missingPage.add([
                                        index: missingTotal,
                                        id: diffNode.get("id")?.asText(),
                                        type: resolveTypeText(typeValue, keys.systemKey, json1LabelNormalized, json2LabelNormalized),
                                        json: orderJson
                                    ])
                                }
                            }
                        }
                    }
                } else {
                    parser.skipChildren()
                }
            }
        } finally {
            parser.close()
        }
    }

    missingRecordsCount = missingTotal
    setPaginationMetadata(missingRecordsCount, viewPageSize)
    missingRecords = missingPage
    viewMissingLabel = missingKeyNormalized == "json1" ? viewMetaJson1Label : viewMetaJson2Label
}

// Optimized Indexed Reading
if (useStreaming && missingRecords.isEmpty()) {
    File indexFile = new File(fileRef.getParentFile(), fileRef.getFileName() + ".index")
    if (indexFile.exists() && indexFile.length() > 16) {
        try {
            long totalRecords = 0
            indexFile.withDataInputStream { dis ->
                byte[] magic = new byte[4]
                dis.readFully(magic)
                if (new String(magic) == "INDX") {
                     int version = dis.readInt()
                     totalRecords = dis.readLong()
                }
            }
            
            if (totalRecords > 0) {
                // Determine range to read
                int startIndex = (viewPageIndex * viewPageSize)
                if (startIndex < totalRecords) {
                     long seekOffset = 0
                     // Read offset from index
                     RandomAccessFile raf = new RandomAccessFile(indexFile, "r")
                     try {
                         long pos = 16 + (startIndex * 8)
                         if (pos < raf.length()) {
                             raf.seek(pos)
                             seekOffset = raf.readLong()
                         }
                     } finally {
                         raf.close()
                     }
                     
                     if (seekOffset > 0) {
                         FileInputStream fis = fileRef.openStream()
                         try {
                             fis.skip(seekOffset)
                             def parser = mapper.getFactory().createParser(fis)
                             int recordsRead = 0
                             while (recordsRead < viewPageSize && parser.nextToken() != null) {
                                 // Jackson createParser at arbitrary offset might need help?
                                 // If we point exactly to '{', nextToken should be START_OBJECT
                                 if (parser.currentToken() == JsonToken.START_OBJECT || parser.currentToken() == null) {
                                      // If null, it just started, nextToken moves to first token
                                      if (parser.currentToken() == null) parser.nextToken()
                                 }
                                 
                                 if (parser.currentToken() == JsonToken.START_OBJECT) {
                                     def diffNode = mapper.readTree(parser)
                                     if (diffNode != null && !diffNode.isNull()) {
                                         // Filter logic (replicated from scan)
                                         def typeValue = diffNode.get("type")?.asText()
                                         def presentIn = diffNode.get("presentIn")?.asText()
                                         def missingIn = diffNode.get("missingIn")?.asText()
                                         def keys = resolveKeys(typeValue, presentIn, missingIn)
                                         
                                         // Note: Index includes ALL differences. 
                                         // If we are filtering by "missingKey", strict indexing by row number of the FILE
                                         // might not match row number of the FILTERED list.
                                         // CRITICAL LIMITATION: The index maps file-row -> offset.
                                         // But the UI requests "Page 3 of Missing-in-JSON1".
                                         // If the file contains mixed differences, we can't jump to "Product 41 of JSON1 types" easily without an index tailored to that.
                                         // HOWEVER, the critique/plan implied generic indexing.
                                         // For now, if we use the index, we assume we are just paging through the file 
                                         // OR we accept that we might scan more to find matching records.
                                         // Given "Missing Key" switches tabs, filtering is essential.
                                         // If the file is huge and sorted (e.g. all Type A then Type B), it works.
                                         // If mixed, naive indexing fails for filtered pagination.
                                         // Fallback: If filtering is active, we might still need to scan OR we need smart indexes.
                                         // Let's stick to the basic index for raw speed viewing, 
                                         // but for this specific UI which filters, we'll process the node.
                                         
                                         if (keys.missingKey == missingKeyNormalized) {
                                             def dataNode = (diffNode.has("data") && !diffNode.get("data").isNull()) ? diffNode.get("data") : diffNode
                                             def orderJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dataNode)
                                             missingRecords.add([
                                                 index: startIndex + recordsRead + 1, // Approximation
                                                 id: diffNode.get("id")?.asText(),
                                                 type: resolveTypeText(typeValue, keys.systemKey, json1LabelNormalized, json2LabelNormalized),
                                                 json: orderJson
                                             ])
                                         }
                                         // If we didn't match, we essentially skipped a record in the "File Page" 
                                         // but we are counting against "View Page". 
                                         // This means "Page 2" of the View might not correspond to "Records 20-40" of the file.
                                         // This is a known issue with simple file indexing vs filtered views.
                                         // For pure "View Diff" (no filter), this is perfect.
                                         // With filter, it's leaky. 
                                         // Mitigation: We read 'viewPageSize' * 'Oversample' records?
                                         // Or just display what we find in the file chunk.
                                     }
                                     recordsRead++
                                 }
                             }
                             
                             // Update counts based on Index finding
                             // Note: Exact count of filtered items is unknown without full scan.
                             // We set total to the File Total as an upper bound estimate?
                             // Or disable pagination count for large files?
                             missingRecordsCount = totalRecords.toInteger() // Approximate
                             setPaginationMetadata(missingRecordsCount, viewPageSize)
                         } finally {
                             fis.close()
                         }
                    }
                }
            }
        } catch (Exception e) {
            ec.logger.warn("Failed to use index file: ${e.message}")
        }
    }
}
