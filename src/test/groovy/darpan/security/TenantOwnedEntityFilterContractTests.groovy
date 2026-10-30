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

    /** DAR-BE-088: the audit statement catalog, filtered from the day its entities were added. */
    static final Map<String, String> DAR_BE_088_FILTERS = [
            "darpan.audit.AuditStatement"                    : "DARPAN_SCOPE_AUD_STATEMENT",
            "darpan.audit.AuditCheck"                        : "DARPAN_SCOPE_AUD_CHECK",
            "darpan.audit.AuditStatementCheck"               : "DARPAN_SCOPE_AUD_STATEMENT_CHECK",
    ].asImmutable()

    /** DAR-BE-089: the findings ledger. */
    static final Map<String, String> DAR_BE_089_FILTERS = [
            "darpan.audit.AuditFinding"                          : "DARPAN_SCOPE_AUD_FINDING",
            "darpan.audit.AuditFindingRecord"                    : "DARPAN_SCOPE_AUD_FINDING_RECORD",
            "darpan.audit.AuditFindingCondition"                     : "DARPAN_SCOPE_AUD_FINDING_CHECK",
            "darpan.audit.AuditFindingEvent"                     : "DARPAN_SCOPE_AUD_FINDING_EVENT",
    ].asImmutable()

    /** DAR-BE-103: the audit a statement belongs to. */
    static final Map<String, String> DAR_BE_103_FILTERS = [
            "darpan.audit.Audit"                                         : "DARPAN_SCOPE_AUD_AUDIT",
    ].asImmutable()

    /** DAR-BE-090: check health. */
    static final Map<String, String> DAR_BE_090_FILTERS = [
            "darpan.audit.AuditCheckRun"                         : "DARPAN_SCOPE_AUD_CHECK_RUN",
            "darpan.audit.AuditSourceRead"                       : "DARPAN_SCOPE_AUD_SOURCE_READ",
            "darpan.audit.AuditDigest"                           : "DARPAN_SCOPE_AUD_DIGEST",
    ].asImmutable()

    /** DAR-BE-107: the stored automation streak. */
    static final Map<String, String> DAR_BE_107_FILTERS = [
            "darpan.reconciliation.AutomationStreak"             : "DARPAN_SCOPE_AUTOMATION_STREAK",
    ].asImmutable()

    /** DAR-BE-108: earned streak badges and found side quests. */
    static final Map<String, String> DAR_BE_108_FILTERS = [
            "darpan.reconciliation.AutomationStreakBadge"        : "DARPAN_SCOPE_AUTOMATION_STREAK_BADGE",
            "darpan.reconciliation.TenantStreakQuest"            : "DARPAN_SCOPE_TENANT_STREAK_QUEST",
    ].asImmutable()

    /** DAR-BE-135 — a tenant's own conclusion codes. */
    static final Map<String, String> DAR_BE_135_FILTERS = [
            "darpan.rule.TenantConclusion": "DARPAN_SCOPE_TENANT_CONCLUSION",
    ].asImmutable()

    /** DAR-BE-138 — the astronomy facts a tenant has been told. */
    static final Map<String, String> DAR_BE_138_FILTERS = [
            "darpan.reconciliation.TenantStreakFact": "DARPAN_SCOPE_TENANT_STREAK_FACT",
    ].asImmutable()

    /**
     * Filter rows added since the last release cut. No pack carries them yet: the next cut generates its pack from
     * the previous tag, and data/upgrade-data.xml is not hand-edited between cuts. Remove an entry once a pack ships it.
     */
    static final Map<String, String> PENDING_NEXT_PACK = ["DARPAN_SCOPE_KNOWLEDGE_ENTRY": "DAR-BE-161 knowledge entries; the next release cut generates its pack row",
                                                          "DARPAN_SCOPE_TENANT_SCRIPT": "DAR-BE-164 tenant scripts; the next release cut generates its pack row"].asImmutable()

    /** Every filter row this class pins by id, with its entity. */
    static final Map<String, String> NAMED_FILTERS = (DAR_BE_076_FILTERS + DAR_BE_088_FILTERS + DAR_BE_089_FILTERS + DAR_BE_090_FILTERS + DAR_BE_103_FILTERS + DAR_BE_107_FILTERS + DAR_BE_108_FILTERS + DAR_BE_135_FILTERS + DAR_BE_138_FILTERS).asImmutable()

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

    /**
     * entityFilterId -> attributes as last shipped: the pre-reset packs, then every release pack, each in version
     * order, then the current pack.
     * data/upgrade-data.xml is rebuilt each release from the previous tag, so a row that shipped in an earlier
     * pack is not repeated there (DAR-BE-135 found this test pinning 2.0.0 filters to the 2.1.0 pack).
     */
    private static Map<String, Map<String, String>> shippedFilters() {
        Path data = componentRoot().resolve("darpan/data")
        List<Path> packs = releasePacks(data.resolve("releases/pre-reset")) + releasePacks(data.resolve("releases"))
        packs << data.resolve("upgrade-data.xml")
        Map<String, Map<String, String>> rows = [:]
        packs.each { Path pack -> rows.putAll(filterRows(pack)) }
        return rows
    }

    private static List<Path> releasePacks(Path dir) {
        return Files.list(dir)
                .filter { Path d -> d.fileName.toString() ==~ /\d+(\.\d+)*/ && Files.exists(d.resolve("upgrade-data.xml")) }
                .sorted({ Path a, Path b -> versionOf(a) <=> versionOf(b) } as Comparator<Path>)
                .collect { it.resolve("upgrade-data.xml") }
    }

    private static String versionOf(Path releaseDir) {
        return releaseDir.fileName.toString().tokenize(".").collect { (it as int).toString().padLeft(6, "0") }.join(".")
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
        Map<String, Map<String, String>> pack = shippedFilters()
        PENDING_NEXT_PACK.keySet().each { String filterId ->
            assertTrue(seed.containsKey(filterId), "PENDING_NEXT_PACK names ${filterId}, which the seed does not carry")
            assertFalse(pack.containsKey(filterId), "${filterId} has shipped; remove it from PENDING_NEXT_PACK")
        }
        seed.keySet().findAll { !PENDING_NEXT_PACK.containsKey(it) }.each { String filterId ->
            assertTrue(pack.containsKey(filterId), "no shipped upgrade pack carries EntityFilter ${filterId}; " +
                    "if it was added since the last cut, list it in PENDING_NEXT_PACK")
            assertEquals(seed[filterId], pack[filterId], "the latest pack carrying ${filterId} must restate it verbatim")
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
