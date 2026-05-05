package darpan.reconciliation.support

import darpan.facade.common.TenantAccessSupport
import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.naming.NameNotFoundException
import java.sql.Timestamp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator

class ReconciliationSmokeTestSupport {
    private static final String TRANSACTIONAL_DS_NAME = "transactional_DS"
    private static final String TEST_COMPANY_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TEST_COMPANY_USERNAME = "test.customer"
    private static final String TEST_COMPANY_USER_GROUP_ID = "KREWE"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    static ExecutionContext initMoqui(Path backendRoot, String testDbName) {
        String runtimePath = backendRoot.resolve("runtime").toString()
        Path testDbBasePath = backendRoot.resolve("runtime/tmp/test-h2/${testDbName}")
        resetDatabaseFiles(testDbBasePath)
        String testDbPath = testDbBasePath.toString()
        String safeTestToken = testDbName.replaceAll(/[^A-Za-z0-9_-]/, "_")
        Path atomikosLogPath = backendRoot.resolve("runtime/tmp/test-atomikos/${safeTestToken}")

        System.setProperty("moqui.runtime", runtimePath)
        System.setProperty("moqui_runtime", runtimePath)
        System.setProperty("entity_ds_url", "jdbc:h2:${testDbPath};lock_timeout=30000")
        System.setProperty("darpan.data.manager.location", "runtime://tmp/test-data-manager/${safeTestToken}")
        resetDirectory(atomikosLogPath)
        System.setProperty("com.atomikos.icatch.log_base_dir", atomikosLogPath.toString())
        System.setProperty("com.atomikos.icatch.log_base_name", safeTestToken)
        System.setProperty("com.atomikos.icatch.tm_unique_name", "tm_${safeTestToken}")

        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
        clearTransactionalDatasourceRegistration()

        Moqui.dynamicInit(new ExecutionContextFactoryImpl(
                runtimePath,
                "conf/MoquiDevConf.xml"
        ))

        ExecutionContext ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push(
                "smokeTests",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        if (!ec.user.internalLoginUser("john.doe")) {
            assert ec.user.loginAnonymousIfNoUser()
        }
        ec.message.clearErrors()
        ec.resource.properties["darpan.data.manager.location"] = "runtime://tmp/test-data-manager/${safeTestToken}"
        return ec
    }

    static void cleanupMoqui(ExecutionContext ec) {
        ec?.destroy()
        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
        clearTransactionalDatasourceRegistration()
        System.clearProperty("darpan.data.manager.location")
    }

    static void loadSeedData(ExecutionContext ec, String... locations) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "loadData",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            locations.each { String location ->
                ec.entity.makeDataLoader().location(location).load()
            }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    static void seedBaseCompareRuleSet(ExecutionContext ec) {
        seedCompanyScope(ec)
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                ruleSetName   : "Darpan Test Compare RuleSet",
                description   : "Base RuleSet required by compare-scope smoke fixtures.",
                version       : "1.0",
                explosionPath : "rows",
                primaryKeyPath: "itemId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
    }

    static void seedCompareScopeFixtures(ExecutionContext ec) {
        seedBaseCompareRuleSet(ec)
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_PRODUCT_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                ruleSetName   : "Darpan Test Product Compare RuleSet",
                description   : "Smoke-test RuleSet for matched-pair field diff generation.",
                version       : "1.0",
                explosionPath : "data.products",
                primaryKeyPath: "productId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_PRODUCT_BROKEN_RS"], [
                ruleSetId     : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                ruleSetName   : "Darpan Test Broken Product RuleSet",
                description   : "Smoke-test RuleSet for preserving base diffs when DRL compilation fails.",
                version       : "1.0",
                explosionPath : "data.products",
                primaryKeyPath: "productId",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId: TEST_COMPANY_USER_ID
        ])

