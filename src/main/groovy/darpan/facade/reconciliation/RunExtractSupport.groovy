package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport

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
 *  exclusion filters      resolveExtractExcludeFilters       loadAutomationSourceFilters(automationId,
 *                         (source-level store)                fileSide) -- a DIFFERENT STORE, by design
 *                                                            (design §3.2, not a defect)
 *  state window mode      not supported                     isStateWindowMode + supportsStateExtract
 *                                                            sends NO date parameters at all
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
        String safeName = ReconciliationOutputSupport.sanitizeUploadFileName(inputName, token)
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
                                                  Map<String, Object> progressContext) {
        String token = sideToken(fileSide)
        Map<String, Object> extractParams = [
                (normalize(connector?.configParameterName) ?: "sourceConfigId"): configId,
                (normalize(connector?.dateFromParameterName) ?: "windowStart") : formatApiWindow(windowStartDate),
                (normalize(connector?.dateToParameterName) ?: "windowEnd")     : formatApiWindow(windowEndDate),
                outputLocation: DataManagerSupport.childLocation(artifactContext?.location as String, "${token}-api"),
                fileName      : DataManagerSupport.runArtifactFileName(artifactContext?.runToken, token, fileNameValue),
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
                                                Closure serviceCaller) {
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
        String fileNameValue = ReconciliationOutputSupport.sanitizeUploadFileName("${label}-orders-api.json", "${token}-api.json")

        List<String> keepFields = null
        if (normalize(connector.keepFieldsParameterName)) {
            keepFields = ReconciliationSavedRunSupport.resolveExtractKeepFields(ec, source, connector.keepFieldsBase)
        }
        List<Map<String, Object>> excludeFilters = null
        if (normalize(connector.filterParameterName)) {
            excludeFilters = ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)
        }

        Map<String, Object> extractParams = buildExtractParams(connector, fileSide, configId, artifactContext,
                (Timestamp) apiWindow?.windowStartDate, (Timestamp) apiWindow?.windowEndDate,
                fileNameValue, keepFields, excludeFilters, progressContext)

        Map extraction = (serviceCaller.call(extractServiceName, extractParams) ?: [:]) as Map
        ((List) (extraction.errors ?: [])).each { Object error -> ec.message.addError("${label}: ${error}") }
        if (ec.message.hasError()) return [:]

        String extractedLocation = normalize(extraction.fileLocation)
        if (!extractedLocation) {
            ec.message.addError("${label} API did not return an output file for the selected time period.")
            return [:]
        }
        return sourceResultForLocation(ec, source, fileSide, normalize(extraction.fileName) ?: fileNameValue,
                extractedLocation, normalizeInt(extraction.recordCount, null))
    }
}
