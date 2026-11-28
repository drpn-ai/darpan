package darpan.security

import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import org.junit.jupiter.api.Test
import reconciliation.rule.RuleEngineSupportTestEc

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 step 3 — cross-tenant denial tests for the 12 migrated bare-disableAuthz sites.
 *
 * <p>Verifies that selected migrated reads fail closed when the requested PK or parent PK belongs
 * to a foreign tenant.  Uses {@link RuleEngineSupportTestEc} (no Moqui boot required): the shared FakeEntityFacade,
 * whose finders answer as Moqui's do.</p>
 *
 * <p><strong>Coverage note:</strong> Only methods whose first parameter is {@code def ec} (untyped)
 * are reachable from Expando stubs.  Methods that declare {@code ExecutionContext ec} (e.g.
 * {@code ReconciliationServices.resolveRuleSetCompareScopeConfig} and
 * {@code RuleSetCompareScopeAdapter.prepareRuleSetCompareScope}) reject an {@code Expando} at
 * Groovy's runtime dispatch layer and throw {@code MissingMethodException} before even entering
 * the method body — making Expando-based tests structurally impossible for those signatures.
 * Their tenant-scoping guarantees are verified indirectly via {@link darpan.facade.common.TenantScopedFinderTests}
 * and the {@link DisableAuthzRatchetTest} ratchet.</p>
 */
class Step3TenantDenialTests {

    // -----------------------------------------------------------------------
    // ReconciliationSavedRunSupport.collectRuleRows — foreign ruleSetId → empty (no rules leaked)
    // -----------------------------------------------------------------------

    @Test
    void collectRuleRowsReturnsEmptyForForeignRuleSetId() {
        // Arrange: active tenant = KREWE; RuleSet belongs to ACME (foreign).
        def ec = RuleEngineSupportTestEc.ec("KREWE", [ruleSetId: "RS_ACME", companyUserGroupId: "ACME"],
                [[ruleId: "RULE_1", ruleSetId: "RS_ACME"]])

        // Act
        List result = ReconciliationSavedRunSupport.collectRuleRows(ec, "RS_ACME")

        // Assert: parent gate denied (foreign RuleSet) → no rules leaked
        assertTrue(result.isEmpty(),
                "collectRuleRows must return empty for a foreign-tenant ruleSetId — no rules leaked")
    }

    @Test
    void collectRuleRowsReturnsEmptyWhenRuleSetNotFound() {
        // Arrange: active tenant = KREWE; ruleSetId does not exist.
        def ec = RuleEngineSupportTestEc.ec("KREWE", null, [])

        // Act
        List result = ReconciliationSavedRunSupport.collectRuleRows(ec, "RS_MISSING")

        // Assert
        assertTrue(result.isEmpty(),
                "collectRuleRows must return empty when the ruleSetId does not exist")
    }

    // The positive control: without it, both denials above could pass for an unrelated reason.
    @Test
    void collectRuleRowsReturnsTheRulesOfAnOwnedRuleSetInSequenceOrder() {
        def ec = RuleEngineSupportTestEc.ec("KREWE", [ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"],
                [[ruleId: "R2", ruleSetId: "RS_KREWE", sequenceNum: 20], [ruleId: "R1", ruleSetId: "RS_KREWE", sequenceNum: 10],
                 [ruleId: "R9", ruleSetId: "RS_OTHER", sequenceNum: 5]])

        assertEquals(["R1", "R2"], ReconciliationSavedRunSupport.collectRuleRows(ec, "RS_KREWE")*.ruleId)
    }
}
