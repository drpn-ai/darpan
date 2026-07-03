package darpan.facade.common

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for {@link TenantScopedFinder} — P0 #4 step 1 (TDD).
 *
 * <p>All tests are stub-based (no Moqui boot required) and mirror the pattern established in
 * {@link TenantAccessSupportTests}: an {@link Expando} as the ExecutionContext, lightweight inner
 * stub classes for {@code user}, {@code entity}, and {@code message}.</p>
 *
 * <p>Behavioural properties verified:</p>
 * <ol>
 *   <li>{@link TenantScopedFinder#findTenantScoped} with a null active tenant applies the
 *       impossible sentinel condition so the finder returns EMPTY (default-deny, not global rows).
 *   </li>
 *   <li>{@link TenantScopedFinder#findTenantScopedById} adds a forbidden/not-found error when
 *       the record belongs to a different tenant or is missing.</li>
 *   <li>{@link TenantScopedFinder#findTenantScopedChildren} adds an error and returns null when
 *       the parent is not owned by the active tenant.</li>
 *   <li>{@link TenantScopedFinder#findGlobalUnscoped} throws {@link IllegalArgumentException}
 *       when reason is blank or null.</li>
 * </ol>
 */
class TenantScopedFinderTests {

    // -----------------------------------------------------------------------
    // (a) findTenantScoped with null active tenant → default-deny (EMPTY)
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedWithNoActiveTenantAppliesImpossibleSentinelAndReturnsEmpty() {
        // Arrange: no user logged in → currentActiveTenantUserGroupId returns null.
        def owningRecord = [companyUserGroupId: "KREWE", ruleSetId: "RS001"]
        def finder = new FinderStub(listResult: [owningRecord])
        def ec = executionContext(
                user: new UserStub(userId: null),
                entity: new EntityFacadeStub(finders: ["darpan.rule.RuleSet": finder])
        )

        // Act
        def result = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet")
        List rows = result.list()

        // Assert: impossible sentinel prevents global rows from being returned.
        assertTrue(rows.isEmpty(),
                "findTenantScoped with null active tenant must return EMPTY — not global rows")
        assertTrue(
                result.conditions.containsKey("companyUserGroupId"),
                "companyUserGroupId condition must always be applied (impossible sentinel for null tenant)"
        )
        def appliedCondition = result.conditions["companyUserGroupId"]
        assertTrue(
                appliedCondition == TenantScopedFinder.NO_ACTIVE_TENANT_SENTINEL,
                "Null tenant must apply the NO_ACTIVE_TENANT_SENTINEL, not null (which would match global rows). Got: ${appliedCondition}"
        )
    }

    @Test
    void findTenantScopedWithActiveTenantFiltersToTenantRows() {
        // Arrange: user logged in with active tenant KREWE.
        def kreweRecord  = [companyUserGroupId: "KREWE",   ruleSetId: "RS001"]
        def gorjanaRecord = [companyUserGroupId: "GORJANA", ruleSetId: "RS002"]
        def finder = new FinderStub(listResult: [kreweRecord, gorjanaRecord])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": finder,
                ])
        )

        // Act
        def result = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet")
        List rows = result.list()

        // Assert: only KREWE records returned.
        assertFalse(rows.isEmpty(), "findTenantScoped must return rows for valid active tenant")
        assertTrue(rows.every { it.companyUserGroupId == "KREWE" },
                "All returned rows must belong to the active tenant KREWE")
        assertFalse(rows.any { it.companyUserGroupId == "GORJANA" },
                "Rows belonging to a different tenant must be filtered out")
    }

    // -----------------------------------------------------------------------
    // (b) findTenantScopedById raises on a foreign/missing record
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedByIdAddsErrorWhenRecordBelongsToDifferentTenant() {
        // Arrange: user is in KREWE; record belongs to ACME.
        MessageFacadeStub message = new MessageFacadeStub()
        def foreignRecord = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignRecord),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, "darpan.rule.RuleSet", "ruleSetId", "RS_ACME")

        // Assert: error raised (cross-tenant forbidden), result must be null (fail-safe — not the foreign record).
        assertTrue(message.hasError(),
                "findTenantScopedById must add a forbidden error when record belongs to a different tenant")
        assertNull(result, "findTenantScopedById must return null for a foreign-tenant record")
    }

    @Test
    void findTenantScopedByIdAddsErrorWhenRecordNotFound() {
        // Arrange: user is in KREWE; PK does not exist.
        MessageFacadeStub message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: null),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, "darpan.rule.RuleSet", "ruleSetId", "MISSING")

        // Assert
        assertNull(result, "findTenantScopedById must return null when record is not found")
        assertTrue(message.hasError(),
                "findTenantScopedById must add a not-found error when record does not exist")
    }

    @Test
    void findTenantScopedByIdSucceedsForOwnedRecord() {
        // Arrange: user is in KREWE; record belongs to KREWE.
        MessageFacadeStub message = new MessageFacadeStub()
        def ownRecord = [ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: ownRecord),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, "darpan.rule.RuleSet", "ruleSetId", "RS_KREWE")

        // Assert
        assertNotNull(result, "findTenantScopedById must return the record for an owned record")
        assertFalse(message.hasError(), "No error must be added for an owned record")
    }

    // -----------------------------------------------------------------------
    // (c) findTenantScopedChildren raises when the parent isn't owned
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedChildrenReturnsNullWithNoErrorWhenParentIsForeignTenant() {
        // Arrange: user is in KREWE; parent RuleSet belongs to ACME (foreign).
        // Since findTenantScopedChildren now delegates parent lookup to findTenantScopedByIdQuiet,
        // denial is SILENT — null returned, NO ec.message error added.
        // Callers (e.g. resolveRuleSetCompareScopeConfig) translate null into their own error message.
        MessageFacadeStub message = new MessageFacadeStub()
        def foreignParent = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]
        def childRecord   = [ruleId: "RULE_1", ruleSetId: "RS_ACME"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignParent),
                        "darpan.rule.Rule"   : new FinderStub(listResult: [childRecord]),
                ]),
                message: message
        )

        // Act
        def childFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, "darpan.rule.Rule", "darpan.rule.RuleSet", "ruleSetId", "RS_ACME", "ruleSetId"
        )

        // Assert: parent gate denied → null returned, NO error added (quiet contract).
        assertNull(childFinder,
                "findTenantScopedChildren must return null when the parent gate fails (no children leaked)")
        assertFalse(message.hasError(),
                "findTenantScopedChildren must NOT add an ec.message error on foreign-parent denial — callers own the error")
    }

    @Test
    void findTenantScopedChildrenReturnsNullWithNoErrorWhenParentNotFound() {
        // Arrange: user is in KREWE; parent PK does not exist.
        // Denial is SILENT — null returned, NO ec.message error (findTenantScopedByIdQuiet contract).
        MessageFacadeStub message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: null),
                        "darpan.rule.Rule"   : new FinderStub(listResult: []),
                ]),
                message: message
        )

        // Act
        def childFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, "darpan.rule.Rule", "darpan.rule.RuleSet", "ruleSetId", "MISSING", "ruleSetId"
        )

        // Assert: missing parent → null returned, NO error added (quiet contract).
        assertNull(childFinder,
                "findTenantScopedChildren must return null when the parent is not found")
        assertFalse(message.hasError(),
                "findTenantScopedChildren must NOT add an ec.message error for a missing parent — callers own the error")
    }

    @Test
    void findTenantScopedChildrenReturnsChildrenFinderWhenParentIsOwned() {
        // Arrange: user is in KREWE; parent and children both belong to KREWE.
        MessageFacadeStub message = new MessageFacadeStub()
        def ownParent   = [ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]
        def childRecord = [ruleId: "RULE_1", ruleSetId: "RS_KREWE"]
        def childFinder = new FinderStub(listResult: [childRecord])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: ownParent),
                        "darpan.rule.Rule"   : childFinder,
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedChildren(
                ec, "darpan.rule.Rule", "darpan.rule.RuleSet", "ruleSetId", "RS_KREWE", "ruleSetId"
        )

        // Assert
        assertNotNull(result, "findTenantScopedChildren must return a child finder when parent is owned")
        assertFalse(message.hasError(), "No error must be added when parent is owned by active tenant")
        assertFalse(result.list().isEmpty(), "Child finder must return child rows for an owned parent")
    }

    // -----------------------------------------------------------------------
    // (c2) findTenantScopedByIdQuiet — returns null silently, no ec.message error
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedByIdQuietReturnNullForForeignRecordWithNoError() {
        // Arrange: user is in KREWE; record belongs to ACME (foreign).
        MessageFacadeStub message = new MessageFacadeStub()
        def foreignRecord = [ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: foreignRecord),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", "RS_ACME")

        // Assert: null returned, and NO error added (contrast with findTenantScopedById which adds one)
        assertNull(result, "findTenantScopedByIdQuiet must return null for a foreign-tenant record")
        assertFalse(message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error on denial — callers handle it themselves")
    }

    @Test
    void findTenantScopedByIdQuietReturnNullForMissingRecordWithNoError() {
        // Arrange: user is in KREWE; PK does not exist.
        MessageFacadeStub message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: null),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", "MISSING")

        // Assert: null returned, and NO error added
        assertNull(result, "findTenantScopedByIdQuiet must return null for a missing record")
        assertFalse(message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error for a missing record")
    }

    @Test
    void findTenantScopedByIdQuietReturnsRecordForOwnedRecordWithNoError() {
        // Arrange: user is in KREWE; record belongs to KREWE (own).
        MessageFacadeStub message = new MessageFacadeStub()
        def ownRecord = [ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER", preferences: [
                        (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                ]),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: "KREWE", userId: "EX_USER",
                                 description: "Krewe", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID],
                        ]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: ownRecord),
                ]),
                message: message
        )

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", "RS_KREWE")

        // Assert: record returned, no error
        assertNotNull(result, "findTenantScopedByIdQuiet must return the record for an owned record")
        assertFalse(message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error for an owned record")
    }

    // -----------------------------------------------------------------------
    // (d) findGlobalUnscoped throws on blank reason
    // -----------------------------------------------------------------------

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsNull() {
        def ec = executionContext(user: new UserStub(userId: "EX_USER"))

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", null)
        }
    }

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsBlank() {
        def ec = executionContext(user: new UserStub(userId: "EX_USER"))

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", "   ")
        }
    }

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsEmptyString() {
        def ec = executionContext(user: new UserStub(userId: "EX_USER"))

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", "")
        }
    }

    @Test
    void findGlobalUnscopedReturnsUnscopedFinderWhenReasonIsProvided() {
        // Arrange: enum rows with no companyUserGroupId — these would be filtered out by tenant scoping.
        def enumRecord = [enumId: "DftCsv", enumTypeId: "DarpanFileType"]
        def finder = new FinderStub(listResult: [enumRecord])
        def ec = executionContext(
                user: new UserStub(userId: "EX_USER"),
                entity: new EntityFacadeStub(finders: ["moqui.basic.Enumeration": finder])
        )

        // Act
        def result = TenantScopedFinder.findGlobalUnscoped(
                ec, "moqui.basic.Enumeration", "framework enum reference data — no tenant scope"
        )

        // Assert: all rows returned without tenant filter.
        assertNotNull(result, "findGlobalUnscoped must return a finder when reason is provided")
        assertFalse(result.list().isEmpty(),
                "findGlobalUnscoped must return rows without imposing a tenant condition")
    }

    // -----------------------------------------------------------------------
    // Execution context and stub builders
    // -----------------------------------------------------------------------

    private static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user   : overrides.user    ?: new UserStub(),
                entity : overrides.entity  ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                service: overrides.service ?: new ServiceFacadeStub(),
                l10n   : overrides.l10n    ?: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    // ------------------------------------------------------------------
    // Stubs — intentionally minimal; mirrors TenantAccessSupportTests
    // ------------------------------------------------------------------

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

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

    static class ServiceFacadeStub {
        // Not used by TenantScopedFinder; present to satisfy executionContext shape.
    }
}
