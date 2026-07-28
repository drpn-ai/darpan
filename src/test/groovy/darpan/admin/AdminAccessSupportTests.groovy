package darpan.admin

import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class AdminAccessSupportTests {

    private def superAdminEc(MessageFacadeStub message = new MessageFacadeStub()) {
        // isSuperAdmin() resolves membership via moqui.security.UserGroupMember
        executionContext(
                user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_SUPER_ADMIN",
                                userId: "ADMIN_USER"
                        ])
                ]),
                message: message)
    }

    private def plainUserEc(MessageFacadeStub message = new MessageFacadeStub()) {
        executionContext(
                user: new UserStub(userId: "PLAIN_USER", username: "hotwax.user"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER",
                                userId: "PLAIN_USER"
                        ])
                ]),
                message: message)
    }

    @Test
    void requireSuperAdminPassesForSuperAdmin() {
        assertTrue(AdminAccessSupport.requireSuperAdmin(superAdminEc()))
    }

    @Test
    void requireSuperAdminDeniesPlainUserWithError() {
        def message = new MessageFacadeStub()
        assertFalse(AdminAccessSupport.requireSuperAdmin(plainUserEc(message)))
        assertTrue(message.hasError())
    }

    @Test
    void buildAdminSessionInfoReturnsIdentityForSuperAdmin() {
        Map info = AdminAccessSupport.buildAdminSessionInfo(superAdminEc())
        assertEquals("ADMIN_USER", info.userId)
        assertEquals("darpan.admin", info.username)
        assertEquals(true, info.isSuperAdmin)
    }

    @Test
    void buildAdminSessionInfoReturnsNullForPlainUser() {
        assertNull(AdminAccessSupport.buildAdminSessionInfo(plainUserEc()))
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
        ServiceCallStub lastCall

        ServiceCallStub sync() {
            lastCall = new ServiceCallStub()
            return lastCall
        }
    }

    static class ServiceCallStub {
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
            return [:]
        }
    }
}
