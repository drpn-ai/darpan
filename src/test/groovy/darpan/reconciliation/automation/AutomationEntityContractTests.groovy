package darpan.reconciliation.automation

import groovy.util.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class AutomationEntityContractTests {
    private static final int MAX_ENUM_ID_LENGTH = 20

    private static final List<String> AUTOMATION_ENTITIES = [
            "darpan.reconciliation.ReconciliationAutomation",
            "darpan.reconciliation.ReconciliationAutomationSource",
            "darpan.reconciliation.ReconciliationAutomationExecution",
    ]

    private static final Map<String, List<String>> REQUIRED_ENUMS_BY_TYPE = [
            AutomationInputMode  : [
                    "AUT_IN_API_RANGE",
                    "AUT_IN_SFTP_FILES",
            ],
            AutomationSourceType : [
                    "AUT_SRC_API",
                    "AUT_SRC_SFTP",
            ],
            AutomationExecStatus : [
                    "AUT_STAT_PENDING",
                    "AUT_STAT_RUNNING",
                    "AUT_STAT_SUCCESS",
                    "AUT_STAT_FAILED",
                    "AUT_STAT_SKIP_DUP",
                    "AUT_STAT_NO_DATA",
            ],
            AutomationRelWindow  : [
                    "AUT_WIN_PREV_DAY",
                    "AUT_WIN_PREV_WEEK",
                    "AUT_WIN_PREV_MONTH",
                    "AUT_WIN_LAST_DAYS",
                    "AUT_WIN_LAST_WEEKS",
                    "AUT_WIN_LAST_MONTHS",
                    "AUT_WIN_CUSTOM",
            ],
    ]
    private static final Map<String, List<String>> RETIRED_ENUMS_BY_TYPE = [
            AutomationExecStatus: [
                    "AUT_STAT_SCHED",
                    "AUT_STAT_RUN",
                    "AUT_STAT_DONE",
                    "AUT_STAT_FAIL",
                    "AUT_STAT_CANCEL",
            ],
    ]

    @Test
    void automationEntitiesDefineTenantOwnedConfigurationSourcesAndExecutions() {
        def entities = parse("entity/ReconciliationEntities.xml").entity
        def runResult = entity(entities, "ReconciliationRunResult")
        def automation = entity(entities, "ReconciliationAutomation")
        def source = entity(entities, "ReconciliationAutomationSource")
        def execution = entity(entities, "ReconciliationAutomationExecution")

        assertFields(runResult, [
                "reconciliationRunResultId", "savedRunId", "savedRunType", "reconciliationRunId",
                "reconciliationMappingId", "ruleSetId", "compareScopeId", "companyUserGroupId",
                "createdByUserId", "file1Name", "file1DataManagerPath", "file2Name",
                "file2DataManagerPath", "resultDataManagerPath", "statusEnumId",
                "reconciliationType", "differenceCount", "onlyInFile1Count", "onlyInFile2Count",
                "createdDate", "startedDate", "completedDate", "lastUpdatedDate",
        ])
        assertEquals("true", attr(field(runResult, "reconciliationRunResultId"), "is-pk"))
        assertEquals("'AUT_STAT_SUCCESS'", attr(field(runResult, "statusEnumId"), "default"))
        assertRelationship(runResult, "Status", "moqui.basic.Enumeration", "statusEnumId", "enumId")

        assertFields(automation, [
                "automationId", "automationName", "companyUserGroupId", "createdByUserId",
                "inputModeEnumId", "savedRunId", "savedRunType", "reconciliationRunId",
                "reconciliationMappingId", "ruleSetId", "compareScopeId", "scheduleExpr",
                "nextScheduledFireTime", "lastScheduledFireTime",
                "relativeWindowTypeEnumId", "relativeWindowCount",
                "customWindowStartDate", "customWindowEndDate", "maxWindowDays",
                "splitWindowDays", "windowTimeZone", "safeConfigJson", "isActive",
                "createdDate", "lastUpdatedDate",
        ])
        assertEquals("true", attr(field(automation, "automationId"), "is-pk"))
        assertEquals("true", attr(field(automation, "companyUserGroupId"), "not-null"))
        assertEquals("'ruleset'", attr(field(automation, "savedRunType"), "default"))
        assertEquals("28", attr(field(automation, "maxWindowDays"), "default"))
        assertEquals("28", attr(field(automation, "splitWindowDays"), "default"))
        assertEquals("'UTC'", attr(field(automation, "windowTimeZone"), "default"))
        assertEquals("'Y'", attr(field(automation, "isActive"), "default"))
        assertRelationship(automation, "InputMode", "moqui.basic.Enumeration", "inputModeEnumId", "enumId")
        assertRelationship(automation, "RelativeWindow", "moqui.basic.Enumeration", "relativeWindowTypeEnumId", "enumId")
        assertRelationship(automation, null, "darpan.rule.RuleSet", "ruleSetId", null)
        assertRelationship(automation, null, "darpan.rule.RuleSetCompareScope", "compareScopeId", null)

        assertFields(source, [
                "automationId", "fileSide", "companyUserGroupId", "createdByUserId",
                "sourceTypeEnumId", "systemEnumId", "fileTypeEnumId", "schemaFileName",
                "recordRootExpression", "primaryIdExpression", "idValueNormalizer",
                "systemMessageRemoteId", "nsRestletConfigId", "sftpServerId",
                "remotePathTemplate", "fileNamePattern", "apiRequestTemplateJson",
                "apiResponsePathExpression", "dateFromParameterName", "dateToParameterName",
                "safeMetadataJson", "createdDate", "lastUpdatedDate",
        ])
        assertEquals("true", attr(field(source, "automationId"), "is-pk"))
        assertEquals("true", attr(field(source, "fileSide"), "is-pk"))
        assertTrue(field(source, "fileSide").description.text().contains("FILE_1 or FILE_2"))
        assertRelationship(source, "SourceType", "moqui.basic.Enumeration", "sourceTypeEnumId", "enumId")
        assertRelationship(source, null, "darpan.reconciliation.SftpServer", "sftpServerId", null)
        assertRelationship(source, null, "darpan.reconciliation.NsRestletConfig", "nsRestletConfigId", null)

        assertFields(execution, [
                "automationExecutionId", "automationId", "companyUserGroupId", "createdByUserId",
                "statusEnumId", "scheduledDate", "startedDate", "completedDate",
                "parentAutomationExecutionId", "childWindowSequenceNum", "windowStartDate",
                "windowEndDate", "childWindowStartDate", "childWindowEndDate",
                "file1Name", "file1DataManagerPath", "file2Name", "file2DataManagerPath",
                "resultFileName", "resultDataManagerPath", "reconciliationRunResultId",
                "file1RecordCount", "file2RecordCount", "differenceCount",
                "onlyInFile1Count", "onlyInFile2Count", "safeMetadataJson",
                "errorMessage", "errorDetail", "createdDate", "lastUpdatedDate",
        ])
        assertEquals("true", attr(field(execution, "automationExecutionId"), "is-pk"))
        assertEquals("'AUT_STAT_PENDING'", attr(field(execution, "statusEnumId"), "default"))
        assertRelationship(execution, "Status", "moqui.basic.Enumeration", "statusEnumId", "enumId")
        assertRelationship(execution, null, "darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId", null)
    }

    @Test
    void automationSeedAndUpgradeDataDefineRequiredEnums() {
        assertAutomationEnums(parse("data/AutomationSeedData.xml"))
        assertAutomationEnums(parse("data/upgrade-data.xml"))
    }

    @Test
    void automationEntitiesAreCoveredByTenantSecuritySeedAndUpgradeData() {
        assertSecurityCoverage(parse("data/SecuritySeedData.xml"))
        assertSecurityCoverage(parse("data/upgrade-data.xml"))
    }

    @Test
    void automationScannerJobIsSeeded() {
        assertScannerJob(parse("data/ReconciliationJobSeedData.xml"))
        assertScannerJob(parse("data/upgrade-data.xml"))
    }

    @Test
    void apiSourceEndpointsAreSeededForCreateRunSetup() {
        assertHotWaxOrdersRemote(parse("data/SystemMessageRemoteSeedData.xml"))
        assertHotWaxOrdersRemote(parse("data/upgrade-data.xml"))
        assertShopifyGraphqlOrdersRemote(parse("data/SystemMessageRemoteSeedData.xml"))
        assertShopifyGraphqlOrdersRemote(parse("data/upgrade-data.xml"))
    }

    @Test
    void omsSystemSourceIsLabeledHotWaxForSetupPickers() {
        assertOmsSystemSourceLabel(parse("data/DarpanSystemSourceSeedData.xml"))
        assertOmsSystemSourceLabel(parse("data/MappingSeedData.xml"))
        assertOmsSystemSourceLabel(parse("data/ReconciliationCompareScopeFixtureData.xml"))
        def upgradeData = parse("data/upgrade-data.xml")
        assertCanonicalSystemSourceRows(upgradeData)
        assertOmsSystemSourceLabel(upgradeData)
        assertLegacySystemSourceAliasCleanup(upgradeData)
        assertNoLegacySystemSourceRows(parse("data/DarpanSystemSourceSeedData.xml"))
        assertNoLegacySystemSourceRows(parse("data/MappingSeedData.xml"))
        assertNoLegacySystemSourceRows(parse("data/upgrade-data.xml"))
    }

    @Test
    void automationSourceOptionHelpersBypassEntityAuthzForInternalMetadataReads() {
        String automationSource = readSource("src/main/groovy/darpan/facade/reconciliation/AutomationFacadeSupport.groovy")
        assertAllFindsDisableAuthz(automationSource, "moqui.basic.Enumeration")
        assertAllFindsDisableAuthz(automationSource, "moqui.service.message.SystemMessageRemote")

        String savedRunSource = readSource("src/main/groovy/darpan/facade/reconciliation/ReconciliationSavedRunSupport.groovy")
        [
                "moqui.basic.Enumeration",
                "moqui.service.message.SystemMessageRemote",
                "darpan.mapping.ReconciliationMapping",
                "darpan.mapping.ReconciliationMappingMember",
                "darpan.rule.RuleSet",
                "darpan.rule.RuleSetCompareScope",
                "darpan.rule.RuleSetCompareSource",
                "darpan.rule.Rule",
                "darpan.shopify.ShopifyAuthConfig",
                "darpan.hotwax.HotWaxOmsRestSourceConfig",
                "darpan.reconciliation.NsAuthConfig",
                "darpan.reconciliation.NsRestletConfig",
        ].each { String entityName ->
            assertAllFindsDisableAuthz(savedRunSource, entityName)
        }
    }

    private static void assertAutomationEnums(def root) {
        List<String> typeIds = nodes(root, "moqui.basic.EnumerationType").collect { attr(it, "enumTypeId") }
        REQUIRED_ENUMS_BY_TYPE.keySet().each { String enumTypeId ->
            assertTrue(typeIds.contains(enumTypeId), "Missing enum type ${enumTypeId}")
            assertTrue(enumTypeId.size() <= MAX_ENUM_ID_LENGTH, "${enumTypeId} should stay at or below ${MAX_ENUM_ID_LENGTH} characters")
        }

        List enumNodes = nodes(root, "moqui.basic.Enumeration")
        REQUIRED_ENUMS_BY_TYPE.each { String enumTypeId, List<String> enumIds ->
            enumIds.each { String enumId ->
                def enumNode = enumNodes.find {
                    attr(it, "enumTypeId") == enumTypeId && attr(it, "enumId") == enumId
                }
                assertNotNull(enumNode, "Missing enum ${enumId} for ${enumTypeId}")
                assertFalse((attr(enumNode, "description") ?: "").trim().isEmpty(), "Missing description for ${enumId}")
                assertTrue(enumId.size() <= MAX_ENUM_ID_LENGTH, "${enumId} should stay at or below ${MAX_ENUM_ID_LENGTH} characters")
            }
        }

        RETIRED_ENUMS_BY_TYPE.each { String enumTypeId, List<String> enumIds ->
            enumIds.each { String enumId ->
                def enumNode = enumNodes.find {
                    attr(it, "enumTypeId") == enumTypeId && attr(it, "enumId") == enumId
                }
                assertNull(enumNode, "Retired enum ${enumId} should not be seeded for ${enumTypeId}")
            }
        }
    }

    private static void assertSecurityCoverage(def root) {
        List<String> artifactEntityNames = nodes(root, "moqui.security.ArtifactGroupMember")
                .findAll { attr(it, "artifactGroupId") == "DARPAN_APP" && attr(it, "artifactTypeEnumId") == "AT_ENTITY" }
                .collect { attr(it, "artifactName") }
        List filterEntityNames = nodes(root, "moqui.security.EntityFilter")
                .findAll { attr(it, "entityFilterSetId") == "DARPAN_ACTIVE_COMPANY_SCOPE" }
                .collect { attr(it, "entityName") }

        AUTOMATION_ENTITIES.each { String entityName ->
            assertTrue(artifactEntityNames.contains(entityName), "Missing DARPAN_APP artifact member for ${entityName}")
            assertTrue(filterEntityNames.contains(entityName), "Missing active-tenant entity filter for ${entityName}")
        }
    }

    private static void assertScannerJob(def root) {
        def job = nodes(root, "moqui.service.job.ServiceJob").find {
            attr(it, "jobName") == "scan_ReconciliationAutomations_5m"
        }
        assertNotNull(job, "Missing reconciliation automation scanner job")
        assertEquals("reconciliation.ReconciliationAutomationServices.scan#DueAutomations", attr(job, "serviceName"))
        assertEquals("0 0/5 * * * ?", attr(job, "cronExpression"))
    }

    private static void assertShopifyGraphqlOrdersRemote(def root) {
        def remote = nodes(root, "moqui.service.message.SystemMessageRemote").find {
            attr(it, "systemMessageRemoteId") == "SHOPIFY_REMOTE"
        }
        assertNotNull(remote, "Missing Shopify GraphQL orders SystemMessageRemote")
        assertEquals("Admin GraphQL Orders", attr(remote, "description"))
        assertEquals("https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json", attr(remote, "sendUrl"))
        assertEquals("facade.ShopifyFacadeServices.execute#ShopifyGraphql", attr(remote, "sendServiceName"))
    }

    private static void assertHotWaxOrdersRemote(def root) {
        def remote = nodes(root, "moqui.service.message.SystemMessageRemote").find {
            attr(it, "systemMessageRemoteId") == "HOTWAX_ORDERS_API"
        }
        assertNotNull(remote, "Missing HotWax orders SystemMessageRemote")
        assertEquals("Orders API", attr(remote, "description"))
        assertEquals("{baseUrl}/rest/s1/oms/orders", attr(remote, "sendUrl"))
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders", attr(remote, "sendServiceName"))
    }

    private static void assertOmsSystemSourceLabel(def root) {
        def oms = nodes(root, "moqui.basic.Enumeration").find {
            attr(it, "enumTypeId") == "DarpanSystemSource" && attr(it, "enumId") == "OMS"
        }
        assertNotNull(oms, "Missing OMS DarpanSystemSource enum")
        assertEquals("HOTWAX", attr(oms, "enumCode"))
        assertEquals("HotWax", attr(oms, "description"))
    }

    private static void assertCanonicalSystemSourceRows(def root) {
        Map<String, String> expectedDescriptions = [
                OMS     : "HotWax",
                SHOPIFY : "Shopify",
                NETSUITE: "NetSuite",
                SAPI    : "SAPI",
        ]
        expectedDescriptions.each { String enumId, String description ->
            def row = nodes(root, "moqui.basic.Enumeration").find {
                attr(it, "enumTypeId") == "DarpanSystemSource" && attr(it, "enumId") == enumId
            }
            assertNotNull(row, "Missing ${enumId} DarpanSystemSource enum")
            assertEquals(description, attr(row, "description"))
        }
    }

    private static void assertLegacySystemSourceAliasCleanup(def root) {
        List<String> staleEnumIds = ["HOTWAX", "DarSysOms", "DarSysShopify", "DarSysNetSuite", "DarSysNetsuite", "DarSysSapi"]
        List<String> cleanupIds = nodes(root, "delete-moqui.basic.Enumeration").collect { attr(it, "enumId") }
        staleEnumIds.each { String staleEnumId ->
            assertTrue(cleanupIds.contains(staleEnumId), "Upgrade data should delete stale ${staleEnumId} DarpanSystemSource aliases")
        }
    }

    private static void assertNoLegacySystemSourceRows(def root) {
        List<String> staleEnumIds = ["HOTWAX", "DarSysOms", "DarSysShopify", "DarSysNetSuite", "DarSysNetsuite", "DarSysSapi"]
        List<String> createdStaleIds = nodes(root, "moqui.basic.Enumeration").findAll {
            attr(it, "enumTypeId") == "DarpanSystemSource" && staleEnumIds.contains(attr(it, "enumId"))
        }.collect { attr(it, "enumId") }
        assertTrue(createdStaleIds.isEmpty(), "Seed data should not create stale DarpanSystemSource aliases: ${createdStaleIds}")
    }

    private static void assertFields(def entityNode, Collection<String> expectedFields) {
        List<String> actualFields = entityNode.field.collect { attr(it, "name") }
        expectedFields.each { String fieldName ->
            assertTrue(actualFields.contains(fieldName), "Missing ${attr(entityNode, "entity-name")}.${fieldName}")
        }
    }

    private static void assertRelationship(def entityNode, String title, String related, String fieldName, String relatedFieldName) {
        def relationship = entityNode.relationship.find {
            (title == null || attr(it, "title") == title) && attr(it, "related") == related &&
                    it."key-map".find { keyMap ->
                        attr(keyMap, "field-name") == fieldName &&
                                (relatedFieldName == null || attr(keyMap, "related-field-name") == relatedFieldName)
                    }
        }
        assertNotNull(relationship, "Missing relationship for ${attr(entityNode, "entity-name")}.${fieldName} -> ${related}")
    }

    private static def entity(def entities, String entityName) {
        def entityNode = entities.find { attr(it, "entity-name") == entityName }
        assertNotNull(entityNode, "Missing entity ${entityName}")
        return entityNode
    }

    private static def field(def entityNode, String fieldName) {
        def fieldNode = entityNode.field.find { attr(it, "name") == fieldName }
        assertNotNull(fieldNode, "Missing field ${attr(entityNode, "entity-name")}.${fieldName}")
        return fieldNode
    }

    private static List nodes(def root, String nodeName) {
        return root.children().findAll { it.name() == nodeName }
    }

    private static String attr(def node, String name) {
        return node.attributes().get(name) as String
    }

    private static def parse(String relativePath) {
        new XmlParser(false, false).parse(componentRoot().resolve(relativePath).toFile())
    }

    private static String readSource(String relativePath) {
        return Files.readString(componentRoot().resolve(relativePath))
    }

    private static void assertAllFindsDisableAuthz(String source, String entityName) {
        String needle = "ec.entity.find(\"${entityName}\")"
        int index = source.indexOf(needle)
        assertTrue(index >= 0, "Expected ${needle} in source")

        while (index >= 0) {
            int oneIndex = source.indexOf(".one()", index)
            int listIndex = source.indexOf(".list()", index)
            List<Integer> terminals = [oneIndex, listIndex].findAll { it >= 0 }
            assertFalse(terminals.isEmpty(), "Expected ${needle} to terminate with one() or list()")
            int terminal = terminals.min()
            String chain = source.substring(index, terminal)
            assertTrue(chain.contains(".disableAuthz()"), "Expected ${needle} read chain to call disableAuthz()")
            index = source.indexOf(needle, index + needle.length())
        }
    }

    private static Path componentRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        List<Path> candidates = [
                cwd,
                cwd.resolve("runtime/component/darpan"),
                cwd.resolve("darpan-backend/runtime/component/darpan"),
        ]

        Path root = candidates.find {
            Files.exists(it.resolve("entity/ReconciliationEntities.xml"))
        }
        if (!root) {
            throw new IllegalStateException("Unable to resolve Darpan component root from ${cwd}")
        }
        return root
    }
}
