package darpan.facade.reconciliation

import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeLower
import static darpan.common.ValueSupport.readField
import static darpan.facade.reconciliation.AutomationFacadeSupport.FILE_SIDES
import static darpan.facade.reconciliation.AutomationFacadeSupport.INPUT_MODE_API_RANGE
import static darpan.facade.reconciliation.AutomationFacadeSupport.INPUT_MODE_SFTP_FILES
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_TYPE_SFTP
import static darpan.facade.reconciliation.AutomationSaveSupport.captureSourceFiltersByFileSide
import static darpan.facade.reconciliation.AutomationSaveSupport.seedFromRuleSet

/**
 * sync#Automation and get#Automation's syncStatus: an automation's snapshot re-derived from its saved run, merged with
 * what the operator owns, and the drift between the two. The automation keeps its own copy on purpose (DAR-BE-338): a run
 * edit reaches it only through a deliberate sync, never on its own. Everything here except the two derive/attach reads is
 * pure, so sync and the drift marker can never disagree (AutomationSyncSupportTests).
 */
class AutomationSyncSupport {

    static final String SOURCE_CONFIG_TYPE_DATABASE_QUERY = "DATABASE_QUERY"

    /**
     * Run-owned columns that also exist on ReconciliationAutomationSource. Used to copy the run's
     * values in AND to compare stored against derived for drift, so it must contain only real stored
     * columns — see runAuthoritativeFieldListHoldsOnlyStoredSourceColumns.
     */
    static final List<String> RUN_AUTHORITATIVE_SOURCE_FIELDS = [
            "sourceTypeEnumId", "systemEnumId", "fileTypeEnumId", "schemaFileName",
            "recordRootExpression", "primaryIdExpression", "idValueNormalizer",
            "systemMessageRemoteId",
    ].asImmutable()

    /**
     * Input mode follows the run's source types. SFTP on either side means the automation polls
     * files; anything else (API, DATABASE) runs on a date window.
     */
    static String deriveInputModeFromSources(Map<String, Map<String, Object>> sourcesByFileSide) {
        boolean anySftp = FILE_SIDES.any { String fileSide ->
            normalize((sourcesByFileSide?.get(fileSide))?.get("sourceTypeEnumId")) == SOURCE_TYPE_SFTP
        }
        return anySftp ? INPUT_MODE_SFTP_FILES : INPUT_MODE_API_RANGE
    }

    /**
     * A DATABASE run source carries its DatabaseSourceQuery id in RuleSetCompareSource.sourceConfigId,
     * discriminated by sourceConfigType — the DATABASE connector's expectedSourceConfigType
     * (data/SourceSystemConnectorSeedData.xml). Null for every other system, whose sourceConfigId
     * means something else entirely and must never land in databaseSourceQueryId.
     */
    static String deriveDatabaseSourceQueryId(Object runSource) {
        Map source = (runSource instanceof Map) ? (Map) runSource : [:]
        if (normalize(readField(source, "sourceConfigType")) != SOURCE_CONFIG_TYPE_DATABASE_QUERY) return null
        return normalize(readField(source, "sourceConfigId")) ?: null
    }

    /**
     * The run's current configuration for an automation, as the sync path needs it. Pure read; writes
     * nothing. Returns savedRunMissing rather than throwing so both callers (sync#Automation and
     * get#Automation's syncStatus) can render the state.
     */
    static Map<String, Object> deriveRunAuthoritativeConfig(def ec, def automation) {
        String savedRunId = normalize(readField(automation, "savedRunId"))
        String savedRunType = normalizeLower(readField(automation, "savedRunType")) ?: "ruleset"
        if (!savedRunId || savedRunType != "ruleset") {
            return [savedRunMissing: true, error: null, sourcesByFileSide: [:], filtersByFileSide: [:]]
        }

        Map<String, Object> resolved = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunId)
        if (resolved.error) {
            return [savedRunMissing: false, error: resolved.error as String,
                    sourcesByFileSide: [:], filtersByFileSide: [:]]
        }
        if (!resolved.savedRun) {
            return [savedRunMissing: true, error: null, sourcesByFileSide: [:], filtersByFileSide: [:]]
        }

        String compareScopeId = normalize(((Map) resolved.savedRun)?.get("compareScopeId"))
        Map sourceBySide = (resolved.sourceBySide instanceof Map) ? (Map) resolved.sourceBySide : [:]

