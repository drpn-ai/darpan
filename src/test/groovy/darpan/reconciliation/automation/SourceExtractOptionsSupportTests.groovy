package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-064. A run side's extract options decide which records a run reads (gorjana R16 reads only cancelled returns),
 * so saving one the connector does not take must be refused, and a run must never be handed a parameter the connector
 * stopped declaring.
 */
class SourceExtractOptionsSupportTests {

    private static final Map<String, Object> OMS_RETURNS = [extractOptionsJson:
            '[{"name":"includeReturnsWithoutShopifyRef","label":"Include returns with no Shopify reference","type":"boolean"},' +
            '{"name":"onlyCancelledReturns","label":"Only cancelled returns","type":"boolean"},' +
            '{"name":"statusNote","type":"text"}]'] as Map<String, Object>

    @Test
    void declaredOptionsReadTheConnector() {
        List<Map<String, Object>> options = SourceExtractOptionsSupport.declaredOptions(OMS_RETURNS)
        assertEquals(["includeReturnsWithoutShopifyRef", "onlyCancelledReturns", "statusNote"], options*.name)
        assertEquals("Only cancelled returns", options[1].label)
        assertEquals("statusNote", options[2].label, "an option with no label shows its name")
        assertEquals([], SourceExtractOptionsSupport.declaredOptions([:]))
        assertEquals([], SourceExtractOptionsSupport.declaredOptions(null))
    }

    @Test
    void savesOnlyWhatIsOnAndSortsIt() {
        assertEquals('{"onlyCancelledReturns":true}',
                SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [onlyCancelledReturns: true, includeReturnsWithoutShopifyRef: false]))
        assertEquals('{"includeReturnsWithoutShopifyRef":true,"onlyCancelledReturns":true}',
                SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, '{"onlyCancelledReturns":"true","includeReturnsWithoutShopifyRef":true}'))
        assertNull(SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [onlyCancelledReturns: false]), "all off stores nothing")
        assertNull(SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, null))
        assertNull(SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, "  "))
    }

    @Test
    void refusesAnOptionTheConnectorDoesNotTake() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [omsRestSourceConfigId: "other_tenant"], "File 1")
        }
        assertTrue(e.message.contains("File 1 has no option 'omsRestSourceConfigId'"), e.message)
        IllegalArgumentException none = assertThrows(IllegalArgumentException) {
            SourceExtractOptionsSupport.validatedJson([:], [onlyCancelledReturns: true])
        }
        assertTrue(none.message.contains("takes no options"), none.message)
    }

    @Test
    void refusesAWrongTypeOrAnUnreadableValue() {
        assertThrows(IllegalArgumentException) { SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [onlyCancelledReturns: "yes"]) }
        assertThrows(IllegalArgumentException) { SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, "{not json") }
        assertThrows(IllegalArgumentException) { SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, "[true]") }
        assertThrows(IllegalArgumentException) { SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [statusNote: "x" * 201]) }
        assertEquals('{"statusNote":"late"}', SourceExtractOptionsSupport.validatedJson(OMS_RETURNS, [statusNote: " late "]))
    }

    @Test
    void aRunPassesOnlyWhatTheConnectorStillDeclaresAndNeverThrows() {
        assertEquals([onlyCancelledReturns: true],
                SourceExtractOptionsSupport.runtimeOptions(OMS_RETURNS, '{"onlyCancelledReturns":true,"retired":true}'))
        assertEquals([:], SourceExtractOptionsSupport.runtimeOptions([:], '{"onlyCancelledReturns":true}'))
        assertEquals([:], SourceExtractOptionsSupport.runtimeOptions(OMS_RETURNS, "{broken"))
        assertEquals([:], SourceExtractOptionsSupport.runtimeOptions(OMS_RETURNS, null))
    }

    @Test
    void optionsNeverDisplaceTheConfigIdOrWindow() {
        Map<String, Object> params = [omsRestSourceConfigId: "gorjana_prod", windowStart: "2026-10-21"] as Map<String, Object>
        SourceExtractOptionsSupport.mergeUnder(params, [omsRestSourceConfigId: "other", onlyCancelledReturns: true] as Map<String, Object>)
        assertEquals("gorjana_prod", params.omsRestSourceConfigId)
        assertEquals(true, params.onlyCancelledReturns)
    }

    @Test
    void storedOnReadsAMapSource() {
        assertEquals('{"a":true}', SourceExtractOptionsSupport.storedOn([extractParametersJson: '{"a":true}']))
        assertNull(SourceExtractOptionsSupport.storedOn([:]))
        assertNull(SourceExtractOptionsSupport.storedOn(null))
    }

    /** DAR-BE-192: the core's readByOtherSideKeys is taken on any side, stored and handed to the run, whatever the connector declares. */
    @Test
    void theCoresOwnOptionIsTakenOnAnySide() {
        assertEquals('{"readByOtherSideKeys":true}', SourceExtractOptionsSupport.validatedJson([:], [readByOtherSideKeys: true]))
        assertEquals([readByOtherSideKeys: true, onlyCancelledReturns: true],
                SourceExtractOptionsSupport.runtimeOptions(OMS_RETURNS, '{"readByOtherSideKeys":true,"onlyCancelledReturns":true,"gone":true}'))
        assertEquals(["includeReturnsWithoutShopifyRef", "onlyCancelledReturns", "statusNote"],
                SourceExtractOptionsSupport.declaredOptions(OMS_RETURNS)*.name, "the run builder still offers only the connector's")
    }

    /**
     * DAR-BE-192: SourceSystemConnector.extractOptionsJson is text-medium (255). A longer seeded value fails its row and Moqui
     * skips the rest of the file, so every later connector silently keeps its old row (found live: 6 of 10 loaded).
     */
    @Test
    void everySeededConnectorsOptionsFitTheirColumn() {
        java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (root != null && !java.nio.file.Files.exists(root.resolve("entity/RuleEntities.xml"))) {
            root = java.nio.file.Files.exists(root.resolve("runtime/component/darpan/entity/RuleEntities.xml")) ? root.resolve("runtime/component/darpan") : root.parent
        }
        File seed = root.resolve("data/SourceSystemConnectorSeedData.xml").toFile()
        def xml = new groovy.xml.XmlSlurper().parse(seed)
        List rows = xml.children().findAll { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@extractOptionsJson.text() }
        assertTrue(rows.size() > 0, "no connector declares options; the seed moved?")
        rows.each { def row ->
            String json = row.@extractOptionsJson.text()
            assertTrue(json.length() <= 255, "${row.@systemEnumId} extractOptionsJson is ${json.length()} characters")
            new groovy.json.JsonSlurper().parseText(json)
        }
    }
}
