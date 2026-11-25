package darpan.reconciliation.automation

import darpan.dataset.KeptCopySupport
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-368. A run side reading Shopify orders can read each order's lines, so an audit finding about the order lists
 * them: the SHOPIFY connector declares the withLineItems option (data/SourceSystemConnectorSeedData.xml); shopify-darpan's
 * extract#ShopifyOrders takes it (its own tests). Off is the default and is never stored, so every side without it keeps its
 * read and its kept-copy options key. No Moqui.
 */
class ShopifyLineItemsOptionTieTests {

    private static final File SEED = new File(System.getProperty("user.dir"), "data/SourceSystemConnectorSeedData.xml")

    private static Map<String, Object> shopifyConnector() {
        def row = new XmlSlurper().parse(SEED).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == "SHOPIFY" }
        return [systemEnumId: "SHOPIFY", extractOptionsJson: row.@extractOptionsJson.toString() ?: null] as Map<String, Object>
    }

    @Test
    void theShopifyConnectorDeclaresTheOptionAndOffIsNeverStored() {
        Map<String, Object> connector = shopifyConnector()
        assertEquals([[name: "withLineItems", label: "Order lines", type: "boolean"]], SourceExtractOptionsSupport.declaredOptions(connector))
        assertEquals('{"withLineItems":true}', SourceExtractOptionsSupport.validatedJson(connector, [withLineItems: true]))
        assertNull(SourceExtractOptionsSupport.validatedJson(connector, [withLineItems: false]), "off is not stored")
        assertEquals([withLineItems: true], SourceExtractOptionsSupport.runtimeOptions(connector, '{"withLineItems":true}'))
        assertEquals([:], KeptCopySupport.optionsKey(connector, [shopifyAuthConfigId: "C1", windowStart: "x"]))
    }
}
