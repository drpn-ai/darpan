import groovy.json.JsonSlurper
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper

def logger = org.slf4j.LoggerFactory.getLogger("darpan.reconciliation.view.GenericReconciliationView")

def requestUri = ec.web?.request?.getRequestURI() ?: ''
def isMenuDataRequest = requestUri.contains('/menuData')

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

def parsePositiveInt = { val ->
    if (val == null) return null
    try {
        def parsed = Integer.parseInt(val.toString().trim())
        return parsed > 0 ? parsed : null
    } catch (Exception ignored) {
        return null
    }
}

def normalizeTypeLabel = { label ->
    def normalized = label?.toString()?.trim()
    if (!normalized) return null
    normalized = normalized.replaceAll(/\s+/, '_')
    normalized = normalized.replaceAll(/[^A-Za-z0-9_]/, "")
    return normalized ?: null
}

def buildSummaryKeys = { json1Label, json2Label ->
    def json1TypeLabel = normalizeTypeLabel(json1Label) ?: "json1"
    def json2TypeLabel = normalizeTypeLabel(json2Label) ?: "json2"
    return [
        json1Key: "onlyIn${json1TypeLabel}Count",
        json2Key: "onlyIn${json2TypeLabel}Count"
    ]
}

def applySummaryMap = { summary, summaryKeys ->
    if (summary == null) return
    def totalDiffs = summary?.totalDifferences
    if (totalDiffs != null) {
        viewOrderTotalCount = totalDiffs
        viewTotalDifferences = totalDiffs
    }
    def onlyInJson1 = summary?.onlyInJson1Count
    def onlyInJson2 = summary?.onlyInJson2Count
    if (onlyInJson1 == null && summaryKeys?.json1Key) {
        onlyInJson1 = summary?.get(summaryKeys.json1Key)
    }
    if (onlyInJson2 == null && summaryKeys?.json2Key) {
        onlyInJson2 = summary?.get(summaryKeys.json2Key)
    }
    if (onlyInJson1 != null) viewOnlyInJson1Count = onlyInJson1
    if (onlyInJson2 != null) viewOnlyInJson2Count = onlyInJson2
}

def readSummaryCount = { node, key ->
    def valueNode = node?.get(key)
    if (valueNode == null || valueNode.isNull()) return null
    if (valueNode.isNumber()) return valueNode.longValue()
    def valueText = valueNode.asText()
    if (valueText) {
        try {
            return Long.parseLong(valueText)
        } catch (Exception ignored) {
            return null
        }
    }
    return null
}

def applySummaryNode = { node, summaryKeys ->
    if (node == null || !node.isObject()) return
    def totalDiffs = readSummaryCount(node, "totalDifferences")
    if (totalDiffs != null) {
        viewOrderTotalCount = totalDiffs
        viewTotalDifferences = totalDiffs
    }
    def onlyInJson1 = readSummaryCount(node, "onlyInJson1Count")
    def onlyInJson2 = readSummaryCount(node, "onlyInJson2Count")
    if (onlyInJson1 == null && summaryKeys?.json1Key) {
        onlyInJson1 = readSummaryCount(node, summaryKeys.json1Key)
    }
    if (onlyInJson2 == null && summaryKeys?.json2Key) {
        onlyInJson2 = readSummaryCount(node, summaryKeys.json2Key)
    }
    if (onlyInJson1 != null) viewOnlyInJson1Count = onlyInJson1
    if (onlyInJson2 != null) viewOnlyInJson2Count = onlyInJson2
}

def finalizeViewCounts = {
    if (viewOrderTotalCount == null) viewOrderTotalCount = 0
    if (viewTotalDifferences == null) viewTotalDifferences = viewOrderTotalCount
    viewOrdersLimited = (viewOrderLimit != null && viewOrderTotalCount > viewOrderLimit)
    viewOrderCount = viewOrdersLimited ? viewOrderLimit : viewOrderTotalCount
    viewMissingJson1Title = "Missing in ${viewMetaJson1Label ?: 'JSON 1'}"
    viewMissingJson2Title = "Missing in ${viewMetaJson2Label ?: 'JSON 2'}"
}

