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
}
