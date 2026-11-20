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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 plan 3a: a run's side is served its window from the tenant's kept copy, when the copy covers that window.
 * Both live dispatchers (RunExtractSupport.extractApiSource and AutomationExecutionSupport.callConfiguredSourceExtractor)
 * build a side with {@link #side} from the very parameters they are about to send the extractor, and call
 * {@link #sliceIfServed} just before the live call. Served: the slice is written where the live extract would land, as
 * {metadata, records}, with the side's source filters applied and the set-aside records in the usual .excluded.json.
 * Not served: the reason comes back, and the caller reads live exactly as before.
 *
 * <p>The copy is read only through {@link KeptCopyStore}: the version, readThru and coverage that decide serving come
 * from its manifest (current.json), never from the Dataset row, which can lag the copy (ruling P3a-12). The copy serves
 * only while its manifest was built under the dataset's current idField, stampField and windowField, and only windows
 * that start at or after the manifest's coveredFrom: a setting changed through store#Dataset stops serving until the
 * full read it forces has rebuilt the copy.</p>
 *
 * <p><b>The window the live call sends</b> (plan 3b-1, ruling 3b1-9). A slice selects what the live read would, so it is
 * cut on the window the dispatcher is about to send, read off the very parameters it built, never re-derived:
 * <ul>
 * <li>a connector that keeps exact instants (preserveWindowInstants, plan 3a): the run's instants, half-open, on the side's
 *     field (createdAt when it names none);</li>
 * <li>any other windowed connector is sent the run's instants as they are (RunExtractSupport.buildExtractParams sends ISO
 *     text, AutomationExecutionSupport.applyWindowParameters the Timestamps). Its extractor reads them half-open, unless it
 *     declares preserveWindowInstants: then it has a mode that rounds them to whole days in its own zone, and the side reads
 *     live. The sent bounds must be the run's instants; a record value is an ISO instant or epoch milliseconds (the HotWax
 *     extracts write those), and any other value, or none, stops serving rather than being skipped (ruling P3b1-6: a
 *     record is dropped only when it is placed outside the window);</li>
 * <li>a whole-days connector (windowDateOnly, NetSuite) is sent calendar days, read in the window's zone by the live code
 *     (ReconciliationApiWindowSupport.calendarDateIn). Its source keeps records whose day falls in [from, to) (SuiteQL's
 *     {@code >= TO_DATE(from) AND < TO_DATE(to)}), so a record dated as a plain yyyy-MM-dd day is compared as that day. A
 *     record dated any other way, or not at all, is not placed on a day here, because the source compares days in its own zone, which no
 *     setting names, and the side reads live. For the same reason the copy must have read each day after it ended in every
 *     zone (readThru at or after the last day's end at UTC-12) and cover each from its start in every zone (the first day on
 *     or after coveredFrom's date at UTC+14).</li>
 * </ul>
 * The side's field is the connector's windowFieldName, which the dispatchers send; else the field the extractor windows on
 * by itself, its windowFieldName parameter's default-value (ruling 3b1-4); else, for a plan 3a connector only, createdAt.
 * No field, no serving. A side that sends its connector's keep-fields parameter reads live: the copy holds whole
 * records, and each extractor's projection lives in its own repo, out of reach here. The excluded-records side file is
 * written exactly when the live extractor writes one (ExcludedRecordsSidecar.WRITTEN_BY, ruling P3b1-7); a filtering side
 * whose connector is in neither of its sets reads live.</p>
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

    /** How the live call sends a side's window: the connector keeps exact instants (plan 3a), it is sent the run's instants
     *  as they are, or it is sent calendar days (windowDateOnly). */
    static final String EXACT_INSTANTS = "EXACT_INSTANTS"
    static final String SENT_INSTANTS = "SENT_INSTANTS"
    static final String CALENDAR_DAYS = "CALENDAR_DAYS"
    /** The zones whose days start first and end last: a day read in an unnamed zone lies inside both bounds. */
    private static final ZoneOffset EARLIEST_DAY_ZONE = ZoneOffset.ofHours(14)
    private static final ZoneOffset LATEST_DAY_ZONE = ZoneOffset.ofHours(-12)
    private static final String PRESERVE_PARAMETER = "preserveWindowInstants"

    static final String BY_KEYS = "the side reads by key"
    static final String NO_WINDOW = "the side has no window"
    static final String NO_DATASET = "the tenant has no ACTIVE dataset for this source, connection and options"
    static final String OTHER_TENANT = "the dataset found belongs to another tenant than the run"
    static final String OTHER_WINDOW_FIELD = "the side windows on another field than the dataset"
    static final String NO_VERSION = "the dataset has no current version"
    static final String PAST_READ_THRU = "the window ends after the copy's readThru"
    static final String BEFORE_HORIZON = "the window starts before what the copy covers"
    static final String OTHER_SETTINGS = "the copy was built under other dataset settings; the next full read rebuilds it"
    static final String COPY_CHANGED = "the copy changed while it was opened"
    static final String NO_WINDOW_FIELD = "the side names no window field, and its extractor declares none of its own"
    static final String ROUNDS_TO_DAYS = "the source rounds its window to whole days in its own zone unless its connector keeps the instants"
    static final String DAYS_PAST_READ_THRU = "the window's last day may end after the copy's readThru in the source's zone"
    static final String DAYS_BEFORE_HORIZON = "the window's first day may start before what the copy covers in the source's zone"
    static final String SIDE_FILE_UNDECLARED = "the side filters, and whether its source writes the excluded-records side file is not declared"
    static final String KEEPS_FIELDS = "the side asks its source to keep only some fields, and a slice holds whole copy records"

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
     * @param run tenantId (the run's owner), windowStart and windowEnd (Timestamps: the instants the run covers),
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

        String windowMode = connector?.windowDateOnly == true ? CALENDAR_DAYS :
                (connector?.preserveWindowInstants ? EXACT_INSTANTS : SENT_INSTANTS)
        String dateFromName = normalize(connector?.dateFromParameterName)
        String dateToName = normalize(connector?.dateToParameterName)

        String liveOnly = null
        if (run?.stateMode) liveOnly = "the side reads by status, with no window"
        else if (connector?.windowed == false) liveOnly = "the source reads its whole population, with no window"
        else if (statusName && present(p.get(statusName))) liveOnly = "the side reads by status"
        else if (present(p.get("readFields"))) liveOnly = "the side asks its source for extra fields"
        // each extractor projects in its own way (OMS returns keep items too, a data definition its keepWith companions);
        // that code lives in the connector's repo, out of reach here, so a projected side is never served whole records
        else if (normalize(connector?.keepFieldsParameterName) && present(p.get(normalize(connector.keepFieldsParameterName))))
            liveOnly = KEEPS_FIELDS
        else if (undeclared) liveOnly = "the side sends parameters no dataset is keyed by: ${undeclared.sort().join(', ')}".toString()

        return [
                tenantId       : normalize(run?.tenantId),
                systemEnumId   : normalize(connector?.systemEnumId) ?: normalize(p.systemEnumId),
                sourceConfigId : normalize(p.get(configName)),
                options        : options,
                windowStart    : run?.windowStart,
                windowEnd      : run?.windowEnd,
                // none sent: the extractor's own field, resolved by sliceIfServed (plan 3a's connectors keep createdAt)
                windowFieldName: normalize(p.windowFieldName) ?: (windowMode == EXACT_INSTANTS ? DEFAULT_WINDOW_FIELD : null),
                windowMode     : windowMode,
                extractServiceName: normalize(connector?.extractServiceName),
                // the window exactly as the live call sends it, under the connector's own parameter names
                sentWindowStart: dateFromName ? p.get(dateFromName) : null,
                sentWindowEnd  : dateToName ? p.get(dateToName) : null,
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
            // the window and field the live call would read, or why a slice cannot reproduce them
            Map live = side.windowMode == EXACT_INSTANTS || side.windowMode == null ? [field: side.windowFieldName] : liveWindow(ec, side)
            if (live.reason) return notServed(live.reason as String)
            // a service called with errors on the facade does not run, and its silence would read as "no dataset"
            if (ec.message.hasError()) return notServed("errors are already on the message facade")

            Map dataset = findServing(ec, side)
            if (dataset == null) return notServed(NO_DATASET)
            // Review Focus 4: the run's own tenant, not only the one the lookup ran as (a user's active tenant can
            // differ from the automation's).
            if (normalize(dataset.companyUserGroupId) != side.tenantId) return notServed(OTHER_TENANT)
            String windowField = normalize(dataset.windowField)
            if (live.field != windowField) return notServed(OTHER_WINDOW_FIELD)

            File dir = DatasetRefreshSupport.datasetDir(ec, dataset.companyUserGroupId as String, dataset.datasetId as String)
            Map manifest = KeptCopyStore.manifest(dir)
            Timestamp readThru = manifest?.readThru as Timestamp
            if (!manifest?.version || readThru == null) return notServed(NO_VERSION)
            // Review Focus 3: the whole window lies inside what the copy holds, or nothing is served.
            // a copy keyed, stamped or windowed otherwise than the dataset now is (or that records none of it) is not this copy
            if (!DatasetRefreshSupport.sameSettings(dataset, manifest)) return notServed(OTHER_SETTINGS)
            if (end.time > readThru.time) return notServed(PAST_READ_THRU)
            // what the copy covers, from its manifest: the last full read's horizon start, carried through merges
            Timestamp coveredFrom = manifest.coveredFrom as Timestamp
            if (coveredFrom == null || start.time < coveredFrom.time) return notServed(BEFORE_HORIZON)
            if (side.windowMode == CALENDAR_DAYS) {
                // the source's days are in a zone no setting names: read only once the last day has ended everywhere, and
                // covered only from the first day that starts after coveredFrom everywhere
                if (readThru.toInstant().isBefore(((LocalDate) live.toDay).atStartOfDay(LATEST_DAY_ZONE).toInstant())) {
                    return notServed(DAYS_PAST_READ_THRU)
                }
                if (((LocalDate) live.fromDay).isBefore(coveredFrom.toInstant().atZone(EARLIEST_DAY_ZONE).toLocalDate())) {
                    return notServed(DAYS_BEFORE_HORIZON)
                }
            }

            return slice(ec, side, dataset, dir, manifest, windowField, live)
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

    /**
     * For a side whose connector does not keep exact instants: the field the live read windows on and the window it is sent
     * ([field] for the run's instants, [field, fromDay, toDay] for calendar days), or [reason] when a slice cannot
     * reproduce it. Reads the extractor's service definition only.
     */
    private static Map liveWindow(def ec, Map side) {
        def definition = side.extractServiceName ? ec.service.getServiceDefinition(side.extractServiceName as String) : null
        if (definition == null) return [reason: "the side's extractor is not installed"]
        // an extractor with a rounding mode rounds unless told to keep the instants, which this connector does not
        if (side.windowMode != CALENDAR_DAYS && PRESERVE_PARAMETER in (definition.getInParameterNames() ?: [])) {
            return [reason: ROUNDS_TO_DAYS]
        }
        String field = side.windowFieldName ?:
                normalize(definition.getInParameter(DatasetRefreshSupport.WINDOW_FIELD_PARAMETER)?.attribute("default-value"))
        if (!field) return [reason: NO_WINDOW_FIELD]
        if (side.windowMode == CALENDAR_DAYS) {
            LocalDate fromDay = calendarDay(side.sentWindowStart), toDay = calendarDay(side.sentWindowEnd)
            if (fromDay == null || toDay == null) return [reason: "the side is not sent its window as calendar days"]
            if (!fromDay.isBefore(toDay)) return [reason: "the side is sent no whole day"]
            return [field: field, fromDay: fromDay, toDay: toDay]
        }
        Instant from = sentInstant(side.sentWindowStart), to = sentInstant(side.sentWindowEnd)
        if (from == null || to == null || from != (side.windowStart as Timestamp).toInstant() || to != (side.windowEnd as Timestamp).toInstant()) {
            return [reason: "the side is not sent the run's own window instants"]
        }
        return [field: field]
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

    private static Map slice(def ec, Map side, Map dataset, File dir, Map manifest, String windowField, Map live) {
        Timestamp start = side.windowStart as Timestamp
        Timestamp end = side.windowEnd as Timestamp
        Instant from = start.toInstant(), to = end.toInstant()
        String location = side.outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(ec,
                side.outputRunId ?: side.sourceConfigId, DataManagerSupport.formatRunTimestamp(ec))
        String fileName = side.fileName ?:
                "${DataManagerSupport.safeToken(side.systemEnumId, 'source').toLowerCase()}-${start.time}-${end.time}.json".toString()
        String fileLocation = DataManagerSupport.childLocation(location, fileName)
        // parsed before the copy is opened, as the extractors do: a malformed rule fails here and the live read reports it
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(side.filters)
        File outDir = DataManagerSupport.resolveDirectoryFile(ec, location, false)
        if (outDir == null) return notServed("the output location is not a folder")
        // the folders this slice creates, deepest first: an aborted slice removes them again when they are empty
        List<File> createdDirs = []
        for (File d = outDir; d != null && !d.exists(); d = d.parentFile) createdDirs << d
        boolean served = false, wroteDocument = false
        // ruling P3b1-7: the side file only where the live extractor writes one (the filters apply either way)
        boolean writesSideFile = side.systemEnumId in ExcludedRecordsSidecar.WRITTEN_BY
        if (rules && !writesSideFile && !(side.systemEnumId in ExcludedRecordsSidecar.NOT_WRITTEN_BY)) {
            return notServed(SIDE_FILE_UNDECLARED)
        }
        Map collector = rules && writesSideFile ? ExcludedRecordsSidecar.newCollector() : null
        Map<String, Integer> excludedCounts = [:], absentCounts = [:]
        File recordsWork = null
        File documentWork = null
        String windowMode = side.windowMode ?: EXACT_INSTANTS
        LocalDate fromDay = live.fromDay as LocalDate, toDay = live.toDay as LocalDate
        // a record whose window value the live read would place but this slice cannot: nothing is served
        String unplaceable = null
        try {
            DataManagerSupport.ensureDirectory(outDir, "kept copy slice")
            recordsWork = File.createTempFile("kept-copy-records-", ".partial", outDir)
            long inWindow = 0, kept = 0
            // ruling P3a-14: the copy's file is closed however the slice ends
            boolean sameVersion = KeptCopyStore.withRecords(dir) { Iterator<Map> copy ->
                // the records opened are the manifest's version only if current.json still names it (versions only move on)
                if (KeptCopyStore.manifest(dir)?.version != manifest.version) return false
                recordsWork.withWriter("UTF-8") { Writer w ->
                    while (copy.hasNext()) {
                        Map record = (Map) copy.next().get("record")
                        // the derived key of a composite id is the copy's own: a served record equals a live one
                        if (record != null && record.containsKey(KeptCopyStore.COPY_ID)) {
                            record = new LinkedHashMap(record)
                            record.remove(KeptCopyStore.COPY_ID)
                        }
                        Object value = record?.get(windowField)
                        if (windowMode == EXACT_INSTANTS) {
                            Instant at = instant(value)
                            // half-open, as instants: [windowStart, windowEnd)
                            if (at == null || at.isBefore(from) || !at.isBefore(to)) continue
                        } else if (blank(value)) {
                            // ruling P3b1-6: a record is dropped only when it is placed outside the window. One with no
                            // value may be dated by a field the source filters on and the record never carries (an OMS
                            // order-item unit has omsOrderDate, the server filters on its order's orderDate).
                            unplaceable = "a record has no ${windowField}, so the slice cannot place it in or out of the window".toString()
                            break
                        } else if (windowMode == CALENDAR_DAYS) {
                            LocalDate day = calendarDay(value)
                            if (day == null) {
                                unplaceable = ("a record's ${windowField} is not a calendar day, and the source compares whole " +
                                        "days in its own zone").toString()
                                break
                            }
                            // half-open, as whole days: [from, to)
                            if (day.isBefore(fromDay) || !day.isBefore(toDay)) continue
                        } else {
                            Instant at = recordInstant(value)
                            if (at == null) {
                                unplaceable = "a record's ${windowField} is neither an ISO instant nor epoch milliseconds".toString()
                                break
                            }
                            // half-open, as instants: [windowStart, windowEnd), as the extractor reads what it is sent
                            if (at.isBefore(from) || !at.isBefore(to)) continue
                        }
                        inWindow++
                        Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                        if (verdict != null) {
                            if (collector != null) ExcludedRecordsSidecar.collect(collector, record, (Map) verdict.rule)
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
                return true
            }
            if (unplaceable) return notServed(unplaceable)
            if (!sameVersion) return notServed(COPY_CHANGED)
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
                    // a whole-days source's days, as the live call sends them
                    windowFromDay       : fromDay?.toString(),
                    windowToDay         : toDay?.toString(),
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
            wroteDocument = true
            List<String> warnings = []
            try {
                ExcludedRecordsSidecar.writeBeside(ec, collector, location, fileName, outDir)
            } catch (Exception sidecarError) {
                // advisory, as every extractor treats it
                warnings.add("Excluded-records sidecar not written: ${sidecarError.message}".toString())
            }
            File extractFile = ec.resource.getLocationReference(fileLocation)?.getFile() ?: new File(outDir, fileName)
            served = true
            return [served: true, extractFile: extractFile, reason: null, fileLocation: fileLocation, fileName: fileName,
                    recordCount: kept, metadata: metadata, warnings: warnings]
        } finally {
            recordsWork?.delete()
            documentWork?.delete()
            ExcludedRecordsSidecar.discard(collector)
            if (!served) {
                // nothing of an unserved slice stays: the live read writes this location on its own
                if (wroteDocument) {
                    new File(outDir, fileName).delete()
                    new File(outDir, ExcludedRecordsSidecar.fileNameFor(fileName)).delete()
                }
                createdDirs.each { File d -> if (d.list()?.length == 0) d.delete() }
            }
        }
    }

    private static boolean present(Object v) {
        if (v == null || v == false) return false
        if (v instanceof CharSequence) return v.toString().trim() as boolean
        if (v instanceof Collection) return !((Collection) v).isEmpty()
        if (v instanceof Map) return !((Map) v).isEmpty()
        return true
    }

    private static boolean blank(Object v) {
        return v == null || (v instanceof CharSequence && !v.toString().trim())
    }

    /** A window bound as a dispatcher sends instants: a Timestamp (automation) or ISO-8601 text (interactive); else null. */
    private static Instant sentInstant(Object value) {
        if (value instanceof Date) return ((Date) value).toInstant()
        return value instanceof CharSequence ? instant(value) : null
    }

    /** A record's window value read as an instant: ISO-8601 text, or integral epoch milliseconds; else null. */
    private static Instant recordInstant(Object value) {
        return value instanceof CharSequence || value instanceof Number ? KeptCopyStore.parseInstant(value) : null
    }

    /** A plain calendar day, yyyy-MM-dd, as a whole-days source is sent its window and dates a DATE column; else null. */
    private static LocalDate calendarDay(Object value) {
        String s = value instanceof CharSequence ? value.toString().trim() : null
        if (!(s ==~ /\d{4}-\d{2}-\d{2}/)) return null
        try { return LocalDate.parse(s) } catch (Exception ignored) { return null }
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
