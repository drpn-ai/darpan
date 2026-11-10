package darpan.reconciliation.automation

import darpan.facade.reconciliation.AutomationFacadeSupport
import darpan.facade.reconciliation.MissingDiffVerificationSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
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
    private static final String OMS_GQL_EXTRACT_SERVICE = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders"
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
    void resolvesOmsGqlRow() {
        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, "OMS_GQL")
        assertNotNull(c, "OMS_GQL connector row should resolve")
        assertEquals("OMS_GQL", c.systemEnumId)
        assertEquals(OMS_GQL_EXTRACT_SERVICE, c.extractServiceName)
        assertEquals("windowStart", c.dateFromParameterName)
        assertEquals("windowEnd", c.dateToParameterName)
        assertEquals("HOTWAX_OMS_GQL", c.expectedSourceConfigType)
        assertEquals("HOTWAX_ORDERS_API", c.remoteId)
        assertEquals("Orders", c.endpointLabel)
        // OMS does NOT preserve window instants (only Shopify does — AutomationExecutionSupport:634).
        assertFalse((boolean) c.preserveWindowInstants)
    }

    @Test
    void noHotWaxRestConnectorResolves() {
        // DAR-BE-052: HotWax is read over GraphQL only; the REST rows are gone from the seed.
        ["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_ORDER_ITEMS", "OMS_ORDER_LINE_UNITS", "OMS_TRANSFER_ORDERS"].each {
            assertNull(SourceSystemConnectorSupport.resolveBySystemEnumId(ec, it), "${it} must not resolve")
        }
        assertNull(SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "HOTWAX_OMS_REST"))
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
        Map<String, Object> oms = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "HOTWAX_OMS_GQL")
        assertNotNull(oms)
        assertEquals(OMS_GQL_EXTRACT_SERVICE, oms.extractServiceName)

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
        Map<String, Object> viaAlias = SourceSystemConnectorSupport.resolve(ec, "HOTWAX_GQL")
        assertNotNull(viaAlias, "alias HOTWAX_GQL should resolve to the OMS_GQL connector")
        assertEquals("OMS_GQL", viaAlias.systemEnumId)
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
        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS_GQL")
        assertNotNull(oms, "OMS_GQL connector row should resolve")
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

        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS_GQL")
        assertNotNull(oms, "OMS_GQL connector row should resolve")
        assertEquals("facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection", oms.healthCheckServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape((String) oms.healthCheckServiceName))

        // DAR-BE-114: NETSUITE gained a probe, and the fields test#SourceConnection finds the auth profile by.
        Map<String, Object> netsuite = SourceSystemConnectorSupport.resolve(ec, "NETSUITE")
        assertNotNull(netsuite, "NETSUITE connector row should resolve")
        assertEquals("facade.NetSuiteSettingsFacadeServices.probe#NsAuthConnection", netsuite.healthCheckServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape((String) netsuite.healthCheckServiceName))
        assertEquals("nsAuthConfigId", netsuite.configParameterName)
        assertEquals("darpan.reconciliation.NsAuthConfig", netsuite.configEntityName)
    }

    @Test
    void omsConnectorDeclaresTheExclusionFilterParameter() {
        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS_GQL")
        assertNotNull(oms, "OMS_GQL connector row should resolve")
        assertEquals("sourceFilters", oms.filterParameterName)
    }

    @Test
    void shopifyConnectorOptsIntoSourceFilters() {
        // Opted in 2026-09-30 (DAR-BE-063): the Shopify fulfilled -> NetSuite billed run keeps only
        // FULFILLED orders, and the order extract now filters per order and writes the excluded sidecar.
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertEquals("sourceFilters", shopify.filterParameterName)
    }

    @Test
    void shopifyReturnRefsConnectorDeclaresTheExclusionFilterParameter() {
        // Opted in 2026-08-27 for return-status exclusion. Without filterParameterName the rules board
        // hides the exclusion control (AutomationFacadeSupport.supportsExcludeFiltersForSystem ->
        // RuleSetBoard.supportsExclusions) and a configured rule would never dispatch. The canonical
        // SHOPIFY connector opted in separately on 2026-09-30 (DAR-BE-063) — see the sibling test above.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS")
        assertNotNull(connector, "SHOPIFY_RETURN_REFS connector row should resolve")
        assertEquals("sourceFilters", connector.filterParameterName)
    }

    @Test
    void theBoardOffersExactlyOneReturnStatusPillForShopifyReturnRefsAndItIsTheOrderLevelOne() {
        // 2026-09-01 (DAR-BE-026): this endpoint has now carried a status pill under three names, and
        // only the third survives. $.records[*].returnStatus (2026-08-27) was named after Shopify's
        // ORDER-level field but held Return.status; $.records[*].returnWorkflowStatus renamed it hours
        // later to stop that collision, which made the NAME honest but left the real failure in place —
        // its in-progress value is spelled OPEN, and no surface an operator can see says OPEN. Shopify's
        // admin says "Return in progress" and its order search says return_status:in_progress.
        // $.records[*].orderReturnStatus holds Order.returnStatus itself, so IN_PROGRESS works as typed.
        //
        // Asserting the other two are ABSENT is the load-bearing half: a pill naming a field the extract
        // no longer emits is a permanent silent no-op for any rule drawn on it.
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")
        Collection<String> paths = pillPaths("SHOPIFY_RETURN_REFS")
        assertTrue(paths.contains('$.records[*].orderReturnStatus'),
                "orderReturnStatus must be offerable as an exclusion pill: ${paths}")
        assertFalse(paths.contains('$.records[*].returnWorkflowStatus'),
                "the withdrawn per-return pill must not still be on offer: ${paths}")
        assertFalse(paths.contains('$.records[*].returnStatus'),
                "the original colliding name must not come back either: ${paths}")
    }

    @Test
    void theRenamedReturnStatusPillIsDeclaredRetiredSoLoadedDatabasesLoseIt() {
        // A rename is a retirement plus a new row as far as a createOrUpdate seed load is concerned:
        // the old PK survives in every database that already loaded it, and the board would offer
        // BOTH names — one of which no longer exists on the extract, so a rule on it silently matches
        // nothing forever. This endpoint's eventId/eventType rename set the precedent.
        assertTrue(SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS.any { Map<String, String> row ->
            row.systemEnumId == "SHOPIFY_RETURN_REFS" && row.fieldPath == '$.records[*].returnStatus'
        }, "the old returnStatus pill must be declared retired: ${SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS}")
        // WITHDRAWN 2026-09-01 (DAR-BE-026): the rename's own product is now retired too, for the same
        // structural reason — a database that loaded 1.5.1 holds the row and no seed load will remove it.
        assertTrue(SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS.any { Map<String, String> row ->
            row.systemEnumId == "SHOPIFY_RETURN_REFS" && row.fieldPath == '$.records[*].returnWorkflowStatus'
        }, "the withdrawn returnWorkflowStatus pill must be declared retired: ${SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS}")
    }

    @Test
    void theShopifyReturnRefsKeepFieldsBaseNamesOnlyTheOrderLevelStatusField() {
        // keepFieldsBase is documentation-only on this row (the service declares no keep-fields
        // parameter), but it is what a reader consults for the record shape — leaving a withdrawn name
        // would re-teach exactly the mistake this change fixes.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS")
        assertNotNull(connector, "SHOPIFY_RETURN_REFS connector row should resolve")
        List<String> keepFields = ((String) connector.keepFieldsBase).split(",").collect { it.trim() }
        assertTrue(keepFields.contains("orderReturnStatus"), "keepFieldsBase should name the surviving key: ${keepFields}")
        assertFalse(keepFields.contains("returnWorkflowStatus"), "keepFieldsBase should drop the withdrawn key: ${keepFields}")
        assertFalse(keepFields.contains("returnStatus"), "keepFieldsBase should not name the original key either: ${keepFields}")
    }

    @Test
    void theBoardOffersTheOmsFilterPillsThatAreNotKeepFields() {
        // Exclusions run before projection, so a filter pill need not be a keep field: the store and channel
        // filters SM's order runs use are offered on OMS_GQL though neither is compared. Primary-ID choice stays
        // the three order identifiers.
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")
        Map<String, Object> oms = SourceSystemConnectorSupport.resolve(ec, "OMS_GQL")
        assertNotNull(oms, "OMS_GQL connector row should resolve")
        List<String> pillFields = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_GQL")
                .collect { Map<String, Object> option -> (option.fieldPath as String).substring("\$.records[*].".length()) }
        List<String> keepFields = ((String) oms.keepFieldsBase).split(",").collect { it.trim() }.findAll { it }
        assertTrue(pillFields.contains("productStoreId"))
        assertFalse(keepFields.contains("productStoreId"))
        assertTrue(pillFields.containsAll(["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"]))
        assertEquals(3, AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS_GQL").size())
    }

    @Test
    void omsReturnsConnectorIsRegisteredAndCarriesExclusionSupport() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(
                ec, ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS_GQL)

        assertNotNull(connector, "OMS_RETURNS_GQL connector row must exist and be enabled")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns",
                connector.extractServiceName)
        assertEquals("HOTWAX_OMS_GQL_RETURNS", connector.expectedSourceConfigType)
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
     * Ratchet, not a spot check. SourceFilterSupport matches top-level record keys only, via
     * CompareIdExpressionSupport.topLevelRecordField. A nested path or a list-valued key persists a
     * rule that validates, matches nothing, and reports no error.
     */
    @Test
    void everyPillNamesATopLevelScalarKey() {
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")

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
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")

        // The endpoints that had NO case in the old switch must now return real options.
        assertFalse(AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS_RETURNS_GQL").isEmpty(),
                "OMS_RETURNS_GQL must return real options from the registry")
        assertFalse(AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "SHOPIFY_RETURN_REFS").isEmpty())

        assertEquals(["\$.records[*].orderId", "\$.records[*].orderName", "\$.records[*].externalId"],
                AutomationFacadeSupport.primaryIdOptionsForSystem(ec, "OMS_GQL").collect { it.fieldPath })
        assertEquals("Return channel",
                AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS_GQL")
                        .find { it.fieldPath == "\$.records[*].returnChannelEnumId" }.label)
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
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")

        List<Map<String, Object>> options = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS_GQL")

        assertFalse(options.isEmpty(), "OMS_RETURNS_GQL must have a curated pill list — an empty list means the "
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
                "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")

        List<Map<String, Object>> options = AutomationFacadeSupport.fieldOptionsForSystem(ec, "OMS_RETURNS_GQL")
        options.each { Map<String, Object> option ->
            String path = option.fieldPath as String
            String tail = path.substring(path.lastIndexOf('.') + 1)
            assertFalse(tail.contains("["), "nested pill path is unusable: ${path}")
            assertTrue(path.startsWith("\$.records[*]."), "unexpected pill path shape: ${path}")
        }
    }

    /**
     * Task 10 (Plan 2). Moqui seed loads are createOrUpdate and never delete, so Task 3's removal of
     * SHOPIFY_RETURN_REFS / orderName from the seed file had no effect on a database that had already
     * loaded the earlier 27-row seed — browser verification confirmed the wizard still offered "Order
     * name" against the live dev DB. This simulates exactly that: load the CURRENT seed (which no
     * longer carries the row), then hand-insert the withdrawn row to reproduce a database that loaded
     * the older version, and prove the migration removes it — and ONLY it.
     */
    @Test
    void retiresAPillThatWasWithdrawnFromSeedData() {
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")
        // Simulate a database that loaded the older seed, which still carried the withdrawn pill.
        ec.entity.makeValue("darpan.reconciliation.SourceSystemConnectorField")
                .setAll([systemEnumId: "SHOPIFY_RETURN_REFS", fieldPath: "\$.records[*].orderName",
                         label: "Order name", fieldType: "string", isPrimaryIdCandidate: "Y",
                         sequenceNum: 2, description: "withdrawn"]).createOrUpdate()

        // An environment that loaded the 2026-08-27 seed still has the withdrawn per-return status
        // pill. A seed load is createOrUpdate and never deletes, so only the retirement migration can
        // remove it — without this it would sit on the board forever naming a field the extract no
        // longer emits, which is a permanent silent no-op for any rule using it.
        ec.entity.makeValue("darpan.reconciliation.SourceSystemConnectorField")
                .setAll([systemEnumId: "SHOPIFY_RETURN_REFS", fieldPath: "\$.records[*].returnWorkflowStatus",
                         label: "Return workflow status", fieldType: "string",
                         sequenceNum: 5, description: "withdrawn 2026-09-01"]).createOrUpdate()

        ec.message.clearAll()
        Map result = ec.service.sync().name("facade.SourceEndpointFacadeServices.migrate#RetiredConnectorFields")
                .disableAuthz().call()

        // The count is what the migration supervisor records as rowsAffected on the ledger row, and
        // the message is what it records as the run's detail — both are part of the contract.
        assertEquals(2, result.rowsDeleted)
        assertEquals(true, result.ok)
        assertEquals(["Deleted 2 retired field row(s)."], result.messages)
        assertEquals([], result.errors)

        assertEquals(0L, ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", "SHOPIFY_RETURN_REFS")
                .condition("fieldPath", "\$.records[*].returnWorkflowStatus")
                .useCache(false).count())

        assertEquals(0L, ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", "SHOPIFY_RETURN_REFS")
                .condition("fieldPath", "\$.records[*].orderName")
                .useCache(false).count())
        // The pills that remain must be untouched — a retirement must not sweep its neighbours.
        // 4, not 2: the 2026-08-17 returns-refund-grain-alignment plan's Task 2 (REVISION 2026-08-18)
        // widened the seeded set for this endpoint from {orderId, createdAt} to the four-field EVENT
        // shape {refundOrReturnId, refundOrReturnType, orderId, createdAt}.
        // 5, not 4: returnWorkflowStatus was seeded 2026-08-27 for return-status exclusion. The COUNT is
        // incidental to what this test guards — that the retirement swept only the withdrawn row —
        // so it tracks the seed file and is expected to move whenever a pill is legitimately added.
        // Still 5 on 2026-09-01 (DAR-BE-026): orderReturnStatus was seeded and returnWorkflowStatus
        // was withdrawn in the same change, so the count nets out. The endpoint now offers exactly
        // ONE status pill, holding Shopify's order-level Order.returnStatus.
        assertEquals(5L, ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", "SHOPIFY_RETURN_REFS").useCache(false).count())
    }

    /**
     * Idempotency half of Task 10: re-running the migration once the retired row is already gone (or
     * was never loaded) must delete nothing and not error.
     */
    @Test
    void reRunningTheMigrationDeletesNothingTheSecondTime() {
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedDataFields.xml")
        ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", "SHOPIFY_RETURN_REFS")
                .condition("fieldPath", "\$.records[*].orderName")
                .deleteAll()

        Map result = ec.service.sync().name("facade.SourceEndpointFacadeServices.migrate#RetiredConnectorFields")
                .disableAuthz().call()

        assertEquals(0, result.rowsDeleted)
        assertFalse((boolean) ec.message.hasError())
    }

    /**
     * The ratchet Task 10 exists to add. Without it, an operator could retire a pill (add it to
     * RETIRED_FIELDS) and re-seed it in the SAME release — the migration deletes a row the loader
     * immediately recreates on the next seed load, and the resulting state depends on run order.
     * Reads the seed file directly off disk rather than through the loaded ec, so it fails whether or
     * not this test class happens to load that file first.
     */
    @Test
    void noRetiredFieldReappearsInTheSeedFile() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path seedPath = backendRoot.resolve("runtime/component/darpan/data/SourceSystemConnectorSeedDataFields.xml")
        assertTrue(Files.exists(seedPath), "seed file not found at ${seedPath}")

        List seedRows = new XmlParser(false, false).parse(seedPath.toFile()).children()
                .findAll { it.name() == "darpan.reconciliation.SourceSystemConnectorField" }

        List<String> offenders = SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS.findAll { Map<String, String> retired ->
            seedRows.any { row ->
                row.attributes().get("systemEnumId") == retired.systemEnumId &&
                        row.attributes().get("fieldPath") == retired.fieldPath
            }
        }.collect { "${it.systemEnumId} / ${it.fieldPath}".toString() }

        assertTrue(offenders.isEmpty(),
                "RETIRED_FIELDS entries still present in SourceSystemConnectorSeedDataFields.xml — the seed " +
                "loader would recreate a row this migration deletes, so the resulting state would depend on " +
                "run order: ${offenders}")
    }

    @Test
    void omsReturnsConnectorDeclaresTheOrderStateLookupService() {
        // The cancellation-refund suppression dispatches through this slot. Seeded on the OMS returns endpoint:
        // it is the OMS API that gets called, even though the rows being suppressed are Shopify events.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        assertNotNull(connector, "OMS_RETURNS_GQL connector row must be seeded")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId",
                connector.orderStateLookupServiceName)
        // Dispatch goes through the same lookup fence as every other verify-stage slot.
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(
                connector.orderStateLookupServiceName as String))
    }

    @Test
    void theOrderStateLookupIsDeclaredExactlyByTheOmsReturnsEndpoints() {
        // DAR-BE-040 fix 3: OMS_RETURNS_GQL is the second endpoint that genuinely needs the slot — without
        // it, cancelled-order refunds on a GraphQL returns run come back as findings. Double dispatch stays
        // impossible: the return-presence pass picks ONE oms side and ONE shopify side per run.
        List rows = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME)
                .condition("orderStateLookupServiceName", org.moqui.entity.EntityCondition.IS_NOT_NULL, null)
                .useCache(false).list()*.systemEnumId
        assertEquals(["OMS_RETURNS_GQL"], rows.sort())
    }

    @Test
    void theReturnPresencePassRecognisesEveryOmsReturnsEndpoint() {
        // The pass found its OMS side by systemEnumId == "OMS_RETURNS" alone, so a GraphQL returns run
        // would have skipped it entirely — and on 2026-09-02 that pass removed 67 false differences.
        // Every endpoint that declares the order-state lookup must be one the pass treats as the OMS side.
        List<String> declarers = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME)
                .condition("orderStateLookupServiceName", org.moqui.entity.EntityCondition.IS_NOT_NULL, null)
                .useCache(false).list()*.systemEnumId
        declarers.each { String systemEnumId ->
            assertTrue(darpan.facade.reconciliation.RunVerificationSupport.isOmsReturnsSystem(systemEnumId),
                    "${systemEnumId} declares the order-state lookup but the return-presence pass ignores it")
        }
        assertFalse(darpan.facade.reconciliation.RunVerificationSupport.isOmsReturnsSystem("OMS"))
        assertFalse(darpan.facade.reconciliation.RunVerificationSupport.isOmsReturnsSystem("SHOPIFY_RETURN_REFS"))
    }

    @Test
    void orderStateLookupIsDistinctFromThePairLookupSlot() {
        // Same service today, but the two slots name DIFFERENT stages (exchange pair verify vs
        // cancellation-refund suppression). Re-pointing one must not silently move the other, so the
        // OMS_RETURNS row must not acquire a pairLookupServiceName by copy-paste.
        Map<String, Object> omsReturns = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        assertNotNull(omsReturns, "OMS_RETURNS_GQL connector row should resolve")
        assertNull(omsReturns.pairLookupServiceName,
                "OMS_RETURNS drives the returns suppression, not the exchange pair verify stage")
    }

    /**
     * Dispatch contract for the cancellation-refund suppression (2026-08-18).
     *
     * runSavedRunDiff's returns verify stage calls the service named by orderStateLookupServiceName
     * with exactly three parameters: the connector's own configParameterName, externalIds, and
     * companyUserGroupId (the run owner's tenant — a scheduled run's session tenant is not guaranteed
     * to be the owner's). Moqui drops undeclared in-parameters silently, so a renamed parameter would
     * not raise anything: the lookup would just return ok=false or resolve against the wrong tenant,
     * the stage would fail closed, and a run that suppresses nothing looks exactly like a healthy one.
     * This pins the contract at build time instead, since the run path itself cannot be exercised
     * offline (an API-source run dies at extraction without live credentials).
     */
    @Test
    void theOrderStateLookupServiceDeclaresEveryParameterTheReturnsStageDispatches() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        assertNotNull(connector, "OMS_RETURNS_GQL connector row should resolve")
        String serviceName = connector.orderStateLookupServiceName as String

        // "<package>.<ServicesFile>.<verb>#<noun>" -> the file that must define it.
        String noun = serviceName.substring(serviceName.indexOf("#") + 1)
        String verb = serviceName.substring(serviceName.lastIndexOf(".") + 1, serviceName.indexOf("#"))
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path servicePath = backendRoot.resolve(
                "runtime/component/darpan-hotwax/service/reconciliation/HotWaxOmsExtractionServices.xml")
        assertTrue(Files.exists(servicePath), "service file not found at ${servicePath}")

        def service = new XmlParser(false, false).parse(servicePath.toFile()).children().find {
            it.name() == "service" && it.attributes().get("noun") == noun && it.attributes().get("verb") == verb
        }
        assertNotNull(service, "${serviceName} is seeded on OMS_RETURNS but defined nowhere")

        List<String> declared = service.children()
                .find { it.name() == "in-parameters" }?.children()
                ?.collect { it.attributes().get("name") as String } ?: []

        // The config id, under the name THIS connector row says to send it as.
        assertTrue(declared.contains(connector.configParameterName as String),
                "dispatch sends the config id as '${connector.configParameterName}'; service declares ${declared}")
        // The id batch, under the name buildFencedLookup/the returns stage pass it as.
        assertTrue(declared.contains("externalIds"), "service must accept externalIds; declares ${declared}")
        // The run owner's tenant, so a scheduled run does not resolve config against a session tenant.
        assertTrue(declared.contains("companyUserGroupId"),
                "service must accept companyUserGroupId or scheduled runs silently use the wrong tenant; declares ${declared}")
    }

    @Test
    void theReturnLinesLookupIsDeclaredExactlyByTheOmsReturnsEndpoints() {
        // DAR-BE-085/086: order + line pairing. Both transports of the OMS returns side declare it, and
        // every declarer must be a side the return-presence pass actually treats as OMS.
        List rows = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME)
                .condition("returnLinesLookupServiceName", org.moqui.entity.EntityCondition.IS_NOT_NULL, null)
                .useCache(false).list()*.systemEnumId
        assertEquals(["OMS_RETURNS_GQL"], rows.sort())
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnLinesByOrderExternalId",
                connector.returnLinesLookupServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(connector.returnLinesLookupServiceName as String))
    }

    @Test
    void theReturnLinesLookupServiceDeclaresEveryParameterTheReturnsStageDispatches() {
        // Same reasoning as the order-state contract above: Moqui drops undeclared in-parameters silently,
        // so a renamed one would make pairing fail closed and look exactly like "nothing to pair".
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        String serviceName = connector.returnLinesLookupServiceName as String
        String noun = serviceName.substring(serviceName.indexOf("#") + 1)
        String verb = serviceName.substring(serviceName.lastIndexOf(".") + 1, serviceName.indexOf("#"))
        Path servicePath = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve(
                "runtime/component/darpan-hotwax/service/reconciliation/HotWaxOmsExtractionServices.xml")
        def service = new XmlParser(false, false).parse(servicePath.toFile()).children().find {
            it.name() == "service" && it.attributes().get("noun") == noun && it.attributes().get("verb") == verb
        }
        assertNotNull(service, "${serviceName} is seeded on OMS_RETURNS but defined nowhere")
        List<String> declared = service.children().find { it.name() == "in-parameters" }?.children()
                ?.collect { it.attributes().get("name") as String } ?: []
        assertTrue(declared.containsAll([connector.configParameterName as String, "externalIds", "companyUserGroupId"]),
                "the returns stage dispatches config id, externalIds and companyUserGroupId; service declares ${declared}")
        List<String> outputs = service.children().find { it.name() == "out-parameters" }?.children()
                ?.collect { it.attributes().get("name") as String } ?: []
        assertTrue(outputs.containsAll(["ok", "returnLinesByExternalId", "errors"]),
                "script services return DECLARED outputs only; declares ${outputs}")
    }

    @Test
    void shopifyReturnRefsConnectorDeclaresTheRefundOrReturnPointLookup() {
        // Without this the generic missing-diff verification pass never runs for the returns pair, so
        // every order the Shopify extract's search-index net misses becomes a permanent false
        // "missing in Shopify" with nothing to catch it (gorjana prod 2026-08-18: 566 of 601).
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS")
        assertNotNull(connector, "SHOPIFY_RETURN_REFS connector row should resolve")
        assertEquals("reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyRefundOrReturnIds",
                connector.lookupServiceName)
        assertTrue(SourceSystemConnectorSupport.isAllowedLookupServiceShape(connector.lookupServiceName as String))
        // The rows carry refund/return ids, never order ids — the dispatch must not send them as orderIds.
        assertEquals("refundOrReturnIds", connector.lookupIdsParameterName)
    }

    @Test
    void connectorsWithoutAnIdsParameterNameFallBackToTheOrderIdsDefault() {
        // buildVerificationLookup defaults a blank slot to "orderIds", so the canonical Shopify orders
        // lookup must keep resolving blank rather than acquire a redundant explicit value.
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertEquals("reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds", shopify.lookupServiceName)
        assertNull(shopify.lookupIdsParameterName, "the orders lookup relies on the orderIds default")
    }

    @Test
    void theRefundOrReturnLookupServiceDeclaresEveryParameterTheVerificationPassDispatches() {
        // Same build-time tripwire as the OMS order-state lookup: Moqui drops undeclared in-parameters
        // silently, so a rename here would make the pass fail closed and look identical to a clean run.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS")
        assertNotNull(connector, "SHOPIFY_RETURN_REFS connector row should resolve")
        String serviceName = connector.lookupServiceName as String

        // Read the definition Moqui actually loads, not the raw XML: parameters may arrive through
        // <implements> (lookup#ShopifyIdExistence), and only the resolved list decides what survives.
        def service = ec.service.getServiceDefinition(serviceName)
        assertNotNull(service, "${serviceName} is seeded on SHOPIFY_RETURN_REFS but defined nowhere")

        List<String> declared = (List<String>) service.inParameterNameList
        assertTrue(declared.contains(connector.configParameterName as String),
                "dispatch sends the config id as '${connector.configParameterName}'; service declares ${declared}")
        assertTrue(declared.contains(connector.lookupIdsParameterName as String),
                "dispatch sends ids as '${connector.lookupIdsParameterName}'; service declares ${declared}")
        assertTrue(declared.contains("companyUserGroupId"),
                "service must accept companyUserGroupId or scheduled runs resolve config against the wrong tenant; declares ${declared}")
    }

    @Test
    void shopifyReturnRefsRaisesItsOwnLookupCapAboveTheSharedDefault() {
        // The shared 1000 default encodes "a gap this large is not index skew, so do not bother
        // looking". For the returns pair that reasoning is inverted: 601 of the OMS side's 903 records
        // were missing-in-Shopify in the 2026-08-18 prod run (67%), because Shopify's updated_at is not
        // reliably bumped and the extract's net is therefore never complete. At the shared default a
        // two-day window would switch the pass off exactly when the false positives peak.
        //
        // RESIZED 10000 -> 50000 (DAR-BE-036). 10000 was sized as "~11x observed DAILY volume", and a
        // window-scaled quantity measured in days has a cliff measured in days: gorjana's first
        // full-MONTH run (result 101073, 2026-09-02) produced 17,172 and skipped the pass entirely.
        // The sizing basis is now the WINDOW an operator may legitimately ask for — 50000 is ~3x that
        // measured month, so a quarter-long backfill still verifies. This number is only an API-spend
        // bound now: since partial-credit chunking landed, an oversized set degrades to a partial
        // answer instead of losing the whole pass, so it no longer has to protect correctness.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS")
        assertNotNull(connector, "SHOPIFY_RETURN_REFS connector row should resolve")
        assertEquals(50000, connector.lookupMaxIds)
        assertTrue((connector.lookupMaxIds as int) > 17172,
                "must clear the measured 30-day gorjana volume that skipped the pass on 2026-09-02")
        assertTrue((connector.lookupMaxIds as int) > MissingDiffVerificationSupport.DEFAULT_MAX_LOOKUP_IDS,
                "the point of the per-connector slot is to exceed the shared default")
    }

    // ------------------------------------------- DAR-BE-032 NetSuite, generalized by DAR-BE-044

    @Test
    void netSuiteSuiteQlResolvesAsOneRowForEveryNetSuitePair() {
        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        assertNotNull(c, "NETSUITE_SUITEQL connector row should resolve")
        assertEquals("NETSUITE_SUITEQL", c.systemEnumId)
        assertEquals("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders",
                c.extractServiceName)
        assertEquals("NETSUITE_SUITEQL", c.expectedSourceConfigType)
        // The config is the per-side QUERY, not the credential. That indirection is the whole of
        // DAR-BE-044: the query says which NetSuite population a side enumerates and how its columns
        // map, so a second NetSuite pair needs a second query row rather than a second connector.
        assertEquals("nsSuiteQlSourceQueryId", c.configParameterName)
        assertEquals("darpan.reconciliation.NsSuiteQlSourceQuery", c.configEntityName)
        assertEquals("windowStart", c.dateFromParameterName)
        assertEquals("windowEnd", c.dateToParameterName)
        assertFalse((boolean) c.preserveWindowInstants)
    }

    @Test
    void theOriginalNetSuiteOrdersIdStillResolvesThroughAnAlias() {
        // DAR-BE-032 shipped the id NETSUITE_ORDERS in its notes, probes and draft configuration.
        // Generalizing the row must not strand those: resolve() scans systemAliases, and the alias is
        // what keeps an existing reference pointing somewhere real instead of silently returning null.
        Map<String, Object> viaLegacyId = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_ORDERS")
        assertNotNull(viaLegacyId, "the pre-DAR-BE-044 id must still resolve")
        assertEquals("NETSUITE_SUITEQL", viaLegacyId.systemEnumId)
    }

    @Test
    void netSuiteSuiteQlDoesNotShadowTheHollowNetSuiteRow() {
        // Both resolvers take the FIRST enabled match on systemEnumId / expectedSourceConfigType, so a
        // NetSuite row that reused either attribute would silently capture dispatch aimed at the
        // original NETSUITE row. The hollow row must keep meaning exactly what it meant: resolvable,
        // and still declaring no extractor.
        Map<String, Object> hollow = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "NETSUITE_AUTH")
        assertNotNull(hollow, "the original NETSUITE row must still resolve")
        assertEquals("NETSUITE", hollow.systemEnumId)
        assertNull(hollow.extractServiceName, "the hollow NETSUITE row must still declare no extractor")

        Map<String, Object> suiteQl = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, "NETSUITE_SUITEQL")
        assertNotNull(suiteQl, "NETSUITE_SUITEQL must resolve to the SuiteQL row")
        assertEquals("NETSUITE_SUITEQL", suiteQl.systemEnumId)
    }

    @Test
    void netSuiteSuiteQlLeavesProjectionToTheQueryAndKeepsExclusionsOnTheRow() {
        // The DATABASE precedent, and for its stated reason: "projection is the SELECT list, where a
        // SQL author expects it". A keepFieldsBase on this row would have to be one field list shared
        // by every NetSuite pair, which cannot be right for more than one of them — so the registry
        // declares none and the query's field mapping IS the projection.
        Map<String, Object> c = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        assertNotNull(c)
        assertNull(c.keepFieldsParameterName,
                "projection moved to the query mapping; a registry-wide field list cannot serve every pair")
        assertNull(c.keepFieldsBase)
        // Exclusion rules stay on the row: they run client-side because SuiteQL has no knowledge of
        // tenant rules, and the OMS pair needs them for orders keyed directly into NetSuite.
        assertEquals("sourceFilters", c.filterParameterName)
        // No verification pass: SuiteQL enumerates a window completely in one pass, so there is no
        // bulk-export index skew for a recheck to correct.
        assertNull(c.lookupServiceName)
    }

    @Test
    void theOrdersLookupKeepsTheConservativeSharedDefault() {
        // The orders pair's bulk export IS expected to be near-complete, so a gap past the default
        // there still means something is broken and skipping remains the right call.
        Map<String, Object> shopify = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertNotNull(shopify, "SHOPIFY connector row should resolve")
        assertNull(shopify.lookupMaxIds, "the orders lookup must keep the shared conservative cap")
    }

    // DAR-BE-157: windowed is a real Boolean, Y unless a row says N ("N" is a non-empty, truthy String).
    @Test
    void windowedDefaultsToY() {
        assertEquals(true, SourceSystemConnectorSupport.resolve(ec, "OMS_GQL").windowed)
        assertEquals(true, SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL").windowed)
        ec.entity.makeValue(SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([systemEnumId: "UNWINDOWED_PHASE1", extractServiceName: "reconciliation.FakeExtractionServices.extract#FakeAll",
                         expectedSourceConfigType: "FAKE_ALL", windowed: "N", enabled: "Y"])
                .create()
        assertEquals(false, SourceSystemConnectorSupport.resolve(ec, "UNWINDOWED_PHASE1").windowed)
    }

    // DAR-BE-176: a data-defined endpoint names its DataSourceDefinition, and the resolver carries it; a coded one has none.
    @Test
    void aDataDefinedEndpointCarriesItsDefinition() {
        ec.entity.makeValue("darpan.datasource.DataSourceDefinition")
                .setAll([dataSourceDefinitionId: "ACME_INVOICES_V1", definitionName: "Acme invoices", protocol: "GRAPHQL",
                         definitionVersion: 1, statusId: "DRAFT", definitionJson: "{}"])
                .create()
        ec.entity.makeValue(SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([systemEnumId: "ACME_INVOICES", extractServiceName: "reconciliation.FakeExtractionServices.extract#FakeAcmeInvoices",
                         expectedSourceConfigType: "FAKE_ACME_INVOICES", dataSourceDefinitionId: "ACME_INVOICES_V1", enabled: "Y"])
                .create()
        assertEquals("ACME_INVOICES_V1", SourceSystemConnectorSupport.resolve(ec, "ACME_INVOICES").dataSourceDefinitionId)
        assertEquals("GRAPHQL", ec.entity.find("darpan.datasource.DataSourceDefinition")
                .condition("dataSourceDefinitionId", "ACME_INVOICES_V1").one().protocol)
        assertNull(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY").dataSourceDefinitionId)
    }
}
