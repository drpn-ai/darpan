package darpan.idindex

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-374: a run's live read (Run now or an automation) feeds the ID index. The run hands its extract file to a background
 * service and goes on; indexing never slows or fails a run. A kept-copy slice is not handed over: its dataset read was indexed.
 */
class IdIndexRunHook {

    private static final Logger logger = LoggerFactory.getLogger(IdIndexRunHook)
    static final String SERVICE = "idindex.IdIndexServices.index#ExtractFile"

    /** Test seam: (service parameters) -> nothing, in place of the async call. */
    static Closure dispatchHook = null

    static void resetForTests() { dispatchHook = null }

    /**
     * Called by both run paths right after a live extract returns its file. Never throws. readStartedAt (OC158): when the run
     * began reading, captured before the fetch; it decides whether a drop came after the read (the file's modified time is
     * when the write ended, later than the read).
     */
    static void afterLiveRead(def ec, String tenantId, String systemEnumId, String connectionId, String fileLocation,
                              java.sql.Timestamp readStartedAt = null) {
        try {
            if (!tenantId || !systemEnumId || !connectionId || !fileLocation) return
            if (!IdIndexCatalog.sources(ec).any { it.systemEnumId == systemEnumId }) return
            Map params = [tenantId: tenantId, systemEnumId: systemEnumId, connectionId: connectionId, fileLocation: fileLocation]
            if (readStartedAt != null) params.readStartedAt = readStartedAt
            if (dispatchHook != null) { dispatchHook.call(params); return }
            ec.service.async().name(SERVICE).parameters(params).call()
        } catch (Throwable t) {
            logger.warn("Could not hand ${systemEnumId} extract ${fileLocation} to the ID index", t)
        }
    }

    /** index#ExtractFile's body: the extract file indexed for the tenant connection; [records, rows] or [error]. */
    static Map indexExtract(def ec, String tenantId, String systemEnumId, String connectionId, String fileLocation,
                            java.sql.Timestamp readStartedAt = null) {
        Map source = IdIndexCatalog.sources(ec).find { it.systemEnumId == systemEnumId }
        if (source == null) return [skipped: true]
        File original = fileLocation.startsWith("/") ? new File(fileLocation) : ec.resource.getLocationReference(fileLocation)?.getFile()
        if (original == null || !original.isFile()) return [error: "no file at ${fileLocation}".toString()]
        long readAt = readStartedAt != null ? readStartedAt.time : original.lastModified()
        ensureMarker(ec, tenantId, connectionId)
        File lines = IdIndexer.toJsonLines(original)
        try {
            return IdIndexer.indexFile(ec, tenantId, source, connectionId, lines, kindHint(ec, source, connectionId),
                    { -> readAfterAnyDrop(ec, tenantId, connectionId, readAt) })
        } finally {
            if (lines != original) lines.delete()
        }
    }

    static final String DROP = "darpan.idindex.RecordIdIndexDrop"

    /**
     * OC104: whether this extract may still be indexed for the tenant connection: its drop marker (drop#ConnectionKeptData writes
     * and locks it first, before deleting anything), read for update, is absent, never dropped, or older than the read. A read made before a
     * drop writes nothing after it; a later read (the same store installed again) indexes. Per tenant, so another tenant's runs
     * on a shared connection are untouched. readAtMillis: the run's readStartedAt when given, else the extract file's modified time.
     */
    static boolean readAfterAnyDrop(def ec, String tenantId, String connectionId, long readAtMillis) {
        def marker = TenantScopedFinder.findGlobalUnscoped(ec, DROP,
                        "OC104: a background index pass (no user) checks its tenant connection's drop marker, under the drop's lock")
                .condition("companyUserGroupId", tenantId).condition("connectionId", connectionId).forUpdate(true).useCache(false).one()
        return marker == null || marker.droppedAt == null || ((java.sql.Timestamp) marker.droppedAt).time < readAtMillis
    }

    /**
     * OC158: the tenant connection's marker row exists before the first batch, so each batch's check locks a real row, the
     * same one a drop locks: a drop that starts mid-batch waits for it, then deletes what it wrote. With no row the check
     * locked nothing. Its own committed transaction; another writer creating the row first (a drop, a parallel pass) is fine.
     */
    static void ensureMarker(def ec, String tenantId, String connectionId) {
        Closure<Object> existing = { ->
            TenantScopedFinder.findGlobalUnscoped(ec, DROP, "OC158: does the tenant connection's drop marker row exist")
                    .condition("companyUserGroupId", tenantId).condition("connectionId", connectionId).useCache(false).one()
        }
        try {
            ec.transaction.runRequireNew(null, "ID index drop marker") {
                if (existing.call() == null) ec.entity.makeValue(DROP).setAll([companyUserGroupId: tenantId, connectionId: connectionId]).create()
            }
        } catch (Exception e) {
            if (existing.call() == null) throw e   // a concurrent create leaves the row, which is all this needs
        }
    }

    /** The read's record kind named outside its records (IdIndexSource.kindHintEntity/kindHintField keyed by the connection), or null. */
    static String kindHint(def ec, Map source, String connectionId) {
        if (!source.kindHintEntity || !source.kindHintField || !connectionId) return null
        if (!ec.entity.isEntityDefined(source.kindHintEntity as String)) return null
        def ed = ec.entity.getEntityDefinition(source.kindHintEntity as String)
        String pk = ed.getPkFieldNames()[0]
        return ec.entity.find(source.kindHintEntity as String).condition(pk, connectionId).useCache(false).disableAuthz().one()
                ?.get(source.kindHintField as String) as String
    }
}
