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
}
