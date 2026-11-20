package darpan.dataset

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import darpan.config.DarpanSettings
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DAR-BE-318 plan 3a: one read of a dataset's source into its kept copy, for refresh#Dataset. The service owns the lock,
 * the read kind and the rows; this class calls the extractor and streams its file into {@link KeptCopyStore}.
 *
 * The extractor is a closure (serviceName, params) -> result map, passed per call: refresh#Dataset's extractor parameter,
 * or {@link #liveExtractor} when none is given. Tests pass a stub; nothing swaps a static.
 */
class DatasetRefreshSupport {
    private static final Logger logger = LoggerFactory.getLogger(DatasetRefreshSupport)
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    static final String FULL = "DRK_FULL"
    static final String INCREMENTAL = "DRK_INCREMENTAL"
    static final String ROLLING = "DRR_ROLLING"
    static final int ROLLING_HORIZON_DAYS = 60
    static final String READS_FOLDER = "reads"
    static final String LOCK_TAKEN_OVER = "lock taken over: another refresh holds this dataset now"
    /** The extract parameter a dataset read windows by: its stampField (daily) or windowField (full). */
    static final String WINDOW_FIELD_PARAMETER = "windowFieldName"
    private static final long DAY = 86_400_000L

    /** The dataset's folder under the data manager root, which honours darpan.data.manager.location (ruling P3a-7). */
    static File datasetDir(def ec, String tenantId, String datasetId) {
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        return DatasetSupport.datasetDir(root, tenantId, datasetId)
    }

    /**
     * The connector a dataset of {@code systemEnumId} reads through, or an IllegalStateException that says why it cannot
     * keep a copy: no connector, an extract service outside the extractor shape, a service that is not installed, or one
     * that declares no windowFieldName. Moqui drops an undeclared parameter, so such a service would silently window on
     * its own default field (createdAt) and every daily read would miss changes to older records.
     */
    static Map<String, Object> datasetConnector(def ec, String systemEnumId) {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        String serviceName = connector?.extractServiceName
        if (!serviceName) throw new IllegalStateException("No source connector reads ${systemEnumId}".toString())
        // The same fence the live dispatchers keep: a registry row cannot aim the read at an arbitrary service.
        if (!SourceSystemConnectorSupport.isAllowedExtractorServiceShape(serviceName)) {
            throw new IllegalStateException("The connector for ${systemEnumId} names an invalid extract service".toString())
        }
        def definition = ec.service.getServiceDefinition(serviceName)
        if (definition == null) throw new IllegalStateException("${serviceName} is not installed".toString())
        if (!(WINDOW_FIELD_PARAMETER in (definition.getInParameterNames() ?: []))) {
            throw new IllegalStateException(("${serviceName} takes no ${WINDOW_FIELD_PARAMETER}, so a read of ${systemEnumId} " +
                    "cannot be windowed on the dataset's fields").toString())
        }
        return connector
    }

    /** {@link #datasetConnector}'s refusal as text, or null when the system can keep a copy. For store#Dataset. */
    static String datasetConnectorProblem(def ec, String systemEnumId) {
        try {
            datasetConnector(ec, systemEnumId)
            return null
        } catch (IllegalStateException e) {
            return e.message
        }
    }

    /** Where one read's extract lands, in the data-manager location form the extractors take: .../datasets/T/D/reads/N. */
    static String readLocation(def ec, String tenantId, String datasetId, String readSeqId) {
        String base = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), "datasets")
        base = DataManagerSupport.childLocation(base, DataManagerSupport.safeToken(tenantId, "tenant"))
        base = DataManagerSupport.childLocation(base, DataManagerSupport.safeToken(datasetId, "dataset"))
        return DataManagerSupport.childLocation(DataManagerSupport.childLocation(base, READS_FOLDER), readSeqId)
    }

    /**
     * Everything refresh#Dataset does while it holds the lock, short of writing rows: decide the read kind and window,
     * read, merge. Never throws, so the service always reaches the step that writes the read row and clears the lock.
     * {@code startedAt} is also the lock token: the copy is swapped and pruned only while the Dataset's refreshingSince
     * still equals it ({@link #ownerGuard}).
     *
     * @return [readKindEnumId, fromStamp] plus either the read's results and nextReadDate, or [error: text]
     */
    static Map refresh(def ec, Map dataset, String readSeqId, boolean forceFull, Timestamp startedAt, Object extractor) {
        String kind = FULL
        Timestamp fromStamp = null
        Map result
        try {
            if (extractor != null && !(extractor instanceof Closure)) {
                throw new IllegalArgumentException("extractor must be a closure (serviceName, params) -> result")
            }
            // I1: checked before anything is read, so a deploy that leaves the extractor behind fails loudly with a warning
            String connectorProblem = datasetConnectorProblem(ec, dataset.systemEnumId as String)
            if (connectorProblem) {
                String warning = "Not read: ${connectorProblem}".toString()
                result = [error: connectorProblem, warningText: warning.length() > 255 ? warning.substring(0, 252) + "..." : warning]
            } else {
                File dir = datasetDir(ec, dataset.companyUserGroupId as String, dataset.datasetId as String)
                kind = readKind(dataset, KeptCopyStore.manifest(dir), forceFull, startedAt)
                fromStamp = kind == FULL ?
                        new Timestamp(startedAt.time - horizonDays(dataset) * DAY) :
                        new Timestamp(((Timestamp) dataset.readThru).time - ((dataset.marginDays ?: 2) as long) * DAY)
                // the tenant's zone, as the live paths resolve it (refresh runs under the dataset's tenant)
                String zone = TenantAccessSupport.resolveActiveTenantTimeZone(ec)
                result = read(ec, dataset, dir, readSeqId, kind, fromStamp, startedAt, zone, (Closure) extractor,
                        ownerGuard(ec, dataset.datasetId as String, dataset.companyUserGroupId as String, startedAt))
                if (!result.error) result.nextReadDate = nextReadAt(startedAt, zone, dataset.readHourLocal as Integer)
            }
        } catch (Throwable t) {
            logger.warn("Dataset ${dataset?.datasetId} read ${readSeqId} failed", t)
            result = [error: t.message ?: t.toString()]
        }
        // an error a service left on the message facade fails the read, and must not stop the row and unlock calls after it
        List leftover = ((ec.message.getErrors() ?: []) as List).collect { it.toString() }
        if (leftover) {
            ec.message.clearErrors()
            if (!result.error) result = [error: leftover.join("; ")]
        }
        return result + [readKindEnumId: kind, fromStamp: fromStamp]
    }

    /**
     * FULL when asked, when there is no current version (or no readThru to read on from), when the copy does not cover the
     * dataset's settings ({@link #copyCovers}: store#Dataset changed one, or the copy predates coverage), or when the last
     * full read is fullReadDays old. Half a day of slack, so the daily read on the seventh day is the full one even when it
     * starts a little earlier in the day than the last full read did.
     */
    static String readKind(Map dataset, Map manifest, boolean forceFull, Timestamp now) {
        // a rolling dataset re-reads its whole window every day (ruling 3b1-1)
        if (isRolling(dataset)) return FULL
        if (forceFull || manifest == null || !dataset.currentVersion || dataset.readThru == null || dataset.lastFullReadAt == null) {
            return FULL
        }
        if (!copyCovers(dataset, manifest, now)) return FULL
        long fullEvery = ((dataset.fullReadDays ?: 7) as long) * DAY - 12 * 3_600_000L
        return now.time - ((Timestamp) dataset.lastFullReadAt).time >= fullEvery ? FULL : INCREMENTAL
    }

    /**
     * The kind lock#DatasetRefresh writes on the RUNNING read before refresh() decides it for good. Never throws (the lock
     * must not fail on an unreadable copy): FULL when the manifest cannot be read, as refresh() would then read.
     */
    static String predictedReadKind(def ec, Map dataset, boolean forceFull, Timestamp now) {
        try {
            return readKind(dataset, KeptCopyStore.manifest(datasetDir(ec, dataset.companyUserGroupId as String,
                    dataset.datasetId as String)), forceFull, now)
        } catch (Throwable t) {
            logger.warn("Dataset ${dataset?.datasetId}: copy manifest unreadable; the read is expected to be full", t)
            return FULL
        }
    }

    /**
     * Whether the copy (its manifest) was built under the dataset's idField, stampField and windowField and covers its
     * whole horizon back from {@code now}: coveredFrom at or before now minus horizonDays. False for a manifest that
     * records no coverage.
     */
    static boolean copyCovers(Map dataset, Map manifest, Timestamp now) {
        Timestamp coveredFrom = manifest?.coveredFrom as Timestamp
        if (coveredFrom == null || !sameSettings(dataset, manifest)) return false
        return coveredFrom.time <= now.time - horizonDays(dataset) * DAY
    }

    /** The dataset's horizon in days: its own, else 60 for a rolling dataset (ruling 3b1-1) and 62 for a changed-since one. */
    static long horizonDays(Map dataset) {
        return (dataset.horizonDays ?: (isRolling(dataset) ? ROLLING_HORIZON_DAYS : 62)) as long
    }

    static boolean isRolling(Map dataset) {
        return dataset?.readRuleEnumId == ROLLING
    }

    /**
     * The fields a composite idField names, in order: "orderId, orderItemSeqId" -> [orderId, orderItemSeqId]. A single
     * field gives a one-element list (ruling 3b1-3).
     */
    static List<String> idFields(String idField) {
        return (idField ?: "").split(",").collect { it.trim() }.findAll { it } as List<String>
    }

    /**
     * The derived copy id of a record under a composite idField: the field values in order, joined by '|' (a missing value
     * is empty). A one-field list gives that field's value alone.
     */
    static String copyId(Map record, List<String> idFields) {
        return idFields.collect { String f -> record.get(f) == null ? "" : String.valueOf(record.get(f)) }.join("|")
    }

    /** The manifest's idField, stampField and windowField equal the dataset's. */
    static boolean sameSettings(Map dataset, Map manifest) {
        return manifest != null && ["idField", "stampField", "windowField"].every { String f ->
            manifest.get(f) && manifest.get(f) == dataset?.get(f)
        }
    }

    /**
     * One due refresh for scan#DueDatasets, run as the dataset's tenant (the job has none). Never throws: a failure, or a
     * refusal because another refresh holds the lock, comes back as [error: text] and the scan goes on to the next dataset.
     */
    static Map refreshAsTenant(def ec, String datasetId, String tenantId, Object extractor) {
        try {
            return (Map) TenantAccessSupport.withSystemTenant(tenantId) {
                Map out = ec.service.sync().name("dataset.DatasetServices.refresh#Dataset")
                        .parameters([datasetId: datasetId, extractor: extractor]).call()
                List errors = ((ec.message.getErrors() ?: []) as List).collect { it.toString() }
                ec.message.clearErrors()
                return errors ? [error: errors.join("; ")] : [readKindEnumId: out?.readKindEnumId, readSeqId: out?.readSeqId,
                                                              missCount: out?.missCount]
            }
        } catch (Throwable t) {
            logger.warn("Dataset ${datasetId} refresh failed in the scan", t)
            ec.message.clearErrors()
            return [error: t.message ?: t.toString()]
        }
    }

    /**
     * The lock-ownership guard (I1): calls {@code action} inside a new transaction that holds the Dataset row for update and
     * only while refreshingSince still equals {@code token}; otherwise returns false without calling it. A refresh whose
     * lock was taken over (it ran past the six-hour stale mark) therefore never swaps the copy or prunes, and holding the
     * row lock across the action means a takeover cannot slip in between the check and the swap.
     */
    static Closure ownerGuard(def ec, String datasetId, String tenantId, Timestamp token) {
        return { Closure action ->
            return ec.transaction.runRequireNew(null, "Dataset ${datasetId} lock check failed".toString()) {
                // list(), not one(): one() drops a non-PK condition beside the full PK, and with it the tenant pin
                def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", datasetId)
                        .condition("companyUserGroupId", tenantId).forUpdate(true).useCache(false).list().find()
                Timestamp held = row?.refreshingSince as Timestamp
                if (held == null || token == null || held.time != token.time) return false
                return action.call()
            }
        }
    }

    /**
     * What a failed read sets on the Dataset (ruling P3a-10). {@code priorStatuses} are the dataset's earlier reads, newest
     * first; the leading DRS_FAILED ones and this failure are the consecutive failures n. Below maxRetries the next read is
     * now + base * 2^(n-1) minutes, capped; at maxRetries it waits for the next day's read hour and the warning names the
     * failure. The limits are settings (darpan.dataset.refresh.*), editable in prod. Never throws.
     */
    static Map failureUpdates(def ec, Map dataset, List<String> priorStatuses, String error, Timestamp now) {
        int failures = 1 + ((priorStatuses ?: []).takeWhile { it == "DRS_FAILED" }.size())
        try {
            int maxRetries = Math.max(1, DarpanSettings.intValue("darpan.dataset.refresh.maxRetries"))
            if (failures >= maxRetries) {
                String zone = TenantAccessSupport.resolveActiveTenantTimeZone(ec)
                String warning = "Reads failed ${failures} times in a row; the next try is tomorrow. Last error: ${error}".toString()
                return [nextReadDate: nextReadAt(now, zone, dataset.readHourLocal as Integer),
                        warningText : warning.length() > 255 ? warning.substring(0, 252) + "..." : warning]
            }
            long base = Math.max(1, DarpanSettings.intValue("darpan.dataset.refresh.retryBackoffBaseMinutes"))
            long cap = Math.max(base, (long) DarpanSettings.intValue("darpan.dataset.refresh.retryBackoffCapMinutes"))
            long minutes = Math.min(cap, base * (1L << Math.min(failures - 1, 20)))
            return [nextReadDate: new Timestamp(now.time + minutes * 60_000L)]
        } catch (Throwable t) {
            logger.warn("Dataset ${dataset?.datasetId}: back-off settings unreadable; waiting a day", t)
            return [nextReadDate: new Timestamp(now.time + DAY)]
        }
    }

    /** The day after {@code from}, at {@code hour} o'clock, in {@code zoneId}. */
    static Timestamp nextReadAt(Timestamp from, String zoneId, Integer hour) {
        ZoneId zone = ZoneId.of(zoneId ?: "UTC")
        ZonedDateTime next = from.toInstant().atZone(zone).toLocalDate().plusDays(1)
                .atTime(hour == null ? 4 : hour, 0).atZone(zone)
        return Timestamp.from(next.toInstant())
    }

    /**
     * Reads the source and merges (INCREMENTAL) or replaces (FULL) the copy, then prunes old versions and old read files.
     * Never throws: a failure returns [error: text] and leaves the current version as it was (KeptCopyStore names a new
     * version current only once it is whole).
     *
     * @param fromStamp the window start: readThru minus the margin (INCREMENTAL), or the horizon start (FULL)
     * @param readThru the moment the read started; the window end
     */
    static Map read(def ec, Map dataset, File datasetDir, String readSeqId, String readKindEnumId,
                    Timestamp fromStamp, Timestamp readThru, String timeZone, Closure extractor, Closure ownerGuard) {
        try {
            boolean full = readKindEnumId == FULL
            String tenantId = dataset.companyUserGroupId
            String datasetId = dataset.datasetId
            Map<String, Object> connector = datasetConnector(ec, dataset.systemEnumId as String)
            String serviceName = connector.extractServiceName

            String location = readLocation(ec, tenantId, datasetId, readSeqId)
            DataManagerSupport.resolveDirectoryFile(ec, location, true)
            Map<String, Object> params = extractParams(connector, dataset, location, readSeqId,
                    full ? dataset.windowField as String : dataset.stampField as String, fromStamp, readThru, timeZone)

            Map out = ((extractor ?: liveExtractor(ec)).call(serviceName, params) ?: [:]) as Map
            List errors = (out.errors instanceof Collection ? (Collection) out.errors : []).findAll { it } as List
            if (errors) throw new IllegalStateException(errors.join("; "))
            File file = resolveFile(ec, out.fileLocation as String)
            if (file == null || !file.isFile()) throw new IllegalStateException("The read returned no file")
            Timestamp readAt = new Timestamp(file.lastModified())

            // The swap and the prune run together under the owner guard: a refresh that lost its lock does neither, and
            // KeptCopyStore deletes its new version.
            CopyIdCheck idCheck = new CopyIdCheck()
            Closure swapAndPrune = { Closure swap ->
                // a record missing an id part, or two with one copy id, fails the read before anything is swapped in
                idCheck.assertClean(dataset.idField as String)
                return ownerGuard.call({ ->
                    swap.call()
                    KeptCopyStore.prune(datasetDir)
                    pruneReads(datasetDir, readSeqId)
                    return true
                })
            }
            boolean rolling = isRolling(dataset)
            // A composite idField keys the copy by a derived _copyId written into each record (ruling 3b1-3); the manifest
            // keeps the dataset's own idField list so serving matches it. A single field is left alone.
            Iterator<Map> source = new RecordFileIterator(file)
            RecordFileIterator records = (RecordFileIterator) source
            List<String> idParts = idFields(dataset.idField as String)
            if (idParts.size() > 1) source = withCopyId(records, idParts, idCheck)
            Map counts
            try {
                counts = full ?
                        KeptCopyStore.replace(datasetDir, source, dataset.idField as String, dataset.stampField as String,
                                dataset.windowField as String, fromStamp, readThru, readAt, (dataset.marginDays ?: 2) as int,
                                swapAndPrune, !rolling) :
                        KeptCopyStore.merge(datasetDir, source, dataset.idField as String, dataset.stampField as String,
                                readThru, readAt, swapAndPrune)
            } catch (IllegalStateException e) {
                if (e.message?.contains("the swap was refused")) throw new IllegalStateException(LOCK_TAKEN_OVER)
                throw e
            } finally {
                records.close()
            }
            Map manifest = KeptCopyStore.manifest(datasetDir)
            return [readAt      : readAt, version: counts.version, recordCount: manifest?.recordCount,
                    recordsRead : counts.recordsRead ?: 0, changedCount: counts.changedCount ?: 0,
                    addedCount  : counts.addedCount ?: 0, removedCount: counts.removedCount ?: 0,
                    missCount   : rolling ? null : (counts.missCount ?: 0)]
        } catch (Throwable t) {
            logger.warn("Dataset ${dataset?.datasetId} read ${readSeqId} failed", t)
            return [error: t.message ?: t.toString()]
        }
    }

    /** {@code records} with {@link KeptCopyStore#COPY_ID} set on each record from its {@code idParts} values. */
    private static Iterator<Map> withCopyId(Iterator<Map> records, List<String> idParts, CopyIdCheck check) {
        return new Iterator<Map>() {
            boolean hasNext() { return records.hasNext() }

            Map next() {
                Map r = records.next()
                String id = copyId(r, idParts)
                check.see(r, idParts, id)
                r.put(KeptCopyStore.COPY_ID, id)
                return r
            }
        }
    }

    /**
     * What a read saw of its composite ids: records with a missing or blank id part, and copy ids seen twice. Such a record
     * would collapse into another one in the copy, so {@link #assertClean} fails the read instead (a configuration fault: the
     * extractor does not supply a declared id field, or the fields do not identify a record).
     */
    static class CopyIdCheck {
        long missing = 0
        long duplicates = 0
        String firstMissing
        String firstDuplicate
        private final Set<String> seen = new HashSet<String>()

        void see(Map record, List<String> idParts, String id) {
            if (idParts.any { String f -> record.get(f) == null || String.valueOf(record.get(f)).trim().isEmpty() }) {
                if (missing++ == 0) firstMissing = idParts.collect { String f -> "${f}=${record.get(f)}".toString() }.join(", ")
            } else if (!seen.add(id)) {
                if (duplicates++ == 0) firstDuplicate = id
            }
        }

        void assertClean(String idField) {
            if (missing > 0) {
                throw new IllegalStateException(("${missing} records lack a value for part of the id ${idField}; the first has " +
                        "${firstMissing}. The extractor must supply every declared id field").toString())
            }
            if (duplicates > 0) {
                throw new IllegalStateException(("${duplicates} records repeat a copy id of ${idField}; the first repeated id is " +
                        "${firstDuplicate}. These fields do not identify a record").toString())
            }
        }
    }

    /**
     * The extract parameters, built the way the interactive path builds them (RunExtractSupport.buildExtractParams: the
     * connector's parameter names, ISO instants, preserveWindowInstants), with the read's own folder and window field, no
     * filters and no keepFields, and the dataset's options as a run passes its stored ones.
     */
    static Map<String, Object> extractParams(Map<String, Object> connector, Map dataset, String location, String readSeqId,
                                             String windowFieldName, Timestamp fromStamp, Timestamp readThru, String timeZone) {
        Map<String, Object> params = RunExtractSupport.buildExtractParams(connector, ReconciliationSavedRunSupport.FILE_SIDE_1,
                dataset.sourceConfigId as String, [location: location, runToken: "read-${readSeqId}".toString()],
                fromStamp, readThru, "dataset-read.json", null, null, null, timeZone)
        params.outputLocation = location
        params.fileName = "read-${readSeqId}.json".toString()
        params.windowFieldName = windowFieldName
        // the trusted owner tenant, as the automation path sends it: the extractor checks its config against this tenant
        params.companyUserGroupId = dataset.companyUserGroupId
        SourceExtractOptionsSupport.mergeUnder(params, SourceExtractOptionsSupport.runtimeOptions(connector, dataset.optionsJson))
        return params
    }

    /**
     * The real extractor: the connector's extract service, called as the current user (no disableAuthz). The scan runs it
     * under the job's anonymous-all grant. {@link #read} has already checked the service is installed and takes the window
     * field ({@link #datasetConnector}). A service that throws leaves its error on the message facade and answers an empty
     * map (DAR-BE-130), so those errors are taken off and thrown here.
     */
    static Closure liveExtractor(def ec) {
        return { String serviceName, Map params ->
            int errorsBefore = ((ec.message.getErrors() ?: []) as List).size()
            Map result = (ec.service.sync().name(serviceName).parameters(params.findAll { it.value != null }).call() ?: [:]) as Map
            List added = ((ec.message.getErrors() ?: []) as List).drop(errorsBefore)
            if (added) {
                ec.message.clearErrors()
                throw new IllegalStateException(added.collect { it.toString() }.join("; "))
            }
            return result
        }
    }

    /** Deletes every read folder but this one, after it merged: the last read's raw file stays for a look, no more. */
    static void pruneReads(File datasetDir, String keepReadSeqId) {
        new File(datasetDir, READS_FOLDER).listFiles()?.each { File f ->
            if (f.isDirectory() && f.name != keepReadSeqId) f.deleteDir()
        }
    }

    private static File resolveFile(def ec, String location) {
        if (!location) return null
        if (location.startsWith("/")) return new File(location)
        try {
            return ec.resource.getLocationReference(location)?.getFile()
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Streams the records array of a {metadata, records} file (or a bare array), one record in memory at a time, for
     * KeptCopyStore's iterator arguments. Fields other than the array are skipped. A malformed file throws from
     * hasNext/next, which fails the read.
     */
    static class RecordFileIterator implements Iterator<Map>, Closeable {
        private final JsonParser parser
        private Map nextRecord
        private boolean inArray
        private boolean done

        RecordFileIterator(File file) {
            parser = MAPPER.getFactory().createParser(file)
            JsonToken first = parser.nextToken()
            if (first == JsonToken.START_ARRAY) {
                inArray = true
            } else if (first == JsonToken.START_OBJECT) {
                while (!inArray && parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.getCurrentName()
                    JsonToken value = parser.nextToken()
                    if (name == "records" && value == JsonToken.START_ARRAY) inArray = true
                    else parser.skipChildren()
                }
            }
            if (!inArray) done = true
        }

        boolean hasNext() {
            if (nextRecord != null) return true
            while (!done) {
                JsonToken t = parser.nextToken()
                if (t == null) throw new IllegalStateException("The read's file ends inside its records")
                if (t == JsonToken.END_ARRAY) { done = true; break }
                Object element = MAPPER.readValue(parser, Object)
                if (element instanceof Map) { nextRecord = (Map) element; return true }
            }
            return false
        }

        Map next() {
            if (!hasNext()) throw new NoSuchElementException()
            Map r = nextRecord
            nextRecord = null
            return r
        }

        void close() { parser.close() }
    }
}
