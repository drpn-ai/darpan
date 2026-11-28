package darpan.idindex

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

    /** Called by both run paths right after a live extract returns its file. Never throws. */
    static void afterLiveRead(def ec, String tenantId, String systemEnumId, String connectionId, String fileLocation) {
        try {
            if (!tenantId || !systemEnumId || !connectionId || !fileLocation) return
            if (!IdIndexCatalog.sources(ec).any { it.systemEnumId == systemEnumId }) return
            Map params = [tenantId: tenantId, systemEnumId: systemEnumId, connectionId: connectionId, fileLocation: fileLocation]
            if (dispatchHook != null) { dispatchHook.call(params); return }
            ec.service.async().name(SERVICE).parameters(params).call()
        } catch (Throwable t) {
            logger.warn("Could not hand ${systemEnumId} extract ${fileLocation} to the ID index", t)
        }
    }

    /** index#ExtractFile's body: the extract file indexed for the tenant connection; [records, rows] or [error]. */
    static Map indexExtract(def ec, String tenantId, String systemEnumId, String connectionId, String fileLocation) {
        Map source = IdIndexCatalog.sources(ec).find { it.systemEnumId == systemEnumId }
        if (source == null) return [skipped: true]
        File original = fileLocation.startsWith("/") ? new File(fileLocation) : ec.resource.getLocationReference(fileLocation)?.getFile()
        if (original == null || !original.isFile()) return [error: "no file at ${fileLocation}".toString()]
        File lines = IdIndexer.toJsonLines(original)
        try {
            return IdIndexer.indexFile(ec, tenantId, source, connectionId, lines, kindHint(ec, source, connectionId),
                    { -> connectionStillThere(ec, systemEnumId, connectionId) })
        } finally {
            if (lines != original) lines.delete()
        }
    }

    /**
     * OC92: whether the run's connection is still there: its connector's configEntityName row, by key (both run paths pass that
     * config's id), read for update so a deletion and an index batch cannot interleave. True when the connector names no config
     * entity: nothing to check.
     */
    static boolean connectionStillThere(def ec, String systemEnumId, String connectionId) {
        def connector = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", systemEnumId)
                .useCache(false).disableAuthz().one()
        String entityName = connector?.configEntityName as String
        if (!entityName || !ec.entity.isEntityDefined(entityName)) return true
        String pk = ec.entity.getEntityDefinition(entityName).getPkFieldNames()[0]
        return !ec.entity.find(entityName).condition(pk, connectionId).forUpdate(true).useCache(false).disableAuthz().list().isEmpty()
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