        upsertEntity(ec, "darpan.rule.Rule", [ruleId: "DARPAN_TEST_PRODUCT_SKU_MISMATCH"], [
                ruleId      : "DARPAN_TEST_PRODUCT_SKU_MISMATCH",
                ruleSetId   : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                sequenceNum : 10,
                ruleText    : "Emit a field diff when SKU differs for the same product",
                ruleLogic   : 'rule "DARPAN_TEST_PRODUCT_SKU_MISMATCH" salience 200 when $m : Map( this["file1"] != null, this["file2"] != null ) eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"))) then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", ((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"), "WARN", "SKU mismatch"); end',
                enabled     : "Y",
                ruleType    : "DRL",
                severity    : "WARN"
        ])
        upsertEntity(ec, "darpan.rule.Rule", [ruleId: "DARPAN_TEST_PRODUCT_PRICE_MISMATCH"], [
                ruleId      : "DARPAN_TEST_PRODUCT_PRICE_MISMATCH",
                ruleSetId   : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                sequenceNum : 20,
                ruleText    : "Emit a field diff when price differs for the same product",
                ruleLogic   : 'rule "DARPAN_TEST_PRODUCT_PRICE_MISMATCH" salience 100 when $m : Map( this["file1"] != null, this["file2"] != null ) eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("price"), ((Map) $m.get("file2")).get("price"))) then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "price", ((Map) $m.get("file1")).get("price"), ((Map) $m.get("file2")).get("price"), "WARN", "Price mismatch"); end',
                enabled     : "Y",
                ruleType    : "DRL",
                severity    : "WARN"
        ])
        upsertEntity(ec, "darpan.rule.Rule", [ruleId: "DARPAN_TEST_PRODUCT_BROKEN_RULE"], [
                ruleId      : "DARPAN_TEST_PRODUCT_BROKEN_RULE",
                ruleSetId   : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                sequenceNum : 10,
                ruleText    : "Broken DRL used to verify base-diff preservation",
                ruleLogic   : 'rule "DARPAN_TEST_PRODUCT_BROKEN_RULE" when $m : Map( this["file1"] != null ) then BROKEN end',
                enabled     : "Y",
                ruleType    : "DRL",
                severity    : "ERROR"
        ])

        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                objectType    : "ORDER",
                description   : "Smoke-test compare scope for nested JSON order IDs."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE"], [
                compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE",
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                objectType    : "ORDER",
                description   : "Smoke-test compare scope for CSV order IDs."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                objectType    : "PRODUCT",
                description   : "Smoke-test compare scope for product field mismatches."
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareScope", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE"], [
                compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                ruleSetId     : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                objectType    : "PRODUCT",
                description   : "Smoke-test compare scope for broken DRL preservation behavior."
        ])

        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ORDER_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id|SHOPIFY_GID_TAIL"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_ORDER_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.orders.edges",
                primaryIdExpression : "node.id",
                idValueNormalizer   : "TRAILING_DIGITS"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_ORDER_CSV_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftCsv",
                primaryIdExpression : "orderId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_ORDER_CSV_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_ORDER_CSV_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftCsv",
                primaryIdExpression : "orderId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE", fileSide: "FILE_1"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                fileSide            : "FILE_1",
                systemEnumId        : "SHOPIFY",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSetCompareSource", [compareScopeId: "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE", fileSide: "FILE_2"], [
                compareScopeId      : "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                fileSide            : "FILE_2",
                systemEnumId        : "OMS",
                fileTypeEnumId      : "DftJson",
                recordRootExpression: "data.products",
                primaryIdExpression : "productId"
        ])
    }

    static void seedSchemaBackedCsvMappingFixtures(ExecutionContext ec) {
        seedCompanyScope(ec)
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestOmsOrderSchema"], [
                jsonSchemaId       : "TestOmsOrderSchema",
                schemaName         : "test-oms-order-id.schema.json",
                description        : "Smoke-test OMS order id schema",
                systemEnumId       : "OMS",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestShopifyOrderSchema"], [
                jsonSchemaId       : "TestShopifyOrderSchema",
                schemaName         : "test-shopify-order-id.schema.json",
                description        : "Smoke-test Shopify order id schema",
                systemEnumId       : "SHOPIFY",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"order_id":{"type":"string"}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestOmsOrdersJsonSchema"], [
                jsonSchemaId       : "TestOmsOrdersJsonSchema",
                schemaName         : "test-oms-orders.schema.json",
                description        : "Smoke-test OMS orders JSON schema",
                systemEnumId       : "OMS",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"orders":{"type":"array","items":{"type":"object","properties":{"order_id":{"type":"string"},"status":{"type":"string"}}}}}}'
        ])
        upsertEntity(ec, "darpan.reconciliation.JsonSchema", [jsonSchemaId: "TestShopifyOrdersJsonSchema"], [
                jsonSchemaId       : "TestShopifyOrdersJsonSchema",
                schemaName         : "test-shopify-orders.schema.json",
                description        : "Smoke-test Shopify orders JSON schema",
                systemEnumId       : "SHOPIFY",
                companyUserGroupId : TEST_COMPANY_USER_GROUP_ID,
                createdDate        : TEST_FROM_DATE,
                schemaText         : '{"type":"object","properties":{"data":{"type":"object","properties":{"orders":{"type":"object","properties":{"edges":{"type":"array","items":{"type":"object","properties":{"node":{"type":"object","properties":{"id":{"type":"string"},"status":{"type":"string"}}}}}}}}}}}}'
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMapping", [reconciliationMappingId: "OrderIdSchemaMap"], [
                reconciliationMappingId: "OrderIdSchemaMap",
                mappingName            : "Order ID",
                description            : "Order ID field mapping backed by saved schemas",
                companyUserGroupId     : TEST_COMPANY_USER_GROUP_ID,
                createdByUserId        : TEST_COMPANY_USER_ID
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdSchemaMapOms"], [
                mappingMemberId         : "OrderIdSchemaMapOms",
                reconciliationMappingId : "OrderIdSchemaMap",
                systemEnumId            : "OMS",
                fileTypeEnumId          : "DftCsv",
                schemaFileName          : "test-oms-order-id.schema.json",
                idFieldExpression       : "order_id"
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdSchemaMapShopify"], [
                mappingMemberId         : "OrderIdSchemaMapShopify",
                reconciliationMappingId : "OrderIdSchemaMap",
                systemEnumId            : "SHOPIFY",
                fileTypeEnumId          : "DftCsv",
                schemaFileName          : "test-shopify-order-id.schema.json",
                idFieldExpression       : "order_id"
        ])
    }

    static void seedCompanyScope(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "UserGroupType"], [
                enumTypeId  : "UserGroupType",
                description : "User Group Type"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "UgtDarpanPermission"], [
                enumId      : "UgtDarpanPermission",
                enumTypeId  : "UserGroupType",
                description : "Darpan permission groups"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID], [
                enumId      : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
                enumTypeId  : "UserGroupType",
                description : "Darpan company groups"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], [
                userGroupId      : TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                description      : "Can create, update, run, and delete tenant-scoped Darpan data",
                groupTypeEnumId  : "UgtDarpanPermission"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: TEST_COMPANY_USER_GROUP_ID], [
                userGroupId     : TEST_COMPANY_USER_GROUP_ID,
                description     : "Krewe",
                groupTypeEnumId : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
        ])
        upsertEntityValue(ec, "moqui.security.UserAccount", [userId: TEST_COMPANY_USER_ID], [
                userId        : TEST_COMPANY_USER_ID,
                username      : TEST_COMPANY_USERNAME,
                userFullName  : "Smoke Test Customer",
                currentPassword: "",
                disabled      : "N"
        ])
        upsertEntityValue(ec, "moqui.security.UserGroupMember", [
                userGroupId: TEST_COMPANY_USER_GROUP_ID,
                userId     : TEST_COMPANY_USER_ID,
                fromDate   : TEST_FROM_DATE
        ], [
                userGroupId: TEST_COMPANY_USER_GROUP_ID,
                userId     : TEST_COMPANY_USER_ID,
                fromDate   : TEST_FROM_DATE
        ])
        upsertEntityValue(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : TEST_COMPANY_USER_GROUP_ID,
                userId               : TEST_COMPANY_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE
        ], [
                tenantUserGroupId    : TEST_COMPANY_USER_GROUP_ID,
                userId               : TEST_COMPANY_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE
        ])
        if (!ec.user.internalLoginUser(TEST_COMPANY_USER_ID)) {
            ec.user.internalLoginUser(TEST_COMPANY_USERNAME)
        }
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TEST_COMPANY_USER_GROUP_ID)
        ec.message.clearErrors()
    }

    static void seedSftpServerFixtures(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanSftpScope"], [
                enumTypeId : "DarpanSftpScope",
                description: "Darpan SFTP server scope"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_TENANT"], [
                enumId     : "DARPAN_SFTP_TENANT",
                enumTypeId : "DarpanSftpScope",
                description: "Tenant-owned SFTP server"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_TENANT_GROUP"], [
                enumId     : "DARPAN_SFTP_TENANT_GROUP",
                enumTypeId : "DarpanSftpScope",
                description: "SFTP server shared across explicitly assigned tenant groups"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DARPAN_SFTP_ADMIN"], [
                enumId     : "DARPAN_SFTP_ADMIN",
                enumTypeId : "DarpanSftpScope",
                description: "Admin/platform-owned SFTP server"
        ])
        upsertEntity(ec, "moqui.security.UserGroup", [userGroupId: "GORJANA"], [
                userGroupId     : "GORJANA",
                description     : "Gorjana",
                groupTypeEnumId : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "SHOPIFY_TEST_SFTP"], [
                sftpServerId     : "SHOPIFY_TEST_SFTP",
                description      : "Smoke-test Shopify SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "shopify.test",
                port             : 22,
                username         : "shopify-user",
                password         : "shopify-pass",
                remoteAttributes : "N"
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "OMS_TEST_SFTP"], [
                sftpServerId     : "OMS_TEST_SFTP",
                description      : "Smoke-test OMS SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: TEST_COMPANY_USER_GROUP_ID,
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "oms.test",
                port             : 22,
                username         : "oms-user",
                password         : "oms-pass",
                remoteAttributes : "N"
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_TEST_SFTP"], [
                sftpServerId     : "GORJANA_TEST_SFTP",
                description      : "Smoke-test Gorjana SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT",
                companyUserGroupId: "GORJANA",
                createdByUserId  : TEST_COMPANY_USER_ID,
                host             : "gorjana.test",
                port             : 22,
                username         : "gorjana-user",
                password         : "gorjana-pass",
                remoteAttributes : "N"
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "ADMIN_TEST_SFTP"], [
                sftpServerId     : "ADMIN_TEST_SFTP",
                description      : "Smoke-test admin SFTP server",
                scopeEnumId      : "DARPAN_SFTP_ADMIN",
                host             : "admin.test",
                port             : 22,
                username         : "admin-user",
                password         : "admin-pass",
                remoteAttributes : "N"
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServer", [sftpServerId: "SHARED_TEST_SFTP"], [
                sftpServerId     : "SHARED_TEST_SFTP",
                description      : "Smoke-test shared tenant-group SFTP server",
                scopeEnumId      : "DARPAN_SFTP_TENANT_GROUP",
                host             : "shared.test",
                port             : 22,
                username         : "shared-user",
                password         : "shared-pass",
                remoteAttributes : "N"
        ])
        upsertEntityValue(ec, "darpan.reconciliation.SftpServerTenantAccess", [
                sftpServerId      : "SHARED_TEST_SFTP",
                tenantUserGroupId : TEST_COMPANY_USER_GROUP_ID
        ], [
                sftpServerId      : "SHARED_TEST_SFTP",
                tenantUserGroupId : TEST_COMPANY_USER_GROUP_ID
        ])
    }

    static Path resolveBackendRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        List<Path> candidates = [
                cwd,
                cwd.resolve("darpan-backend"),
                cwd.resolve("runtime/component/darpan").normalize(),
                cwd.resolve("../../..").normalize(),
                cwd.resolve("../../../..").normalize()
        ].unique()

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("runtime/conf/MoquiDevConf.xml")) &&
                    Files.exists(candidate.resolve("runtime/component/darpan"))) {
                return candidate
            }
        }

        throw new IllegalStateException("Unable to resolve darpan-backend root from ${cwd}")
    }

    private static void resetDirectory(Path directory) {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Path path -> Files.deleteIfExists(path) }
        }
        Files.createDirectories(directory)
    }

    private static void resetDatabaseFiles(Path dbBasePath) {
        Path parent = dbBasePath.parent
        if (parent == null) return
        Files.createDirectories(parent)

        String baseName = dbBasePath.fileName.toString()
        Files.list(parent).withCloseable { stream ->
            stream.filter { Path path ->
                String fileName = path.fileName.toString()
                fileName == baseName || fileName.startsWith(baseName + ".")
            }.forEach { Path path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private static void clearTransactionalDatasourceRegistration() {
        try {
            ClassLoader atomikosClassLoader = Thread.currentThread().contextClassLoader
            if (atomikosClassLoader == null) return

            Class<?> registryClass = atomikosClassLoader.loadClass("com.atomikos.util.IntraVmObjectRegistry")
            registryClass.getMethod("removeResource", String).invoke(null, TRANSACTIONAL_DS_NAME)

            Class<?> configurationClass = atomikosClassLoader.loadClass("com.atomikos.icatch.config.Configuration")
            configurationClass.getMethod("removeResource", String).invoke(null, TRANSACTIONAL_DS_NAME)
            configurationClass.getMethod("shutdown", boolean).invoke(null, false)
        } catch (NameNotFoundException ignored) {
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof NameNotFoundException || cause instanceof ClassNotFoundException) return
            throw new IllegalStateException("Unable to clear Atomikos datasource registration for ${TRANSACTIONAL_DS_NAME}", cause)
        }
    }

    private static void seedSharedReconciliationEnums(ExecutionContext ec) {
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanSystemSource"], [
                enumTypeId  : "DarpanSystemSource",
                description : "Darpan System Source"
        ])
        upsertEntity(ec, "moqui.basic.EnumerationType", [enumTypeId: "DarpanFileType"], [
                enumTypeId  : "DarpanFileType",
                description : "File Types for Reconciliation"
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "OMS"], [
                enumId      : "OMS",
                enumTypeId  : "DarpanSystemSource",
                enumCode    : "HOTWAX",
                description : "HotWax",
                sequenceNum : 1
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "SHOPIFY"], [
                enumId      : "SHOPIFY",
                enumTypeId  : "DarpanSystemSource",
                enumCode    : "SHOPIFY",
                description : "Shopify",
                sequenceNum : 2
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DftCsv"], [
                enumId      : "DftCsv",
                enumTypeId  : "DarpanFileType",
                enumCode    : "CSV",
                description : "CSV",
                sequenceNum : 1
        ])
        upsertEntity(ec, "moqui.basic.Enumeration", [enumId: "DftJson"], [
                enumId      : "DftJson",
                enumTypeId  : "DarpanFileType",
                enumCode    : "JSON",
                description : "JSON",
                sequenceNum : 2
        ])
    }

    private static void upsertEntity(ExecutionContext ec, String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedEntity",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing == null) {
                ec.entity.makeValue(entityName)
                        .setAll(fields)
                        .create()
            }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private static void upsertEntityValue(ExecutionContext ec, String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedEntityValue",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing == null) {
                ec.entity.makeValue(entityName)
                        .setAll(fields)
                        .create()
            }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
