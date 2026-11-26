package darpan.idindex

import darpan.config.DarpanSettings
import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.KeptCopyStore
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
                            new File(dir, "idindex-work"), IdIndexRunHook.kindHint(ec, source, dataset.sourceConfigId as String))
                } as Map
                IdIndexDatasetHook.writeCursor(ec, dataset, [indexedVersion: dataset.currentVersion, lastIndexedAt: ec.user.nowTimestamp,
                        rowsLastPass: out.rows, lastStatus: "OK", lastError: null])
                indexed++
            } catch (Throwable t) {
                logger.warn("ID index catch-up of dataset ${dataset.datasetId} failed", t)
                IdIndexDatasetHook.recordFailure(ec, dataset, t)
                failed++
            }
        }
        return [indexed: indexed, failed: failed]
    }

    /** Deletes rows not seen for darpan.idindex.months, 10,000 at a time; returns how many. */
    static int sweep(def ec) {
        int months = 13
        try { Integer v = DarpanSettings.optionalIntValue(MONTHS_SETTING); if (v != null) months = v } catch (Exception ignored) { }
        Timestamp cutoff = Timestamp.from(ec.user.nowTimestamp.toInstant().atZone(ZoneOffset.UTC).minusMonths(months).toInstant())
        int deleted = 0
        while (true) {
            List<String> keys = ec.entity.find(IdIndexer.ENTITY).condition("lastSeenAt", EntityCondition.LESS_THAN, cutoff)
                    .selectField("rowKey").limit(10_000).useCache(false).disableAuthz().list()*.rowKey as List<String>
            if (!keys) break
            ec.transaction.runRequireNew(null, "ID index sweep failed") {
                ec.entity.find(IdIndexer.ENTITY).condition("rowKey", EntityCondition.IN, keys).disableAuthz().deleteAll()
            }
            deleted += keys.size()
            if (keys.size() < 10_000) break
        }
        return deleted
    }
}
