package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.logging.log4j.ThreadContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.function.Executable
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * Verifies that DarpanMdcSupport.stamp() populates the MDC ThreadContext with the
 * darpan.tenant / darpan.userId / darpan.correlationId keys, and that clear() removes
 * them so values do not leak across pooled threads.
 *
 * Two test groups:
 *  A) Unit tests — no Moqui runtime, use lightweight stub EC. These run fast and prove
 *     the pure logic in DarpanMdcSupport. They also cover the integration path through
 *     TenantAccessSupport.syncUserContext → DarpanMdcSupport.stamp.
 *  B) Smoke test (integration) — spins up a real in-process Moqui/H2 instance via
 *     ReconciliationSmokeTestSupport.initMoqui, calls syncUserContext after seeding a
 *     tenant user, and asserts MDC keys are set and then cleared.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DarpanMdcSupportTests {

    // ── Smoke test state ──────────────────────────────────────────────────────
    private ExecutionContext ec

    @BeforeAll
    void setupMoqui() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "mdc-support-smoke")
    }

    @AfterAll
    void teardownMoqui() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void clearMdcBetweenTests() {
        // Guarantee clean MDC after every test so assertion order does not matter
        DarpanMdcSupport.clear()
    }

    // ── A: unit tests (no Moqui runtime) ─────────────────────────────────────

    @Test
    void stampPopulatesTenantAndUserIdAndGeneratesCorrelationId() {
        def stubEc = stubEc(userId: "unit-user-1", web: null)

        DarpanMdcSupport.stamp(stubEc, "KREWE")

        assertEquals("KREWE",       ThreadContext.get(DarpanMdcSupport.MDC_TENANT))
        assertEquals("unit-user-1", ThreadContext.get(DarpanMdcSupport.MDC_USER_ID))
        assertNotNull(ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID),
                "correlationId must be auto-generated when no inbound header is present")
        // Validate it is a UUID (36-char, four hyphens)
        String cid = ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID)
        assertTrue(cid.length() == 36 && cid.count("-") == 4,
                "auto-generated correlationId must be a UUID, got: ${cid}")
    }

    @Test
    void stampUsesInboundXRequestIdHeaderWhenPresent() {
        def requestStub = [getHeader: { String name -> name == "X-Request-Id" ? "trace-abc-123" : null }] as Object
        def webStub = [request: requestStub] as Object
        def stubEc = stubEc(userId: "unit-user-2", web: webStub)

        DarpanMdcSupport.stamp(stubEc, "GORJANA")

        assertEquals("trace-abc-123", ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID))
        assertEquals("GORJANA",       ThreadContext.get(DarpanMdcSupport.MDC_TENANT))
    }

    @Test
    void stampUsesXCorrelationIdFallbackWhenXRequestIdAbsent() {
        def requestStub = [getHeader: { String name ->
            if (name == "X-Request-Id")    return null
            if (name == "X-Correlation-Id") return "corr-xyz-789"
            return null
        }] as Object
        def webStub = [request: requestStub] as Object
        def stubEc = stubEc(userId: "unit-user-3", web: webStub)

        DarpanMdcSupport.stamp(stubEc, "TENANT_A")

        assertEquals("corr-xyz-789", ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID))
    }

    @Test
    void stampPreservesExistingCorrelationIdOnRestamp() {
        // Simulate before-request stamp followed by syncUserContext re-stamp (e.g. after-login)
        def stubEc = stubEc(userId: "unit-user-4", web: null)
        DarpanMdcSupport.stamp(stubEc, "TENANT_FIRST")
        String firstCid = ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID)
        assertNotNull(firstCid)

        // Re-stamp (e.g. after tenant preference resolved) — correlationId must NOT rotate
        DarpanMdcSupport.stamp(stubEc, "TENANT_SECOND")

        assertEquals(firstCid, ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID),
                "correlationId must not change when re-stamping in the same request")
        // Tenant and userId CAN update on restamp
        assertEquals("TENANT_SECOND", ThreadContext.get(DarpanMdcSupport.MDC_TENANT))
    }

    @Test
    void stampFallsBackToAnonymousWhenTenantAndUserIdNull() {
        def stubEc = stubEc(userId: null, web: null)

        DarpanMdcSupport.stamp(stubEc, null)

        assertEquals("anonymous", ThreadContext.get(DarpanMdcSupport.MDC_TENANT))
        assertEquals("anonymous", ThreadContext.get(DarpanMdcSupport.MDC_USER_ID))
        assertNotNull(ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID))
    }

    @Test
    void clearRemovesAllThreeKeys() {
        def stubEc = stubEc(userId: "unit-user-5", web: null)
        DarpanMdcSupport.stamp(stubEc, "KREWE")
        assertNotNull(ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID))

        DarpanMdcSupport.clear()

        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_TENANT),         "tenant key must be removed")
        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_USER_ID),        "userId key must be removed")
        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID), "correlationId key must be removed")
    }

    @Test
    void clearIsIdempotentWhenMdcIsAlreadyEmpty() {
        // No stamp — just clear; should not throw
        // Cast to Executable to resolve Groovy's ambiguity with assertDoesNotThrow overloads
        assertDoesNotThrow({ DarpanMdcSupport.clear() } as Executable)
        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_TENANT))
    }

    // ── B: smoke / integration test (real Moqui + H2) ────────────────────────

    /**
     * Seed a real tenant context in Moqui, call syncUserContext, assert MDC keys are
     * set to non-anonymous values, then call clear() and assert all keys are gone.
     * This proves the full before-request → after-request lifecycle with a real EC.
     */
    @Test
    void syncUserContextStampsMdcWithTenantAndUserIdThenClearRemovesAll() {
        // Seed company scope (user + tenant + permission) so syncUserContext resolves a real tenant
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.message.clearErrors()

        // Simulate before-request: stamp MDC via syncUserContext
        TenantAccessSupport.syncUserContext(ec)

        String tenant = ThreadContext.get(DarpanMdcSupport.MDC_TENANT)
        String userId = ThreadContext.get(DarpanMdcSupport.MDC_USER_ID)
        String cid    = ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID)

        assertNotNull(tenant, "darpan.tenant must be populated after syncUserContext")
        assertNotNull(userId, "darpan.userId must be populated after syncUserContext")
        assertNotNull(cid,    "darpan.correlationId must be populated after syncUserContext")

        // The smoke test seeds user TEST_CUSTOMER_USER (userId) with tenant KREWE
        assertEquals("KREWE", tenant,
                "darpan.tenant must equal the seeded active tenant KREWE")
        // seedCompanyScope calls internalLoginUser(TEST_COMPANY_USER_ID) which is "TEST_CUSTOMER_USER"
        assertFalse(userId == "anonymous",
                "darpan.userId must be a real userId (not anonymous) after internalLoginUser")

        // Simulate after-request: clear MDC
        DarpanMdcSupport.clear()

        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_TENANT),         "darpan.tenant must be cleared")
        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_USER_ID),        "darpan.userId must be cleared")
        assertNull(ThreadContext.get(DarpanMdcSupport.MDC_CORRELATION_ID), "darpan.correlationId must be cleared")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal EC stub for unit tests — only wires ec.user.userId and ec.web. */
    private static def stubEc(Map args) {
        String userId = args.userId as String
        def webObj    = args.web

        def userStub = new Expando()
        userStub.userId = userId

        def ecStub = new Expando()
        ecStub.user = userStub
        // ec.web may be null (non-web test) or a stub with request
        try {
            ecStub.web = webObj
        } catch (ignored) {}
        return ecStub
    }
}
