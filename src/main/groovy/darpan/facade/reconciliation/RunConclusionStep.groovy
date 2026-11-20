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
            Map<String, String> labels = labelsOf(ec, ruleRows)
            List<Map> rules = scopeRules(ec, compareScopeId, ruleRows)

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
            // DAR-BE-318 (ruling P2-24): a replay hands its own fetcher, which answers only from captured lookups and never
            // calls a system. Every other caller passes none and gets today's live fetchLookup.
            // DAR-BE-318 plan 3a: a live pass keeps what it asked in <run>/lookups; a replay (its own fetcher) writes nothing.
            File lookupsDir = capturesDirFor(args)
            Closure lookupFetcher = (Closure) args.get("lookupFetcher") ?: { def e, Map l, List<String> i, String t, List<String> w ->
                fetchLookup(e, l, i, t, w, lookupsDir)
            }
            String kind = brainKind(scope?.conclusionBrain)
            // Ruling P2-27: with its own fetcher (a replay) the brain neither reads nor writes the answers live runs share.
            Map brain = kind ? [kind: kind, tenantId: tenantId, shareAnswers: sharesBrainAnswers(args),
                    labelFor: { String code -> labels.get(code) ?: normalize(FacadeSupport.findEnum(ec, code)?.description) ?: code },
                    // Answers the shared store serves are never fetched, so they are kept here (live passes only; a replay shares nothing).
                    captureServed: lookupsDir == null ? null : { String question, Map<String, Map> served ->
                        Map lookup = questionLookup(question, lookups, hotwaxSourceConfigId(args.get("file1Source"),
                                RunVerificationSupport.resolveConnector(ec, args.get("file1Source"))))
                        if (lookup != null) captureServed(lookupsDir, lookup, served, lookupWarnings)
                    },
                    fetchQuestion: { String question, List<String> ids ->
                        Map lookup = questionLookup(question, lookups, hotwaxSourceConfigId(args.get("file1Source"),
                                RunVerificationSupport.resolveConnector(ec, args.get("file1Source"))))
                        if (lookup == null) throw new IllegalStateException("the scope has no lookup to ask ${question} of")
                        int before = lookupWarnings.size()
                        Map found = (Map) lookupFetcher.call(ec, lookup, ids, tenantId, lookupWarnings)
                        // A failed lookup must not be remembered as "nothing found".
                        // A capture that could not be written is a note, not a failed answer.
                        List<String> failures = lookupWarnings.subList(before, lookupWarnings.size()).findAll { !it.contains(NOT_CAPTURED) }
                        if (failures) throw new IllegalStateException(failures.last())
                        return found
                    }] : null
            Map result = RunConclusionSupport.concludeRun([
                    brain      : brain,
                    // DAR-BE-198 (b): the run says its Unexplained rows are expected (gorjana R6).
                    unexplainedExpected: normalize(scope?.unexplainedOutcome) == RunConclusionSupport.OUTCOME_EXPECTED,
                    lookups    : lookups,
                    fetchLookup: { Map lookup, List<String> ids ->
                        lookupFetcher.call(ec, lookup, ids, normalize(scope?.companyUserGroupId), lookupWarnings)
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
                    [recordCount: concluded, metricsJson: JsonOutput.toJson(stepMetrics(result, lookupWarnings))])
            return result + (lookupWarnings ? [warning: lookupWarnings.join("; ")] : [:])
        } catch (Throwable t) {
            if (ec?.message?.hasError()) ec.message.clearErrors()
            RunObservability.endStep(ec, step, RunObservability.STATUS_FAILED,
                    [errorMessage: "Conclusions not drawn: ${normalize(t.message) ?: t.class.simpleName}".toString()])
            return [ran: false, warning: t.message]
        }
    }

    /** Marks a warning that only says a capture was not kept; the answer it belongs to stands. */
    static final String NOT_CAPTURED = " not captured: "
    static final String LOOKUPS_FOLDER = "lookups"
    static final java.util.regex.Pattern CAPTURABLE_NAME = ~/[A-Za-z0-9_]+/

    /**
     * DAR-BE-318 plan 3a: where this pass keeps the lookups it asks, <run>/lookups (args.lookupsDir overrides). Null for a
     * replay (ruling P2-27: a call that hands its own lookupFetcher writes no captures).
     */
    static File capturesDirFor(Map args) {
        if (args?.get("lookupFetcher") != null) return null
        File given = (File) args?.get("lookupsDir")
        if (given != null) return given
        File runDir = ((File) args?.get("diffFile"))?.parentFile
        return runDir == null ? null : new File(runDir, LOOKUPS_FOLDER)
    }

    /**
     * The form RunExtractSupport.buildExtractParams hands extractors: a data-manager location string (every extractor resolves
     * it with DataManagerSupport.resolveDirectoryFile). A folder under the data manager is named that way; any other folder
     * (a test's temp dir) falls back to its absolute path.
     */
    static String outputLocationFor(def ec, File dir) {
        try {
            String base = darpan.facade.common.DataManagerSupport.resolveDataManagerLocation(ec)
            File root = darpan.facade.common.DataManagerSupport.resolveDirectoryFile(ec, base, false)
            if (root != null) {
                java.nio.file.Path rootPath = root.canonicalFile.toPath()
                java.nio.file.Path dirPath = dir.canonicalFile.toPath()
                if (dirPath.startsWith(rootPath)) {
                    return darpan.facade.common.DataManagerSupport.childLocation(base, rootPath.relativize(dirPath).toString().replace(File.separator, "/"))
                }
            }
        } catch (Throwable ignored) {
        }
        return dir.absolutePath
    }

    /**
     * Keeps answers the brain store served as <lookupsDir>/lookup-<name>-<millis>.json ({metadata, records}), the file
     * JudgeReplaySupport.capturedFetcher reads. Nothing found for an id adds no record. A write that fails, or a name the
     * capture pattern cannot hold, is a warning and no file, never a failure.
     */
    static void captureServed(File lookupsDir, Map lookup, Map<String, Map> served, List<String> warnings) {
        String name = lookup?.name as String
        if (!(name ==~ CAPTURABLE_NAME)) {
            warnings.add("Lookup ${name}${NOT_CAPTURED}its name is outside [A-Za-z0-9_]+".toString())
            return
        }
        List records = []
        (served ?: [:]).values().each { Object answer ->
            if (answer instanceof List) records.addAll((List) answer.findAll { it != null })
            else if (answer != null) records.add(answer)
        }
        if (!records) return
        try {
            lookupsDir.mkdirs()
            long millis = System.currentTimeMillis()
            File target = new File(lookupsDir, "lookup-${name}-${millis}.json")
            // Never the same name twice: the replay reads the newest by its number, and an older file must not be overwritten.
            while (target.exists()) target = new File(lookupsDir, "lookup-${name}-${++millis}.json")
            File partial = new File(lookupsDir, "${target.name}.partial")
            partial.setText(JsonOutput.toJson([metadata: [lookup: name, systemEnumId: lookup.systemEnumId, servedFromStore: true,
                                                          recordCount: records.size()], records: records]), "UTF-8")
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (Throwable t) {
            warnings.add("Lookup ${name}${NOT_CAPTURED}${normalize(t.message) ?: t.class.simpleName}".toString())
        }
    }

    /**
     * DAR-BE-318, ruling P2-27: whether this pass's brain reads and writes BrainAnswerStore, the answers every check in the JVM
     * shares. A caller that hands its own lookupFetcher (a replay, answering from captured lookups only) must neither take a
     * live run's answers nor leave its own for one; every other caller shares, as before.
     */
    static boolean sharesBrainAnswers(Map args) {
        return args?.get("lookupFetcher") == null
    }

    /** The conclude step's metadata: counts, unread sides, lookup warnings and the brain's counts with its per-question ledger (DAR-BE-228). */
    static Map stepMetrics(Map result, List<String> lookupWarnings) {
        return [counts: result.counts, unknownSides: result.unknownSides, lookupWarnings: lookupWarnings ?: null, brain: result.brain]
    }

    /**
     * DAR-BE-159. The scope's saved conclusion rows as this step compiles them (BRAIN rows left out), read as the active
     * tenant. ruleRows: the scope's RuleSetConclusionRule rows when already read.
     */
    static List<Map> scopeRules(def ec, String compareScopeId, List ruleRows = null) {
        List rows = ruleRows != null ? ruleRows : (TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetConclusionRule")
                .condition("compareScopeId", compareScopeId).orderBy("sequenceNum").useCache(false).list() ?: [])
        List conditionRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetConclusionCondition")
                .condition("compareScopeId", compareScopeId).orderBy("sequenceNum,conditionSeq").useCache(false).list() ?: []
        return buildRules(rows.collect { toMap(it) }, conditionRows.collect { toMap(it) }, labelsOf(ec, rows))
    }

    /** Each conclusion's label (its enumeration's description), by enum id. */
    static Map<String, String> labelsOf(def ec, List ruleRows) {
        Map<String, String> labels = [:]
        (ruleRows ?: []).each { Object row ->
            String enumId = normalize(row.conclusionEnumId)
            if (enumId && !labels.containsKey(enumId)) {
                def enumValue = FacadeSupport.findEnum(ec, enumId)
                labels.put(enumId, normalize(enumValue?.description) ?: enumId)
            }
        }
        return labels
    }

    /**
     * DAR-BE-118. One lookup call: the lookup's connector reads its config for these ids only. A connector whose extract
     * does not declare lookupKeys is never called (it would read a whole window). A failed lookup is a warning and
     * attaches nothing, so those findings keep the conclusion they reach without it.
     */
    static Map<String, Map> fetchLookup(def ec, Map lookup, List<String> ids, String tenantId, List<String> warnings, File lookupsDir = null) {
        try {
            // DAR-BE-318 plan 3a: with a folder, the extract writes its answer there (lookup-<name>-<millis>.json), so a replay can read it.
            boolean capture = lookupsDir != null
            if (capture && !((lookup.name as String) ==~ CAPTURABLE_NAME)) {
                warnings.add("Lookup ${lookup.name}${NOT_CAPTURED}its name is outside [A-Za-z0-9_]+".toString())
                capture = false
            }
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
            // DAR-BE-205: conclusionLookup tells an extract that declares it (HotWax orders) to answer for every id named,
            // with none of a comparison's built-in exclusions; an audit re-check by key never sends it.
            if (capture) lookupsDir.mkdirs()
            Map out = ec.service.sync().name(service).parameters(options + window + (capture ? [outputLocation: outputLocationFor(ec, lookupsDir)] : [:]) + [(configParameter): lookup.sourceConfigId,
                    lookupKeys: ids, lookupKeyField: lookup.keyField, conclusionLookup: true, companyUserGroupId: tenantId, systemEnumId: connector.systemEnumId,
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
    /**
     * DAR-BE-151: the HotWax connection a brain's default question reads, from the run's FILE_1 source. A scheduled or
     * audit run passes its ReconciliationAutomationSource row, which keeps the id in safeMetadataJson.parameters under the
     * connector's own name (omsRestSourceConfigId) and has no sourceConfigId column; reading only that column left every
     * default question unasked on prod (gorjana R1/R7, 2026-11-06), as it once left verification inert (2026-08-27).
     */
    static String hotwaxSourceConfigId(Object file1Source, Map<String, Object> connector) {
        return RunVerificationSupport.resolveSourceConfigId(file1Source, connector)
    }

    static Map questionLookup(String question, List<Map> lookups, String hotwaxSourceConfigId) {
        return (lookups ?: []).find { it.name == darpan.conclusion.brain.BrainQuestions.lookupNameFor(question) } ?:
                darpan.conclusion.brain.BrainQuestions.defaultLookup(question, hotwaxSourceConfigId)
    }

    /** DAR-BE-151: RuleSetCompareScope.conclusionBrain to the brain's finding kind; anything else is no brain. */
    static String brainKind(Object conclusionBrain) {
        switch (normalize(conclusionBrain)) {
            case "ORDER_LINE": return "orderLine"
            case "RETURN": return "return"
            // DAR-BE-192: an order-grain run (gorjana R7: approved orders against NetSuite sales orders).
            case "ORDER": return "order"
            // DAR-BE-204: a Shopify-paired run (Dolls Kill: every Shopify order is in NetSuite), concluded through its HotWax order.
            case "SHOPIFY_ORDER": return "shopifyOrder"
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

    /** One side as the conclude pass reads it: its extract, how the compare keyed it, and its connector's evidence fields. */
    static Map sideSpec(def ec, String compareScopeId, String fileSide, Object source, Map fileResult) {
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
