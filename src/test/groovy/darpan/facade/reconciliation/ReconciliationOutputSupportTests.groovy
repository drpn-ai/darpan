package darpan.facade.reconciliation

import darpan.facade.common.TenantAccessSupport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

class ReconciliationOutputSupportTests {

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

        String csv = ReconciliationOutputSupport.renderDifferencesCsv(diffDocument)
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
    void renderDifferencesCsvSupportsRuleSetDiffDocuments() {
        Map<String, Object> diffDocument = [
                differences: [
                        [
                                diffType  : "FIELD_MISMATCH",
                                primaryId : "P200",
                                field     : "sku",
                                file1Value: "SKU-B",
                                file2Value: "SKU-B-ALT",
                                ruleId    : "PRODUCT_SKU_MISMATCH",
                                severity  : "WARN",
                                message   : "SKU mismatch",
                                data      : [productId: "P200"]
                        ],
                        [
                                diffType : "MISSING_IN_FILE_1",
                                primaryId: "P500",
                                presentIn: "OMS",
                                missingIn: "SHOPIFY",
                                message  : "Present in OMS, missing in SHOPIFY",
                                data     : "{\"productId\":\"P500\"}"
                        ]
                ]
        ]

        String csv = ReconciliationOutputSupport.renderDifferencesCsv(diffDocument)
        List<String> lines = csv.readLines()

        assertEquals("diffType,primaryId,field,file1Value,file2Value,presentIn,missingIn,ruleId,severity,message,data", lines.first())
        assertEquals(3, lines.size())
        assertTrue(lines[1].contains("\"FIELD_MISMATCH\""))
        assertTrue(lines[1].contains("\"P200\""))
        assertTrue(lines[1].contains("\"sku\""))
        assertTrue(lines[1].contains("\"SKU-B-ALT\""))
        assertTrue(lines[2].contains("\"MISSING_IN_FILE_1\""))
        assertTrue(lines[2].contains("\"P500\""))
        assertTrue(lines[2].contains("\"Present in OMS, missing in SHOPIFY\""))
    }

