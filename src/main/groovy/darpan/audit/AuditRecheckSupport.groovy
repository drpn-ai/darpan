package darpan.audit

import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunConclusionStep
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.JsonRecordStream
import groovy.json.JsonOutput
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-119. An open finding older than the run's read is read again BY KEY and appended to each side's extract and
 * sidecar before the compare, so it closes or is concluded again from today's facts. DAR-BE-156: each side reads only
 * the keys it does not already hold, within the check's own window (what a full read would see, restricted to the keys),
 * oldest finding first, every key in batches of MAX_KEYS; a re-check that cannot finish is reported on both sides' reads
 * (recheckFailure), so the check is partial and says why, never a log line under a check that reads "ran".
 */
class AuditRecheckSupport {

    private static final Logger logger = LoggerFactory.getLogger(AuditRecheckSupport.class)

    /** At most this many keys one read by key asks for; a re-check reads every missing key, this many at a time. */
    static final int MAX_KEYS = 1000

    /**
     * sides: [[source, result]] for FILE_1 and FILE_2. readByKey(source, keys) runs the side's extractor by key. Answers what
     * it read ([keys: side -> count, records: side -> count]), or null when there was nothing to re-check.
     */
    static Map appendOpenFindings(def ec, def automation, List<Map> checks, Map window, List<Map> sides, Closure readByKey) {
        if (!checks || window?.auditRecentReadStartDate == null || !window?.auditPeriod) return null
        List<Map> live = sides.findAll { it.source != null }
        if (!live || live.any { !it.result?.fileLocation } || !live.any { AuditRecentReadSupport.sourceReadsRecentOnly(ec, it.source) }) return null
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        List<String> ordered = oldestFirstKeys(openFindings(ec, tenantId, checks*.checkId, window.auditPeriod as String))
        Set<String> open = ordered as Set<String>
        if (!open) return null
        String scopeId = normalize(readField(automation, "compareScopeId"))
        List<Map> plan
        try {
            plan = live.collect { Map side ->
                String fileSide = normalize(readField(side.source, "fileSide"))
                [side: side, fileSide: fileSide, keyField: firstKeyField(ec, scopeId, fileSide),
                 file: RunVerificationSupport.resolveLocationFile(ec, side.result.fileLocation as String)]
            }
        } catch (Exception e) {
            String reason = "Older findings were not re-checked: ${e.message}".toString()
            ((Map) live[0].result).recheckFailure = reason
            logger.warn("Audit re-check (${window.auditPeriod}) failed: ${e.message}")
            return [keys: [:], records: [:], failure: reason]
        }
        Map outcome = readAndAppend(plan, ordered) { Map side, List<String> keys ->
            Map out = (Map) readByKey.call(side.source, keys)
            RunVerificationSupport.resolveLocationFile(ec, normalize(out?.fileLocation))
        }
        if (outcome.failure) logger.warn("Audit re-check (${window.auditPeriod}) failed after ${outcome.keys}: ${outcome.failure}")
        else logger.info("Audit re-check (${window.auditPeriod}): ${open.size()} open finding key(s); read again by key ${outcome.keys}, " +
                "records added ${outcome.records}, in ${outcome.millis} ms")
        return outcome
    }

    /**
     * plan: one [side: [source, result], fileSide, keyField, file] per side. Each side is asked only for the keys of `ordered`
     * its extract lacks (readSide(side, keys) answers the file it wrote), MAX_KEYS at a time; only when every read finished
     * is anything appended, so a failed side never leaves the other's records to be judged alone. A failure is set on the
     * failing side's result as recheckFailure and answered as failure.
     */
    static Map readAndAppend(List<Map> plan, List<String> ordered, Closure readSide) {
        Set<String> open = ordered as Set<String>
        Map<String, Integer> keysRead = [:], recordsAdded = [:]
        long started = System.currentTimeMillis()
        Map failing = plan[0]
        try {
            plan.each { Map p ->
                failing = p
                if (!p.keyField || p.file == null) throw new IllegalStateException("its ${p.fileSide} key cannot be read")
                // A side that already holds a key has today's record for it: only the keys it lacks are asked of it.
                p.read = batches(ordered, missingKeys((File) p.file, (String) p.keyField, open)).collect { List<String> keys ->
                    File f = (File) readSide.call(p.side, keys)
                    if (f == null) throw new IllegalStateException("the ${p.fileSide} read by key wrote no file")
                    keysRead[(String) p.fileSide] = (keysRead[(String) p.fileSide] ?: 0) + keys.size()
                    f
                }
            }
            plan.each { Map p ->
                Map result = (Map) ((Map) p.side).result
                ((List<File>) p.read).each { File f ->
                    int added = append((File) p.file, f, (String) p.keyField, [] as Set<String>)
                    result.recordCount = ((result.recordCount ?: 0) as int) + added
                    recordsAdded[(String) p.fileSide] = (recordsAdded[(String) p.fileSide] ?: 0) + added
                }
            }
            return [keys: keysRead, records: recordsAdded, millis: System.currentTimeMillis() - started]
        } catch (Exception e) {
            String reason = "Older findings were not re-checked: ${e.message}".toString()
            ((Map) ((Map) failing.side).result).recheckFailure = reason
            return [keys: keysRead, records: recordsAdded, millis: System.currentTimeMillis() - started, failure: reason]
        }
    }

