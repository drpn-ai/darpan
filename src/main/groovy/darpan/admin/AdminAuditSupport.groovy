package darpan.admin

import groovy.transform.CompileDynamic

/** Writes the AdminAuditLog row for every admin.* mutation, in the caller's transaction. */
@CompileDynamic
class AdminAuditSupport {

    static void record(def ec, String serviceName, String targetType, String targetId, String detailText) {
        ec.service.sync().name("create#darpan.admin.AdminAuditLog").parameters([
            adminUserId   : ec?.user?.userId,
            serviceName   : serviceName,
            targetType    : targetType,
            targetId      : targetId,
            detailText    : detailText,
            auditTimestamp: ec?.user?.nowTimestamp,
        ]).call()
    }

    /**
     * DAR-BE-265. The same row for a run that owns no transaction (a migration run, a retention run): written once the run
     * is done, in its own transaction, even when the run left errors behind, so a failed attempt is on the trail too.
     */
    static void recordRun(def ec, String serviceName, String targetType, String targetId, String detailText) {
        ec.service.sync().name("create#darpan.admin.AdminAuditLog").parameters([
            adminUserId   : ec?.user?.userId ?: SYSTEM_USER_ID,
            serviceName   : serviceName,
            targetType    : targetType,
            targetId      : targetId,
            detailText    : detailText,
            auditTimestamp: ec?.user?.nowTimestamp,
        ]).ignorePreviousError(true).call()
    }

    /** Who a scheduled job's row names: the job runs as no one. */
    static final String SYSTEM_USER_ID = "_NA_"
}
