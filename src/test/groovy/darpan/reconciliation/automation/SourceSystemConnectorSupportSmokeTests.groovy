package darpan.reconciliation.automation

import darpan.facade.reconciliation.AutomationFacadeSupport
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

    @Test
    void probeFenceAcceptsOnlyProbeShapedServiceNames() {
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "facade.ShopifyFacadeServices.probe#ShopifyAuthConnection"))
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection"))

        // The probe sink stays narrower than the extraction and lookup fences: every other verb is
        // rejected, so a mutated registry row cannot aim diagnostics at an extract/execute service.
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "facade.ShopifyFacadeServices.execute#ShopifyGraphql"))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds"))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                "org.moqui.impl.EntityServices.create#Entity"))
        // Must end in ExtractionServices/FacadeServices — a bare *Services name is not enough.
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape("facade.SomeServices.probe#Thing"))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape("admin.TenantAdminServices.probe#Thing"))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(null))
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(""))
    }

    @Test
    void healthCheckServiceNameRoundTripsFromTheSeedRows() {
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertEquals("facade.ShopifyFacadeServices.probe#ShopifyAuthConnection", shopify.healthCheckServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape((String) shopify.healthCheckServiceName))

        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS")
        assertNotNull(oms, "OMS connector row should resolve")
        assertEquals("facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection", oms.healthCheckServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape((String) oms.healthCheckServiceName))

        // NETSUITE declares no probe: diagnostics are unavailable for it, which is a reported
        // state rather than an error.
        Map<String, Object> netsuite = SourceSystemConnectorSupport.resolve(ec, "NETSUITE")
        assertNotNull(netsuite, "NETSUITE connector row should resolve")
        assertNull(netsuite.healthCheckServiceName)
    }

    @Test
    void omsConnectorDeclaresTheExclusionFilterParameter() {
        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS")
        assertNotNull(oms, "OMS connector row should resolve")
        assertEquals("sourceFilters", oms.filterParameterName)
    }

    @Test
    void shopifyConnectorDeclaresNoFilterParameterUntilItOptsIn() {
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertNull(shopify.filterParameterName)
    }

    @Test
    void theBoardsOmsFieldPillsCoverEveryKeepFieldsBaseField() {
        // FINAL-REVIEW CRITICAL 1b, updated for Task 5 (Plan 2): the OMS field pills used to be a
        // hand-written AutomationFacadeSupport constant mirroring keepFieldsBase plus
        // salesChannelEnumId; they are now SourceSystemConnectorField seed rows read through
        // AutomationFacadeSupport.fieldOptionsForSystem. Pin the mirror: adding a field to
        // keepFieldsBase without adding a pill row would silently make it unselectable on the rules
        // board.
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS")
        assertNotNull(oms, "OMS connector row should resolve")

        List<String> pillFields = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS")
                .collect { Map<String, Object> option -> (option.fieldPath as String).substring("\$.records[*].".length()) }
        List<String> keepFields = ((String) oms.keepFieldsBase).split(",").collect { it.trim() }.findAll { it }

        assertTrue(pillFields.containsAll(keepFields),
                "keepFieldsBase ${keepFields} not all offered as pills ${pillFields}")
        // The whole point of the widening: the shipping use case is selectable even though it is NOT
        // a keep field (exclusions run before projection, so any raw field is testable).
        assertTrue(pillFields.contains("salesChannelEnumId"))
        assertFalse(keepFields.contains("salesChannelEnumId"))
        // DAR-BE-017, same widening for the same reason. Verified against a real captured OMS extract
        // (org.apache.ofbiz.order.order.OrderHeader): productStoreId is a top-level key on every order
        // document, so a rule drawn on this pill matches the raw record before projection trims it.
        assertTrue(pillFields.contains("productStoreId"))
        assertFalse(keepFields.contains("productStoreId"))
        // Primary-ID selection stays deliberately narrow — widening the pills must not widen it.
        assertEquals(3, AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS").size())
    }

    @Test
    void transferOrderConnectorResolvesDistinctlyFromSalesOrders() {
        Map<String, Object> salesOrders = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, "HOTWAX_OMS_REST")
        Map<String, Object> transferOrders = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, "HOTWAX_OMS_REST_TRANSFER")

        assertEquals("OMS", salesOrders.systemEnumId)
        assertEquals("OMS_TRANSFER_ORDERS", transferOrders.systemEnumId)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders",
                salesOrders.extractServiceName)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsTransferOrders",
                transferOrders.extractServiceName)
        // Both reuse the same credential storage; only the routing label differs.
        assertEquals(salesOrders.configEntityName, transferOrders.configEntityName)
        assertEquals(salesOrders.configParameterName, transferOrders.configParameterName)
    }

    @Test
    void reconciliationOrdersConnectorResolvesDistinctlyFromLegacyOmsOrders() {
        Map<String, Object> legacy = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, "HOTWAX_OMS_REST")
        Map<String, Object> recon = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(
                ec, "HOTWAX_OMS_REST_RECON")

        assertNotNull(recon, "reconciliationOrders connector row should resolve")
        assertEquals("OMS", legacy.systemEnumId)
        assertEquals("OMS_RECON_ORDERS", recon.systemEnumId)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders",
                recon.extractServiceName)
        // Same credentials, same window parameters, same projection — only the endpoint differs, so
        // an operator can move a rule set across without touching its source config or its rules.
        assertEquals(legacy.configEntityName, recon.configEntityName)
        assertEquals(legacy.configParameterName, recon.configParameterName)
        assertEquals(legacy.dateFromParameterName, recon.dateFromParameterName)
        assertEquals(legacy.dateToParameterName, recon.dateToParameterName)
        assertEquals(legacy.keepFieldsBase, recon.keepFieldsBase)
        // Configured exclusions still run client-side here: the endpoint knows nothing of tenant
        // exclusion rules, so dropping filterParameterName would silently disable them.
        assertEquals(legacy.filterParameterName, recon.filterParameterName)
    }

    @Test
    void reconciliationOrdersExtractServiceIsDispatchable() {
        Set<String> allowed = SourceSystemConnectorSupport.allowedServiceNames(ec)
        String reconService = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders"

        assertTrue(allowed.contains(reconService),
                "the connector row must auto-permit its extract service: ${allowed}")
        assertTrue(SourceSystemConnectorSupport.isAllowedExtractorServiceShape(reconService),
                "the service name must also satisfy the defense-in-depth naming guard")
    }

    @Test
    void omsReturnsConnectorIsRegisteredAndCarriesExclusionSupport() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(
                ec, ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS)

        assertNotNull(connector, "OMS_RETURNS connector row must exist and be enabled")
        assertEquals(ReconciliationSavedRunSupport.HOTWAX_OMS_RETURNS_EXTRACT_SERVICE,
                connector.extractServiceName)
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST_RETURNS,
                connector.expectedSourceConfigType)
        // Without filterParameterName the channel exclusion cannot dispatch at all, and the rules
        // board hides the control — a configured rule would validate, persist and never run (§5).
        assertEquals("sourceFilters", connector.filterParameterName)
    }

    /**
     * Ratchet, not a spot check. resolveByExtractServiceName and resolveByExpectedSourceConfigType
     * both take the FIRST enabled match, so two enabled rows sharing either attribute resolve
     * ambiguously and silently — and the row that loses could be a shipped one. Adding a connector
     * that clones an existing config type must fail here rather than in production.
     */
    @Test
    void noTwoEnabledConnectorsShareAnExtractServiceOrConfigType() {
        List rows = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).useCache(false).list()
                .findAll { row -> "Y".equalsIgnoreCase(row.enabled?.toString()) }

        Map<String, List<String>> byConfigType = [:]
        Map<String, List<String>> byExtractService = [:]
        rows.each { row ->
            String configType = row.expectedSourceConfigType?.toString()?.trim()
            String extractService = row.extractServiceName?.toString()?.trim()
            String systemEnumId = row.systemEnumId?.toString()
            if (configType) byConfigType.computeIfAbsent(configType, { [] }).add(systemEnumId)
            if (extractService) byExtractService.computeIfAbsent(extractService, { [] }).add(systemEnumId)
        }

        Map duplicateConfigTypes = byConfigType.findAll { ignored, owners -> owners.size() > 1 }
        Map duplicateExtractServices = byExtractService.findAll { ignored, owners -> owners.size() > 1 }

        assertTrue(duplicateConfigTypes.isEmpty(),
                "expectedSourceConfigType claimed by more than one enabled connector: ${duplicateConfigTypes}")
        assertTrue(duplicateExtractServices.isEmpty(),
                "extractServiceName claimed by more than one enabled connector: ${duplicateExtractServices}")
    }

    /**
     * Ratchet, not a spot check. HOTWAX_OMS_RECON_ORDER_FIELD_OPTIONS is currently derived in code by
     * filtering HOTWAX_OMS_ORDER_FIELD_OPTIONS, so the two provably cannot diverge. Once both become
     * SourceSystemConnectorField seed rows (Task 5), that guarantee is gone — this test replaces it.
     * The recon endpoint (OMS_RECON_ORDERS) projects server-side to a subset of the OMS document; an
     * extra pill there names a field that never arrives, so a rule on it validates, persists, and
     * excludes nothing.
     */
    @Test
    void reconOrderPillsAreASubsetOfOmsPills() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        Set<String> omsPaths = pillPaths("OMS")
        Set<String> reconPaths = pillPaths("OMS_RECON_ORDERS")

        assertTrue(omsPaths.containsAll(reconPaths),
                "The recon endpoint projects server-side to a subset of the OMS document. Extra pills " +
                "here name fields that never arrive: ${(reconPaths - omsPaths).sort()}")
    }

    /**
     * Ratchet, not a spot check. SourceFilterSupport matches top-level record keys only, via
     * CompareIdExpressionSupport.topLevelRecordField. A nested path or a list-valued key persists a
     * rule that validates, matches nothing, and reports no error.
     */
    @Test
    void everyPillNamesATopLevelScalarKey() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        List rows = ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .useCache(false).list() ?: []
        assertFalse(rows.isEmpty(), "Seed data did not load")

        // SourceFilterSupport matches top-level record keys only, via
        // CompareIdExpressionSupport.topLevelRecordField. Anything else persists a rule that
        // validates, matches nothing, and reports no error.
        List<String> offenders = rows.collect { it.fieldPath as String }.findAll { String path ->
            !(path ==~ /^\$\.records\[\*\]\.[A-Za-z][A-Za-z0-9]*$/)
        }
        assertTrue(offenders.isEmpty(), "Non top-level pill paths: ${offenders.sort()}")

        // List-valued keys are top-level but equally unusable. These are the known ones on
        // SHOPIFY_RETURN_REFS; a new list-valued field must be added here when its endpoint ships.
        List<String> listValued = ["refundIds", "returnIds", "refunds", "returns"]
        List<String> listOffenders = rows.findAll { it.systemEnumId == "SHOPIFY_RETURN_REFS" }
                .collect { (it.fieldPath as String).substring("\$.records[*].".length()) }
                .findAll { listValued.contains(it) }
        assertTrue(listOffenders.isEmpty(), "List-valued pills are not offerable: ${listOffenders.sort()}")
    }

    private Set<String> pillPaths(String systemEnumId) {
        return (ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", systemEnumId).useCache(false).list() ?: [])
                .collect { it.fieldPath as String } as Set<String>
    }

    /**
     * Task 5 (Plan 2). primaryIdOptionsForSystem / fieldOptionsForSystem now read
     * SourceSystemConnectorField instead of the five deleted AutomationFacadeSupport constants. The
     * substance of the change: OMS_RETURNS and SHOPIFY_RETURN_REFS had NO case in the old
     * hand-written switch (default -> [] / null) and must now return real options purely because a
     * registry row exists for them — no Groovy branch was added for either.
     */
    @Test
    void pillLookupsComeFromTheRegistry() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        // The endpoints that had NO case in the old switch must now return real options.
        assertFalse(AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS_RETURNS").isEmpty(),
                "OMS_RETURNS fell through to default: return [] before this change")
        assertFalse(AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "SHOPIFY_RETURN_REFS").isEmpty())

        assertEquals(["\$.records[*].orderId", "\$.records[*].orderName", "\$.records[*].externalId"],
                AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS").collect { it.fieldPath })
        assertEquals("Return channel",
                AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS")
                        .find { it.fieldPath == "\$.records[*].returnChannelEnumId" }.label)
    }

    /**
     * Moved from AutomationFacadeSupportTests.groovy (Task 5, Plan 2): primaryIdOptionsForSystem /
     * fieldOptionsForSystem gained an ec parameter and now read the SourceSystemConnectorField
     * registry, so these assertions need a real, seed-backed ExecutionContext rather than the
     * ec-free fixture that file otherwise sticks to.
     *
     * DAR-BE-018. The reconciliationOrders connector is a second OMS orders source with its own
     * systemEnumId, and these lookups key on systemEnumId equality against the registry. Without a
     * matching row they return empty, and the rules board silently offers no field pills and no
     * primary-id choices for a source the operator can otherwise configure — a connector that
     * validates and then cannot be given a join key.
     *
     * systemAliases does NOT cover this: aliases are read only by SourceSystemConnectorSupport.resolve
     * to find a row FROM an id, and have no effect on these comparisons.
     */
    @Test
    void reconciliationOrdersSystemOffersRulesBoardPillsAtAll() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        assertFalse(AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RECON_ORDERS").isEmpty(),
                "without pills the rules board cannot configure a source this connector can otherwise save")
        assertEquals(AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS"),
                AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS_RECON_ORDERS"),
                "the join key must be selectable from the same choices — all three are projected fields")
    }

    /**
     * Moved from AutomationFacadeSupportTests.groovy (Task 5, Plan 2) — see note above.
     *
     * The recon endpoint projects server-side to a fixed six-field set, so the two pills that exist
     * only because the LEGACY endpoint ships whole order documents (salesChannelEnumId,
     * productStoreId) must not be offered here. A rule drawn on a field the endpoint never returns
     * would validate, persist, and then exclude nothing — silently.
     */
    @Test
    void reconciliationOrdersPillsAreLimitedToProjectedFields() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        List<String> reconFields = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RECON_ORDERS")
                .collect { Map option -> (option.fieldPath as String).substring("\$.records[*].".length()) }
        List<String> legacyFields = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS")
                .collect { Map option -> (option.fieldPath as String).substring("\$.records[*].".length()) }

        assertEquals(["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"], reconFields)
        assertFalse(reconFields.contains("salesChannelEnumId"))
        assertFalse(reconFields.contains("productStoreId"))
        // Still a strict subset of the legacy list, so the two cannot drift into disagreeing labels.
        assertTrue(legacyFields.containsAll(reconFields))
    }

    /**
     * Moved from AutomationFacadeSupportTests.groovy (Task 5, Plan 2) — see note above.
     * fieldOptionsForSystem used to hard-branch on known system ids and return null for anything
     * else; a null fell back to primaryIdOptions and the returnChannelEnumId exclusion pill silently
     * never appeared — no error, no failing test, no log line. Now it reads the registry, so this
     * pins actual presence rather than mere non-nullness.
     */
    @Test
    void returnsSystemOffersTheChannelPill() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        List<Map<String, Object>> options = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS")

        assertFalse(options.isEmpty(), "OMS_RETURNS must have a curated pill list — an empty list means the "
                + "channel pill silently disappears (design §5)")
        List<String> paths = options.collect { it.fieldPath as String }
        assertTrue(paths.contains("\$.records[*].returnChannelEnumId"),
                "the channel pill is the whole point of the returns exclusion: ${paths}")
        assertTrue(paths.contains("\$.records[*].externalId"))
        assertTrue(paths.contains("\$.records[*].orderExternalId"))
    }

    /**
     * Moved from AutomationFacadeSupportTests.groovy (Task 5, Plan 2) — see note above.
     * SourceFilterSupport matches against top-level keys via CompareIdExpressionSupport
     * .topLevelRecordField, so a nested path would persist a rule that excludes nothing.
     */
    @Test
    void returnsPillsNameOnlyTopLevelRecordKeys() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorFieldSeedData.xml")

        List<Map<String, Object>> options = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS")
        options.each { Map<String, Object> option ->
            String path = option.fieldPath as String
            String tail = path.substring(path.lastIndexOf('.') + 1)
            assertFalse(tail.contains("["), "nested pill path is unusable: ${path}")
            assertTrue(path.startsWith("\$.records[*]."), "unexpected pill path shape: ${path}")
        }
    }
}
