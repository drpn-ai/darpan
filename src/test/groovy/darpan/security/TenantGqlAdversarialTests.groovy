package darpan.security

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
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

    /** OC211: { ...F1 } with fragment Fi on Query { ...F(i+1) ...F(i+1) } and F(n) on Query { __typename }. No cycle, so
     *  graphql-java's NoFragmentCycles passes it, but expanding every spread walks 2^n spreads. */
    private static String fragmentChain(int n) {
        StringBuilder q = new StringBuilder("query { ...F1 }")
        for (int i = 1; i < n; i++) q.append(" fragment F").append(i).append(" on Query { ...F").append(i + 1).append(" ...F").append(i + 1).append(" }")
        q.append(" fragment F").append(n).append(" on Query { __typename }")
        return q.toString()
    }

    /** Runs body with gql.maxWalkSteps set (GqlEngine reads it per engine, so per gql() call), then restores it. */
    private static <T> T withWalkSteps(String steps, Closure<T> body) {
        String before = System.getProperty("gql.maxWalkSteps")
        System.setProperty("gql.maxWalkSteps", steps)
        try { return body.call() } finally {
            if (before == null) System.clearProperty("gql.maxWalkSteps") else System.setProperty("gql.maxWalkSteps", before)
        }
    }

    private static List<String> codes(Map out) { return ((out?.errors ?: []) as List)*.extensions*.code as List<String> }

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
    /** OC211: the cost walk stops at its step budget and refuses the query, before any fetch and before the throttle debit. */
    @Test
    void aWalkPastTheStepBudgetIsRefusedWithComplexityExceeded() {
        // 40 fragments: about 80 steps even with each fragment costed once, so past a 50-step budget either way
        Map out = withWalkSteps("50") { gql(fragmentChain(40)) }
        assertTrue(codes(out).contains("COMPLEXITY_EXCEEDED"), "a 40-fragment walk under a 50-step budget was not refused: ${codes(out)}")
        assertEquals(50, (out.errors.find { it.extensions?.code == "COMPLEXITY_EXCEEDED" }.extensions.maxSteps as Integer))
        assertEquals(null, out?.data, "a refused query returned data: ${out}")
    }

    /** OC211: a fragment spread repeated under the same parent is walked once, so an acyclic doubling chain costs steps linear
     *  in its length (about 40 for 20 fragments), not 2^20. */
    @Test
    void aRepeatedFragmentSpreadIsWalkedOnce() {
        Map out = withWalkSteps("1000") { gql(fragmentChain(20)) }
        assertEquals([], out?.errors, "a 20-fragment doubling chain did not fit a 1000-step budget: ${out?.errors}")
        assertEquals("Query", out?.data?.__typename)
    }

    /** OC211, the reported attack: a 60-fragment doubling chain (2^60 spreads unmemoised) under the default budget is costed
     *  in a few hundred steps and answered. Without the memo the budget would refuse it within milliseconds; the timeout
     *  (own thread, so a regression cannot pin the test worker) is the last guard. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theReportedSixtyFragmentChainIsCostedAndAnsweredQuickly() {
        Map out = gql(fragmentChain(60))
        assertEquals([], out?.errors, "the 60-fragment chain was refused: ${out?.errors}")
        assertEquals("Query", out?.data?.__typename)
    }

    /** OC211 guard: the memo must not change a legitimate query's cost; the same fragment under two lists of different
     *  width costs exactly what the inlined selections cost. */
    @Test
    void aFragmentCostsWhatItsInlinedSelectionCosts() {
        Map inlined = gql("{ a: reconciliationResults(first: 2) { edges { node { reconciliationRunResultId statusEnumId } } } " +
                "b: reconciliationResults(first: 3) { edges { node { reconciliationRunResultId statusEnumId } } } }")
        Map spread = gql("{ a: reconciliationResults(first: 2) { edges { node { ...R } } } " +
                "b: reconciliationResults(first: 3) { edges { node { ...R } } } } " +
                "fragment R on ReconciliationResult { reconciliationRunResultId statusEnumId }")
        assertEquals([], inlined?.errors, "inlined control errored: ${inlined}")
        assertEquals([], spread?.errors, "fragment query errored: ${spread}")
        assertEquals(15L, inlined.extensions.cost.requestedQueryCost as long, "2 x (1 + 2) + 3 x (1 + 2)")
        assertEquals(inlined.extensions.cost.requestedQueryCost as long, spread.extensions.cost.requestedQueryCost as long)
    }
}
