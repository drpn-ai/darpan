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
        [ACME, BETA, COPY].each { KnowledgeSupport.invalidate(it) }
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
        // The mark is async: let reset's own marks land first, so the ones counted below are this save's.
        assertNotNull(awaitPending(BETA), "reset's KB_BETA row marks KB_BETA pending")
        asSystem { ec.entity.find(PENDING).deleteAll() }
        asSystem { entry("KE_ACME_2", ACME, "second", "Second").create() }
        assertNotNull(awaitPending(ACME))
        assertNull(asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", BETA).one() })
    }

    /** The tenant's pending row, polled for up to 10 s (TenantConfigChangeTracker marks it in an async call). */
    private def awaitPending(String tenant) {
        long until = System.currentTimeMillis() + 10_000L
        while (true) {
            def row = asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", tenant).useCache(false).one() }
            if (row != null || System.currentTimeMillis() > until) return row
            Thread.sleep(100L)
        }
    }

    @Test
    void retiredEntriesTravelInTheBundle() {
        asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().set("statusId", "RETIRED").update() }
        Map bundle = asSystem { ConfigTransferExporter.bundle(ec, ACME, [], [], [], "local") }
        assertEquals(["RETIRED"], (bundle.knowledge as List<Map>).collect { ((Map) it.fields).statusId })
        Map none = asSystem { ConfigTransferExporter.bundle(ec, COPY, [], [], [], "local") }
        assertFalse(none.containsKey("knowledge"), "a tenant with no entries carries no knowledge key")
    }

    // ---------------------------------------------------------------- transfer: knowledge stays in its own tenant (R18)

    /** (a) The same tenant id on both sides (local -> prod): the entries arrive, matched by key, and the plan says so. */
    @Test
    void sameTenantTransferWritesEntries() {
        Map bundle = exportAcme()
        asSystem { ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).deleteAll() }
        Map plan = applyBundle(ACME, bundle)
        assertTrue((plan.warnings as List<Map>).any { it.code == "KNOWLEDGE_WRITTEN" &&
                (it.message as String).contains("1 knowledge entries will be written to tenant ${ACME}") }, "${plan.warnings}")
        asSystem {
            List rows = ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).list()
            assertEquals(["join-keys"], rows*.entryKey)
            assertTrue((rows[0].knowledgeEntryId as String).startsWith("KE_"))
            assertEquals("Join keys", rows[0].title)
            assertEquals(Date.valueOf("2027-01-01"), rows[0].reviewByDate)
            assertEquals("Beta join keys", ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_BETA_1").one().title)
        }
    }

    /** (b) Any other target tenant: no entry is written, and the plan says why. */
    @Test
    void otherTenantTransferWritesNoEntries() {
        Map bundle = exportAcme()
        Map plan = applyBundle(COPY, bundle)
        Map warning = (plan.warnings as List<Map>).find { it.code == "KNOWLEDGE_NOT_COPIED" }
        assertEquals("1 knowledge entries not copied: they belong to tenant ${ACME}.".toString(), warning?.message, "${plan.warnings}")
        assertFalse((plan.lines as List<Map>).any { it.kind == "KNOWLEDGE" }, "no knowledge line: ${plan.lines}")
        assertFalse((plan.warnings as List<Map>).any { it.code == "KNOWLEDGE_WRITTEN" })
        asSystem {
            assertEquals(0L, ec.entity.find(ENTRY).condition("companyUserGroupId", COPY).count())
            assertEquals(1L, ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).count())
            assertEquals(1L, ec.entity.find(ENTRY).condition("companyUserGroupId", BETA).count())
        }
    }

    /**
     * (c) bundle.source is caller-supplied: a bundle that names the target as its source but carries another tenant's
     * identifier is still blocked by the store write check, by entry key and reason kind only.
     */
    @Test
    void aBundleClaimingTheTargetStillPassesTheWriteCheck() {
        Map bundle = exportAcme()
        Map rec = (bundle.knowledge as List<Map>)[0]
        ((Map) rec.fields).body = "Rows here also come from ${BETA}.".toString()
        Map plan = asSystem { ConfigTransferImporter.plan(ec, ACME, bundle, [:]) }
        Map refused = (plan.blocking as List<Map>).find { it.code == "KNOWLEDGE_REFUSED" }
        assertNotNull(refused, "${plan.blocking}")
        assertEquals("Knowledge entry join-keys was not imported: it holds another tenant's tenantId.", refused.message)
        assertFalse((refused.message as String).contains(BETA), "never the matched value")

        ((Map) rec.fields).body = "Ask ops@acme.example about it."
        Map email = asSystem { ConfigTransferImporter.preview(ec, ACME, bundle, [:]) }
        assertTrue(((Map) email.plan).blocking.any { it.code == "KNOWLEDGE_REFUSED" && (it.message as String).contains("customer data (email)") })
        assertNull(asSystem { ConfigTransferImporter.apply(ec, ACME, bundle, [:], email.planHash as String, "local") }, "a blocked plan writes nothing")
        ec.message.clearErrors()
        assertEquals("Synthetic body for join-keys.", asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().body })
    }

    /** The version-restore path (ownVersion) runs the same write check. */
    @Test
    void versionRestoreRunsTheWriteCheck() {
        Map bundle = exportAcme()
        ((Map) (bundle.knowledge as List<Map>)[0].fields).body = "Shared with ${BETA}.".toString()
        Map preview = asSystem { ConfigTransferImporter.preview(ec, ACME, bundle, [:], null, true) }
        assertTrue(((Map) preview.plan).blocking.any { it.code == "KNOWLEDGE_REFUSED" }, "${((Map) preview.plan).blocking}")
    }

    /** ownVersion matches knowledge by id, owner-checked: an id that is another tenant's row is never applied to it. */
    @Test
    void aVersionPackCannotLandOnAnotherTenantsRow() {
        Map bundle = exportAcme()
        Map rec = (bundle.knowledge as List<Map>)[0]
        rec.ref = "KNOWLEDGE:KE_BETA_1"
        ((Map) rec.fields).entryKey = "taken-over"
        ((Map) rec.fields).title = "Overwritten"
        Map plan = applyBundle(ACME, bundle, true)
        assertEquals("CREATE", (plan.lines as List<Map>).find { it.ref == "KNOWLEDGE:KE_BETA_1" }?.action, "${plan.lines}")
        asSystem {
            def beta = ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_BETA_1").one()
            assertEquals(BETA, beta.companyUserGroupId)
            assertEquals("Beta join keys", beta.title)
            assertEquals("join-keys", beta.entryKey)
            def copied = ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).condition("entryKey", "taken-over").list()
            assertEquals(1, copied.size())
            assertNotEquals("KE_BETA_1", copied[0].knowledgeEntryId)
        }
    }

    /** I1: a transfer that changes an entry is seen by the next search (the cached tenant index is dropped). */
    @Test
    void transferRefreshesTheTenantIndex() {
        assertEquals(["Join keys"], searchTitles(ACME, "join"))
        Map bundle = exportAcme()
        ((Map) (bundle.knowledge as List<Map>)[0].fields).title = "Join keys revised"
        applyBundle(ACME, bundle)
        assertEquals(["Join keys revised"], searchTitles(ACME, "join"))
    }

    /** I1: a version restore that changes an entry is seen by the next search too. */
    @Test
    void versionRestoreRefreshesTheTenantIndex() {
        Map bundle = exportAcme()
        asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().set("title", "Join keys edited").update() }
        KnowledgeSupport.invalidate(ACME)
        assertEquals(["Join keys edited"], searchTitles(ACME, "join"))
        applyBundle(ACME, bundle, true)
        assertEquals(["Join keys"], searchTitles(ACME, "join"))
    }

    /** I2: a retire on the source reaches the target: the row turns RETIRED and is no longer searched. */
    @Test
    void retireReachesTheTarget() {
        assertEquals(["Join keys"], searchTitles(ACME, "join"))
        asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().set("statusId", "RETIRED").update() }
        Map bundle = exportAcme()
        // the target still holds the entry ACTIVE (and searched), as prod would before the transfer
        asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().set("statusId", "ACTIVE").update() }
        KnowledgeSupport.invalidate(ACME)
        assertEquals(["Join keys"], searchTitles(ACME, "join"))
        Map plan = applyBundle(ACME, bundle)
        assertEquals("UPDATE", (plan.lines as List<Map>).find { it.kind == "KNOWLEDGE" }?.action, "${plan.lines}")
        assertEquals("RETIRED", asSystem { ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_ACME_1").one().statusId })
        assertEquals([], searchTitles(ACME, "join"))
    }

    // ---------------------------------------------------------------- helpers

    private Map exportAcme() {
        Map bundle = asSystem { ConfigTransferExporter.bundle(ec, ACME, [], [], [], "local") }
        assertNotNull(bundle, ec.message.errorsString)
        assertEquals(1, (bundle.knowledge as List).size())
        return bundle
    }

    /** Preview then apply; answers the plan. Fails on a blocking line or a refused apply. */
    private Map applyBundle(String tenant, Map bundle, boolean ownVersion = false) {
        return asSystem {
            Map preview = ConfigTransferImporter.preview(ec, tenant, bundle, [:], null, ownVersion)
            Map plan = (Map) preview.plan
            assertTrue((plan.blocking as List).isEmpty(), "${plan.blocking}")
            assertNotNull(ConfigTransferImporter.apply(ec, tenant, bundle, [:], preview.planHash as String, "local", null,
                    ConfigTransferImporter.SERVICE_NAME, ownVersion), ec.message.errorsString)
            return plan
        }
    }

    private List<String> searchTitles(String tenant, String words) {
        return asSystem { (KnowledgeSupport.search(ec, tenant, words, null, null, 20).hits as List<Map>).collect { it.title as String } }
    }

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
