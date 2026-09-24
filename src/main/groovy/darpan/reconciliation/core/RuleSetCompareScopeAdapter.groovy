package darpan.reconciliation.core

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSetCompareScopeAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RuleSetCompareScopeAdapter.class)
    private static final List<String> FILE_SIDES = ["FILE_1", "FILE_2"].asImmutable()
    private static final Map<String, String> SIDE_PREFIX_BY_SIDE = [FILE_1: "file1", FILE_2: "file2"].asImmutable()

    /** A scope that diffs two sources. Every scope that existed before DAR-BE-049 is one of these. */
    static final String SCOPE_MODE_COMPARE = "COMPARE"
    /** A scope that evaluates ONE source, whose emitted rows ARE its findings (DAR-BE-049). */
    static final String SCOPE_MODE_EVALUATE = "EVALUATE"
    private static final List<String> SCOPE_MODES = [SCOPE_MODE_COMPARE, SCOPE_MODE_EVALUATE].asImmutable()
    private static final Map<String, String> SIDE_FALLBACK_LABEL_BY_SIDE = [FILE_1: "File 1", FILE_2: "File 2"].asImmutable()
    private static final Set<String> SUPPORTED_FILE_TYPES = ["CSV", "JSON"] as Set
    private static final int MAX_COMPOSITE_KEY_FIELDS = 5

    /**
     * How many sides this scope legally has, given its mode (DAR-BE-049).
     *
     * The engine was two-sided by construction: FILE_SIDES was a constant and a scope missing either
     * side threw. That is correct for a comparison and wrong for an exception check — "which rows fail
     * a predicate" has ONE source, and its findings are its rows. So the mode decides the side count
     * rather than a constant doing it.
     *
     * Pure and package-visible so the arithmetic is pinned by a fast unit test rather than only by a
     * booted smoke test; see RuleSetCompareScopeModeTests.
     */
    static List<String> resolveActiveSides(String scopeMode, Collection<String> presentSides, String compareScopeLabel) {
        // A stored null MUST mean COMPARE. Every scope configured before this field existed has no
        // value, and defaulting the other way would silently convert every live reconciliation into a
        // one-sided evaluate the moment the column was added.
        String mode = ReconciliationServices.normalize(scopeMode)?.toUpperCase() ?: SCOPE_MODE_COMPARE
        if (!(mode in SCOPE_MODES)) {
            // Deliberately NOT falling back to COMPARE: a typo'd mode would then run a different
            // reconciliation than the one configured and report success.
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' has unsupported " +
                    "scopeMode ${scopeMode}. Supported values: ${SCOPE_MODES.join(', ')}")
        }
        Set<String> present = ((presentSides ?: []) as Collection<String>)
                .findAll { it }.collect { it.trim().toUpperCase() }.toSet()

        if (mode == SCOPE_MODE_EVALUATE) {
            if (!present.contains("FILE_1")) {
                throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' is ${SCOPE_MODE_EVALUATE} " +
                        "and must define a FILE_1 source; the single-source plumbing is keyed on FILE_1")
            }
            if (present.contains("FILE_2")) {
                throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' is ${SCOPE_MODE_EVALUATE}, " +
                        "which takes one source, but a FILE_2 source is defined. Use ${SCOPE_MODE_COMPARE} to diff two sources.")
            }
            return ["FILE_1"].asImmutable()
        }
        if (FILE_SIDES.any { String fileSide -> !present.contains(fileSide) }) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' must define both FILE_1 and FILE_2 sources")
        }
        return FILE_SIDES
    }

    static Map<String, Object> prepareRuleSetCompareScope(ExecutionContext ec) {
        Map<String, Object> context = (Map<String, Object>) ec.contextStack

        String ruleSetId = ReconciliationServices.normalize(context.get("ruleSetId"))
        String compareScopeId = ReconciliationServices.normalize(context.get("compareScopeId"))
        Boolean hasHeader = (Boolean) context.get("hasHeader")
        Boolean allowDuplicateCompareIds = (Boolean) context.get("allowDuplicateCompareIds")
        // Security (HIGH gaps 3,5, defense-in-depth 2026-06-30): IGNORE any caller-supplied sparkMaster.
        // Resolve server-side only (resource property, else local[*]) so a future allow-remote exposure
        // of this service cannot redirect the tenant's Spark job to an attacker-controlled cluster.
        String sparkMaster = ReconciliationServices.normalize(ec.resource.properties["spark.master"]) ?: "local[*]"
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
        // FILE_1 is required in EVERY mode, so it is still checked here — before the scope is loaded,
        // which is the cheapest place to fail. FILE_2 cannot be checked yet: whether it is required at
        // all depends on the scope's scopeMode, which is not known until the scope row is read. That
        // check moved to the per-active-side loop below (DAR-BE-049).
        if (!sideInputBySide.FILE_1.fileLocation) throw new IllegalArgumentException("file1Location is required")

        // RuleSetCompareScope has no companyUserGroupId — gate via parent RuleSet (directly-owned).
        // findTenantScopedChildren gates the RuleSet and returns a pre-scoped finder for its compare scopes.
        def compareScopeBaseFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, DarpanEntityConstants.RULE_SET_COMPARE_SCOPE,
                "darpan.rule.RuleSet", "ruleSetId", ruleSetId, "ruleSetId")
        if (compareScopeBaseFinder == null) {
            throw new IllegalArgumentException("RuleSet ${ruleSetId} was not found or is not accessible in your active tenant")
        }
        def compareScope = compareScopeBaseFinder.condition("compareScopeId", compareScopeId).useCache(false).one()
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
        List<String> activeSides = resolveActiveSides(
                ReconciliationServices.normalize(compareScope.scopeMode), sourceBySide.keySet(), compareScopeLabel)
        boolean singleSided = activeSides.size() == 1

        // file2Location cannot be declared required on the service any more (an EVALUATE scope has no
        // second side and a declarative requirement cannot be conditional), so it is enforced here,
        // per ACTIVE side, where the count is finally known.
        activeSides.each { String fileSide ->
            if (!((Map<String, Object>) sideInputBySide[fileSide])?.get("fileLocation")) {
                throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' needs a " +
                        "${SIDE_PREFIX_BY_SIDE[fileSide]}Location for its ${fileSide} source")
            }
        }

        Map<String, Object> sideConfigBySide = activeSides.collectEntries { String fileSide ->
            Map<String, Object> sideInput = (Map<String, Object>) sideInputBySide[fileSide]
            def source = sourceBySide[fileSide]
            [(fileSide): [
                    fileSide            : ReconciliationServices.normalize(source.fileSide)?.toUpperCase(),
                    systemEnumId        : ReconciliationServices.normalize(source.systemEnumId),
                    fileTypeEnumId      : sideInput.fileTypeEnumId ?: ReconciliationServices.normalize(source.fileTypeEnumId),
                    schemaFileName      : sideInput.schemaFileName ?: ReconciliationServices.normalize(source.schemaFileName),
                    recordRootExpression: ReconciliationServices.normalize(source.recordRootExpression),
                    primaryIdExpression : ReconciliationServices.normalize(source.primaryIdExpression),
                    idValueNormalizer   : ReconciliationServices.normalize(source.idValueNormalizer),
                    keyFields           : source.findRelated("keyFields", null, ["sequenceNum"], false, false) ?: []
            ]]
        }
        List<String> fileTypeEnumIds = sideConfigBySide.values()
                .collect { Map<String, Object> sideConfig -> ReconciliationServices.normalize(sideConfig.fileTypeEnumId) }
                .findAll { String enumId -> enumId }
                .unique()
        Map<String, Object> fileTypeEnumById = fileTypeEnumIds ?
                TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                        "framework reference data: enumeration batch lookup for file type labels")
                        .condition("enumId", "in", fileTypeEnumIds)
                        .useCache(true)
                        .list()
                        .collectEntries { enumValue -> [(ReconciliationServices.normalize(enumValue.enumId)): enumValue] } :
                [:]
        Map<String, Object> sidePlanBySide = activeSides.collectEntries { String fileSide ->
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
                    idSpecs     : buildCompareSourceIdSpecs(compareScopeLabel, fileSide, fileType, sideConfig, processingWarnings),
                    label       : sideInput.fileLabel ?: ReconciliationServices.resolveEnumLabel(ec, (String) sideConfig.systemEnumId, SIDE_FALLBACK_LABEL_BY_SIDE[fileSide])
            ]]
        }
        Map<String, Object> file1Plan = (Map<String, Object>) sidePlanBySide.FILE_1
        // Null on an EVALUATE scope. Every read of it below is guarded rather than branched, so the
        // COMPARE path executes exactly the statements it did before this change.
        Map<String, Object> file2Plan = (Map<String, Object>) sidePlanBySide.FILE_2
        Map<String, Object> file1Config = (Map<String, Object>) file1Plan.config
        Map<String, Object> file2Config = (Map<String, Object>) file2Plan?.config
        List<Map<String, Object>> file1IdSpecs = (List<Map<String, Object>>) file1Plan.idSpecs
        List<Map<String, Object>> file2IdSpecs = (List<Map<String, Object>>) file2Plan?.idSpecs
        // Defense in depth: the facade-level create#RuleSetRun/save#RuleSetRun guard already rejects
        // mismatched cross-side composite key-field counts at request time, but this adapter is the
        // single choke point every extraction path (including ones that predate or bypass that facade
        // guard) funnels through before ingest — re-check here so a stored scope with skewed counts
        // fails loudly instead of silently mis-composing compare_id joins.
        // Single-sided by definition has no cross-side key count to match; the guard below would
        // otherwise compare the one side against null and report skew that does not exist.
        if (!singleSided) assertMatchingIdSpecCounts(file1IdSpecs, file2IdSpecs)

        logger.info("Preparing compare scope extraction: ruleSet={} compareScope={} objectType={} mode={} file1Type={} file2Type={}",
                ruleSetId, compareScopeId, compareScope.objectType,
                singleSided ? SCOPE_MODE_EVALUATE : SCOPE_MODE_COMPARE, file1Plan.fileType, file2Plan?.fileType)

        SparkSession spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .config("spark.sql.adaptive.enabled", "true")
                .config("spark.ui.enabled", "false") // embedded batch use; Spark UI needs legacy javax.servlet, absent under Moqui 4 jakarta
                .getOrCreate()
        // Audit 2026-06-11 #18: getOrCreate returns a JVM singleton, so per-call builder .config() is
        // ignored after the first session. Set the shuffle-partition count at runtime (it is a runtime
        // SQL conf) so the embedded local[*] batches don't pay the 200-partition default overhead on
        // the small datasets typical here. Overridable via the system property.
        spark.conf().set("spark.sql.shuffle.partitions",
                (System.getProperty("darpan.reconciliation.spark.shufflePartitions") ?: "16"))

        Map<String, Object> ingestBySide = activeSides.collectEntries { String fileSide ->
            Map<String, Object> plan = (Map<String, Object>) sidePlanBySide[fileSide]
            Map<String, Object> config = (Map<String, Object>) plan.config
            [(fileSide): ReconciliationServices.ingestFile(
                    ec, spark, (String) plan.fileLocation, (String) plan.fileType, (List) plan.idSpecs,
                    hasHeader != null ? hasHeader : true, (String) plan.label, validationErrors,
                    (String) config.schemaFileName)]
        }
        Map<String, Object> preparedIngestBySide = activeSides.collectEntries { String fileSide ->
            Map<String, Object> plan = (Map<String, Object>) sidePlanBySide[fileSide]
            Map<String, Object> ingest = (Map<String, Object>) ingestBySide[fileSide]
            if (singleSided) {
                // EVALUATE NEVER JOINS ON compare_id — there is no other side to join to, so the id is
                // a row identifier and uniqueness is not a correctness property here. Both existing
                // branches would be wrong: validate would throw on an exception report that
                // legitimately has two failing rows for one order, and collapse would DROP one of them
                // (the [[DAR-BE-046]] behaviour) and under-report the very findings this scope exists
                // to surface. So duplicates pass through untouched.
                [(fileSide): ingest]
            } else if (allowDuplicateCompareIds == Boolean.TRUE) {
                [(fileSide): collapseDuplicateCompareIdsForBaseDiffOnly(ingest, compareScopeLabel, fileSide, (String) plan.label, processingWarnings)]
            } else {
                ReconciliationServices.validateUniqueCompareIds((Dataset) ingest.dataDf, compareScopeLabel, fileSide, (String) plan.label)
                [(fileSide): ingest]
            }
        }
        Map<String, Object> ingest1 = (Map<String, Object>) preparedIngestBySide.FILE_1
        Map<String, Object> ingest2 = (Map<String, Object>) preparedIngestBySide.FILE_2

        // Audit 2026-06-11 #16: the legacy reconcileUnifiedFiles path persists its id frames, but the
        // RuleSet path returned un-cached Datasets that the XML orchestration then re-reads and
        // re-parses across many independent Spark actions (two anti-joins, two missing-diff joins, the
        // matched-id join, the matched-pair join, plus counts). Persist the four ingested frames to
        // disk once so those actions reuse them. They are returned in `persistedSources` so the
        // outermost owner (reconcileGenericFiles) can unpersist them in a finally — no block leak.
        Dataset file1IdDf = ((Dataset) ingest1.idDf)?.persist(StorageLevel.DISK_ONLY())
        Dataset file2IdDf = ((Dataset) ingest2?.idDf)?.persist(StorageLevel.DISK_ONLY())
        Dataset file1DataDf = ((Dataset) ingest1.dataDf)?.persist(StorageLevel.DISK_ONLY())
        Dataset file2DataDf = ((Dataset) ingest2?.dataDf)?.persist(StorageLevel.DISK_ONLY())

        return [
                ruleSetId        : ruleSetId,
                compareScopeId   : compareScopeId,
                compareScopeDescription: compareScopeLabel,
                objectType       : ReconciliationServices.normalize(compareScope.objectType),
                scopeMode        : singleSided ? SCOPE_MODE_EVALUATE : SCOPE_MODE_COMPARE,
                file1Type        : file1Plan.fileType,
                file2Type        : file2Plan?.fileType,
                file1SystemEnumId: file1Config.systemEnumId,
                file2SystemEnumId: file2Config?.systemEnumId,
                file1SchemaFileName: file1Config.schemaFileName,
                file2SchemaFileName: file2Config?.schemaFileName,
                file1IdExpression: file1IdSpecs.collect { it.idExpr }.join(' + '),
                file2IdExpression: file2IdSpecs ? file2IdSpecs.collect { it.idExpr }.join(' + ') : null,
                file1IdNormalizer: file1IdSpecs.collect { it.idNormalizer }.findAll { it }.join(', ') ?: null,
                file2IdNormalizer: file2IdSpecs ? (file2IdSpecs.collect { it.idNormalizer }.findAll { it }.join(', ') ?: null) : null,
                file1Label       : file1Plan.label,
                file2Label       : file2Plan?.label,
                file1IdDf        : file1IdDf,
                file2IdDf        : file2IdDf,
                file1DataDf      : file1DataDf,
                file2DataDf      : file2DataDf,
                persistedSources : [file1IdDf, file2IdDf, file1DataDf, file2DataDf].findAll { it != null },
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

    static List<Map<String, Object>> buildCompareSourceIdSpecsForTest(String compareScopeLabel, String fileSide, String fileType,
                                                                        Map<String, Object> sourceConfig, List<String> processingWarnings) {
        return buildCompareSourceIdSpecs(compareScopeLabel, fileSide, fileType, sourceConfig, processingWarnings)
    }

    static void assertMatchingIdSpecCountsForTest(List file1IdSpecs, List file2IdSpecs) {
        assertMatchingIdSpecCounts(file1IdSpecs, file2IdSpecs)
    }

    // Defense in depth: mirrors the facade-level cross-side count guard (create#RuleSetRun /
    // save#RuleSetRun). A composed compare_id with N segments on one side can never join an
    // M-segment side, so every extraction path must fail loudly here rather than let Spark
    // silently mis-join or mis-compose the compare_id columns.
    private static void assertMatchingIdSpecCounts(List file1IdSpecs, List file2IdSpecs) {
        if (file1IdSpecs.size() != file2IdSpecs.size()) {
            throw new IllegalArgumentException("Compare scope sides must use the same number of primary-key fields; file1 has ${file1IdSpecs.size()}, file2 has ${file2IdSpecs.size()}")
        }
    }

    private static List<Map<String, Object>> buildCompareSourceIdSpecs(String compareScopeLabel, String fileSide, String fileType,
                                                                Map<String, Object> sourceConfig, List<String> processingWarnings) {
        List<Map> keyFields = ((List) sourceConfig.keyFields ?: []) as List<Map>
        if (!keyFields) {
            return [buildLegacyCompareSourceIdSpec(compareScopeLabel, fileSide, fileType, sourceConfig, processingWarnings)]
        }

        if (keyFields.size() > MAX_COMPOSITE_KEY_FIELDS) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} defines ${keyFields.size()} key fields; the maximum is ${MAX_COMPOSITE_KEY_FIELDS}")
        }

        String recordRootExpression = (String) sourceConfig.recordRootExpression
        return keyFields
                .sort { (it.sequenceNum as Integer) }
                .collect { Map keyField ->
                    buildCompositeKeyFieldIdSpec(compareScopeLabel, fileSide, fileType,
                            ReconciliationServices.normalize(keyField.fieldExpression), recordRootExpression, processingWarnings)
                }
    }

    private static Map<String, Object> buildLegacyCompareSourceIdSpec(String compareScopeLabel, String fileSide, String fileType,
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

    private static Map<String, Object> buildCompositeKeyFieldIdSpec(String compareScopeLabel, String fileSide, String fileType,
                                                                String rawExpression, String recordRootExpression,
                                                                List<String> processingWarnings) {
        if (!rawExpression) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} has a composite key field with an empty expression")
        }

        Map<String, Object> split = ReconciliationServices.splitIdExpression(rawExpression)
        String normalizer = ReconciliationServices.resolveIdNormalizer((String) split.normalizer)
        String baseExpression = ReconciliationServices.normalize(split.idExpr)
        if ("JSON".equals(fileType)) {
            baseExpression = combineJsonRootAndPrimaryExpression(recordRootExpression, baseExpression)
        } else if ("CSV".equals(fileType) && ReconciliationServices.normalize(recordRootExpression)) {
            processingWarnings.add("Compare scope '${compareScopeLabel}' ${fileSide} ignores recordRootExpression for CSV input")
        }

        if (!baseExpression) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' ${fileSide} resolved an empty composite key field expression")
        }

        String expressionWithNormalizer = normalizer ? "${baseExpression}|${normalizer}" : baseExpression
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
