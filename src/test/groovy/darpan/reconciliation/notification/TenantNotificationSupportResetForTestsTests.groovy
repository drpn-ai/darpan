package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

class TenantNotificationSupportResetForTestsTests {
    @Test
    void resetForTestsClearsTheDeliveryHookAndIsIdempotent() {
        TenantNotificationSupport.resetForTests()
        assertNull(TenantNotificationSupport.@deliveryHook)
        TenantNotificationSupport.setDeliveryHook({ String ref, Map payload -> [ok: true] })
        assertNotNull(TenantNotificationSupport.@deliveryHook)
        TenantNotificationSupport.resetForTests()
        assertNull(TenantNotificationSupport.@deliveryHook)
        TenantNotificationSupport.resetForTests()
        assertNull(TenantNotificationSupport.@deliveryHook)
    }
}
