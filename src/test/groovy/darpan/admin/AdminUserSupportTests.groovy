package darpan.admin

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

class AdminUserSupportTests {

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

    @Test
    void createUserDelegatesToFrameworkWithForcedPasswordChangeAndDarpanUserMembership() {
        def service = new ServiceFacadeStub()
        service.resultsByName["org.moqui.impl.UserServices.create#UserAccount"] = [userId: "NEW_USER"]
        def ec = adminEc([:], service)

        Map result = AdminUserSupport.createUser(ec, "new.user", "New User", "new@x.co", "Temp#Pass1")

        assertNotNull(result)
        def createCall = service.calls.find { it.serviceName == "org.moqui.impl.UserServices.create#UserAccount" }
        assertEquals("new.user", createCall.parametersMap.username)
        assertEquals("Temp#Pass1", createCall.parametersMap.newPassword)
        assertEquals("Temp#Pass1", createCall.parametersMap.newPasswordVerify)
        assertEquals("Y", createCall.parametersMap.requirePasswordChange)
        def memberCall = service.calls.find { it.serviceName == "create#moqui.security.UserGroupMember" }
        assertEquals("DARPAN_USER", memberCall.parametersMap.userGroupId, "new users must join DARPAN_USER or no facade call works")
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void updateUserWithOnlyEmailOmitsUserFullNameKeepingItUnchanged() {
        // Entity-auto update applies EXPLICIT nulls (setIfEmpty). If userFullName were sent as
        // null, an "update email only" call would wipe the display name - contradicting the
        // service's "unchanged when omitted" contract.
        def service = new ServiceFacadeStub()
        def ec = adminEc([
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
        ], service)

        assertTrue(AdminUserSupport.updateUser(ec, "TARGET_USER", null, "new@x.co"))

        def updateCall = service.calls.find { it.serviceName == "org.moqui.impl.UserServices.update#UserAccount" }
        assertEquals("TARGET_USER", updateCall.parametersMap.userId)
        assertEquals("new@x.co", updateCall.parametersMap.emailAddress)
        assertFalse(updateCall.parametersMap.containsKey("userFullName"),
                "omitted userFullName must not be sent as an explicit null")
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void updateUserWithNeitherFieldFailsWithNoWrites() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc([
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
        ], service, message)

        assertFalse(AdminUserSupport.updateUser(ec, "TARGET_USER", null, null))
        assertTrue(message.errors.any { it.contains("Nothing to update") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void disableUserRefusesSelf() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc([:], service, message)

        assertFalse(AdminUserSupport.setUserDisabled(ec, "ADMIN_USER", true))
        assertTrue(message.errors.any { it.contains("your own account") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void disableUserDisablesAndRevokesLoginKeys() {
        def service = new ServiceFacadeStub()
        def ec = adminEc([
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
                "moqui.security.UserLoginKey": new FinderStub(listResult: [[userId: "TARGET_USER", loginKey: "key-1"]]),
        ], service)

        assertTrue(AdminUserSupport.setUserDisabled(ec, "TARGET_USER", true))
        assertTrue(service.calls*.serviceName.contains("org.moqui.impl.UserServices.disable#UserAccount"))
        def keyDelete = service.calls.find { it.serviceName == "delete#moqui.security.UserLoginKey" }
        assertNotNull(keyDelete, "live sessions must be revoked on disable")
        assertEquals("key-1", keyDelete.parametersMap.loginKey)
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void resetPasswordUsesInternalServiceWithForcedChangeAndRevokesKeys() {
        def service = new ServiceFacadeStub()
        def ec = adminEc([
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
                "moqui.security.UserLoginKey": new FinderStub(listResult: [[userId: "TARGET_USER", loginKey: "key-2"]]),
        ], service)

        assertTrue(AdminUserSupport.resetPassword(ec, "TARGET_USER", "Temp#Pass2"))
        def pwCall = service.calls.find { it.serviceName == "org.moqui.impl.UserServices.update#PasswordInternal" }
        assertEquals("Temp#Pass2", pwCall.parametersMap.newPassword)
        assertEquals("Y", pwCall.parametersMap.requirePasswordChange)
        def keyDelete = service.calls.find { it.serviceName == "delete#moqui.security.UserLoginKey" }
        assertNotNull(keyDelete, "live sessions must be revoked on password reset")
        assertEquals("key-2", keyDelete.parametersMap.loginKey)
        assertTrue(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"))
    }

    @Test
    void resetPasswordStopsWhenFrameworkServiceFailsWritesNothingFurther() {
        def service = new ServiceFacadeStub()
        service.errorsByName["org.moqui.impl.UserServices.update#PasswordInternal"] =
                "Found issues with password so not updating"
        def message = new MessageFacadeStub()
        def ec = adminEc([
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "TARGET_USER", username: "target.user"]),
                "moqui.security.UserLoginKey": new FinderStub(listResult: [[userId: "TARGET_USER", loginKey: "key-3"]]),
        ], service, message)

        assertFalse(AdminUserSupport.resetPassword(ec, "TARGET_USER", "weak"))
        assertTrue(service.calls*.serviceName.contains("org.moqui.impl.UserServices.update#PasswordInternal"),
                "the framework call itself must still happen")
        assertFalse(service.calls*.serviceName.contains("delete#moqui.security.UserLoginKey"),
                "no login-key revocation when the framework password update failed")
        assertFalse(service.calls*.serviceName.contains("create#darpan.admin.AdminAuditLog"),
                "no audit row when the framework password update failed")
    }

    @Test
    void resetPasswordFailsForUnknownUserWritesNothing() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(["moqui.security.UserAccount": new FinderStub(oneResult: null)], service, message)

        assertFalse(AdminUserSupport.resetPassword(ec, "NOPE", "Temp#Pass2"))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size())
    }

    @Test
    void mutationsDeniedForPlainUser() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "PLAIN_USER"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER", userId: "PLAIN_USER"])]),
                service: service, message: message)

        assertNull(AdminUserSupport.createUser(ec, "x", "X", null, "Temp#Pass1"))
        assertFalse(AdminUserSupport.setUserDisabled(ec, "OTHER", true))
        assertFalse(AdminUserSupport.resetPassword(ec, "OTHER", "Temp#Pass1"))
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
