package darpan.facade.common

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class TenantAccessSupportTests {

    @Test
    void buildAccessScopeTreatsAdminGroupMemberWithoutTenantMembershipAsTenantScopedWithoutActiveTenant() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN"),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupMember": adminFinder])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertTrue(scope.isSuperAdmin as boolean)
        assertEquals("TENANT", scope.scopeType)
        assertEquals(null, scope.customerScopeId)
        assertEquals(null, scope.activeTenantUserGroupId)
        assertEquals([], scope.availableTenants)
    }

    @Test
    void buildAccessScopeUsesConfiguredTenantsForAdminWithoutMemberships() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        FinderStub companyGroupFinder = new FinderStub(listResult: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertTrue(scope.isSuperAdmin as boolean)
        assertEquals("TENANT", scope.scopeType)
        assertEquals("KREWE", scope.customerScopeId)
        assertEquals("KREWE", scope.activeTenantUserGroupId)
        assertEquals("Krewe", scope.activeTenantLabel)
        assertEquals([
                [userGroupId: "GORJANA", label: "Gorjana"],
                [userGroupId: "KREWE", label: "Krewe"],
        ], scope.availableTenants)
    }

    @Test
    void canAccessTenantRecordRequiresActiveTenantMatchForSuperAdmin() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        FinderStub companyGroupFinder = new FinderStub(listResult: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        boolean wrongTenantAllowed = TenantAccessSupport.canAccessTenantRecord(ec, [companyUserGroupId: "GORJANA"])
        boolean activeTenantAllowed = TenantAccessSupport.canAccessTenantRecord(ec, [companyUserGroupId: "KREWE"])

        assertFalse(wrongTenantAllowed)
        assertTrue(activeTenantAllowed)
    }

    @Test
    void buildAccessScopeUsesPreferredTenantWhenMembershipIsValid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertFalse(scope.isSuperAdmin as boolean)
        assertEquals("TENANT", scope.scopeType)
        assertEquals("KREWE", scope.customerScopeId)
        assertEquals("KREWE", scope.activeTenantUserGroupId)
        assertEquals("Krewe", scope.activeTenantLabel)
        assertEquals([
                [userGroupId: "ACME", label: "Acme"],
                [userGroupId: "KREWE", label: "Krewe"],
        ], scope.availableTenants)
    }

    @Test
    void buildAccessScopeFallsBackToFirstAvailableTenantWhenPreferenceIsInvalid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "MISSING",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals("ACME", scope.customerScopeId)
        assertEquals("ACME", scope.activeTenantUserGroupId)
        assertEquals("Acme", scope.activeTenantLabel)
    }

    @Test
    void buildAccessScopeUsesPermissionAssignmentsForTheSelectedTenant() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "GORJANA", userId: "EX_USER", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        FinderStub permissionFinder = new FinderStub(listResult: [
                [tenantUserGroupId: "GORJANA", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID], scope.activeTenantPermissionGroupIds)
        assertFalse(scope.canEditActiveTenantData as boolean)
    }

    @Test
    void resolveGenericOutputLocationUsesCustomerScopedFolderForNonAdmin() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "test.customer", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "test.customer", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/tenant/KREWE/output", outputLocation)
    }

    @Test
    void resolveGenericOutputLocationUsesTenantScopedFolderForAdminWithActiveTenant() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        FinderStub companyGroupFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/tenant/KREWE/output", outputLocation)
    }

    @Test
    void requireOwnedRecordAccessRejectsCrossCustomerRecord() {
        MessageFacadeStub message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "CUSTOMER_A"),
                message: message
        )

        TenantAccessSupport.requireOwnedRecordAccess(ec, [ownerUserId: "CUSTOMER_B"],
                "Schema not found", "Schema is not available in your customer scope.")

        assertTrue(message.hasError())
        assertEquals(["Schema is not available in your customer scope."], message.errors)
    }

    @Test
    void requireTenantRecordAccessRejectsCrossTenantRecord() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "CUSTOMER_A", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "CUSTOMER_A", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder]),
                message: message
        )

        TenantAccessSupport.requireTenantRecordAccess(ec, [companyUserGroupId: "ACME"],
                "Schema not found", "Schema is not available in your active tenant.")

        assertTrue(message.hasError())
        assertEquals(["Schema is not available in your active tenant."], message.errors)
    }

    @Test
    void assignTenantOwnershipOnCreateUsesActiveTenantAndCreator() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
        ])
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )
        Map<String, Object> newValue = [:]

        TenantAccessSupport.assignTenantOwnershipOnCreate(newValue, ec)

        assertEquals("KREWE", newValue.companyUserGroupId)
        assertEquals("EX_USER", newValue.createdByUserId)
    }

    @Test
    void saveActiveTenantPersistsRequestedTenantWhenMembershipIsValid() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        boolean saved = TenantAccessSupport.saveActiveTenant(ec, "KREWE")

        assertTrue(saved)
        assertEquals("KREWE", user.preferences[TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY])
    }

    @Test
    void buildAccessScopeSyncsActiveTenantIntoUserContext() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        FinderStub permissionFinder = new FinderStub(listResult: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
        ])
        UserStub user = new UserStub(userId: "EX_USER", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
        ])
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        TenantAccessSupport.buildAccessScope(ec)

        assertEquals("KREWE", user.context.activeTenantUserGroupId)
        assertEquals("Krewe", user.context.activeTenantLabel)
        assertEquals(["KREWE"], user.context.availableTenantUserGroupIds)
        assertEquals([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], user.context.activeTenantPermissionGroupIds)
        assertTrue(user.context.canEditActiveTenantData as boolean)
        assertFalse(user.context.isSuperAdmin as boolean)
        assertEquals("TENANT", user.context.scopeType)
    }

    @Test
    void canEditActiveTenantDataRequiresExplicitEditorAssignment() {
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        assertFalse(TenantAccessSupport.canEditActiveTenantData(ec))
        assertEquals([], TenantAccessSupport.currentActiveTenantPermissionGroupIds(ec))
    }

    @Test
    void saveActiveTenantAllowsAdminWhenTenantIsConfigured() {
        FinderStub adminFinder = new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"])
        FinderStub companyGroupFinder = new FinderStub(listResult: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_ADMIN")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        boolean saved = TenantAccessSupport.saveActiveTenant(ec, "GORJANA")

        assertTrue(saved)
        assertEquals("GORJANA", user.preferences[TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY])
    }

    @Test
    void saveActiveTenantRejectsRequestedTenantOutsideUserMembership() {
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

        boolean saved = TenantAccessSupport.saveActiveTenant(ec, "OTHER")

        assertFalse(saved)
        assertEquals(null, user.preferences[TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY])
        assertEquals([TenantAccessSupport.ACTIVE_TENANT_UNAVAILABLE_MESSAGE], message.errors)
    }

    @Test
    void requireActiveTenantWriteAccessRejectsReadOnlyTenantRole() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub companyFinder = new FinderStub(listResult: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        FinderStub permissionFinder = new FinderStub(listResult: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                message: message
        )

        boolean allowed = TenantAccessSupport.requireActiveTenantWriteAccess(ec, "View only tenant.")

        assertFalse(allowed)
        assertEquals(["View only tenant."], message.errors)
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
        Map<String, Object> context = [:]

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
            return listResult.findAll { Object row ->
                if (!(row instanceof Map)) return true
                conditions.every { String field, Object value -> row[field] == value }
            }
        }
    }
}
