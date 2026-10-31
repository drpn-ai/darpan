package darpan.facade.reconciliation

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ConclusionLookupSupport
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.conclusion.RunConclusionSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-044. The CONCLUDE stage, as ONE seam both orchestrators call (runSavedRunDiff and
 * AutomationExecutionSupport) — RunPathParityGateTest holds them to it, because a pass wired into one
 * path only is how the missing-diff pass spent a year interactive-only.
 *
 * Everything it needs is read by compareScopeId (rules, key fields, normalizers) or from the result
 * document itself (the side labels), so the two orchestrators pass the same small set of values even
 * though one holds saved-run Maps and the other automation rows.
 *
 * A scope with no conclusion rules opens no step and leaves the document untouched. Any failure ends
 * the step FAILED and returns; a conclusion is never allowed to fail a run whose compare succeeded.
 */
class RunConclusionStep {

    static Map runIfConfigured(Map args) {
        def ec = args?.get("ec")
        String compareScopeId = normalize(args?.get("compareScopeId"))
        File diffFile = (File) args?.get("diffFile")
        if (!compareScopeId || diffFile == null || !diffFile.isFile()) return [ran: false]

        List ruleRows
        try {
            ruleRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetConclusionRule")
                    .condition("compareScopeId", compareScopeId).orderBy("sequenceNum").useCache(false).list() ?: []
        } catch (Throwable t) {
            return [ran: false, warning: "Conclusion rules could not be read: ${t.message}".toString()]
        }
        if (!ruleRows) return [ran: false]   // a brain scope carries at least its BRAIN fix rows

        String runResultId = normalize(args.get("runResultId"))
        Map stepCtx = (args.get("stepCtx") ?: [:]) as Map
        def step = runResultId ? RunObservability.beginStep(ec, runResultId, stepCtx, RunObservability.STAGE_CONCLUDE) : null
        try {
            List conditionRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetConclusionCondition")
                    .condition("compareScopeId", compareScopeId).orderBy("sequenceNum,conditionSeq").useCache(false).list() ?: []
            Map<String, String> labels = [:]
            ruleRows.each { Object row ->
                String enumId = normalize(row.conclusionEnumId)
                if (enumId && !labels.containsKey(enumId)) {
                    def enumValue = FacadeSupport.findEnum(ec, enumId)
                    labels.put(enumId, normalize(enumValue?.description) ?: enumId)
                }
            }
            List<Map> rules = buildRules(ruleRows.collect { toMap(it) }, conditionRows.collect { toMap(it) }, labels)

            Map documentLabels = documentLabels(diffFile)
            boolean singleSided = documentLabels.file2Label == null || args.get("file2Result") == null
            // one() on the full PK would drop a findTenantScoped pin, so the owner is checked on the row, against
            // the same active tenant the rule reads above are scoped to.
            def scope = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.rule.RuleSetCompareScope", "compareScopeId",
                    compareScopeId, TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                    "RunConclusionStep scope read; owner checked on the row")
            List<String> lookupWarnings = []
            List<Map> lookups = []
            try {
                lookups = ConclusionLookupSupport.parse(scope?.conclusionLookupsJson)
            } catch (Exception e) {
                lookupWarnings.add("Lookups not read: ${e.message}".toString())
            }
            String tenantId = normalize(scope?.companyUserGroupId)
            String kind = brainKind(scope?.conclusionBrain)
            Map brain = kind ? [kind: kind, tenantId: tenantId,
                    labelFor: { String code -> labels.get(code) ?: normalize(FacadeSupport.findEnum(ec, code)?.description) ?: code },
                    fetchQuestion: { String question, List<String> ids ->
                        Map lookup = questionLookup(question, lookups,
                                darpan.common.ValueSupport.readOptionalString(args.get("file1Source"), "sourceConfigId"))
                        if (lookup == null) throw new IllegalStateException("the scope has no HotWax source to ask ${question} of")
                        int before = lookupWarnings.size()
                        Map found = fetchLookup(ec, lookup, ids, tenantId, lookupWarnings)
                        // A failed lookup must not be remembered as "nothing found".
                        if (lookupWarnings.size() > before) throw new IllegalStateException(lookupWarnings.last())
                        return found
                    }] : null
            Map result = RunConclusionSupport.concludeRun([
                    brain      : brain,
                    lookups    : lookups,
                    fetchLookup: { Map lookup, List<String> ids ->
                        fetchLookup(ec, lookup, ids, normalize(scope?.companyUserGroupId), lookupWarnings)
                    },
                    diffFile   : diffFile,
                    file1Label : documentLabels.file1Label,
                    file2Label : documentLabels.file2Label,
                    singleSided: singleSided,
                    rules      : rules,
                    file1      : sideSpec(ec, compareScopeId, "FILE_1", args.get("file1Source"), (Map) args.get("file1Result")),
                    file2      : singleSided ? null : sideSpec(ec, compareScopeId, "FILE_2", args.get("file2Source"), (Map) args.get("file2Result")),
            ])
            int concluded = ((Map) (result.counts ?: [:])).values().sum(0) as int
            RunObservability.endStep(ec, step, RunObservability.STATUS_SUCCESS,
                    [recordCount: concluded,
                     metricsJson: JsonOutput.toJson([counts: result.counts, unknownSides: result.unknownSides,
                                                     lookupWarnings: lookupWarnings ?: null, brain: result.brain])])
            return result + (lookupWarnings ? [warning: lookupWarnings.join("; ")] : [:])
        } catch (Throwable t) {
            if (ec?.message?.hasError()) ec.message.clearErrors()
            RunObservability.endStep(ec, step, RunObservability.STATUS_FAILED,
                    [errorMessage: "Conclusions not drawn: ${normalize(t.message) ?: t.class.simpleName}".toString()])
            return [ran: false, warning: t.message]
        }
    }

    /**
     * DAR-BE-118. One lookup call: the lookup's connector reads its config for these ids only. A connector whose extract
     * does not declare lookupKeys is never called (it would read a whole window). A failed lookup is a warning and
     * attaches nothing, so those findings keep the conclusion they reach without it.
     */
    static Map<String, Map> fetchLookup(def ec, Map lookup, List<String> ids, String tenantId, List<String> warnings) {
        try {
            Map connector = SourceSystemConnectorSupport.resolveBySystemEnumId(ec, (String) lookup.systemEnumId)
            String service = normalize(connector?.extractServiceName)
            String configParameter = normalize(connector?.configParameterName)
            if (!service || !configParameter || !ec.service.getServiceDefinition(service)?.inParameterNameList?.contains("lookupKeys")) {
                throw new IllegalStateException("${lookup.systemEnumId} cannot be asked by id")
            }
            // DAR-BE-174 security review: only keepRecordFields reaches the extract; a tenant saves lookups now.
            Map options = ConclusionLookupSupport.safeParameters(lookup)
            // A read by key has no window, but an extract's contract may still require one: send all time, under the
            // connector's own names; the keys bound the read (lookupKeys).
            Map window = [(normalize(connector.dateFromParameterName) ?: "windowStart"): new java.sql.Timestamp(0L),
                          (normalize(connector.dateToParameterName) ?: "windowEnd")  : ec.user.nowTimestamp]
            Map out = ec.service.sync().name(service).parameters(options + window + [(configParameter): lookup.sourceConfigId,
                    lookupKeys: ids, lookupKeyField: lookup.keyField, companyUserGroupId: tenantId,
                    fileName: "lookup-${lookup.name}-${System.currentTimeMillis()}.json".toString()]).call()
            List errors = (List) (out?.errors ?: (ec.message.hasError() ? ec.message.errors : []))
            if (errors) throw new IllegalStateException(errors.join("; "))
            File file = RunVerificationSupport.resolveLocationFile(ec, normalize(out?.fileLocation))
            // An answer with no file is a failed lookup, never "none found" (a refused call answers nothing at all).
            if (!file?.isFile() && out?.dataAvailable != false) {
                throw new IllegalStateException("${service} answered no file${ec.message.validationErrors ? ': ' + ec.message.validationErrors.join('; ') : ''}")
            }
            // DAR-BE-174: a `many` lookup keeps every record under a key (an order's items), others one.
            Map<String, Object> found = [:]
            if (file?.isFile()) JsonRecordStream.forEachRecord(file, "records") { Map r -> ConclusionLookupSupport.collect(found, lookup, r) }
            ec.logger.info("conclusion lookup ${lookup.name}: asked ${ids.size()} ids of ${lookup.systemEnumId}, found ${found.size()}")
            return found
        } catch (Throwable t) {
            if (ec?.message?.hasError()) ec.message.clearErrors()
            warnings.add("Lookup ${lookup.name} not answered: ${normalize(t.message) ?: t.class.simpleName}".toString())
            return [:]
        }
    }

    /** DAR-BE-151: the lookup a brain question is answered through: the scope's own of that name, else its default. */
    static Map questionLookup(String question, List<Map> lookups, String hotwaxSourceConfigId) {
        return (lookups ?: []).find { it.name == darpan.conclusion.brain.BrainQuestions.lookupNameFor(question) } ?:
                darpan.conclusion.brain.BrainQuestions.defaultLookup(question, hotwaxSourceConfigId)
    }

    /** DAR-BE-151: RuleSetCompareScope.conclusionBrain to the brain's finding kind; anything else is no brain. */
    static String brainKind(Object conclusionBrain) {
        switch (normalize(conclusionBrain)) {
            case "ORDER_LINE": return "orderLine"
            case "RETURN": return "return"
            default: return null
        }
    }

    /** Entity rows to the ConclusionTreeEngine node shape, in sequence order. Pure, so it is tested alone. */
    static List<Map> buildRules(List<Map> ruleRows, List<Map> conditionRows, Map<String, String> labelsByEnum) {
        Map<Object, List<Map>> conditionsBySeq = [:]
        (conditionRows ?: []).sort { ((it.conditionSeq ?: 0) as int) }.each { Map row ->
            Object key = (row.sequenceNum as int)
            conditionsBySeq.computeIfAbsent(key) { [] }.add([
                    subject        : normalize(row.subject),
                    presence       : normalize(row.presence) ?: "ANY",
                    keyScope       : normalize(row.keyScope) ?: "FULL",
                    quantifier     : normalize(row.quantifier) ?: "ALL",
                    fieldExpression: normalize(row.fieldExpression),
                    operator       : normalize(row.operator),
                    conditionValues: (normalize(row.conditionValues) ?: "").split(",").collect { it.trim() }.findAll { it },
                    checkLabel     : normalize(row.checkLabel),
            ])
        }
        // DAR-BE-151: a BRAIN row carries a brain cause's fix text for the audit only; the tree never compiles it.
        return (ruleRows ?: []).findAll { normalize(it.engine) != "BRAIN" }.sort { ((it.sequenceNum ?: 0) as int) }.collect { Map row ->
            String code = normalize(row.conclusionEnumId)
            return [sequenceNum      : row.sequenceNum as int,
                    parentSequenceNum: normalize(row.parentSequenceNum) ? (row.parentSequenceNum as int) : null,
                    conclusionEnumId : code,
                    label            : labelsByEnum?.get(code) ?: code,
                    appliesToBucket  : normalize(row.appliesToBucket),
                    questionText     : normalize(row.questionText),
                    suggestedFilter  : parseJson(row.suggestedFilterJson),
                    // DAR-BE-157: HIDE drops the rows this rule concludes from the result.
                    outcome          : row.containsKey("outcome") ? normalize(row.outcome) : null,
                    conditions       : conditionsBySeq.get(row.sequenceNum as int) ?: []]
        }
    }

    /** The side labels as the result document itself records them — what the rows' presentIn/missingIn use. */
    static Map documentLabels(File diffFile) {
        JsonSlurper slurper = new JsonSlurper()
        String metadataPrefix = "\"metadata\":"
        Map labels = [file1Label: null, file2Label: null]
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            while ((line = lines.readLine()) != null) {
                if (line.startsWith(metadataPrefix)) {
                    Object metadata = DiffDocumentStreamSupport.headerFragment(slurper, line, metadataPrefix)
                    if (metadata instanceof Map) {
                        labels.file1Label = normalize(((Map) metadata).file1Label)
                        labels.file2Label = normalize(((Map) metadata).file2Label)
                    }
                    break
                }
                if (line.startsWith(DiffDocumentStreamSupport.DIFFERENCES_HEADER)) break
            }
        }
        return labels
    }

    private static Map sideSpec(def ec, String compareScopeId, String fileSide, Object source, Map fileResult) {
        Map key = keySpecFor(ec, compareScopeId, fileSide)
        Map<String, Object> connector = RunVerificationSupport.resolveConnector(ec, source)
        return [extractFile : RunVerificationSupport.resolveLocationFile(ec, normalize(fileResult?.fileLocation)),
                keyFields   : key.keyFields,
                idNormalizer: key.idNormalizer,
                keyReliable : key.keyReliable,
                evidence    : parseJson(connector?.evidenceFieldsJson) ?: [:]]
    }

    /**
     * How the compare keyed a side, per RuleSetCompareScopeAdapter: composite key fields each carry
     * their own inline normalizer and ignore the source's idValueNormalizer; a legacy primaryIdExpression
     * takes idValueNormalizer ?: its inline one. keyReliable is false when the key cannot be rebuilt from
     * an extract's top-level `records` fields (a nested path, or a record root other than records), so
     * the side reads UNKNOWN rather than being keyed differently and reported "Not found".
     */
    /** keySpec of one side of a compare scope, read from its RuleSetCompareSource and key field rows. */
    static Map keySpecFor(def ec, String compareScopeId, String fileSide) {
        List keyFieldRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSourceKeyField")
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                .orderBy("sequenceNum").useCache(false).list() ?: []
        // list(), not one(): one() on the full PK would drop the tenant pin.
        def sourceRow = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide).useCache(false).list()?.find()
        return keySpec(keyFieldRows.collect { normalize(it.fieldExpression) }, normalize(sourceRow?.primaryIdExpression),
                normalize(sourceRow?.idValueNormalizer), normalize(sourceRow?.recordRootExpression))
    }

    static Map keySpec(List<String> keyFieldExpressions, String primaryIdExpression, String idValueNormalizer,
                       String recordRootExpression) {
        List<String> composite = (keyFieldExpressions ?: []).collect { normalize(it) }.findAll { it }
        List<String> keyFields = composite ?: (normalize(primaryIdExpression) ? [normalize(primaryIdExpression)] : [])
        boolean reliable = !keyFields.isEmpty() && recordsRoot(recordRootExpression) &&
                keyFields.every { ConclusionKeySupport.plainField(it) != null }
        return [keyFields  : keyFields,
                idNormalizer: composite ? null : normalize(idValueNormalizer),
                keyReliable: reliable]
    }

    private static boolean recordsRoot(String recordRootExpression) {
        String root = normalize(recordRootExpression)
        return !root || root.replaceFirst(/^\$\.?/, "").replaceFirst(/\[\*\]$/, "") == "records"
    }

    private static Map toMap(Object row) {
        if (row instanceof Map) return (Map) row
        return row?.getMap() as Map
    }

    private static Map parseJson(Object raw) {
        String text = normalize(raw)
        if (!text) return null
        try {
            Object parsed = new JsonSlurper().parseText(text)
            return parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }
}
