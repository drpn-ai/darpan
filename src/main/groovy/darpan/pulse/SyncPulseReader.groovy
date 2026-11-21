package darpan.pulse

import darpan.audit.AuditRecheckSupport
import darpan.dataset.KeptCopyStore
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunConclusionStep
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepStepSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-328 plan 3. The sync pulse's two reads of a pair's daily check (its automation): the upstream side for a short
 * orderDate window, with the check's own filters, and the target side asked by key which of those records it holds.
 *
 * <p>Both go through the check's own extractor dispatch (AutomationExecutionSupport.callConfiguredSourceExtractor: the
 * connector registry, the allowlist, window parameter names, the side's filters, readFields, the kept copy), so a pulse
 * reads exactly what the check's run would read, only narrower. The keys are the check's own join keys, paired as
 * AutomationExecutionSupport.readByOtherSideKeys pairs them: the upstream side's first compare key field
 * (AuditRecheckSupport.firstKeyField) holds the value the target side's first compare key field is read by.</p>
 *
 * <p>DAR-BE-318 (plan 3b-2b, Ruling 3b2b-11): a side keeps what the check's run keeps. The dispatch decides through the
 * shared gate (AutomationExecutionSupport.sideKeepOf, which asks KeepPlanSupport.effectiveSideKeep) whether the side filters at the read,
 * as every side does until its extract stops taking the filter parameter, or keeps in Spark; there the pulse runs the same
 * Keep over its own extract (KeepStepSupport.keptValues, which writes the set-aside artifact beside it) and reads only the
 * kept records. A record Keep set aside is neither an upstream record nor present in the target, as when the read dropped it.</p>
 *
 * <p>Neither read throws: every failure is [ok: false, errors]. Each writes into its own folder,
 * sync-pulse/&lt;tenant&gt;/&lt;syncPairId&gt;/&lt;stamp&gt; under the data manager, removed before it answers. NetSuite reads take
 * their turn in NetSuiteReadGate inside the NetSuite extract itself; HotWax reads keep their peak share there too. A
 * reader only reads: what is waiting or late is the pulse engine's question (SyncPulseSupport).</p>
 *
 * <p>A pulse runs on a bare thread (RunAdmission) that already holds an anonymous login with the artifact authorized, the
 * way execute#Automation's scheduled reads run; the reader signs in and elevates nothing itself. A caller with a user reads
 * as that user.</p>
 */
class SyncPulseReader {
    private static final Logger logger = LoggerFactory.getLogger(SyncPulseReader)

