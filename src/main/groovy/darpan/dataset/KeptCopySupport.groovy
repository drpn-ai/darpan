package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 plan 3a: a run's side is served its window from the tenant's kept copy, when the copy covers that window.
 * Both live dispatchers (RunExtractSupport.extractApiSource and AutomationExecutionSupport.callConfiguredSourceExtractor)
 * build a side with {@link #side} from the very parameters they are about to send the extractor, and call
 * {@link #sliceIfServed} just before the live call. Served: the slice is written where the live extract would land, as
 * {metadata, records}, with the side's source filters applied and the set-aside records in the usual .excluded.json.
 * Not served: the reason comes back, and the caller reads live exactly as before.
 *
 * <p>The copy is read only through {@link KeptCopyStore}: the version and readThru that decide serving come from its
 * manifest (current.json), never from the Dataset row, which can lag the copy (ruling P3a-12).</p>
 *
 * <p><b>The options key</b> ({@code find#ServingDataset}'s options) is the run side's extract options its connector
 * declares (SourceExtractOptionsSupport.declaredOptions), read off the parameters the dispatcher built, with false and
 * blank values dropped (absent IS the default, as the run side stores them) and readByOtherSideKeys never in it. That is
 * what a dataset is stored with and what refresh#Dataset sends. Filters, keepFields, the window, the window field, the
 * output location, progress and run ids are never in it. Any other parameter the side would send (an undeclared
 * snapshot parameter, a status list, readFields) makes the side read live, so a copy read without it is never served.</p>
 */
class KeptCopySupport {
    private static final Logger logger = LoggerFactory.getLogger(KeptCopySupport)

    static final String FIND_SERVICE = "dataset.DatasetServices.find#ServingDataset"
    static final String DEFAULT_WINDOW_FIELD = "createdAt"
    private static final long DAY = 86_400_000L

    static final String BY_KEYS = "the side reads by key"
    static final String NO_WINDOW = "the side has no window"
    static final String NO_DATASET = "the tenant has no ACTIVE dataset for this source, connection and options"
    static final String OTHER_TENANT = "the dataset found belongs to another tenant than the run"
    static final String OTHER_WINDOW_FIELD = "the side windows on another field than the dataset"
    static final String NO_VERSION = "the dataset has no current version"
    static final String PAST_READ_THRU = "the window ends after the copy's readThru"
    static final String BEFORE_HORIZON = "the window starts before the copy's horizon"
    static final String COPY_CHANGED = "the copy changed while it was opened"

    /**
     * Parameters a dispatcher sets that never change which raw records a read returns (or that the side checks on its
     * own: the window field, readFields, lookup keys). Everything else the side sends must be a declared option.
     */
    private static final Set<String> DISPATCH_PARAMETERS = ([
            "outputLocation", "fileName", "systemEnumId", "preserveWindowInstants", "windowFieldName",
            "lookupKeys", "lookupKeyField", "lookupWithinWindow", "reconciliationRunResultId", "progressStageCode",
            "expectedRecordCount", "automationId", "automationExecutionId", "companyUserGroupId", "fileSide",
            "sourceTypeEnumId", "windowStartDate", "windowEndDate", "windowStart", "windowEnd", "fromDate", "toDate",
            AutomationExecutionSupport.READ_BY_OTHER_SIDE_KEYS,
    ] as Set<String>).asImmutable()

    /**
     * The side shape, from what a dispatcher is about to send. Pure: reads neither the ec nor the copy.
     *
     * @param connector the connector map the dispatcher resolved (SourceSystemConnectorSupport)
     * @param params the extractor parameters, complete, as they would be sent
     * @param run tenantId (the run's owner), windowStart and windowEnd (Timestamps: the instants the read covers),
     *        stateMode (an automation read by status with no window), outputRunId (the folder name an extractor uses
     *        when params carry no outputLocation)
     */
    static Map<String, Object> side(Map<String, Object> connector, Map<String, Object> params, Map<String, Object> run) {
        Map<String, Object> p = params ?: [:]
        String configName = normalize(connector?.configParameterName) ?: "sourceConfigId"
        String filterName = normalize(connector?.filterParameterName)
        String statusName = normalize(connector?.statusParameterName)
        Set<String> declared = SourceExtractOptionsSupport.declaredOptions(connector)*.name as Set<String>

        Map<String, Object> options = new TreeMap<String, Object>()
        List<String> undeclared = []
        p.each { Object k, Object v ->
            String name = k as String
            if (name in declared) {
                if (present(v)) options.put(name, v)
            } else if (!(name in DISPATCH_PARAMETERS) && name != configName && name != filterName && name != statusName &&
                    name != normalize(connector?.keepFieldsParameterName) && name != normalize(connector?.dateFromParameterName) &&
                    name != normalize(connector?.dateToParameterName) && present(v)) {
                undeclared << name
            }
        }

        String liveOnly = null
        if (run?.stateMode) liveOnly = "the side reads by status, with no window"
        else if (connector?.windowed == false) liveOnly = "the source reads its whole population, with no window"
        else if (connector?.windowDateOnly == true) liveOnly = "the source reads whole days"
        else if (!connector?.preserveWindowInstants) liveOnly = "the source does not read the exact window instants"
        else if (statusName && present(p.get(statusName))) liveOnly = "the side reads by status"
        else if (present(p.get("readFields"))) liveOnly = "the side asks its source for extra fields"
        else if (undeclared) liveOnly = "the side sends parameters no dataset is keyed by: ${undeclared.sort().join(', ')}".toString()

        return [
                tenantId       : normalize(run?.tenantId),
                systemEnumId   : normalize(connector?.systemEnumId) ?: normalize(p.systemEnumId),
                sourceConfigId : normalize(p.get(configName)),
                options        : options,
                windowStart    : run?.windowStart,
                windowEnd      : run?.windowEnd,
                windowFieldName: normalize(p.windowFieldName) ?: DEFAULT_WINDOW_FIELD,
                filters        : filterName ? p.get(filterName) : null,
                byKeys         : p.get("lookupKeys") != null,
                outputLocation : normalize(p.outputLocation),
                fileName       : normalize(p.fileName),
                outputRunId    : normalize(run?.outputRunId),
                liveOnly       : liveOnly,
        ] as Map<String, Object>
    }

    /**
     * Serves the side's window from the copy, or says why not. Never throws: anything unexpected (an unreadable copy, a
     * failed lookup) is a reason, logged, and the caller reads live. Nothing is left at the output location unless it
     * is served.
     *
     * @return [served: true, extractFile, fileLocation, fileName, recordCount, metadata, warnings] or [served: false, reason]
     */
    static Map sliceIfServed(def ec, Map side) {
        try {
            if (side == null) return notServed(NO_WINDOW)
            if (side.liveOnly) return notServed(side.liveOnly as String)
            if (side.byKeys) return notServed(BY_KEYS)
            Timestamp start = side.windowStart as Timestamp
            Timestamp end = side.windowEnd as Timestamp
            if (start == null || end == null) return notServed(NO_WINDOW)
            if (!side.tenantId || !side.systemEnumId || !side.sourceConfigId) return notServed(NO_DATASET)
            // a service called with errors on the facade does not run, and its silence would read as "no dataset"
            if (ec.message.hasError()) return notServed("errors are already on the message facade")

            Map dataset = findServing(ec, side)
            if (dataset == null) return notServed(NO_DATASET)
            // Review Focus 4: the run's own tenant, not only the one the lookup ran as (a user's active tenant can
            // differ from the automation's).
            if (normalize(dataset.companyUserGroupId) != side.tenantId) return notServed(OTHER_TENANT)
            String windowField = normalize(dataset.windowField)
            if (side.windowFieldName != windowField) return notServed(OTHER_WINDOW_FIELD)

            File dir = DatasetRefreshSupport.datasetDir(ec, dataset.companyUserGroupId as String, dataset.datasetId as String)
            Map manifest = KeptCopyStore.manifest(dir)
            Timestamp readThru = manifest?.readThru as Timestamp
            if (!manifest?.version || readThru == null) return notServed(NO_VERSION)
            // Review Focus 3: the whole window lies inside what the copy holds, or nothing is served.
            if (end.time > readThru.time) return notServed(PAST_READ_THRU)
            long horizonDays = ((dataset.horizonDays ?: 62) as Number).longValue()
            if (start.time < readThru.time - horizonDays * DAY) return notServed(BEFORE_HORIZON)

            return slice(ec, side, dataset, dir, manifest, windowField)
        } catch (Throwable t) {
            logger.warn("Kept copy not served for ${side?.systemEnumId} ${side?.sourceConfigId}", t)
            return notServed("the copy could not be read: ${t.message ?: t}".toString())
        }
    }

    /** A served slice as an extractor's result map, for the automation path (what callConfiguredSourceExtractor returns). */
    static Map<String, Object> extractResult(Map served) {
        return [dataAvailable  : ((served.recordCount ?: 0) as long) > 0,
                fileLocation   : served.fileLocation,
                fileName       : served.fileName,
                fileTypeEnumId : "DftJson",
                recordCount    : served.recordCount,
                requestMetadata: served.metadata,
                warnings       : served.warnings ?: [],
                errors         : []] as Map<String, Object>
    }

    private static Map notServed(String reason) {
        return [served: false, extractFile: null, reason: reason]
    }

    /** find#ServingDataset, leaving the message facade as it found it; a failed lookup throws (and reads live). */
    private static Map findServing(def ec, Map side) {
        Map out = (ec.service.sync().name(FIND_SERVICE).parameters([systemEnumId: side.systemEnumId,
                sourceConfigId: side.sourceConfigId, options: side.options]).call() ?: [:]) as Map
        List added = new ArrayList((ec.message.getErrors() ?: []) as List)
        if (added) {
            ec.message.clearErrors()
            throw new IllegalStateException(added.join("; "))
        }
        return out.dataset instanceof Map ? (Map) out.dataset : null
    }

    private static Map slice(def ec, Map side, Map dataset, File dir, Map manifest, String windowField) {
        Timestamp start = side.windowStart as Timestamp
        Timestamp end = side.windowEnd as Timestamp
        Instant from = start.toInstant(), to = end.toInstant()
        String location = side.outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(ec,
                side.outputRunId ?: side.sourceConfigId, DataManagerSupport.formatRunTimestamp(ec))
        String fileName = side.fileName ?:
                "${DataManagerSupport.safeToken(side.systemEnumId, 'source').toLowerCase()}-${start.time}-${end.time}.json".toString()
        String fileLocation = DataManagerSupport.childLocation(location, fileName)
        File outDir = DataManagerSupport.resolveDirectoryFile(ec, location, true)
        // parsed before the copy is opened, as the extractors do: a malformed rule fails here and the live read reports it
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(side.filters)
        Map collector = rules ? ExcludedRecordsSidecar.newCollector() : null
        Map<String, Integer> excludedCounts = [:], absentCounts = [:]
        File recordsWork = File.createTempFile("kept-copy-records-", ".partial", outDir)
        File documentWork = null
        try {
            Iterator<Map> copy = KeptCopyStore.records(dir)
            // the records opened are the manifest's version only if current.json still names it (versions only move on)
            if (KeptCopyStore.manifest(dir)?.version != manifest.version) return notServed(COPY_CHANGED)
            long inWindow = 0, kept = 0
            recordsWork.withWriter("UTF-8") { Writer w ->
                while (copy.hasNext()) {
                    Map record = (Map) copy.next().get("record")
                    Instant at = instant(record?.get(windowField))
                    // half-open, as instants: [windowStart, windowEnd)
                    if (at == null || at.isBefore(from) || !at.isBefore(to)) continue
                    inWindow++
                    Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                    if (verdict != null) {
                        ExcludedRecordsSidecar.collect(collector, record, (Map) verdict.rule)
                        String key = String.valueOf(((Map) verdict.rule).sequenceNum)
                        Map<String, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.reason ? absentCounts : excludedCounts
                        bucket.put(key, (bucket.get(key) ?: 0) + 1)
                        continue
                    }
                    if (kept > 0) w.write(",")
                    w.write(JsonOutput.toJson(record))
                    kept++
                }
            }
            Map<String, Object> metadata = [
                    servedFromCopy      : true,
                    datasetId           : dataset.datasetId,
                    copyVersion         : manifest.version,
                    copyReadAt          : (manifest.readAt as Timestamp)?.toInstant()?.toString(),
                    copyReadThru        : (manifest.readThru as Timestamp).toInstant().toString(),
                    systemEnumId        : side.systemEnumId,
                    sourceConfigId      : side.sourceConfigId,
                    windowStart         : from.toString(),
                    windowEnd           : to.toString(),
                    windowFieldName     : windowField,
                    extractedRecordCount: inWindow,
                    keptRecordCount     : kept,
                    // as the extractors report it: absent when no rules, every rule listed (zero counts too) when some
                    filters             : rules ? [configuredExclusions: rules.collect { Map<String, Object> rule ->
                        String key = String.valueOf(rule.sequenceNum)
                        [sequenceNum     : rule.sequenceNum, fieldExpression: rule.fieldExpression, operator: rule.operator,
                         values          : new ArrayList<String>((List) rule.values), excludedCount: excludedCounts.get(key) ?: 0,
                         fieldAbsentCount: absentCounts.get(key) ?: 0]
                    }] : null,
            ].findAll { it.value != null } as Map<String, Object>

            documentWork = File.createTempFile("kept-copy-", ".partial", outDir)
            documentWork.withOutputStream { OutputStream out ->
                out.write(('{"metadata":' + JsonOutput.toJson(metadata) + ',"records":[').getBytes(StandardCharsets.UTF_8))
                recordsWork.withInputStream { InputStream input -> input.transferTo(out) }
                out.write(']}'.getBytes(StandardCharsets.UTF_8))
            }
            DataManagerSupport.moveIntoLocation(ec, documentWork, fileLocation)
            List<String> warnings = []
            try {
                ExcludedRecordsSidecar.writeBeside(ec, collector, location, fileName, outDir)
            } catch (Exception sidecarError) {
                // advisory, as every extractor treats it
                warnings.add("Excluded-records sidecar not written: ${sidecarError.message}".toString())
            }
            File extractFile = ec.resource.getLocationReference(fileLocation)?.getFile() ?: new File(outDir, fileName)
            return [served: true, extractFile: extractFile, reason: null, fileLocation: fileLocation, fileName: fileName,
                    recordCount: kept, metadata: metadata, warnings: warnings]
        } finally {
            recordsWork.delete()
            documentWork?.delete()
            ExcludedRecordsSidecar.discard(collector)
        }
    }

    private static boolean present(Object v) {
        if (v == null || v == false) return false
        if (v instanceof CharSequence) return v.toString().trim() as boolean
        if (v instanceof Collection) return !((Collection) v).isEmpty()
        if (v instanceof Map) return !((Map) v).isEmpty()
        return true
    }

    /** An ISO-8601 instant or offset date-time, as KeptCopyStore compares them; null when absent or unreadable. */
    private static Instant instant(Object value) {
        String s = value?.toString()?.trim()
        if (!s) return null
        try { return Instant.parse(s) } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(s).toInstant() } catch (Exception ignored) { }
        return null
    }
}