    /** DAR-BE-156. The findings' first keys, oldest firstFoundDate first (then by key), each once. */
    static List<String> oldestFirstKeys(List<Map> findings) {
        return (findings ?: []).sort(false) { Map a, Map b ->
            (a.firstFoundDate as java.util.Date) <=> (b.firstFoundDate as java.util.Date) ?: (a.recordKey as String) <=> (b.recordKey as String)
        }.collect { ConclusionKeySupport.firstField(it.recordKey as String) }.findAll { it }.unique()
    }

    /** DAR-BE-156. Of `ordered`, the keys `missing` names, in order, MAX_KEYS a batch: every one is read, none dropped. */
    static List<List<String>> batches(List<String> ordered, Set<String> missing) {
        return (ordered ?: []).findAll { missing.contains(it) }.collate(MAX_KEYS)
    }

    /** The first field of each compare key (FIRST_FIELD keyScope's own split). */
    static Set<String> firstKeys(Collection<String> recordKeys) {
        return (recordKeys ?: []).collect { ConclusionKeySupport.firstField(it) }.findAll { it } as Set<String>
    }

    /** Of `wanted`, the keys no record of `extract` holds in keyField. Streams the file. */
    static Set<String> missingKeys(File extract, String keyField, Set<String> wanted) {
        Set<String> present = new HashSet<>()
        JsonRecordStream.forEachRecord(extract, "records") { Map r ->
            String v = normalize(r.get(keyField))
            if (v && wanted.contains(v)) present.add(v)
        }
        ExcludedRecordsSidecar.forEachRecord(extract) { Map r ->
            String v = normalize(r.get(keyField))
            if (v && wanted.contains(v)) present.add(v)
        }
        return (wanted - present) as Set<String>
    }

    /**
     * Appends byKey's records to extract (its other fields kept) and byKey's sidecar to extract's, leaving out the keys
     * extract already holds (`already`: read on this side by the recent read, asked again only for the other side).
     */
    static int append(File extract, File byKey, String keyField, Set<String> already) {
        File merged = new File(extract.parentFile, "${extract.name}.recheck")
        int added = 0
        merged.withWriter("UTF-8") { Writer w ->
            w.write("{\"records\":[")
            boolean first = true
            Closure write = { Map r -> if (!first) w.write(","); w.write(JsonOutput.toJson(r)); first = false }
            Map head = JsonRecordStream.forEachRecord(extract, "records", write)
            JsonRecordStream.forEachRecord(byKey, "records") { Map r ->
                if (already.contains(normalize(r.get(keyField)))) return
                write(r); added++
            }
            w.write("]")
            head.findAll { k, v -> k != "records" && k != "truncated" }.each { k, v -> w.write(",${JsonOutput.toJson(k)}:${JsonOutput.toJson(v)}") }
            w.write("}")
        }
        if (!merged.renameTo(extract)) { extract.delete(); merged.renameTo(extract) }
        mergeSidecars(extract, byKey, keyField, already)
        return added
    }

    private static void mergeSidecars(File extract, File byKey, String keyField, Set<String> already) {
        Map collector = ExcludedRecordsSidecar.newCollector()
        int fromByKey = 0
        Closure keep = { Map r ->
            List<String> by = (r.remove("_excludedBy")?.toString() ?: ":").split(":", 2) as List<String>
            ExcludedRecordsSidecar.collect(collector, r, [sequenceNum: by[0], fieldExpression: by.size() > 1 ? by[1] : null])
        }
        ExcludedRecordsSidecar.forEachRecord(byKey) { Map r ->
            if (already.contains(normalize(r.get(keyField)))) return
            keep(r); fromByKey++
        }
        if (fromByKey == 0) { ExcludedRecordsSidecar.discard(collector); return }
        ExcludedRecordsSidecar.forEachRecord(extract, keep)
        ExcludedRecordsSidecar.write(new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name)), collector, extract.name)
    }

    private static List<Map> openFindings(def ec, String tenantId, List checkIds, String period) {
        return TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditFinding")
                .condition("companyUserGroupId", tenantId).condition("checkId", EntityCondition.IN, checkIds)
                .condition("period", period).condition("stateEnumId", EntityCondition.IN, ["AUDFS_OPEN", "AUDFS_PENDING"])
                .selectFields(["recordKey", "firstFoundDate"]).useCache(false).list()
                .collect { [recordKey: it.recordKey as String, firstFoundDate: it.firstFoundDate] }
    }

    /** The side's first compare key field as a record field, as the compare keys it. */
    static String firstKeyField(def ec, String compareScopeId, String fileSide) {
        Map key = RunConclusionStep.keySpecFor(ec, compareScopeId, fileSide)
        if (!key.keyReliable || key.idNormalizer) return null
        return ConclusionKeySupport.plainField(((List<String>) key.keyFields)[0])
    }
}
