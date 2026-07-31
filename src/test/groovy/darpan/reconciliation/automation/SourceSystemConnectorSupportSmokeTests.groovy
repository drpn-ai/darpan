package darpan.reconciliation.automation

import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Phase 1 (DAR-293) acceptance for the SourceSystemConnector registry: the data-driven resolver must
 * return the SAME extract service, date parameters, config type and Shopify preserve-window flag that
 * today's per-system switch in AutomationExecutionSupport / ReconciliationSavedRunSupport produces.
 *
 * Assertions compare against the LIVE constants (not re-typed literals) so the seed rows are proven
 * byte-identical to the source of truth and will fail if either drifts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceSystemConnectorSupportSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "source-system-connector-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void resolvesOmsRowByteIdenticalToSwitch() {
        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, "OMS")
        assertNotNull(c, "OMS connector row should resolve")
        assertEquals("OMS", c.systemEnumId)
        assertEquals(ReconciliationSavedRunSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE, c.extractServiceName)
        // Date params must equal what the automation switch computes for this extract service.
        assertEquals("windowStart", c.dateFromParameterName)
        assertEquals("windowEnd", c.dateToParameterName)
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST, c.expectedSourceConfigType)
        assertEquals(ReconciliationSavedRunSupport.HOTWAX_ORDERS_REMOTE_ID, c.remoteId)
        assertEquals(ReconciliationSavedRunSupport.HOTWAX_ORDERS_ENDPOINT_LABEL, c.endpointLabel)
        // OMS does NOT preserve window instants (only Shopify does — AutomationExecutionSupport:634).
        assertFalse((boolean) c.preserveWindowInstants)
    }

    @Test
    void resolvesShopifyRowByteIdenticalToSwitch() {
        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(c, "SHOPIFY connector row should resolve")
        assertEquals("SHOPIFY", c.systemEnumId)
        assertEquals(ReconciliationSavedRunSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE, c.extractServiceName)
        assertEquals("windowStart", c.dateFromParameterName)
        assertEquals("windowEnd", c.dateToParameterName)
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH, c.expectedSourceConfigType)
        assertEquals(ReconciliationSavedRunSupport.SHOPIFY_ORDERS_REMOTE_ID, c.remoteId)
        assertEquals(ReconciliationSavedRunSupport.SHOPIFY_ORDERS_ENDPOINT_LABEL, c.endpointLabel)
        // Shopify special-case: preserveWindowInstants = true (AutomationExecutionSupport:634).
        assertTrue((boolean) c.preserveWindowInstants)
        // Verification pass: Shopify is the only source with a point-lookup service (nodes(ids:)),
        // used to recheck missing-in-SHOPIFY diffs against the primary datastore.
        assertEquals(ReconciliationSavedRunSupport.SHOPIFY_ORDER_IDS_LOOKUP_SERVICE, c.lookupServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape((String) c.lookupServiceName))
    }

    @Test
    void resolvesByExpectedSourceConfigTypeForInteractiveDispatch() {
        // The saved-run path keys sources on sourceConfigType — the registry must resolve it.
        Map<String, Object> oms = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST)
        assertNotNull(oms)
        assertEquals(ReconciliationSavedRunSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE, oms.extractServiceName)

        Map<String, Object> shopify = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH)
        assertNotNull(shopify)
        assertEquals(ReconciliationSavedRunSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE, shopify.extractServiceName)

        // NETSUITE row exists but declares no extractServiceName — interactive dispatch must
        // treat it as unsupported, and unknown types resolve to null.
        Map<String, Object> netsuite = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "NETSUITE_AUTH")
        assertNotNull(netsuite)
        assertEquals(null, netsuite.extractServiceName)
        assertEquals(null, SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "NO_SUCH_TYPE"))
        assertEquals(null, SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, null))
    }

    @Test
    void resolvesByAliasAndNormalizesInput() {
        Map<String, Object> viaAlias = SourceSystemConnectorSupport.resolve(ec, "HOTWAX")
        assertNotNull(viaAlias, "alias HOTWAX should resolve to the OMS connector")
        assertEquals("OMS", viaAlias.systemEnumId)
        // Whitespace/case-insensitive input still resolves.
        assertEquals("SHOPIFY", SourceSystemConnectorSupport.resolve(ec, " shopify ")?.systemEnumId)
    }

    @Test
    void unknownAndBlankSystemsResolveToNull() {
        assertNull(SourceSystemConnectorSupport.resolve(ec, "NOT_A_SYSTEM"))
        assertNull(SourceSystemConnectorSupport.resolve(ec, null))
        assertNull(SourceSystemConnectorSupport.resolve(ec, ""))
    }

    @Test
    void brandNewSystemResolvesFromDataAlone() {
        // Down payment on the whole-effort acceptance test: a systemEnumId that exists ONLY as a
        // registry row resolves with zero core-Groovy change.
        String fakeSystem = "FAKESYS_PHASE1"
        ec.entity.makeValue(SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([
                        systemEnumId            : fakeSystem,
                        extractServiceName      : "reconciliation.FakeExtractionServices.extract#FakeOrders",
                        dateFromParameterName   : "windowStart",
                        dateToParameterName     : "windowEnd",
                        expectedSourceConfigType: "FAKE_AUTH",
                        systemAliases           : "FAKESYS,FAKE_SYS_PHASE1",
                        preserveWindowInstants  : "N",
                        enabled                 : "Y",
                ])
                .create()

        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, fakeSystem)
        assertNotNull(c, "a registry-only system should resolve without core changes")
        assertEquals("reconciliation.FakeExtractionServices.extract#FakeOrders", c.extractServiceName)
        assertEquals(fakeSystem, SourceSystemConnectorSupport.resolve(ec, "FAKESYS")?.systemEnumId)
    }

    @Test
    void disabledRowResolvesToNull() {
        String disabled = "DISABLED_PHASE1"
        ec.entity.makeValue(SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([systemEnumId: disabled, extractServiceName: "x", enabled: "N"])
                .create()
        assertNull(SourceSystemConnectorSupport.resolve(ec, disabled), "an enabled=N row must not resolve")
    }

    @Test
    void exchangeLookupFieldsRoundTripAndPassTheLookupFence() {
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId"))
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderExchangeState"))

        // OMS side: pairLookupServiceName returns full order groups keyed by the compare join key.
        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS")
        assertNotNull(oms, "OMS connector row should resolve")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId",
                oms.pairLookupServiceName)

        // Shopify side: exchangeStateLookupServiceName returns per-order exchange state.
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertEquals("reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderExchangeState",
                shopify.exchangeStateLookupServiceName)

        // Shopify side: exchangeSweepServiceName enumerates in-window Shopify exchanges for the
        // presence check (every Shopify exchange must have been imported into OMS).
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyExchangeSweep"))
        assertEquals("reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyExchangeSweep",
                shopify.exchangeSweepServiceName)
    }
}
