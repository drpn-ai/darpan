package darpan.content

import darpan.admin.AuditLogTestSupport
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
    private static final String SUPER_ID = "APPCONT_SUPER"
    private static final String SUPER = "appcont.super"
    private static final String TADMIN_ID = "APPCONT_TADMIN"
    private static final String TADMIN = "appcont.tadmin"
    private static final String A = "admin.AppContentAdminServices."
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
        [[SUPER_ID, SUPER, "DARPAN_SUPER_ADMIN"], [TADMIN_ID, TADMIN, "DARPAN_TENANT_ADMIN"]].each { id, name, group ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: id, username: name, userFullName: name,
                    emailAddress: "${name}@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: group, userId: id,
                    fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
        }
        AuditLogTestSupport.wipe(ec, "darpan.admin.AdminAuditLog")
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
                holder.errors = new ArrayList<String>(tec.message.errors) + (tec.message.validationErrors*.message ?: [])
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
        assertTrue(out.version ==~ /[0-9a-f]{16}/, "a hash of what is served: ${out.version}")
        assertEquals(out.version, read().version, "stable across reads")
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

    // ---- DAR-BE-313 Task 3: admin services ----

    private Map adm(String method, Map params, String username = SUPER) { return callAs(username, A + method, params) }

    private Map admOk(String method, Map params) {
        Map out = adm(method, params)
        assertNull(out.denied, "denied: ${out.denied}")
        assertFalse(out.hasError as boolean, "errors: ${out.errors}")
        return out.result as Map
    }

    private List<Map> audit() {
        return ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().orderBy("auditTimestamp").list().collect { new LinkedHashMap(it.getMap()) }
    }

    private Map firstAnchoredSeedHint() {
        return new LinkedHashMap(ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", HINT)
                .condition("conditionKind", "ANCHOR_PRESENT").disableAuthz().orderBy("contentKey").list().first().getMap())
    }

    @Test
    void vocabularyListsKnownRoutesAndAnchors() {
        loadSeed()
        Map out = admOk("get#AppContentVocabulary", [:])
        List<Map> routes = out.routes as List<Map>
        assertTrue(routes.size() > 20)
        Map seedHint = firstAnchoredSeedHint()
        Map r = routes.find { it.routeName == seedHint.routeName }
        assertNotNull(r)
        assertTrue((r.routePath as String).startsWith("/"))
        assertTrue((r.anchors as List).contains(seedHint.anchorTestId))
        assertEquals(["ALWAYS", "ANCHOR_PRESENT"], out.conditionKinds)
        assertEquals([renderedMax: 200, hintMax: 160, aliasMax: 60, aliasesMax: 10, patternMax: 60, wildcardsMax: 2], out.limits)
    }

    @Test
    void aNewHintOnAKnownRouteIsServed() {
        loadSeed()
        Map out = admOk("create#AppContentEntry", [kind: HINT, slug: "download-tip", body: "Download the file to keep a copy.",
                routeName: "reconciliation-run-result", conditionKind: "ANCHOR_PRESENT", anchorTestId: "run-result-download", sequence: 15])
        assertEquals("admin:download-tip", out.key)
        assertEquals([], out.problems)
        assertTrue((out.whereShown as String).contains("when run-result-download is on the page"), "${out.whereShown}")
        assertTrue((out.whereShown as String) ==~ /.*position \d+ of \d+.*/, "${out.whereShown}")
        Map served = (read().entries as List<Map>).find { it.key == "admin:download-tip" }
        assertNotNull(served)
        assertEquals("ADMIN", served.source)
        assertEquals(15, served.sequence)
        assertEquals("reconciliation-run-result", served.routeName)
        assertEquals(SUPER_ID, row(HINT, "admin:download-tip").createdByUserId)
    }

    @Test
    void createRefusesAnUnknownRouteConditionOrAnchor() {
        loadSeed()
        Map seedHint = firstAnchoredSeedHint()
        long before = ec.entity.find("darpan.content.AppContentEntry").disableAuthz().count()
        [[routeName: "no-such-route", conditionKind: "ALWAYS"], [routeName: seedHint.routeName, conditionKind: "SOMETIMES"],
         [routeName: seedHint.routeName, conditionKind: "ANCHOR_PRESENT", anchorTestId: "no-such-anchor"]].each { Map bad ->
            Map out = admOk("create#AppContentEntry", [kind: HINT, slug: "bad-tip", body: "A tip.", sequence: 5] + bad)
            assertFalse((out.problems as List).isEmpty(), "must be refused: ${bad}")
            assertNull(out.whereShown)
        }
        assertEquals(before, ec.entity.find("darpan.content.AppContentEntry").disableAuthz().count())
        assertEquals([], audit())
    }

    @Test
    void createRefusesAMalformedAliasOrPattern() {
        loadSeed()
        long before = ec.entity.find("darpan.content.AppContentEntry").disableAuthz().count()
        [[aliases: ["a|b"]], [aliases: [""]], [matchPattern: "nostar"], [matchPattern: "a*b*c*d"],
         [aliases: ["differences"]], [matchPattern: "differ*"]].each { Map bad ->
            Map out = admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "bad-term", title: "Bad", body: "is bad."] + bad)
            assertFalse((out.problems as List).isEmpty(), "must be refused: ${bad}")
        }
        assertEquals(before, ec.entity.find("darpan.content.AppContentEntry").disableAuthz().count())
        // markup reaches the rules (allow-html) and comes back in problems like every other refusal
        Map html = admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "html", title: "Html", body: "is <b>html</b>.", aliases: ["<b>x</b>"], matchPattern: "a<b>*zzzz"])
        assertTrue((html.problems as List).any { it.toString().contains("cannot contain < or >") }, "${html.problems}")
        assertNull(row(GLOSSARY, "admin:html"))
        assertTrue((admOk("update#AppContentEntry", [kind: GLOSSARY, key: "differenceCount", title: "<i>x</i>", aliases: ["<b>x</b>"]]).problems as List).any { it.toString().contains("<") })
        assertTrue((admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "x y", title: "Bad", body: "is bad.", aliases: ["zzz one"]]).problems as List).any { it.toString().contains("key") })
    }

    @Test
    void dryRunSavesNothingAndPreviewsWhereShown() {
        loadSeed()
        Map out = admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "dry-term", title: "Dry term", body: "is dry.",
                aliases: ["dry label", "wet label"], dryRun: true])
        assertEquals([], out.problems)
        assertEquals("admin:dry-term", out.key)
        assertTrue((out.whereShown as String).startsWith("answers labels: dry label"), "${out.whereShown}")
        assertNull(row(GLOSSARY, "admin:dry-term"))
        assertEquals([], audit())
        // update dry run too
        Map own = row(GLOSSARY, "differenceCount")
        Map up = admOk("update#AppContentEntry", [kind: GLOSSARY, key: "differenceCount", title: "Zzz", dryRun: true])
        assertEquals([], up.problems)
        assertNull(row(GLOSSARY, "differenceCount").title)
        assertEquals(own.defaultTitle, row(GLOSSARY, "differenceCount").defaultTitle)
        assertEquals([], audit())
    }

    @Test
    void aNewGlossaryTermAndActionAreServed() {
        loadSeed()
        Map g = admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "pace-count", title: "Pace count", body: "is how many ran.",
                aliases: ["pace count", "pace tally"], matchPattern: "pace * total"])
        assertEquals([], g.problems)
        assertTrue((g.whereShown as String).startsWith("answers labels:"), "${g.whereShown}")
        Map a = admOk("create#AppContentEntry", [kind: "MASCOT_ACTION", slug: "pace-now", title: "Pace now", body: "starts a pace run.", aliases: ["pace now"]])
        assertEquals([], a.problems)
        assertTrue((a.whereShown as String).startsWith("answers controls named"), "${a.whereShown}")
        List<Map> entries = read().entries as List<Map>
        Map term = entries.find { it.key == "admin:pace-count" }
        assertEquals(["pace count", "pace tally"], term.aliases)
        assertEquals("pace * total", term.matchPattern)
        assertNotNull(entries.find { it.key == "admin:pace-now" && it.kind == "MASCOT_ACTION" })
        // the same key twice is refused
        assertTrue((admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "pace-count", title: "Pace count", body: "is again.", aliases: ["zzz again"]]).problems as List).any { it.toString().contains("exists") })
    }

    @Test
    void updateCannotRebindASeedRow() {
        loadSeed()
        Map seedHint = firstAnchoredSeedHint()
        Map out = admOk("update#AppContentEntry", [kind: HINT, key: seedHint.contentKey, routeName: "hub", sequence: 1])
        assertFalse((out.problems as List).isEmpty())
        Map after = row(HINT, seedHint.contentKey as String)
        assertEquals(seedHint.routeName, after.routeName)
        assertEquals(seedHint.sequence, after.sequence)
        assertEquals([], audit())
        // an ADMIN row may be rebound
        admOk("create#AppContentEntry", [kind: HINT, slug: "move-me", body: "Move me.", routeName: "hub", conditionKind: "ALWAYS", sequence: 15])
        Map moved = admOk("update#AppContentEntry", [kind: HINT, key: "admin:move-me", routeName: seedHint.routeName, conditionKind: "ANCHOR_PRESENT", anchorTestId: seedHint.anchorTestId, sequence: 16])
        assertEquals([], moved.problems)
        assertEquals(seedHint.routeName, row(HINT, "admin:move-me").routeName)
    }

    @Test
    void hideASeedRowThenReset() {
        loadSeed()
        Map dflt = row(GLOSSARY, "differenceCount")
        Map out = admOk("update#AppContentEntry", [kind: GLOSSARY, key: "differenceCount", title: "Another title", body: dflt.defaultBody, hidden: true, aliases: ["diff total"]])
        assertEquals([], out.problems)
        Map r = row(GLOSSARY, "differenceCount")
        assertEquals("Another title", r.title)
        assertNull(r.body, "a body equal to its default is stored as null")
        assertEquals("Y", r.hidden)
        assertEquals("diff total", r.aliases)
        assertEquals(true, (read().entries as List<Map>).find { it.key == "differenceCount" }.hidden)
        // title equal to default clears it
        admOk("update#AppContentEntry", [kind: GLOSSARY, key: "differenceCount", title: dflt.defaultTitle])
        assertNull(row(GLOSSARY, "differenceCount").title)

        Map rs = admOk("reset#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        assertEquals([], rs.problems ?: [])
        Map back = row(GLOSSARY, "differenceCount")
        assertTrue(back.title == null && back.body == null && back.aliases == null && back.hidden == null, "${back}")
        assertEquals([], read().entries)
        assertEquals(dflt.defaultBody, back.defaultBody)
        // reset of an ADMIN row is refused
        admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "mine", title: "Mine", body: "is mine.", aliases: ["zzz mine"]])
        assertTrue(adm("reset#AppContentEntry", [kind: GLOSSARY, key: "admin:mine"]).hasError as boolean)
    }

    @Test
    void deleteRefusesASeedRowButTakesAdminAndRetiredRows() {
        loadSeed()
        Map out = adm("delete#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        assertTrue(out.hasError as boolean)
        assertTrue((out.errors as List).any { it.toString().contains("hide it instead") }, "${out.errors}")
        assertNotNull(row(GLOSSARY, "differenceCount"))
        admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "gone", title: "Gone", body: "is gone.", aliases: ["zzz gone"]])
        admOk("delete#AppContentEntry", [kind: GLOSSARY, key: "admin:gone"])
        assertNull(row(GLOSSARY, "admin:gone"))
        // a seed row a later release dropped is retired and may be deleted
        ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", GLOSSARY).condition("contentKey", "differenceCount").disableAuthz().one()
                .setAll([seedMark: "0000000000000000"]).update()
        admOk("delete#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        assertNull(row(GLOSSARY, "differenceCount"))
        assertTrue(adm("delete#AppContentEntry", [kind: GLOSSARY, key: "admin:nope"]).hasError as boolean)
    }

    @Test
    void theListFlagsRetiredAndShadowedRows() {
        loadSeed()
        admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "shadow", title: "Shadow", body: "is shadow.", aliases: ["zzz shadow"]])
        Map list = admOk("list#AppContent", [:])
        assertTrue((list.entries as List).size() > 500)
        Map one = (list.entries as List<Map>).find { it.key == "admin:shadow" }
        assertEquals("ADMIN", one.source)
        assertEquals(false, one.retired)
        assertNull(one.shadowedBy)
        // a release later answers the same label: the ADMIN row is shadowed by it
        Map seed = row(GLOSSARY, "differenceCount")
        ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", GLOSSARY).condition("contentKey", "differenceCount").disableAuthz().one()
                .setAll([matchLabels: seed.matchLabels + "|zzz shadow"]).update()
        Map after = (admOk("list#AppContent", [kind: GLOSSARY, source: "ADMIN"]).entries as List<Map>).find { it.key == "admin:shadow" }
        assertEquals("differenceCount", after.shadowedBy)
        assertTrue((admOk("list#AppContent", [kind: GLOSSARY, source: "ADMIN"]).entries as List<Map>).every { it.source == "ADMIN" })
        assertEquals(["admin:shadow"], (admOk("list#AppContent", [words: "SHADOW", editedOnly: true]).entries as List<Map>)*.key)
        ec.entity.find("darpan.content.AppContentEntry").condition("contentKind", GLOSSARY).condition("contentKey", "differenceCount").disableAuthz().one()
                .setAll([seedMark: "0000000000000000"]).update()
        Map ret = (admOk("list#AppContent", [kind: GLOSSARY, words: "differenceCount"]).entries as List<Map>).find { it.key == "differenceCount" }
        assertEquals(true, ret.retired)
    }

    @Test
    void everyWriteIsAudited() {
        loadSeed()
        admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "audited", title: "Audited", body: "is audited.", aliases: ["zzz audited"]])
        admOk("update#AppContentEntry", [kind: GLOSSARY, key: "admin:audited", body: "is audited twice."])
        admOk("update#AppContentEntry", [kind: GLOSSARY, key: "differenceCount", hidden: true])
        admOk("reset#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        admOk("delete#AppContentEntry", [kind: GLOSSARY, key: "admin:audited"])
        List<Map> rows = audit()
        assertEquals([A + "create#AppContentEntry", A + "update#AppContentEntry", A + "update#AppContentEntry", A + "reset#AppContentEntry", A + "delete#AppContentEntry"], rows*.serviceName)
        assertTrue(rows.every { it.adminUserId == SUPER_ID && it.targetType == "AppContentEntry" })
        assertEquals("MASCOT_GLOSSARY:admin:audited", rows[0].targetId)
        assertTrue((rows[1].detailText as String).contains("body") && (rows[1].detailText as String).contains("->"), "${rows[1].detailText}")
        assertTrue((rows[2].detailText as String).contains("hidden"), "${rows[2].detailText}")
        assertTrue((rows[4].detailText as String).contains("aliases: zzz audited") && (rows[4].detailText as String).contains("body: is audited twice."), "${rows[4].detailText}")
        // a reset with nothing edited, a refused call and a dry run write no row
        admOk("reset#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        adm("delete#AppContentEntry", [kind: GLOSSARY, key: "differenceCount"])
        admOk("create#AppContentEntry", [kind: GLOSSARY, slug: "dry", title: "Dry", body: "is dry.", aliases: ["zzz dry"], dryRun: true])
        assertEquals(5, audit().size())
    }

    @Test
    void aTenantAdminIsRefused() {
        loadSeed()
        ["get#AppContentVocabulary": [:], "list#AppContent": [:],
         "create#AppContentEntry": [kind: GLOSSARY, slug: "no", title: "No", body: "is no.", aliases: ["zzz no"]],
         "update#AppContentEntry": [kind: GLOSSARY, key: "differenceCount", hidden: true],
         "reset#AppContentEntry": [kind: GLOSSARY, key: "differenceCount"],
         "delete#AppContentEntry": [kind: GLOSSARY, key: "differenceCount"]].each { String m, Map p ->
            Map out = adm(m, p, TADMIN)
            assertTrue(out.denied != null || out.hasError as boolean, "${m} must refuse a tenant admin")
            assertNull(out.result?.entries)
        }
        assertNull(row(GLOSSARY, "admin:no"))
        assertNull(row(GLOSSARY, "differenceCount").hidden)
        assertEquals([], audit())
    }

    @Test
    void theVersionChangesOnEveryServedChange() {
        loadSeed()
        edit(GLOSSARY, "differenceCount", [title: "Edited title", lastChangedDate: T1])
        createAdmin(GLOSSARY, "admin:v-old", [title: "Old", body: "is old.", aliases: "zzz old", lastChangedDate: T1])
        createAdmin(GLOSSARY, "admin:v-new", [title: "New", body: "is new.", aliases: "zzz new", lastChangedDate: T2])
        String v0 = read().version
        // delete a row that is not the newest: the version still changes
        admOk("delete#AppContentEntry", [kind: GLOSSARY, key: "admin:v-old"])
        String v1 = read().version
        assertNotEquals(v0, v1)
        // delete the newest: not back to an earlier value
        admOk("delete#AppContentEntry", [kind: GLOSSARY, key: "admin:v-new"])
        String v2 = read().version
        assertNotEquals(v1, v2)
        assertNotEquals(v0, v2)
        // a seed load that retires the edited seed row changes it
        String v1seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toFile(), "runtime/component/darpan/data/AppContentSeedData.xml").text
        String mark = (v1seed =~ /seedId="CURRENT" seedMark="([0-9a-f]+)"/)[0][1]
        ec.entity.makeDataLoader().xmlText(v1seed.replace(mark, "ffffffffffffffff").readLines().findAll { !it.contains('contentKey="differenceCount"') }.join("\n")).dummyFks(true).load()
        assertNull(read().version, "nothing is served any more")
        assertNotEquals(v2, read().version)
    }
}
