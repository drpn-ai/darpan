package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 — structural contract for the shared-config grant table.
 *
 * <p>Parse-level checks only (no Moqui boot), mirroring the entity-contract tests added for the
 * exclusion-filter tables. They pin the four properties that silently break sharing if lost:
 * the composite PK, the standalone tenant index (the SFTPACCESS_TENANT lesson — Audit H9.5),
 * the soft-revoke column, and the deliberate ABSENCE of a companyUserGroupId field.</p>
 */
class SharedConfigEntityContractTests {

    private static String authEntitiesXml() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        return backendRoot.resolve("runtime/component/darpan/entity/AuthEntities.xml").toFile().text
    }

    private static String securitySeedXml() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        return backendRoot.resolve("runtime/component/darpan/data/SecuritySeedData.xml").toFile().text
    }

    private static String configTenantAccessEntityBlock() {
        String xml = authEntitiesXml()
        int start = xml.indexOf('entity-name="ConfigTenantAccess"')
        int end = xml.indexOf('</entity>', start)
        return xml.substring(start, end)
    }

    private static String settingsFacadeServicesXml() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        return backendRoot.resolve("runtime/component/darpan/service/facade/SettingsFacadeServices.xml").toFile().text
    }

    /**
     * Extracts a single {@code <service verb="..." noun="...">...</service>} block by its opening
     * tag. SettingsFacadeServices.xml is a large, multi-service file — list#NsRestletConfigs' own
     * auth-metadata join (a documented, out-of-scope Task 5 cosmetic minor / Finding B territory)
     * contains a bare {@code ec.entity.find('darpan.reconciliation.NsAuthConfig')} that a whole-file
     * match would collide with. Scoping to the specific service block is what makes the assertions
     * below match ONLY the two save# services Task 8 Finding A fixed.
     */
    private static String serviceBlock(String xml, String verb, String noun) {
        String startMarker = "<service verb=\"${verb}\" noun=\"${noun}\""
        int start = xml.indexOf(startMarker)
        assertTrue(start >= 0, "Could not find <service verb=\"${verb}\" noun=\"${noun}\"> in SettingsFacadeServices.xml")
        int end = xml.indexOf('</service>', start)
        return xml.substring(start, end)
    }

    @Test
    void configTenantAccessDeclaresTheFourPartCompositePrimaryKey() {
        String block = configTenantAccessEntityBlock()
        assertTrue(block.contains('entity-name="ConfigTenantAccess"'),
                "ConfigTenantAccess must be declared in AuthEntities.xml")
        ['configTypeEnumId', 'configId', 'tenantUserGroupId', 'fromDate'].each { String field ->
            assertTrue((block =~ /(?s)<field name="${field}"[^>]*is-pk="true"/).find(),
                    "${field} must be part of the ConfigTenantAccess primary key")
        }
    }

    @Test
    void configTenantAccessCarriesSoftRevokeAndGrantAudit() {
        String block = configTenantAccessEntityBlock()
        assertTrue((block =~ /(?s)<field name="thruDate" type="date-time"[^>]*\/?>/).find(),
                "thruDate is the soft-revoke column; revoke must never delete a grant row")
        assertTrue((block =~ /(?s)<field name="grantedByUserId" type="id"[^>]*\/?>/).find(),
                "grantedByUserId records who widened credential access")
    }

    @Test
    void configTenantAccessIndexesTenantAloneBecauseItIsNonLeftmostInThePk() {
        String xml = authEntitiesXml()
        assertTrue(xml.contains('<index name="CFGACCESS_TENANT">'),
                "tenantUserGroupId is non-leftmost in the composite PK; 'what is shared with me' " +
                "would full-scan without a standalone index (same defect as SFTPACCESS_TENANT)")
    }

    @Test
    void configTenantAccessHasNoCompanyUserGroupIdField() {
        String block = configTenantAccessEntityBlock()
        assertFalse(block.contains('companyUserGroupId'),
                "ConfigTenantAccess is a peer-group join with no owning tenant of its own; a " +
                "companyUserGroupId would invite TenantScopedFinder.findTenantScoped and break sharing")
    }

    @Test
    void sharedConfigTypeEnumSeedsAllFourShareableConfigTypes() {
        String xml = securitySeedXml()
        assertTrue(xml.contains('enumTypeId="DarpanSharedConfigType"'),
                "DarpanSharedConfigType enum type must be seeded")
        ['SCFG_HOTWAX_OMS', 'SCFG_SHOPIFY_AUTH', 'SCFG_NS_AUTH', 'SCFG_NS_RESTLET'].each { String enumId ->
            assertTrue(xml.contains("enumId=\"${enumId}\""),
                    "${enumId} must be seeded so a grant can name this config type")
        }
    }

    /**
     * DAR-BE-005 seam C. The four shareable config entities are members of the framework
     * EntityFilterSet DARPAN_ACTIVE_COMPANY_SCOPE, which hard-filters them to the active tenant on
     * any authz-enabled read. Every sharing-aware read MUST therefore go through TenantScopedFinder
     * (which disables authz on every method). A future read that forgets would silently drop the
     * shared row, and the symptom — "the shared config vanished" — points nowhere near the cause.
     */
    @Test
    void shareableConfigEntitiesAreStillCoveredByTheActiveCompanyEntityFilter() {
        String xml = securitySeedXml()
        ['darpan.reconciliation.NsAuthConfig',
         'darpan.reconciliation.NsRestletConfig',
         'darpan.hotwax.HotWaxOmsRestSourceConfig',
         'darpan.shopify.ShopifyAuthConfig'].each { String entityName ->
            assertTrue(xml.contains("entityName=\"${entityName}\""),
                    "${entityName} must stay in DARPAN_ACTIVE_COMPANY_SCOPE — sharing widens access " +
                    "through the resolver, NOT by removing the framework tenant filter")
        }
    }

    @Test
    void configTenantAccessIsNotInTheActiveCompanyEntityFilterSet() {
        String xml = securitySeedXml()
        int setStart = xml.indexOf('entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"')
        assertTrue(setStart > 0, "DARPAN_ACTIVE_COMPANY_SCOPE must exist")
        assertFalse(xml.contains('entityName="darpan.auth.ConfigTenantAccess"'),
                "ConfigTenantAccess has no companyUserGroupId; a [companyUserGroupId: ...] filter on it " +
                "cannot work. Its only reader is SharedConfigAccessSupport via findGlobalUnscoped.")
    }

    @Test
    void everySharedConfigReadInDarpanGoesThroughTenantScopedFinder() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path accessSupport = backendRoot.resolve(
                "runtime/component/darpan/src/main/groovy/darpan/facade/common/SharedConfigAccessSupport.groovy")
        String source = accessSupport.toFile().text

        assertFalse(source.contains("ec.entity.find("),
                "SharedConfigAccessSupport must never call ec.entity.find directly — an authz-enabled " +
                "read is silently filtered by DARPAN_ACTIVE_COMPANY_SCOPE and the shared row disappears")
        assertTrue(source.contains("TenantScopedFinder.findGlobalUnscoped"),
                "the cross-tenant grant read is the one justified opt-out and must be explicit")
    }

    /**
     * DAR-BE-005 Task 8 review finding. Finding A's fix has two halves at each of the three sites in
     * {@code save#NsAuthConfig} / {@code save#NsRestletConfig}: the owner-or-shared access gate
     * ({@code SharedConfigAccessSupport.canActiveTenantUseConfig}) AND the read that feeds it
     * ({@code TenantScopedFinder.findGlobalUnscoped} instead of a bare {@code ec.entity.find}). The
     * gate half is exercised end to end by {@code SettingsFacadeSharedConfigSaveTests}. The read half
     * is NOT, and cannot be, exercised by any service-level test in this repo: Moqui's
     * {@code authzDisabled} flag is a single non-scoped boolean on the execution context
     * ({@code ArtifactExecutionFacadeImpl.groovy}), set for the ENTIRE nested service execution
     * before the called service's actions run ({@code ServiceCallSyncImpl.java}). Every test that
     * exercises these two services invokes them via
     * {@code ec.service.sync()...disableAuthz().call()} — both
     * {@code SettingsFacadeSharedConfigSaveTests} and
     * {@code SettingsFacadeTenantFilteringSmokeTests} use exactly that shape. Inside those tests
     * {@code DARPAN_ACTIVE_COMPANY_SCOPE} never applies to ANY nested {@code ec.entity.find}, bare or
     * wrapped, so reverting the read at {@code :607}/{@code :800}/{@code :815} back to a bare
     * {@code ec.entity.find} — while keeping the new gate — would fail no service-level test in the
     * repo. A static source assertion is therefore the ONLY available way to pin this invariant, the
     * same technique {@link #everySharedConfigReadInDarpanGoesThroughTenantScopedFinder} already uses
     * for {@code SharedConfigAccessSupport.groovy}.
     *
     * <p>Scoped to the three service-block sites (via {@link #serviceBlock}), not the whole file: see
     * that method's Javadoc for the unrelated bare find this must not collide with.</p>
     */
    @Test
    void saveNsAuthAndNsRestletConfigResolveTheirRecordsThroughFindGlobalUnscopedNotABareEntityFind() {
        String xml = settingsFacadeServicesXml()
        String saveNsAuthConfig = serviceBlock(xml, "save", "NsAuthConfig")
        String saveNsRestletConfig = serviceBlock(xml, "save", "NsRestletConfig")

        [["save#NsAuthConfig", saveNsAuthConfig], ["save#NsRestletConfig", saveNsRestletConfig]].each { List pair ->
            String label = (String) pair[0]
            String block = (String) pair[1]
            assertFalse(block.contains("ec.entity.find('darpan.reconciliation.NsAuthConfig'") ||
                    block.contains('ec.entity.find("darpan.reconciliation.NsAuthConfig"'),
                    "${label} must not resolve NsAuthConfig via a bare ec.entity.find — " +
                    "DARPAN_ACTIVE_COMPANY_SCOPE silently filters it to the active tenant on any " +
                    "authz-enabled read, hiding a shared-but-foreign-owned row from the " +
                    "owner-or-shared access check before it can even run")
            assertFalse(block.contains("ec.entity.find('darpan.reconciliation.NsRestletConfig'") ||
                    block.contains('ec.entity.find("darpan.reconciliation.NsRestletConfig"'),
                    "${label} must not resolve NsRestletConfig via a bare ec.entity.find — same reason")
        }

        assertTrue(saveNsAuthConfig.contains("TenantScopedFinder.findGlobalUnscoped(ec, 'darpan.reconciliation.NsAuthConfig'"),
                "save#NsAuthConfig's own existence check (site :604) must resolve the record through " +
                "TenantScopedFinder.findGlobalUnscoped so a shared-but-foreign-owned row is visible " +
                "to SharedConfigAccessSupport.canActiveTenantUseConfig")
        assertTrue(saveNsRestletConfig.contains("TenantScopedFinder.findGlobalUnscoped(ec, 'darpan.reconciliation.NsRestletConfig'"),
                "save#NsRestletConfig's own existence check (site :785) must resolve the record " +
                "through TenantScopedFinder.findGlobalUnscoped")
        assertTrue(saveNsRestletConfig.contains("TenantScopedFinder.findGlobalUnscoped(ec, 'darpan.reconciliation.NsAuthConfig'"),
                "save#NsRestletConfig's referenced-auth-config check (site :797) must resolve the " +
                "referenced NsAuthConfig through TenantScopedFinder.findGlobalUnscoped too")
    }

    /**
     * Deploy-safety tripwire (kept by explicit ruling, Aditi 2026-08-11).
     *
     * <p>The backfill widens credential access to a named tenant, using logical ids that have not
     * been confirmed against the live instance. This test pins the UNVERIFIED-IDS marker so the file
     * cannot quietly become "verified" without someone deciding it is. Deleting the marker and
     * deleting this test are the same commit, and that commit is the review gate.</p>
     *
     * <p>It asserts a marker is PRESENT on purpose. A reviewer may read that as an inverted test —
     * it is not: the assertion is "this file still declares itself unverified", which is a real
     * property with a real failure mode (loading unconfirmed tenant ids into production).</p>
     */
    @Test
    void backfillStillDeclaresItsTenantIdsUnverified() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        Path upgradeData = backendRoot.resolve("runtime/component/darpan/data/releases/1.5.0/upgrade-data.xml")
        String xml = upgradeData.toFile().text

        assertTrue(xml.contains("UNVERIFIED-IDS"),
                "The UNVERIFIED-IDS marker is gone, so someone confirmed STEVE_MADDEN / BETSEY_JOHNSON / " +
                "HOTWAX_OMS_SHARED against the live instance. Good — record the verified ids in the " +
                "release notes and delete this test in the same commit. If you did NOT verify them, " +
                "restore the marker: loading unconfirmed tenant ids widens credential access to whoever " +
                "those ids actually name.")
    }
}
