package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleSetCompareScopeContractSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ruleset-compare-scope-contract-smoke")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @Test
    void createCsvRunRepairsLegacyObjectTypeNotNullConstraint() {
        forceObjectTypeNotNull()
        assertFalse(isObjectTypeNullable())

        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "Legacy Scope Repair",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        assertTrue(isObjectTypeNullable())
        assertNotNull(result.savedRun?.compareScopeId)
    }

    private void forceObjectTypeNotNull() {
        def entityDefinition = ec.entity.getEntityDefinition("darpan.rule.RuleSetCompareScope")
        String groupName = ec.entity.getEntityGroupName("darpan.rule.RuleSetCompareScope")
        String tableName = entityDefinition.getFullTableName()
        String columnName = entityDefinition.getColumnName("objectType")

        Connection connection = null
        Statement statement = null
        try {
            connection = ec.entity.getConnection(groupName)
            statement = connection.createStatement()
            statement.execute("ALTER TABLE ${tableName} ALTER COLUMN ${columnName} SET NOT NULL")
        } finally {
            statement?.close()
            connection?.close()
        }
    }

    private boolean isObjectTypeNullable() {
        def entityDefinition = ec.entity.getEntityDefinition("darpan.rule.RuleSetCompareScope")
        String groupName = ec.entity.getEntityGroupName("darpan.rule.RuleSetCompareScope")
        String schemaName = entityDefinition.getSchemaName()
        String tableName = entityDefinition.getTableName()
        String columnName = entityDefinition.getColumnName("objectType")

        Connection connection = null
        ResultSet columns = null
        try {
            connection = ec.entity.getConnection(groupName)
            columns = connection.metaData.getColumns(connection.catalog, schemaName, tableName, columnName)
            if (!columns.next()) return true
            return "YES".equalsIgnoreCase(columns.getString("IS_NULLABLE"))
        } finally {
            columns?.close()
            connection?.close()
        }
    }
}
