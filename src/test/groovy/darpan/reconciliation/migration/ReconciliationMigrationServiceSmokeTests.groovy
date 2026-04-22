package darpan.reconciliation.migration

import darpan.reconciliation.automation.SftpAutomationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationMigrationServiceSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    private Path backendRoot
    private ExecutionContext ec
    private FakeSftpEnvironment sftpEnvironment

    @BeforeAll
    void resolveRoot() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
    }

    @BeforeEach
    void setup() {
        String testDbName = "rsmig" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, testDbName)
        clearMigratedConfigurationRows("OrderIdSchemaMap")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
        ReconciliationSmokeTestSupport.seedLegacyMappingSftpJobFixture(ec)
        ec.cache.clearAllCaches()
        ec.message.clearErrors()

        sftpEnvironment = new FakeSftpEnvironment()
        SftpAutomationSupport.setClientFactory { String host, String user, Integer port ->
            new FakeSftpClient(host, user, port, sftpEnvironment)
        }
    }

    @AfterEach
    void cleanup() {
        clearMigratedConfigurationRows("OrderIdSchemaMap")
        SftpAutomationSupport.resetClientFactory()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void migrationDryRunApplyRewriteAndRerunAreStable() {
        assertNotNull(findMapping("OrderIdSchemaMap"))

        ec.message.clearErrors()
        ec.cache.clearAllCaches()
        Map<String, Object> dryRunResult = ec.service.sync()
                .name("reconciliation.ReconciliationMigrationServices.migrate#MappingsToRuleSetScopes")
                .parameters([
                        reconciliationMappingId: "OrderIdSchemaMap",
                        dryRun                : true,
                        rewriteSftpJobParams  : true
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertFalse(dryRunResult.applied as Boolean)
        assertEquals(1, dryRunResult.mappingsScanned)
        assertEquals(1, dryRunResult.ruleSetsCreated)
        assertEquals(1, dryRunResult.compareScopesCreated)
        assertEquals(2, dryRunResult.sourcesCreated)
        assertEquals(1, dryRunResult.jobsUpdated)
        assertTrue(((List) dryRunResult.warnings).isEmpty(), dryRunResult.warnings?.toString())

        assertNull(findRuleSet(expectedRuleSetId("OrderIdSchemaMap")))
        assertNull(findCompareScope(expectedCompareScopeId("OrderIdSchemaMap")))

        Map<String, String> legacyJobParams = loadJobParams("OrderIdSchemaMapLegacySftpJob")
        assertEquals("OrderIdSchemaMap", legacyJobParams.reconciliationMappingId)
        assertNull(legacyJobParams.ruleSetId)
        assertNull(legacyJobParams.compareScopeId)
        assertEquals("SHOPIFY", legacyJobParams.file1SystemEnumId)
        assertEquals("OMS", legacyJobParams.file2SystemEnumId)
        assertNotNull(findMapping("OrderIdSchemaMap"))

        ec.message.clearErrors()
        ec.cache.clearAllCaches()
        Map<String, Object> applyResult = ec.service.sync()
                .name("reconciliation.ReconciliationMigrationServices.migrate#MappingsToRuleSetScopes")
                .parameters([
                        reconciliationMappingId: "OrderIdSchemaMap",
                        dryRun                : false,
                        rewriteSftpJobParams  : true
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(applyResult.applied as Boolean)
        assertEquals(1, applyResult.mappingsScanned)
        assertEquals(1, applyResult.ruleSetsCreated)
        assertEquals(1, applyResult.compareScopesCreated)
        assertEquals(2, applyResult.sourcesCreated)
        assertEquals(1, applyResult.jobsUpdated)
        assertTrue(((List) applyResult.warnings).isEmpty(), applyResult.warnings?.toString())

        String ruleSetId = expectedRuleSetId("OrderIdSchemaMap")
        String compareScopeId = expectedCompareScopeId("OrderIdSchemaMap")

        def ruleSet = findRuleSet(ruleSetId)
        assertNotNull(ruleSet)
        assertEquals("Order ID", ruleSet.ruleSetName)
        assertEquals("Migrated from Mapping OrderIdSchemaMap: Order ID field mapping backed by saved schemas", ruleSet.description)

        def compareScope = findCompareScope(compareScopeId)
        assertNotNull(compareScope)
        assertEquals(ruleSetId, compareScope.ruleSetId)
        assertEquals("ORDER", compareScope.objectType)

        def file1Source = findCompareSource(compareScopeId, "FILE_1")
        def file2Source = findCompareSource(compareScopeId, "FILE_2")
        assertEquals("OMS", file1Source.systemEnumId)
        assertEquals("SHOPIFY", file2Source.systemEnumId)
        assertEquals("test-oms-order-id.schema.json", file1Source.schemaFileName)
        assertEquals("test-shopify-order-id.schema.json", file2Source.schemaFileName)
        assertEquals("order_id", file1Source.primaryIdExpression)
        assertEquals("order_id", file2Source.primaryIdExpression)
        assertNotNull(findMapping("OrderIdSchemaMap"))

        Map<String, String> rewrittenParams = loadJobParams("OrderIdSchemaMapLegacySftpJob")
        assertNull(rewrittenParams.reconciliationMappingId)
        assertEquals(ruleSetId, rewrittenParams.ruleSetId)
        assertEquals(compareScopeId, rewrittenParams.compareScopeId)
        assertEquals("OMS", rewrittenParams.file1SystemEnumId)
        assertEquals("SHOPIFY", rewrittenParams.file2SystemEnumId)
        assertEquals("OMS_TEST_SFTP", rewrittenParams.file1SftpServerId)
        assertEquals("SHOPIFY_TEST_SFTP", rewrittenParams.file2SftpServerId)
        assertEquals("test-oms-order-id.schema.json", rewrittenParams.file1SchemaFileName)
        assertEquals("test-shopify-order-id.schema.json", rewrittenParams.file2SchemaFileName)

        sftpEnvironment.putFile("shopify.test", 22, "/incoming/orders-1.csv",
                "order_id\nA100\nA200\nA300\n", 2000L)
        sftpEnvironment.putFile("oms.test", 22, "/incoming/orders-2.csv",
                "order_id\nA200\nA300\nA400\n", 1000L)

        ec.message.clearErrors()
        Map<String, Object> runResult = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile")
                .parameters(rewrittenParams)
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue(runResult.dataAvailable as Boolean)
        assertEquals("CSV", runResult.reconciliationType)
        assertEquals(2L, runResult.differenceCount)
        assertEquals(1L, runResult.onlyInFile1Count)
        assertEquals(1L, runResult.onlyInFile2Count)
        assertTrue(((List) runResult.validationErrors).isEmpty())
        assertNotNull(runResult.diffLocation)
        assertNotNull(runResult.diffFileName)

        Map<String, Object> diffDocument = parseOutputFile(runResult.diffLocation as String)
        assertEquals(ruleSetId, diffDocument.metadata.ruleSetId)
        assertEquals(compareScopeId, diffDocument.metadata.compareScopeId)
        assertEquals("ORDER", diffDocument.metadata.objectType)
        assertEquals("CSV", diffDocument.metadata.reconciliation)
        assertEquals(2, diffDocument.summary.totalDifferences)
        assertTrue(sftpEnvironment.hasFile("oms.test", 22, "/incoming/${runResult.diffFileName}"))

        ec.message.clearErrors()
        Map<String, Object> rerunResult = ec.service.sync()
                .name("reconciliation.ReconciliationMigrationServices.migrate#MappingsToRuleSetScopes")
                .parameters([
                        reconciliationMappingId: "OrderIdSchemaMap",
                        dryRun                : false,
                        rewriteSftpJobParams  : true
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, rerunResult.mappingsScanned)
        assertEquals(0, rerunResult.ruleSetsCreated)
        assertEquals(0, rerunResult.compareScopesCreated)
        assertEquals(0, rerunResult.sourcesCreated)
        assertEquals(0, rerunResult.jobsUpdated)
        assertNotNull(findMapping("OrderIdSchemaMap"))
    }

    private def findMapping(String mappingId) {
        return ec.entity.find("darpan.mapping.ReconciliationMapping")
                .condition("reconciliationMappingId", mappingId)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private def findRuleSet(String ruleSetId) {
        return ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", ruleSetId)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private def findCompareScope(String compareScopeId) {
        return ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private def findCompareSource(String compareScopeId, String fileSide) {
        return ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private Map<String, String> loadJobParams(String jobName) {
        Map<String, String> paramMap = [:]
        ec.entity.find("moqui.service.job.ServiceJobParameter")
                .condition("jobName", jobName)
                .disableAuthz()
                .useCache(false)
                .list()
                ?.each { paramMap[it.parameterName] = it.parameterValue }
        return paramMap
    }

    private void clearMigratedConfigurationRows(String mappingId) {
        String ruleSetId = expectedRuleSetId(mappingId)
        String compareScopeId = expectedCompareScopeId(mappingId)

        ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .list()
                ?.each { it.delete() }
        def compareScope = findCompareScope(compareScopeId)
        if (compareScope != null) compareScope.delete()
        def ruleSet = findRuleSet(ruleSetId)
        if (ruleSet != null) ruleSet.delete()
    }

    private String expectedRuleSetId(String mappingId) {
        return deterministicId("RSMIG", mappingId)
    }

    private String expectedCompareScopeId(String mappingId) {
        return deterministicId("CMPMIG", mappingId)
    }

    private String deterministicId(String prefix, String mappingId) {
        String cleaned = mappingId.toUpperCase().replaceAll(/[^A-Z0-9_]/, "_")
                .replaceAll(/_+/, "_")
                .replaceAll(/^_+|_+$/, "")
        if (!(cleaned[0] ==~ /[A-Z]/)) cleaned = "MAP_${cleaned}"
        String hash = stableHash(mappingId)
        int maxBaseLength = Math.max(1, 60 - prefix.length() - hash.length() - 2)
        if (cleaned.length() > maxBaseLength) cleaned = cleaned.substring(0, maxBaseLength)
        return "${prefix}_${cleaned}_${hash}"
    }

    private String stableHash(String rawValue) {
        CRC32 crc = new CRC32()
        byte[] bytes = (rawValue ?: "").getBytes(StandardCharsets.UTF_8)
        crc.update(bytes, 0, bytes.length)
        return String.format("%08X", crc.value)
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

        void put(String host, int port, String remoteDir, String name, InputStream inputStream) {
            String normalizedDir = normalizePath(remoteDir)
            String normalizedPath = normalizedDir == "/" ? "/${name}" : "${normalizedDir}/${name}"
            filesByServer[serverKey(host, port)][normalizedPath] = new FakeSftpFile(
                    bytes: inputStream.bytes,
                    lastModified: System.currentTimeMillis()
            )
        }

        void rm(String host, int port, String path) {
            filesByServer[serverKey(host, port)].remove(normalizePath(path))
        }

        void mkdirs(String host, int port, String path) {
        }

        private static String serverKey(String host, int port) {
            return "${host}:${port}"
        }

        private static String normalizePath(String path) {
            String normalized = path?.replaceAll(/\/+$/, "")
            if (!normalized) return "/"
            if (!normalized.startsWith("/")) normalized = "/" + normalized
            return normalized
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

        FakeSftpClient password(String ignored) { return this }
        FakeSftpClient privateKey(String ignored) { return this }
        FakeSftpClient preserveAttributes(boolean ignored) { return this }
        FakeSftpClient connect() { return this }
        void close() { }

        List<Map<String, Object>> ls(String basePath) {
            return environment.list(host, port, basePath)
        }

        InputStream openStream(String path) {
            return environment.openStream(host, port, path)
        }

        void rename(String fromPath, String toPath) {
            environment.rename(host, port, fromPath, toPath)
        }

        void put(String remoteDir, String name, InputStream inputStream) {
            environment.put(host, port, remoteDir, name, inputStream)
        }

        void rm(String path) {
            environment.rm(host, port, path)
        }

        void mkdirs(String path) {
            environment.mkdirs(host, port, path)
        }
    }

    private static final class FakeSftpFile {
        byte[] bytes
        long lastModified
    }
}
