package darpan.knowledge

import darpan.admin.transfer.ConfigTransferExporter
import darpan.admin.transfer.ConfigTransferImporter
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-161. darpan.knowledge.KnowledgeEntry, the tenant tier of the knowledge base: two tenants may use the same entry
 * key and never see each other's rows; the entries ride a tenant's config versions and an admin transfer bundle. Tenants
 * and entries are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeEntrySmokeTests {

    private static final String ENTRY = "darpan.knowledge.KnowledgeEntry"
    private static final String PENDING = "darpan.config.TenantConfigPending"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String FACADE = "facade.TenantConfigFacadeServices"
    private static final String ACME = "KB_ACME"
    private static final String BETA = "KB_BETA"
    private static final String COPY = "KB_COPY"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    /** A facade service the caller's DARPAN_USER grant covers; its grant is what binds the filter. */
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "knowledge-entry")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // a KREWE editor
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        [ACME, BETA, COPY].each { String t ->
            insert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(),
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            insert("moqui.security.UserGroupMember", [userGroupId: t, userId: EDITOR_ID, fromDate: FROM_DATE])
            insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: t, userId: EDITOR_ID,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        }
        actAs(ACME)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        actAs(ACME)
        asSystem {
            [ENTRY, PENDING, VERSION].each { ec.entity.find(it).deleteAll() }
            entry("KE_ACME_1", ACME, "join-keys", "Join keys").create()
            entry("KE_BETA_1", BETA, "join-keys", "Beta join keys").create()
        }
    }

    @Test
    void sameKeyTwoTenants() {
        asSystem {
            assertEquals(2L, ec.entity.find(ENTRY).condition("entryKey", "join-keys").count())
            List<String> mine = ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).condition("entryKey", "join-keys")
                    .list()*.knowledgeEntryId.collect { it as String }
            assertEquals(["KE_ACME_1"], mine)
        }
    }

    @Test
    void scopeFilterHidesTheOtherTenant() {
        underFacadeGrant {
            List<String> seen = ec.entity.find(ENTRY).useCache(false).list()*.knowledgeEntryId.collect { it as String }
            assertEquals(["KE_ACME_1"], seen, "the active tenant sees only its own rows")
            assertEquals(1L, ec.entity.find(ENTRY).useCache(false).count())
            assertNull(ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_BETA_1").useCache(false).one(),
                    "another tenant's row is hidden even by its full key")
            assertNull(TenantScopedFinder.findOneOwnedByTenant(ec, ENTRY, "knowledgeEntryId", "KE_BETA_1", ACME, "knowledge test"))
            assertNotNull(TenantScopedFinder.findOneOwnedByTenant(ec, ENTRY, "knowledgeEntryId", "KE_ACME_1", ACME, "knowledge test"))
        }
    }

    @Test
    void versionHoldsEntries() {
        Map out = call("snapshot#TenantConfig", [reason: "knowledge"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map version = (Map) out.version
        assertEquals(1, ((Map) version.counts).knowledge)
        Map got = call("get#TenantConfigVersion", [id: version.id])
        Map pack = (Map) ((Map) got.version).pack
        assertEquals(["join-keys"], (pack.knowledge as List<Map>).collect { ((Map) it.fields).entryKey })
        String raw = asSystem { ec.entity.find(VERSION).condition("configVersionId", version.id).one().packJson as String }
        assertFalse(raw.contains("Beta join keys"), "nothing of another tenant")
        assertFalse(raw.contains(BETA))
    }

    @Test
    void saveMarksTenantPending() {
        asSystem { ec.entity.find(PENDING).deleteAll() }
        asSystem { entry("KE_ACME_2", ACME, "second", "Second").create() }
        assertNotNull(asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", ACME).one() })
        assertNull(asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", BETA).one() })
    }

    @Test
    void retiredEntriesStayOutOfTheBundle() {
        asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().set("statusId", "RETIRED").update() }
        Map bundle = asSystem { ConfigTransferExporter.bundle(ec, ACME, [], [], [], "local") }
        assertTrue(((bundle.knowledge ?: []) as List).isEmpty())
    }

    @Test
    void transferRoundTrip() {
        Map bundle = asSystem { ConfigTransferExporter.bundle(ec, ACME, [], [], [], "local") }
        assertNotNull(bundle, ec.message.errorsString)
        assertEquals(1, (bundle.knowledge as List).size())
        asSystem {
            Map preview = ConfigTransferImporter.preview(ec, COPY, bundle, [:])
            assertTrue(((Map) preview.plan).blocking.isEmpty(), "${((Map) preview.plan).blocking}")
            assertNotNull(ConfigTransferImporter.apply(ec, COPY, bundle, [:], preview.planHash as String, "local"), ec.message.errorsString)
            List copied = ec.entity.find(ENTRY).condition("companyUserGroupId", COPY).list()
            assertEquals(["join-keys"], copied*.entryKey)
            assertTrue((copied[0].knowledgeEntryId as String).startsWith("KE_"))
            assertEquals("Join keys", copied[0].title)
            assertEquals(Date.valueOf("2027-01-01"), copied[0].reviewByDate)
            assertEquals(1L, ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).count())
            assertEquals("Join keys", ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().title)
            assertEquals(1L, ec.entity.find(ENTRY).condition("companyUserGroupId", BETA).count())
            ec.entity.find(ENTRY).condition("companyUserGroupId", COPY).deleteAll()
        }
    }

    // ---------------------------------------------------------------- helpers

    private def entry(String id, String tenant, String key, String title) {
        return ec.entity.makeValue(ENTRY).setAll([knowledgeEntryId: id, companyUserGroupId: tenant, entryKey: key, title: title,
                body: "Synthetic body for ${key}.".toString(), sourceText: "synthetic", ownerName: "Aditi Patel",
                reviewByDate: Date.valueOf("2027-01-01"), statusId: "ACTIVE", createdDate: new Timestamp(System.currentTimeMillis())])
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in")
        ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void underFacadeGrant(Closure body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_VIEW, true)
            try { body.call() } finally { ec.artifactExecution.pop(aei) }
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
