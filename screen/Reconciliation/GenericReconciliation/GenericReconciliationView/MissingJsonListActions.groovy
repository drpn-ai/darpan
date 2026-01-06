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
