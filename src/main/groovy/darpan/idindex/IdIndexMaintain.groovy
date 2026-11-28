package darpan.idindex

import darpan.config.DarpanSettings
import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.KeptCopyStore
import darpan.ops.ErrorChatSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.ZoneOffset

/**
 * DAR-BE-374: the nightly upkeep of the ID index, reading nothing outside Darpan. catchUp indexes every kept copy whose current
 * version the index has not (first load, a failed pass, a tenant field changed); sweep drops rows no read has seen for
 * darpan.idindex.months.
 */
class IdIndexMaintain {

    private static final Logger logger = LoggerFactory.getLogger(IdIndexMaintain)
    static final String MONTHS_SETTING = "darpan.idindex.months"

    /** Test seam: called with each chunk's row keys after the sweep selected them, before it deletes them. */
    static Closure beforeSweepDeleteHook = null

    static void resetForTests() { beforeSweepDeleteHook = null }

    static Map catchUp(def ec) {
        Map<String, Map> sources = IdIndexCatalog.sources(ec).collectEntries { [(it.systemEnumId): it] }
        int indexed = 0, failed = 0
        if (!sources) return [indexed: 0, failed: 0]
        List datasets = ec.entity.find("darpan.dataset.Dataset").condition("statusId", "DST_ACTIVE")
                .condition("systemEnumId", EntityCondition.IN, sources.keySet()).useCache(false).disableAuthz().list()
                .findAll { it.currentVersion != null }
        for (def ds : datasets) {
            Map dataset = new LinkedHashMap(ds.getMap())
            def cursor = ec.entity.find(IdIndexDatasetHook.CURSOR).condition("companyUserGroupId", dataset.companyUserGroupId)
                    .condition("datasetId", dataset.datasetId).useCache(false).disableAuthz().one()
            if (cursor?.indexedVersion == dataset.currentVersion) continue
            Map source = sources.get(dataset.systemEnumId as String)
            try {
                File dir = DatasetRefreshSupport.datasetDir(ec, dataset.companyUserGroupId as String, dataset.datasetId as String)
                Map out = KeptCopyStore.withRecords(dir) { Iterator<Map> entries ->
                    // each entry is [id, stamp, readAt, record]: the index reads the record
                    Iterator<Map> records = [hasNext: { entries.hasNext() }, next: { (Map) entries.next().record }] as Iterator<Map>
                    IdIndexer.indexRecords(ec, dataset.companyUserGroupId as String, source, dataset.sourceConfigId as String, records,
                            new File(dir, "idindex-work"), IdIndexRunHook.kindHint(ec, source, dataset.sourceConfigId as String),
                            IdIndexDatasetHook.datasetLive(ec, dataset))
                } as Map
                // OC92: a dataset dropped mid-pass (drop#ConnectionKeptData) stops the pass and gets no cursor back
                if (out.stopped) continue
                if (IdIndexDatasetHook.writeCursor(ec, dataset, [indexedVersion: dataset.currentVersion, lastIndexedAt: ec.user.nowTimestamp,
                        rowsLastPass: out.rows, lastStatus: "OK", lastError: null])) indexed++
            } catch (Throwable t) {
                logger.warn("ID index catch-up of dataset ${dataset.datasetId} failed", t)
                IdIndexDatasetHook.recordFailure(ec, dataset, t)
                failed++
            }
        }
        return [indexed: indexed, failed: failed]
    }

    /**
     * Deletes rows not seen for darpan.idindex.months (the catalog's default 13, 1 to 36), 10,000 at a time; returns how many.
     * OC100: read with intValue. The setting is required, so optionalIntValue always threw and a catch kept 13 whatever was
     * set. OC188: read strictly, so a settings table that has not loaded since the restart stops the sweep before anything
     * is deleted instead of answering 13: Darpan's alerts room hears why (never a client room) and the job fails.
     */
    static int sweep(def ec) {
        int months
        try {
            months = DarpanSettings.intValueStrict(MONTHS_SETTING)
        } catch (Throwable t) {
            ErrorChatSupport.postAlertText(ec, "ID index sweep stopped before deleting anything: the setting ${MONTHS_SETTING} could not be read (${t.message ?: t.class.simpleName}).".toString())
            throw t
        }
        Timestamp cutoff = Timestamp.from(ec.user.nowTimestamp.toInstant().atZone(ZoneOffset.UTC).minusMonths(months).toInstant())
        int deleted = 0
        while (true) {
            List<String> keys = ec.entity.find(IdIndexer.ENTITY).condition("lastSeenAt", EntityCondition.LESS_THAN, cutoff)
                    .selectField("rowKey").limit(10_000).useCache(false).disableAuthz().list()*.rowKey as List<String>
            if (!keys) break
            if (beforeSweepDeleteHook != null) beforeSweepDeleteHook.call(keys)
            ec.transaction.runRequireNew(null, "ID index sweep failed") {
                // OC187: the age again, in the delete itself: a row an index pass saw again after the select above stays
                ec.entity.find(IdIndexer.ENTITY).condition("rowKey", EntityCondition.IN, keys)
                        .condition("lastSeenAt", EntityCondition.LESS_THAN, cutoff).disableAuthz().deleteAll()
            }
            deleted += keys.size()
            if (keys.size() < 10_000) break
        }
        return deleted
    }
}
