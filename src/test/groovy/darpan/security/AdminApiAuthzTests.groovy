package darpan.security

import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static darpan.reconciliation.support.TestHelpers.componentRoot

/**
 * Pins the DARPAN_ADMIN_API fence shape: the admin.* service namespace is granted ONLY to
 * DARPAN_SUPER_ADMIN and framework ADMIN (the two groups that satisfy isSuperAdmin()), and the
 * authz rows carry no ArtifactAuthzFilter (admin services are deliberately cross-tenant).
 */
class AdminApiAuthzTests {

    private static groovy.xml.slurpersupport.GPathResult seedXml() {
        Path root = componentRoot()
        File seed = root.resolve("data/SecuritySeedData.xml").toFile()
        assertTrue(seed.exists(), "SecuritySeedData.xml not found at ${seed.absolutePath}")
        return new groovy.xml.XmlSlurper().parse(seed)
    }

    @Test
    void adminApiGroupHasExactlyOnePatternMemberCoveringAdminServices() {
        def root = seedXml()
        def members = root.'moqui.security.ArtifactGroupMember'.findAll {
            it.@artifactGroupId == 'DARPAN_ADMIN_API'
        }
        assertEquals(1, members.size(), "DARPAN_ADMIN_API must have exactly one member")
        def member = members[0]
        assertEquals('admin\\..*', member.@artifactName.text())
        assertEquals('AT_SERVICE', member.@artifactTypeEnumId.text())
        assertEquals('Y', member.@nameIsPattern.text())
        assertEquals('Y', member.@inheritAuthz.text())
    }

    @Test
    void adminApiIsGrantedOnlyToSuperAdminAndFrameworkAdmin() {
        def root = seedXml()
        def authzRows = root.'moqui.security.ArtifactAuthz'.findAll {
            it.@artifactGroupId == 'DARPAN_ADMIN_API'
        }
        assertEquals(['ADMIN', 'DARPAN_SUPER_ADMIN'],
                authzRows*.@userGroupId*.text().sort(),
                "admin.* may be granted ONLY to DARPAN_SUPER_ADMIN and framework ADMIN")
        authzRows.each { assertEquals('AUTHZA_ALL', it.@authzActionEnumId.text()) }
    }

    @Test
    void adminApiAuthzRowsCarryNoTenantEntityFilter() {
        def root = seedXml()
        def adminAuthzIds = root.'moqui.security.ArtifactAuthz'
                .findAll { it.@artifactGroupId == 'DARPAN_ADMIN_API' }
                .collect { it.@artifactAuthzId.text() }
        // Not vacuous: both admin grants are named, so a zero below means they carry no filter.
        assertEquals(['DARPAN_ADMIN_API_ADMIN', 'DARPAN_ADMIN_API_SUPER_ADMIN'], adminAuthzIds.sort(false),
                "DARPAN_ADMIN_API authz rows must carry their artifactAuthzIds")
        // Positive control: the same lookup finds the tenant filter on the facade grant that does carry it.
        assertTrue(root.'moqui.security.ArtifactAuthzFilter'.any {
            it.@artifactAuthzId.text() == 'DARPAN_FACADE_APP_USER' && it.@entityFilterSetId.text() == 'DARPAN_ACTIVE_COMPANY_SCOPE'
        }, "control: DARPAN_FACADE_APP_USER must carry DARPAN_ACTIVE_COMPANY_SCOPE")
        def filterRefs = root.'moqui.security.ArtifactAuthzFilter'.findAll {
            it.@artifactAuthzId.text() in adminAuthzIds
        }
        assertEquals(0, filterRefs.size(),
                "DARPAN_ADMIN_API authz must NOT carry the tenant entity filter set — admin services are cross-tenant by design")
    }

    @Test
    void superAdminHoldsAdminPasswordPermission() {
        def root = seedXml()
        def rows = root.'moqui.security.UserGroupPermission'.findAll {
            it.@userGroupId == 'DARPAN_SUPER_ADMIN'
        }
        assertTrue(rows.any { it.@userPermissionId.text() == 'ADMIN_PASSWORD' },
                "DARPAN_SUPER_ADMIN needs ADMIN_PASSWORD for privileged password operations")
    }
}
