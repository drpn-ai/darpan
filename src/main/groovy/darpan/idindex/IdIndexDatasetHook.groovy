package darpan.idindex

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
                        IdIndexRunHook.kindHint(ec, source, dataset.sourceConfigId as String))
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

    /** Upserts the dataset's cursor row in its own transaction. */
    static void writeCursor(def ec, Map dataset, Map fields) {
        ec.transaction.runRequireNew(null, "ID index cursor write failed") {
            boolean was = ec.artifactExecution.disableAuthz()
            try {
                ec.entity.makeValue(CURSOR).setAll([companyUserGroupId: dataset.companyUserGroupId, datasetId: dataset.datasetId,
                        systemEnumId: dataset.systemEnumId, connectionId: dataset.sourceConfigId] + fields).createOrUpdate()
            } finally {
                if (!was) ec.artifactExecution.enableAuthz()
            }
        }
    }
}
