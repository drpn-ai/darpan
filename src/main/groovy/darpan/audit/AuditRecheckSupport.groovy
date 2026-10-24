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
 * DAR-BE-119. An open finding older than the run's read is read again BY KEY on both sides (a key read on one side only
 * would report the other missing) and appended to each side's extract and sidecar before the compare, so it closes or
 * is concluded again from today's facts. Only keys the read does not already hold; a failure skips it with a warning.
 */
class AuditRecheckSupport {

    private static final Logger logger = LoggerFactory.getLogger(AuditRecheckSupport.class)

    /** At most this many keys a window re-reads (the oldest open findings beyond it wait for a later run). */
    static final int MAX_KEYS = 1000

    /** sides: [[source, result]] for FILE_1 and FILE_2. readByKey(source, keys) runs the side's extractor by key. */
    static void appendOpenFindings(def ec, def automation, List<Map> checks, Map window, List<Map> sides, Closure readByKey) {
        if (!checks || window?.auditRecentReadStartDate == null || !window?.auditPeriod) return
        List<Map> live = sides.findAll { it.source != null }
        if (!live || live.any { !it.result?.fileLocation } || !live.any { AuditRecentReadSupport.readsRecentOnly(normalize(readField(it.source, "systemEnumId"))) }) return
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        Set<String> open = firstKeys(openRecordKeys(ec, tenantId, checks*.checkId, window.auditPeriod as String))
        if (!open) return
        String scopeId = normalize(readField(automation, "compareScopeId"))
        try {
            List<Map> plan = live.collect { Map side ->
                String keyField = firstKeyField(ec, scopeId, normalize(readField(side.source, "fileSide")))
                File file = RunVerificationSupport.resolveLocationFile(ec, side.result.fileLocation as String)
                if (!keyField || file == null) throw new IllegalStateException("its ${readField(side.source, 'fileSide')} key cannot be read")
                [side: side, keyField: keyField, file: file, missing: missingKeys(file, keyField, open)]
            }
            // A key either side lacks is read again on both, so the pair is judged together.
            List<String> keys = (plan.collectMany { it.missing as Set } as Set).toList().sort().take(MAX_KEYS)
            if (!keys) return
            List<File> read = plan.collect { Map p ->
                Map out = (Map) readByKey.call(p.side.source, keys)
                File f = RunVerificationSupport.resolveLocationFile(ec, normalize(out?.fileLocation))
                if (f == null) throw new IllegalStateException("the ${readField(p.side.source, 'fileSide')} read by key wrote no file")
                f
            }
            [plan, read].transpose().each { List pair ->
                Map p = (Map) pair[0]
                Map result = (Map) ((Map) p.side).result
                result.recordCount = ((result.recordCount ?: 0) as int) +
                        append((File) p.file, (File) pair[1], (String) p.keyField, ((keys as Set) - (Set) p.missing) as Set<String>)
            }
            logger.info("Audit re-check (${window.auditPeriod}): ${keys.size()} open finding key(s) beyond the recent read, read again by key")
        } catch (Exception e) {
            logger.warn("Audit re-check of older open findings skipped: ${e.message}")
        }
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

    private static List<String> openRecordKeys(def ec, String tenantId, List checkIds, String period) {
        return TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditFinding")
                .condition("companyUserGroupId", tenantId).condition("checkId", EntityCondition.IN, checkIds)
                .condition("period", period).condition("stateEnumId", EntityCondition.IN, ["AUDFS_OPEN", "AUDFS_PENDING"])
                .selectField("recordKey").useCache(false).list()*.recordKey.collect { it as String }
    }

    /** The side's first compare key field as a record field, as the compare keys it. */
    static String firstKeyField(def ec, String compareScopeId, String fileSide) {
        Map key = RunConclusionStep.keySpecFor(ec, compareScopeId, fileSide)
        if (!key.keyReliable || key.idNormalizer) return null
        return ConclusionKeySupport.plainField(((List<String>) key.keyFields)[0])
    }
}
