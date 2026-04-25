package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSetCompareScopeAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RuleSetCompareScopeAdapter.class)

    static Map<String, Object> prepareRuleSetCompareScope(ExecutionContext ec) {
        Map<String, Object> context = (Map<String, Object>) ec.contextStack

        String ruleSetId = ReconciliationServices.normalize(context.get("ruleSetId"))
        String compareScopeId = ReconciliationServices.normalize(context.get("compareScopeId"))
        String file1Location = ReconciliationServices.normalize(context.get("file1Location"))
        String file2Location = ReconciliationServices.normalize(context.get("file2Location"))
        String file1Name = ReconciliationServices.normalize(context.get("file1Name"))
        String file2Name = ReconciliationServices.normalize(context.get("file2Name"))
        String file1FileTypeEnumId = ReconciliationServices.normalize(context.get("file1FileTypeEnumId"))
        String file2FileTypeEnumId = ReconciliationServices.normalize(context.get("file2FileTypeEnumId"))
        String file1SchemaFileName = ReconciliationServices.normalize(context.get("file1SchemaFileName"))
        String file2SchemaFileName = ReconciliationServices.normalize(context.get("file2SchemaFileName"))
        String file1Label = ReconciliationServices.normalize(context.get("file1Label"))
        String file2Label = ReconciliationServices.normalize(context.get("file2Label"))
        Boolean hasHeader = (Boolean) context.get("hasHeader")
        Boolean allowDuplicateCompareIds = (Boolean) context.get("allowDuplicateCompareIds")
        String sparkMaster = ReconciliationServices.normalize(context.get("sparkMaster")) ?: "local[*]"
        String sparkAppName = ReconciliationServices.normalize(context.get("sparkAppName")) ?: "RuleSetCompareScopePreparation"

        List<String> processingWarnings = ((List<String>) context.get("processingWarnings") ?: []) as List<String>
        List<String> validationErrors = ((List<String>) context.get("validationErrors") ?: []) as List<String>

        if (!ruleSetId) throw new IllegalArgumentException("ruleSetId is required")
        if (!compareScopeId) throw new IllegalArgumentException("compareScopeId is required")
        if (!file1Location || !file2Location) throw new IllegalArgumentException("file1Location and file2Location are required")

        def compareScope = ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(true)
                .one()
        if (!compareScope) {
            throw new IllegalArgumentException("Compare scope ${compareScopeId} was not found")
        }
        String scopeRuleSetId = ReconciliationServices.normalize(compareScope.ruleSetId)
        String compareScopeLabel = ReconciliationServices.compareScopeDisplayName(compareScope.compareScopeId, compareScope.description)
        if (scopeRuleSetId != ruleSetId) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' belongs to RuleSet ${scopeRuleSetId}, not ${ruleSetId}")
        }

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(true)
                .list()
        if (!sources) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has no source definitions")
        }

        Map<String, Object> sourceBySide = [:]
        sources.each { source ->
            String fileSide = ReconciliationServices.normalize(source.fileSide)?.toUpperCase()
            if (!(fileSide in ["FILE_1", "FILE_2"])) {
                throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has unsupported fileSide ${source.fileSide}. Supported values: FILE_1, FILE_2")
            }
            if (sourceBySide.containsKey(fileSide)) {
                throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has more than one source for ${fileSide}")
            }
            sourceBySide[fileSide] = source
        }

        if (!sourceBySide.FILE_1 || !sourceBySide.FILE_2) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' must define both FILE_1 and FILE_2 sources")
        }

        Map<String, Object> file1Config = buildSideConfig(sourceBySide.FILE_1, file1FileTypeEnumId, file1SchemaFileName)
        Map<String, Object> file2Config = buildSideConfig(sourceBySide.FILE_2, file2FileTypeEnumId, file2SchemaFileName)

        String file1SafeName = file1Name ?: safeNameFromLocation(file1Location, "file1")
        String file2SafeName = file2Name ?: safeNameFromLocation(file2Location, "file2")

        String file1Type = resolveFileTypeCode(ec, (String) file1Config.fileTypeEnumId) ?: detectFileTypeFromName(file1SafeName)
        String file2Type = resolveFileTypeCode(ec, (String) file2Config.fileTypeEnumId) ?: detectFileTypeFromName(file2SafeName)
        if (!file1Type) {
            processingWarnings.add("File type auto-detected as CSV for ${file1SafeName} because compare scope '${compareScopeLabel}' has no FILE_1 fileTypeEnumId")
            file1Type = "CSV"
        }
        if (!file2Type) {
            processingWarnings.add("File type auto-detected as CSV for ${file2SafeName} because compare scope '${compareScopeLabel}' has no FILE_2 fileTypeEnumId")
            file2Type = "CSV"
        }

        file1Type = file1Type.toUpperCase()
        file2Type = file2Type.toUpperCase()
        validateSupportedFileType(compareScopeLabel, "FILE_1", file1Type)
        validateSupportedFileType(compareScopeLabel, "FILE_2", file2Type)

        Map<String, Object> file1IdSpec = buildCompareSourceIdSpec(compareScopeLabel, "FILE_1", file1Type, file1Config, processingWarnings)
        Map<String, Object> file2IdSpec = buildCompareSourceIdSpec(compareScopeLabel, "FILE_2", file2Type, file2Config, processingWarnings)

        String resolvedFile1Label = file1Label ?: resolveEnumLabel(ec, (String) file1Config.systemEnumId, "File 1")
        String resolvedFile2Label = file2Label ?: resolveEnumLabel(ec, (String) file2Config.systemEnumId, "File 2")

        logger.info("Preparing compare scope extraction: ruleSet={} compareScope={} objectType={} file1Type={} file2Type={}",
                ruleSetId, compareScopeId, compareScope.objectType, file1Type, file2Type)

        SparkSession spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .config("spark.sql.adaptive.enabled", "true")
                .getOrCreate()

        Map<String, Object> ingest1 = ReconciliationServices.ingestFile(
                ec, spark, file1Location, file1Type, file1IdSpec, hasHeader != null ? hasHeader : true,
                resolvedFile1Label, validationErrors, (String) file1Config.schemaFileName)
        Map<String, Object> ingest2 = ReconciliationServices.ingestFile(
                ec, spark, file2Location, file2Type, file2IdSpec, hasHeader != null ? hasHeader : true,
                resolvedFile2Label, validationErrors, (String) file2Config.schemaFileName)

        if (allowDuplicateCompareIds == Boolean.TRUE) {
            ingest1 = collapseDuplicateCompareIdsForBaseDiffOnly(ingest1, compareScopeLabel, "FILE_1", resolvedFile1Label, processingWarnings)
            ingest2 = collapseDuplicateCompareIdsForBaseDiffOnly(ingest2, compareScopeLabel, "FILE_2", resolvedFile2Label, processingWarnings)
        } else {
            ReconciliationServices.validateUniqueCompareIds((Dataset) ingest1.dataDf, compareScopeLabel, "FILE_1", resolvedFile1Label)
            ReconciliationServices.validateUniqueCompareIds((Dataset) ingest2.dataDf, compareScopeLabel, "FILE_2", resolvedFile2Label)
        }

        return [
                ruleSetId        : ruleSetId,
                compareScopeId   : compareScopeId,
                compareScopeDescription: compareScopeLabel,
                objectType       : ReconciliationServices.normalize(compareScope.objectType),
                file1Type        : file1Type,
                file2Type        : file2Type,
                file1SystemEnumId: file1Config.systemEnumId,
                file2SystemEnumId: file2Config.systemEnumId,
                file1SchemaFileName: file1Config.schemaFileName,
                file2SchemaFileName: file2Config.schemaFileName,
                file1IdExpression: file1IdSpec.idExpr,
                file2IdExpression: file2IdSpec.idExpr,
                file1IdNormalizer: file1IdSpec.idNormalizer,
                file2IdNormalizer: file2IdSpec.idNormalizer,
                file1Label       : resolvedFile1Label,
                file2Label       : resolvedFile2Label,
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
        ingest.dataDf = collapsedDataDf
        ingest.idDf = collapsedDataDf.select("compare_id").distinct()
        return ingest
    }

    private static Map<String, Object> buildSideConfig(def source, String fileTypeEnumIdOverride, String schemaFileNameOverride) {
        return [
                fileSide            : ReconciliationServices.normalize(source.fileSide)?.toUpperCase(),
                systemEnumId        : ReconciliationServices.normalize(source.systemEnumId),
                fileTypeEnumId      : fileTypeEnumIdOverride ?: ReconciliationServices.normalize(source.fileTypeEnumId),
                schemaFileName      : schemaFileNameOverride ?: ReconciliationServices.normalize(source.schemaFileName),
                recordRootExpression: ReconciliationServices.normalize(source.recordRootExpression),
                primaryIdExpression : ReconciliationServices.normalize(source.primaryIdExpression),
                idValueNormalizer   : ReconciliationServices.normalize(source.idValueNormalizer)
        ]
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
        if (!(fileType in ["CSV", "JSON"])) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} resolved unsupported file type ${fileType}. Supported values: CSV, JSON")
        }
    }

    private static String resolveFileTypeCode(ExecutionContext ec, String enumId) {
        String normalized = ReconciliationServices.normalize(enumId)
        if (!normalized) return null

        def enumValue = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", normalized)
                .disableAuthz()
                .useCache(true)
                .one()
        return ReconciliationServices.normalize(enumValue?.enumCode)
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

    private static String resolveEnumLabel(ExecutionContext ec, String enumId, String fallback) {
        String normalized = ReconciliationServices.normalize(enumId)
        if (!normalized) return fallback

        def enumValue = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", normalized)
                .disableAuthz()
                .useCache(true)
                .one()
        String code = ReconciliationServices.normalize(enumValue?.enumCode)
        if (code) return code

        String description = ReconciliationServices.normalize(enumValue?.description)
        if (description) return description

        return normalized
    }
}
