package darpan.reconciliation.automation

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * A connector row can name a service from a component that is not in this image - database-darpan
 * is absent from every production Dockerfile today. Moqui's own failure is "Unknown service", which
 * reads as a bug rather than a missing install.
 */
class ExtractorInstalledCheckTests {

    /** ec.service is ServiceFacadeImpl at runtime; isServiceDefined(String) is its existence check. */
    private static def ecWith(Set<String> installed) {
        return new Expando(service: new Expando(isServiceDefined: { String n -> installed.contains(n) }))
    }

    @Test void missingDatabaseComponentProducesALegibleError() {
        def e = assertThrows(IllegalStateException) {
            AutomationExecutionSupport.requireExtractorServiceInstalled(ecWith([] as Set),
                    "reconciliation.DatabaseExtractionServices.extract#DatabaseRecords")
        }
        assertTrue(e.message.contains("database source component is not installed"),
                "operator needs to know it is a missing install, not a bug")
        assertFalse(e.message.contains("Unknown service"))
    }

    @Test void otherMissingExtractorStillNamesTheService() {
        def e = assertThrows(IllegalStateException) {
            AutomationExecutionSupport.requireExtractorServiceInstalled(ecWith([] as Set),
                    "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
        }
        assertTrue(e.message.contains("HotWaxOmsExtractionServices"))
    }

    @Test void installedServicePassesThrough() {
        String name = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"
        AutomationExecutionSupport.requireExtractorServiceInstalled(ecWith([name] as Set), name)
    }
}
