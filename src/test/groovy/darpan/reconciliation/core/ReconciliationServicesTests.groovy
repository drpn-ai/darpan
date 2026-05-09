package darpan.reconciliation.core

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ReconciliationServicesTests {

    @Test
    void normalizesPlainJsonKeyToDefaultArrayJsonPath() {
        Map spec = ReconciliationServices.parseIdSpec("legacyResourceId", false)

        assertEquals('$[*].legacyResourceId', spec.idExpr)
        assertNull(spec.idNormalizer)
    }

    @Test
    void validatesAndPreservesRootedJsonPathWithNormalizer() {
        Map spec = ReconciliationServices.parseIdSpec('$.data.orders.edges[0].node.legacyResourceId|shopify-gid-tail', false)

        assertEquals('$.data.orders.edges[*].node.legacyResourceId', spec.idExpr)
        assertEquals('SHOPIFY_GID_TAIL', spec.idNormalizer)
    }

    @Test
    void rejectsLegacyIdNormalizerAliases() {
        IllegalArgumentException trailingDigits = assertThrows(IllegalArgumentException) {
            ReconciliationServices.parseIdSpec('id|TRAILING_DIGITS', false)
        }
        assertTrue(trailingDigits.message.contains("Supported value: SHOPIFY_GID_TAIL"))

        IllegalArgumentException shopifyGidAlias = assertThrows(IllegalArgumentException) {
            ReconciliationServices.parseIdSpec('id|SHOPIFY_GID', false)
        }
        assertTrue(shopifyGidAlias.message.contains("Unsupported ID normalizer 'SHOPIFY_GID'"))
    }

    @Test
    void convertsWizardStyleJsonFieldPathToRootedJsonPath() {
        Map spec = ReconciliationServices.parseIdSpec('data.orders.edges.[0].node.id', false)

        assertEquals('$.data.orders.edges[*].node.id', spec.idExpr)
    }

    @Test
    void rejectsInvalidJsonPathSyntaxAfterCoercion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            ReconciliationServices.parseIdSpec('data.orders[0', false)
        }

        assertTrue(error.message.startsWith("Invalid JSONPath '\$.data.orders[0':"))
    }

    @Test
    void keepsCsvIdExpressionBehaviorSeparateFromJsonPathHandling() {
        Map spec = ReconciliationServices.parseIdSpec('$.records[*].orderId', true)

        assertEquals('orderId', spec.idExpr)
    }
}
