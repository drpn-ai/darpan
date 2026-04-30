package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

class SftpAutomationSupportTests {

    @Test
    void defaultOutputLocationUsesDataManagerRunFolder() {
        def ec = new Expando(resource: new Expando(properties: [:]))

        assertEquals(
                "runtime://datamanager/reconciliation-runs/OrderIdMap/20260430-010000000",
                SftpAutomationSupport.resolveDefaultOutputLocation(ec, "OrderIdMap", "20260430-010000000")
        )
    }

    @Test
    void runtimeOutputLocationMapsToRemoteDatamanagerPath() {
        assertEquals(
                "/datamanager/reconciliation-runs/OrderIdMap/20260430-010000000",
                SftpAutomationSupport.remotePathForRuntimeLocation(
                        "runtime://datamanager/reconciliation-runs/OrderIdMap/20260430-010000000"
                )
        )
        assertNull(SftpAutomationSupport.remotePathForRuntimeLocation("/incoming/results"))
    }
}
