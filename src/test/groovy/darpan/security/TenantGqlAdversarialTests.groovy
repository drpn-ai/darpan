package darpan.security

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-277 — the curated GraphQL API (moqui-gql), session and public-key realms, against tenant B's run results.
 * Each test asserts the SECURE outcome: red = a confirmed hole, green = the boundary held.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantGqlAdversarialTests {

    static final String A = TenantAttackHarness.TENANT_A
    static final String B = TenantAttackHarness.TENANT_B
    static final String LIST = "{ reconciliationResults(first: 100) { edges { node { reconciliationRunResultId } } } }"
    static final String ONE_A = "{ reconciliationResult(reconciliationRunResultId: \"ADV_GQL_RR_A\") { reconciliationRunResultId } }"
    static final String ONE_B = "{ reconciliationResult(reconciliationRunResultId: \"ADV_GQL_RR_B\") { reconciliationRunResultId } }"

    TenantAttackHarness h
    String bucketBefore

    @BeforeAll
    void setup() {
        bucketBefore = System.getProperty("gql.throttle.bucketSize")
        System.setProperty("gql.throttle.bucketSize", "1000000000")
        h = new TenantAttackHarness("tenant-gql-adversarial")
        h.insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "ADV_GQL_RR_A", companyUserGroupId: A,
                statusEnumId: "AUT_STAT_SUCCESS"])
        h.insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "ADV_GQL_RR_B", companyUserGroupId: B,
                statusEnumId: "AUT_STAT_SUCCESS"])
    }

    @AfterAll
    void cleanup() {
        h.close()
        if (bucketBefore == null) System.clearProperty("gql.throttle.bucketSize") else System.setProperty("gql.throttle.bucketSize", bucketBefore)
    }

    @BeforeEach
    void attacker() { h.asAttacker() }

    private Map gql(String query, Map authOverride = null) {
        assert h.ec.factory.getToolFactory("GraphQL") != null: "the moqui-gql component did not load"
        def engineClass = h.ec.factory.getClassLoader().loadClass("org.moqui.gql.GqlEngine")
        def engine = engineClass.getDeclaredConstructor(ExecutionContext).newInstance(h.ec)
        // As in production: inside gql.QueryServices.execute#Query, authorized by the caller's DARPAN_GQL_API grant.
        return (Map) h.asProduction {
            def aei = h.ec.artifactExecution.push("gql.QueryServices.execute#Query", org.moqui.context.ArtifactExecutionInfo.AT_SERVICE,
                    org.moqui.context.ArtifactExecutionInfo.AUTHZA_VIEW, true)
            try { return engine.execute(query, null, null, authOverride) } finally { h.ec.artifactExecution.pop(aei) }
        }
    }

    private static List<String> ids(Map out) {
        return ((out?.data?.reconciliationResults?.edges ?: []) as List)*.node*.reconciliationRunResultId as List<String>
    }

    @Test
    void controlSessionListSeesOnlyOwnTenant() {
        Map out = gql(LIST)
        List<String> seen = ids(out)
        assertTrue(seen.contains("ADV_GQL_RR_A"), "positive control: own row missing: ${out}")
        assertFalse(seen.contains("ADV_GQL_RR_B"), "session GQL list leaked tenant B: ${seen}")
    }

    @Test
    void sessionByPkCannotReadForeignRow() {
        // Positive control: the same by-pk root returns A's own row, so a null for B's row is the tenant pin, not a broken query.
        Map own = gql(ONE_A)
        assertEquals([], own?.errors, "by-pk control query errored: ${own}")
        assertEquals("ADV_GQL_RR_A", own?.data?.reconciliationResult?.reconciliationRunResultId, "positive control: own row missing by pk: ${own}")

        Map out = gql(ONE_B)
        assertEquals([], out?.errors, "by-pk read of tenant B's row errored instead of finding no row: ${out}")
        assertEquals(null, out?.data?.reconciliationResult, "session GQL by-pk read tenant B's row: ${out}")
    }

    @Test
    void publicKeyRealmPinnedToItsTenant() {
        Map out = gql(LIST, [scopeTenantId: A, callerId: "adv-key-A"])
        List<String> seen = ids(out)
        assertTrue(seen.contains("ADV_GQL_RR_A"), "positive control: own row missing on the key realm: ${out}")
        assertFalse(seen.contains("ADV_GQL_RR_B"), "public key for A listed tenant B: ${seen}")
    }

    @Test
    void publicKeyRealmWithBlankTenantSeesNothing() {
        Map out = gql(LIST, [scopeTenantId: "", callerId: "adv-key-blank"])
        assertEquals([], out?.errors, "a blank key tenant must deny with zero rows, not error: ${out}")
        assertEquals([], ids(out).findAll { it.startsWith("ADV_GQL_") })
    }

    /**
     * A caller profile with a productStore scope used to REPLACE the tenant filter on the session path (GqlEngine.execute;
     * DAR-BE-282 made it only narrow the pin); ProductStoreScopeFilter leaves entities without a productStoreId column
     * (ReconciliationRunResult) unscoped, so only the tenant pin keeps B's row out and A's row in.
     */
    @Test
    void sessionCallerWithStoreScopedProfileStillPinnedToTenant() {
        h.insert("moqui.gql.GqlCallerProfile", [profileId: "ADV_STORE_PROFILE", description: "store-scoped", scopeProductStoreId: "ANY_STORE"])
        h.insert("moqui.gql.GqlCallerProfileMember", [userId: TenantAttackHarness.ATTACKER_ID, profileId: "ADV_STORE_PROFILE"])
        try {
            Map out = gql(LIST)
            assertEquals([], out?.errors, "store-scoped list errored: ${out}")
            List<String> seen = ids(out)
            assertTrue(seen.contains("ADV_GQL_RR_A"), "positive control: own row missing under the store-scoped profile: ${out}")
            assertFalse(seen.contains("ADV_GQL_RR_B"), "store-scoped profile dropped the tenant pin; listed tenant B: ${seen}")
        } finally {
            h.ec.entity.find("moqui.gql.GqlCallerProfileMember").condition("userId", TenantAttackHarness.ATTACKER_ID).disableAuthz().deleteAll()
        }
    }
}
