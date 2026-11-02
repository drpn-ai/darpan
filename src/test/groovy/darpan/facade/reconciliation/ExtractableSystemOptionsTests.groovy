package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-BE-052: the source picker offers only endpoints a connector can read. */
class ExtractableSystemOptionsTests {

    private static final Set<String> CONNECTORS = ["OMS_GQL", "OMS_RETURNS_GQL", "SHOPIFY", "SHOPIFY_RETURN_REFS"] as Set

    private static List<Map<String, Object>> pick(List<Map<String, Object>> options) {
        return AutomationFacadeSupport.extractableSystemOptions(options, { String id -> id in CONNECTORS })
    }

    @Test
    void anEndpointWithNoConnectorIsNotOffered() {
        List<Map<String, Object>> options = [
                [enumId: "OMS"], [enumId: "OMS_GQL", parentEnumId: "OMS"], [enumId: "OMS_RETURNS", parentEnumId: "OMS"],
                [enumId: "OMS_RETURNS_GQL", parentEnumId: "OMS"], [enumId: "OMS_TRANSFER_ORDERS", parentEnumId: "OMS"]]
        assertEquals(["OMS", "OMS_GQL", "OMS_RETURNS_GQL"], pick(options)*.enumId)
    }

    @Test
    void aParentWithoutItsOwnConnectorGroupsButIsNotAnEndpoint() {
        List<Map<String, Object>> picked = pick([[enumId: "OMS"], [enumId: "OMS_GQL", parentEnumId: "OMS"],
                                                 [enumId: "SHOPIFY"], [enumId: "SHOPIFY_RETURN_REFS", parentEnumId: "SHOPIFY"]])
        assertEquals(false, picked.find { it.enumId == "OMS" }.extractable)
        assertEquals(false, picked.find { it.enumId == "SHOPIFY" }.containsKey("extractable"), "a readable parent stays an endpoint")
    }

    @Test
    void aTopLevelSystemWithNoEndpointsIsUnchanged() {
        assertEquals([[enumId: "SAPI"]], pick([[enumId: "SAPI"]]))
    }
}
