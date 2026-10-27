package darpan.admin

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-ADMIN-006. The admin.TransferAdminServices catalog, export and import, through the real service engine as a
 * super admin with authz ON (the AdminServicesSmokeTests harness). XFER_SRC carries one of everything a bundle moves;
 * XFER_OTHER has its own audit and Shopify auth and must never leak; XFER_DST is the empty import target, wiped
 * before every test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigTransferSmokeTests {
    private ExecutionContext ec

    private static final String SERVICES = "admin.TransferAdminServices"
    private static final String SUPER_USERNAME = "xfer.super"
    private static final String PLAIN_USERNAME = "xfer.plain"
    private static final String SRC = "XFER_SRC"
    private static final String OTHER = "XFER_OTHER"
    private static final String DST = "XFER_DST"
    private static final String PASSWORD = "S3cret-Xfer-Pass"
    private static final String PEM = "PEM-SECRET-XFER-KEY"
    private static final String AUTH_REF = "NETSUITE_AUTH:XFER_NS_AUTH"
    private static final Timestamp T0 = Timestamp.valueOf("2026-01-01 00:00:00")

    /** Every entity a transfer writes, children first, so a wipe never trips a foreign key. */
    private static final List<String> TRANSFER_ENTITIES = [
            "darpan.audit.AuditStatementCheck", "darpan.audit.AuditCheck", "darpan.audit.AuditStatement", "darpan.audit.Audit",
            "darpan.rule.RuleSetConclusionCondition", "darpan.rule.RuleSetConclusionRule",
            "darpan.rule.RuleSetCompareSourceFilter", "darpan.rule.RuleSetCompareSourceKeyField",
            "darpan.rule.RuleSetCompareSource", "darpan.rule.RuleSetCompareScope", "darpan.rule.Rule", "darpan.rule.RuleSet",
            "darpan.rule.TenantConclusion",
            "darpan.reconciliation.NsSuiteQlSourceQuery", "darpan.reconciliation.NsRestletConfig",
            "darpan.reconciliation.NsAuthConfig", "darpan.shopify.ShopifyAuthConfig", "darpan.hotwax.HotWaxOmsRestSourceConfig",
    ]

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "config-transfer-smoke")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                // The connector registry names the entity behind a lookup's or a derived source type's config id.
                "component://darpan/data/SourceSystemConnectorSeedData.xml")

        upsert("moqui.security.UserAccount", [userId: "XFER_SUPER", username: SUPER_USERNAME, userFullName: "Xfer Super",
                emailAddress: "xfer.super@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserAccount", [userId: "XFER_PLAIN", username: PLAIN_USERNAME, userFullName: "Xfer Plain",
                emailAddress: "xfer.plain@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: "XFER_SUPER", fromDate: T0])
        upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: "XFER_PLAIN", fromDate: T0])
        [SRC, OTHER, DST].each { String t ->
            upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(),
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        }
        // The super admin is "in" OTHER: nothing the transfer reads or writes may follow the session's tenant.
        upsert("moqui.security.UserPreference", [userId: "XFER_SUPER", preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY,
                preferenceValue: OTHER])
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])

        seedSource()
        seedOther()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        wipeTenant(DST)
        ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().deleteAll()
        ec.message.clearErrors()
    }

    // ---------------------------------------------------------------- Task 1: catalog and export

    @Test
    void catalogListsAuditsRunsConnections() {
        Map out = callAs(SUPER_USERNAME, "${SERVICES}.get#TransferCatalog", [tenantUserGroupId: SRC])
        assertNoErrors(out)
        Map catalog = out.result as Map

        assertEquals(["month-close"], catalog.audits*.auditCode)
        Map audit = (catalog.audits as List<Map>)[0]
        assertEquals("XFER_AUDIT", audit.auditId)
        assertEquals("Month close", audit.auditName)
        assertEquals(2, audit.statementCount as int)
        assertEquals(2, audit.checkCount as int)

        assertEquals(["XFER_RS"], catalog.runs*.savedRunId)
        assertEquals(["month-close"], (catalog.runs as List<Map>)[0].auditCodes)
        assertEquals("Returns vs memos", (catalog.runs as List<Map>)[0].ruleSetName)

        Map auth = (catalog.connections as List<Map>).find { it.ref == AUTH_REF }
        assertNotNull(auth, "the tenant's NetSuite auth is offered: ${catalog.connections}")
        assertEquals([ref: AUTH_REF, kind: "NETSUITE_AUTH", id: "XFER_NS_AUTH", description: "gorjana prod", granted: false], auth)
        assertFalse(JsonOutput.toJson(catalog).contains("XFER_OTHER"), "nothing of another tenant: ${catalog}")
    }

    @Test
    void bundleCarriesRefsNotIds() {
        Map bundle = exportSource()
        assertEquals(1, bundle.bundleVersion)
        assertEquals(SRC, bundle.source.tenantUserGroupId)
        assertEquals("local", bundle.source.instanceLabel)

        List<String> refValues = []
        walk(bundle) { Map node ->
            ((node.refs ?: [:]) as Map).values().each { refValues << (it as String) }
            ((node.listRefs ?: [:]) as Map).values().each { refValues.addAll(((List) it).findAll { it != "UNEXPLAINED" }) }
        }
        assertFalse(refValues.isEmpty())
        refValues.each { assertTrue(it ==~ /^[A-Z_]+:.+$/, "a ref, never a raw id: ${it}") }

        Map run = (bundle.runs as List<Map>).find { it.ref == "RUN:XFER_RS" }
        List<Map> sources = ((run.children.RuleSetCompareScope as List<Map>)[0].children.RuleSetCompareSource) as List<Map>
        assertEquals(["NETSUITE_SUITEQL:XFER_Q1", AUTH_REF], sources*.refs*.sourceConfigId)
        assertFalse(sources.any { (it.fields as Map).containsKey("sourceConfigId") }, "the polymorphic id travels as a ref only")
        String lookups = (run.children.RuleSetCompareScope as List<Map>)[0].fields.conclusionLookupsJson
        assertEquals(AUTH_REF, (new JsonSlurper().parseText(lookups) as List<Map>)[0].sourceConfigId,
                "a lookup's connection is a ref too")

        Map check = (bundle.checks as List<Map>).find { it.ref == "CHECK:XFER_CHK1" }
        Map link = (check.links as List<Map>)[0]
        assertEquals("STATEMENT:XFER_AUDIT_R4", link.refs.statementId)
        assertEquals(["RULE:XFER_RULE_B"], link.listRefs.ruleIds)
        assertEquals(["CONC:CONC_XFER_OWN", "UNEXPLAINED"], link.listRefs.conclusionCodes, "a code that is no enum stays literal")
        assertEquals("RUN:XFER_RS", check.refs.savedRunId)
        assertFalse((check.fields as Map).containsKey("automationId"), "automations stay behind")

        assertEquals(["Refunded, no memo", "Seed reason"], bundle.conclusionCodes*.label)
        assertEquals([true, false], bundle.conclusionCodes*.tenantOwned)
        assertEquals("CONC_XFER_SEED", (bundle.conclusionCodes as List<Map>)[1].enumId, "a seed code names its enum id")
        assertEquals(["Credit memos"], bundle.queries*.label)
        assertEquals(AUTH_REF, (bundle.queries as List<Map>)[0].refs.nsAuthConfigId)
        assertEquals(["R4", "R8"], bundle.statements*.fields*.statementCode)
    }

    @Test
    void bundleCarriesNoSecretValues() {
        Map bundle = exportSource()
        String json = JsonOutput.toJson(bundle)
        assertFalse(json.contains(PASSWORD), "no password value, plain or otherwise")
        assertFalse(json.contains(PEM), "no private key value")
        Map auth = (bundle.connections as List<Map>).find { it.ref == AUTH_REF }
        assertEquals(["apiToken", "password", "privateKeyPem"], (auth.secretFields as List).sort())
        assertFalse((auth.fields as Map).keySet().any { it in ["password", "apiToken", "privateKeyPem"] })
        assertEquals("svc-user", auth.fields.username)
        assertEquals(false, auth.granted)
    }

    @Test
    void headersJsonTravelsAsASecretName() {
        // headersJson is plain text, not encrypt="true", but it can carry an auth header.
        create("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "XFER_RESTLET", description: "returns restlet",
                companyUserGroupId: SRC, endpointUrl: "https://ns.example/restlet", nsAuthConfigId: "XFER_NS_AUTH",
                headersJson: '{"X-Api-Key":"restlet-key-xfer"}'])
        try {
            Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC,
                    connectionRefs: ["NETSUITE_RESTLET:XFER_RESTLET"]])
            assertNoErrors(out)
            String json = JsonOutput.toJson(out.result.bundle)
            assertFalse(json.contains("X-Api-Key"), "no header names from headersJson")
            assertFalse(json.contains("restlet-key-xfer"), "no header values from headersJson")
            Map restlet = (out.result.bundle.connections as List<Map>).find { it.ref == "NETSUITE_RESTLET:XFER_RESTLET" }
            assertTrue("headersJson" in (restlet.secretFields as List), "${restlet.secretFields}")
            assertFalse((restlet.fields as Map).containsKey("headersJson"))
            assertEquals(AUTH_REF, restlet.refs.nsAuthConfigId)
        } finally {
            ec.entity.find("darpan.reconciliation.NsRestletConfig").condition("nsRestletConfigId", "XFER_RESTLET").disableAuthz().deleteAll()
        }
    }

    @Test
    void bundleIsDeterministic() {
        assertEquals(JsonOutput.toJson(exportSource()), JsonOutput.toJson(exportSource()))
    }

    @Test
    void exportRefusesForeignAudit() {
        Map foreign = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, auditCodes: ["other-close"]])
        Map unknown = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, auditCodes: ["no-such-audit"]])
        assertTrue(foreign.hasError as boolean)
        assertTrue(unknown.hasError as boolean)
        assertEquals((unknown.errors as List<String>).collect { it.replace("no-such-audit", "?") },
                (foreign.errors as List<String>).collect { it.replace("other-close", "?") }, "another tenant's audit reads as unknown")
        assertNull(foreign.result?.bundle)
    }

    @Test
    void nonAdminIsRefused() {
        ["get#TransferCatalog": [tenantUserGroupId: SRC],
         "export#ConfigBundle": [tenantUserGroupId: SRC, auditCodes: ["month-close"]]].each { String verb, Map params ->
            Map out = callAs(PLAIN_USERNAME, "${SERVICES}.${verb}", params)
            assertNotNull(out.authzDenied, "${verb} must be refused by the DARPAN_ADMIN_API fence; got ${out}")
            Map gated = callAs(PLAIN_USERNAME, "${SERVICES}.${verb}", params, true)
            assertEquals([AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE], gated.errors, "${verb} gate")
        }
    }

    // ---------------------------------------------------------------- Task 2: import dry run

    @Test
    void planOnEmptyTargetCreatesEverything() {
        Map bundle = exportSource()
        Map out = importInto(DST, bundle, [(AUTH_REF): "CREATE"])
        assertNoErrors(out)
        Map plan = out.result.plan as Map
        List<Map> lines = plan.lines as List<Map>

        assertEquals([], plan.blocking)
        assertEquals(["NETSUITE_AUTH", "NETSUITE_SUITEQL", "CONC", "CONC", "RUN", "AUDIT", "STATEMENT", "STATEMENT", "CHECK", "CHECK"],
                lines*.kind, "one line per record, in creation order")
        assertEquals("NEEDS_CREDENTIALS", lines.find { it.ref == AUTH_REF }.action)
        // This test database is one instance, so the shared seed code already exists here and resolves by its id.
        assertEquals("UNCHANGED", lines.find { it.ref == "CONC:CONC_XFER_SEED" }.action)
        assertEquals("CONC_XFER_SEED", lines.find { it.ref == "CONC:CONC_XFER_SEED" }.targetId)
        lines.findAll { !(it.ref in [AUTH_REF, "CONC:CONC_XFER_SEED"]) }.each {
            assertEquals("CREATE", it.action, "${it.ref}")
            assertNull(it.targetId)
        }
        assertEquals(lines.size() - 2, plan.counts.CREATE)
        assertEquals(1, plan.counts.NEEDS_CREDENTIALS)
        assertEquals([[code: "NEEDS_CREDENTIALS", ref: AUTH_REF]], (plan.warnings as List<Map>).collect { it.subMap(["code", "ref"]) })
        assertEquals(["Returns vs memos", "month-close R4"], lines.findAll { it.kind in ["RUN", "STATEMENT"] }*.label.take(2))
        assertTrue((out.result.planHash as String) ==~ /[0-9a-f]{64}/)
        assertEquals(false, out.result.applied)
    }

    @Test
    void unmappedConnectionBlocks() {
        Map plan = importInto(DST, exportSource(), [:]).result.plan as Map
        Map block = (plan.blocking as List<Map>).find { it.code == "CONNECTION_UNMAPPED" }
        assertNotNull(block, "${plan.blocking}")
        assertEquals(AUTH_REF, block.ref)
    }

    @Test
    void foreignConnectionBlocksLikeMissing() {
        Map bundle = exportSource()
        Map foreign = (importInto(DST, bundle, [(AUTH_REF): "XFER_OTHER_NS"]).result.plan as Map).blocking[0] as Map
        Map missing = (importInto(DST, bundle, [(AUTH_REF): "NOPE"]).result.plan as Map).blocking[0] as Map
        assertEquals("CONNECTION_NOT_FOUND", foreign.code)
        assertEquals("CONNECTION_NOT_FOUND", missing.code)
        assertEquals((missing.message as String).replace("NOPE", "?"), (foreign.message as String).replace("XFER_OTHER_NS", "?"),
                "another tenant's connection answers exactly like a missing one")
    }

    @Test
    void grantedConnectionIsReferencedNotExported() {
        String shopRef = "SHOPIFY_AUTH:XFER_OTHER_SHOP"
        create("darpan.auth.ConfigTenantAccess", [configTypeEnumId: "SCFG_SHOPIFY_AUTH", configId: "XFER_OTHER_SHOP",
                tenantUserGroupId: SRC, fromDate: T0])
        create("darpan.rule.RuleSet", [ruleSetId: "XFER_RS_SHOP", ruleSetName: "Shop orders", version: "1.0", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareScope", [compareScopeId: "XFER_CS_SHOP", ruleSetId: "XFER_RS_SHOP", scopeMode: "EVALUATE",
                objectType: "ORDER", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSource", [compareScopeId: "XFER_CS_SHOP", fileSide: "FILE_1", systemEnumId: "SHOPIFY",
                sourceTypeEnumId: "AUT_SRC_API", sourceConfigId: "XFER_OTHER_SHOP", sourceConfigType: "SHOPIFY_AUTH",
                primaryIdExpression: "id", companyUserGroupId: SRC])
        try {
            Map catalog = callAs(SUPER_USERNAME, "${SERVICES}.get#TransferCatalog", [tenantUserGroupId: SRC]).result as Map
            assertEquals(true, (catalog.connections as List<Map>).find { it.ref == shopRef }?.granted, "${catalog.connections}")

            Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, savedRunIds: ["XFER_RS_SHOP"]])
            assertNoErrors(out)
            Map bundle = out.result.bundle as Map
            Map shop = (bundle.connections as List<Map>).find { it.ref == shopRef }
            assertEquals(true, shop.granted)
            assertFalse(shop.containsKey("fields"), "a granted connection is named, never copied")
            assertFalse(JsonOutput.toJson(bundle).contains("other.myshopify.com"))

            Map plan = importInto(DST, bundle, [(shopRef): "CREATE"]).result.plan as Map
            assertEquals(["CONNECTION_GRANTED_CREATE"], plan.blocking*.code)
            assertEquals([shopRef], plan.blocking*.ref)
        } finally {
            ec.entity.find("darpan.auth.ConfigTenantAccess").condition("configId", "XFER_OTHER_SHOP").disableAuthz().deleteAll()
            ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", "XFER_CS_SHOP").disableAuthz().deleteAll()
            ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", "XFER_CS_SHOP").disableAuthz().deleteAll()
            ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "XFER_RS_SHOP").disableAuthz().deleteAll()
        }
    }

    @Test
    void dryRunWritesNothing() {
        Map before = rowCounts(DST)
        Map out = importInto(DST, exportSource(), [(AUTH_REF): "CREATE"])
        assertNoErrors(out)
        assertEquals(before, rowCounts(DST))
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
        assertNull(out.result.idMap)
    }

    @Test
    void planHashIsStable() {
        Map bundle = exportSource()
        String first = importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.planHash
        String second = importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.planHash
        String remapped = importInto(DST, bundle, [(AUTH_REF): "NOPE"]).result.planHash
        assertEquals(first, second)
        assertNotEquals(first, remapped, "the connection map is part of what was previewed")
    }

    // ---------------------------------------------------------------- Task 3: apply, round trip, gates

    @Test
    void roundTripCarriesEveryField() {
        Map sent = exportSource()
        Map out = applyPlan(DST, sent, [(AUTH_REF): "CREATE"])
        assertNoErrors(out)
        assertEquals(true, out.result.applied)

        Map back = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: DST, auditCodes: ["month-close"],
                sourceInstanceLabel: "local"]).result.bundle as Map
        assertEquals(naturalised(sent), naturalised(back), "every column the source carried arrives, refs by natural identity")

        // The columns no facade service writes, spelled out so a regression names itself.
        Map check = (back.checks as List<Map>).find { it.fields.checkCode == "returns-vs-memos" }
        assertEquals("trandate", check.fields.otherSideDateField)
        assertEquals('[{"when":"x"}]', (back.queries as List<Map>)[0].fields.waiveRules)
        assertEquals("10.5", (back.queries as List<Map>)[0].fields.minAmount)
        assertNotNull((back.runs as List<Map>)[0].children.RuleSetCompareScope[0].fields.conclusionLookupsJson)
        assertEquals(["R4", "R8"], back.statements*.fields*.statementCode)
        assertEquals("FIELD_MISMATCH", (check.links as List<Map>)[0].fields.diffTypes)
    }

    @Test
    void secondImportIsAllUnchanged() {
        Map bundle = exportSource()
        Map first = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertNoErrors(first)
        String authId = first.result.idMap[AUTH_REF]
        assertNotNull(authId)
        assertNotEquals("XFER_NS_AUTH", authId, "a target id is minted on the target")
        Map before = rowCounts(DST)

        Map again = importInto(DST, bundle, [(AUTH_REF): authId])
        Map plan = again.result.plan as Map
        assertEquals([], plan.blocking)
        (plan.lines as List<Map>).each {
            assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
        }
        Map reapplied = importInto(DST, bundle, [(AUTH_REF): authId], true, again.result.planHash as String)
        assertNoErrors(reapplied)
        assertEquals(before, rowCounts(DST), "applying an all-unchanged plan writes no rows")
        assertEquals(first.result.idMap, reapplied.result.idMap)
    }

    @Test
    void staleHashRefusesAndWritesNothing() {
        Map before = rowCounts(DST)
        Map out = importInto(DST, exportSource(), [(AUTH_REF): "CREATE"], true, "0" * 64)
        assertTrue(out.hasError as boolean)
        assertTrue((out.errors as List<String>).contains("The target changed since the preview. Preview again."), "${out.errors}")
        assertEquals(before, rowCounts(DST))
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
    }

    @Test
    void failureRollsBackEverything() {
        Map bundle = copy(exportSource())
        // The last check names a rule its run does not carry: everything before it is written, then it fails.
        Map lastCheck = (bundle.checks as List<Map>)[-1]
        assertEquals("returns-vs-memos", lastCheck.fields.checkCode)
        (lastCheck.links as List<Map>)[0].listRefs.ruleIds = ["RULE:NOPE"]
        Map before = rowCounts(DST)

        Map out = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertTrue(out.hasError as boolean, "the apply fails")
        assertEquals(before, rowCounts(DST), "and leaves nothing behind")
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
    }

    @Test
    void createdConnectionHasNoSecrets() {
        Map out = applyPlan(DST, exportSource(), [(AUTH_REF): "CREATE"])
        assertNoErrors(out)
        def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", out.result.idMap[AUTH_REF])
                .disableAuthz().useCache(false).one()
        assertEquals(DST, auth.companyUserGroupId)
        assertEquals("svc-user", auth.username)
        assertEquals("gorjana prod", auth.description)
        assertNull(auth.password)
        assertNull(auth.privateKeyPem)
        assertNull(auth.apiToken)
    }

    @Test
    void isolationOtherTenantsUntouched() {
        // The DarpanConclusion enum count spans tenants (DST gains a code), so only tenant-owned rows are compared.
        Closure<Map> owned = { String t -> rowCounts(t).findAll { !it.key.startsWith("moqui.") } }
        Map otherBefore = owned(OTHER)
        Map sourceBefore = owned(SRC)
        String sourceBundleBefore = JsonOutput.toJson(exportSource())
        assertNoErrors(applyPlan(DST, exportSource(), [(AUTH_REF): "CREATE"]))
        assertEquals(otherBefore, owned(OTHER))
        assertEquals(sourceBefore, owned(SRC))
        assertEquals(sourceBundleBefore, JsonOutput.toJson(exportSource()), "the source reads exactly as before")
    }

    @Test
    void unknownFieldBlocks() {
        Map bundle = copy(exportSource())
        (bundle.runs as List<Map>)[0].fields.fakeColumn = "from the future"
        Map plan = importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.plan as Map
        Map block = (plan.blocking as List<Map>).find { it.code == "UNKNOWN_FIELD" }
        assertNotNull(block, "${plan.blocking}")
        assertEquals("RUN:XFER_RS", block.ref)
        assertTrue((block.message as String).contains("darpan.rule.RuleSet") && (block.message as String).contains("fakeColumn"), block.message)
    }

    @Test
    void systemMessageRemoteBlocks() {
        Map bundle = copy(exportSource())
        Map run = (bundle.runs as List<Map>)[0]
        (run.children.RuleSetCompareScope[0].children.RuleSetCompareSource[1] as Map).fields.systemMessageRemoteId = "XFER_REMOTE"
        Map plan = importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.plan as Map
        assertEquals(["SYSTEM_MESSAGE_REMOTE"], plan.blocking*.code)
        assertEquals(["RUN:XFER_RS"], plan.blocking*.ref)
        assertTrue((plan.blocking[0].message as String).contains("Returns vs memos"))
        Map refused = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.planHash as String)
        assertTrue(refused.hasError as boolean, "a blocked plan never applies, even with its own hash")
    }

    @Test
    void applyWritesOneAuditLogRow() {
        Map out = applyPlan(DST, exportSource(), [(AUTH_REF): "CREATE"])
        assertNoErrors(out)
        List rows = ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()
        assertEquals(1, rows.size())
        assertEquals("XFER_SUPER", rows[0].adminUserId)
        assertEquals("admin.TransferAdminServices.import#ConfigBundle", rows[0].serviceName)
        assertEquals("Tenant", rows[0].targetType)
        assertEquals(DST, rows[0].targetId)
        assertTrue((rows[0].detailText as String).startsWith("local/${SRC} bundle ${(out.result.planHash as String).take(12)}: "),
                rows[0].detailText as String)
    }

    // ---------------------------------------------------------------- fixtures

    private void seedSource() {
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_NS_AUTH", description: "gorjana prod",
                companyUserGroupId: SRC, authType: "OAUTH2_M2M", username: "svc-user", password: PASSWORD,
                tokenUrl: "https://ns.example/token", clientId: "client-1", certId: "cert-1", privateKeyPem: PEM, isActive: "Y"])
        create("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "XFER_Q1", nsAuthConfigId: "XFER_NS_AUTH",
                companyUserGroupId: SRC, description: "Credit memos", recordType: "creditmemo", waiveRules: '[{"when":"x"}]',
                minAmount: new BigDecimal("10.50"), isActive: "Y"])
        create("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "XFER_Q1", recordFieldName: "tranId",
                sourceColumn: "tranid", sequenceNum: 1L])
        create("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "XFER_Q1", recordFieldName: "amount",
                sourceColumn: "foreigntotal", sourceColumnAlias: "amt", sequenceNum: 2L])

        create("moqui.basic.Enumeration", [enumId: "CONC_XFER_OWN", enumTypeId: "DarpanConclusion", enumCode: "CONC_XFER_OWN",
                description: "Refunded, no memo"])
        create("darpan.rule.TenantConclusion", [enumId: "CONC_XFER_OWN", companyUserGroupId: SRC])
        create("moqui.basic.Enumeration", [enumId: "CONC_XFER_SEED", enumTypeId: "DarpanConclusion", enumCode: "CONC_XFER_SEED",
                description: "Seed reason"])

        create("darpan.rule.RuleSet", [ruleSetId: "XFER_RS", ruleSetName: "Returns vs memos", description: "OMS returns vs memos",
                version: "1.0", companyUserGroupId: SRC, isActive: "Y", isArchived: "N"])
        create("darpan.rule.Rule", [ruleId: "XFER_RULE_A", ruleSetId: "XFER_RS", sequenceNum: 10L, ruleText: "status equals 'A'",
                enabled: "Y", ruleType: "FIELD_COMPARISON", companyUserGroupId: SRC])
        create("darpan.rule.Rule", [ruleId: "XFER_RULE_B", ruleSetId: "XFER_RS", sequenceNum: 20L, ruleText: "amount greater than 0",
                enabled: "Y", ruleType: "FIELD_COMPARISON", severity: "WARN", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareScope", [compareScopeId: "XFER_CS", ruleSetId: "XFER_RS", objectType: "RETURN",
                scopeMode: "COMPARE", description: "Returns", companyUserGroupId: SRC,
                conclusionLookupsJson: '[{"name":"memo","subject":"FILE_1","idField":"tranId","systemEnumId":"NETSUITE",' +
                        '"sourceConfigId":"XFER_NS_AUTH","keyField":"id"}]'])
        create("darpan.rule.RuleSetCompareSource", [compareScopeId: "XFER_CS", fileSide: "FILE_1", systemEnumId: "NETSUITE_SUITEQL",
                sourceTypeEnumId: "AUT_SRC_API", sourceConfigId: "XFER_Q1", sourceConfigType: "NETSUITE_SUITEQL",
                primaryIdExpression: "tranId", extractParametersJson: '{"limit":5}', companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSource", [compareScopeId: "XFER_CS", fileSide: "FILE_2", systemEnumId: "NETSUITE",
                sourceTypeEnumId: "AUT_SRC_API", sourceConfigId: "XFER_NS_AUTH", sourceConfigType: "NETSUITE_AUTH",
                primaryIdExpression: "id", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "XFER_CS", fileSide: "FILE_1", sequenceNum: 1L,
                fieldExpression: "tranId", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSourceKeyField", [compareScopeId: "XFER_CS", fileSide: "FILE_1", sequenceNum: 2L,
                fieldExpression: "line", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSourceFilter", [compareScopeId: "XFER_CS", fileSide: "FILE_2", sequenceNum: 1L,
                fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "X,Y", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetConclusionRule", [compareScopeId: "XFER_CS", sequenceNum: 10L, conclusionEnumId: "CONC_XFER_OWN",
                appliesToBucket: "FINDING", suggestedFilterJson: '{"fileSide":"FILE_1"}', companyUserGroupId: SRC])
        create("darpan.rule.RuleSetConclusionRule", [compareScopeId: "XFER_CS", sequenceNum: 20L, parentSequenceNum: 10L,
                conclusionEnumId: "CONC_XFER_SEED", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "XFER_CS", sequenceNum: 10L, conditionSeq: 1L,
                subject: "FILE_1", fieldExpression: "status", operator: "IN", conditionValues: "A", checkLabel: "Status A",
                companyUserGroupId: SRC])

        create("darpan.audit.Audit", [auditId: "XFER_AUDIT", companyUserGroupId: SRC, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        create("darpan.audit.AuditStatement", [statementId: "XFER_AUDIT_R4", companyUserGroupId: SRC, auditId: "XFER_AUDIT",
                statementCode: "R4", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", statementText: "Returns have memos",
                isActive: "Y", sequenceNum: 1L])
        create("darpan.audit.AuditStatement", [statementId: "XFER_AUDIT_R8", companyUserGroupId: SRC, auditId: "XFER_AUDIT",
                statementCode: "R8", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_DERIVED", statementText: "Memos close",
                isActive: "Y", sequenceNum: 2L])
        create("darpan.audit.AuditCheck", [checkId: "XFER_CHK1", companyUserGroupId: SRC, checkCode: "returns-vs-memos",
                label: "Returns vs memos", savedRunId: "XFER_RS", graceDays: 2L, otherSideDateField: "trandate",
                fixWhereEnumId: "NETSUITE", headlineTemplate: "Return {id} has no memo"])
        create("darpan.audit.AuditCheck", [checkId: "XFER_CHK2", companyUserGroupId: SRC, checkCode: "memos-open",
                label: "Memos left open", savedRunId: "XFER_RS"])
        create("darpan.audit.AuditStatementCheck", [statementId: "XFER_AUDIT_R4", checkId: "XFER_CHK1", companyUserGroupId: SRC,
                sequenceNum: 1L, diffTypes: "FIELD_MISMATCH", ruleIds: "XFER_RULE_B", conclusionCodes: "CONC_XFER_OWN,UNEXPLAINED"])
        create("darpan.audit.AuditStatementCheck", [statementId: "XFER_AUDIT_R8", checkId: "XFER_CHK2", companyUserGroupId: SRC,
                sequenceNum: 1L, diffTypes: "FINDING"])
    }

    private void seedOther() {
        create("darpan.audit.Audit", [auditId: "XFER_OTHER_AUDIT", companyUserGroupId: OTHER, auditCode: "other-close",
                auditName: "Other close", periodEnumId: "AUDP_MONTH"])
        create("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: "XFER_OTHER_SHOP", description: "Other shop",
                companyUserGroupId: OTHER, shopApiUrl: "https://other.myshopify.com", apiVersion: "2026-01",
                accessToken: "shpat-other-secret"])
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_OTHER_NS", description: "gorjana prod",
                companyUserGroupId: OTHER, authType: "NONE"])
    }

    // ---------------------------------------------------------------- harness

    private Map exportSource() {
        Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, auditCodes: ["month-close"],
                sourceInstanceLabel: "local"])
        assertNoErrors(out)
        return out.result.bundle as Map
    }

    private Map importInto(String tenantId, Map bundle, Map connectionMap, boolean apply = false, String planHash = null) {
        Map params = [tenantUserGroupId: tenantId, bundle: bundle, connectionMap: connectionMap, apply: apply]
        if (planHash) params.planHash = planHash
        return callAs(SUPER_USERNAME, "${SERVICES}.import#ConfigBundle", params)
    }

    /** Preview, then apply with the preview's hash: what the admin app does. */
    private Map applyPlan(String tenantId, Map bundle, Map connectionMap) {
        Map preview = importInto(tenantId, bundle, connectionMap)
        assertNoErrors(preview)
        return importInto(tenantId, bundle, connectionMap, true, preview.result.planHash as String)
    }

    private static Map copy(Map bundle) {
        return new JsonSlurper().parseText(JsonOutput.toJson(bundle)) as Map
    }

    /**
     * A bundle with every ref replaced by what the record is called on any instance (a run by its name, a rule by its
     * run and position, a code by its label), including refs inside JSON text; source and record refs dropped.
     */
    private static Object naturalised(Map bundle) {
        Map<String, String> names = [:]
        (bundle.connections as List<Map>).each { names[it.ref as String] = "CONN ${it.kind} ${it.description}".toString() }
        (bundle.queries as List<Map>).each { names[it.ref as String] = "QUERY ${it.label}".toString() }
        (bundle.conclusionCodes as List<Map>).each { names[it.ref as String] = "CONC ${it.label}".toString() }
        (bundle.runs as List<Map>).each { Map run ->
            names[run.ref as String] = "RUN ${run.label}".toString()
            (run.children.Rule as List<Map>).eachWithIndex { Map rule, int i -> names[rule.ref as String] = "RULE ${run.label}#${i}".toString() }
        }
        (bundle.audits as List<Map>).each { names[it.ref as String] = "AUDIT ${it.fields.auditCode}".toString() }
        (bundle.statements as List<Map>).each { names[it.ref as String] = "STATEMENT ${it.label}".toString() }
        (bundle.checks as List<Map>).each { names[it.ref as String] = "CHECK ${it.fields.checkCode}".toString() }
        Closure rename
        rename = { Object node ->
            if (node instanceof Map) return ((Map) node).findAll { k, v -> !(k in ["source", "ref"]) }.collectEntries { k, v -> [(k): rename(v)] }
            if (node instanceof List) return ((List) node).collect { rename(it) }
            if (node instanceof String && names.containsKey(node)) return names[node]
            if (node instanceof String && ((String) node).startsWith("[{")) return JsonOutput.toJson(rename(new JsonSlurper().parseText((String) node)))
            return node
        }
        return rename(bundle)
    }

    /** Row count per transfer entity for one tenant (children counted through their tenant stamp or parent). */
    private Map<String, Long> rowCounts(String tenantId) {
        Map<String, Long> counts = new TreeMap<>()
        TRANSFER_ENTITIES.each { String entityName ->
            def find = ec.entity.find(entityName).disableAuthz().useCache(false)
            if (ec.entity.getEntityDefinition(entityName).isField("companyUserGroupId")) find.condition("companyUserGroupId", tenantId)
            counts[entityName] = find.count()
        }
        List<String> queryIds = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("companyUserGroupId", tenantId)
                .disableAuthz().useCache(false).list()*.nsSuiteQlSourceQueryId.collect { it as String }
        counts["darpan.reconciliation.NsSuiteQlSourceQueryField"] = queryIds ? ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
                .condition("nsSuiteQlSourceQueryId", "in", queryIds).disableAuthz().useCache(false).count() : 0L
        counts["moqui.basic.Enumeration(DarpanConclusion)"] = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumTypeId", "DarpanConclusion").disableAuthz().useCache(false).count()
        return counts
    }

    /** Runs one service call on a fresh thread (authz ON, empty artifact stack) as the named user. */
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
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "xfer-smoke-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static void assertNoErrors(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
    }

    /** Every map in the tree, depth first. */
    private static void walk(Object node, Closure visit) {
        if (node instanceof Map) {
            visit((Map) node)
            ((Map) node).values().each { walk(it, visit) }
        } else if (node instanceof List) {
            ((List) node).each { walk(it, visit) }
        }
    }

    private void wipeTenant(String tenantId) {
        List<String> ownCodes = ec.entity.find("darpan.rule.TenantConclusion").condition("companyUserGroupId", tenantId)
                .disableAuthz().useCache(false).list()*.enumId.collect { it as String }
        List<String> ruleSetIds = ec.entity.find("darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .disableAuthz().useCache(false).list()*.ruleSetId.collect { it as String }
        List<String> scopeIds = ruleSetIds ? ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", "in", ruleSetIds)
                .disableAuthz().useCache(false).list()*.compareScopeId.collect { it as String } : []
        List<String> queryIds = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("companyUserGroupId", tenantId)
                .disableAuthz().useCache(false).list()*.nsSuiteQlSourceQueryId.collect { it as String }
        if (queryIds) ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
                .condition("nsSuiteQlSourceQueryId", "in", queryIds).disableAuthz().deleteAll()
        TRANSFER_ENTITIES.each { String entityName ->
            def find = ec.entity.find(entityName).disableAuthz()
            if (entityName.startsWith("darpan.rule.") && entityName != "darpan.rule.RuleSet" && entityName != "darpan.rule.TenantConclusion") {
                String key = entityName == "darpan.rule.Rule" || entityName == "darpan.rule.RuleSetCompareScope" ? "ruleSetId" : "compareScopeId"
                List<String> ids = key == "ruleSetId" ? ruleSetIds : scopeIds
                if (ids) find.condition(key, "in", ids).deleteAll()
            } else {
                find.condition("companyUserGroupId", tenantId).deleteAll()
            }
        }
        if (ownCodes) ec.entity.find("moqui.basic.Enumeration").condition("enumId", "in", ownCodes).disableAuthz().deleteAll()
    }

    private void upsert(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    private void create(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).create()
    }
}
