package darpan.reconciliation.core

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleSetCompareSourceCompositeKeyTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ruleset-composite-key")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
    }

    @Test
    void keyFieldRowsAreReadableInSequenceOrderThroughTheSourceRelationship() {
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([
                ruleSetId: "DARPAN_TEST_CKEY_RS", ruleSetName: "Composite key test", version: "1.0"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", ruleSetId: "DARPAN_TEST_CKEY_RS", objectType: "RETURN_ITEM"
        ]).create()
        // RuleSetCompareSource.systemEnumId and fileTypeEnumId carry real FKs (RSCSR_SYS_ENUM,
        // RSCSR_FTYPE_ENUM) to moqui.basic.Enumeration; this test's own Enumeration.enumTypeId is
        // nullable, so minimal enum rows satisfy the FKs without needing EnumerationType rows too.
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DARPAN_SYS_SHOPIFY"
        ]).create()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([
                enumId: "DftJson"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", systemEnumId: "DARPAN_SYS_SHOPIFY",
                fileTypeEnumId: "DftJson"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", sequenceNum: 2, fieldExpression: "product_id"
        ]).create()
        ec.entity.makeValue("darpan.rule.RuleSetCompareSourceKeyField").setAll([
                compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1", sequenceNum: 1, fieldExpression: "return_id"
        ]).create()

        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition([compareScopeId: "DARPAN_TEST_CKEY_SCOPE", fileSide: "FILE_1"]).one()
        List keyFields = source.findRelated("keyFields", null, ["sequenceNum"], false, false)

        assertEquals(2, keyFields.size())
        assertEquals("return_id", keyFields[0].fieldExpression)
        assertEquals("product_id", keyFields[1].fieldExpression)
        assertFalse(ec.message.hasError())
    }
}
