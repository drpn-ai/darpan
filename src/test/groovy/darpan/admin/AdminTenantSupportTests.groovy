package darpan.admin

import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

class AdminTenantSupportTests {

    private def adminEc(Map finders, ServiceFacadeStub service = new ServiceFacadeStub(),
                        MessageFacadeStub message = new MessageFacadeStub()) {
        finders["moqui.security.UserGroupMember"] = finders["moqui.security.UserGroupMember"] ?:
                new FinderStub(oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: "ADMIN_USER"])
        executionContext(
                user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"),
                entity: new EntityFacadeStub(finders: finders),
                service: service, message: message)
    }

    @Test
    void createTenantCreatesGroupSettingAndAudit() {
        def service = new ServiceFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup": new FinderStub(oneResult: null)], service)

        Map result = AdminTenantSupport.createTenant(ec, "KREWE", "Krewe", "America/Chicago")

        assertEquals("KREWE", result.tenantUserGroupId)
        List names = service.calls*.serviceName
        assertTrue(names.contains("create#moqui.security.UserGroup"))
        assertTrue(names.contains("create#darpan.auth.TenantSetting"))
        assertTrue(names.contains("create#darpan.admin.AdminAuditLog"))
        def groupCall = service.calls.find { it.serviceName == "create#moqui.security.UserGroup" }
        assertEquals("KREWE", groupCall.parametersMap.userGroupId)
        assertEquals("Krewe", groupCall.parametersMap.description)
        assertEquals(TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID, groupCall.parametersMap.groupTypeEnumId)
    }

    @Test
    void createTenantRejectsMalformedId() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc([:], service, message)

        assertNull(AdminTenantSupport.createTenant(ec, "bad id!", "Bad", null))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size(), "no writes on validation failure")
    }

    @Test
    void createTenantRejectsDuplicateId() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup":
                new FinderStub(oneResult: [userGroupId: "KREWE", description: "Krewe"])], service, message)

        assertNull(AdminTenantSupport.createTenant(ec, "KREWE", "Krewe again", null))
        assertTrue(message.errors.any { it.contains("already exists") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void createTenantDeniedForPlainUserWritesNothing() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "PLAIN_USER"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER", userId: "PLAIN_USER"])]),
                service: service, message: message)

        assertNull(AdminTenantSupport.createTenant(ec, "KREWE", "Krewe", null))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size())
    }

    @Test
    void setTenantDisabledStoresFlagAndAudits() {
        def service = new ServiceFacadeStub()
        def ec = adminEc([
                "moqui.security.UserGroup": new FinderStub(oneResult: [userGroupId: "KREWE",
                        groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]),
                "darpan.auth.TenantSetting": new FinderStub(oneResult: [companyUserGroupId: "KREWE", disabled: "N"]),
        ], service)

        assertTrue(AdminTenantSupport.setTenantDisabled(ec, "KREWE", true))
        def storeCall = service.calls.find { it.serviceName == "store#darpan.auth.TenantSetting" }
        assertEquals("Y", storeCall.parametersMap.disabled)
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void setTenantDisabledFailsForUnknownTenant() {
        def message = new MessageFacadeStub()
        def service = new ServiceFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup": new FinderStub(oneResult: null)], service, message)

        assertFalse(AdminTenantSupport.setTenantDisabled(ec, "NOPE", true))
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
            return facade?.resultsByName?.get(serviceName) ?: [:]
        }
    }
}