        Map<String, Map<String, Object>> sourcesByFileSide = [:]
        Map<String, List<Map<String, Object>>> filtersByFileSide = [:]
        FILE_SIDES.each { String fileSide ->
            def runSource = sourceBySide.get(fileSide)
            if (runSource == null) return
            Map<String, Object> derived = [fileSide: fileSide]
            RUN_AUTHORITATIVE_SOURCE_FIELDS.each { String fieldName ->
                derived.put(fieldName, normalize(readField(runSource, fieldName)) ?: null)
            }
            String queryId = deriveDatabaseSourceQueryId(runSource)
            if (queryId) derived.put("databaseSourceQueryId", queryId)
            // Transient: feeds applyApiSourceMetadataDefaults' config-id chain inside
            // prepareAutomationSave. Never stored — sourceCreateMap does not list it.
            String sourceConfigId = normalize(readField(runSource, "sourceConfigId"))
            if (sourceConfigId) derived.put("sourceConfigId", sourceConfigId)
            // DAR-UI-064: the run side's extract options (R8, R16) are the run's too. Without them a sync rebuilt the
            // source's parameters from the config id alone and an R16 automation went back to reading every return.
            Map<String, Object> options = SourceExtractOptionsSupport.runtimeOptions(
                    SourceSystemConnectorSupport.resolve(ec, normalize(readField(runSource, "systemEnumId"))),
                    SourceExtractOptionsSupport.storedOn(runSource))
            if (options) derived.put("safeMetadataJson", JsonOutput.toJson([parameters: options]))
            sourcesByFileSide.put(fileSide, derived)
            filtersByFileSide.put(fileSide, seedFromRuleSet(ec, compareScopeId, fileSide))
        }

