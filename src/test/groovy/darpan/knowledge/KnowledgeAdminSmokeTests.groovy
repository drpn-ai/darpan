package darpan.knowledge

import darpan.admin.AdminAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-161. admin.KnowledgeAdminServices over darpan.knowledge.KnowledgeSupport: staff search and write the knowledge
 * base, and no path returns one tenant's entry to another or lets a tenant's identifiers into the product tier. Every
 * call runs with authz ON as a real super admin on a thread of its own (the AdminServicesSmokeTests pattern), so the
 * admin fence carries no tenant filter and only the explicit tenant conditions keep tenants apart. Tenants, hosts and
 * entries are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeAdminSmokeTests {

    private static final String SVC = "admin.KnowledgeAdminServices"
    private static final String ENTRY = "darpan.knowledge.KnowledgeEntry"
    private static final String ACME = "KB_ACME"
    private static final String BETA = "KB_BETA"
    private static final String SUPER_ID = "KB_SUPER"
    private static final String SUPER_USERNAME = "kb.super"
    private static final String EDITOR_ID = "KB_EDITOR"
    private static final String EDITOR_USERNAME = "kb.editor"
    private static final String SHOPIFY_HOST = "kb-acme-test.myshopify.com"
    private static final String NS_ACCOUNT = "1234567-sb1"
    private static final String WRITES = "darpan.knowledge.allowDirectWrites"
    private static final String PRODUCT_PATH = "darpan.knowledge.productPath"
    private static final Timestamp T0 = Timestamp.valueOf("2026-01-01 00:00:00")

    private ExecutionContext ec
    private Path productRoot

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "knowledge-admin")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()

        upsert("moqui.security.UserAccount", [userId: SUPER_ID, username: SUPER_USERNAME, userFullName: "KB Super",
                emailAddress: "kb.super@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserAccount", [userId: EDITOR_ID, username: EDITOR_USERNAME, userFullName: "KB Editor",
                emailAddress: "kb.editor@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: T0])
        upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: T0])
        [(ACME): "Acme Synthetic", (BETA): "Beta Synthetic"].each { String tenant, String label ->
            upsert("moqui.security.UserGroup", [userGroupId: tenant, description: label,
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        }
        upsert("moqui.security.UserGroupMember", [userGroupId: ACME, userId: EDITOR_ID, fromDate: T0])
        upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: ACME, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: T0])
        upsert("moqui.security.UserPreference", [userId: SUPER_ID, preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY,
                preferenceValue: ACME])
        upsert("moqui.security.UserPreference", [userId: EDITOR_ID, preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY,
                preferenceValue: ACME])
        upsert("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "KB_ACME_SHOP", companyUserGroupId: ACME,
                shopApiUrl: "https://${SHOPIFY_HOST}/admin/api".toString(), apiVersion: "2025-01"])
        upsert("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "KB_BETA_NS", companyUserGroupId: BETA,
                endpointUrl: "https://${NS_ACCOUNT}.restlets.api.netsuite.com/app/site/hosting/restlet.nl".toString()])

        productRoot = Files.createTempDirectory("kb-product-")
        Path oms = Files.createDirectories(productRoot.resolve("product/oms"))
        Path nested = Files.createDirectories(productRoot.resolve("product/shopify/deep"))
        Files.writeString(oms.resolve("walrus-good-one.md"), productFile("walrus-good-one", "Walrus paging rule",
                "The walrus read pages by cursor and retries twice."))
        Files.writeString(oms.resolve("walrus-good-two.md"), productFile("walrus-good-two", "Walrus refund timing",
                "A walrus refund lands after the capture settles."))
        Files.writeString(nested.resolve("walrus-good-three.md"), productFile("walrus-good-three", "Walrus bulk export",
                "Bulk walrus exports finish within the hour."))
        Files.writeString(oms.resolve("walrus-no-header.md"), "# Walrus without a header\n\nThe walrus has no header.\n")
        Files.writeString(oms.resolve("walrus-bad-date.md"), productFile("walrus-bad-date", "Walrus bad date",
                "The walrus date is written the wrong way.").replace("reviewBy: 2099-01-01", "reviewBy: 31/01/2027"))
        Files.writeString(oms.resolve("walrus-bad-email.md"), productFile("walrus-bad-email", "Walrus contact",
                "Write to ops@acme.example about the walrus."))
        Files.writeString(oms.resolve("walrus-bad-tenant.md"), productFile("walrus-bad-tenant", "Walrus owner",
                "The walrus field is set by KB_BETA only."))
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() {
        System.clearProperty(WRITES)
        System.clearProperty(PRODUCT_PATH)
        productRoot?.toFile()?.deleteDir()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        [ENTRY, "darpan.auth.TenantSetting", "darpan.admin.AdminAuditLog", "darpan.config.TenantConfigPending"].each {
            ec.entity.find(it).disableAuthz().deleteAll()
        }
        [ACME, BETA].each { KnowledgeSupport.invalidate(it) }
        System.setProperty(WRITES, "true")
        System.setProperty(PRODUCT_PATH, productRoot.toString())
        ec.message.clearErrors()
    }

    @AfterEach
    void clearWrites() {
        System.clearProperty(WRITES)
    }

    // ---------------------------------------------------------------- tenants apart

    @Test
    void crossTenantSearchNeverLeaks() {
        assertNoErrors(store(BETA, tenantFile("beta-zebra", "Beta joins on the zebra field", "The zebra field joins both sides.")))

        Map asAcme = kb("search#Knowledge", [tenantUserGroupId: ACME, words: "zebra field", limit: 20])
        assertNoErrors(asAcme)
        assertEquals("loaded", asAcme.result.tenantTier)
        assertTrue((asAcme.result.hits as List<Map>).every { it.key != "beta-zebra" }, "KB_ACME never sees KB_BETA's entry: ${asAcme.result.hits}")
        assertTrue((asAcme.result.hits as List<Map>).every { it.tier != "tenant" })

        Map asBeta = kb("search#Knowledge", [tenantUserGroupId: BETA, words: "zebra field", limit: 20])
        assertNoErrors(asBeta)
        List<Map> hits = asBeta.result.hits as List<Map>
        assertEquals(["beta-zebra"], hits*.key)
        assertEquals("tenant", hits[0].tier)
        assertEquals("Beta joins on the zebra field", hits[0].title)

        Map productOnly = kb("search#Knowledge", [words: "zebra field", limit: 20])
        assertEquals("none", productOnly.result.tenantTier)
        assertTrue((productOnly.result.hits as List<Map>).isEmpty(), "no tenant named: the product tier only")
    }

    @Test
    void crossTenantGetFails() {
        assertNoErrors(store(BETA, tenantFile("beta-only", "Beta only entry", "Only the beta tenant has this.")))
        assertNoErrors(store(ACME, tenantFile("shared-key", "Acme copy", "The acme side of a shared key.")))
        assertNoErrors(store(BETA, tenantFile("shared-key", "Beta copy", "The beta side of a shared key.")))

        Map fromAcme = kb("get#KnowledgeEntry", [tenantUserGroupId: ACME, key: "beta-only"])
        assertNoErrors(fromAcme)
        assertNull(fromAcme.result.entry, "KB_ACME cannot read KB_BETA's entry by its key")

        Map fromBeta = kb("get#KnowledgeEntry", [tenantUserGroupId: BETA, key: "beta-only"])
        assertEquals("Beta only entry", (fromBeta.result.entry as Map)?.title, "positive control: the owner reads it")

        assertEquals("Acme copy", (kb("get#KnowledgeEntry", [tenantUserGroupId: ACME, key: "shared-key"]).result.entry as Map).title)
        assertEquals("Beta copy", (kb("get#KnowledgeEntry", [tenantUserGroupId: BETA, key: "shared-key"]).result.entry as Map).title)
        assertNull(kb("get#KnowledgeEntry", [key: "beta-only"]).result.entry, "no tenant named: product only")

        Map unknown = kb("get#KnowledgeEntry", [tenantUserGroupId: "KB_NOPE", key: "beta-only"])
        assertTrue(unknown.errors.any { it.contains("Tenant KB_NOPE was not found.") }, "${unknown.errors}")
    }

    @Test
    void writeNamingAnotherTenantIsRefused() {
        Map refused = store(ACME, tenantFile("names-beta", "Names the other tenant", "Rows here come from KB_BETA as well."))
        assertTrue(refused.hasError as boolean, "naming another tenant's id is refused")
        assertTrue(refused.errors.any { it.contains("tenantId") }, "the refusal names the identifier kind: ${refused.errors}")
        assertFalse(refused.errors.any { it.contains(BETA) }, "the refusal never repeats the matched value: ${refused.errors}")
        assertEquals(0L, entryCount(ACME))

        Map label = store(ACME, tenantFile("names-beta-label", "Names the other label", "Beta Synthetic runs a second store."))
        assertTrue(label.hasError as boolean, "another tenant's label is refused too")
        Map host = store(BETA, tenantFile("names-acme-host", "Names the other host", "Reads come from ${SHOPIFY_HOST} too."))
        assertTrue(host.hasError as boolean, "another tenant's connection host is refused")
        assertEquals(0L, entryCount(BETA))

        Map own = store(ACME, tenantFile("names-acme", "Names itself", "Rows here come from KB_ACME via ${SHOPIFY_HOST}."))
        assertNoErrors(own)
        assertEquals(true, own.result.created)
        assertEquals(1L, entryCount(ACME))
    }

    @Test
    void customerPersonalDataIsRefused() {
        Map email = store(ACME, tenantFile("has-email", "Has an email", "Ask ops@acme.example about it."))
        assertTrue(email.hasError as boolean)
        assertTrue(email.errors.any { it.contains("email") }, "${email.errors}")
        assertFalse(email.errors.any { it.contains("ops@acme.example") })
        assertEquals(0L, entryCount(ACME))

        Map order = store(ACME, tenantFile("has-order", "Has an order id", "Order M1088731 shows the split."))
        assertNoErrors(order)
        assertEquals("has-order", (order.result.entry as Map).key)
        assertEquals(1L, entryCount(ACME))
    }

    // ---------------------------------------------------------------- product tier

    @Test
    void productLoadDropsBadFilesOnly() {
        Map out = kb("search#Knowledge", [words: "walrus", limit: 20])
        assertNoErrors(out)
        assertEquals("loaded", out.result.productTier)
        assertEquals("none", out.result.tenantTier)
        List<Map> hits = out.result.hits as List<Map>
        assertEquals(["walrus-good-one", "walrus-good-three", "walrus-good-two"], (hits*.key as List<String>).sort())
        assertTrue(hits.every { it.tier == "product" })
        ["walrus-bad-date", "walrus-bad-email", "walrus-bad-tenant"].each { String key ->
            assertNull(kb("get#KnowledgeEntry", [key: key]).result.entry, "${key} is never served")
        }
        Map good = kb("get#KnowledgeEntry", [tenantUserGroupId: ACME, key: "walrus-good-two"]).result.entry as Map
        assertEquals("product", good.tier)
        assertEquals("2099-01-01", good.reviewBy)
    }

    @Test
    void productPathAbsent() {
        assertNoErrors(store(ACME, tenantFile("acme-walrus", "Acme walrus note", "The acme walrus is tenant data.")))

        System.clearProperty(PRODUCT_PATH)
        Map unset = kb("search#Knowledge", [tenantUserGroupId: ACME, words: "walrus", limit: 20])
        assertNoErrors(unset)
        assertEquals("absent", unset.result.productTier)
        assertEquals(["acme-walrus"], (unset.result.hits as List<Map>)*.key)

        System.setProperty(PRODUCT_PATH, productRoot.resolve("no-such-dir").toString())
        Map missing = kb("search#Knowledge", [tenantUserGroupId: ACME, words: "walrus", limit: 20])
        assertNoErrors(missing)
        assertEquals("absent", missing.result.productTier)
        assertEquals(["acme-walrus"], (missing.result.hits as List<Map>)*.key)

        System.setProperty(PRODUCT_PATH, productRoot.toString())
        Map back = kb("search#Knowledge", [tenantUserGroupId: ACME, words: "walrus", limit: 20])
        assertEquals("loaded", back.result.productTier)
        assertEquals(4, (back.result.hits as List).size(), "three product hits and the tenant's one, merged")
    }

    // ---------------------------------------------------------------- writes

    @Test
    void writesNeedTheProperty() {
        insertRow("KE_KB_1", ACME, "kept", "Kept entry")
        System.clearProperty(WRITES)

        Map stored = store(ACME, tenantFile("not-allowed", "Not allowed", "Writes are off here."))
        assertTrue(stored.hasError as boolean)
        assertTrue(stored.errors.any { it.contains(WRITES) }, "${stored.errors}")
        assertEquals(1L, entryCount(ACME), "nothing written")

        Map retired = kb("retire#KnowledgeEntry", [tenantUserGroupId: ACME, key: "kept"])
        assertTrue(retired.hasError as boolean)
        assertEquals("ACTIVE", ec.entity.find(ENTRY).condition("knowledgeEntryId", "KE_KB_1").disableAuthz().useCache(false).list()[0].statusId)
    }

    @Test
    void storeMapsPromotedToToPromotedToKey() {
        String md = tenantFile("promo-key", "Promoted lesson", "Rewritten as a product entry.").replace("source: synthetic test\n", "source: synthetic test\npromotedTo: some-product-key\n")
        assertNoErrors(store(ACME, md))
        def row = ec.entity.find(ENTRY).condition("companyUserGroupId", ACME).condition("entryKey", "promo-key").disableAuthz().useCache(false).list()[0]
        assertEquals("some-product-key", row.promotedToKey)
    }

    @Test
    void searchSeesWritesAtOnce() {
        Map first = store(ACME, tenantFile("quick-write", "Quokka timing rule", "The quokka rule changes at noon."))
        assertNoErrors(first)
        assertEquals(true, first.result.created)
        assertEquals(["quick-write"], (kb("search#Knowledge", [tenantUserGroupId: ACME, words: "quokka"]).result.hits as List<Map>)*.key)

        Map again = store(ACME, tenantFile("quick-write", "Quokka timing rule, revised", "The quokka rule changes at midnight."))
        assertNoErrors(again)
        assertEquals(false, again.result.created, "an upsert by tenant and key")
        assertEquals(1L, entryCount(ACME))
        assertEquals(["Quokka timing rule, revised"],
                (kb("search#Knowledge", [tenantUserGroupId: ACME, words: "midnight"]).result.hits as List<Map>)*.title)

        Map retired = kb("retire#KnowledgeEntry", [tenantUserGroupId: ACME, key: "quick-write"])
        assertNoErrors(retired)
        assertEquals(true, retired.result.ok)
        assertTrue((kb("search#Knowledge", [tenantUserGroupId: ACME, words: "quokka"]).result.hits as List).isEmpty())
        assertNull(kb("get#KnowledgeEntry", [tenantUserGroupId: ACME, key: "quick-write"]).result.entry, "a retired entry is not served")

        Map otherTenant = kb("retire#KnowledgeEntry", [tenantUserGroupId: BETA, key: "quick-write"])
        assertTrue(otherTenant.hasError as boolean, "KB_BETA cannot retire KB_ACME's key")

        List audits = ec.entity.find("darpan.admin.AdminAuditLog").condition("targetType", "KnowledgeEntry").disableAuthz().useCache(false).list()
        assertEquals(["${ACME}:quick-write".toString()] * 3, audits*.targetId, "two stores and one retire, each audited; the refusal is not")
    }

    @Test
    void deactivatedTenantHasNoTenantHits() {
        assertNoErrors(store(ACME, tenantFile("acme-heron", "Heron mapping", "The heron field maps one to one.")))
        assertEquals(["acme-heron"], (kb("search#Knowledge", [tenantUserGroupId: ACME, words: "heron"]).result.hits as List<Map>)*.key)

        assertNoErrors(callAs(SUPER_USERNAME, "admin.TenantAdminServices.deactivate#Tenant", [tenantUserGroupId: ACME]))
        Map out = kb("search#Knowledge", [tenantUserGroupId: ACME, words: "heron"])
        assertNoErrors(out)
        assertEquals("deactivated", out.result.tenantTier)
        assertTrue((out.result.hits as List).isEmpty())
    }

    @Test
    void forReviewListsDueEntries() {
        String soon = LocalDate.now().plusDays(5).toString()
        assertNoErrors(store(ACME, tenantFile("due-soon", "Due soon", "Read again shortly.").replace("reviewBy: 2099-01-01", "reviewBy: ${soon}")))
        assertNoErrors(store(ACME, tenantFile("due-later", "Due later", "Read again much later.")))
        assertNoErrors(store(BETA, tenantFile("beta-due", "Beta due", "Beta reads again shortly.").replace("reviewBy: 2099-01-01", "reviewBy: ${soon}")))

        Map out = kb("get#KnowledgeForReview", [tenantUserGroupId: ACME])
        assertNoErrors(out)
        assertEquals(["due-soon"], (out.result.entries as List<Map>)*.key, "within 14 days, this tenant only")
        assertEquals(soon, (out.result.entries as List<Map>)[0].reviewBy)
    }

    // ---------------------------------------------------------------- access and identifiers

    @Test
    void superAdminOnly() {
        Map fenced = callAs(EDITOR_USERNAME, "${SVC}.search#Knowledge".toString(), [words: "walrus"])
        assertNotNull(fenced.authzDenied, "the DARPAN_ADMIN_API fence refuses a tenant editor")

        Map gated = callAs(EDITOR_USERNAME, "${SVC}.search#Knowledge".toString(), [tenantUserGroupId: ACME, words: "walrus"], true)
        assertNull(gated.authzDenied)
        assertEquals([AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE], gated.errors)
        assertNull(gated.result?.hits)
    }

    @Test
    void identifiersCoverTenantsAndHosts() {
        Map out = kb("get#KnowledgeIdentifiers", [:])
        assertNoErrors(out)
        List<Map> ids = out.result.identifiers as List<Map>
        assertNotNull(ids.find { it.value == ACME && it.kind == "tenantId" && it.tenantId == ACME && it.caseSensitive == true })
        assertNotNull(ids.find { it.value == "Acme Synthetic" && it.kind == "label" && it.tenantId == ACME })
        assertNotNull(ids.find { it.value == SHOPIFY_HOST && it.kind == "host" && it.tenantId == ACME })
        assertNotNull(ids.find { it.value == "${NS_ACCOUNT}.restlets.api.netsuite.com" && it.kind == "host" && it.tenantId == BETA })
        assertNotNull(ids.find { it.value == NS_ACCOUNT && it.kind == "account" && it.tenantId == BETA })
        assertTrue(ids.every { it.tenantId != "DARPAN_USER" }, "only company groups are tenants")
    }

    // ---------------------------------------------------------------- helpers

    private static String productFile(String key, String title, String body) {
        return "---\nid: ${key}\ntier: product\nsystem: oms\nkind: api-limit\ntags: walrus\nsource: synthetic test\n" +
                "reviewBy: 2099-01-01\n---\n# ${title}\n\n${body}\n"
    }

    private static String tenantFile(String key, String title, String body) {
        return "---\nid: ${key}\ntier: tenant\nsystem: oms\nkind: join-key\nsource: synthetic test\nreviewBy: 2099-01-01\n---\n" +
                "# ${title}\n\n${body}\n"
    }

    private Map store(String tenant, String markdown) {
        return kb("store#KnowledgeEntry", [tenantUserGroupId: tenant, markdown: markdown])
    }

    private Map kb(String verbNoun, Map params) {
        return callAs(SUPER_USERNAME, "${SVC}.${verbNoun}".toString(), params)
    }

    private long entryCount(String tenant) {
        return ec.entity.find(ENTRY).condition("companyUserGroupId", tenant).disableAuthz().useCache(false).count()
    }

    private void insertRow(String id, String tenant, String key, String title) {
        ec.entity.makeValue(ENTRY).setAll([knowledgeEntryId: id, companyUserGroupId: tenant, entryKey: key, title: title,
                body: "Synthetic body.", sourceText: "synthetic", ownerName: "Aditi Patel", reviewByDate: Date.valueOf("2099-01-01"),
                statusId: "ACTIVE", createdDate: new Timestamp(System.currentTimeMillis())]).create()
    }

    /** One service call on a fresh thread (authz ON, empty artifact stack) as the named user; bypassFence skips the fence only. */
    private Map callAs(String username, String serviceName, Map params, boolean bypassFence = false) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                def call = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params))
                if (bypassFence) call.disableAuthz()
                try {
                    holder.result = call.call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.validationErrors = tec.message.validationErrors*.message
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "knowledge-admin-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static void assertNoErrors(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors} ${out.validationErrors}")
    }

    private void upsert(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
