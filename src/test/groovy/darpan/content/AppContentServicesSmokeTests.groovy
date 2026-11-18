package darpan.content

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-313: the seed loads through the real data loader, a release reload keeps edits and admin rows, the app's read
 * answers only edited SEED rows and ADMIN rows (to a user with no active tenant too), and the DB-backed rules refuse
 * shadowing and unknown routes, conditions and anchors.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppContentServicesSmokeTests {
    private ExecutionContext ec
    private static final String USER_ID = "APPCONT_USER"
    private static final String USERNAME = "appcont.user"
    private static final String GLOSSARY = "MASCOT_GLOSSARY"
    private static final String HINT = "MASCOT_HINT"
    private static final Timestamp T1 = Timestamp.valueOf("2026-11-18 10:00:00")
    private static final Timestamp T2 = Timestamp.valueOf("2026-11-18 11:30:00")

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "app-content-smoke")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ["darpan.content.AppContentEntry", "darpan.content.AppRoute", "darpan.content.AppRouteAnchor", "darpan.content.AppContentSeed",
         "moqui.security.UserGroupMember", "moqui.security.UserPreference"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, username: USERNAME,
                userFullName: "Content User", emailAddress: "appcont@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: USER_ID,
                fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
        ec.message.clearErrors()
    }

    private void loadSeed() { ec.entity.makeDataLoader().location("component://darpan/data/AppContentSeedData.xml").dummyFks(true).load() }

    private Map callAs(String username, String serviceName, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (username != null && !tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params)).call()
                } catch (Throwable e) {
                    holder.denied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "app-content-smoke")
        worker.start(); worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private Map read() {
        Map out = callAs(USERNAME, "facade.AppContentFacadeServices.get#AppContent", [:])
        assertNull(out.denied, "read denied: ${out.denied}")
        assertFalse(out.hasError as boolean, "errors: ${out.errors}")
        return out.result as Map
    }

    private Map row(String kind, String key) {
        def v = ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", kind).condition("contentKey", key).disableAuthz().useCache(false).one()
        return v == null ? null : new LinkedHashMap(v.getMap())
    }

    private void edit(String kind, String key, Map fields) {
        ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", kind).condition("contentKey", key).disableAuthz().one()
                .setAll(fields).update()
    }

    private void createAdmin(String kind, String key, Map fields) {
        ec.entity.makeValue("darpan.content.AppContentEntry").setAll([contentKind: kind, contentKey: key, source: "ADMIN",
                lastChangedDate: T2] + fields).create()
    }

    @Test
    void theGeneratedSeedLoadsThroughTheRealLoader() {
        loadSeed()
        assertTrue(ec.entity.find("darpan.content.AppContentEntry").disableAuthz().count() >= 500)
        assertTrue(ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", HINT).disableAuthz().count() >= 80)
        assertTrue(ec.entity.find("darpan.content.AppRoute").disableAuthz().count() > 20)
        assertTrue(ec.entity.find("darpan.content.AppRouteAnchor").disableAuthz().count() > 1000)
    }

    @Test
    void readsEditedSeedRowsAndAdminRowsOnly() {
        loadSeed()
        assertEquals([], read().entries, "a fresh seed has no edits: nothing to send")
        edit(GLOSSARY, "differenceCount", [title: "Edited title", body: "edited body.", aliases: "diff total|delta count", lastChangedDate: T1])
        createAdmin("MASCOT_ACTION", "admin:export-now", [defaultTitle: null, title: "Export now", body: "starts an export.", aliases: "export now", hidden: "N"])
        createAdmin(HINT, "admin:hub-tip", [body: "Try the tip.", routeName: "hub", conditionKind: "ALWAYS", sequence: 15])
        Map hidden = ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", HINT).condition("source", "SEED").disableAuthz().list().first()
        edit(HINT, hidden.contentKey as String, [hidden: "Y", lastChangedDate: T1])

        Map out = read()
        List<Map> entries = out.entries as List<Map>
        assertEquals(4, entries.size(), "the edited term, the hidden hint and two admin rows: ${entries*.key}")
        Map term = entries.find { it.key == "differenceCount" }
        assertEquals("Edited title", term.title)
        assertEquals("edited body.", term.body)
        assertEquals(["diff total", "delta count"], term.aliases)
        assertFalse(term.containsKey("hidden"), "nulls and false are omitted")
        assertFalse(term.containsKey("matchPattern"))
        Map h = entries.find { it.key == hidden.contentKey }
        assertEquals(true, h.hidden)
        assertEquals(hidden.routeName, h.routeName)
        assertTrue(h.sequence instanceof Integer, "a seed hint's sequence is its default")
        Map admin = entries.find { it.key == "admin:hub-tip" }
        assertEquals(15, admin.sequence)
        assertEquals("ADMIN", admin.source)
        assertEquals(T2.toInstant().toString(), out.version)
        assertTrue(entries.every { it.kind && it.key && it.source })
    }

    @Test
    void aSeedReloadKeepsEditsAndAdminRows() {
        loadSeed()
        edit(GLOSSARY, "differenceCount", [title: "Edited title", body: "edited body.", hidden: "Y", lastChangedDate: T1])
        createAdmin(GLOSSARY, "admin:my-term", [title: "My term", body: "is mine.", aliases: "my term"])
        String before = row(GLOSSARY, "differenceCount").defaultBody

        String xml = """<entity-facade-xml type="darpan-seed"><darpan.content.AppContentEntry contentKind="${GLOSSARY}" contentKey="differenceCount" source="SEED" defaultBody="a brand new default."/></entity-facade-xml>"""
        ec.entity.makeDataLoader().xmlText(xml).dummyFks(true).load()
        loadSeed() // and the whole file again, as a release does

        Map after = row(GLOSSARY, "differenceCount")
        assertEquals("Edited title", after.title)
        assertEquals("edited body.", after.body)
        assertEquals("Y", after.hidden)
        assertEquals(before, after.defaultBody, "the release default is the shipped one again")
        Map admin = row(GLOSSARY, "admin:my-term")
        assertEquals("My term", admin.title)
        assertEquals("ADMIN", admin.source)
        // The partial load alone sets the default and nothing else.
        ec.entity.makeDataLoader().xmlText(xml).dummyFks(true).load()
        Map partial = row(GLOSSARY, "differenceCount")
        assertEquals("a brand new default.", partial.defaultBody)
        assertEquals("Edited title", partial.title)
    }

    @Test
    void aDroppedSeedEntryStopsBeingServedAndShadowing() {
        loadSeed()
        edit(GLOSSARY, "differenceCount", [title: "Edited title", lastChangedDate: T1])
        createAdmin(GLOSSARY, "admin:my-term", [title: "My term", body: "is mine.", aliases: "my term"])
        assertEquals(["admin:my-term", "differenceCount"], (read().entries as List<Map>)*.key.sort())
        // Seed v2: a new mark, and the file no longer has differenceCount.
        String v1 = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toFile(), "runtime/component/darpan/data/AppContentSeedData.xml").text
        String mark = (v1 =~ /seedId="CURRENT" seedMark="([0-9a-f]+)"/)[0][1]
        String v2 = v1.replace(mark, "ffffffffffffffff").readLines().findAll { !it.contains('contentKey="differenceCount"') }.join("\n")
        ec.entity.makeDataLoader().xmlText(v2).dummyFks(true).load()

        assertNotNull(row(GLOSSARY, "differenceCount"), "the row stays until an admin deletes it")
        assertEquals(["admin:my-term"], (read().entries as List<Map>)*.key, "the dropped, edited entry is not served; the ADMIN row is")
        assertEquals([], AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "admin:reuse", source: "ADMIN",
                title: "Reuse", body: "is reused.", aliases: ["differences"]]), "a retired entry's labels no longer shadow")
        assertTrue(AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "admin:dup", source: "ADMIN",
                title: "Dup", body: "is dup.", aliases: ["my term"]]).any { it.contains("already answered") }, "ADMIN rows still count")
    }

    @Test
    void aRowWrittenAroundTheRulesIsNotServed() {
        loadSeed()
        createAdmin(GLOSSARY, "admin:good", [title: "Good", body: "is good.", aliases: "good one"])
        createAdmin(GLOSSARY, "admin:bad", [title: "Bad", body: "has <b>markup</b>", aliases: "bad one"])
        assertEquals(["admin:good"], (read().entries as List<Map>)*.key)
    }

    @Test
    void refusesAShadowingAlias() {
        loadSeed()
        List<String> p = AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "admin:new-term", source: "ADMIN",
                title: "New term", body: "is new.", aliases: ["differences"]])
        assertTrue(p.any { it.contains("already answered") }, "got ${p}")
        // A SEED row's own label is answered too.
        Map own = row(GLOSSARY, "differenceCount")
        assertTrue(AppContentRules.problems(ec, own, [aliases: [own.matchLabels.toString().split(/\|/)[0]]]).any { it.contains("already answered") })
        // A pattern that would answer an existing label.
        List<String> pp = AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "admin:wild", source: "ADMIN",
                title: "Wild", body: "is wild.", matchPattern: "differ*"])
        assertTrue(pp.any { it.contains("already answered") }, "got ${pp}")
        // A clean new term passes.
        assertEquals([], AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "admin:fresh", source: "ADMIN",
                title: "Fresh", body: "is fresh.", aliases: ["zzz fresh label"], matchPattern: "zzz fresh * label"]))
    }

    @Test
    void refusesASeedChangeOutsideItsFourFields() {
        loadSeed()
        Map own = row(GLOSSARY, "differenceCount")
        assertTrue(AppContentRules.problems(ec, own, [matchPattern: "some * pattern"]).any { it.contains("pattern") })
        assertTrue(AppContentRules.problems(ec, own, [contentKey: "other"]).any { it.contains("key cannot change") })
        assertTrue(AppContentRules.problems(ec, own, [source: "ADMIN"]).any { it.contains("source cannot change") })
        assertTrue(AppContentRules.problems(ec, own, [routeName: "hub"]).any { it.contains("cannot change") })
        assertTrue(AppContentRules.problems(ec, null, [contentKind: GLOSSARY, contentKey: "x", source: "SEED", title: "X", body: "x."]).any { it.contains("release") })
        assertEquals([], AppContentRules.problems(ec, own, [title: own.defaultTitle, body: own.defaultBody, hidden: "Y"]), "the default words are valid edits")
    }

    @Test
    void refusesAnUnknownRouteConditionOrAnchor() {
        loadSeed()
        Map seedHint = ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", HINT).condition("conditionKind", "ANCHOR_PRESENT").disableAuthz().list().first()
        String route = seedHint.routeName
        String anchor = seedHint.anchorTestId
        Closure hint = { Map extra ->
            AppContentRules.problems(ec, null, [contentKind: HINT, contentKey: "admin:tip", source: "ADMIN", body: "A tip."] + extra)
        }
        assertEquals([], hint([routeName: route, conditionKind: "ALWAYS", sequence: 5]))
        assertEquals([], hint([routeName: route, conditionKind: "ANCHOR_PRESENT", anchorTestId: anchor, sequence: 0]))
        assertTrue(hint([routeName: "no-such-route", conditionKind: "ALWAYS", sequence: 5]).any { it.contains("unknown route") })
        assertTrue(hint([routeName: route, conditionKind: "SOMETIMES", sequence: 5]).any { it.contains("condition") })
        assertTrue(hint([routeName: route, conditionKind: "ANCHOR_PRESENT", anchorTestId: "no-such-anchor", sequence: 5]).any { it.contains("unknown anchor") })
        assertTrue(hint([routeName: route, conditionKind: "ANCHOR_PRESENT", anchorTestId: "Bad Id", sequence: 5]).any { it.contains("anchor must be") })
        assertTrue(hint([routeName: route, conditionKind: "ALWAYS", anchorTestId: anchor, sequence: 5]).any { it.contains("no anchor") })
        assertTrue(hint([routeName: route, conditionKind: "ALWAYS", sequence: 10000]).any { it.contains("0-9999") })
        assertTrue(hint([routeName: route, conditionKind: "ALWAYS", sequence: -1]).any { it.contains("0-9999") })
        assertTrue(hint([routeName: route, conditionKind: "ALWAYS"]).any { it.contains("sequence") })
        assertTrue(hint([routeName: route, conditionKind: "ALWAYS", sequence: 5, aliases: ["x tip"]]).any { it.contains("no aliases") })
    }

    @Test
    void answersAUserWithNoActiveTenant() {
        loadSeed()
        edit(GLOSSARY, "differenceCount", [title: "Edited title", lastChangedDate: T1])
        assertEquals(0L, ec.entity.find("darpan.auth.TenantUserPermissionGroupMember").condition("userId", USER_ID).disableAuthz().count())
        Map out = read()
        assertEquals(["differenceCount"], (out.entries as List<Map>)*.key)
    }

    @Test
    void refusesAnonymous() {
        loadSeed()
        Map out = callAs(null, "facade.AppContentFacadeServices.get#AppContent", [:])
        assertTrue(out.denied != null || out.hasError as boolean, "an anonymous call must not read: ${out}")
        assertNull(out.result?.entries, "and must not return entries")
    }
}
