package darpan.admin

import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

class AdminMembershipSupportTests {

    private static final String TENANT_TYPE = TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID

    private def adminEc(Map finders, ServiceFacadeStub service = new ServiceFacadeStub(),
                        MessageFacadeStub message = new MessageFacadeStub()) {
        finders["moqui.security.UserGroupMember"] = finders["moqui.security.UserGroupMember"] ?:
                new FinderStub(oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: "ADMIN_USER"])
        service.message = message
        executionContext(
                user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"),
                entity: new EntityFacadeStub(finders: finders),
                service: service, message: message)
    }

    // moqui.security.UserGroupMember must satisfy TWO independent queries at once: the super-admin
    // guard's PK lookup for ADMIN_USER (via oneResult) and removeTenantMember's tenant-scoped
    // membership-row lookup for TARGET_USER (via listResult). Both fields live on the same
    // FinderStub instance so either query path resolves correctly regardless of which is exercised.
    private Map healthyTenantFinders() {
        ["moqui.security.UserGroup"  : new FinderStub(oneResult: [userGroupId: "KREWE", groupTypeEnumId: TENANT_TYPE]),
         "darpan.auth.TenantSetting" : new FinderStub(oneResult: [companyUserGroupId: "KREWE", disabled: "N"]),
         "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
         "darpan.auth.TenantUserPermissionGroupMember": new FinderStub(listResult: []),
         "moqui.security.UserGroupMember": new FinderStub(
                 oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: "ADMIN_USER"], listResult: []),
         "moqui.security.UserPreference": new FinderStub(oneResult: null)]
    }

    @Test
    void addTenantMemberWritesBothMembershipEntitiesAndAudit() {
        def service = new ServiceFacadeStub()
        def ec = adminEc(healthyTenantFinders(), service)

        assertTrue(AdminMembershipSupport.addTenantMember(ec, "TARGET_USER", "KREWE", "DARPAN_TENANT_USER"))

        def groupMember = service.calls.find { it.serviceName == "create#moqui.security.UserGroupMember" }
        assertEquals("KREWE", groupMember.parametersMap.userGroupId)
        assertEquals("TARGET_USER", groupMember.parametersMap.userId)
        def permMember = service.calls.find { it.serviceName == "create#darpan.auth.TenantUserPermissionGroupMember" }
        assertEquals("KREWE", permMember.parametersMap.tenantUserGroupId)
        assertEquals("DARPAN_TENANT_USER", permMember.parametersMap.permissionUserGroupId)
        assertEquals(groupMember.parametersMap.fromDate, permMember.parametersMap.fromDate,
                "both rows must share one fromDate so history stays joinable")
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void addTenantMemberRejectsNonAllowlistedRoleWithNoWrites() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(healthyTenantFinders(), service, message)

        assertFalse(AdminMembershipSupport.addTenantMember(ec, "TARGET_USER", "KREWE", "DARPAN_SUPER_ADMIN"))
        assertTrue(message.errors.any { it.contains("not an assignable tenant role") })
        assertEquals(0, service.calls.size(), "INVARIANT: rejected add must write NEITHER membership entity")
    }

    @Test
    void addTenantMemberRejectsDeactivatedTenant() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def finders = healthyTenantFinders()
        finders["darpan.auth.TenantSetting"] = new FinderStub(oneResult: [companyUserGroupId: "KREWE", disabled: "Y"])
        def ec = adminEc(finders, service, message)

        assertFalse(AdminMembershipSupport.addTenantMember(ec, "TARGET_USER", "KREWE", "DARPAN_TENANT_USER"))
        assertTrue(message.errors.any { it.contains("deactivated") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void addTenantMemberIsIdempotentForExistingActiveMember() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def finders = healthyTenantFinders()
        finders["darpan.auth.TenantUserPermissionGroupMember"] = new FinderStub(listResult: [
                [tenantUserGroupId: "KREWE", userId: "TARGET_USER",
                 permissionUserGroupId: "DARPAN_TENANT_USER", thruDate: null]])
        def ec = adminEc(finders, service, message)

        assertFalse(AdminMembershipSupport.addTenantMember(ec, "TARGET_USER", "KREWE", "DARPAN_TENANT_USER"))
        assertTrue(message.errors.any { it.contains("already a member") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void removeTenantMemberThruDatesBothEntitiesAndClearsActiveTenantPreference() {
        def service = new ServiceFacadeStub()
        def finders = healthyTenantFinders()
        finders["darpan.auth.TenantUserPermissionGroupMember"] = new FinderStub(listResult: [
                [tenantUserGroupId: "KREWE", userId: "TARGET_USER",
                 permissionUserGroupId: "DARPAN_TENANT_USER", fromDate: new Timestamp(1L), thruDate: null]])
        finders["moqui.security.UserGroupMember"] = new FinderStub(
                oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: "ADMIN_USER"],
                listResult: [[userGroupId: "KREWE", userId: "TARGET_USER", fromDate: new Timestamp(1L), thruDate: null]])
        finders["moqui.security.UserPreference"] = new FinderStub(oneResult: [
                userId: "TARGET_USER", preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY,
                preferenceValue: "KREWE"])
        def ec = adminEc(finders, service)

        assertTrue(AdminMembershipSupport.removeTenantMember(ec, "TARGET_USER", "KREWE"))

        def stores = service.calls.findAll { it.serviceName.startsWith("store#") || it.serviceName.startsWith("update#") }
        assertTrue(stores.any { it.serviceName.contains("UserGroupMember") && it.parametersMap.thruDate != null })
        assertTrue(stores.any { it.serviceName.contains("TenantUserPermissionGroupMember") && it.parametersMap.thruDate != null })
        assertTrue(service.calls.any { it.serviceName == "delete#moqui.security.UserPreference" },
                "stale active-tenant preference must be cleared")
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void removeTenantMemberFailsWhenNotAMember() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(healthyTenantFinders(), service, message)

        assertFalse(AdminMembershipSupport.removeTenantMember(ec, "TARGET_USER", "KREWE"))
        assertTrue(message.errors.any { it.contains("not a member") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void updateTenantMemberRoleThruDatesOldRowAndCreatesNew() {
        def service = new ServiceFacadeStub()
        def finders = healthyTenantFinders()
        finders["darpan.auth.TenantUserPermissionGroupMember"] = new FinderStub(listResult: [
                [tenantUserGroupId: "KREWE", userId: "TARGET_USER",
                 permissionUserGroupId: "DARPAN_TENANT_USER", fromDate: new Timestamp(1L), thruDate: null]])
        def ec = adminEc(finders, service)

        assertTrue(AdminMembershipSupport.updateTenantMemberRole(ec, "TARGET_USER", "KREWE", "DARPAN_TENANT_ADMIN"))

        def close = service.calls.find { it.serviceName == "update#darpan.auth.TenantUserPermissionGroupMember" }
        assertEquals("DARPAN_TENANT_USER", close.parametersMap.permissionUserGroupId)
        assertNotNull(close.parametersMap.thruDate, "old role row must be thruDated, not deleted")
        def open = service.calls.find { it.serviceName == "create#darpan.auth.TenantUserPermissionGroupMember" }
        assertEquals("DARPAN_TENANT_ADMIN", open.parametersMap.permissionUserGroupId)
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void mutationsDeniedForPlainUserWriteNothing() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "PLAIN_USER"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER", userId: "PLAIN_USER"])]),
                service: service, message: message)

        assertFalse(AdminMembershipSupport.addTenantMember(ec, "U", "KREWE", "DARPAN_TENANT_USER"))
        assertFalse(AdminMembershipSupport.updateTenantMemberRole(ec, "U", "KREWE", "DARPAN_TENANT_USER"))
        assertFalse(AdminMembershipSupport.removeTenantMember(ec, "U", "KREWE"))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size())
    }

    private static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user: overrides.user ?: new UserStub(),
                entity: overrides.entity ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                service: overrides.service ?: new ServiceFacadeStub(),
                l10n: overrides.l10n ?: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

        Object getPreference(String preferenceKey) {
            return preferences[preferenceKey]
        }

        void setPreference(String preferenceKey, Object preferenceValue) {
            preferences[preferenceKey] = preferenceValue
        }
    }

    static class MessageFacadeStub {
        List<String> errors = []

        void addError(String error) {
            errors << error
        }

        void addMessage(String message) {
        }

        boolean hasError() {
            return !errors.isEmpty()
        }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        // Mirrors real ec.entity.find(): every call returns a fresh finder/condition-builder
        // (never accumulating conditions across unrelated queries), backed by the same
        // configured dataset for that entity name. Without this, two independent queries
        // against the same entity in one call chain would corrupt each other's conditions.
        FinderStub find(String entityName) {
            FinderStub template = finders[entityName]
            if (template == null) {
                template = new FinderStub()
                finders[entityName] = template
            }
            return new FinderStub(oneResult: template.oneResult, listResult: template.listResult)
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        List listResult = []

        FinderStub condition(String field, Object value) {
            conditions[field] = value
            return this
        }

        FinderStub conditionDate(String fromField, String thruField, Object moment) {
            return this
        }

        FinderStub useCache(boolean useCache) {
            return this
        }

        Object one() {
            if (oneResult instanceof Map && !conditions.every { String field, Object value -> oneResult[field] == value }) {
                return null
            }
            return oneResult
        }

        List list() {
            return listResult.findAll { Object row ->
                if (!(row instanceof Map)) return true
                conditions.every { String field, Object value -> row[field] == value }
            }
        }

        FinderStub disableAuthz() {
            return this
        }
    }

    static class ServiceFacadeStub {
        List<ServiceCallStub> calls = []
        Map<String, Map> resultsByName = [:]
        // Simulates a framework service call that fails: keyed by full service name, the error
        // text is added to the wired MessageFacadeStub when that call() runs (mirrors a real
        // service's <return error="true".../> setting ec.message in the caller's context).
        Map<String, String> errorsByName = [:]
        MessageFacadeStub message
        ServiceCallStub lastCall

        ServiceCallStub sync() {
            lastCall = new ServiceCallStub(facade: this)
            calls << lastCall
            return lastCall
        }
    }

    static class ServiceCallStub {
        ServiceFacadeStub facade
        String serviceName
        Map<String, Object> parametersMap = [:]

        ServiceCallStub name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        ServiceCallStub parameters(Map<String, Object> parametersMap) {
            this.parametersMap = parametersMap
            return this
        }

        Map<String, Object> call() {
            String error = facade?.errorsByName?.get(serviceName)
            if (error) facade?.message?.addError(error)
            return facade?.resultsByName?.get(serviceName) ?: [:]
        }
    }
}
