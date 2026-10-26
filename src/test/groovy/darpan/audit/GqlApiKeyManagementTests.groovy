package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-100 — read-only agent keys for the public GraphQL tier (moqui-gql gql.GqlAuthServices): mint, resolve, list,
 * disable, rotate. A key's digest never leaves the database; a refused key always reads as the same generic
 * "invalid API key".
 * DAR-BE-136 — a tenant admin (or super-admin) manages keys, and only for the tenant they are signed into: a key is
 * minted for the active tenant, even when the caller also administers another, and another tenant's key reads as missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GqlApiKeyManagementTests {

    private static final String AUTH = "gql.GqlAuthServices"
    private static final String TENANT = "TKEY_A"
    private static final String OTHER_TENANT = "TKEY_B"
    private static final String PLAIN_USER = "TKEY_PLAIN"
    private static final String ADMIN_USER = "TKEY_ADMIN"
    private static final String TENANT_ADMIN_USER = "TKEY_TADMIN"
    private static final Timestamp FROM_DATE = new Timestamp(System.currentTimeMillis() - 60000L)

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "gql-api-keys")
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "UserGroupType", description: "User Group Type"]).createOrUpdate()
        [TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID, "UgtDarpanPermission"].each { String enumId ->
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: enumId, enumTypeId: "UserGroupType",
                    description: enumId]).createOrUpdate()
        }
        [TENANT, OTHER_TENANT].each { String tenant ->
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: "Key tenant ${tenant}",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
        [TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID].each { String group ->
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: group, description: group,
                    groupTypeEnumId: "UgtDarpanPermission"]).createOrUpdate()
        }
        [(PLAIN_USER): "tkey.plain", (ADMIN_USER): "tkey.admin", (TENANT_ADMIN_USER): "tkey.tadmin"].each { String userId, String username ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: userId, username: username,
                    userFullName: username, emailAddress: "${username}@example.com".toString()]).createOrUpdate()
        }
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                description: "Darpan super admin"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                userId: ADMIN_USER, fromDate: FROM_DATE]).createOrUpdate()
        // The tenant admin administers BOTH tenants; the plain user is an ordinary member of the first.
        member(TENANT_ADMIN_USER, TENANT, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        member(TENANT_ADMIN_USER, OTHER_TENANT, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        member(PLAIN_USER, TENANT, TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID)
        signInAs("tkey.admin", TENANT)
        assertTrue(TenantAccessSupport.isSuperAdmin(ec), "the test admin must be a super-admin")
    }

    private void member(String userId, String tenant, String permissionGroupId) {
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenant, userId: userId,
                fromDate: FROM_DATE]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: tenant,
                userId: userId, permissionUserGroupId: permissionGroupId, fromDate: FROM_DATE]).createOrUpdate()
    }

    private void signInAs(String username, String activeTenant) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username))
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, activeTenant)
        assertEquals(activeTenant, TenantAccessSupport.currentActiveTenantUserGroupId(ec), "${username} signed into ${activeTenant}")
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    private Map svc(String verbNoun, Map params) {
        Map out = ec.service.sync().name("${AUTH}.${verbNoun}").parameters(params).call() ?: [:]
        String errors = ec.message.hasError() ? ec.message.errorsString : null
        ec.message.clearErrors()
        return out + [errors: errors]
    }

    private Map mint(String label, Timestamp expireDate = null) {
        Map out = svc("create#ApiKey", [clientName: label, expireDate: expireDate])
        assertEquals(null, out.errors)
        return out
    }

    private boolean resolves(String rawKey) {
        Map out = svc("resolve#ApiKey", [apiKey: rawKey])
        if (out.valid != true) assertTrue((out.errors as String).contains("invalid API key"), "refusal is generic: ${out.errors}")
        return out.valid == true
    }

    private Map row(String apiKeyId) {
        return ec.entity.find("moqui.gql.GqlApiKey").condition("apiKeyId", apiKeyId).useCache(false).one().getMap()
    }

    private static Timestamp minutesFromNow(long minutes) {
        return new Timestamp(System.currentTimeMillis() + minutes * 60000L)
    }

    @Test
    void anExpiredOrDisabledKeyIsRefusedLikeAnUnknownOne() {
        Map expired = mint("expired agent", minutesFromNow(-1))
        assertFalse(resolves(expired.apiKey as String))
        Map live = mint("live agent")
        assertTrue(resolves(live.apiKey as String))
        assertEquals(null, svc("disable#ApiKey", [apiKeyId: live.apiKeyId]).errors)
        assertFalse(resolves(live.apiKey as String))
        assertEquals(null, svc("disable#ApiKey", [apiKeyId: live.apiKeyId]).errors, "disable is idempotent")
        assertFalse(resolves("dgql_not-a-key"))
    }

    @Test
    void rotationHandsOverWithAGraceThenTheOldKeyStops() {
        Map old = mint("rotating agent")
        Map rotated = svc("rotate#ApiKey", [apiKeyId: old.apiKeyId, graceMinutes: 60])
        assertEquals(null, rotated.errors)
        assertNotEquals(old.apiKey, rotated.apiKey)
        assertTrue((rotated.apiKey as String).startsWith("dgql_"))
        assertTrue(resolves(rotated.apiKey as String), "the new key works")
        assertTrue(resolves(old.apiKey as String), "the old key works during the grace")
        Map newRow = row(rotated.newApiKeyId as String)
        assertEquals([TENANT, "rotating agent"], [newRow.companyUserGroupId, newRow.clientName])
        long graceLeft = (rotated.oldKeyExpireDate as Timestamp).time - System.currentTimeMillis()
        assertTrue(graceLeft > 55 * 60000L && graceLeft <= 60 * 60000L, "grace ${graceLeft}ms")

        // Rotating the new key with no grace ends it now; an ended key cannot be rotated again.
        Map again = svc("rotate#ApiKey", [apiKeyId: rotated.newApiKeyId, graceMinutes: 0])
        assertEquals(null, again.errors)
        assertFalse(resolves(rotated.apiKey as String))
        assertTrue(resolves(again.apiKey as String))
        assertTrue((svc("rotate#ApiKey", [apiKeyId: rotated.newApiKeyId]).errors as String).contains("disabled or expired"))
        assertTrue((svc("rotate#ApiKey", [apiKeyId: again.newApiKeyId, graceMinutes: 2000]).errors as String).contains("graceMinutes"))
    }

    @Test
    void theListNeverCarriesAKeyOrItsDigest() {
        Map minted = mint("listed agent")
        Map out = svc("list#ApiKeys", [:])
        assertEquals(null, out.errors)
        Map listed = (out.apiKeys as List<Map>).find { it.apiKeyId == minted.apiKeyId }
        assertEquals(["listed agent", TENANT, true, true], [listed.clientName, listed.companyUserGroupId, listed.enabled, listed.active])
        assertFalse(listed.containsKey("keyHash"))
        String everything = out.apiKeys.toString()
        assertFalse(everything.contains(minted.apiKey as String), "no raw key")
        assertFalse(everything.contains(row(minted.apiKeyId as String).keyHash as String), "no digest")
        assertFalse(everything.contains("dgql_"))
    }

    @Test
    void aTenantMemberWhoIsNotAnAdminManagesNoKeys() {
        Map minted = mint("guarded agent")
        signInAs("tkey.plain", TENANT)
        try {
            assertFalse(TenantAccessSupport.isTenantAdmin(ec, TENANT))
            ["list#ApiKeys": [:], "disable#ApiKey": [apiKeyId: minted.apiKeyId], "rotate#ApiKey": [apiKeyId: minted.apiKeyId],
             "create#ApiKey": [clientName: "self-served"]].each { String service, Map params ->
                Map out = svc(service, params)
                assertTrue((out.errors as String)?.contains("requires tenant-admin"), "${service}: ${out.errors}")
                assertEquals(null, out.apiKey, "${service} returned a key")
                assertEquals(null, out.apiKeys, "${service} listed keys")
            }
        } finally {
            signInAs("tkey.admin", TENANT)
        }
        assertTrue(resolves(minted.apiKey as String), "the refused calls changed nothing")
    }

    @Test
    void aTenantAdminMintsOnlyForTheTenantTheyAreSignedInto() {
        signInAs("tkey.tadmin", TENANT)
        try {
            assertTrue(TenantAccessSupport.isTenantAdmin(ec, OTHER_TENANT), "the caller also administers the other tenant")
            // Naming the other tenant changes nothing: the key's tenant is not an input.
            Map out = svc("create#ApiKey", [clientName: "tenant admin agent", companyUserGroupId: OTHER_TENANT])
            assertEquals(null, out.errors)
            assertEquals(TENANT, out.companyUserGroupId)
            assertEquals(TENANT, row(out.apiKeyId as String).companyUserGroupId)
            assertEquals(TENANT, svc("resolve#ApiKey", [apiKey: out.apiKey]).companyUserGroupId, "every read is fixed to the active tenant")

            signInAs("tkey.tadmin", OTHER_TENANT)
            Map other = svc("create#ApiKey", [clientName: "other tenant agent"])
            assertEquals(null, other.errors)
            assertEquals(OTHER_TENANT, row(other.apiKeyId as String).companyUserGroupId)

            Map profiled = svc("create#ApiKey", [clientName: "unlimited agent", profileId: "ANY_PROFILE"])
            assertTrue((profiled.errors as String)?.contains("super-admin"), "a tenant admin cannot pick a key's limits: ${profiled.errors}")
            assertEquals(null, profiled.apiKey)
        } finally {
            signInAs("tkey.admin", TENANT)
        }
    }

    @Test
    void anotherTenantsKeyReadsAsMissing() {
        signInAs("tkey.tadmin", OTHER_TENANT)
        Map foreign
        Map own
        try {
            foreign = svc("create#ApiKey", [clientName: "other tenant key"])
            assertEquals(null, foreign.errors)
            signInAs("tkey.tadmin", TENANT)
            own = svc("create#ApiKey", [clientName: "own tenant key"])
            assertEquals(null, own.errors)

            List<Map> listed = svc("list#ApiKeys", [companyUserGroupId: OTHER_TENANT]).apiKeys as List<Map>
            assertTrue(listed*.apiKeyId.contains(own.apiKeyId))
            assertEquals([TENANT] as Set, listed*.companyUserGroupId as Set, "only the active tenant's keys")

            String missing = svc("disable#ApiKey", [apiKeyId: "NO_SUCH_KEY"]).errors as String
            assertEquals(missing.replace("NO_SUCH_KEY", foreign.apiKeyId as String),
                    svc("disable#ApiKey", [apiKeyId: foreign.apiKeyId]).errors, "foreign = missing")
            assertTrue((svc("rotate#ApiKey", [apiKeyId: foreign.apiKeyId]).errors as String).contains("was not found"))
            assertTrue(resolves(foreign.apiKey as String), "the other tenant's key is untouched")

            assertEquals(null, svc("disable#ApiKey", [apiKeyId: own.apiKeyId]).errors, "the active tenant's key is the admin's to disable")
            assertFalse(resolves(own.apiKey as String))
        } finally {
            signInAs("tkey.admin", TENANT)
        }
    }

    @Test
    void lastUsedIsWrittenAtMostOnceAMinute() {
        Map minted = mint("busy agent")
        def key = ec.entity.find("moqui.gql.GqlApiKey").condition("apiKeyId", minted.apiKeyId).useCache(false).one()
        Timestamp recent = new Timestamp(System.currentTimeMillis() - 30000L)
        key.set("lastUsedDate", recent); key.update()
        assertTrue(resolves(minted.apiKey as String))
        assertEquals(recent, row(minted.apiKeyId as String).lastUsedDate, "30s ago: not rewritten")
        Timestamp stale = new Timestamp(System.currentTimeMillis() - 120000L)
        key.set("lastUsedDate", stale); key.update()
        assertTrue(resolves(minted.apiKey as String))
        assertTrue((row(minted.apiKeyId as String).lastUsedDate as Timestamp).after(stale), "2 minutes ago: rewritten")
    }
}
