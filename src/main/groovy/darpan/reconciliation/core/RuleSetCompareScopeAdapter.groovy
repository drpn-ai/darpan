package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSetCompareScopeAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RuleSetCompareScopeAdapter.class)
    private static final List<String> FILE_SIDES = ["FILE_1", "FILE_2"].asImmutable()
    private static final Map<String, String> SIDE_PREFIX_BY_SIDE = [FILE_1: "file1", FILE_2: "file2"].asImmutable()
    private static final Map<String, String> SIDE_FALLBACK_LABEL_BY_SIDE = [FILE_1: "File 1", FILE_2: "File 2"].asImmutable()
    private static final Set<String> SUPPORTED_FILE_TYPES = ["CSV", "JSON"] as Set

    static Map<String, Object> prepareRuleSetCompareScope(ExecutionContext ec) {
        Map<String, Object> context = (Map<String, Object>) ec.contextStack

        String ruleSetId = ReconciliationServices.normalize(context.get("ruleSetId"))
        String compareScopeId = ReconciliationServices.normalize(context.get("compareScopeId"))
        Boolean hasHeader = (Boolean) context.get("hasHeader")
        Boolean allowDuplicateCompareIds = (Boolean) context.get("allowDuplicateCompareIds")
        String sparkMaster = ReconciliationServices.normalize(context.get("sparkMaster")) ?: "local[*]"
        String sparkAppName = ReconciliationServices.normalize(context.get("sparkAppName")) ?: "RuleSetCompareScopePreparation"

        List<String> processingWarnings = ((List<String>) context.get("processingWarnings") ?: []) as List<String>
        List<String> validationErrors = ((List<String>) context.get("validationErrors") ?: []) as List<String>
        Map<String, Object> sideInputBySide = FILE_SIDES.collectEntries { String fileSide ->
            String prefix = SIDE_PREFIX_BY_SIDE[fileSide]
            [(fileSide): [
                    fileLocation  : ReconciliationServices.normalize(context.get("${prefix}Location")),
                    fileName      : ReconciliationServices.normalize(context.get("${prefix}Name")),
                    fileTypeEnumId: ReconciliationServices.normalize(context.get("${prefix}FileTypeEnumId")),
                    schemaFileName: ReconciliationServices.normalize(context.get("${prefix}SchemaFileName")),
                    fileLabel     : ReconciliationServices.normalize(context.get("${prefix}Label"))
            ]]
        }

        if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")
        if (!compareScopeId) throw new IllegalArgumentException("compareScopeId is required")
        if (!sideInputBySide.FILE_1.fileLocation || !sideInputBySide.FILE_2.fileLocation) throw new IllegalArgumentException("file1Location and file2Location are required")

        def compareScope = ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (!compareScope) {
            throw new IllegalArgumentException("Compare scope ${compareScopeId} was not found")
        }
        String scopeRuleSetId = ReconciliationServices.normalize(compareScope.ruleSetId)
        String compareScopeLabel = ReconciliationServices.compareScopeDisplayName(compareScope.compareScopeId, compareScope.description)
        if (scopeRuleSetId != ruleSetId) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' belongs to RuleSet ${scopeRuleSetId}, not ${ruleSetId}")
        }

        List sources = compareScope.findRelated("sources", null, ["fileSide"], false, false) ?: []
        if (!sources) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has no source definitions")
        }
        Map<String, Object> sourceBySide = sources.collectEntries { source ->
            [(ReconciliationServices.normalize(source.fileSide)?.toUpperCase()): source]
        }
        String unsupportedFileSide = sourceBySide.keySet().find { String fileSide -> !(fileSide in FILE_SIDES) }
        if (unsupportedFileSide) {
            def source = sources.find { source -> ReconciliationServices.normalize(source.fileSide)?.toUpperCase() == unsupportedFileSide }
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has unsupported fileSide ${source?.fileSide}. Supported values: FILE_1, FILE_2")
        }
        if (FILE_SIDES.any { String fileSide -> !sourceBySide[fileSide] }) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' must define both FILE_1 and FILE_2 sources")
        }

        Map<String, Object> sideConfigBySide = FILE_SIDES.collectEntries { String fileSide ->
            Map<String, Object> sideInput = (Map<String, Object>) sideInputBySide[fileSide]
            def source = sourceBySide[fileSide]
            [(fileSide): [
                    fileSide            : ReconciliationServices.normalize(source.fileSide)?.toUpperCase(),
                    systemEnumId        : ReconciliationServices.normalize(source.systemEnumId),
                    fileTypeEnumId      : sideInput.fileTypeEnumId ?: ReconciliationServices.normalize(source.fileTypeEnumId),
                    schemaFileName      : sideInput.schemaFileName ?: ReconciliationServices.normalize(source.schemaFileName),
                    recordRootExpression: ReconciliationServices.normalize(source.recordRootExpression),
                    primaryIdExpression : ReconciliationServices.normalize(source.primaryIdExpression),
                    idValueNormalizer   : ReconciliationServices.normalize(source.idValueNormalizer)
            ]]
        }
        List<String> fileTypeEnumIds = sideConfigBySide.values()
                .collect { Map<String, Object> sideConfig -> ReconciliationServices.normalize(sideConfig.fileTypeEnumId) }
                .findAll { String enumId -> enumId }
                .unique()
        Map<String, Object> fileTypeEnumById = fileTypeEnumIds ?
                ec.entity.find("moqui.basic.Enumeration")
                        .condition("enumId", "in", fileTypeEnumIds)
                        .disableAuthz()
                        .useCache(true)
                        .list()
                        .collectEntries { enumValue -> [(ReconciliationServices.normalize(enumValue.enumId)): enumValue] } :
                [:]
        Map<String, Object> sidePlanBySide = FILE_SIDES.collectEntries { String fileSide ->
            Map<String, Object> sideInput = (Map<String, Object>) sideInputBySide[fileSide]
            Map<String, Object> sideConfig = (Map<String, Object>) sideConfigBySide[fileSide]
            String safeName = (String) sideInput.fileName ?:
                    safeNameFromLocation((String) sideInput.fileLocation, SIDE_PREFIX_BY_SIDE[fileSide])
            String fileType = ReconciliationServices.normalize(fileTypeEnumById[sideConfig.fileTypeEnumId]?.enumCode) ?:
                    detectFileTypeFromName(safeName)
            if (!fileType) {
                processingWarnings.add("File type auto-detected as CSV for ${safeName} because compare scope '${compareScopeLabel}' has no ${fileSide} fileTypeEnumId")
                fileType = "CSV"
            }

            fileType = fileType.toUpperCase()
            validateSupportedFileType(compareScopeLabel, fileSide, fileType)
            [(fileSide): [
                    config      : sideConfig,
                    fileLocation: sideInput.fileLocation,
                    fileType    : fileType,
                    idSpec      : buildCompareSourceIdSpec(compareScopeLabel, fileSide, fileType, sideConfig, processingWarnings),
                    label       : sideInput.fileLabel ?: ReconciliationServices.resolveEnumLabel(ec, (String) sideConfig.systemEnumId, SIDE_FALLBACK_LABEL_BY_SIDE[fileSide])
            ]]
        }
        Map<String, Object> file1Plan = (Map<String, Object>) sidePlanBySide.FILE_1
        Map<String, Object> file2Plan = (Map<String, Object>) sidePlanBySide.FILE_2
        Map<String, Object> file1Config = (Map<String, Object>) file1Plan.config
        Map<String, Object> file2Config = (Map<String, Object>) file2Plan.config
        Map<String, Object> file1IdSpec = (Map<String, Object>) file1Plan.idSpec
        Map<String, Object> file2IdSpec = (Map<String, Object>) file2Plan.idSpec

        logger.info("Preparing compare scope extraction: ruleSet={} compareScope={} objectType={} file1Type={} file2Type={}",
                ruleSetId, compareScopeId, compareScope.objectType, file1Plan.fileType, file2Plan.fileType)

        SparkSession spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .config("spark.sql.adaptive.enabled", "true")
                .getOrCreate()

        Map<String, Object> ingestBySide = FILE_SIDES.collectEntries { String fileSide ->
            Map<String, Object> plan = (Map<String, Object>) sidePlanBySide[fileSide]
            Map<String, Object> config = (Map<String, Object>) plan.config
            [(fileSide): ReconciliationServices.ingestFile(
                    ec, spark, (String) plan.fileLocation, (String) plan.fileType, (Map) plan.idSpec,
                    hasHeader != null ? hasHeader : true, (String) plan.label, validationErrors,
                    (String) config.schemaFileName)]
        }
        Map<String, Object> preparedIngestBySide = FILE_SIDES.collectEntries { String fileSide ->
            Map<String, Object> plan = (Map<String, Object>) sidePlanBySide[fileSide]
            Map<String, Object> ingest = (Map<String, Object>) ingestBySide[fileSide]
            if (allowDuplicateCompareIds == Boolean.TRUE) {
                [(fileSide): collapseDuplicateCompareIdsForBaseDiffOnly(ingest, compareScopeLabel, fileSide, (String) plan.label, processingWarnings)]
            } else {
                ReconciliationServices.validateUniqueCompareIds((Dataset) ingest.dataDf, compareScopeLabel, fileSide, (String) plan.label)
                [(fileSide): ingest]
            }
        }
        Map<String, Object> ingest1 = (Map<String, Object>) preparedIngestBySide.FILE_1
        Map<String, Object> ingest2 = (Map<String, Object>) preparedIngestBySide.FILE_2

        return [
                ruleSetId        : ruleSetId,
                compareScopeId   : compareScopeId,
                compareScopeDescription: compareScopeLabel,
                objectType       : ReconciliationServices.normalize(compareScope.objectType),
                file1Type        : file1Plan.fileType,
                file2Type        : file2Plan.fileType,
                file1SystemEnumId: file1Config.systemEnumId,
                file2SystemEnumId: file2Config.systemEnumId,
                file1SchemaFileName: file1Config.schemaFileName,
                file2SchemaFileName: file2Config.schemaFileName,
                file1IdExpression: file1IdSpec.idExpr,
                file2IdExpression: file2IdSpec.idExpr,
                file1IdNormalizer: file1IdSpec.idNormalizer,
                file2IdNormalizer: file2IdSpec.idNormalizer,
                file1Label       : file1Plan.label,
                file2Label       : file2Plan.label,
                file1IdDf        : ingest1.idDf,
                file2IdDf        : ingest2.idDf,
                file1DataDf      : ingest1.dataDf,
                file2DataDf      : ingest2.dataDf,
                validationErrors : validationErrors,
                processingWarnings: processingWarnings
        ]
    }

    private static Map<String, Object> collapseDuplicateCompareIdsForBaseDiffOnly(Map<String, Object> ingest,
                                                                                  String compareScopeLabel,
                                                                                  String fileSide,
                                                                                  String fileLabel,
                                                                                  List<String> processingWarnings) {
        Dataset dataDf = (Dataset) ingest?.dataDf
        List duplicateRows = ReconciliationServices.findDuplicateCompareIdRows(dataDf)
        if (!duplicateRows) return ingest

        String sideCode = ReconciliationServices.normalize(fileSide) ?: "FILE"
        String label = ReconciliationServices.normalize(fileLabel) ?: sideCode
        String examples = ReconciliationServices.buildDuplicateCompareIdExamples(duplicateRows)
        processingWarnings.add(
                "Compare scope '${ReconciliationServices.normalize(compareScopeLabel) ?: 'compare scope'}' ${sideCode} (${label}) collapsed duplicate primaryId values for base diff only because this RuleSet run has no active rules: ${examples}."
        )

        Dataset collapsedDataDf = ReconciliationServices.collapseDuplicateCompareIds(dataDf)
        Map<String, Object> collapsedIngest = new LinkedHashMap<>((Map<String, Object>) ingest)
        collapsedIngest.dataDf = collapsedDataDf
        collapsedIngest.idDf = collapsedDataDf.select("compare_id").distinct()
        return collapsedIngest
    }

    private static Map<String, Object> buildCompareSourceIdSpec(String compareScopeLabel, String fileSide, String fileType,
                                                                Map<String, Object> sourceConfig, List<String> processingWarnings) {
        String rawPrimaryIdExpression = ReconciliationServices.normalize(sourceConfig.primaryIdExpression)
        if (!rawPrimaryIdExpression) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} is missing primaryIdExpression")
        }

        Map<String, Object> primarySplit = ReconciliationServices.splitIdExpression(rawPrimaryIdExpression)
        String inlineNormalizer = ReconciliationServices.resolveIdNormalizer((String) primarySplit.normalizer)
        String configuredNormalizer = ReconciliationServices.resolveIdNormalizer((String) sourceConfig.idValueNormalizer)
        String finalNormalizer = configuredNormalizer ?: inlineNormalizer
        if (configuredNormalizer && inlineNormalizer && configuredNormalizer != inlineNormalizer) {
            processingWarnings.add("Compare scope '${compareScopeLabel}' ${fileSide} normalizer ${configuredNormalizer} overrides inline normalizer ${inlineNormalizer}")
        }

        String baseExpression = ReconciliationServices.normalize(primarySplit.idExpr)
        if ("JSON".equals(fileType)) {
            baseExpression = combineJsonRootAndPrimaryExpression((String) sourceConfig.recordRootExpression, baseExpression)
        } else if ("CSV".equals(fileType) && ReconciliationServices.normalize(sourceConfig.recordRootExpression)) {
            processingWarnings.add("Compare scope '${compareScopeLabel}' ${fileSide} ignores recordRootExpression for CSV input")
        }

        if (!baseExpression) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} resolved an empty primary ID expression")
        }

        String expressionWithNormalizer = finalNormalizer ? "${baseExpression}|${finalNormalizer}" : baseExpression
        return ReconciliationServices.parseIdSpec(expressionWithNormalizer, "CSV".equals(fileType))
    }

    static String combineJsonRootAndPrimaryExpression(String recordRootExpression, String primaryIdExpression) {
        String normalizedPrimary = normalizeJsonPrimaryExpression(primaryIdExpression)
        String normalizedRoot = normalizeJsonRecordRootExpression(recordRootExpression)
        if (!normalizedRoot) {
            return primaryIdExpression
        }
        if (!normalizedPrimary) {
            return normalizedRoot
        }

        String rootRelative = normalizedRoot.replaceFirst(/^\$\./, "").replaceFirst(/^\$/, "")
        if (rootRelative && (normalizedPrimary == rootRelative ||
                normalizedPrimary.startsWith(rootRelative + ".") ||
                normalizedPrimary.startsWith(rootRelative + "[*]"))) {
            return normalizedPrimary.startsWith("[") ? '$' + normalizedPrimary : '$.' + normalizedPrimary
        }

        return normalizedRoot.endsWith(".") ? normalizedRoot + normalizedPrimary : normalizedRoot + "." + normalizedPrimary
    }

    private static String normalizeJsonRecordRootExpression(String expression) {
        String raw = ReconciliationServices.normalize(expression)
        if (!raw) return null

        String normalized = raw.replaceAll(/\[(\d+)\]/, "[*]").replace(".[*]", "[*]")
        if (!normalized.endsWith("]")) normalized = normalized + "[*]"
        if (normalized.startsWith('$')) return normalized
        if (normalized.startsWith('[')) return '$' + normalized
        if (normalized.startsWith('.')) return '$' + normalized
        return '$.' + normalized
    }

    private static String normalizeJsonPrimaryExpression(String expression) {
        String raw = ReconciliationServices.normalize(expression)
        if (!raw) return null

        String normalized = raw.replaceAll(/\[(\d+)\]/, "[*]").replace(".[*]", "[*]")
        normalized = normalized.replaceFirst(/^\$\[\*\]\.?/, "")
        normalized = normalized.replaceFirst(/^\$\./, "")
        normalized = normalized.replaceFirst(/^\$/, "")
        if (normalized.startsWith(".")) normalized = normalized.substring(1)
        return normalized
    }

    private static void validateSupportedFileType(String compareScopeLabel, String fileSide, String fileType) {
        if (!(fileType in SUPPORTED_FILE_TYPES)) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} resolved unsupported file type ${fileType}. Supported values: CSV, JSON")
        }
    }

    private static String detectFileTypeFromName(String fileName) {
        String lower = fileName?.toLowerCase()
        if (lower?.endsWith(".csv")) return "CSV"
        if (lower?.endsWith(".json")) return "JSON"
        return null
    }

    private static String safeNameFromLocation(String location, String fallback) {
        String loc = ReconciliationServices.normalize(location) ?: ""
        List parts = loc.tokenize("/\\")
        return parts ? parts[-1] : (fallback ?: "file")
    }
}