        return [
                savedRunMissing  : false,
                error            : null,
                compareScopeId   : compareScopeId,
                inputModeEnumId  : deriveInputModeFromSources(sourcesByFileSide),
                sourcesByFileSide: sourcesByFileSide,
                filtersByFileSide: filtersByFileSide,
        ]
    }

    /**
     * Operator-owned source columns the run cannot supply. The wizard always asks for remote path and
     * file-name pattern and auto-picks an SFTP server only when the tenant has exactly one, so these
     * are answers, not derivations. extractStatusIds has no RuleSetCompareSource equivalent at all.
     */
    static final List<String> OPERATOR_OWNED_SOURCE_FIELDS = [
            "sftpServerId", "remotePathTemplate", "fileNamePattern", "extractStatusIds",
    ].asImmutable()

    /**
     * One save#Automation `sources` entry per side the RUN defines: run-authoritative columns from the
     * derive, operator-owned columns carried over from the stored row. A side the run no longer
     * defines disappears; a side the automation lacks is added.
     *
     * excludeFilters is ALWAYS set (replace, not merge), and its rows are re-keyed to the declared
     * `values` list parameter: seedFromRuleSet returns `filterValues`, which save#Automation does not
     * declare and Moqui may strip before validateExcludeFilterPayload ever sees it.
     */
    static List<Map<String, Object>> mergeSyncedSourceEntries(List storedSources,
            Map<String, Map<String, Object>> derivedByFileSide,
            Map<String, List<Map<String, Object>>> filtersByFileSide) {
        Map<String, Object> storedByFileSide = [:]
        (storedSources ?: []).each { def stored ->
            String fileSide = normalize(readField(stored, "fileSide"))
            if (fileSide) storedByFileSide.put(fileSide, stored)
        }

        return FILE_SIDES.findAll { String fileSide -> derivedByFileSide?.containsKey(fileSide) }
                .collect { String fileSide ->
                    Map<String, Object> entry = new LinkedHashMap<>(derivedByFileSide.get(fileSide))
                    def stored = storedByFileSide.get(fileSide)
                    // The run is authoritative WHERE IT HAS A VALUE. Every run-authoritative column is
                    // nullable on RuleSetCompareSource, while several are required on
                    // ReconciliationAutomationSource (sourceTypeEnumId is not-null; an API source must
                    // carry systemMessageRemoteId). A run that never recorded one
                    // cannot supply it, and writing the null through makes validateSources reject the
                    // whole sync — leaving the operator no way to sync at all.
                    //
                    // Blank therefore means "no opinion", not "cleared". Nothing in the product can
                    // blank these on a rule set: they are set when the run is created, and a run that
                    // changes endpoint sets the NEW value, which is non-blank and overwrites normally.
                    RUN_AUTHORITATIVE_SOURCE_FIELDS.each { String fieldName ->
                        if (normalize(entry.get(fieldName))) return
                        String storedValue = stored != null ? normalize(readField(stored, fieldName)) : null
                        if (storedValue) entry.put(fieldName, storedValue)
                    }
                    OPERATOR_OWNED_SOURCE_FIELDS.each { String fieldName ->
                        String preserved = stored != null ? normalize(readField(stored, fieldName)) : null
                        if (preserved) entry.put(fieldName, preserved)
                    }
                    entry.put("excludeFilters", toDeclaredExcludeFilterRows(filtersByFileSide?.get(fileSide)))
                    return entry
                } as List<Map<String, Object>>
    }

    /**
     * The input mode a sync will actually persist. Computed from the MERGED entries, not the raw
     * derive: merge restores a stored sourceTypeEnumId where the run recorded none, and that
     * substitution can flip the mode. Deriving from the raw run would let the payload declare
     * AUT_IN_API_RANGE while carrying an AUT_SRC_SFTP source, which validateSources rejects.
     */
    static String resolveSyncedInputMode(List<Map<String, Object>> mergedSourceEntries) {
        Map<String, Map<String, Object>> byFileSide = [:]
        (mergedSourceEntries ?: []).each { Map<String, Object> entry ->
            String fileSide = normalize(entry?.get("fileSide"))
            if (fileSide) byFileSide.put(fileSide, entry)
        }
        return deriveInputModeFromSources(byFileSide)
    }

    /** Rule set filter rows re-keyed to save#Automation's declared excludeFilters sub-parameters. */
    protected static List<Map<String, Object>> toDeclaredExcludeFilterRows(List<Map<String, Object>> rows) {
        return (rows ?: []).collect { Map<String, Object> row ->
            [fieldExpression: normalize(row?.get("fieldExpression")),
             operator       : normalize(row?.get("operator")),
             values         : SourceFilterSupport.splitValues(
                     row?.containsKey("values") ? row.get("values") : row?.get("filterValues"))] as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * A complete save#Automation in-map for a sync: every operator-owned field read back off the
     * stored row, the derived input mode, and the merged source entries. Sync delegates persistence to
     * save#Automation rather than growing a second writer, so this map is the whole contract.
     *
     * Three shapes here are load-bearing:
     *   `sources` is the declared parameter name. `sourceEntries` is the internal name that
     *     prepareAutomationSave reads after the XML rebuilds the list, and would be silently ignored.
     *   isActive is a BOOLEAN — prepareAutomationSave reads `input.isActive == false`, so the stored
     *     "N" string compares false and would silently reactivate a paused automation.
     *   nextScheduledFireTime/lastScheduledFireTime pass through — omit them and the next fire is
     *     recomputed from now, shifting a schedule sync must leave alone.
     */
    static Map<String, Object> buildAutomationSyncPayload(def automation, String derivedInputModeEnumId,
            List<Map<String, Object>> sourceEntries) {
        return [
                automationId            : normalize(readField(automation, "automationId")),
                automationName          : readField(automation, "automationName"),
                description             : readField(automation, "description"),
                savedRunId              : normalize(readField(automation, "savedRunId")),
                savedRunType            : normalizeLower(readField(automation, "savedRunType")) ?: "ruleset",
                inputModeEnumId         : derivedInputModeEnumId,
                scheduleExpr            : readField(automation, "scheduleExpr"),
                nextScheduledFireTime   : readField(automation, "nextScheduledFireTime"),
                lastScheduledFireTime   : readField(automation, "lastScheduledFireTime"),
                relativeWindowTypeEnumId: readField(automation, "relativeWindowTypeEnumId"),
                relativeWindowCount     : readField(automation, "relativeWindowCount"),
                customWindowStartDate   : readField(automation, "customWindowStartDate"),
                customWindowEndDate     : readField(automation, "customWindowEndDate"),
                maxWindowDays           : readField(automation, "maxWindowDays"),
                splitWindowDays         : readField(automation, "splitWindowDays"),
                windowTimeZone          : readField(automation, "windowTimeZone"),
                safeConfigJson          : readField(automation, "safeConfigJson"),
                chatSpaceId             : normalize(readField(automation, "chatSpaceId")) ?: null,
                isActive                : normalize(readField(automation, "isActive")) != "N",
                sources                 : sourceEntries ?: [],
        ] as Map<String, Object>
    }

    /**
     * Whether an automation's snapshot still matches its run, and which fields differ. Derived from the
     * SAME deriveRunAuthoritativeConfig output sync#Automation applies, so the marker can never claim a
     * change sync would not make, or miss one it would.
     *
     * A missing or unresolvable saved run is reported as savedRunMissing and NOT as drift: there is
     * nothing to sync to, so calling it out-of-date would offer an action that cannot work.
     */
    static Map<String, Object> buildAutomationSyncStatus(def automation, List storedSources,
            Map<String, Object> derivedConfig) {
        if (derivedConfig?.savedRunMissing || derivedConfig?.error) {
            return [inSync           : true, changedFields: [], inputModeChanging: false,
                    savedRunMissing  : (derivedConfig?.savedRunMissing ?: false) as Boolean]
        }

        // Compare against what sync would WRITE, not against the raw derive. mergeSyncedSourceEntries
        // preserves operator-owned columns and keeps stored values where the run is blank, so comparing
        // the raw derive reports drift on fields sync will never change — permanent, unfixable drift.
        List<Map<String, Object>> merged = mergeSyncedSourceEntries(storedSources,
                (derivedConfig?.sourcesByFileSide ?: [:]) as Map<String, Map<String, Object>>,
                (derivedConfig?.filtersByFileSide ?: [:]) as Map<String, List<Map<String, Object>>>)
        Map<String, Map<String, Object>> mergedByFileSide = [:]
        merged.each { Map<String, Object> entry ->
            String fileSide = normalize(entry?.get("fileSide"))
            if (fileSide) mergedByFileSide.put(fileSide, entry)
        }

        List<String> changedFields = []
        String syncedInputMode = resolveSyncedInputMode(merged)
        boolean inputModeChanging = syncedInputMode &&
                syncedInputMode != normalize(readField(automation, "inputModeEnumId"))
        if (inputModeChanging) changedFields.add("inputMode")

        String derivedCompareScopeId = normalize(derivedConfig?.compareScopeId)
        if (derivedCompareScopeId && derivedCompareScopeId != normalize(readField(automation, "compareScopeId"))) {
            changedFields.add("compareScope")
        }

        Map storedByFileSide = [:]
        (storedSources ?: []).each { def stored ->
            String fileSide = normalize(readField(stored, "fileSide"))
            if (fileSide) storedByFileSide.put(fileSide, stored)
        }

        FILE_SIDES.each { String fileSide ->
            def stored = storedByFileSide.get(fileSide)
            Map mergedEntry = mergedByFileSide.get(fileSide)
            if (mergedEntry == null) {
                if (stored != null) changedFields.add("${fileSide}.removed".toString())
                return
            }
            if (stored == null) {
                changedFields.add("${fileSide}.added".toString())
                return
            }
            (RUN_AUTHORITATIVE_SOURCE_FIELDS + ["databaseSourceQueryId"]).each { String fieldName ->
                if (normalize(mergedEntry.get(fieldName)) != normalize(readField(stored, fieldName))) {
                    changedFields.add("${fileSide}.${fieldName}".toString())
                }
            }
            List mergedFilters = (mergedEntry.get("excludeFilters") ?: []) as List
            List storedFilters = toDeclaredExcludeFilterRows(
                    filterRowsFromSource(stored) as List<Map<String, Object>>)
            if (mergedFilters != storedFilters) changedFields.add("${fileSide}.excludeFilters".toString())
        }

        return [inSync           : changedFields.isEmpty(), changedFields: changedFields,
                inputModeChanging: inputModeChanging, savedRunMissing: false]
    }

    /** The excludeFilters list attached to a source row by attachCapturedFilters, or empty. */
    protected static List<Map<String, Object>> filterRowsFromSource(def storedSource) {
        Object attached = readField(storedSource, "excludeFilters")
        return (attached instanceof Collection) ? new ArrayList<>((Collection) attached) : []
    }

    /**
     * Source rows narrowed to the columns sync compares, with their own filter rows attached, so
     * buildAutomationSyncStatus stays a pure comparison with no entity access of its own.
     */
    static List<Map<String, Object>> attachCapturedFilters(def ec, List sources, String automationId) {
        Map<String, List<Map<String, Object>>> capturedByFileSide = captureSourceFiltersByFileSide(ec, automationId)
        return (sources ?: []).collect { def source ->
            String fileSide = normalize(readField(source, "fileSide"))
            Map<String, Object> row = [fileSide: fileSide]
            (RUN_AUTHORITATIVE_SOURCE_FIELDS + ["databaseSourceQueryId"]).each { String fieldName ->
                row.put(fieldName, readField(source, fieldName))
            }
            row.put("excludeFilters", capturedByFileSide?.get(fileSide) ?: [])
            return row
        } as List<Map<String, Object>>
    }
}
