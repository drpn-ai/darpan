package darpan.facade.common

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class PilotAccessSupportTests {

    @Test
    void buildAccessScopeTreatsAdminGroupMemberAsGlobal() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN"),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupMember": adminFinder])
        )

        Map<String, Object> scope = PilotAccessSupport.buildAccessScope(ec)

        assertTrue(scope.isSuperAdmin as boolean)
        assertEquals("GLOBAL", scope.scopeType)
        assertEquals(null, scope.customerScopeId)
    }

    @Test
    void resolveGenericOutputLocationUsesCustomerScopedFolderForNonAdmin() {
        def ec = executionContext(user: new UserStub(userId: "pilot.customer"))

        String outputLocation = PilotAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/user/pilot.customer/output", outputLocation)
    }

    @Test
    void requireOwnedRecordAccessRejectsCrossCustomerRecord() {
        MessageFacadeStub message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "CUSTOMER_A"),
                message: message
        )

        PilotAccessSupport.requireOwnedRecordAccess(ec, [ownerUserId: "CUSTOMER_B"],
                "Schema not found", "Schema is not available in your customer scope.")

        assertTrue(message.hasError())
        assertEquals(["Schema is not available in your customer scope."], message.errors)
    }

    private static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user: overrides.user ?: new UserStub(),
                entity: overrides.entity ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
    }

    static class MessageFacadeStub {
        List<String> errors = []

        void addError(String error) {
            errors << error
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
            return oneResult
        }
    }
}
