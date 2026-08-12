package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Focused unit tests for the HIGH gap 6 fix: validateApiSourceMetadata must reject any
 * extractServiceName outside the known extract/execute service allowlist, so a caller cannot persist
 * an arbitrary Moqui service name that is later invoked with disableAuthz() on every scheduled run.
 *
 * Uses a minimal Expando ec stub (no Moqui bootstrap) — the method only touches ec.message.addError.
 */
class AutomationFacadeSupportExtractServiceAllowlistTests {

    private static MessageStub callValidate(String extractServiceName, Map<String, Object> parameters = [:]) {
        MessageStub message = new MessageStub()
        def ec = new Expando(message: message, entity: connectorEntityStub())
        Map<String, Object> metadata = [extractServiceName: extractServiceName]
        if (parameters) metadata.parameters = parameters
        Map<String, Object> source = [
                fileSide        : "FILE_1",
                safeMetadataJson: groovy.json.JsonOutput.toJson(metadata),
        ]
        AutomationFacadeSupport.validateApiSourceMetadata(ec, source)
        return message
    }

    @Test
    void allowsKnownExtractServices() {
        assertFalse(callValidate(AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                [omsRestSourceConfigId: "OMS_CFG"]).hasError())
        assertFalse(callValidate(AutomationFacadeSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE,
                [shopifyAuthConfigId: "SHOP_CFG"]).hasError())
        assertFalse(callValidate(AutomationFacadeSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE).hasError())
    }

    /**
     * DAR-BE-018. The required-parameter branch keys on the extract service NAME, so a second OMS
     * connector with its own service name would skip the check entirely and persist a source with no
     * omsRestSourceConfigId — which then fails at run time, inside a scheduled automation, rather
     * than at save time in front of the operator.
     */
    @Test
    void reconOrdersSourceStillRequiresItsOmsConfigId() {
        MessageStub message = callValidate(AutomationFacadeSupport.HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE)
        assertTrue(message.hasError())
        assertTrue(message.errors.any { it.contains("omsRestSourceConfigId") },
                "Unexpected errors: ${message.errors}")
    }

    @Test
    void allowsReconOrdersSourceCarryingItsConfigId() {
        assertFalse(callValidate(AutomationFacadeSupport.HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE,
                [omsRestSourceConfigId: "OMS_CFG"]).hasError())
    }

    @Test
    void rejectsArbitraryServiceName() {
        MessageStub message = callValidate("darpan.reconciliation.SomeInternalService")
        assertTrue(message.hasError())
        assertTrue(message.errors.any { it.contains("is not in the allowed service list") },
                "Unexpected errors: ${message.errors}")
    }

    @Test
    void rejectsMissingServiceName() {
        MessageStub message = callValidate(null)
        assertTrue(message.hasError())
    }

    // Minimal entity-facade stub so the save-side allow-list (now registry-derived via
    // SourceSystemConnectorSupport.allowedServiceNames) resolves the known OMS/SHOPIFY services without a
    // Moqui bootstrap. Mirrors data/SourceSystemConnectorSeedData.xml (extract + remoteSend service names).
    private static Object connectorEntityStub() {
        List<Map<String, Object>> rows = [
                [systemEnumId         : "OMS",
                 extractServiceName   : AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                 remoteSendServiceName: AutomationFacadeSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                 enabled              : "Y"],
                [systemEnumId         : "SHOPIFY",
                 extractServiceName   : AutomationFacadeSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE,
                 remoteSendServiceName: AutomationFacadeSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE,
                 enabled              : "Y"],
                [systemEnumId         : AutomationFacadeSupport.OMS_RECON_SYSTEM_ENUM_ID,
                 extractServiceName   : AutomationFacadeSupport.HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE,
                 remoteSendServiceName: AutomationFacadeSupport.HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE,
                 enabled              : "Y"],
        ]
        Expando finder = new Expando()
        finder.useCache = { boolean ignored -> finder }
        finder.list = { -> rows }
        Expando entity = new Expando()
        entity.find = { String ignored -> finder }
        return entity
    }

    static class MessageStub {
        List<String> errors = []
        void addError(Object error) { errors.add(error?.toString()) }
        boolean hasError() { return !errors.isEmpty() }
    }
}
