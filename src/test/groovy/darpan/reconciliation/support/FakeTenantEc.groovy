package darpan.reconciliation.support

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantAccessSupport

import java.sql.Timestamp

/**
 * DAR-OPS-029 S99-200: the stub ExecutionContext the tenant-scoping unit tests share (no Moqui). The user, message and
 * entity stand-ins used to be hand-copied into each test; ec.entity is a {@link FakeEntityFacade}, so finders answer as
 * Moqui's do (a full PK drops a tenant condition beside it; a fresh finder per find()).
 *
 * <p>{@link #tenantTables} declares the tables TenantAccessSupport reads to resolve the active tenant: memberships
 * (UserGroupAndMember, UserGroupMember), per-tenant permission groups and TenantSetting (disabled tenants).</p>
 */
class FakeTenantEc {
    static final List<String> USER_GROUP_MEMBER_PK = ["userGroupId", "userId", "fromDate"].asImmutable()

    /**
     * Declares the tenant tables on {@code entity} for {@code userId} as a member of each of {@code tenantIds}. A
     * table the test declared already is left alone, so a test can seed its own rows first.
     */
    static FakeEntityFacade tenantTables(FakeEntityFacade entity, String userId, List<String> tenantIds) {
        List<Map> memberships = tenantIds.collect { String tenant ->
            [userGroupId: tenant, userId: userId, description: tenant,
             groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID] as Map
        }
        declareIfAbsent(entity, "moqui.security.UserGroupAndMember", USER_GROUP_MEMBER_PK, memberships)
        declareIfAbsent(entity, "moqui.security.UserGroupMember", USER_GROUP_MEMBER_PK,
                memberships.collect { [userGroupId: it.userGroupId, userId: userId] as Map })
        declareIfAbsent(entity, DarpanEntityConstants.TENANT_USER_PERMISSION_GROUP_MEMBER,
                ["tenantUserGroupId", "userId", "permissionUserGroupId", "fromDate"], [])
        declareIfAbsent(entity, DarpanEntityConstants.TENANT_SETTING, ["companyUserGroupId"], [])
        return entity
    }

    /** An ec whose user {@code userId} is a member of, and active in, {@code activeTenant} (null: no tenant). */
    static Expando ec(String userId, String activeTenant, FakeEntityFacade entity = new FakeEntityFacade(), Map overrides = [:]) {
        tenantTables(entity, userId, activeTenant ? [activeTenant] : [])
        UserStub user = new UserStub(userId: userId)
        if (activeTenant) user.preferences.put(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, activeTenant)
        return new Expando([
                user    : user,
                entity  : entity,
                message : new MessageFacadeStub(),
                l10n    : new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:]),
        ] + overrides)
    }

    private static void declareIfAbsent(FakeEntityFacade entity, String name, List<String> pk, List<Map> rows) {
        if (entity.rows(name) == null) entity.table(name, pk, rows)
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")
        /** Every setPreference() call in order, so a test can tell "never written" from "written back unchanged". */
        List<Map<String, Object>> preferenceCalls = []

        Object getPreference(String key) { preferences[key] }

        void setPreference(String key, Object value) {
            preferenceCalls << [key: key, value: value]
            preferences[key] = value
        }
    }

    static class MessageFacadeStub {
        List<String> errors = []
        List<String> messages = []

        void addError(String msg) { errors << msg }

        void addMessage(String msg) { messages << msg }

        boolean hasError() { !errors.isEmpty() }
    }
}
