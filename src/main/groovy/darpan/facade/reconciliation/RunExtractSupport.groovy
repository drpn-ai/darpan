package darpan.facade.reconciliation

import darpan.conclusion.brain.BrainFactAdapter
import darpan.dataset.KeptCopySupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.idindex.IdIndexRunHook
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.source.KeepPlanSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * The interactive run's EXTRACT step, lifted out of {@code runSavedRunDiff.groovy} so something other
 * than that script can call it (design {@code 2026-08-26-reconciliation-pipeline-unification} step 5).
 *
 * <p><b>Why this had to exist before anything else.</b> That script is a Moqui <i>script</i> service and
 * its pipeline lived in ~25 script-local closures bound to the script's own scope — "none of these are
 * callable from anywhere else" (design §5). That is why the scheduled path reimplemented extraction
 * rather than calling it, why {@code RunPathParityGateTest} can only be a structural gate rather than a
 * differential one, and why a single-sided EVALUATE run ([[DAR-BE-049]]) had no way to stage a source
 * without copying ~140 lines.</p>
 *
 * <p><b>This is step 5a, not step 5.</b> The design's step 5 also has the automation's
 * {@code callConfiguredSourceExtractor} delegate here. It deliberately does NOT yet, because the two
 * implementations are not the same job — see {@code AUTOMATION DIVERGENCES} below. Converging them is a
 * behaviour change on every scheduled run (design §9.5) and wants the same treatment step 3 gave
 * verification: a second caller behind a flag, default off, not a silent swap. This class is the
 * enabler; it changes nothing for anyone.</p>
 *
 * <h3>AUTOMATION DIVERGENCES — read before wiring the scheduled path to this</h3>
 * <pre>
 *                         interactive (this class)          automation (callConfiguredSourceExtractor)
 *  connector lookup       resolve(systemEnumId), then       resolveSourceExtractorMetadata(source,
 *                         resolveByExpectedSourceConfigType   defaults), then resolveByExtractServiceName
 *  date param defaults    windowStart / windowEnd           fromDate / toDate   <-- DIFFERENT DEFAULT
 *  window value type      ISO-8601 instant STRING           raw java.sql.Timestamp
 *  missing service        ec.message error, returns [:]      THROWS IllegalStateException
 *  allowlist              shape check only                  allowedServiceNames + shape + installed
 *  exclusion filters      sideKeep: the compare scope's      loadAutomationSourceFilters(automationId,
 *                         rows for the active tenant           fileSide, tenant) -- a DIFFERENT STORE, by
 *                         (KeepPlanSupport). Spark Keep sets    design (DAR-BE-338: an automation runs on
 *                         them aside at ingest; the read gets   its own copy, so a run edit reaches it only
 *                         them from a data-defined source or    through sync#Automation)
 *                         an extract still taking the filter
 *                         parameter (3b2b-23); an evaluate run
 *                         keeps in Spark too
 *  state window mode      retired (DAR-BE-052): no connector reads by status; both paths refuse an AUT_WIN_STATE row
 *  status parameter       absent                            applyStatusParameter
 *  progress heartbeat     yes (reconciliationRunResultId)    absent
 *  output location        under the run's artifact dir       extractor default, per automationExecutionId
 *  errors                 accumulate on ec.message           THROW joined
 * </pre>
 * Any convergence has to decide each of those explicitly. Adopting this class's defaults wholesale
 * would change which date fields a scheduled extract queries and which filter store it reads — both
 * silent, both production.
 */
class RunExtractSupport {
    private static final Logger logger = LoggerFactory.getLogger(RunExtractSupport)

    /** Interactive artifact naming: FILE_1 -> "file1", anything else -> "file2". */
    static String sideToken(String fileSide) {
        return ReconciliationSavedRunSupport.FILE_SIDE_1 == fileSide ? "file1" : "file2"
    }

    /** Extract windows travel to the getters as ISO-8601 instants, never as a formatted local time. */
    static String formatApiWindow(Timestamp timestamp) {
        return timestamp?.toInstant()?.toString()
    }

    /**
     * Normalizes a staged or extracted file into the per-side result the compare stage consumes.
     * Null-valued entries are dropped so an absent recordCount stays absent rather than becoming null.
     */
    static Map<String, Object> sourceResultForLocation(def ec, Object source, String fileSide,
                                                       String fileNameValue, String location,
                                                       Integer recordCount = null) {
        def locationRef = ec.resource.getLocationReference(location)
        File locationFile = locationRef?.getFile()
        return [
                fileLocation   : locationFile?.getAbsolutePath() ?: location,
                dataManagerPath: DataManagerSupport.relativeDataManagerPath(ec, locationFile),
                fileName       : fileNameValue,
                fileTypeEnumId : normalize(source?.fileTypeEnumId) ?: "DftJson",
                schemaFileName : normalize(source?.schemaFileName),
                recordCount    : recordCount,
                fileSide       : fileSide,
        ].findAll { entry -> entry.value != null } as Map<String, Object>
    }

    /** Writes an uploaded/pasted body into the run's artifact folder and normalizes the result. */
    static Map<String, Object> stageTextInput(def ec, Object source, String fileSide, String inputName,
                                              String inputText, Map<String, Object> artifactContext) {
        String token = sideToken(fileSide)
        String safeName = OutputPathSupport.sanitizeUploadFileName(inputName, token)
        String location = DataManagerSupport.childLocation(
                artifactContext.location as String,
                DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, safeName)
        )
        DataManagerSupport.writeText(ec, location, inputText)
        return sourceResultForLocation(ec, source, fileSide, safeName, location, null)
    }

    /**
     * Registry-driven connector lookup: by system first, then by the source's config type when the
     * system's row does not match it. Mirrors the pre-lift order exactly.
     */
    static Map<String, Object> resolveExtractConnector(def ec, Object source) {
        String sourceConfigType = normalize(source?.sourceConfigType)
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(source?.systemEnumId))
        if (connector == null || normalize(connector.expectedSourceConfigType) != sourceConfigType) {
            connector = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, sourceConfigType)
        }
        return connector
    }

    /**
     * THE PART A COPY GETS WRONG, and therefore the part that is pure and directly unit-tested: which
     * parameter NAMES the connector supplies, which optional parameters appear at all, and that the
     * window arrives as an ISO instant rather than a Timestamp.
     *
     * keepFields and excludeFilters are passed IN rather than resolved here so this stays free of the
     * entity facade — the caller owns those lookups.
     */
    static Map<String, Object> buildExtractParams(Map<String, Object> connector, String fileSide,
                                                  String configId, Map<String, Object> artifactContext,
                                                  Timestamp windowStartDate, Timestamp windowEndDate,
                                                  String fileNameValue, List<String> keepFields,
                                                  List<Map<String, Object>> excludeFilters,
                                                  Map<String, Object> progressContext,
                                                  Object windowTimeZone = null) {
        String token = sideToken(fileSide)
        // A DATE-granular source is sent the calendar day, not an instant (DAR-BE-057). The bounds
        // arriving here are midnight-in-the-window's-zone, so the day is recovered in THAT zone —
        // recovering it anywhere else is the whole bug, and UTC is not a safe default for it.
        boolean dateOnly = connector?.windowDateOnly == true
        Closure<String> windowBound = { Timestamp bound ->
            dateOnly ? ReconciliationApiWindowSupport.calendarDateIn(bound, windowTimeZone) : formatApiWindow(bound)
        }
        Map<String, Object> extractParams = [
                (normalize(connector?.configParameterName) ?: "sourceConfigId"): configId,
                (normalize(connector?.dateFromParameterName) ?: "windowStart") : windowBound(windowStartDate),
                (normalize(connector?.dateToParameterName) ?: "windowEnd")     : windowBound(windowEndDate),
                outputLocation: DataManagerSupport.childLocation(artifactContext?.location as String, "${token}-api"),
                fileName      : DataManagerSupport.runArtifactFileName(artifactContext?.runToken, token, fileNameValue),
                // DAR-BE-176: the endpoint being read, as the automation path always sends it; a data-defined
                // extract (one service, many endpoints) reads its connector row by it.
                systemEnumId  : normalize(connector?.systemEnumId),
        ] as Map<String, Object>
        if (connector?.preserveWindowInstants) extractParams.preserveWindowInstants = true

        // Config over code, mirrors AutomationExecutionSupport.applyWindowFieldParameter: the connector
        // names the record date field its extract window filters on. Blank leaves the parameter unset so
        // the extractor keeps its own default (orderDate). Without this, an interactive run and a
        // scheduled automation of the same saved run would query different date fields the moment
        // windowFieldName is set to anything other than that default (e.g. lastUpdatedTxStamp).
        String windowFieldName = normalize(connector?.windowFieldName)
        if (windowFieldName) extractParams.windowFieldName = windowFieldName

        // Registry-driven record projection: only a connector that declares the parameter receives it,
        // and only when the caller resolved a non-empty field set.
        String keepFieldsParameterName = normalize(connector?.keepFieldsParameterName)
        if (keepFieldsParameterName && keepFields) extractParams[keepFieldsParameterName] = keepFields

        // Registry-driven record exclusion, same shape: connectors that declare no parameter never
        // receive filters.
        String filterParameterName = normalize(connector?.filterParameterName)
        if (filterParameterName && excludeFilters) extractParams[filterParameterName] = excludeFilters

        // Live extract progress: the extractor heartbeats a running record count onto this stage's step
        // so the live view shows the count climbing during a multi-minute paged extract. The count is
        // the progress and needs no denominator; expectedRecordCount is optional and only adds a
        // percent, which only the second side can have. Gating the whole thing on a denominator is what
        // left the first extract reporting nothing at all.
        if (progressContext?.reconciliationRunResultId) {
            extractParams.reconciliationRunResultId = progressContext.reconciliationRunResultId
            extractParams.progressStageCode = progressContext.progressStageCode
            if (progressContext?.expectedRecordCount) {
                extractParams.expectedRecordCount = progressContext.expectedRecordCount
            }
        }
        return extractParams
    }

    /**
     * One side's API extraction, end to end.
     *
     * <p>{@code serviceCaller} is injected rather than dispatched here on purpose: the authz-relaxed
     * dispatch stays in its audited home in the calling script, so lifting this logic neither moves nor
     * adds a bare {@code disableAuthz} site (see {@code DisableAuthzRatchetTest}), and this method is
     * testable with a stub caller instead of a booted service facade.</p>
     */
    static Map<String, Object> extractApiSource(def ec, Object source, String fileSide, String label,
                                                Map<String, Object> artifactContext,
                                                Map<String, Object> apiWindow,
                                                Map<String, Object> progressContext,
                                                Closure serviceCaller,
                                                Map<String, Object> byKeys = null,
                                                Map sideKeep = null) {
        String sourceConfigType = normalize(source?.sourceConfigType)
        Map<String, Object> connector = resolveExtractConnector(ec, source)
        String extractServiceName = connector == null ? null : normalize(connector.extractServiceName)
        // A connector without an extractServiceName (e.g. NETSUITE) does not support interactive
        // extraction — same outcome as no connector at all.
        if (extractServiceName == null) {
            ec.message.addError("${label} API source type '${sourceConfigType ?: "unknown"}' is not supported for manual saved-run execution.")
            return [:]
        }
        // Same defense-in-depth fence as the automation sink: a registry row cannot point the
        // authz-relaxed dispatch at an arbitrary internal service.
        if (!SourceSystemConnectorSupport.isAllowedExtractorServiceShape(extractServiceName)) {
            ec.message.addError("${label} connector for '${sourceConfigType}' has an invalid extract service configuration.")
            return [:]
        }
        String configId = normalize(source?.sourceConfigId)
        if (!configId) {
            ec.message.addError("${label} API source requires a ${connector.endpointLabel ?: sourceConfigType} config.")
            return [:]
        }
        if (ec.message.hasError()) return [:]

        String token = sideToken(fileSide)
        String fileNameValue = OutputPathSupport.sanitizeUploadFileName("${label}-orders-api.json", "${token}-api.json")

        // DAR-BE-318 (plan 3b-2b): where this side's Keep runs is the caller's plan (sideKeep, which it hands the compare as
        // keepBySide), never one built here: a caller whose compare takes no Keep must not have its filters dropped. SPARK sets
        // records aside at ingest, so the read gets no filters; any other plan filters at the read and sends exactly today's
        // projections (Ruling 3b2b-16). Only a Spark side's Keep fields join keepFields and readFields.
        if (sideKeep == null) throw new IllegalArgumentException("${label}: the side's Keep plan (RunExtractSupport.sideKeep) is required.")
        Collection<String> keepRuleFields = KeepPlanSupport.SPARK == sideKeep.placement ? (Collection<String>) sideKeep.fields : []
        List<String> keepFields = sideKeepFields(ec, source, connector)
        if (keepFields != null && keepRuleFields) keepFields = (keepFields + keepRuleFields).unique()
        List<Map<String, Object>> excludeFilters = (List<Map<String, Object>>) sideKeep.readFilters ?: null

        Map<String, Object> extractParams = buildExtractParams(connector, fileSide, configId, artifactContext,
                (Timestamp) apiWindow?.windowStartDate, (Timestamp) apiWindow?.windowEndDate,
                fileNameValue, keepFields, excludeFilters, progressContext,
                // The zone the window was anchored in. preserveExactWindow and normalizeCalendarWindow
                // have both always returned it on this map; only a date-granular connector reads it.
                apiWindow?.timeZone)
        // DAR-UI-064: the run side's own extract options (gorjana R8 / R16), under everything above so they never displace
        // the config id, window or filters; only names the connector declares.
        SourceExtractOptionsSupport.mergeUnder(extractParams,
                SourceExtractOptionsSupport.runtimeOptions(connector, SourceExtractOptionsSupport.storedOn(source)))
        // DAR-BE-192: readByOtherSideKeys is the run's choice, not the extract's; byKeys carries the other side's keys.
        extractParams.remove(AutomationExecutionSupport.READ_BY_OTHER_SIDE_KEYS)
        // DAR-BE-239: the fields this side's rules read, as the scheduled path sends them.
        List<String> readFields = ReconciliationSavedRunSupport.extractReadFields(ec, extractServiceName, source?.compareScopeId, fileSide)
        List<String> declaredParameters = (ec.service.getServiceDefinition(extractServiceName)?.inParameterNameList ?: []) as List<String>
        if (keepRuleFields && ReconciliationSavedRunSupport.READ_FIELDS_PARAMETER in declaredParameters) {
            readFields = ((readFields ?: []) + keepRuleFields).unique()
        }
        if (readFields) extractParams[ReconciliationSavedRunSupport.READ_FIELDS_PARAMETER] = readFields
        // The fields Spark Keep reads, to an extract that takes them by name (so it selects them though it no longer filters).
        if (KeepPlanSupport.SPARK == sideKeep.placement && keepRuleFields && KeepPlanSupport.KEEP_FIELDS_PARAMETER in declaredParameters) {
            extractParams[KeepPlanSupport.KEEP_FIELDS_PARAMETER] = new ArrayList<String>(keepRuleFields)
        }
        if (byKeys != null) {
            if (!("lookupKeys" in declaredParameters)) {
                ec.message.addError("${label} is read by the other side's keys, and its source cannot read by key.")
                return [:]
            }
            extractParams.lookupKeys = byKeys.lookupKeys
            extractParams.lookupKeyField = byKeys.lookupKeyField
        }
        // DAR-BE-318 plan 3a: the window from the tenant's kept copy when the copy covers it; otherwise the live read below.
        Map served = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(connector, extractParams, [
                tenantId   : TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                windowStart: apiWindow?.windowStartDate, windowEnd: apiWindow?.windowEndDate, sideKeep: sideKeep]))
        if (served.served) {
            return sourceResultForLocation(ec, source, fileSide, extractParams.fileName as String, served.fileLocation as String,
                    served.recordCount as Integer)
        }
        logger.info("${label} reads live, not from a kept copy: ${served.reason}")

        Map extraction = (serviceCaller.call(extractServiceName, extractParams) ?: [:]) as Map
        ((List) (extraction.errors ?: [])).each { Object error -> ec.message.addError("${label}: ${error}") }
        if (ec.message.hasError()) return [:]

        String extractedLocation = normalize(extraction.fileLocation)
        if (!extractedLocation) {
            ec.message.addError("${label} API did not return an output file for the selected time period.")
            return [:]
        }
        // DAR-BE-374: what this run read feeds the ID index, in the background
        IdIndexRunHook.afterLiveRead(ec, TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                normalize(connector?.systemEnumId), configId, extractedLocation)
        return sourceResultForLocation(ec, source, fileSide, normalize(extraction.fileName) ?: fileNameValue,
                extractedLocation, normalizeInt(extraction.recordCount, null))
    }

    /**
     * DAR-BE-318 (plan 3b-2b, Task 13). One API side's Keep plan (KeepPlanSupport.effectiveSideKeep), read for the active tenant from the
     * side's compare scope rows, with the side's own extract options (a built-in may apply unless one is set). The run builds it
     * once per API side, sends it to extractApiSource and hands it to the compare as keepBySide, so whatever the read returned
     * (live, a kept-copy slice, a retried run's landed file) gets the same Keep. An upload has no plan: it never gets filters.
     *
     * Where the side's Keep runs is KeepPlanSupport.effectiveSideKeep's one decision. A SPARK side's filters stay at the read,
     * exactly as before Keep moved into Spark, and Spark runs nothing for it (KeepPlanSupport.todaysRead), when:
     * <ul>
     * <li>its extract service still declares the connector's filter parameter (Ruling 3b2b-23, KeepPlanSupport.sparkKeepsSide):
     *     the read still filters until its system's task (17–23) drops the parameter;</li>
     * <li>landed: the side is a landed file handed back without a new read (a retried run's, a judge replay's) that its read
     *     already filtered (KeepPlanSupport.landedFilteredAtRead, asked by effectiveSideKeep).</li>
     * </ul>
     * Every run's compare takes keepBySide, an evaluate run's included (Task 16b).
     * A side whose extract no longer declares the parameter but that Spark cannot keep (not keyed on
     * top-level records: Spark Keep numbers and reads the records array, review M5/M6) fails before any read when it has Keep
     * rules (Ruling 3b2b-25): neither its read nor Spark could filter it.
     */
    static Map sideKeep(def ec, Object source, String fileSide, Map<String, Object> connector, File landed = null) {
        String compareScopeId = normalize(source?.compareScopeId)
        String side = normalize(fileSide) ?: normalize(source?.fileSide)
        // the compare scope's rows, read for the active tenant; an upload or database side (NONE) takes none
        List<Map<String, Object>> rows = KeepPlanSupport.placement(connector) == KeepPlanSupport.NONE ? [] :
                KeepPlanSupport.tenantRuleRows(ec, compareScopeId, side, TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        String extractServiceName = normalize(connector?.extractServiceName)
        return KeepPlanSupport.effectiveSideKeep(ec, rows, connector,
                SourceExtractOptionsSupport.runtimeOptions(connector, SourceExtractOptionsSupport.storedOn(source)),
                extractServiceName ? ec.service.getServiceDefinition(extractServiceName) : null,
                normalize(source?.recordRootExpression), (List<String>) RunConclusionStep.keySpecFor(ec, compareScopeId, side).keyFields,
                true, landed, side)
    }

    /**
     * The keep fields one compare source's side sends its extractor: the rule set's (ReconciliationSavedRunSupport
     * .resolveExtractKeepFields) and the conclusion brain's; null for whole records, or when the connector takes no keep
     * fields. The interactive read and a dataset's kept-fields union (DatasetSupport.keptFields) both use this.
     */
    static List<String> sideKeepFields(def ec, Object source, Map<String, Object> connector) {
        if (!normalize(connector?.keepFieldsParameterName)) return null
        List<String> keepFields = ReconciliationSavedRunSupport.resolveExtractKeepFields(ec, source, connector.keepFieldsBase)
        // DAR-BE-151: a scope in the conclusion brain keeps the fields the brain's facts read (null stays null: full records).
        if (keepFields != null) keepFields = (keepFields + brainRecordFields(ec, source)).unique()
        return keepFields
    }

    /** DAR-BE-151: the record fields the conclusion brain reads on this source's side of its scope; none when the scope is not in it. */
    private static List<String> brainRecordFields(def ec, Object source) {
        String compareScopeId = normalize(source?.compareScopeId)
        if (!compareScopeId) return []
        def scope = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSetCompareScope", "compareScopeId", compareScopeId)
        return BrainFactAdapter.recordFieldsFor(scope?.conclusionBrain, normalize(source?.fileSide))
    }

}
