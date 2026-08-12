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
}
