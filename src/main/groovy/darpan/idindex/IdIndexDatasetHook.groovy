package darpan.idindex

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-374: after a good read of any dataset whose system the ID index reads, index that read's file and record the outcome
 * on the dataset's cursor. Never throws: an index failure is a FAILED cursor (the nightly catch-up indexes the version again),
 * never a failed read. Rows are only added or touched, so a kept copy trimmed to the fields runs keep is safe to index.
 */
class IdIndexDatasetHook {

    private static final Logger logger = LoggerFactory.getLogger(IdIndexDatasetHook)
    static final String CURSOR = "darpan.idindex.RecordIdIndexCursor"

    /** dataset is the row as it stood before the read (its currentVersion is the previous version). */
    static Map afterRead(def ec, Map dataset, String readSeqId, String readFile, String version, String readKindEnumId) {
        if (!readFile) return [skipped: true]
        Map source = IdIndexCatalog.sources(ec).find { it.systemEnumId == dataset?.systemEnumId }
        if (source == null) return [skipped: true]
        try {
            File original = new File(readFile)
            File lines = IdIndexer.toJsonLines(original)
            Map out
            try {
                out = IdIndexer.indexFile(ec, dataset.companyUserGroupId as String, source, dataset.sourceConfigId as String, lines,
                        IdIndexRunHook.kindHint(ec, source, dataset.sourceConfigId as String), datasetLive(ec, dataset))
            } finally {
                if (lines != original) lines.delete()
            }
            Map cursor = [lastIndexedReadSeqId: readSeqId, lastIndexedAt: ec.user.nowTimestamp, rowsLastPass: out.rows, lastStatus: "OK", lastError: null]
            // An incremental read holds only what changed, so the new version counts as indexed only after a full read or when
            // the version it merged into was indexed; otherwise the nightly catch-up indexes the whole copy.
            def prior = ec.entity.find(CURSOR).condition("companyUserGroupId", dataset.companyUserGroupId).condition("datasetId", dataset.datasetId)
                    .useCache(false).disableAuthz().one()
            if (version && (readKindEnumId == "DRK_FULL" || (prior?.indexedVersion && prior.indexedVersion == dataset.currentVersion))) {
                cursor.indexedVersion = version
            }
            writeCursor(ec, dataset, cursor)
            return out
        } catch (Throwable t) {
            logger.warn("ID index of dataset ${dataset.datasetId} read ${readSeqId} failed", t)
            recordFailure(ec, dataset, t)
            return [error: t.message ?: t.toString()]
        }
    }

    static void recordFailure(def ec, Map dataset, Throwable t) {
        String error = t.message ?: t.toString()
        try {
            writeCursor(ec, dataset, [lastStatus: "FAILED", lastError: error.length() > 4000 ? error.substring(0, 4000) : error])
        } catch (Throwable inner) {
            logger.warn("Could not record the ID index failure of dataset ${dataset.datasetId}", inner)
        }
    }

    /**
     * Upserts the dataset's cursor row in its own transaction; returns whether it wrote. OC92: not for a dataset that is gone
     * (drop#ConnectionKeptData took it while this pass ran), checked under the dataset's row lock, so a drop never finds its
     * cursor written back.
     */
    static boolean writeCursor(def ec, Map dataset, Map fields) {
        return (boolean) ec.transaction.runRequireNew(null, "ID index cursor write failed") {
            boolean was = ec.artifactExecution.disableAuthz()
            try {
                if (!datasetStillThere(ec, dataset)) return false
                ec.entity.makeValue(CURSOR).setAll([companyUserGroupId: dataset.companyUserGroupId, datasetId: dataset.datasetId,
                        systemEnumId: dataset.systemEnumId, connectionId: dataset.sourceConfigId] + fields).createOrUpdate()
                return true
            } finally {
                if (!was) ec.artifactExecution.enableAuthz()
            }
        }
    }

    /**
     * OC92: whether the dataset is still there, read for update: drop#ConnectionKeptData locks the same row, so a drop and an
     * index batch or cursor write cannot interleave. The tenant is in the WHERE beside datasetId (a list, not one(): Moqui
     * drops a condition beside a full key).
     */
    static boolean datasetStillThere(def ec, Map dataset) {
        return !TenantScopedFinder.findGlobalUnscoped(ec, "darpan.dataset.Dataset",
                        "OC92: a background index pass (no user) re-checks its dataset still exists, under the drop's row lock")
                .condition("companyUserGroupId", dataset.companyUserGroupId).condition("datasetId", dataset.datasetId)
                .forUpdate(true).useCache(false).list().isEmpty()
    }

    /** The per-batch check IdIndexer.indexFile asks while it indexes this dataset (OC92). */
    static Closure<Boolean> datasetLive(def ec, Map dataset) {
        return { -> datasetStillThere(ec, dataset) }
    }
}
