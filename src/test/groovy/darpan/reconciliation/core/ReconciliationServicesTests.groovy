package darpan.reconciliation.core

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

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
        assertTrue(trailingDigits.message.contains("SHOPIFY_GID_TAIL"))

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

    // Audit 2026-06-11 #3 — driver-heap rule-diff accumulation is bounded.
    @Test
    void appendBoundedRuleDiffsAddsAllWhenUnderCap() {
        List<Map<String, Object>> acc = []
        long dropped = ReconciliationServices.appendBoundedRuleDiffs(acc, [[a: 1], [a: 2]], 10)
        assertEquals(0L, dropped)
        assertEquals(2, acc.size())
    }

    @Test
    void appendBoundedRuleDiffsDropsOverflowWithinABatch() {
        List<Map<String, Object>> acc = [[a: 0]]
        long dropped = ReconciliationServices.appendBoundedRuleDiffs(acc, [[a: 1], [a: 2], [a: 3]], 2)
        // cap=2, acc already holds 1, so only 1 more fits and 2 are dropped.
        assertEquals(2L, dropped)
        assertEquals(2, acc.size())
    }

    @Test
    void appendBoundedRuleDiffsDropsEntireBatchOnceCapReached() {
        List<Map<String, Object>> acc = [[a: 0], [a: 1]]
        long dropped = ReconciliationServices.appendBoundedRuleDiffs(acc, [[a: 2], [a: 3]], 2)
        assertEquals(2L, dropped)
        assertEquals(2, acc.size())
    }

    // Audit 2026-06-11 #17 — opt-in case-insensitive compare_id normalizer.
    @Test
    void resolveIdNormalizerAcceptsCaseFoldAliases() {
        assertEquals("CASE_FOLD", ReconciliationServices.resolveIdNormalizer("CASE_FOLD"))
        assertEquals("CASE_FOLD", ReconciliationServices.resolveIdNormalizer("case-insensitive"))
        assertEquals("CASE_FOLD", ReconciliationServices.resolveIdNormalizer("lower"))
        assertEquals("SHOPIFY_GID_TAIL", ReconciliationServices.resolveIdNormalizer("SHOPIFY_GID_TAIL"))
        assertNull(ReconciliationServices.resolveIdNormalizer(null))
    }

    @Test
    void resolveIdNormalizerStillRejectsUnknownValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            ReconciliationServices.resolveIdNormalizer("UPPER")
        }
        assertTrue(error.message.contains("Unsupported ID normalizer 'UPPER'"))
    }

    @Test
    void parseIdSpecAcceptsCaseFoldNormalizer() {
        Map spec = ReconciliationServices.parseIdSpec('order_id|CASE_FOLD', true)
        assertEquals('order_id', spec.idExpr)
        assertEquals('CASE_FOLD', spec.idNormalizer)
    }

    // Run-output metadata timestamps must travel as absolute instants. A bare
    // java.sql.Timestamp.toString() emits a zone-less wall clock ("2026-08-26 03:00:57.579"),
    // which darpan-ui re-parses in the BROWSER's zone and then renders in the tenant's
    // display zone -- two stacked conversions that moved a 2:00 AM run to 4:30 PM the
    // previous day for an IST viewer on an America/Chicago tenant.
    @Test
    void formatsMetadataTimestampAsParseableInstant() {
        Timestamp runInstant = Timestamp.from(Instant.parse("2026-08-26T07:00:57.579Z"))

        String formatted = ReconciliationServices.formatMetadataTimestamp(runInstant)

        assertEquals(runInstant.toInstant(), OffsetDateTime.parse(formatted).toInstant())
    }

    @Test
    void metadataTimestampResolvesToSameInstantRegardlessOfDefaultZone() {
        Timestamp runInstant = Timestamp.from(Instant.parse("2026-08-26T07:00:57.579Z"))
        TimeZone originalZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            String fromNewYork = ReconciliationServices.formatMetadataTimestamp(runInstant)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            String fromKolkata = ReconciliationServices.formatMetadataTimestamp(runInstant)

            assertEquals(OffsetDateTime.parse(fromNewYork).toInstant(),
                    OffsetDateTime.parse(fromKolkata).toInstant())
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    void formatsNullMetadataTimestampAsNull() {
        assertNull(ReconciliationServices.formatMetadataTimestamp(null))
    }
}
