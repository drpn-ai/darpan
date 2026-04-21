package darpan.reconciliation.support

import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.naming.NameNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ReconciliationSmokeTestSupport {
    private static final String TRANSACTIONAL_DS_NAME = "transactional_DS"

    static ExecutionContext initMoqui(Path backendRoot, String testDbName) {
        String runtimePath = backendRoot.resolve("runtime").toString()
        String testDbPath = backendRoot.resolve("runtime/tmp/test-h2/${testDbName}").toString()

        System.setProperty("moqui.runtime", runtimePath)
        System.setProperty("moqui_runtime", runtimePath)
        System.setProperty("entity_ds_url", "jdbc:h2:${testDbPath};lock_timeout=30000")

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
        assert ec.user.loginAnonymousIfNoUser()
        return ec
    }

    static void cleanupMoqui(ExecutionContext ec) {
        ec?.destroy()
        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
        clearTransactionalDatasourceRegistration()
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
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_COMPARE_RS"], [
                ruleSetId     : "DARPAN_TEST_COMPARE_RS",
                ruleSetName   : "Darpan Test Compare RuleSet",
                description   : "Base RuleSet required by compare-scope smoke fixtures.",
                version       : "1.0",
                explosionPath : "rows",
                primaryKeyPath: "itemId"
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
                primaryKeyPath: "productId"
        ])
        upsertEntity(ec, "darpan.rule.RuleSet", [ruleSetId: "DARPAN_TEST_PRODUCT_BROKEN_RS"], [
                ruleSetId     : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                ruleSetName   : "Darpan Test Broken Product RuleSet",
                description   : "Smoke-test RuleSet for preserving base diffs when DRL compilation fails.",
                version       : "1.0",
                explosionPath : "data.products",
                primaryKeyPath: "productId"
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

    static void seedMappingFixtures(ExecutionContext ec) {
        seedSharedReconciliationEnums(ec)
        upsertEntity(ec, "darpan.mapping.ReconciliationMapping", [reconciliationMappingId: "OrderIdMap"], [
                reconciliationMappingId: "OrderIdMap",
                mappingName            : "Order ID",
                description            : "Order ID field mapping"
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdMapOms"], [
                mappingMemberId         : "OrderIdMapOms",
                reconciliationMappingId : "OrderIdMap",
                systemEnumId            : "OMS",
                fileTypeEnumId          : "DftCsv",
                idFieldExpression       : "order_id"
        ])
        upsertEntity(ec, "darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "OrderIdMapShopify"], [
                mappingMemberId         : "OrderIdMapShopify",
                reconciliationMappingId : "OrderIdMap",
                systemEnumId            : "SHOPIFY",
                fileTypeEnumId          : "DftCsv",
                idFieldExpression       : "order_id"
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
                enumCode    : "OMS",
                description : "OMS",
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
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .one()
            if (existing == null) {
                ec.service.sync()
                        .name("create", entityName)
                        .parameters(fields)
                        .disableAuthz()
                        .call()
            }
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
