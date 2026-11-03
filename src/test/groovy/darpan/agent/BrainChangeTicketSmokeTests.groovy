package darpan.agent

import darpan.admin.AdminAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159. Brain change tickets: the agent raises one for a request that needs code; the tenant lists its own; staff
 * list across tenants and move one through RECEIVED, ACCEPTED, DECLINED, SHIPPED inside the admin fence. Synthetic data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrainChangeTicketSmokeTests {

    private static final String FACADE = "facade.AgentChangeFacadeServices"
    private static final String ADMIN = "admin.AgentTicketAdminServices"
    private static final String TICKET = "darpan.agent.BrainChangeTicket"
    private static final String MINE = "KREWE"
    private static final String OTHER = "BCT_OTHER"
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String USERNAME = "test.customer"
    private static final String CONV = "BCT_CONV"
    private static final String CONV_OTHER = "BCT_CONV_O"
    private static final String SUPER_ID = "BCT_SUPER"
    private static final String SUPER_USERNAME = "bct.super"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final List<String> CATEGORIES = ["THRESHOLD_OR_WINDOW", "EXCLUSION", "STATEMENT_SCOPE", "FIX_WORDING", "CAUSE_STANCE",
            "NEW_CHECK_EXISTING_READ", "NEW_OR_WIDER_CAUSE", "MISSING_FIELD", "NEW_SYSTEM_OR_PACK", "PRODUCT_BUG", "DISPLAY"]

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "brain-change-ticket")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("moqui.security.UserGroupMember", [userGroupId: OTHER, userId: USER, fromDate: FROM])
            upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: OTHER, userId: USER,
                    permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM])
            upsert("moqui.security.UserAccount", [userId: SUPER_ID, username: SUPER_USERNAME, userFullName: "BCT Super",
                    emailAddress: "bct.super@example.com", disabled: "N", requirePasswordChange: "N"])
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: FROM])
            upsert("darpan.agent.AgentConversation", [conversationId: CONV, companyUserGroupId: MINE, userId: USER, createdDate: FROM])
            upsert("darpan.agent.AgentConversation", [conversationId: CONV_OTHER, companyUserGroupId: OTHER, userId: USER, createdDate: FROM])
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem { ec.entity.find(TICKET).disableAuthz().deleteAll() }
        actAs(MINE)
    }

    @Test
    @DisplayName("a repeated ticket in one conversation is created once")
    void repeatedTicketIsCreatedOnce() {
        Map first = create(CONV, "MISSING_FIELD", "Show the carrier on every finding.")
        Map again = create(CONV, "MISSING_FIELD", "Show the carrier on every finding.")
        assertFalse(first.hadError as boolean, first.errorText as String)
        assertFalse(again.hadError as boolean, again.errorText as String)
        assertNotNull(first.ticketId)
        assertEquals(first.ticketId, again.ticketId)
        assertEquals(1L, count(MINE))
        Map other = create(CONV, "MISSING_FIELD", "Show the warehouse on every finding.")
        assertNotEquals(first.ticketId, other.ticketId)
        assertEquals(2L, count(MINE))
        def row = asSystem { ec.entity.find(TICKET).condition("ticketId", first.ticketId).useCache(false).one() }
        assertEquals("RECEIVED", row.status)
        assertEquals(MINE, row.companyUserGroupId)
        assertEquals(USER, row.createdByUserId)
        assertNotNull(row.createdDate)
    }

    @Test
    @DisplayName("an email in evidence is stored as <populated>")
    void emailInEvidenceIsPopulated() {
        Map out = create(CONV, "PRODUCT_BUG", "The memo total is wrong.",
                [note: "mail jane.doe@example.com"],
                [orderRef: "M-1", customer: [email: "jane.doe@example.com", phone: "+1 415 555 0134", notes: ["gid://shopify/Order/12345", "plain note"]]],
                [lever: "display"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        def row = asSystem { ec.entity.find(TICKET).condition("ticketId", out.ticketId).useCache(false).one() }
        String evidence = row.evidenceJson
        assertFalse(evidence.contains("jane.doe"), evidence)
        assertFalse(evidence.contains("555"), evidence)
        assertFalse(evidence.contains("gid://shopify"), evidence)
        assertTrue(evidence.contains("<populated>"), evidence)
        assertTrue(evidence.contains("plain note"), evidence)
        assertTrue(evidence.contains("M-1"), evidence)
        assertFalse((row.refsJson as String).contains("jane.doe"), row.refsJson as String)
        assertTrue((row.refsJson as String).contains("<populated>"))
        assertTrue((row.placementJson as String).contains("display"))
    }

    @Test
    @DisplayName("tenant B cannot list tenant A's tickets")
    void otherTenantCannotListTickets() {
        Map a = create(CONV, "DISPLAY", "Make the headline shorter.")
        assertFalse(a.hadError as boolean, a.errorText as String)
        assertEquals([a.ticketId], listIds())
        actAs(OTHER)
        assertEquals([], listIds())
        // and it cannot raise one into tenant A's conversation
        Map crossed = create(CONV, "DISPLAY", "Sneak in.")
        assertTrue(crossed.hadError as boolean)
        assertNull(crossed.ticketId)
        actAs(MINE)
        assertEquals(1L, count(MINE))
        assertEquals(0L, count(OTHER))
        Map filtered = call(FACADE, "list#BrainChangeTickets", [status: "SHIPPED"])
        assertEquals([], filtered.tickets)
    }

    @Test
    @DisplayName("an unknown category is refused and every listed one is accepted")
    void unknownCategoryRefused() {
        Map bad = create(CONV, "FEATURE_REQUEST", "Do something.")
        assertTrue(bad.hadError as boolean)
        assertEquals(0L, count(MINE))
        CATEGORIES.each { String c ->
            Map ok = create(CONV, c, "Please handle ${c}.".toString())
            assertFalse(ok.hadError as boolean, "${c}: ${ok.errorText}")
        }
        assertEquals(CATEGORIES.size() as long, count(MINE))
        assertTrue(create(CONV, "DISPLAY", "").hadError as boolean)
    }

    @Test
    @DisplayName("a non-admin cannot call update#BrainChangeTicket")
    void nonAdminCannotUpdate() {
        String id = create(CONV, "PRODUCT_BUG", "A bug.").ticketId
        // authz ON: the DARPAN_ADMIN_API fence refuses
        Map fenced = onThread(USERNAME, "${ADMIN}.update#BrainChangeTicket", [ticketId: id, status: "ACCEPTED"], false)
        assertNotNull(fenced.authzDenied, "the fence must refuse a non-admin")
        // fence bypassed: the in-service super-admin gate refuses
        Map gated = onThread(USERNAME, "${ADMIN}.update#BrainChangeTicket", [ticketId: id, status: "ACCEPTED"], true)
        assertTrue(gated.hasError as boolean)
        assertTrue((gated.errors as List).contains(AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE), "${gated.errors}")
        assertEquals("RECEIVED", status(id))
        assertNotNull(onThread(USERNAME, "${ADMIN}.list#BrainChangeTickets", [:], false).authzDenied)
    }

    @Test
    @DisplayName("staff list across tenants and move a ticket through its statuses")
    void staffListAndMoveStatuses() {
        String a = create(CONV, "PRODUCT_BUG", "Bug in tenant A.").ticketId
        actAs(OTHER)
        String b = create(CONV_OTHER, "DISPLAY", "Display in tenant B.").ticketId
        actAs(MINE)

        Map all = onThread(SUPER_USERNAME, "${ADMIN}.list#BrainChangeTickets", [:], false)
        assertNull(all.authzDenied)
        assertFalse(all.hasError as boolean, "${all.errors}")
        assertEquals([a, b] as Set, (all.result.tickets as List<Map>)*.ticketId as Set)
        Map one = onThread(SUPER_USERNAME, "${ADMIN}.list#BrainChangeTickets", [tenantUserGroupId: OTHER], false)
        assertEquals([b], (one.result.tickets as List<Map>)*.ticketId)
        assertEquals(OTHER, (one.result.tickets as List<Map>)[0].companyUserGroupId)

        assertTrue(update(a, [status: "DECLINED"]).hasError as boolean, "DECLINED needs a reason")
        assertTrue(update(a, [status: "SHIPPED"]).hasError as boolean, "SHIPPED needs a version")
        assertTrue(update(a, [status: "DONE"]).hasError as boolean, "unknown status")
        assertEquals("RECEIVED", status(a))
        assertFalse(update(a, [status: "ACCEPTED"]).hasError as boolean)
        assertEquals("ACCEPTED", status(a))
        assertFalse(update(a, [status: "SHIPPED", shippedVersion: "v2.2.0"]).hasError as boolean)
        assertEquals("SHIPPED", status(a))
        assertFalse(update(b, [status: "DECLINED", statusReason: "Works as designed."]).hasError as boolean)
        def row = asSystem { ec.entity.find(TICKET).condition("ticketId", a).useCache(false).one() }
        assertEquals("v2.2.0", row.shippedVersion)
        assertEquals("Works as designed.", asSystem { ec.entity.find(TICKET).condition("ticketId", b).useCache(false).one() }.statusReason)
        assertTrue(update("NOPE", [status: "ACCEPTED"]).hasError as boolean)
        assertEquals([a], (onThread(SUPER_USERNAME, "${ADMIN}.list#BrainChangeTickets", [status: "SHIPPED"], false).result.tickets as List<Map>)*.ticketId)
        // the tenant sees the status its ticket reached
        assertEquals("SHIPPED", (call(FACADE, "list#BrainChangeTickets", [:]).tickets as List<Map>)[0].status)
    }

    // ------------------------------------------------------------------ helpers

    private Map create(String conv, String category, String text, Object refs = null, Object evidence = null, Object placement = null) {
        return call(FACADE, "create#BrainChangeTicket", [conversationId: conv, category: category, verbatimText: text,
                refs: refs, evidence: evidence, placement: placement])
    }

    private Map update(String id, Map params) {
        Map out = onThread(SUPER_USERNAME, "${ADMIN}.update#BrainChangeTicket", [ticketId: id] + params, false)
        assertNull(out.authzDenied)
        return out
    }

    private Map call(String svc, String verbNoun, Map params) {
        Map out = ec.service.sync().name("${svc}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearAll()
        return result
    }

    private List<String> listIds() { return (call(FACADE, "list#BrainChangeTickets", [:]).tickets as List<Map>)*.ticketId }

    private long count(String tenant) {
        return asSystem { ec.entity.find(TICKET).condition("companyUserGroupId", tenant).useCache(false).count() }
    }

    private String status(String id) {
        return asSystem { ec.entity.find(TICKET).condition("ticketId", id).useCache(false).one() }.status
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearAll()
    }

    /** One service call as a real user on a thread of its own, authz ON (the AdminServicesSmokeTests pattern). */
    private Map onThread(String username, String serviceName, Map params, boolean bypassFence) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                def c = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params))
                if (bypassFence) c.disableAuthz()
                try { holder.result = c.call() } catch (ArtifactAuthorizationException e) { holder.authzDenied = e }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "bct-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
