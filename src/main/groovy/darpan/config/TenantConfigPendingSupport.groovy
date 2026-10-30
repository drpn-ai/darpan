package darpan.config

import groovy.transform.CompileDynamic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/**
 * DAR-BE-160 phase A. The pending side of automatic config versions: mark a tenant pending (after a save commits), and
 * keep a version for each tenant that has been quiet for a minute. Each tenant is versioned in its own transaction, so
 * one tenant's failure never holds up another; a failure is written on the pending row and retried after 15 minutes.
 */
@CompileDynamic
class TenantConfigPendingSupport {

    private static final Logger logger = LoggerFactory.getLogger(TenantConfigPendingSupport.class)

    static final String ENTITY = "darpan.config.TenantConfigPending"
    static final String SETTINGS_SAVE = "SETTINGS_SAVE"
    static final String REASON = "Saved in Darpan"
    static final long BACK_OFF_MILLIS = 15L * 60L * 1000L
    static final int VERSION_TIMEOUT_SECONDS = 300

    /** Marks the tenant pending: creates its row, or moves its last change forward and counts one more save. */
    static void markPending(def ec, String tenantId, String userId, Timestamp at = null) {
        if (!tenantId) return
        Timestamp now = at ?: ec.user.nowTimestamp
        def row = ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).useCache(false).one()
        if (row == null) {
            ec.entity.makeValue(ENTITY).setAll([companyUserGroupId: tenantId, firstChangeDate: now, lastChangeDate: now,
                    lastChangedByUserId: userId, changeCount: 1L]).create()
        } else {
            row.setAll([lastChangeDate: now, lastChangedByUserId: userId ?: row.lastChangedByUserId,
                        changeCount: ((row.changeCount ?: 0L) as Long) + 1L])
            row.update()
        }
    }

    /**
     * Keeps a version for each pending tenant whose last change is at least quietSeconds old, up to maxTenants, oldest
     * first. Answers one outcome per pending tenant: VERSIONED, UNCHANGED, NOT_QUIET, BACKING_OFF or FAILED.
     */
    static List<Map> snapshotDue(def ec, Timestamp nowStamp, int quietSeconds, int maxTenants) {
        Timestamp now = nowStamp ?: ec.user.nowTimestamp
        long quietBefore = now.time - quietSeconds * 1000L
        List<Map> outcomes = []
        int versioned = 0
        List rows = ec.entity.find(ENTITY).orderBy("lastChangeDate").useCache(false).list()
        rows.each { row ->
            String tenantId = row.companyUserGroupId as String
            Timestamp seen = row.lastChangeDate as Timestamp
            if (seen != null && seen.time > quietBefore) {
                outcomes << [tenant: tenantId, outcome: "NOT_QUIET"]
                return
            }
            Timestamp attempted = row.lastAttemptDate as Timestamp
            if (row.lastErrorText && attempted != null && now.time - attempted.time < BACK_OFF_MILLIS) {
                outcomes << [tenant: tenantId, outcome: "BACKING_OFF"]
                return
            }
            if (versioned >= maxTenants) return
            versioned++
            outcomes << versionOne(ec, tenantId, seen, row.lastChangedByUserId as String, now)
        }
        return outcomes
    }

    /** One tenant, in its own transaction: keep a version, then clear the row unless a newer save came in meanwhile. */
    private static Map versionOne(def ec, String tenantId, Timestamp seen, String userId, Timestamp now) {
        try {
            Map result = (Map) ec.transaction.runRequireNew(VERSION_TIMEOUT_SECONDS, "Could not keep tenant ${tenantId}'s config version") {
                Map snap = TenantConfigVersionSupport.snapshot(ec, tenantId, REASON, SETTINGS_SAVE, userId)
                if (snap == null || ec.message.hasError()) {
                    String why = ec.message.errorsString ?: "the config could not be read"
                    ec.message.clearErrors()
                    throw new IllegalStateException(why)
                }
                def row = ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).useCache(false).one()
                if (row != null && (row.lastChangeDate as Timestamp) == seen) row.delete()
                return snap
            }
            return [tenant: tenantId, outcome: result.created ? "VERSIONED" : "UNCHANGED", versionId: result.version?.id]
        } catch (Throwable t) {
            String why = rootMessage(t)
            logger.warn("Tenant ${tenantId}: no config version kept: ${why}")
            ec.message.clearErrors()
            try {
                ec.transaction.runRequireNew(30, "Could not record tenant ${tenantId}'s config version failure") {
                    def row = ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).useCache(false).one()
                    if (row != null) {
                        row.setAll([lastAttemptDate: now, lastErrorText: why])
                        row.update()
                    }
                }
            } catch (Throwable inner) {
                logger.warn("Tenant ${tenantId}: could not record why its config version failed", inner)
            }
            return [tenant: tenantId, outcome: "FAILED", error: why]
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t
        while (cause.cause != null && cause.cause != cause) cause = cause.cause
        return (cause.message ?: t.message ?: t.class.simpleName).trim()
    }
}
