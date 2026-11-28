package darpan.facade.common

import darpan.reconciliation.support.FakeEntityFacade
import darpan.reconciliation.support.FakeTenantEc
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for {@link TenantScopedFinder} — P0 #4 step 1 (TDD).
 *
 * <p>All tests are stub-based (no Moqui boot required): {@link FakeTenantEc} as the ExecutionContext,
 * over the shared {@link FakeEntityFacade}, whose finders answer as Moqui's do (a full primary key
 * drops a tenant condition beside it, so {@code findTenantScopedById} must gate the row itself).</p>
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

    private static final String RULE_SET = "darpan.rule.RuleSet"
    private static final String RULE = "darpan.rule.Rule"

    /** An ec whose user EX_USER is active in KREWE, over RuleSet and Rule tables holding {@code ruleSets} and {@code rules}. */
    private static Expando kreweEc(List<Map> ruleSets, List<Map> rules = []) {
        return FakeTenantEc.ec("EX_USER", "KREWE", new FakeEntityFacade()
                .table(RULE_SET, ["ruleSetId"], ruleSets)
                .table(RULE, ["ruleId"], rules))
    }

    // -----------------------------------------------------------------------
    // (a) findTenantScoped with null active tenant → default-deny (EMPTY)
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedWithNoActiveTenantAppliesImpossibleSentinelAndReturnsEmpty() {
        // Arrange: no user logged in → currentActiveTenantUserGroupId returns null.
        def ec = FakeTenantEc.ec(null, null, new FakeEntityFacade()
                .table(RULE_SET, ["ruleSetId"], [[companyUserGroupId: "KREWE", ruleSetId: "RS001"]]))

        // Act
        def result = TenantScopedFinder.findTenantScoped(ec, RULE_SET)
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
        def ec = kreweEc([[companyUserGroupId: "KREWE", ruleSetId: "RS001"], [companyUserGroupId: "GORJANA", ruleSetId: "RS002"]])

        // Act
        def result = TenantScopedFinder.findTenantScoped(ec, RULE_SET)
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
        def ec = kreweEc([[ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]])

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, RULE_SET, "ruleSetId", "RS_ACME")

        // Assert: error raised (cross-tenant forbidden), result must be null (fail-safe — not the foreign record).
        assertTrue(ec.message.hasError(),
                "findTenantScopedById must add a forbidden error when record belongs to a different tenant")
        assertNull(result, "findTenantScopedById must return null for a foreign-tenant record")
    }

    @Test
    void findTenantScopedByIdAddsErrorWhenRecordNotFound() {
        // Arrange: user is in KREWE; PK does not exist.
        def ec = kreweEc([])

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, RULE_SET, "ruleSetId", "MISSING")

        // Assert
        assertNull(result, "findTenantScopedById must return null when record is not found")
        assertTrue(ec.message.hasError(),
                "findTenantScopedById must add a not-found error when record does not exist")
    }

    @Test
    void findTenantScopedByIdSucceedsForOwnedRecord() {
        // Arrange: user is in KREWE; record belongs to KREWE.
        def ec = kreweEc([[ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]])

        // Act
        def result = TenantScopedFinder.findTenantScopedById(ec, RULE_SET, "ruleSetId", "RS_KREWE")

        // Assert
        assertNotNull(result, "findTenantScopedById must return the record for an owned record")
        assertFalse(ec.message.hasError(), "No error must be added for an owned record")
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
        def ec = kreweEc([[ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]], [[ruleId: "RULE_1", ruleSetId: "RS_ACME"]])

        // Act
        def childFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, RULE, RULE_SET, "ruleSetId", "RS_ACME", "ruleSetId"
        )

        // Assert: parent gate denied → null returned, NO error added (quiet contract).
        assertNull(childFinder,
                "findTenantScopedChildren must return null when the parent gate fails (no children leaked)")
        assertFalse(ec.message.hasError(),
                "findTenantScopedChildren must NOT add an ec.message error on foreign-parent denial — callers own the error")
    }

    @Test
    void findTenantScopedChildrenReturnsNullWithNoErrorWhenParentNotFound() {
        // Arrange: user is in KREWE; parent PK does not exist.
        // Denial is SILENT — null returned, NO ec.message error (findTenantScopedByIdQuiet contract).
        def ec = kreweEc([])

        // Act
        def childFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, RULE, RULE_SET, "ruleSetId", "MISSING", "ruleSetId"
        )

        // Assert: missing parent → null returned, NO error added (quiet contract).
        assertNull(childFinder,
                "findTenantScopedChildren must return null when the parent is not found")
        assertFalse(ec.message.hasError(),
                "findTenantScopedChildren must NOT add an ec.message error for a missing parent — callers own the error")
    }

    @Test
    void findTenantScopedChildrenReturnsChildrenFinderWhenParentIsOwned() {
        // Arrange: user is in KREWE; parent and children both belong to KREWE; a child of another set is not returned.
        def ec = kreweEc([[ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]],
                [[ruleId: "RULE_1", ruleSetId: "RS_KREWE"], [ruleId: "RULE_X", ruleSetId: "RS_OTHER"]])

        // Act
        def result = TenantScopedFinder.findTenantScopedChildren(
                ec, RULE, RULE_SET, "ruleSetId", "RS_KREWE", "ruleSetId"
        )

        // Assert
        assertNotNull(result, "findTenantScopedChildren must return a child finder when parent is owned")
        assertFalse(ec.message.hasError(), "No error must be added when parent is owned by active tenant")
        assertTrue(result.list()*.ruleId == ["RULE_1"], "Child finder must return only the owned parent's child rows")
    }

    // -----------------------------------------------------------------------
    // (c2) findTenantScopedByIdQuiet — returns null silently, no ec.message error
    // -----------------------------------------------------------------------

    @Test
    void findTenantScopedByIdQuietReturnNullForForeignRecordWithNoError() {
        // Arrange: user is in KREWE; record belongs to ACME (foreign).
        def ec = kreweEc([[ruleSetId: "RS_ACME", companyUserGroupId: "ACME"]])

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, RULE_SET, "ruleSetId", "RS_ACME")

        // Assert: null returned, and NO error added (contrast with findTenantScopedById which adds one)
        assertNull(result, "findTenantScopedByIdQuiet must return null for a foreign-tenant record")
        assertFalse(ec.message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error on denial — callers handle it themselves")
    }

    @Test
    void findTenantScopedByIdQuietReturnNullForMissingRecordWithNoError() {
        // Arrange: user is in KREWE; PK does not exist.
        def ec = kreweEc([])

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, RULE_SET, "ruleSetId", "MISSING")

        // Assert: null returned, and NO error added
        assertNull(result, "findTenantScopedByIdQuiet must return null for a missing record")
        assertFalse(ec.message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error for a missing record")
    }

    @Test
    void findTenantScopedByIdQuietReturnsRecordForOwnedRecordWithNoError() {
        // Arrange: user is in KREWE; record belongs to KREWE (own).
        def ec = kreweEc([[ruleSetId: "RS_KREWE", companyUserGroupId: "KREWE"]])

        // Act
        def result = TenantScopedFinder.findTenantScopedByIdQuiet(ec, RULE_SET, "ruleSetId", "RS_KREWE")

        // Assert: record returned, no error
        assertNotNull(result, "findTenantScopedByIdQuiet must return the record for an owned record")
        assertFalse(ec.message.hasError(),
                "findTenantScopedByIdQuiet must NOT add any ec.message error for an owned record")
    }

    // -----------------------------------------------------------------------
    // (d) findGlobalUnscoped throws on blank reason
    // -----------------------------------------------------------------------

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsNull() {
        def ec = FakeTenantEc.ec("EX_USER", null)

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", null)
        }
    }

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsBlank() {
        def ec = FakeTenantEc.ec("EX_USER", null)

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", "   ")
        }
    }

    @Test
    void findGlobalUnscopedThrowsWhenReasonIsEmptyString() {
        def ec = FakeTenantEc.ec("EX_USER", null)

        assertThrows(IllegalArgumentException.class) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", "")
        }
    }

    @Test
    void findGlobalUnscopedReturnsUnscopedFinderWhenReasonIsProvided() {
        // Arrange: enum rows with no companyUserGroupId — these would be filtered out by tenant scoping.
        def ec = FakeTenantEc.ec("EX_USER", null, new FakeEntityFacade()
                .table("moqui.basic.Enumeration", ["enumId"], [[enumId: "DftCsv", enumTypeId: "DarpanFileType"]]))

        // Act
        def result = TenantScopedFinder.findGlobalUnscoped(
                ec, "moqui.basic.Enumeration", "framework enum reference data — no tenant scope"
        )

        // Assert: all rows returned without tenant filter.
        assertNotNull(result, "findGlobalUnscoped must return a finder when reason is provided")
        assertFalse(result.list().isEmpty(),
                "findGlobalUnscoped must return rows without imposing a tenant condition")
    }
}
