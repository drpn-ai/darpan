package darpan.admin

import darpan.facade.common.TenantAccessSupport
import groovy.transform.CompileDynamic

import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Tenant creation for the admin app. A Darpan tenant IS a moqui.security.UserGroup with
 *  groupTypeEnumId=UgtDarpanCompany plus its darpan.auth.TenantSetting row.
 *  Create stays Groovy for the id-pattern check; the rest of the tenant lifecycle (update,
 *  deactivate/reactivate, list, detail) is XML actions in service/admin/TenantAdminServices.xml. */
@CompileDynamic
class AdminTenantSupport {

    static final String TENANT_ID_PATTERN = /^[A-Z][A-Z0-9_]{1,39}$/

    static Map<String, Object> createTenant(def ec, String tenantUserGroupId, String label, String timeZone) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        String tenantId = tenantUserGroupId?.trim()
        if (!tenantId || !(tenantId ==~ TENANT_ID_PATTERN)) {
            ec.message.addError("Tenant id must be 2-40 chars: uppercase letters, digits, underscores, starting with a letter.")
            return null
        }
        if (!label?.trim()) { ec.message.addError("Tenant label is required."); return null }
        if (ec.entity.find("moqui.security.UserGroup").condition("userGroupId", tenantId).one() != null) {
            ec.message.addError("Tenant ${tenantId} already exists."); return null
        }
        String createTimeZoneError = timeZone?.trim() ? TenantAccessSupport.validateTimeZone(timeZone) : null
        if (createTimeZoneError) { ec.message.addError(createTimeZoneError); return null }
        ec.service.sync().name("create#moqui.security.UserGroup").parameters([
            userGroupId: tenantId, description: label.trim(),
            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).call()
        ec.service.sync().name("create#darpan.auth.TenantSetting").parameters([
            companyUserGroupId: tenantId, createdByUserId: ec.user.userId,
            timeZone: timeZone?.trim() ?: "UTC", disabled: "N",
            createdDate: ec.user.nowTimestamp, lastUpdatedDate: ec.user.nowTimestamp,
        ]).call()
        AdminAuditSupport.record(ec, "admin.TenantAdminServices.create#Tenant", "Tenant", tenantId,
                "Created tenant ${tenantId} (${label.trim()}).")
        return [tenantUserGroupId: tenantId]
    }

    /**
     * DAR-BE-145. A go-live as typed: with an offset ("2026-10-20T21:05:23-07:00") it is that instant; without one
     * ("2026-10-20 21:05:23") it is that wall-clock time in the tenant's zone, as a go-live is handed over. Null when
     * it is neither.
     */
    static Timestamp parseGoLive(String text, String zoneId) {
        String value = text?.trim()?.replace(' ', 'T')
        if (!value) return null
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant())
        } catch (DateTimeParseException ignored) { }
        try {
            return Timestamp.from(LocalDateTime.parse(value).atZone(ZoneId.of(zoneId ?: "UTC")).toInstant())
        } catch (DateTimeParseException ignored) {
            return null
        }
    }
}