def extraPathList = sri?.screenUrlInfo?.extraPathNameList
def viewFileName = null
if (extraPathList && extraPathList.size() > 0) {
    viewFileName = trimToNull(extraPathList[0])
}
if (!viewFileName) {
    def candidates = [
        viewFile,
        resolveParamValue("viewFile"),
        resolveParamValue("filename")
    ]
    candidates.find { candidate ->
        viewFileName = trimToNull(candidate)
        return viewFileName
    }
}
if (!viewFileName) {
    logger.info("GenericReconciliationView no viewFileName from extraPathList=${extraPathList}")
} else {
    logger.info("GenericReconciliationView resolved viewFileName=${viewFileName} extraPathList=${extraPathList}")
}
viewFile = viewFileName ?: null

def viewParams = [
    viewFile: resolveParamValue("viewFile"),
    filename: resolveParamValue("filename"),
    viewLimit: resolveParamValue("viewLimit")
]
logger.info("GenericReconciliationView load requestUri=${requestUri} menuData=${isMenuDataRequest} params=${viewParams}")

viewError = null
viewOrderTotalCount = 0
viewTotalDifferences = 0
viewOnlyInJson1Count = null
viewOnlyInJson2Count = null
viewOrderCount = 0
viewOrdersLimited = false
viewOrderLimit = parsePositiveInt(viewLimit)
viewMetaJson1Label = "JSON 1"
viewMetaJson2Label = "JSON 2"
viewMissingJson1Title = "Missing in ${viewMetaJson1Label ?: 'JSON 1'}"
viewMissingJson2Title = "Missing in ${viewMetaJson2Label ?: 'JSON 2'}"
viewMaxInMemoryBytes = 5 * 1024 * 1024

if (!viewFileName) {
    viewError = "No diff file selected"
    logger.warn("GenericReconciliationView error=${viewError}")
    return
}

if (isMenuDataRequest) {
    return
}

if (viewFileName.contains('..') || viewFileName.contains('/') || viewFileName.contains('\\')) {
    viewError = "Invalid filename"
    logger.warn("GenericReconciliationView error=${viewError} viewFile=${viewFileName}")
    return
}

if (!viewFileName.toLowerCase().endsWith('.json')) {
    viewError = "Only JSON diff files can be viewed"
    logger.warn("GenericReconciliationView error=${viewError} viewFile=${viewFileName}")
    return
}

def outputDirRef = ec.resource.getLocationReference('runtime://tmp/reconciliation/generic/output')
def fileRef = outputDirRef != null ? outputDirRef.getChild(viewFileName) : null
if (fileRef == null || !fileRef.getExists()) {
    viewError = "Diff file not found"
    logger.warn("GenericReconciliationView error=${viewError} viewFile=${viewFileName} outputDir=${outputDirRef?.getLocation()}")
    return
}

def fileSize = fileRef.getSize() ?: 0
def useStreaming = fileSize > viewMaxInMemoryBytes

try {
    if (!useStreaming) {
        def jsonData = fileRef.openStream().withCloseable { stream ->
            new JsonSlurper().parse(stream)
        }
        def metadata = jsonData?.metadata ?: [:]
        viewMetaJson1Label = metadata?.json1Label ?: viewMetaJson1Label
        viewMetaJson2Label = metadata?.json2Label ?: viewMetaJson2Label
        def summary = jsonData?.summary ?: [:]
        applySummaryMap(summary, buildSummaryKeys(viewMetaJson1Label, viewMetaJson2Label))
    } else {
        def mapper = new ObjectMapper()
        def summaryKeys = buildSummaryKeys(viewMetaJson1Label, viewMetaJson2Label)
        def summaryNode = null

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
                            summaryKeys = buildSummaryKeys(viewMetaJson1Label, viewMetaJson2Label)
                            if (summaryNode != null) applySummaryNode(summaryNode, summaryKeys)
                        }
                    } else if ("summary".equals(fieldName)) {
                        summaryNode = mapper.readTree(parser)
                        applySummaryNode(summaryNode, summaryKeys)
                    } else {
                        parser.skipChildren()
                    }
                }
            } finally {
                parser.close()
            }
        }
    }
    finalizeViewCounts()
    logger.info("GenericReconciliationView loaded summary total=${viewOrderTotalCount}")
} catch (Exception e) {
    viewError = "Unable to parse diff JSON: ${e.message}"
    logger.warn("GenericReconciliationView error=${viewError} viewFile=${viewFileName}", e)
}
