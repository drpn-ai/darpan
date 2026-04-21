package darpan.reconciliation.automation

import darpan.facade.common.PilotAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpAutomationServiceSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    private ExecutionContext ec
    private Path backendRoot
    private FakeSftpEnvironment sftpEnvironment

    @BeforeAll
    void setup() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "sftp-automation-smoke")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        SftpAutomationSupport.resetClientFactory()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void prepare() {
        ec.message.clearErrors()
        sftpEnvironment = new FakeSftpEnvironment()
        SftpAutomationSupport.setClientFactory { String host, String user, Integer port ->
            new FakeSftpClient(host, user, port, sftpEnvironment)
        }
    }

    @AfterEach
    void resetFactory() {
        SftpAutomationSupport.resetClientFactory()
    }

    @Test
    void sftpAutomationRoutesRuleSetCompareScopeAndUploadsUnifiedDiff() {
        sftpEnvironment.putFile("shopify.test", 22, "/incoming/products-1.json",
                readFixtureText("data/test/test-products-1.json"), 2000L)
        sftpEnvironment.putFile("oms.test", 22, "/incoming/products-2.json",
                readFixtureText("data/test/test-products-2.json"), 1000L)

        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile")
                .parameters([
                        ruleSetId         : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                        file1SftpServerId : "SHOPIFY_TEST_SFTP",
                        file2SftpServerId : "OMS_TEST_SFTP",
                        file1RemotePath   : "/incoming",
                        file2RemotePath   : "/incoming",
                        stageLocation     : "runtime://tmp/reconciliation/automation/input/smoke-ruleset",
                        outputLocation    : "runtime://tmp/reconciliation/automation/output/smoke-ruleset",
                        sparkMaster       : "local[1]",
                        sparkAppName      : "SftpAutomationServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(result.dataAvailable as Boolean)
        assertEquals("JSON", result.reconciliationType)
        assertEquals(4L, result.differenceCount)
        assertEquals(1L, result.onlyInFile1Count)
        assertEquals(1L, result.onlyInFile2Count)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertNotNull(result.file1StagedLocation)
        assertNotNull(result.file2StagedLocation)
        assertNotNull(result.diffLocation)
        assertNotNull(result.diffFileName)
        assertTrue(new File(result.file1StagedLocation as String).exists())
        assertTrue(new File(result.file2StagedLocation as String).exists())

        Map<String, Object> diffDocument = parseOutputFile(result.diffLocation as String)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", diffDocument.metadata.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", diffDocument.metadata.compareScopeId)
        assertEquals("PRODUCT", diffDocument.metadata.objectType)
        assertEquals("JSON", diffDocument.metadata.reconciliation)
        assertEquals(4, diffDocument.summary.totalDifferences)
        assertEquals(1, diffDocument.summary.onlyInFile1Count)
        assertEquals(1, diffDocument.summary.onlyInFile2Count)
        assertEquals(2, diffDocument.summary.missingObjectDifferenceCount)
        assertEquals(2, diffDocument.summary.ruleDifferenceCount)

        List<Map<String, Object>> differences = ((List) diffDocument.differences).collect { it as Map<String, Object> }
        assertTrue(differences.any { Map<String, Object> row -> row.diffType == "MISSING_IN_FILE_2" && row.primaryId == "P300" })
        assertTrue(differences.any { Map<String, Object> row -> row.diffType == "MISSING_IN_FILE_1" && row.primaryId == "P500" })
        assertTrue(differences.any { Map<String, Object> row -> row.diffType == "FIELD_MISMATCH" && row.primaryId == "P200" && row.field == "sku" })
        assertTrue(differences.any { Map<String, Object> row -> row.diffType == "FIELD_MISMATCH" && row.primaryId == "P400" && row.field == "price" })

        assertTrue(sftpEnvironment.hasFile("shopify.test", 22, "/incoming/archive/products-1.json"))
        assertTrue(sftpEnvironment.hasFile("oms.test", 22, "/incoming/archive/products-2.json"))
        assertTrue(sftpEnvironment.hasFile("shopify.test", 22, "/incoming/${result.diffFileName}"))
        String uploadedDiff = sftpEnvironment.readFile("shopify.test", 22, "/incoming/${result.diffFileName}")
        assertTrue(uploadedDiff.contains("\"ruleSetId\":\"DARPAN_TEST_PRODUCT_COMPARE_RS\""))
        assertTrue(uploadedDiff.contains("\"compareScopeId\":\"DARPAN_TEST_PRODUCT_JSON_SCOPE\""))
    }

    @Test
    void sftpAutomationRetainsMappingBridgeForExistingJobs() {
        List<Map<String, Object>> companyMemberships = ec.entity.find("moqui.security.UserGroupAndMember")
                .condition("userId", "TEST_CUSTOMER_USER")
                .disableAuthz()
                .list()
                .collect { it as Map<String, Object> }
        assertEquals("TEST_CUSTOMER_USER", ec.user.userId)
        assertEquals("KREWE", ec.user.getPreference(PilotAccessSupport.ACTIVE_COMPANY_PREFERENCE_KEY))
        assertTrue(!companyMemberships.isEmpty(), companyMemberships.toString())
        assertTrue(companyMemberships.any { Map<String, Object> row ->
            row.userGroupId == "KREWE" && row.groupTypeEnumId == PilotAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
        }, companyMemberships.toString())
        assertEquals("KREWE", PilotAccessSupport.currentActiveCompanyUserGroupId(ec))
        assertEquals("KREWE", ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("jsonSchemaId", "TestOmsOrderSchema")
                .disableAuthz()
                .one()
                .companyUserGroupId)
        assertEquals("KREWE", ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("jsonSchemaId", "TestShopifyOrderSchema")
                .disableAuthz()
                .one()
                .companyUserGroupId)

        sftpEnvironment.putFile("shopify.test", 22, "/incoming/orders-1.csv",
                "order_id\nA100\nA200\nA300\n", 2000L)
        sftpEnvironment.putFile("oms.test", 22, "/incoming/orders-2.csv",
                "order_id\nA200\nA300\nA400\n", 1000L)

        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile")
                .parameters([
                        reconciliationMappingId: "OrderIdSchemaMap",
                        file1SystemEnumId      : "SHOPIFY",
                        file2SystemEnumId      : "OMS",
                        file1SftpServerId      : "SHOPIFY_TEST_SFTP",
                        file2SftpServerId      : "OMS_TEST_SFTP",
                        file1RemotePath        : "/incoming",
                        file2RemotePath        : "/incoming",
                        stageLocation          : "runtime://tmp/reconciliation/automation/input/smoke-mapping",
                        outputLocation         : "runtime://tmp/reconciliation/automation/output/smoke-mapping",
                        sparkMaster            : "local[1]",
                        sparkAppName           : "SftpAutomationServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(result.dataAvailable as Boolean)
        assertEquals("CSV", result.reconciliationType)
        assertEquals(2L, result.differenceCount)
        assertEquals(1L, result.onlyInFile1Count)
        assertEquals(1L, result.onlyInFile2Count)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertNotNull(result.diffLocation)
        assertNotNull(result.diffFileName)

        Map<String, Object> diffDocument = parseOutputFile(result.diffLocation as String)
        assertEquals("OrderIdSchemaMap", diffDocument.metadata.reconciliationMappingId)
        assertEquals("CSV", diffDocument.metadata.reconciliation)
        assertEquals(2, diffDocument.summary.totalDifferences)
        assertEquals(1, diffDocument.summary.onlyInFile1Count)
        assertEquals(1, diffDocument.summary.onlyInFile2Count)

        List<Map<String, Object>> differences = ((List) diffDocument.differences).collect { it as Map<String, Object> }
        assertEquals(2, differences.size())
        assertTrue(differences.any { Map<String, Object> row -> row.id == "A100" })
        assertTrue(differences.any { Map<String, Object> row -> row.id == "A400" })

        assertTrue(sftpEnvironment.hasFile("shopify.test", 22, "/incoming/archive/orders-1.csv"))
        assertTrue(sftpEnvironment.hasFile("oms.test", 22, "/incoming/archive/orders-2.csv"))
        assertTrue(sftpEnvironment.hasFile("shopify.test", 22, "/incoming/${result.diffFileName}"))
    }

    private String readFixtureText(String relativePath) {
        return Files.readString(backendRoot.resolve("runtime/component/darpan").resolve(relativePath))
    }

    private static Map<String, Object> parseOutputFile(String diffLocation) {
        File outputFile = new File(diffLocation)
        assertTrue(outputFile.exists())
        return (Map<String, Object>) JSON_SLURPER.parseText(outputFile.getText("UTF-8"))
    }

    private static final class FakeSftpEnvironment {
        private final Map<String, Map<String, FakeSftpFile>> filesByServer = [:].withDefault { [:] }

        void putFile(String host, int port, String path, String text, long lastModified) {
            filesByServer[serverKey(host, port)][normalizePath(path)] = new FakeSftpFile(
                    bytes: text.getBytes(StandardCharsets.UTF_8),
                    lastModified: lastModified
            )
        }

        boolean hasFile(String host, int port, String path) {
            return filesByServer[serverKey(host, port)].containsKey(normalizePath(path))
        }

        String readFile(String host, int port, String path) {
            FakeSftpFile file = filesByServer[serverKey(host, port)][normalizePath(path)]
            return file == null ? null : new String(file.bytes, StandardCharsets.UTF_8)
        }

        List<Map<String, Object>> list(String host, int port, String basePath) {
            String normalizedBasePath = normalizePath(basePath)
            String prefix = normalizedBasePath == "/" ? "/" : normalizedBasePath + "/"
            Map<String, FakeSftpFile> serverFiles = filesByServer[serverKey(host, port)]
            Map<String, Map<String, Object>> entriesByPath = [:]

            serverFiles.each { String path, FakeSftpFile file ->
                if (!path.startsWith(prefix)) return
                String remainder = path.substring(prefix.length())
                if (!remainder) return
                String firstSegment = remainder.tokenize("/")[0]
                String childPath = normalizedBasePath == "/" ? "/${firstSegment}" : "${normalizedBasePath}/${firstSegment}"
                if (remainder.contains("/")) {
                    entriesByPath[childPath] = [name: firstSegment, path: childPath, isFile: false, isDir: true, isDirectory: true, lastModified: 0L]
                } else {
                    entriesByPath[childPath] = [name: firstSegment, path: childPath, isFile: true, isDir: false, isDirectory: false, lastModified: file.lastModified]
                }
            }

            return entriesByPath.values() as List<Map<String, Object>>
        }

        InputStream openStream(String host, int port, String path) {
            FakeSftpFile file = filesByServer[serverKey(host, port)][normalizePath(path)]
            if (file == null) throw new FileNotFoundException(path)
            return new ByteArrayInputStream(file.bytes)
        }

        void rename(String host, int port, String fromPath, String toPath) {
            Map<String, FakeSftpFile> serverFiles = filesByServer[serverKey(host, port)]
            FakeSftpFile file = serverFiles.remove(normalizePath(fromPath))
            if (file == null) throw new FileNotFoundException(fromPath)
            serverFiles[normalizePath(toPath)] = file
        }

        void put(String host, int port, String remoteDir, String fileName, InputStream inputStream) {
            byte[] bytes = inputStream.bytes
            String normalizedDir = normalizePath(remoteDir)
            String targetPath = normalizedDir == "/" ? "/${fileName}" : "${normalizedDir}/${fileName}"
            filesByServer[serverKey(host, port)][targetPath] = new FakeSftpFile(
                    bytes: bytes,
                    lastModified: System.currentTimeMillis()
            )
        }

        void rm(String host, int port, String path) {
            filesByServer[serverKey(host, port)].remove(normalizePath(path))
        }

        void mkdirs(String host, int port, String path) {
            filesByServer[serverKey(host, port)]
        }

        private static String serverKey(String host, int port) {
            return "${host}:${port}"
        }

        private static String normalizePath(String path) {
            String cleaned = path?.trim()
            if (!cleaned) return "/"
            cleaned = cleaned.replaceAll(/\\+/, "/")
            cleaned = cleaned.replaceAll(/\/+$/, "")
            if (!cleaned) return "/"
            if (!cleaned.startsWith("/")) cleaned = "/" + cleaned
            return cleaned
        }
    }

    private static final class FakeSftpClient {
        private final String host
        private final int port
        private final FakeSftpEnvironment environment

        FakeSftpClient(String host, String user, int port, FakeSftpEnvironment environment) {
            this.host = host
            this.port = port
            this.environment = environment
        }

        FakeSftpClient password(String password) { return this }

        FakeSftpClient privateKey(String privateKey) { return this }

        FakeSftpClient preserveAttributes(boolean preserveAttributes) { return this }

        void connect() { }

        List<Map<String, Object>> ls(String basePath) {
            return environment.list(host, port, basePath)
        }

        InputStream openStream(String path) {
            return environment.openStream(host, port, path)
        }

        void mkdirs(String path) {
            environment.mkdirs(host, port, path)
        }

        void rename(String fromPath, String toPath) {
            environment.rename(host, port, fromPath, toPath)
        }

        void put(String remoteDir, String fileName, InputStream inputStream) {
            environment.put(host, port, remoteDir, fileName, inputStream)
        }

        void rm(String path) {
            environment.rm(host, port, path)
        }

        void close() { }
    }

    private static final class FakeSftpFile {
        byte[] bytes
        long lastModified
    }
}
