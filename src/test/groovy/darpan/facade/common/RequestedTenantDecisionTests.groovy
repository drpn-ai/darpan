package darpan.facade.common

import org.junit.jupiter.api.Test
import org.moqui.context.ArtifactAuthorizationException

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class RequestedTenantDecisionTests {
    private static final List<Map> AVAILABLE = [[userGroupId: "A"], [userGroupId: "B"]]

    @Test
    void "returns the requested tenant when it is a membership"() {
        assertEquals("B", TenantAccessSupport.decideRequestedTenant("B", AVAILABLE))
    }

    @Test
    void "refuses a tenant that is not a membership, never the first one"() {
        ArtifactAuthorizationException e = assertThrows(ArtifactAuthorizationException) {
            TenantAccessSupport.decideRequestedTenant("C", AVAILABLE)
        }
        assertTrue(e.message.contains("C"), e.message)
    }

    @Test
    void "refuses when there are no memberships"() {
        assertThrows(ArtifactAuthorizationException) { TenantAccessSupport.decideRequestedTenant("A", []) }
        assertThrows(ArtifactAuthorizationException) { TenantAccessSupport.decideRequestedTenant("A", null) }
    }

    @Test
    void "null requested is not decided here"() {
        // Callers only decide a non-null request; null/blank means "no request" and keeps the preference path.
        assertEquals(null, TenantAccessSupport.requestedTenantHeader(null))
        assertEquals(null, TenantAccessSupport.requestedTenantHeader([web: null]))
    }

    private static Map ecWithHeader(String value) {
        return [web: [request: [getHeader: { String n -> n == "X-Darpan-Tenant" ? value : null }]]]
    }

    @Test
    void "reads and trims the X-Darpan-Tenant header"() {
        assertEquals("B", TenantAccessSupport.requestedTenantHeader(ecWithHeader(" B ")))
    }

    @Test
    void "a blank header is no request"() {
        assertEquals(null, TenantAccessSupport.requestedTenantHeader(ecWithHeader("   ")))
        assertEquals(null, TenantAccessSupport.requestedTenantHeader(ecWithHeader(null)))
    }

    @Test
    void "the ThreadLocal wins over the header"() {
        Map ec = ecWithHeader("A")
        Map picked = TenantAccessSupport.withRequestedTenant("B") {
            TenantAccessSupport.resolveEffectiveActiveTenant(ec, AVAILABLE)
        }
        assertEquals("B", picked.userGroupId)
        assertEquals("A", TenantAccessSupport.resolveEffectiveActiveTenant(ec, AVAILABLE).userGroupId, "header alone is used when no ThreadLocal")
    }
}
