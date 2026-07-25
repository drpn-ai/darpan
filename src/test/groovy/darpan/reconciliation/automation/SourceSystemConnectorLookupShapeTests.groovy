package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Naming-guard acceptance for the verification lookup fence: the lookup slot may only dispatch
 * lookup#* services in the recognized namespaces — never extract/execute or arbitrary internal
 * services — keeping the verification sink strictly narrower than the extraction fence.
 */
class SourceSystemConnectorLookupShapeTests {

    @Test
    void acceptsRecognizedLookupServiceShapes() {
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds"))
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "facade.ShopifyFacadeServices.lookup#OrderIds"))
    }

    @Test
    void rejectsNonLookupAndArbitraryServiceShapes() {
        // extract/execute services pass the extractor fence but must NOT pass the lookup fence
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"))
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "facade.ShopifyFacadeServices.execute#ShopifyGraphql"))
        // arbitrary internal / framework services
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "org.moqui.impl.UserServices.create#UserAccount"))
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "facade.ReconciliationFacadeServices.delete#SavedRun"))
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(null))
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(""))
    }

    @Test
    void lookupFenceIsDisjointFromExtractorFence() {
        // a name accepted by one fence is rejected by the other, so a misconfigured row cannot
        // cross-dispatch between the two sinks
        String lookupName = "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds"
        String extractName = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(lookupName))
        assertFalse(SourceSystemConnectorSupport.isAllowedExtractorServiceShape(lookupName))
        assertTrue(SourceSystemConnectorSupport.isAllowedExtractorServiceShape(extractName))
        assertFalse(SourceSystemConnectorSupport.isAllowedLookupServiceShape(extractName))
    }
}
