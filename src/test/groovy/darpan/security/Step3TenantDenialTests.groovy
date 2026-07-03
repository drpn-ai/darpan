package darpan.security

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * P0 #4 step 3 — cross-tenant denial tests for the 12 migrated bare-disableAuthz sites.
 *
 * <p>Verifies that selected migrated reads fail closed when the requested PK or parent PK belongs
 * to a foreign tenant.  Uses stub-based execution contexts (no Moqui boot required) — same pattern
 * as {@link RuleEngineTenantDenialTests} and {@link darpan.facade.common.TenantScopedFinderTests}.</p>
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
        def foreignRuleSet = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]
        def foreignRule    = [ruleId: "RULE_1", ruleSetId: "RS_ACME"]
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
                        (DarpanEntityConstants.RULE_SET): new FinderStub(oneResult: foreignRuleSet),
                        "darpan.rule.Rule"             : new FinderStub(listResult: [foreignRule]),
                ]),
                message: message
        )

        // Act
        List result = ReconciliationSavedRunSupport.collectRuleRows(ec, "RS_ACME")

        // Assert: parent gate denied (foreign RuleSet) → no rules leaked
        assertTrue(result.isEmpty(),
                "collectRuleRows must return empty for a foreign-tenant ruleSetId — no rules leaked")
    }

    @Test
    void collectRuleRowsReturnsEmptyWhenRuleSetNotFound() {
        // Arrange: active tenant = KREWE; ruleSetId does not exist.
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
                        (DarpanEntityConstants.RULE_SET): new FinderStub(oneResult: null),
                        "darpan.rule.Rule"             : new FinderStub(listResult: []),
                ]),
                message: message
        )

        // Act
        List result = ReconciliationSavedRunSupport.collectRuleRows(ec, "RS_MISSING")

        // Assert
        assertTrue(result.isEmpty(),
                "collectRuleRows must return empty when the ruleSetId does not exist")
    }

    // -----------------------------------------------------------------------
    // ReconciliationSavedRunSupport.buildRuleSetSystemOptions — NsRestletConfig denial
    // -----------------------------------------------------------------------

    @Test
    void buildRuleSetSystemOptionsReturnsNullNsRestletConfigLabelForForeignTenant() {
        // Arrange: active tenant = KREWE; NsRestletConfig belongs to ACME (foreign).
        def foreignNsRestletConfig = [nsRestletConfigId: "NS_ACME", companyUserGroupId: "ACME", description: "Acme NS"]
        def source = [fileSide: "FILE_1", systemEnumId: "NETSUITE", nsRestletConfigId: "NS_ACME"]
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
                        "moqui.basic.Enumeration"                  : new FinderStub(oneResult: null),
                        (DarpanEntityConstants.NS_RESTLET_CONFIG)  : new FinderStub(oneResult: foreignNsRestletConfig),
                        "moqui.service.message.SystemMessageRemote": new FinderStub(oneResult: null),
                ]),
                message: new MessageFacadeStub()
        )

        // Act: call buildRuleSetSystemOptions with a sourceBySide map containing the foreign source
        Map<String, Object> sourceBySide = [FILE_1: source]
        List result = ReconciliationSavedRunSupport.buildRuleSetSystemOptions(ec, sourceBySide)

        // Assert: NsRestletConfig is denied (foreign) → nsRestletConfigLabel must be null/empty (no leak)
        def file1Option = result.find { it?.fileSide == "FILE_1" }
        assertNull(file1Option?.nsRestletConfigLabel,
                "nsRestletConfigLabel must be null when NsRestletConfig belongs to a foreign tenant")
    }

    // -----------------------------------------------------------------------
    // Execution context and stub builders (mirror RuleEngineTenantDenialTests pattern)
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
        FinderStub condition(String field, String op, Object value) { conditions[field] = value; this }
        FinderStub conditionDate(String f, String t, Object m) { this }
        FinderStub useCache(boolean v) { this }
        FinderStub disableAuthz() { this }
        FinderStub orderBy(String fields) { this }
        FinderStub orderBy(List fields) { this }
        FinderStub limit(int n) { this }

        Object one() {
            if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
            return oneResult
        }

        List list() {
            if (listResult == null) return []
            listResult.findAll { row ->
                if (!(row instanceof Map)) return true
                conditions.every { k, v -> row[k] == v }
            }
        }
    }
}
