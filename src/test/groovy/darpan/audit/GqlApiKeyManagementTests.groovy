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
 * disable, rotate. Super-admin only; a key's digest never leaves the database; a refused key always reads as the same
 * generic "invalid API key".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GqlApiKeyManagementTests {

    private static final String AUTH = "gql.GqlAuthServices"
    private static final String TENANT = "TKEY_A"
    private static final String PLAIN_USER = "TKEY_PLAIN"
    private static final String ADMIN_USER = "TKEY_ADMIN"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "gql-api-keys")
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: "Key tenant"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: PLAIN_USER, username: "tkey.plain",
                userFullName: "Plain user", emailAddress: "tkey.plain@example.com"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: ADMIN_USER, username: "tkey.admin",
                userFullName: "Key admin", emailAddress: "tkey.admin@example.com"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                description: "Darpan super admin"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                userId: ADMIN_USER, fromDate: new Timestamp(System.currentTimeMillis() - 60000L)]).createOrUpdate()
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser("tkey.admin"))
        assertTrue(TenantAccessSupport.isSuperAdmin(ec), "the test admin must be a super-admin")
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
        Map out = svc("create#ApiKey", [companyUserGroupId: TENANT, clientName: label, expireDate: expireDate])
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
        Map out = svc("list#ApiKeys", [companyUserGroupId: TENANT])
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
    void onlyASuperAdminManagesKeys() {
        Map minted = mint("guarded agent")
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser("tkey.plain"))
        try {
            assertFalse(TenantAccessSupport.isSuperAdmin(ec))
            ["list#ApiKeys": [:], "disable#ApiKey": [apiKeyId: minted.apiKeyId], "rotate#ApiKey": [apiKeyId: minted.apiKeyId],
             "create#ApiKey": [companyUserGroupId: TENANT, clientName: "self-served"]].each { String service, Map params ->
                Map out = svc(service, params)
                assertTrue((out.errors as String)?.contains("requires super-admin"), "${service}: ${out.errors}")
                assertEquals(null, out.apiKey, "${service} returned a key")
            }
        } finally {
            ec.user.logoutUser()
            assertTrue(ec.user.internalLoginUser("tkey.admin"))
        }
        assertTrue(resolves(minted.apiKey as String), "the refused calls changed nothing")
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
