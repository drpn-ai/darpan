package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-076 — every tenant-owned entity is under the DARPAN_ACTIVE_COMPANY_SCOPE entity filter.
 *
 * <p>Parse-level, no Moqui boot. The runtime half — that the rows actually hide another tenant's
 * data on an authz-enabled facade read — is {@link TenantOwnedEntityFilterRuntimeTests}.</p>
 *
 * <p>The completeness check is the guard that matters: any entity in any component that carries a
 * {@code companyUserGroupId} must either have a filter row or be named in {@link #EXCLUDED} with the
 * reason it cannot or need not have one. A new tenant-owned entity added without a filter fails
 * here, not in production.</p>
 */
class TenantOwnedEntityFilterContractTests {

    private static final String FILTER_SET_ID = "DARPAN_ACTIVE_COMPANY_SCOPE"
    private static final String FILTER_MAP = "[companyUserGroupId: activeTenantUserGroupId]"

    /** DAR-BE-076: the thirteen tenant-owned entities that had a companyUserGroupId but no filter. */
    static final Map<String, String> DAR_BE_076_FILTERS = [
            "darpan.database.DatabaseSourceConfig"                     : "DARPAN_SCOPE_DB_SOURCE_CONFIG",
            "darpan.database.DatabaseSourceQuery"                      : "DARPAN_SCOPE_DB_SOURCE_QUERY",
            "darpan.reconciliation.NsSuiteQlSourceQuery"               : "DARPAN_SCOPE_NS_SUITEQL_QUERY",
            "darpan.reconciliation.Reconciliation"                     : "DARPAN_SCOPE_RECONCILIATION",
            "darpan.reconciliation.ReconciliationRun"                  : "DARPAN_SCOPE_RECON_RUN",
            "darpan.reconciliation.ReconciliationAutomationSourceFilter": "DARPAN_SCOPE_AUTOMATION_SRC_FILTER",
            "darpan.reconciliation.ReconciliationRunStep"              : "DARPAN_SCOPE_RUN_STEP",
            "darpan.reconciliation.SlackOAuthState"                    : "DARPAN_SCOPE_SLACK_OAUTH_STATE",
            "darpan.reconciliation.SlackWorkspaceInstall"              : "DARPAN_SCOPE_SLACK_INSTALL",
            "darpan.reconciliation.SourceConfigEndpointAccess"         : "DARPAN_SCOPE_ENDPOINT_ACCESS",
            "darpan.rule.RuleSetCompareSourceFilter"                   : "DARPAN_SCOPE_RULE_SOURCE_FILTER",
            "darpan.rule.RuleSetConclusionRule"                        : "DARPAN_SCOPE_CONCLUSION_RULE",
            "darpan.rule.RuleSetConclusionCondition"                   : "DARPAN_SCOPE_CONCLUSION_CONDITION",
    ].asImmutable()

    /** DAR-BE-088: the month-close statement catalog, filtered from the day its entities were added. */
    static final Map<String, String> DAR_BE_088_FILTERS = [
            "darpan.monthclose.MonthCloseStatement"                    : "DARPAN_SCOPE_MC_STATEMENT",
            "darpan.monthclose.MonthCloseCheck"                        : "DARPAN_SCOPE_MC_CHECK",
            "darpan.monthclose.MonthCloseStatementCheck"               : "DARPAN_SCOPE_MC_STATEMENT_CHECK",
    ].asImmutable()

    /** DAR-BE-089: the findings ledger. */
    static final Map<String, String> DAR_BE_089_FILTERS = [
            "darpan.monthclose.MonthCloseFinding"                          : "DARPAN_SCOPE_MC_FINDING",
            "darpan.monthclose.MonthCloseFindingRecord"                    : "DARPAN_SCOPE_MC_FINDING_RECORD",
            "darpan.monthclose.MonthCloseFindingCheck"                     : "DARPAN_SCOPE_MC_FINDING_CHECK",
            "darpan.monthclose.MonthCloseFindingEvent"                     : "DARPAN_SCOPE_MC_FINDING_EVENT",
    ].asImmutable()

    /** Every filter row this class pins by id, with its entity. */
    static final Map<String, String> NAMED_FILTERS = (DAR_BE_076_FILTERS + DAR_BE_088_FILTERS + DAR_BE_089_FILTERS).asImmutable()

    /** Entities deliberately NOT in the filter set, each with the reason. */
    static final Map<String, String> EXCLUDED = [
            // Carries companyUserGroupId but cannot be filtered.
            "moqui.gql.GqlApiKey"                                  : "moqui.* entities are skipped by the framework " +
                    "(ArtifactExecutionFacadeImpl.getFindFiltersForUser) — a filter row would be inert",
            // Tenant-named differently: a [companyUserGroupId: ...] filter cannot apply.
            "darpan.auth.ConfigTenantAccess"                       : "peer-group grant keyed by tenantUserGroupId; read only " +
                    "through SharedConfigAccessSupport via findGlobalUnscoped",
            "darpan.reconciliation.SftpServerTenantAccess"         : "sharing grant keyed by tenantUserGroupId",
            "darpan.auth.TenantUserPermissionGroupMember"          : "membership row keyed by tenantUserGroupId; it decides " +
                    "which tenants a user may enter, so it cannot be filtered by the active one",
            // Parent-only children: no tenant column; reached only through a gated parent.
            "darpan.reconciliation.NsSuiteQlSourceQueryField"      : "child of NsSuiteQlSourceQuery, no tenant column",
            "darpan.mapping.ReconciliationMappingMember"           : "child of ReconciliationMapping, no tenant column",
            "darpan.reconciliation.SlackPostedMessage"             : "child of a run result, no tenant column",
            "darpan.reconciliation.ReconciliationRunNotifySubscription": "keyed by the caller's own userId, no tenant column " +
                    "(documented in SecuritySeedData.xml)",
            "darpan.reconciliation.RunDataDocument"                : "child of ReconciliationRun, no tenant column",
            "darpan.reconciliation.RunSystemInstance"              : "child of ReconciliationRun, no tenant column",
    ].asImmutable()

    private static Path componentRoot() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
    }

    private static Node parse(Path file) {
        return new XmlParser(false, false).parse(file.toFile())
    }

    /** entityFilterId -> attributes, for every EntityFilter row in DARPAN_ACTIVE_COMPANY_SCOPE. */
    private static Map<String, Map<String, String>> filterRows(Path file) {
        Map<String, Map<String, String>> rows = [:]
        parse(file).children().findAll { it instanceof Node && it.name() == "moqui.security.EntityFilter" }
                .each { Node row ->
                    if (row.attribute("entityFilterSetId") == FILTER_SET_ID) {
                        rows[row.attribute("entityFilterId") as String] = row.attributes() as Map<String, String>
                    }
                }
        return rows
    }

    private static Map<String, Map<String, String>> seedFilters() {
        return filterRows(componentRoot().resolve("darpan/data/SecuritySeedData.xml"))
    }

    /** Full entity name -> declared field names, across every component's entity XML. */
    private static Map<String, List<String>> entityFields() {
        Map<String, List<String>> out = [:]
        Files.newDirectoryStream(componentRoot()).each { Path component ->
            Path entityDir = component.resolve("entity")
            if (!Files.isDirectory(entityDir)) return
            Files.newDirectoryStream(entityDir, "*.xml").each { Path file ->
                parse(file).children().findAll { it instanceof Node && it.name() == "entity" }.each { Node e ->
                    String full = "${e.attribute('package')}.${e.attribute('entity-name')}".toString()
                    out[full] = e.children().findAll { it instanceof Node && it.name() == "field" }
                            .collect { (it as Node).attribute("name") as String }
                }
            }
        }
        return out
    }

    @Test
    void everyDarBe076EntityHasAFilterRowWithTheStandardFilterMap() {
        Map<String, Map<String, String>> rows = seedFilters()
        NAMED_FILTERS.each { String entityName, String filterId ->
            Map<String, String> row = rows[filterId]
            assertTrue(row != null, "SecuritySeedData.xml is missing EntityFilter ${filterId} for ${entityName}")
            assertEquals(entityName, row.entityName, "${filterId} must filter ${entityName}")
            assertEquals(FILTER_MAP, row.filterMap, "${filterId} must use the same filterMap as every other row")
        }
    }

    @Test
    void everyDarBe076EntityReallyCarriesTheTenantColumn() {
        // The filterMap names companyUserGroupId; on an entity without that field the filter cannot
        // express "this tenant only", so the row would protect nothing.
        Map<String, List<String>> fields = entityFields()
        NAMED_FILTERS.keySet().each { String entityName ->
            assertTrue(fields.containsKey(entityName), "${entityName} is not defined in any component's entity XML")
            assertTrue(fields[entityName].contains("companyUserGroupId"), "${entityName} has no companyUserGroupId field")
        }
    }

    @Test
    void theUpgradePackCarriesTheSameRowsVerbatim() {
        // The seed file only reaches an existing database through the upgrade pack.
        Map<String, Map<String, String>> seed = seedFilters()
        Map<String, Map<String, String>> pack = filterRows(componentRoot().resolve("darpan/data/upgrade-data.xml"))
        NAMED_FILTERS.values().each { String filterId ->
            assertTrue(pack.containsKey(filterId), "upgrade-data.xml is missing EntityFilter ${filterId}")
            assertEquals(seed[filterId], pack[filterId], "upgrade-data.xml must restate ${filterId} verbatim")
        }
    }

    @Test
    void everyEntityWithACompanyUserGroupIdIsFilteredOrExplicitlyExcluded() {
        Set<String> filtered = seedFilters().values().collect { it.entityName as String } as Set<String>
        List<String> unprotected = entityFields().findAll { String entityName, List<String> fieldNames ->
            fieldNames.contains("companyUserGroupId") && !filtered.contains(entityName) && !EXCLUDED.containsKey(entityName)
        }.keySet().sort()
        assertEquals([], unprotected,
                "Tenant-owned entities with no DARPAN_ACTIVE_COMPANY_SCOPE filter. Add a filter row (and the " +
                "same row to upgrade-data.xml), or add the entity to EXCLUDED with the reason it cannot have one.")
    }

    @Test
    void excludedEntitiesExistAreUnfilteredAndHaveAReason() {
        Map<String, List<String>> fields = entityFields()
        Set<String> filtered = seedFilters().values().collect { it.entityName as String } as Set<String>
        EXCLUDED.each { String entityName, String reason ->
            assertTrue(fields.containsKey(entityName), "EXCLUDED names ${entityName}, which no longer exists")
            assertFalse(filtered.contains(entityName), "${entityName} is EXCLUDED but has a filter row")
            assertTrue(reason?.trim()?.length() > 10, "${entityName} needs a real reason for its exclusion")
            if (!entityName.startsWith("moqui.")) {
                assertFalse(fields[entityName].contains("companyUserGroupId"),
                        "${entityName} now has a companyUserGroupId — it should be filtered, not excluded")
            }
        }
    }
}
