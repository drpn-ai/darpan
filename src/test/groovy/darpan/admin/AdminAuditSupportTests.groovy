package darpan.admin

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals

class AdminAuditSupportTests {

    @Test
    void recordCreatesAuditRowWithOperatorAndTarget() {
        def service = new ServiceFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"),
                service: service)

        AdminAuditSupport.record(ec, "admin.TenantAdminServices.create#Tenant", "Tenant", "KREWE", "Created tenant KREWE.")

        assertEquals(1, service.calls.size())
        def call = service.calls[0]
        assertEquals("create#darpan.admin.AdminAuditLog", call.serviceName)
        assertEquals("ADMIN_USER", call.parametersMap.adminUserId)
        assertEquals("admin.TenantAdminServices.create#Tenant", call.parametersMap.serviceName)
        assertEquals("Tenant", call.parametersMap.targetType)
        assertEquals("KREWE", call.parametersMap.targetId)
        assertEquals("Created tenant KREWE.", call.parametersMap.detailText)
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

        FinderStub find(String entityName) {
            FinderStub finder = finders[entityName]
            if (finder == null) {
                finder = new FinderStub()
                finders[entityName] = finder
            }
            return finder
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
