package darpan.admin

import darpan.admin.AdminTestStubs.MessageFacadeStub
import org.junit.jupiter.api.Test

import static darpan.admin.AdminTestStubs.plainUserEc
import static darpan.admin.AdminTestStubs.superAdminEc
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class AdminAccessSupportTests {

    @Test
    void requireSuperAdminPassesForSuperAdmin() {
        assertTrue(AdminAccessSupport.requireSuperAdmin(superAdminEc()))
    }

    @Test
    void requireSuperAdminDeniesPlainUserWithError() {
        def message = new MessageFacadeStub()
        assertFalse(AdminAccessSupport.requireSuperAdmin(plainUserEc(message: message)))
        assertTrue(message.hasError())
    }

    @Test
    void buildAdminSessionInfoReturnsIdentityForSuperAdmin() {
        Map info = AdminAccessSupport.buildAdminSessionInfo(superAdminEc())
        assertEquals("ADMIN_USER", info.userId)
        assertEquals("darpan.admin", info.username)
        assertEquals(true, info.isSuperAdmin)
    }

    @Test
    void buildAdminSessionInfoReturnsNullForPlainUser() {
        assertNull(AdminAccessSupport.buildAdminSessionInfo(plainUserEc()))
    }
}
