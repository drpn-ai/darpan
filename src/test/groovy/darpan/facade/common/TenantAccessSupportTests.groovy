package darpan.facade.common

import darpan.reconciliation.support.FakeEntityFacade
import darpan.reconciliation.support.FakeEntityFind
import darpan.reconciliation.support.FakeTenantEc.MessageFacadeStub
import darpan.reconciliation.support.FakeTenantEc.UserStub
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class TenantAccessSupportTests {

    @Test
    void buildAccessScopeTreatsAdminGroupMemberWithoutTenantMembershipAsTenantScopedWithoutActiveTenant() {
        TableRows adminFinder = new TableRows(rows: [[userGroupId: "ADMIN", userId: "EX_ADMIN"]])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN"),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupMember": adminFinder])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertTrue(scope.isSuperAdmin as boolean)
        assertEquals("TENANT", scope.scopeType)
        assertEquals(null, scope.customerScopeId)
        assertEquals(null, scope.activeTenantUserGroupId)
        assertEquals([], scope.availableTenants)
        assertFalse(scope.canViewActiveTenantData as boolean)
        assertFalse(scope.canRunActiveTenantReconciliation as boolean)
        assertFalse(scope.canEditActiveTenantData as boolean)
        assertTrue(scope.canManageDarpanCore as boolean)
    }

    @Test
    void buildAccessScopeUsesConfiguredTenantsForAdminWithoutMemberships() {
        TableRows adminFinder = new TableRows(rows: [[userGroupId: "ADMIN", userId: "EX_ADMIN"]])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
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
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertTrue(scope.canRunActiveTenantReconciliation as boolean)
        assertTrue(scope.canEditActiveTenantData as boolean)
        assertTrue(scope.canManageDarpanCore as boolean)
    }

    @Test
    void buildAccessScopeTreatsDarpanSuperAdminGroupAsSuperAdmin() {
        TableRows adminFinder = new TableRows(rows: [[
                userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                userId     : "EX_ADMIN",
        ]])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertTrue(scope.isSuperAdmin as boolean)
        assertEquals("KREWE", scope.activeTenantUserGroupId)
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertTrue(scope.canRunActiveTenantReconciliation as boolean)
        assertTrue(scope.canEditActiveTenantData as boolean)
        assertTrue(scope.canManageDarpanCore as boolean)
    }

    private static def darpanAdminOnlyEc(Map extra = [:]) {
        TableRows adminFinder = new TableRows(rows: [[
                userGroupId: TenantAccessSupport.DARPAN_ADMIN_GROUP_ID,
                userId     : "EX_DARPAN_ADMIN",
        ] + extra])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        return executionContext(
                user: new UserStub(userId: "EX_DARPAN_ADMIN"),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )
    }

    @Test
    void buildAccessScopeGivesADarpanAdminEveryTenantAsTenantAdmin() {
        def ec = darpanAdminOnlyEc()

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertFalse(scope.isSuperAdmin as boolean)
        assertEquals(["GORJANA", "KREWE"] as Set, (scope.availableTenants*.userGroupId) as Set)
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertTrue(scope.canRunActiveTenantReconciliation as boolean)
        assertTrue(scope.canEditActiveTenantData as boolean)
        assertTrue(scope.canManageDarpanCore as boolean)
        assertEquals(["DARPAN_ADMIN", "DARPAN_TENANT_ADMIN", "DARPAN_TENANT_USER"], scope.activeTenantPermissionGroupIds)
        assertTrue(TenantAccessSupport.isTenantAdmin(ec, "GORJANA"))
    }

    @Test
    void endingTheDarpanAdminRoleEndsEveryTenantAccess() {
        // The membership row stays; its thruDate passes, and conditionDate(fromDate, thruDate, now) stops finding it.
        def ec = darpanAdminOnlyEc()
        ec.entity.rows("moqui.security.UserGroupMember").each { it.thruDate = new Timestamp(System.currentTimeMillis() - 60_000L) }

        assertFalse(TenantAccessSupport.isDarpanAdmin(ec))
        assertEquals([], TenantAccessSupport.listAvailableTenants(ec))
    }

    @Test
    void aDarpanAdminIsNotASuperAdmin() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows adminFinder = new TableRows(rows: [[
                userGroupId: TenantAccessSupport.DARPAN_ADMIN_GROUP_ID,
                userId     : "EX_DARPAN_ADMIN",
        ]])
        def ec = executionContext(
                user: new UserStub(userId: "EX_DARPAN_ADMIN"),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupMember": adminFinder]),
                message: message
        )

        assertFalse(TenantAccessSupport.isSuperAdmin(ec))
        assertFalse(TenantAccessSupport.requireSuperAdmin(ec))
    }

    @Test
    void requireDarpanAdminPassesASuperAdmin() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows adminFinder = new TableRows(rows: [[
                userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                userId     : "EX_ADMIN",
        ]])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN"),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupMember": adminFinder]),
                message: message
        )

        assertTrue(TenantAccessSupport.requireDarpanAdmin(ec, "Darpan admin required."))
        assertEquals([], message.errors)
    }

    @Test
    void canAccessTenantRecordRequiresActiveTenantMatchForSuperAdmin() {
        TableRows adminFinder = new TableRows(rows: [[userGroupId: "ADMIN", userId: "EX_ADMIN"]])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
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
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
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
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "ACME", userId: "EX_USER", description: "Acme", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "MISSING",
                ]),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals("ACME", scope.customerScopeId)
        assertEquals("ACME", scope.activeTenantUserGroupId)
        assertEquals("Acme", scope.activeTenantLabel)
    }

    @Test
    void buildAccessScopeUsesPermissionAssignmentsForTheSelectedTenant() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "GORJANA", userId: "EX_USER", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "GORJANA", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID], scope.activeTenantPermissionGroupIds)
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertFalse(scope.canRunActiveTenantReconciliation as boolean)
        assertFalse(scope.canEditActiveTenantData as boolean)
    }

    @Test
    void buildAccessScopeAllowsTenantUserToRunButNotEditActiveTenantData() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals([TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID], scope.activeTenantPermissionGroupIds)
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertTrue(scope.canRunActiveTenantReconciliation as boolean)
        assertFalse(scope.canEditActiveTenantData as boolean)
        assertTrue(TenantAccessSupport.hasActiveTenantRunAccess(ec))
        assertFalse(TenantAccessSupport.hasActiveTenantWriteAccess(ec))
    }

    @Test
    void buildAccessScopeAllowsTenantAdminToRunAndEditActiveTenantData() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        Map<String, Object> scope = TenantAccessSupport.buildAccessScope(ec)

        assertEquals([TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID], scope.activeTenantPermissionGroupIds)
        assertTrue(scope.canViewActiveTenantData as boolean)
        assertTrue(scope.canRunActiveTenantReconciliation as boolean)
        assertTrue(scope.canEditActiveTenantData as boolean)
    }

    @Test
    void resolveGenericOutputLocationUsesCustomerScopedFolderForNonAdmin() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "test.customer", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "test.customer", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/tenant/KREWE/output", outputLocation)
    }

    @Test
    void resolveGenericOutputLocationUsesTenantScopedFolderForAdminWithActiveTenant() {
        TableRows adminFinder = new TableRows(rows: [[userGroupId: "ADMIN", userId: "EX_ADMIN"]])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_ADMIN", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)

        assertEquals("runtime://tmp/reconciliation/generic/tenant/KREWE/output", outputLocation)
    }

    @Test
    void requireTenantRecordAccessRejectsCrossTenantRecord() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "CUSTOMER_A", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "CUSTOMER_A", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder]),
                message: message
        )

        TenantAccessSupport.requireTenantRecordAccess(ec, [companyUserGroupId: "ACME"], "Schema not found")

        // DAR-BE-135: another tenant's row answers exactly as a missing one does.
        assertTrue(message.hasError())
        assertEquals(["Schema not found"], message.errors)
    }

    @Test
    void assignTenantOwnershipOnCreateUsesActiveTenantAndCreator() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
        ])
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
        )
        Map<String, Object> newValue = [:]

        TenantAccessSupport.assignTenantOwnershipOnCreate(newValue, ec)

        assertEquals("KREWE", newValue.companyUserGroupId)
        assertEquals("EX_USER", newValue.createdByUserId)
    }

    @Test
    void saveActiveTenantPersistsRequestedTenantWhenMembershipIsValid() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        boolean saved = TenantAccessSupport.saveActiveTenant(ec, "KREWE")

        assertTrue(saved)
        assertEquals("KREWE", user.preferences[TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY])
    }

    @Test
    void buildAccessScopeSyncsActiveTenantIntoUserContext() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
        ])
        UserStub user = new UserStub(userId: "EX_USER", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
        ])
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ])
        )

        TenantAccessSupport.buildAccessScope(ec)

        assertEquals("KREWE", user.context.activeTenantUserGroupId)
        assertEquals("Krewe", user.context.activeTenantLabel)
        assertEquals(["KREWE"], user.context.availableTenantUserGroupIds)
        assertEquals([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], user.context.activeTenantPermissionGroupIds)
        assertTrue(user.context.canViewActiveTenantData as boolean)
        assertTrue(user.context.canRunActiveTenantReconciliation as boolean)
        assertTrue(user.context.canEditActiveTenantData as boolean)
        assertFalse(user.context.canManageDarpanCore as boolean)
        assertFalse(user.context.isSuperAdmin as boolean)
        assertEquals("TENANT", user.context.scopeType)
    }

    @Test
    void canEditActiveTenantDataRequiresExplicitEditorAssignment() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder])
        )

        assertFalse(TenantAccessSupport.canEditActiveTenantData(ec))
        assertEquals([], TenantAccessSupport.buildAccessScope(ec).activeTenantPermissionGroupIds)
    }

    @Test
    void requireActiveTenantRunAccessAllowsTenantUserRole() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                message: message
        )

        boolean allowed = TenantAccessSupport.requireActiveTenantRunAccess(ec, "Tenant cannot run reconciliation.")

        assertTrue(allowed)
        assertEquals([], message.errors)
    }

    @Test
    void saveActiveTenantAllowsAdminWhenTenantIsConfigured() {
        TableRows adminFinder = new TableRows(rows: [[userGroupId: "ADMIN", userId: "EX_ADMIN"]])
        TableRows companyGroupFinder = new TableRows(rows: [
                [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_ADMIN")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupMember": adminFinder,
                        "moqui.security.UserGroup"      : companyGroupFinder,
                ])
        )

        boolean saved = TenantAccessSupport.saveActiveTenant(ec, "GORJANA")

        assertTrue(saved)
        assertEquals("GORJANA", user.preferences[TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY])
    }

    @Test
    void sessionInfoUsesActiveTenantTimezoneBeforeUserAccountTimezone() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows tenantSettingsFinder = new TableRows(rows: [[
                companyUserGroupId: "KREWE",
                timeZone          : "Europe/London",
        ]])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", username: "test.user", userAccount: [timeZone: "Asia/Kolkata"], preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"       : companyFinder,
                        (TenantAccessSupport.TENANT_SETTING_ENTITY_NAME): tenantSettingsFinder,
                ])
        )

        Map<String, Object> sessionInfo = TenantAccessSupport.buildSessionInfo(ec)

        assertEquals("Europe/London", sessionInfo.timeZone)
    }

    /**
     * OC70 (S19 QA). For windows and dates, a tenant with no zone reads as UTC, as the scheduler does (tenantTimeZoneFor). It
     * used to fall back to the signed-in user's zone, then l10n, so two users of one tenant got different windows for the
     * same dates. What a person SEES is unchanged (Aditi 14:38: display follows the user's preferred timezone).
     */
    @Test
    void aTenantWithNoZoneComputesWindowsInUtcButShowsTimesInTheUsersZone() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", userAccount: [timeZone: "Asia/Kolkata"], preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                l10n: new Expando(timeZone: "Europe/Paris"),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"             : companyFinder,
                        (TenantAccessSupport.TENANT_SETTING_ENTITY_NAME): new TableRows(),
                ])
        )

        assertEquals("UTC", TenantAccessSupport.resolveActiveTenantTimeZone(ec))
        assertEquals(TenantAccessSupport.tenantTimeZoneFor(ec, "KREWE").id, TenantAccessSupport.resolveActiveTenantTimeZone(ec))
        assertEquals("Asia/Kolkata", TenantAccessSupport.resolveActiveTenantDisplayTimeZone(ec))
        assertEquals("Asia/Kolkata", TenantAccessSupport.readActiveTenantSettings(ec).timeZone)
    }

    @Test
    void aStoredZoneThatIsNotAZoneIsUtcLikeTheScheduler() {
        assertEquals("UTC", TenantAccessSupport.resolveTenantSettingsTimeZone([timeZone: "Mars/Olympus"]))
        assertEquals("Europe/London", TenantAccessSupport.resolveTenantSettingsTimeZone([timeZone: " Europe/London "]))
    }

    @Test
    void buildSessionInfoExposesRawUserAndTenantTimeZones() {
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "GORJANA", userId: "EX_USER", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows tenantSettingFinder = new TableRows(rows: [[companyUserGroupId: "GORJANA", timeZone: "America/New_York"]])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", userAccount: [timeZone: "Asia/Kolkata"], preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "GORJANA",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"             : companyFinder,
                        (TenantAccessSupport.TENANT_SETTING_ENTITY_NAME): tenantSettingFinder,
                ])
        )

        Map<String, Object> sessionInfo = TenantAccessSupport.buildSessionInfo(ec)

        assertEquals("Asia/Kolkata", sessionInfo.userTimeZone)
        assertEquals("America/New_York", sessionInfo.tenantTimeZone)
    }

    @Test
    void saveActiveTenantSettingsStoresTimezoneForActiveTenant() {
        Timestamp now = Timestamp.valueOf("2026-05-02 10:00:00")
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
        ])
        ServiceFacadeStub service = new ServiceFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", nowTimestamp: now, preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                service: service
        )

        Map<String, Object> settings = TenantAccessSupport.saveActiveTenantSettings(ec, "Europe/London")

        assertEquals("KREWE", settings.companyUserGroupId)
        assertEquals("Europe/London", settings.timeZone)
        assertEquals("store#darpan.auth.TenantSetting", service.lastCall.serviceName)
        assertEquals("KREWE", service.lastCall.parametersMap.companyUserGroupId)
        assertEquals("EX_USER", service.lastCall.parametersMap.createdByUserId)
        assertEquals("Europe/London", service.lastCall.parametersMap.timeZone)
        assertEquals(now, service.lastCall.parametersMap.createdDate)
        assertEquals(now, service.lastCall.parametersMap.lastUpdatedDate)
    }

    @Test
    void saveActiveTenantSettingsRejectsInvalidTimezone() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
        ])
        ServiceFacadeStub service = new ServiceFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                message: message,
                service: service
        )

        TenantAccessSupport.saveActiveTenantSettings(ec, "Not/AZone")

        assertEquals(["Timezone is invalid."], message.errors)
        assertEquals(null, service.lastCall)
    }

    @Test
    void saveUserSettingsPersistsValidTimeZoneToUserAccount() {
        ServiceFacadeStub service = new ServiceFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER"),
                service: service,
        )

        boolean saved = TenantAccessSupport.saveUserSettings(ec, "HotWax", "Asia/Kolkata")

        assertTrue(saved)
        assertEquals("update#moqui.security.UserAccount", service.lastCall.serviceName)
        assertEquals("EX_USER", service.lastCall.parametersMap.userId)
        assertEquals("Asia/Kolkata", service.lastCall.parametersMap.timeZone)
    }

    @Test
    void saveUserSettingsRejectsInvalidTimeZoneWithoutWriting() {
        ServiceFacadeStub service = new ServiceFacadeStub()
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                service: service,
                message: message,
        )

        boolean saved = TenantAccessSupport.saveUserSettings(ec, "HotWax", "Not/AZone")

        assertFalse(saved)
        assertTrue(message.errors.contains("Timezone is invalid."))
        assertEquals(null, service.lastCall)
        // Validation must run BEFORE any write: an invalid timezone leaves displayName untouched
        // too, not just the UserAccount row — a rejected save must be all-or-nothing.
        assertTrue(user.preferenceCalls.isEmpty())
    }

    @Test
    void saveUserSettingsWithTimeZoneOnlyLeavesDisplayNamePreferenceUntouched() {
        ServiceFacadeStub service = new ServiceFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(user: user, service: service)

        boolean saved = TenantAccessSupport.saveUserSettings(ec, null, "Asia/Kolkata")

        assertTrue(saved)
        assertEquals("update#moqui.security.UserAccount", service.lastCall.serviceName)
        assertEquals("Asia/Kolkata", service.lastCall.parametersMap.timeZone)
        // rawDisplayName == null means the caller didn't send displayName (a timezone-only save),
        // so the existing preference must be left alone — not overwritten with a normalized null.
        assertTrue(user.preferenceCalls.isEmpty())
    }

    @Test
    void saveUserSettingsRefreshesUserAccountSoBuildSessionInfoSeesNewTimeZone() {
        Map<String, Object> backingRow = [timeZone: "UTC"]
        RefreshableUserAccountStub userAccount = new RefreshableUserAccountStub(backingRow)
        ServiceFacadeStub service = new ServiceFacadeStub(onCall: { String serviceName, Map<String, Object> parametersMap ->
            if (serviceName == "update#moqui.security.UserAccount") {
                backingRow.timeZone = parametersMap.timeZone
            }
        })
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", userAccount: userAccount),
                service: service,
        )

        boolean saved = TenantAccessSupport.saveUserSettings(ec, null, "Asia/Kolkata")

        assertTrue(saved)
        // ec.user.userAccount is a per-request snapshot (populated once, like the real
        // UserFacadeImpl) — without saveUserSettings refreshing it after a successful write,
        // this read of the SAME ec still sees the stale pre-save value.
        Map<String, Object> sessionInfo = TenantAccessSupport.buildSessionInfo(ec)
        assertEquals("Asia/Kolkata", sessionInfo.userTimeZone)
    }

    @Test
    void saveUserSettingsClearsTimeZoneOnEmptyString() {
        ServiceFacadeStub service = new ServiceFacadeStub()
        def ec = executionContext(user: new UserStub(userId: "EX_USER"), service: service)

        boolean saved = TenantAccessSupport.saveUserSettings(ec, "HotWax", "")

        assertTrue(saved)
        assertEquals("update#moqui.security.UserAccount", service.lastCall.serviceName)
        assertEquals(null, service.lastCall.parametersMap.timeZone)
    }

    @Test
    void saveUserSettingsLeavesTimeZoneUntouchedWhenParameterAbsent() {
        ServiceFacadeStub service = new ServiceFacadeStub()
        def ec = executionContext(user: new UserStub(userId: "EX_USER"), service: service)

        boolean saved = TenantAccessSupport.saveUserSettings(ec, "HotWax", null)

        assertTrue(saved)
        assertEquals(null, service.lastCall)
    }

    @Test
    void saveActiveTenantRejectsRequestedTenantOutsideUserMembership() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        UserStub user = new UserStub(userId: "EX_USER")
        def ec = executionContext(
                user: user,
                entity: new EntityFacadeStub(tables: ["moqui.security.UserGroupAndMember": companyFinder]),
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
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                message: message
        )

        boolean allowed = TenantAccessSupport.requireActiveTenantWriteAccess(ec, "View only tenant.")

        assertFalse(allowed)
        assertEquals(["View only tenant."], message.errors)
    }

    @Test
    void requireActiveTenantWriteAccessRejectsTenantUserRole() {
        MessageFacadeStub message = new MessageFacadeStub()
        TableRows companyFinder = new TableRows(rows: [
                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
        ])
        TableRows permissionFinder = new TableRows(rows: [
                [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID],
        ])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember"                     : companyFinder,
                        (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): permissionFinder,
                ]),
                message: message
        )

        boolean allowed = TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Tenant user cannot edit.")

        assertFalse(allowed)
        assertEquals(["Tenant user cannot edit."], message.errors)
    }

    @Test
    void listAvailableTenantsExcludesDeactivatedTenantForMember() {
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER"),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember": new TableRows(rows: [
                                [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                                [userGroupId: "ACME", userId: "EX_USER", description: "Acme",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.auth.TenantSetting": new TableRows(rows: [
                                [companyUserGroupId: "ACME", disabled: "Y"],
                        ]),
                ]))

        List tenants = TenantAccessSupport.listAvailableTenants(ec)

        assertEquals(["KREWE"], tenants*.userGroupId, "deactivated ACME must be excluded")
    }

    /**
     * S26a. Moqui's one() keeps only the full primary key, so a groupTypeEnumId condition beside userGroupId was dropped
     * and ADMIN's description came back as a "tenant label". The shared FakeEntityFind answers like Moqui does.
     */
    @Test
    void aNonTenantGroupNeverLendsItsDescriptionAsATenantLabel() {
        def ec = executionContext(entity: new EntityFacadeStub(tables: [
                "moqui.security.UserGroup": new TableRows(rows: [[
                        userGroupId: "ADMIN", description: "Administrators", groupTypeEnumId: "UgtMoqui"]]),
        ]))

        assertEquals("ADMIN", TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, "ADMIN"))
        assertNull(TenantAccessSupport.findTenantGroup(ec, "ADMIN"))
    }

    @Test
    void aTenantGroupLendsItsDescription() {
        def ec = executionContext(entity: new EntityFacadeStub(tables: [
                "moqui.security.UserGroup": new TableRows(rows: [[
                        userGroupId: "KREWE", description: "Krewe",
                        groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]]),
        ]))

        assertEquals("Krewe", TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, "KREWE"))
    }

    /** S26a. With no active tenant, the session's last run was the user's latest in ANY tenant, read with authz off. */
    @Test
    void lastRunIsNeverReadOutsideTheActiveTenant() {
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER"),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupAndMember": new TableRows(rows: []),
                        (darpan.common.DarpanEntityConstants.RECONCILIATION_RUN_RESULT): new TableRows(rows: [[
                                reconciliationRunResultId: "RR_1", createdByUserId: "EX_USER",
                                companyUserGroupId: "TENANT_LEFT_BEHIND", savedRunId: "RS_1"]]),
                ]))

        assertNull(TenantAccessSupport.resolveLastRun(ec))
    }

    @Test
    void listAvailableTenantsExcludesDeactivatedTenantForSuperAdmin() {
        def ec = executionContext(
                user: new UserStub(userId: "SA_USER"),
                entity: new EntityFacadeStub(tables: [
                        "moqui.security.UserGroupMember": new TableRows(rows: [[
                                userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                                userId     : "SA_USER",
                        ]]),
                        "moqui.security.UserGroup": new TableRows(rows: [
                                [userGroupId: "KREWE", description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                                [userGroupId: "ACME", description: "Acme",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.auth.TenantSetting": new TableRows(rows: [
                                [companyUserGroupId: "ACME", disabled: "Y"],
                        ]),
                ]))

        List tenants = TenantAccessSupport.listAvailableTenants(ec)

        assertEquals(["KREWE"], tenants*.userGroupId)
    }

    // Regression guard for the live-fence-proof finding fixed in 6f13dc3: darpan.auth.TenantSetting's
    // only ArtifactAuthz grant is the framework ADMIN group, but listDisabledTenantIds runs on
    // EVERY request via syncUserContext()'s <before-request> hook — before the target endpoint's
    // own authz check — so an unguarded read there 403'd every non-ADMIN authenticated user on
    // every request, system-wide. The two tests above only assert the filtered tenant list, which
    // passes identically whether or not disableAuthz() is called; this test pins the guard itself
    // so a future refactor that drops it fails CI instead of only failing a live-server proof.
    @Test
    void listAvailableTenantsDisablesAuthzOnTenantSettingFinderForDisabledTenantLookup() {
        EntityFacadeStub entityFacadeStub = new EntityFacadeStub(tables: [
                "moqui.security.UserGroupMember": new TableRows(rows: [[
                        userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                        userId     : "SA_USER",
                ]]),
                "moqui.security.UserGroup": new TableRows(rows: [
                        [userGroupId: "KREWE", description: "Krewe",
                         groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                ]),
                "darpan.auth.TenantSetting": new TableRows(rows: []),
        ])
        def ec = executionContext(user: new UserStub(userId: "SA_USER"), entity: entityFacadeStub)

        TenantAccessSupport.listAvailableTenants(ec)

        List<FakeEntityFind> tenantSettingFinders = entityFacadeStub.issuedFor("darpan.auth.TenantSetting")
        assertTrue(tenantSettingFinders && tenantSettingFinders.every { it.authzDisabled },
                "listDisabledTenantIds must call disableAuthz() on its TenantSetting finder — that " +
                "entity's only ArtifactAuthz grant is the framework ADMIN group, and this read runs " +
                "on every request via the before-request hook, for every user")
    }

    // ------------------------------------------------------------------
    // DAR-BE-005 — per-tenant admin check for an ARBITRARY tenant
    // ------------------------------------------------------------------

    @Test
    void isTenantAdminIsTrueWhenUserHoldsTenantAdminInThatSpecificTenant() {
        def ec = executionContext(
                user: new UserStub(userId: "aditi"),
                entity: new EntityFacadeStub(tables: [
                        "darpan.auth.TenantUserPermissionGroupMember": new TableRows(rows: [
                                [userId: "aditi", tenantUserGroupId: "STEVE_MADDEN",
                                 permissionUserGroupId: "DARPAN_TENANT_ADMIN", thruDate: null],
                        ]),
                        "moqui.security.UserGroupMember": new TableRows(rows: []),
                ])
        )

        assertTrue(TenantAccessSupport.isTenantAdmin(ec, "STEVE_MADDEN"),
                "A DARPAN_TENANT_ADMIN row for the named tenant must satisfy isTenantAdmin")
    }

    @Test
    void isTenantAdminIsFalseForATenantTheUserOnlyEditsOrDoesNotAdminister() {
        def ec = executionContext(
                user: new UserStub(userId: "aditi"),
                entity: new EntityFacadeStub(tables: [
                        "darpan.auth.TenantUserPermissionGroupMember": new TableRows(rows: [
                                [userId: "aditi", tenantUserGroupId: "BETSEY_JOHNSON",
                                 permissionUserGroupId: "DARPAN_COMPANY_EDITOR", thruDate: null],
                        ]),
                        "moqui.security.UserGroupMember": new TableRows(rows: []),
                ])
        )

        assertFalse(TenantAccessSupport.isTenantAdmin(ec, "BETSEY_JOHNSON"),
                "DARPAN_COMPANY_EDITOR is not tenant admin — it must not authorize a credential grant")
        assertFalse(TenantAccessSupport.isTenantAdmin(ec, "THIRD_LOVE"),
                "A tenant with no permission row at all must be denied (default-deny)")
    }

    @Test
    void isTenantAdminIsFalseForBlankTenantAndAnonymousUser() {
        def ec = executionContext(user: new UserStub(userId: "aditi"))
        assertFalse(TenantAccessSupport.isTenantAdmin(ec, null), "null tenant must be denied")
        assertFalse(TenantAccessSupport.isTenantAdmin(ec, "  "), "blank tenant must be denied")

        def anonymous = executionContext(user: new UserStub(userId: null))
        assertFalse(TenantAccessSupport.isTenantAdmin(anonymous, "STEVE_MADDEN"),
                "An unauthenticated caller must never be tenant admin")
    }

    @Test
    void isTenantAdminIsTrueForSuperAdminOnAnyTenant() {
        def ec = executionContext(
                user: new UserStub(userId: "root"),
                entity: new EntityFacadeStub(tables: [
                        "darpan.auth.TenantUserPermissionGroupMember": new TableRows(rows: []),
                        "moqui.security.UserGroupMember": new TableRows(rows: [[
                                userId: "root", userGroupId: "DARPAN_SUPER_ADMIN", thruDate: null
                        ]]),
                ])
        )

        assertTrue(TenantAccessSupport.isTenantAdmin(ec, "ANY_TENANT"),
                "Super admin already holds DARPAN_TENANT_ADMIN on the active tenant via " +
                "resolveActiveTenantPermissionScope; isTenantAdmin must stay consistent with that")
    }

    @Test
    void requireTenantAdminAddsAnErrorAndReturnsFalseWhenDenied() {
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "aditi"),
                message: message,
                entity: new EntityFacadeStub(tables: [
                        "darpan.auth.TenantUserPermissionGroupMember": new TableRows(rows: []),
                        "moqui.security.UserGroupMember": new TableRows(rows: []),
                ])
        )

        assertFalse(TenantAccessSupport.requireTenantAdmin(ec, "THIRD_LOVE", "nope"), "denied must be false")
        assertTrue(message.hasError(), "requireTenantAdmin must add an ec.message error when it denies")
        assertTrue(message.errors.first().contains("nope"), "the caller's message must reach the user")
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

    // Models the real ec.user.userAccount contract just enough for the refresh-after-write test:
    // a per-request EntityValue snapshot (stale timeZone) whose refresh() re-reads a backing row
    // that the ServiceFacadeStub's onCall hook mutates when update#moqui.security.UserAccount is
    // "called" — mirroring how EntityValueBase.refresh() re-reads the DB after another service's
    // write, per EntityValueBase.java:1794.
    static class RefreshableUserAccountStub {
        private final Map<String, Object> backingRow
        String timeZone
        String userFullName = null
        String emailAddress = null

        RefreshableUserAccountStub(Map<String, Object> backingRow) {
            this.backingRow = backingRow
            this.timeZone = backingRow.timeZone
        }

        void refresh() {
            timeZone = backingRow.timeZone
        }
    }

    /** One table's rows for {@link EntityFacadeStub}: the shared FakeEntityFind applies the conditions, as Moqui would. */
    static class TableRows {
        List<Map> rows = []
    }

    /**
     * ec.entity over the shared {@link FakeEntityFacade}: keys from the entity XML, a fresh finder per find(), and one() by a
     * full primary key drops any other equality condition. Tables the test does not give are empty.
     */
    static class EntityFacadeStub extends FakeEntityFacade {
        void setTables(Map<String, TableRows> tables) {
            tables.each { String name, TableRows t -> table(name, t.rows) }
        }
    }

    static class ServiceFacadeStub {
        ServiceCallStub lastCall

        // Optional side effect invoked as call(serviceName, parametersMap) when .call() runs —
        // lets a test simulate the real service actually writing a row (e.g. so a
        // RefreshableUserAccountStub's backing map reflects the write). Null by default so every
        // pre-existing test that doesn't need it is unaffected.
        Closure onCall

        ServiceCallStub sync() {
            lastCall = new ServiceCallStub(onCall: onCall)
            return lastCall
        }
    }

    static class ServiceCallStub {
        String serviceName
        Map<String, Object> parametersMap = [:]
        Closure onCall

        ServiceCallStub name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        ServiceCallStub parameters(Map<String, Object> parametersMap) {
            this.parametersMap = parametersMap
            return this
        }

        Map<String, Object> call() {
            onCall?.call(serviceName, parametersMap)
            return [:]
        }
    }
}
