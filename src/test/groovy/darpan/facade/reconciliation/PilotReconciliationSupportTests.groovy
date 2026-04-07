package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class PilotReconciliationSupportTests {

    @Test
    void renderDifferencesCsvEscapesStructuredFields() {
        Map<String, Object> diffDocument = [
                differences: [
                        [
                                type     : "missing_in_SHOPIFY",
                                id       : "123",
                                presentIn: "OMS",
                                missingIn: "SHOPIFY",
                                note     : "Present in OMS, missing in SHOPIFY",
                                data     : "{\"order_id\":\"123\",\"notes\":\"comma,value\"}"
                        ],
                        [
                                type     : "missing_in_OMS",
                                id       : "456",
                                presentIn: "SHOPIFY",
                                missingIn: "OMS",
                                note     : null,
                                data     : [shopifyOrderId: "456", tags: ["vip", "priority"]]
                        ]
                ]
        ]

        String csv = PilotReconciliationSupport.renderDifferencesCsv(diffDocument)
        List<String> lines = csv.readLines()

        assertEquals("type,id,presentIn,missingIn,note,data", lines.first())
        assertEquals(3, lines.size())
        assertTrue(lines[1].contains("\"missing_in_SHOPIFY\""))
        assertTrue(lines[1].contains("\"{\"\"order_id\"\":\"\"123\"\",\"\"notes\"\":\"\"comma,value\"\"}\""))
        assertTrue(lines[2].contains("\"missing_in_OMS\""))
        assertTrue(lines[2].contains("\"{"))
        assertTrue(lines[2].contains("shopifyOrderId"))
        assertTrue(lines[2].contains("456"))
    }

    @Test
    void buildGeneratedOutputDescriptorExposesSummaryAndFormats() {
        Map<String, Object> diffDocument = [
                metadata: [
                        reconciliationMappingId  : "OrderIdMap",
                        reconciliationMappingName: "Order ID",
                        reconciliation           : "CSV",
                        file1Label               : "OMS",
                        file2Label               : "SHOPIFY"
                ],
                summary : [
                        totalDifferences: 4,
                        onlyInFile1Count: 1,
                        onlyInFile2Count: 3
                ]
        ]

        Map<String, Object> row = PilotReconciliationSupport.buildGeneratedOutputDescriptor(
                "order-id-diff-20260330.json",
                diffDocument,
                2048L,
                Timestamp.valueOf("2026-03-30 15:00:00")
        )

        assertEquals("order-id-diff-20260330.json", row.fileName)
        assertEquals("json", row.sourceFormat)
        assertIterableEquals(["json", "csv"], row.availableFormats as List<String>)
        assertEquals("OrderIdMap", row.reconciliationMappingId)
        assertEquals("Order ID", row.mappingName)
        assertEquals("CSV", row.reconciliationType)
        assertEquals("OMS", row.file1Label)
        assertEquals("SHOPIFY", row.file2Label)
        assertEquals(4L, row.totalDifferences)
        assertEquals(1L, row.onlyInFile1Count)
        assertEquals(3L, row.onlyInFile2Count)
        assertEquals(2048L, row.sizeBytes)
    }

    @Test
    void matchesGeneratedOutputDescriptorHonorsMappingIdFilter() {
        Map<String, Object> descriptor = [
                fileName               : "gorjana-order-diff.json",
                reconciliationMappingId: "GorjanaOrderReconciliation-260407095913",
                mappingName            : "Gorjana Order Reconciliation",
                file1Label             : "OMS",
                file2Label             : "SHOPIFY",
                reconciliationType     : "JSON"
        ]

        assertTrue(PilotReconciliationSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation-260407095913",
                null
        ))
        assertFalse(PilotReconciliationSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation",
                null
        ))
        assertTrue(PilotReconciliationSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation-260407095913",
                "shopify"
        ))
    }

    @Test
    void matchesGeneratedOutputDescriptorRejectsUnscopedLegacyOutputWhenMappingIdFilterProvided() {
        Map<String, Object> descriptor = [
                fileName           : "legacy-output.csv",
                reconciliationType : "CSV",
                mappingName        : "Gorjana Order Reconciliation",
                reconciliationMappingId: null
        ]

        assertFalse(PilotReconciliationSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation-260407095913",
                null
        ))
        assertTrue(PilotReconciliationSupport.matchesGeneratedOutputDescriptor(descriptor, null, "legacy"))
    }
}
