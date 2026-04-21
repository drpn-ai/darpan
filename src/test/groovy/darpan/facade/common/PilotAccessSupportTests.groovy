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
        assertEquals(null, scope.activeCompanyUserGroupId)
        assertEquals([], scope.availableCompanies)
    }

    @Test
    void buildAccessScopeUsesPreferredCompanyWhenMembershipIsValid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        Map<String, Object> scope = PilotAccessSupport.buildAccessScope(ec)

        assertFalse(scope.isSuperAdmin as boolean)
        assertEquals("COMPANY", scope.scopeType)
        assertEquals("KREWE", scope.customerScopeId)
        assertEquals("KREWE", scope.activeCompanyUserGroupId)
        assertEquals("Krewe", scope.activeCompanyLabel)
        assertEquals([
                [userGroupId: "ACME", label: "Acme"],
                [userGroupId: "KREWE", label: "Krewe"],
        ], scope.availableCompanies)
    }

    @Test
    void buildAccessScopeFallsBackToFirstAvailableCompanyWhenPreferenceIsInvalid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "MISSING",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        Map<String, Object> scope = PilotAccessSupport.buildAccessScope(ec)

        assertEquals("ACME", scope.customerScopeId)
        assertEquals("ACME", scope.activeCompanyUserGroupId)
        assertEquals("Acme", scope.activeCompanyLabel)
    }

    @Test
    void resolveGenericOutputLocationUsesCustomerScopedFolderForNonAdmin() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "pilot.customer", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "pilot.customer", preferences: [
                        (PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        String outputLocation = PilotAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/company/KREWE/output", outputLocation)
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

    @Test
    void requireCompanyRecordAccessRejectsCrossCompanyRecord() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "CUSTOMER_A", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "CUSTOMER_A", preferences: [
                        (PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder]),
                message: message
        )

        PilotAccessSupport.requireCompanyRecordAccess(ec, [companyUserGroupId: "ACME"],
                "Schema not found", "Schema is not available in your active company.")

        assertTrue(message.hasError())
        assertEquals(["Schema is not available in your active company."], message.errors)
    }

    @Test
    void assignCompanyOwnershipOnCreateUsesActiveCompanyAndCreator() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER", preferences: [
                (PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY): "KREWE",
        ])
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )
        Map<String, Object> newValue = [:]

        PilotAccessSupport.assignCompanyOwnershipOnCreate(newValue, ec)

        assertEquals("KREWE", newValue.companyUserGroupId)
        assertEquals("EX_USER", newValue.createdByUserId)
    }

    @Test
    void saveActiveCompanyPersistsRequestedCompanyWhenMembershipIsValid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        boolean saved = PilotAccessSupport.saveActiveCompany(ec, "KREWE")

        assertTrue(saved)
        assertEquals("KREWE", user.preferences[PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY])
    }

    @Test
    void saveActiveCompanyRejectsRequestedCompanyOutsideUserMembership() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder]),
                message: message
        )

        boolean saved = PilotAccessSupport.saveActiveCompany(ec, "OTHER")

        assertFalse(saved)
        assertEquals(null, user.preferences[PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY])
        assertEquals([PilotAccessSupport.ACTIVE_COMPANY_UNAVAILABLE_MESSAGE], message.errors)
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
        Map<String, Object> preferences = [:]

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
            return oneResult
        }

        List list() {
            return listResult
        }
    }
}
