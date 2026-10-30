package darpan.config

import darpan.admin.transfer.ConfigTransferExporter
import darpan.admin.transfer.TransferSpecs
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * DAR-BE-160 phase A. A tenant's audit config kept as versions in the database (darpan.config.TenantConfigVersion).
 *
 * <p>The pack is the DAR-ADMIN-006 transfer bundle of the whole tenant: every audit, every saved run a lifecycle does not
 * own, and every connection the tenant owns, plus all they reach. So ids travel as refs, children come in a fixed order,
 * and no secret column is ever read. A snapshot writes a version only when the content hash differs from the tenant's
 * latest one. Every read and write names the tenant explicitly; callers pass the session's active tenant.</p>
 */
@CompileDynamic
class TenantConfigVersionSupport {

    static final String ENTITY = "darpan.config.TenantConfigVersion"
    static final String MANUAL = "MANUAL"
    static final List<String> CHANGE_SOURCES = [MANUAL, "SETTINGS_SAVE", "AGENT_APPLY", "IMPORT", "RESTORE"].asImmutable()
    static final List<String> COUNTED_LISTS = ["audits", "statements", "checks", "runs", "queries", "conclusionCodes",
                                               "connections", "automations"].asImmutable()
    static final int DEFAULT_LIST_LIMIT = 50

    /** The tenant's whole config as a transfer bundle; null (with errors) when the exporter refuses. */
    static Map<String, Object> currentPack(def ec, String tenantId) {
        Map<String, Object> catalog = ConfigTransferExporter.catalog(ec, tenantId)
        if (catalog == null) return null
        List auditCodes = (catalog.audits as List<Map>)*.auditCode
        List runIds = (catalog.runs as List<Map>)*.savedRunId
        // Owned connections only; one shared to the tenant still travels when a run reaches it, as /transfer does.
        List connectionRefs = (catalog.connections as List<Map>).findAll { !it.granted }*.ref
        // With automations: the morning audit runs an automation's own copy of its run's sources and filters.
        return ConfigTransferExporter.bundle(ec, tenantId, auditCodes, runIds, connectionRefs, null, null, true)
    }

    /** Hex SHA-256 of the pack without its source block, map keys sorted; list order is kept (the exporter sorts lists). */
    static String contentHash(Map pack) {
        Map content = new LinkedHashMap(pack ?: [:])
        content.remove("source")
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.toJson(canonical(content)).getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format("%02x", it) }.join()
    }

    static Object canonical(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<String, Object>()
            ((Map) value).each { k, v -> sorted.put(k as String, canonical(v)) }
            return sorted
        }
        if (value instanceof Collection) return ((Collection) value).collect { canonical(it) }
        return value
    }

    static Map<String, Integer> counts(Map pack) {
        return COUNTED_LISTS.collectEntries { String list -> [(list): ((pack?.get(list) ?: []) as List).size()] } as Map<String, Integer>
    }

    /**
     * Keeps the tenant's config as a new version when it changed since the latest one. Answers [created, version]; when
     * nothing changed, created is false and version is the latest. Null (with errors) when the pack cannot be built.
     */
    static Map<String, Object> snapshot(def ec, String tenantId, String reason, String changeSource = MANUAL,
                                        String createdByUserId = null) {
        String source = changeSource ?: MANUAL
        if (!(source in CHANGE_SOURCES)) {
            ec.message.addError("Change source ${source} is not one of ${CHANGE_SOURCES.join(', ')}.")
            return null
        }
        Map<String, Object> pack = currentPack(ec, tenantId)
        if (pack == null || ec.message.hasError()) return null
        String hash = contentHash(pack)

        def latest = latestRow(ec, tenantId)
        if (latest != null && latest.contentHash == hash) return [created: false, version: summary(latest)] as Map<String, Object>

        def row = ec.entity.makeValue(ENTITY)
        row.setAll([companyUserGroupId: tenantId,
                    versionSeq        : ((latest?.versionSeq ?: 0L) as Long) + 1L,
                    parentVersionId   : latest?.configVersionId,
                    contentHash       : hash,
                    bundleVersion     : (pack.bundleVersion ?: TransferSpecs.BUNDLE_VERSION) as Long,
                    packJson          : JsonOutput.toJson(pack),
                    countsJson        : JsonOutput.toJson(counts(pack)),
                    changeSource      : source,
                    reasonText        : reason?.trim() ?: null,
                    // A scheduled version has no user; it is credited to whoever saved last.
                    createdByUserId   : createdByUserId ?: ec.user.userId,
                    createdDate       : ec.user.nowTimestamp])
        row.setSequencedIdPrimary()
        row.create()
        return [created: true, version: summary(row)] as Map<String, Object>
    }

    /** The tenant's versions, newest first, without their packs. */
    static List<Map> versions(def ec, String tenantId, Integer limit = DEFAULT_LIST_LIMIT) {
        int max = (limit && limit > 0) ? Math.min(limit, 500) : DEFAULT_LIST_LIMIT
        List rows = ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).orderBy("-versionSeq").limit(max)
                .selectFields(SUMMARY_FIELDS).useCache(false).list()
        Map<String, String> names = userNames(ec, rows*.createdByUserId)
        return rows.collect { summary(it) + [createdByName: names[it.createdByUserId as String]] }
    }

    /** Full name, else username, for each user id; one read for the whole list. */
    static Map<String, String> userNames(def ec, Collection userIds) {
        List<String> ids = (userIds ?: []).findAll { it }.collect { it as String }.unique()
        if (!ids) return [:]
        return ec.entity.find("moqui.security.UserAccount").condition("userId", "in", ids)
                .selectFields(["userId", "userFullName", "username"]).disableAuthz().useCache(false).list()
                .collectEntries { [(it.userId as String): (it.userFullName ?: it.username) as String] } as Map<String, String>
    }

    /** One of the tenant's versions with its pack; null (with an error) when it is not this tenant's. */
    static Map version(def ec, String tenantId, String configVersionId) {
        // Not find(...).condition(pk).condition(tenant).one(): with the full PK, one() drops the tenant condition.
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, ENTITY, "configVersionId", configVersionId, tenantId,
                "DAR-BE-160 config version: read by id, then the tenant compared")
        if (row == null) {
            ec.message.addError("Config version ${configVersionId ?: '(missing)'} was not found.")
            return null
        }
        return summary(row) + [createdByName: userNames(ec, [row.createdByUserId])[row.createdByUserId as String],
                               pack: new JsonSlurper().parseText(row.packJson as String)]
    }

    private static final List<String> SUMMARY_FIELDS = ["configVersionId", "companyUserGroupId", "versionSeq", "parentVersionId",
            "contentHash", "bundleVersion", "countsJson", "changeSource", "reasonText", "createdByUserId", "createdDate"].asImmutable()

    private static def latestRow(def ec, String tenantId) {
        return ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).orderBy("-versionSeq").limit(1)
                .selectFields(SUMMARY_FIELDS).useCache(false).list().find()
    }

    private static Map summary(def row) {
        return [id          : row.configVersionId,
                seq         : row.versionSeq as Long,
                parentId    : row.parentVersionId,
                hash        : row.contentHash,
                bundleVersion: row.bundleVersion as Long,
                counts      : row.countsJson ? new JsonSlurper().parseText(row.countsJson as String) : [:],
                changeSource: row.changeSource,
                reason      : row.reasonText,
                createdBy   : row.createdByUserId,
                createdDate : row.createdDate]
    }
}