    @Test
    void buildGeneratedOutputDescriptorExposesSummaryAndFormats() {
        Map<String, Object> diffDocument = [
                metadata: [
                        companyUserGroupId       : "KREWE",
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

        Map<String, Object> row = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
                "order-id-diff-20260330.json",
                diffDocument,
                2048L,
                Timestamp.valueOf("2026-03-30 15:00:00")
        )

        assertEquals("order-id-diff-20260330.json", row.fileName)
        assertEquals("json", row.sourceFormat)
        assertIterableEquals(["json", "csv"], row.availableFormats as List<String>)
        assertEquals("KREWE", row.companyUserGroupId)
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
    void buildRunResultDescriptorExposesRunningStatusWithoutResultFile() {
        Map<String, Object> row = ReconciliationOutputSupport.buildRunResultDescriptor(null, [
                reconciliationRunResultId: "RUN_RESULT_1",
                savedRunId               : "RS_PRODUCTION_ORDERS",
                savedRunType             : "ruleset",
                ruleSetId                : "RS_PRODUCTION_ORDERS",
                companyUserGroupId       : "TENANT_1",
                statusEnumId             : "AUT_STAT_RUNNING",
                createdDate              : Timestamp.valueOf("2026-05-05 10:00:00"),
                startedDate              : Timestamp.valueOf("2026-05-05 10:00:00"),
        ], null, [:])

        assertEquals("", row.fileName)
        assertEquals("RUN_RESULT_1", row.reconciliationRunResultId)
        assertEquals("RS_PRODUCTION_ORDERS", row.savedRunId)
        assertEquals("ruleset", row.savedRunType)
        assertEquals("RS_PRODUCTION_ORDERS", row.ruleSetId)
        assertEquals("TENANT_1", row.companyUserGroupId)
        assertEquals("AUT_STAT_RUNNING", row.statusEnumId)
        assertEquals("Running", row.statusLabel)
        assertFalse(row.resultAvailable as Boolean)
        assertEquals(Timestamp.valueOf("2026-05-05 10:00:00"), row.createdDate)
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

        assertTrue(ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation-260407095913",
                null
        ))
        assertFalse(ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation",
                null
        ))
        assertTrue(ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(
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

        assertFalse(ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(
                descriptor,
                "GorjanaOrderReconciliation-260407095913",
                null
        ))
        assertTrue(ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(descriptor, null, "legacy"))
    }

    @Test
    void generatedOutputPathsAllowSafeDataManagerRelativePathsOnly() {
        assertTrue(ReconciliationOutputSupport.isSafeOutputPath(
                "reconciliation-runs/OrderId/20260428-120000000/OrderId_result.json"
        ))
        assertFalse(ReconciliationOutputSupport.isSafeOutputPath("../OrderId_result.json"))
        assertFalse(ReconciliationOutputSupport.isSafeOutputPath("/tmp/OrderId_result.json"))
        assertFalse(ReconciliationOutputSupport.isSafeOutputPath("reconciliation-runs/OrderId/20260428-120000000/OrderId_file1.csv"))
        assertFalse(ReconciliationOutputSupport.isSafeOutputPath("reconciliation-runs/RS_CSV_TENANT_SHARED_RESULT/20260428-120000000/RS_CSV_TENANT_SHARED_RESULT_file1.csv"))
    }

    @Test
    void safeReadableArtifactPathAllowsResultFilesWithoutManifestLookup() {
        def ecWithoutManifest = [entity: [find: { String entityName -> null }]]

        assertTrue(ReconciliationOutputSupport.isSafeReadableArtifactPath(
                ecWithoutManifest,
                "reconciliation-runs/RS_API_ORDER_SYNC/20260505-195645688/RS_API_ORDER_SYNC_result.json"
        ))
        assertFalse(ReconciliationOutputSupport.isSafeReadableArtifactPath(
                ecWithoutManifest,
                "reconciliation-runs/RS_API_ORDER_SYNC/20260505-195645688/file1-api/shopify-orders.json"
        ))
    }

    @Test
    void apiSourceDetailsModeAcceptsDateRangeMaps() {
        Map<String, Object> dateRange = [
                start: Timestamp.valueOf("2026-03-31 17:00:00"),
                end  : Timestamp.valueOf("2026-04-30 17:00:00")
        ]

        assertTrue(ReconciliationOutputSupport.isApiSourceDetailsMode([:], dateRange))
        assertTrue(ReconciliationOutputSupport.isApiSourceDetailsMode([sourceMode: "api"], null))
        assertFalse(ReconciliationOutputSupport.isApiSourceDetailsMode([sourceMode: "files"], null))
    }

    @Test
    void sourceDetailsResolveStoredRuntimeDataManagerPaths() {
        String resultPath = "reconciliation-runs/RS_API_ORDER_SYNC/20260505-195645688/RS_API_ORDER_SYNC_result.json"
        String hotwaxPath = "reconciliation-runs/RS_API_ORDER_SYNC/20260505-195645688/HotWax-orders-api.json"
        String shopifyPath = "reconciliation-runs/RS_API_ORDER_SYNC/20260505-195645688/SHOPIFY-orders-api.json"
        Map<String, Object> runResult = [
                reconciliationRunResultId: "RUN_RESULT_API",
                resultDataManagerPath    : "runtime://datamanager/${resultPath}",
                file1DataManagerPath     : "runtime://datamanager/${hotwaxPath}",
                file2DataManagerPath     : "runtime://datamanager/${shopifyPath}",
                file1Name                : "HotWax-orders-api.json",
                file2Name                : "SHOPIFY-orders-api.json",
        ]
        def ec = new Expando(
                resource: new FakeResource(),
                entity  : new FakeEntity([
                        "darpan.reconciliation.ReconciliationRunResult"        : [runResult],
                        "darpan.reconciliation.ReconciliationAutomationExecution": [],
                ])
        )

        Map<String, Object> sourceDetails = ReconciliationOutputSupport.buildGeneratedOutputSourceDetails(ec, resultPath, [
                metadata: [
                        sourceMode: "api",
                        file1Label: "HotWax",
                        file2Label: "SHOPIFY",
                        windowStart: "2026-04-01T00:00:00Z",
                        windowEnd  : "2026-05-01T00:00:00Z",
                ],
        ])

        assertEquals(runResult, ReconciliationOutputSupport.resolveRunResultForArtifactPath(ec, resultPath))
        assertNotNull(sourceDetails)
        assertEquals("API", sourceDetails.mode)
        assertEquals([start: "2026-04-01T00:00:00Z", end: "2026-05-01T00:00:00Z"], sourceDetails.dateRange)
        assertEquals(2, sourceDetails.files.size())
        assertEquals("HotWax", sourceDetails.files[0].label)
        assertEquals("HotWax-orders-api.json", sourceDetails.files[0].fileName)
        assertEquals(hotwaxPath, sourceDetails.files[0].filePath)
        assertTrue(sourceDetails.files[0].canDownload as Boolean)
        assertEquals("SHOPIFY-orders-api.json", sourceDetails.files[1].fileName)
        assertEquals(shopifyPath, sourceDetails.files[1].filePath)
        assertEquals("HotWax-orders-api.json", ReconciliationOutputSupport.sourceArtifactDisplayName(ec, hotwaxPath))
    }

    @Test
    void sourceDetailsFallbackReadsSiblingApiArtifactsWhenRunResultIsMissing(@TempDir File dataManagerRoot) {
        String resultPath = "reconciliation-runs/RS_API_ORDER_SYNC/20260505-201713752/RS_API_ORDER_SYNC_result.json"
        File runFolder = new File(dataManagerRoot, "reconciliation-runs/RS_API_ORDER_SYNC/20260505-201713752")
        File file1Folder = new File(runFolder, "file1-api")
        File file2Folder = new File(runFolder, "file2-api")
        assertTrue(file1Folder.mkdirs())
        assertTrue(file2Folder.mkdirs())
        new File(file1Folder, "RS_API_ORDER_SYNC_file1.jsonl").text = "{}\n"
        new File(file1Folder, "RS_API_ORDER_SYNC_file1.json").text =
                '{"metadata":{"windowStartUtc":"2026-04-01T00:00:00Z","windowEndUtc":"2026-05-01T00:00:00Z"},"records":[]}'
        new File(file2Folder, "oms-orders-1775026800000-1775113200000.json").text =
                '{"metadata":{"windowStartEpochMillis":1775026800000,"windowEndEpochMillis":1775113200000},"records":[]}'
        def ec = new Expando(
                resource: new FakeResource(dataManagerRoot),
                entity  : new FakeEntity([
                        "darpan.reconciliation.ReconciliationRunResult": [],
                ])
        )

        Map<String, Object> sourceDetails = ReconciliationOutputSupport.buildGeneratedOutputSourceDetails(ec, resultPath, [
                metadata: [
                        file1Label: "SHOPIFY",
                        file2Label: "HotWax",
                ],
        ])

        assertNotNull(sourceDetails)
        assertEquals("API", sourceDetails.mode)
        assertEquals([start: "2026-04-01T00:00:00Z", end: "2026-05-01T00:00:00Z"], sourceDetails.dateRange)
        assertEquals(2, sourceDetails.files.size())
        assertEquals("SHOPIFY", sourceDetails.files[0].label)
        assertEquals("RS_API_ORDER_SYNC_file1.json", sourceDetails.files[0].fileName)
        assertEquals(
                "reconciliation-runs/RS_API_ORDER_SYNC/20260505-201713752/file1-api/RS_API_ORDER_SYNC_file1.json",
                sourceDetails.files[0].filePath
        )
        assertEquals("HotWax", sourceDetails.files[1].label)
        assertEquals("oms-orders-1775026800000-1775113200000.json", sourceDetails.files[1].fileName)
        assertTrue(sourceDetails.files[1].canDownload as Boolean)
    }

    @Test
    void sourceArtifactDownloadFallbackAllowsExposedSiblingFileWithoutRunResult(@TempDir File dataManagerRoot) {
        String runFolderPath = "reconciliation-runs/RS_API_ORDER_SYNC/20260505-201713752"
        String resultPath = "${runFolderPath}/RS_API_ORDER_SYNC_result.json"
        String sourcePath = "${runFolderPath}/file1-api/RS_API_ORDER_SYNC_file1.json"
        File runFolder = new File(dataManagerRoot, runFolderPath)
        File file1Folder = new File(runFolder, "file1-api")
        assertTrue(file1Folder.mkdirs())
        new File(runFolder, "RS_API_ORDER_SYNC_result.json").text =
                '{"metadata":{"companyUserGroupId":"GORJANA","sourceMode":"api","file1Label":"SHOPIFY","file2Label":"HotWax"},"differences":[]}'
        File sourceFile = new File(file1Folder, "RS_API_ORDER_SYNC_file1.json")
        sourceFile.text = '{"records":[]}'
        new File(runFolder, "file2-api").mkdirs()
        new File(runFolder, "file2-api/RS_API_ORDER_SYNC_file2.json").text = '{"records":[]}'
        def ec = new Expando(
                resource: new FakeResource(dataManagerRoot),
                user    : new Expando(
                        userId       : "editor",
                        nowTimestamp : Timestamp.valueOf("2026-05-05 12:00:00"),
                        getPreference: { String key ->
                            key == TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY ? "GORJANA" : null
                        }
                ),
                entity  : new FakeEntity([
                        "darpan.reconciliation.ReconciliationRunResult": [],
                        "moqui.security.UserGroupAndMember"            : [[
                                userId         : "editor",
                                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
                                userGroupId    : "GORJANA",
                        ]],
                        "moqui.security.UserGroupMember"               : [],
                ])
        )

        assertNotNull(ReconciliationOutputSupport.buildGeneratedOutputSourceDetails(ec, resultPath, [
                metadata: [sourceMode: "api", file1Label: "SHOPIFY", file2Label: "HotWax"],
        ])?.files?.find { Map<String, Object> file -> file.filePath == sourcePath })
        assertTrue(ReconciliationOutputSupport.isSafeReadableArtifactPath(ec, "runtime://datamanager/${sourcePath}"))
        assertEquals(
                sourceFile.canonicalPath,
                ReconciliationOutputSupport.resolveGeneratedOutputArtifactFile(ec, "runtime://datamanager/${sourcePath}").canonicalPath
        )
        assertEquals("GORJANA", ReconciliationOutputSupport.resolveGeneratedOutputTenantUserGroupId(ec, sourceFile, sourcePath))
        assertTrue(ReconciliationOutputSupport.canAccessGeneratedOutputFile(ec, sourceFile, sourcePath))
        assertTrue(ReconciliationOutputSupport.canAccessGeneratedOutputFile(ec, sourceFile, "runtime://datamanager/${sourcePath}"))

        def outsideTenantEc = new Expando(
                resource: new FakeResource(dataManagerRoot),
                user    : new Expando(
                        userId       : "editor",
                        nowTimestamp : Timestamp.valueOf("2026-05-05 12:00:00"),
                        getPreference: { String key ->
                            key == TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY ? "OTHER" : null
                        }
                ),
                entity  : new FakeEntity([
                        "darpan.reconciliation.ReconciliationRunResult": [],
                        "moqui.security.UserGroupAndMember"            : [[
                                userId         : "editor",
                                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
                                userGroupId    : "OTHER",
                        ]],
                        "moqui.security.UserGroupMember"               : [],
                ])
        )
        assertFalse(ReconciliationOutputSupport.canAccessGeneratedOutputFile(
                outsideTenantEc,
                sourceFile,
                "runtime://datamanager/${sourcePath}"
        ))
    }

    @Test
    void generatedOutputAccessFailsClosedWithoutActiveTenantForDataManagerPaths() {
        def anonymousEc = [user: [userId: null]]

        assertFalse(ReconciliationOutputSupport.canAccessGeneratedOutputFile(
                anonymousEc,
                new File("OrderId_result.json"),
                "reconciliation-runs/OrderId/20260428-120000000/OrderId_result.json"
        ))
    }

    static class FakeResource {
        Map<String, Object> properties = [:]
        File dataManagerRoot

        FakeResource() {
        }

        FakeResource(File dataManagerRoot) {
            this.dataManagerRoot = dataManagerRoot
        }

        Object getLocationReference(String location) {
            if (location == "runtime://datamanager" && dataManagerRoot != null) {
                return new FakeLocationReference(dataManagerRoot)
            }
            return null
        }
    }

    static class FakeLocationReference {
        File file

        FakeLocationReference(File file) {
            this.file = file
        }

        File getFile() {
            return file
        }
    }

    static class FakeEntity {
        Map<String, List<Map<String, Object>>> rowsByEntity

        FakeEntity(Map<String, List<Map<String, Object>>> rowsByEntity) {
            this.rowsByEntity = rowsByEntity
        }

        FakeFind find(String entityName) {
            return new FakeFind(rowsByEntity[entityName] ?: [])
        }
    }

    static class FakeFind {
        List<Map<String, Object>> rows
        List<Map<String, Object>> conditions = []

        FakeFind(List<Map<String, Object>> rows) {
            this.rows = rows
        }

        FakeFind condition(String fieldName, Object value) {
            conditions.add([fieldName: fieldName, value: value])
            return this
        }

        FakeFind condition(String fieldName, String operator, Object value) {
            conditions.add([fieldName: fieldName, operator: operator, value: value])
            return this
        }

        FakeFind disableAuthz() {
            return this
        }

        FakeFind useCache(boolean ignored) {
            return this
        }

        Object one() {
            return rows.find { Map<String, Object> row ->
                conditions.every { Map<String, Object> condition ->
                    condition.operator == "in" ?
                            ((Collection) condition.value).any { candidate -> candidate?.toString() == row[condition.fieldName as String]?.toString() } :
                            row[condition.fieldName as String] == condition.value
                }
            }
        }

        List<Map<String, Object>> list() {
            return rows.findAll { Map<String, Object> row ->
                conditions.every { Map<String, Object> condition ->
                    condition.operator == "in" ?
                            ((Collection) condition.value).any { candidate -> candidate?.toString() == row[condition.fieldName as String]?.toString() } :
                            row[condition.fieldName as String] == condition.value
                }
            }
        }
    }
}
