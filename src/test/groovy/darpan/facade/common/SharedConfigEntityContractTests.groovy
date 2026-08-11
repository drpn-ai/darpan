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

    @Test
    void configTenantAccessDeclaresTheFourPartCompositePrimaryKey() {
        String xml = authEntitiesXml()
        assertTrue(xml.contains('entity-name="ConfigTenantAccess"'),
                "ConfigTenantAccess must be declared in AuthEntities.xml")
        ['configTypeEnumId', 'configId', 'tenantUserGroupId', 'fromDate'].each { String field ->
            assertTrue((xml =~ /(?s)<field name="${field}"[^>]*is-pk="true"/).find(),
                    "${field} must be part of the ConfigTenantAccess primary key")
        }
    }

    @Test
    void configTenantAccessCarriesSoftRevokeAndGrantAudit() {
        String xml = authEntitiesXml()
        assertTrue(xml.contains('<field name="thruDate" type="date-time"/>'),
                "thruDate is the soft-revoke column; revoke must never delete a grant row")
        assertTrue(xml.contains('<field name="grantedByUserId" type="id"/>'),
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
        String xml = authEntitiesXml()
        int start = xml.indexOf('entity-name="ConfigTenantAccess"')
        int end = xml.indexOf('</entity>', start)
        String block = xml.substring(start, end)
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
}
