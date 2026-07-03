package reconciliation.rule

import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 step 2 — cross-tenant denial tests for {@link RuleEngineSupport}.
 *
 * <p>Verifies that {@code compileRuleSet} and {@code executeRuleSetFacts} fail closed when the
 * requested {@code ruleSetId} belongs to a foreign tenant.  Uses stub-based execution contexts
 * (no Moqui boot required) — the same pattern as {@code TenantScopedFinderTests}.</p>
 */
class RuleEngineTenantDenialTests {

    // -----------------------------------------------------------------------
    // compileRuleSet — foreign ruleSetId → error, no KieContainer
    // -----------------------------------------------------------------------

    @Test
    void compileRuleSetReturnsErrorForForeignTenantRuleSetId() {
        // Arrange: active tenant = KREWE; RuleSet belongs to ACME (foreign)
        def message = new MessageFacadeStub()
        def foreignRuleSet = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME", ruleSetName: "Acme RS"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE"
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignRuleSet),
                ]),
                message: message
        )

        // Act: compileRuleSet should fail closed — no KieContainer compiled for a foreign ruleSet
        Map result = RuleEngineSupport.compileRuleSet(ec, "RS_ACME", false, false)

        // Assert
        assertNotNull(result, "compileRuleSet must return a result map (not null) even on denial")
        assertNotNull(result.error,
                "compileRuleSet must return an error for a foreign-tenant ruleSetId — no compilation")
        assertNull(result.kieContainer,
                "compileRuleSet must NOT return a KieContainer for a foreign-tenant ruleSetId")
    }

    @Test
    void compileRuleSetReturnsErrorWhenRuleSetIdIsMissing() {
        // Arrange: active tenant = KREWE; PK does not exist in DB
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE"
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: null),
                ]),
                message: message
        )

        // Act
        Map result = RuleEngineSupport.compileRuleSet(ec, "RS_MISSING", false, false)

        // Assert: missing PK = same fail-closed result as foreign (not-found)
        assertNotNull(result.error, "compileRuleSet must return an error for a missing ruleSetId")
        assertNull(result.kieContainer, "No KieContainer for a missing ruleSetId")
    }

    // -----------------------------------------------------------------------
    // executeRuleSetFacts — foreign ruleSetId → error, no rule execution
    // -----------------------------------------------------------------------

    @Test
    void executeRuleSetFactsReturnsErrorForForeignTenantRuleSetId() {
        // Arrange: active tenant = KREWE; RuleSet belongs to GORJANA (foreign)
        def message = new MessageFacadeStub()
        def foreignRuleSet = [ruleSetId: "RS_GORJANA", companyUserGroupId: "GORJANA", ruleSetName: "Gorjana RS"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE"
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignRuleSet),
                ]),
                message: message
        )

        // Act
        Map result = RuleEngineSupport.executeRuleSetFacts(ec, "RS_GORJANA", [[key: "value"]], false)

        // Assert: no facts matched, error reported
        assertNotNull(result.error,
                "executeRuleSetFacts must return an error for a foreign-tenant ruleSetId — no rule execution")
    }

    // -----------------------------------------------------------------------
    // executeMatchedPairFacts — foreign ruleSetId → error, no rule execution
    // -----------------------------------------------------------------------

    @Test
    void executeMatchedPairFactsReturnsErrorForForeignTenantRuleSetId() {
        // Arrange: active tenant = KREWE; RuleSet belongs to ACME (foreign)
        def message = new MessageFacadeStub()
        def foreignRuleSet = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME", ruleSetName: "Acme RS"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE"
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe",
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignRuleSet),
                ]),
                message: message
        )

        // Act: executeMatchedPairFacts must fail closed — no rule logic runs for a foreign ruleSet
        Map result = RuleEngineSupport.executeMatchedPairFacts(ec, "RS_ACME", [[key: "value"]])

        // Assert: error reported, no diff results leaked
        assertNotNull(result.error,
                "executeMatchedPairFacts must return an error for a foreign-tenant ruleSetId — no rule execution")
        assertTrue(result.diffResults == null || ((List) result.diffResults).isEmpty(),
                "executeMatchedPairFacts must not leak diff results for a foreign-tenant ruleSetId")
    }

    // -----------------------------------------------------------------------
    // Execution context and stub builders (mirror TenantScopedFinderTests pattern)
    // -----------------------------------------------------------------------

    private static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user   : overrides.user    ?: new UserStub(),
                entity : overrides.entity  ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]

        Object getPreference(String key) { preferences[key] }
        void setPreference(String key, Object value) { preferences[key] = value }
    }

    static class MessageFacadeStub {
        List<String> errors = []
        void addError(String msg) { errors << msg }
        boolean hasError() { !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub f = finders[entityName]
            if (f == null) { f = new FinderStub(); finders[entityName] = f }
            return f
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        List listResult = []

        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        FinderStub conditionDate(String f, String t, Object m) { this }
        FinderStub useCache(boolean v) { this }
        FinderStub disableAuthz() { this }
        FinderStub orderBy(List fields) { this }

        Object one() {
            if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
            return oneResult
        }

        List list() {
            listResult.findAll { row ->
                if (!(row instanceof Map)) return true
                conditions.every { k, v -> row[k] == v }
            }
        }
    }
}