    static final String PULSE_FOLDER = "sync-pulse"
    /** The record field a pulse dates an upstream record by. */
    static final String CREATED_FIELD = "orderDate"
    static final String NAME_FIELD = "orderName"
    /** The keys a NetSuite read by key accepts (NsSuiteQlOrderSupport.lookupKeyList; they reach SuiteQL by interpolation). */
    static final String KEY_PATTERN = /[A-Za-z0-9#_.\-]{1,64}/
    /** Bookkeeping only: a read by key sends no lookupWithinWindow, so the target ignores this window. */
    static final Duration TARGET_WINDOW = Duration.ofDays(1)

    /**
     * Seam for tests: a live extractor Closure(serviceName, serviceParams), handed to callConfiguredSourceExtractor as its
     * own liveExtractor. Null in production, which calls the extract service.
     */
    private static volatile Closure liveExtractor = null

    static void setLiveExtractor(Closure extractor) { liveExtractor = extractor }

    static void resetHooks() { liveExtractor = null }

    /** DAR-OPS-026: clears the live-extractor seam. Test-only. */
    static void resetForTests() { resetHooks() }

    /**
     * The upstream side of the pair's check for [from, thru) on its window field, with the side's filters.
     *
     * @return [ok, records: [[key, orderName, createdAt (Instant), otherKey]], errors, warnings]; key is the upstream
     *         side's join key value, otherKey the value the target side is read by (the same value: the check joins on it)
     */
    static Map readUpstream(def ec, Map pair, Map automation, Instant from, Instant thru) {
        return run(ec, pair, automation, "upstream", [records: []]) { Map sides, String location ->
            if (from == null || thru == null || !from.isBefore(thru)) throw new IllegalArgumentException("A pulse reads a window that ends after it starts.")
            if (AutomationExecutionSupport.isStateWindowMode(automation)) throw new IllegalStateException("A pulse reads a window; this check reads by status.")
            Map window = [childWindowStartDate: Timestamp.from(from), childWindowEndDate: Timestamp.from(thru)]
            File file = extract(ec, automation, sides.upstream, window, location, null, sides.upstreamKey as String, false).file
            Map read = upstreamRecords(file, sides.upstreamKey as String)
            return [ok: true, records: read.records, errors: [], warnings: read.warnings]
        }
    }

    /**
     * Which of keys the pair's target side holds, read by key AuditRecheckSupport.MAX_KEYS at a time. A key NetSuite would
     * refuse (KEY_PATTERN) is skipped with one warning, never sent, and comes back in unasked: it is neither present nor
     * absent, so the pulse engine must not count it as waiting or past its allowance. unasked is answered on a failed
     * read too.
     *
     * @return [ok, present: Set of keys the target holds, unasked: Set of keys never asked, errors, warnings]
     */
    static Map presentInTarget(def ec, Map pair, Map automation, Collection<String> keys) {
        Set<String> unasked = (validKeys(keys).invalid as Set<String>)
        if (!keys) return [ok: true, present: [] as Set<String>, unasked: unasked, errors: [], warnings: []]
        return run(ec, pair, automation, "target", [present: [] as Set<String>, unasked: unasked]) { Map sides, String location ->
            Timestamp now = (ec.user?.nowTimestamp as Timestamp) ?: new Timestamp(System.currentTimeMillis())
            Map window = [childWindowStartDate: Timestamp.from(now.toInstant().minus(TARGET_WINDOW)), childWindowEndDate: now]
            Map found = presentByBatches(keys, sides.targetKey as String) { List<String> batch ->
                extract(ec, automation, sides.target, window, location, batch, sides.targetKey as String, true)
            }
            return [ok: true, present: found.present, unasked: found.unasked, errors: [], warnings: found.warnings]
        }
    }

    // ------------------------------------------------------------------------------------------------- pure parts

    /**
     * The records of an upstream extract file: key from keyField, createdAt from orderDate, each key once (the first). A
     * record with no key, or no orderDate that reads as an instant, is skipped, each kind with one warning. No file: none.
     */
    static Map upstreamRecords(File file, String keyField) {
        List<Map> records = []
        Set<String> seen = new HashSet<String>()
        int noKey = 0, noDate = 0
        if (file != null && file.exists()) forEachPresentRecord(file) { Map r ->
            String key = normalize(r.get(keyField))
            if (!key) { noKey++; return }
            Instant createdAt = createdAt(r.get(CREATED_FIELD))
            if (createdAt == null) { noDate++; return }
            if (!seen.add(key)) return
            records << [key: key, orderName: normalize(r.get(NAME_FIELD)), createdAt: createdAt, otherKey: key]
        }
        List<String> warnings = []
        if (noKey) warnings << "${noKey} upstream record(s) skipped: no ${keyField}.".toString()
        if (noDate) warnings << "${noDate} upstream record(s) skipped: no ${CREATED_FIELD} that reads as a date and time.".toString()
        return [records: records, warnings: warnings]
    }

    /**
     * An orderDate as an instant, as KeptCopyStore.parseInstant reads a record's date: epoch milliseconds as a Long or
     * BigInteger (JSON reads every epoch-ms date after January 1970 as one) or as text of 13+ digits, else ISO-8601 text.
     * Any other number (a small Integer, a decimal) or a shorter digit string is no date: null.
     */
    static Instant createdAt(Object value) {
        if (value instanceof Long || value instanceof BigInteger) return KeptCopyStore.parseInstant(value)
        if (value instanceof Number) return null
        String s = normalize(value)
        if (s ==~ /\d+/) return s.length() >= 13 ? Instant.ofEpochMilli(Long.parseLong(s)) : null
        return KeptCopyStore.parseInstant(s)
    }

    /** keys split into those a read by key accepts (each once, in order) and those it would refuse. */
    static Map validKeys(Collection keys) {
        LinkedHashSet<String> valid = new LinkedHashSet<String>()
        List<String> invalid = []
        (keys ?: []).each { Object k ->
            String key = normalize(k)
            if (!key) return
            if (key ==~ KEY_PATTERN) valid.add(key)
            else invalid << key
        }
        return [valid: valid as List<String>, invalid: invalid]
    }

    /**
     * Asks readBatch(batch) -> File (the target's records for those keys, or null for none), or [file, kept] with the kept keys
     * already read off it, AuditRecheckSupport.MAX_KEYS valid keys at a time; a key is present when a record's keyField holds it. Invalid keys are never sent: they come back
     * as unasked.
     */
    static Map presentByBatches(Collection keys, String keyField, Closure readBatch) {
        Map checked = validKeys(keys)
        List<String> valid = (List<String>) checked.valid
        List<String> warnings = []
        if (checked.invalid) {
            List<String> invalid = (List<String>) checked.invalid
            warnings << ("${invalid.size()} key(s) not asked for: a key holds letters, digits, # _ . - only (up to 64), e.g. " +
                    "${invalid.take(3).join(', ')}").toString()
        }
        Set<String> asked = valid as Set<String>
        Set<String> present = new LinkedHashSet<String>()
        valid.collate(AuditRecheckSupport.MAX_KEYS).each { List<String> batch ->
            // a File, or [file, kept] where the read already holds the kept keys (KeepStepSupport.keptValues), not read again
            def read = readBatch.call(batch)
            File file = (read instanceof Map ? ((Map) read).get("file") : read) as File
            Set<String> kept = read instanceof Map ? (Set<String>) ((Map) read).get("kept") : null
            if (file == null || !file.exists()) return
            try {
                if (kept != null) {
                    kept.each { String v -> if (asked.contains(v)) present.add(v) }
                } else {
                    forEachPresentRecord(file) { Map r ->
                        String v = normalize(r.get(keyField))
                        if (v && asked.contains(v)) present.add(v)
                    }
                }
            } finally {
                // one batch's file at a time: a read by key may name every batch's file alike, so what a Keep wrote beside
                // it (its summary, rows and side file) goes too, or the next batch's Keep would take it for its own
                file.delete()
                if (KeepArtifacts.keepFile(file).delete()) {
                    KeepArtifacts.rowsFile(file).delete()
                    new File(file.parentFile, ExcludedRecordsSidecar.fileNameFor(file.name)).delete()
                }
            }
        }
        return [present: present, unasked: (checked.invalid as Set<String>), warnings: warnings]
    }

    /**
     * DAR-BE-318 (Ruling 3b2b-11): the records of an extract the check's run reads as present. Where Keep ran in Spark (a
     * .keep.json beside the file) the extract also holds the records Keep set aside, and only the kept ones are visited
     * (KeepArtifacts.forEachKeptRecord); with no .keep.json every record, as before.
     */
    static void forEachPresentRecord(File file, Closure visit) {
        if (KeepArtifacts.keepFile(file).isFile()) KeepArtifacts.forEachKeptRecord(file, visit)
        else JsonRecordStream.forEachRecord(file, "records", visit)
    }

    /**
     * work() answered, or [ok: false, errors] for anything it threw; folder is removed either way. Never throws.
     */
    static Map guarded(String what, File folder, Map empty, Closure<Map> work) {
        try {
            Map out = work.call()
            return new LinkedHashMap(empty) + (out ?: [:])
        } catch (Throwable t) {
            String reason = normalize(t.message) ?: t.class.simpleName
            logger.warn("Sync pulse ${what} read failed: ${reason}")
            return new LinkedHashMap(empty) + [ok: false, errors: ["The ${what} read failed: ${reason}".toString()], warnings: []]
        } finally {
            removeFolder(folder)
        }
    }

    // --------------------------------------------------------------------------------------------------- ec parts

    /**
     * signedIn(...), never throwing. The caller (RunAdmission's pool via SyncPulseSupport) already runs as an anonymous
     * login with the artifact authorized, so the reader signs in and elevates nothing itself.
     */
    private static Map run(def ec, Map pair, Map automation, String what, Map empty, Closure<Map> work) {
        try {
            return signedIn(ec, pair, automation, what, empty, work)
        } catch (Throwable t) {
            logger.warn("Sync pulse ${what} read failed: ${t.message}")
            return new LinkedHashMap(empty) + [ok: false, errors: ["The ${what} read failed: ${normalize(t.message) ?: t.class.simpleName}".toString()],
                                               warnings: []]
        }
    }

    /**
     * Resolves the pair's sides and keys and its folder, then runs work(sides, location) as the check's tenant, guarded.
     * Errors work leaves on the message facade fail the read and are cleared, so the caller's next service call runs.
     */
    private static Map signedIn(def ec, Map pair, Map automation, String what, Map empty, Closure<Map> work) {
        int errorsBefore = messageErrors(ec).size()
        String location = null
        File folder = null
        Map out
        try {
            location = folderLocation(ec, pair, what)
            folder = DataManagerSupport.resolveDirectoryFile(ec, location, false)
        } catch (Throwable t) {
            logger.warn("Sync pulse ${what} folder could not be resolved: ${t.message}")
        }
        if (folder == null) {
            out = new LinkedHashMap(empty) + [ok: false, errors: ["The ${what} read has no folder to write in.".toString()], warnings: []]
        } else {
            String tenantId = normalize(pair?.get("companyUserGroupId"))
            out = guarded(what, folder, empty) { ->
                (Map) TenantAccessSupport.withSystemTenant(tenantId) { -> work.call(sides(ec, pair, automation), location) }
            }
        }
        List added = messageErrors(ec).drop(errorsBefore)
        if (added) {
            ec.message.clearErrors()
            out.ok = false
            out.errors = ((out.errors ?: []) as List) + added.collect { normalize(it) }.findAll { it }
        }
        return out
    }

    /** The pair's upstream and target sources and their join key fields; throws what is wrong with the pair. */
    private static Map sides(def ec, Map pair, Map automation) {
        String tenantId = normalize(pair?.get("companyUserGroupId"))
        String automationId = normalize(readField(automation, "automationId"))
        if (!tenantId || tenantId != normalize(readField(automation, "companyUserGroupId"))) {
            throw new IllegalStateException("The sync pair and its check belong to different tenants.")
        }
        if (!automationId || automationId != normalize(pair.get("automationId"))) {
            throw new IllegalStateException("The sync pair names another check.")
        }
        String upstreamSide = normalize(pair.get("upstreamSide"))
        if (!(upstreamSide in [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2])) {
            throw new IllegalStateException("The sync pair's upstream side is FILE_1 or FILE_2, not ${upstreamSide}.")
        }
        String targetSide = upstreamSide == AutomationExecutionSupport.FILE_SIDE_1 ? AutomationExecutionSupport.FILE_SIDE_2 :
                AutomationExecutionSupport.FILE_SIDE_1
        Map<String, Object> sources = AutomationRuntimeSupport.loadAutomationSources(ec, automationId)
        def upstream = sources.get(upstreamSide), target = sources.get(targetSide)
        [(upstreamSide): upstream, (targetSide): target].each { String side, def source ->
            if (source == null) throw new IllegalStateException("The check has no ${side} source.")
            if (normalize(readField(source, "companyUserGroupId")) != tenantId) {
                throw new IllegalStateException("The check's ${side} source belongs to another tenant.")
            }
        }
        String scopeId = normalize(readField(automation, "compareScopeId"))
        Map upstreamSpec = RunConclusionStep.keySpecFor(ec, scopeId, upstreamSide)
        Map targetSpec = RunConclusionStep.keySpecFor(ec, scopeId, targetSide)
        String block = SyncPulseFacadeSupport.pulseBlockFor(automation, upstreamSpec, targetSpec)
        if (block) throw new IllegalStateException(block)
        String upstreamKey = ConclusionKeySupport.plainField(((List<String>) upstreamSpec.keyFields)[0])
        String targetKey = ConclusionKeySupport.plainField(((List<String>) targetSpec.keyFields)[0])
        if (!upstreamKey || !targetKey) throw new IllegalStateException(SyncPulseFacadeSupport.E_KEY)
        return [upstream: upstream, target: target, upstreamKey: upstreamKey, targetKey: targetKey]
    }

    /**
     * One read of source through the check's own dispatch, into location; lookupKeys reads by key. Answers the file it
     * wrote, or null when it found nothing and wrote none.
     *
     * <p>DAR-BE-318: the dispatch is told this caller's compare takes Keep as the run's does (KEEP_IN_SPARK, !evaluateMode), so a
     * side the shared gate puts in Spark is read as the check's run reads it (no filters, the Keep fields kept); the side's Keep
     * then runs over the file here and leaves its set-aside artifact beside it, read by forEachPresentRecord. Any other side
     * filters at the read as before and gets no artifact.</p>
     *
     * @return [file: the file it wrote or null, kept: the keyField values of the records Keep kept (every record's without a
     *         Keep run), or null when no Keep ran or the key is not asked for]
     */
    private static Map extract(def ec, Map automation, def source, Map window, String location, List<String> lookupKeys,
                               String keyField, boolean wantKept) {
        boolean keepInSpark = !AutomationExecutionSupport.isEvaluateScope(ec, automation)
        Map<String, Object> params = [automationExecutionId: null, sideOutputLocation: location,
                                      (AutomationExecutionSupport.KEEP_IN_SPARK): keepInSpark] as Map<String, Object>
        Map<String, Object> defaults = AutomationExecutionSupport.resolveSourceExtractorConfigDefaults(ec, automation, [source])
        if (defaults) params.sourceExtractorConfigDefaults = defaults
        if (lookupKeys != null) params.lookupKeys = lookupKeys
        Closure live = liveExtractor
        if (live != null) params.liveExtractor = live
        Map<String, Object> read = AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, source, window, params)
        Map<String, Object> result = AutomationExecutionSupport.normalizeSourceResult(read, source)
        File file = RunVerificationSupport.resolveLocationFile(ec, normalize(result.fileLocation))
        if (file == null || !file.exists()) {
            if (((result.recordCount ?: 0) as int) > 0) throw new IllegalStateException("${readField(source, 'fileSide')}'s read wrote no file.")
            return [file: null, kept: null]
        }
        // The plan the read was sent; a read served from a kept copy carries none, so it is worked out again, as the run does.
        Map sideKeep = AutomationExecutionSupport.sideKeepOfResult(ec, automation, source, read, params, keepInSpark)
        Set<String> kept = KeepStepSupport.applies(sideKeep) ? KeepStepSupport.keptValues(ec, file, sideKeep, keyField) : null
        return [file: file, kept: wantKept ? kept : null]
    }

    private static String folderLocation(def ec, Map pair, String what) {
        String tenant = DataManagerSupport.safeToken(pair?.get("companyUserGroupId"), "tenant")
        String syncPairId = DataManagerSupport.safeToken(pair?.get("syncPairId"), "pair")
        String stamp = "${DataManagerSupport.formatRunTimestamp(ec)}-${what}-${UUID.randomUUID().toString().take(8)}".toString()
        String base = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), PULSE_FOLDER)
        return DataManagerSupport.childLocation(DataManagerSupport.childLocation(DataManagerSupport.childLocation(base, tenant),
                syncPairId), stamp)
    }

    private static void removeFolder(File folder) {
        if (folder == null || !folder.exists()) return
        try {
            if (!folder.deleteDir()) logger.warn("Sync pulse folder ${folder} was not removed completely")
        } catch (Throwable t) {
            logger.warn("Sync pulse folder ${folder} could not be removed: ${t.message}")
        }
    }

    private static List messageErrors(def ec) {
        try {
            return new ArrayList((ec?.message?.getErrors() ?: []) as List)
        } catch (Throwable ignored) {
            return []
        }
    }
}
