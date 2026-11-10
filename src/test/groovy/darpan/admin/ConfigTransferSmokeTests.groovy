package darpan.admin

import darpan.admin.transfer.ConfigTransferExporter
import darpan.admin.transfer.ConfigTransferImporter
import darpan.admin.transfer.SecretHandoff
import darpan.audit.AuditSettingsSupport
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
            "darpan.reconciliation.ReconciliationRunResult",
            "darpan.audit.AuditStatementCheck", "darpan.audit.AuditCheck", "darpan.audit.AuditStatement", "darpan.audit.Audit",
            "darpan.reconciliation.ReconciliationAutomationSourceFilter", "darpan.reconciliation.ReconciliationAutomationSource",
            "darpan.reconciliation.ReconciliationAutomation", "darpan.reconciliation.TenantChatSpace",
            "darpan.reconciliation.ReconciliationRun", "darpan.reconciliation.Reconciliation",
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
        assertFalse((link.listRefs as Map).containsKey("conclusionCodes") || (link.fields as Map).containsKey("conclusionCodes"),
                "DAR-BE-196: a link carries no code list")
        Map exportedAudit = (bundle.audits as List<Map>)[0]
        assertEquals("Month close", (exportedAudit.fields as Map).auditName, "the audit's own fields travel: ${exportedAudit}")
        assertFalse(["scheduleTime", "scheduleTimeZone", "scheduleActive"].any { (exportedAudit.fields as Map).containsKey(it) },
                "DAR-BE-199: the audit's schedule is the instance's own: ${exportedAudit.fields}")
        Map expectedRule = ((run.children.RuleSetCompareScope as List<Map>)[0].children.RuleSetConclusionRule as List<Map>)
                .find { it.refs.conclusionEnumId == "CONC:CONC_XFER_SEED" }
        assertEquals("EXPECTED", expectedRule.fields.outcome, "an expected cause travels on its run's rule")
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
         "export#ConfigBundle": [tenantUserGroupId: SRC, auditCodes: ["month-close"]],
         "begin#SecretHandoff": [tenantUserGroupId: DST]].each { String verb, Map params ->
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
        // DAR-BE-210: a rule's display name travels with it, and lands on the target's rule.
        Map backRun = (back.runs as List<Map>).find { it.fields.ruleSetName == "Returns vs memos" }
        assertEquals(["Status Sync", null], (backRun.children.Rule as List<Map>)*.fields*.ruleName)
        String targetRunId = ec.entity.find("darpan.rule.RuleSet").condition([companyUserGroupId: DST, ruleSetName: "Returns vs memos"])
                .disableAuthz().useCache(false).one().ruleSetId
        assertEquals(["Status Sync"], ec.entity.find("darpan.rule.Rule").condition("ruleSetId", targetRunId).disableAuthz()
                .useCache(false).list()*.ruleName.findAll { it })
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
    void nullSourceFieldMatchesTargetDefault() {
        // Older prod rows hold null where the entity now declares a default (RuleSet.isActive 'Y', isArchived 'N',
        // a condition's presence, keyScope, quantifier). The target's create fills the default; the re-preview must
        // still read them as the same. Direct SQL, so the source keeps its nulls.
        Closure insert = { String entityName, Map fields -> ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields) }
        insert("darpan.rule.RuleSet", [ruleSetId: "XFER_RS_NULLS", ruleSetName: "Old prod run", version: "1.0",
                companyUserGroupId: SRC, isActive: null, isArchived: null])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "XFER_CS_NULLS", ruleSetId: "XFER_RS_NULLS", objectType: "RETURN",
                companyUserGroupId: SRC])
        insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "XFER_CS_NULLS", sequenceNum: 10L, conclusionEnumId: "CONC_XFER_SEED",
                companyUserGroupId: SRC])
        insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "XFER_CS_NULLS", sequenceNum: 10L, conditionSeq: 1L,
                subject: "FILE_1", checkLabel: "Present", presence: null, keyScope: null, quantifier: null, companyUserGroupId: SRC])
        try {
            Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, savedRunIds: ["XFER_RS_NULLS"]])
            assertNoErrors(out)
            Map bundle = out.result.bundle as Map
            assertNull((bundle.runs as List<Map>)[0].fields.isActive, "the source really holds null")
            assertNoErrors(applyPlan(DST, bundle, [:]))
            assertEquals("Y", ec.entity.find("darpan.rule.RuleSet").condition("companyUserGroupId", DST).disableAuthz().useCache(false)
                    .one().isActive, "the target's create filled the default")

            Map again = importInto(DST, bundle, [:]).result.plan as Map
            assertEquals([], again.blocking)
            (again.lines as List<Map>).each { assertEquals("UNCHANGED", it.action, "${it.ref}: ${it.changes}") }
        } finally {
            wipeTenant(DST)
            ["darpan.rule.RuleSetConclusionCondition", "darpan.rule.RuleSetConclusionRule", "darpan.rule.RuleSetCompareScope"].each {
                ec.entity.find(it).condition("compareScopeId", "XFER_CS_NULLS").disableAuthz().deleteAll()
            }
            ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "XFER_RS_NULLS").disableAuthz().deleteAll()
        }
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
        // A rule this instance's engine cannot compile: the plan cannot see it, so the apply writes connections, query,
        // codes and the run, then the compile fails and every row goes.
        ((bundle.runs as List<Map>)[0].children.Rule[0] as Map).fields.ruleLogic = 'this["status"] ==== "A" ((('
        Map before = rowCounts(DST)

        Map out = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertTrue(out.hasError as boolean, "the apply fails")
        assertTrue((out.errors as List<String>).any { it.contains("does not compile") }, "${out.errors}")
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
    void aRetiredGraceDaysIsDroppedOnImportNotRefused() {
        // DAR-BE-206: bundles and config versions made before a check's graceDays was retired still carry it.
        Map bundle = copy(exportSource())
        Map check = (bundle.checks as List<Map>).find { it.ref == "CHECK:XFER_CHK1" }
        assertFalse((check.fields as Map).containsKey("graceDays"), "the export no longer writes it")
        check.fields.graceDays = "2"
        Map first = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertNoErrors(first)
        Map again = importInto(DST, bundle, [(AUTH_REF): first.result.idMap[AUTH_REF]]).result.plan as Map
        assertEquals([], again.blocking)
        assertEquals("UNCHANGED", (again.lines as List<Map>).find { it.ref == "CHECK:XFER_CHK1" }.action,
                "dropped before the diff too, so a re-preview converges")
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

        // A remote this instance already has is instance config, not the source's: it transfers and nothing is created.
        create("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "XFER_REMOTE", description: "Here already"])
        try {
            Map present = importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.plan as Map
            assertEquals([], present.blocking)
            assertNoErrors(applyPlan(DST, bundle, [(AUTH_REF): "CREATE"]))
            assertEquals("Here already", ec.entity.find("moqui.service.message.SystemMessageRemote")
                    .condition("systemMessageRemoteId", "XFER_REMOTE").disableAuthz().useCache(false).one().description)
        } finally {
            wipeTenant(DST)
            ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", "XFER_REMOTE").disableAuthz().deleteAll()
        }
    }

    @Test
    void sharedOmsRemoteTransfersWhenTheRegistryCanMakeIt() {
        // The gorjana month-close shape: an OMS GraphQL returns source on the shared HOTWAX_ORDERS_API remote. The source
        // must hold the remote (a foreign key), so the bundle is exported first and the remote then removed: this test
        // database plays a target that has never needed it, and the OMS_RETURNS_GQL registry row names it.
        String omsRef = "HOTWAX_OMS_REST:XFER_OMS"
        Closure dropSourceRun = {
            ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", "XFER_CS_OMS").disableAuthz().deleteAll()
            ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", "XFER_CS_OMS").disableAuthz().deleteAll()
            ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "XFER_RS_OMS").disableAuthz().deleteAll()
            ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig").condition("omsRestSourceConfigId", "XFER_OMS").disableAuthz().deleteAll()
        }
        Closure dropRemote = {
            ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", "HOTWAX_ORDERS_API").disableAuthz().deleteAll()
        }
        upsert("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "HOTWAX_ORDERS_API", description: "Orders API"])
        create("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: "XFER_OMS", description: "gorjana oms",
                companyUserGroupId: SRC, baseUrl: "https://oms.example"])
        create("darpan.rule.RuleSet", [ruleSetId: "XFER_RS_OMS", ruleSetName: "OMS returns", version: "1.0", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareScope", [compareScopeId: "XFER_CS_OMS", ruleSetId: "XFER_RS_OMS", scopeMode: "EVALUATE",
                objectType: "RETURN", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetCompareSource", [compareScopeId: "XFER_CS_OMS", fileSide: "FILE_1", systemEnumId: "OMS_RETURNS_GQL",
                sourceTypeEnumId: "AUT_SRC_API", sourceConfigId: "XFER_OMS", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS",
                systemMessageRemoteId: "HOTWAX_ORDERS_API", primaryIdExpression: "returnId", companyUserGroupId: SRC])
        try {
            Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, savedRunIds: ["XFER_RS_OMS"]])
            assertNoErrors(out)
            Map bundle = out.result.bundle as Map
            assertEquals(omsRef, sourcesOf((bundle.runs as List<Map>)[0])[0].refs.sourceConfigId)
            dropSourceRun()
            dropRemote()

            Map plan = importInto(DST, bundle, [(omsRef): "CREATE"]).result.plan as Map
            assertEquals([], plan.blocking, "the shared remote is not the source's to keep")
            assertNoErrors(applyPlan(DST, bundle, [(omsRef): "CREATE"]))
            def made = ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", "HOTWAX_ORDERS_API")
                    .disableAuthz().useCache(false).one()
            assertNotNull(made, "the apply makes the remote from the registry row")
            assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns", made.sendServiceName)
            def dstSource = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("companyUserGroupId", DST)
                    .disableAuthz().useCache(false).one()
            assertEquals("HOTWAX_ORDERS_API", dstSource.systemMessageRemoteId)
        } finally {
            wipeTenant(DST)
            dropSourceRun()
            dropRemote()
        }
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

    // ---------------------------------------------------------------- review fixes: the dry run predicts the apply

    @Test
    void handEditedBundleIsRefused() {
        Map bundle = copy(exportSource())
        Map run = (bundle.runs as List<Map>)[0]
        run.fields.ruleSetId = "XFER_RS"
        (bundle.checks as List<Map>)[0].fields.companyUserGroupId = OTHER
        (sourcesOf(run)[1].fields as Map).sourceConfigId = "XFER_OTHER_NS"
        (bundle.connections as List<Map>)[0].fields.password = "planted"
        (bundle.audits as List<Map>)[0].refs.periodEnumId = "AUDIT:XFER_AUDIT"
        // DAR-BE-196: an older bundle's link code list no longer travels.
        (((bundle.checks as List<Map>)[0].links as List<Map>)[0].listRefs as Map).conclusionCodes = ["CONC:CONC_XFER_OWN"]
        Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"])
        List<Map> refused = (preview.result.plan.blocking as List<Map>).findAll { it.code == "FIELD_NOT_ALLOWED" }
        assertEquals(["RuleSet.ruleSetId", "AuditCheck.companyUserGroupId", "RuleSetCompareSource.sourceConfigId",
                      "NsAuthConfig.password", "Audit.periodEnumId", "AuditStatementCheck.conclusionCodes"].sort(),
                refused.collect { (it.message as String).find(/[A-Za-z]+\.[A-Za-z]+(?= is not)/) }.sort(), "${refused}")

        Map before = rowCounts(DST)
        Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String)
        assertTrue(applied.hasError as boolean)
        assertEquals(before, rowCounts(DST))
    }

    @Test
    void blankOrDuplicateNaturalKeysBlock() {
        String queryRef = "NETSUITE_SUITEQL:XFER_Q1"
        Map blank = copy(exportSource())
        (blank.queries as List<Map>)[0].fields.description = null
        assertEquals([queryRef], blocked(blank, "AMBIGUOUS_MATCH"), "a query with no description matches nothing safely")

        Map twice = copy(exportSource())
        Map second = copy((twice.queries as List<Map>)[0])
        second.ref = "NETSUITE_SUITEQL:XFER_Q2"
        (twice.queries as List<Map>) << second
        assertEquals([queryRef, "NETSUITE_SUITEQL:XFER_Q2"], blocked(twice, "AMBIGUOUS_MATCH"), "two records, one key")

        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_DST_NS", description: "dst", companyUserGroupId: DST])
        ["XFER_DST_Q1", "XFER_DST_Q2"].each { String id ->
            create("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: id, nsAuthConfigId: "XFER_DST_NS",
                    companyUserGroupId: DST, description: "Credit memos", recordType: "creditmemo"])
        }
        assertEquals([queryRef], blocked(exportSource(), "AMBIGUOUS_MATCH"), "two target rows answer to one description")
    }

    @Test
    void lifecycleOwnedTargetRunBlocks() {
        create("darpan.rule.RuleSet", [ruleSetId: "XFER_DST_LIFE_RS", ruleSetName: "Returns vs memos", version: "1.0",
                companyUserGroupId: DST, ownerReconciliationId: "XFER_LIFE"])
        assertEquals(["RUN:XFER_RS"], blocked(exportSource(), "TARGET_RUN_OWNED"))
    }

    @Test
    void unresolvedRefsBlock() {
        Map bundle = copy(exportSource())
        Map check = (bundle.checks as List<Map>).find { it.fields.checkCode == "returns-vs-memos" }
        (check.links as List<Map>)[0].listRefs.ruleIds = ["RULE:NOPE"]
        Map scope = (bundle.runs as List<Map>)[0].children.RuleSetCompareScope[0] as Map
        (scope.children.RuleSetConclusionRule[0] as Map).refs.conclusionEnumId = "CONC:NOPE"
        scope.fields.conclusionLookupsJson = "not a list of lookups"
        List<Map> refused = (importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.plan.blocking as List<Map>)
                .findAll { it.code == "REF_UNRESOLVED" }
        assertEquals(["CHECK:XFER_CHK1", "RUN:XFER_RS", "RUN:XFER_RS"], refused*.ref.sort(), "${refused}")
        assertTrue(refused.any { (it.message as String).contains("RULE:NOPE") })
        assertTrue(refused.any { (it.message as String).contains("CONC:NOPE") })
        assertTrue(refused.any { (it.message as String).contains("conclusionLookupsJson") })
    }

    @Test
    void exportCarriesACodeThatIsNoConclusion() {
        // AUDP_MONTH is an Enumeration, but not a DarpanConclusion: carried as missing, so the plan can refuse it.
        create("darpan.rule.RuleSetConclusionRule", [compareScopeId: "XFER_CS", sequenceNum: 30L, conclusionEnumId: "AUDP_MONTH",
                companyUserGroupId: SRC])
        try {
            Map bundle = exportSource()
            Map code = (bundle.conclusionCodes as List<Map>).find { it.ref == "CONC:AUDP_MONTH" }
            assertNotNull(code, "${bundle.conclusionCodes}")
            assertEquals(true, code.missing)
            assertEquals(["CONC:AUDP_MONTH"], blocked(bundle, "REF_UNRESOLVED"))
        } finally {
            ec.entity.find("darpan.rule.RuleSetConclusionRule").condition("compareScopeId", "XFER_CS").condition("sequenceNum", 30L)
                    .disableAuthz().deleteAll()
        }
    }

    @Test
    void scopeInUseBlocks() {
        Map bundle = exportSource()
        Map first = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertNoErrors(first)
        String runId = first.result.idMap["RUN:XFER_RS"]
        String scopeId = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", runId).disableAuthz().useCache(false)
                .one().compareScopeId
        create("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "XFER_DST_RR", ruleSetId: runId,
                compareScopeId: scopeId, companyUserGroupId: DST])
        Map map = [(AUTH_REF): first.result.idMap[AUTH_REF]]

        Map dropped = copy(bundle)
        (dropped.runs as List<Map>)[0].children.RuleSetCompareScope = []
        assertEquals(["RUN:XFER_RS"], blocked(dropped, "SCOPE_IN_USE", map), "a scope a run result names is not deleted")

        Map repurposed = copy(bundle)
        ((repurposed.runs as List<Map>)[0].children.RuleSetCompareScope[0] as Map).fields.objectType = "ORDER"
        assertEquals(["RUN:XFER_RS"], blocked(repurposed, "SCOPE_IN_USE", map), "nor turned into another object's scope")
    }

    @Test
    void missingReferenceDataBlocks() {
        Map bundle = copy(exportSource())
        (bundle.audits as List<Map>)[0].fields.periodEnumId = "AUDP_NOPE"
        (bundle.checks as List<Map>).find { it.fields.checkCode == "memos-open" }.fields.amountCurrencyUomId = "XYZ"
        List<Map> refused = (importInto(DST, bundle, [(AUTH_REF): "CREATE"]).result.plan.blocking as List<Map>)
                .findAll { it.code == "MISSING_REFERENCE_DATA" }
        assertEquals(["AUDIT:XFER_AUDIT", "CHECK:XFER_CHK2"], refused*.ref.sort(), "${refused}")
        assertTrue(refused.any { (it.message as String).contains("AUDP_NOPE") })
        assertTrue(refused.any { (it.message as String).contains("XYZ") })
    }

    @Test
    void updateWithFewerRulesAndScopes() {
        Map bundle = exportSource()
        Map first = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
        assertNoErrors(first)
        Map map = [(AUTH_REF): first.result.idMap[AUTH_REF]]
        String runId = first.result.idMap["RUN:XFER_RS"]
        String firstRuleId = first.result.idMap["RULE:XFER_RULE_A"]

        // Rule A goes; rule B moves to the first position and keeps that position's target id.
        Map fewerRules = copy(bundle)
        ((fewerRules.runs as List<Map>)[0].children.Rule as List).remove(0)
        Map second = applyPlan(DST, fewerRules, map)
        assertNoErrors(second)
        assertEquals("UPDATE", (second.result.plan.lines as List<Map>).find { it.ref == "RUN:XFER_RS" }.action)
        List rules = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", runId).disableAuthz().useCache(false).list()
        assertEquals([firstRuleId], rules*.ruleId)
        assertEquals("amount greater than 0", rules[0].ruleText)
        String checkId = second.result.idMap["CHECK:XFER_CHK1"]
        assertEquals(firstRuleId, ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", checkId)
                .disableAuthz().useCache(false).one().ruleIds, "the check follows its rule to its new id")

        Map noScope = copy(fewerRules)
        (noScope.runs as List<Map>)[0].children.RuleSetCompareScope = []
        assertNoErrors(applyPlan(DST, noScope, map))
        Map counts = rowCounts(DST)
        ["darpan.rule.RuleSetCompareScope", "darpan.rule.RuleSetCompareSource", "darpan.rule.RuleSetCompareSourceKeyField",
         "darpan.rule.RuleSetCompareSourceFilter", "darpan.rule.RuleSetConclusionRule", "darpan.rule.RuleSetConclusionCondition"].each {
            assertEquals(0L, counts[it], it)
        }
        (importInto(DST, noScope, map).result.plan.lines as List<Map>).each {
            assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
        }
    }

    // ---------------------------------------------------------------- DAR-ADMIN-007: credentials sealed end to end

    @Test
    void sealedRoundTrip() {
        String restletRef = "NETSUITE_RESTLET:XFER_SEAL_RESTLET"
        Map secrets = [password: "restlet-pass-xfer", apiToken: "restlet-token-xfer", privateKeyPem: "restlet-pem-xfer",
                       headersJson: '{"X-Api-Key":"restlet-header-xfer"}']
        create("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "XFER_SEAL_RESTLET", description: "sealed restlet",
                companyUserGroupId: SRC, endpointUrl: "https://ns.example/sealed", nsAuthConfigId: "XFER_NS_AUTH"] + secrets)
        List<String> plaintexts = [PASSWORD, PEM] + secrets.values().collect { it as String } + ["restlet-header-xfer"]
        try {
            Map handoff = beginHandoff(DST)
            assertTrue((handoff.publicKey as String).length() > 300)
            assertTrue((handoff.keyFingerprint as String) ==~ /[0-9a-f]{64}/)
            assertNotNull(handoff.expiresAt)
            Map bundle = exportSealed(handoff.publicKey as String, [connectionRefs: [restletRef]])
            String bundleJson = JsonOutput.toJson(bundle)
            plaintexts.each { assertFalse(bundleJson.contains(it), "no plaintext in the bundle: ${it}") }
            assertEquals(handoff.keyFingerprint, bundle.secrets.keyFingerprint)
            assertEquals("RSA-OAEP-256+A256GCM", bundle.secrets.alg)
            assertEquals(["password", "privateKeyPem"], ((bundle.connections as List<Map>).find { it.ref == AUTH_REF }.sealedSecrets as Map).keySet().collect { it as String }.sort())

            Map map = [(AUTH_REF): "CREATE", (restletRef): "CREATE"]
            Map preview = importInto(DST, bundle, map, false, null, handoff.handoffToken as String)
            assertNoErrors(preview)
            Map plan = preview.result.plan as Map
            assertEquals([], plan.blocking)
            Map authLine = (plan.lines as List<Map>).find { it.ref == AUTH_REF }
            assertEquals("CREATE", authLine.action, "credentials arrive, so nothing is left to enter")
            assertEquals(["password", "privateKeyPem"], authLine.secretsFilled)
            assertEquals([], authLine.secretsKept)
            assertEquals(["apiToken", "headersJson", "password", "privateKeyPem"], (plan.lines as List<Map>).find { it.ref == restletRef }.secretsFilled)
            assertEquals(2, plan.counts.CREDENTIALS_FILLED)
            assertFalse((plan.warnings as List<Map>).any { it.code == "NEEDS_CREDENTIALS" })

            Map applied = importInto(DST, bundle, map, true, preview.result.planHash as String, handoff.handoffToken as String)
            assertNoErrors(applied)
            def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", applied.result.idMap[AUTH_REF])
                    .disableAuthz().useCache(false).one()
            assertEquals(PASSWORD, auth.password)
            assertEquals(PEM, auth.privateKeyPem)
            assertNull(auth.apiToken)
            def restlet = ec.entity.find("darpan.reconciliation.NsRestletConfig").condition("nsRestletConfigId", applied.result.idMap[restletRef])
                    .disableAuthz().useCache(false).one()
            secrets.each { String field, String value -> assertEquals(value, restlet.get(field), field) }

            String everythingSaid = JsonOutput.toJson([preview.result, applied.result, preview.errors, applied.errors,
                    ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()*.getMap()])
            plaintexts.each { assertFalse(everythingSaid.contains(it), "no plaintext in a plan, an error or the audit log: ${it}") }
            assertTrue((ec.entity.find("darpan.admin.AdminAuditLog").condition("targetId", DST)
                    .condition("serviceName", "admin.TransferAdminServices.import#ConfigBundle").disableAuthz().useCache(false).one()
                    .detailText as String).contains("credentials filled: 6"), "the apply log counts the fields filled")
        } finally {
            wipeTenant(DST)
            ec.entity.find("darpan.reconciliation.NsRestletConfig").condition("nsRestletConfigId", "XFER_SEAL_RESTLET").disableAuthz().deleteAll()
        }
    }

    @Test
    void handoffTokenWrongTenantBlocks() {
        Map handoff = beginHandoff(OTHER)
        Map bundle = exportSealed(handoff.publicKey as String)
        assertEquals(["SECRET_HANDOFF_INVALID"], blockedCodes(bundle, handoff.handoffToken as String))
    }

    @Test
    void handoffTokenExpiredBlocks() {
        Map handoff = SecretHandoff.begin(ec, DST, System.currentTimeMillis() - 31L * 60 * 1000)
        Map bundle = exportSealed(handoff.publicKey as String)
        assertEquals(["SECRET_HANDOFF_INVALID"], blockedCodes(bundle, handoff.handoffToken as String))
        assertEquals(["SECRET_HANDOFF_INVALID"], blockedCodes(bundle, "not-a-token"), "an unreadable token answers the same")
    }

    @Test
    void tamperedHandoffTokenBlocks() {
        Map handoff = beginHandoff(DST)
        Map bundle = exportSealed(handoff.publicKey as String)
        char[] chars = (handoff.handoffToken as String).toCharArray()
        int at = chars.length - 5
        chars[at] = chars[at] == ('A' as char) ? ('B' as char) : ('A' as char)
        assertEquals(["SECRET_HANDOFF_INVALID"], blockedCodes(bundle, new String(chars)))
    }

    @Test
    void beginIsAuditLoggedOnTheTarget() {
        Map handoff = beginHandoff(DST)
        List rows = ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()
        assertEquals(1, rows.size())
        assertEquals("admin.TransferAdminServices.begin#SecretHandoff", rows[0].serviceName)
        assertEquals(DST, rows[0].targetId)
        assertTrue((rows[0].detailText as String).contains(handoff.keyFingerprint as String), rows[0].detailText as String)
        assertTrue((rows[0].detailText as String).contains("expires"), rows[0].detailText as String)
        assertFalse((rows[0].detailText as String).contains(handoff.handoffToken as String))
    }

    @Test
    void unreadableSecretAtSourceIsNeedsCredentials() {
        // Prod's OMS apiToken does not decrypt: Moqui reads it as its _DECRYPT_FAILED_ placeholder, which must never be
        // sealed (writing it back throws) and must not fail the transfer either.
        Closure update = { String value -> rawColumn("XFER_NS_AUTH", "apiToken", value) }
        update("zz-not-ciphertext")
        try {
            Map handoff = beginHandoff(DST)
            Map bundle = exportSealed(handoff.publicKey as String)
            Map auth = (bundle.connections as List<Map>).find { it.ref == AUTH_REF }
            assertEquals(["apiToken"], auth.unreadableSecrets)
            assertEquals(["password", "privateKeyPem"], (auth.sealedSecrets as Map).keySet().collect { it as String }.sort())
            assertFalse(JsonOutput.toJson(bundle).contains("_DECRYPT_FAILED_"))
            String sourceRow = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.TransferAdminServices.export#ConfigBundle")
                    .disableAuthz().useCache(false).one().detailText
            assertTrue(sourceRow.contains("unreadable") && sourceRow.contains("apiToken"), sourceRow)

            Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String)
            Map plan = preview.result.plan as Map
            assertEquals([], plan.blocking, "an unreadable source secret is a warning, not a block")
            Map line = (plan.lines as List<Map>).find { it.ref == AUTH_REF }
            assertEquals("NEEDS_CREDENTIALS", line.action)
            assertEquals(["password", "privateKeyPem"], line.secretsFilled)
            Map warning = (plan.warnings as List<Map>).find { it.code == "SECRET_UNREADABLE_AT_SOURCE" }
            assertEquals(AUTH_REF, warning?.ref, "${plan.warnings}")
            assertTrue((warning.message as String).contains("apiToken"))

            Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String, handoff.handoffToken as String)
            assertNoErrors(applied)
            def target = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", applied.result.idMap[AUTH_REF])
                    .disableAuthz().useCache(false).one()
            assertEquals(PASSWORD, target.password)
            assertNull(target.apiToken)
        } finally {
            update(null)
        }
    }

    @Test
    void emptyStringTargetSecretIsFilled() {
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_DST_AUTH", description: "dst auth", companyUserGroupId: DST,
                authType: "OAUTH2_M2M"])
        // Raw SQL: the entity layer stores a blank as null, but a hand load can leave '' in the column.
        rawColumn("XFER_DST_AUTH", "password", "")
        Map handoff = beginHandoff(DST)
        Map bundle = exportSealed(handoff.publicKey as String)
        Map map = [(AUTH_REF): "XFER_DST_AUTH"]
        Map preview = importInto(DST, bundle, map, false, null, handoff.handoffToken as String)
        assertEquals(["password", "privateKeyPem"], (preview.result.plan.lines as List<Map>).find { it.ref == AUTH_REF }.secretsFilled,
                "an empty string is as empty as null")
        assertNoErrors(importInto(DST, bundle, map, true, preview.result.planHash as String, handoff.handoffToken as String))
        assertEquals(PASSWORD, ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", "XFER_DST_AUTH")
                .disableAuthz().useCache(false).one().password)
    }

    @Test
    void fillingNeverOverwritesASecretSetMeanwhile() {
        // The guarded write the apply makes for a mapped connection, called directly: a value saved since the plan read
        // the row (a concurrent settings save) is kept.
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_DST_AUTH", description: "dst auth", companyUserGroupId: DST,
                authType: "OAUTH2_M2M", password: "saved-meanwhile"])
        List filled = ConfigTransferImporter.fillEmptySecrets(ec, "darpan.reconciliation.NsAuthConfig", "nsAuthConfigId", "XFER_DST_AUTH",
                DST, [password: "from-transfer", privateKeyPem: "pem-from-transfer"])
        assertEquals(["privateKeyPem"], filled)
        def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", "XFER_DST_AUTH").disableAuthz().useCache(false).one()
        assertEquals("saved-meanwhile", auth.password)
        assertEquals("pem-from-transfer", auth.privateKeyPem)
        assertThrows(IllegalStateException) {
            ConfigTransferImporter.fillEmptySecrets(ec, "darpan.reconciliation.NsAuthConfig", "nsAuthConfigId", "XFER_DST_AUTH", OTHER, [apiToken: "x"])
        }
    }

    @Test
    void missingTokenBlocks() {
        Map bundle = exportSealed(beginHandoff(DST).publicKey as String)
        assertEquals(["SECRET_HANDOFF_MISSING"], blockedCodes(bundle, null))
        Map other = beginHandoff(DST)
        assertEquals(["SECRET_HANDOFF_INVALID"], blockedCodes(bundle, other.handoffToken as String), "a token for another key")
    }

    @Test
    void tamperedCiphertextBlocks() {
        Map handoff = beginHandoff(DST)
        Map bundle = copy(exportSealed(handoff.publicKey as String))
        Map sealed = (bundle.connections as List<Map>).find { it.ref == AUTH_REF }.sealedSecrets.password as Map
        byte[] ct = Base64.decoder.decode(sealed.ct as String)
        ct[3] = (byte) (ct[3] ^ 0x10)
        sealed.ct = Base64.encoder.encodeToString(ct)
        Map plan = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String).result.plan as Map
        assertEquals([[code: "SECRET_UNREADABLE", ref: AUTH_REF]], (plan.blocking as List<Map>).collect { it.subMap(["code", "ref"]) })
        assertTrue((plan.blocking[0].message as String).contains("password"))
    }

    @Test
    void ciphertextMovedToAnotherFieldBlocks() {
        Map handoff = beginHandoff(DST)
        Map bundle = copy(exportSealed(handoff.publicKey as String))
        Map sealedSecrets = (bundle.connections as List<Map>).find { it.ref == AUTH_REF }.sealedSecrets as Map
        sealedSecrets.privateKeyPem = sealedSecrets.password
        Map plan = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String).result.plan as Map
        assertEquals(["SECRET_UNREADABLE"], plan.blocking*.code)
        assertTrue((plan.blocking[0].message as String).contains("privateKeyPem"))
    }

    @Test
    void existingTargetSecretIsKept() {
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_DST_AUTH", description: "dst auth", companyUserGroupId: DST,
                authType: "OAUTH2_M2M", password: "dst-own-pass"])
        Map handoff = beginHandoff(DST)
        Map bundle = exportSealed(handoff.publicKey as String)
        Map map = [(AUTH_REF): "XFER_DST_AUTH"]
        Map preview = importInto(DST, bundle, map, false, null, handoff.handoffToken as String)
        Map line = (preview.result.plan.lines as List<Map>).find { it.ref == AUTH_REF }
        assertEquals("MAPPED", line.action)
        assertEquals(["privateKeyPem"], line.secretsFilled)
        assertEquals(["password"], line.secretsKept)
        assertNoErrors(importInto(DST, bundle, map, true, preview.result.planHash as String, handoff.handoffToken as String))
        def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", "XFER_DST_AUTH").disableAuthz().useCache(false).one()
        assertEquals("dst-own-pass", auth.password, "a set secret is never overwritten")
        assertEquals(PEM, auth.privateKeyPem, "an empty one is filled")
    }

    @Test
    void emptyMappedSecretIsFilled() {
        create("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "XFER_DST_AUTH", description: "dst auth", companyUserGroupId: DST,
                authType: "OAUTH2_M2M"])
        Map handoff = beginHandoff(DST)
        Map bundle = exportSealed(handoff.publicKey as String)
        Map map = [(AUTH_REF): "XFER_DST_AUTH"]
        Map preview = importInto(DST, bundle, map, false, null, handoff.handoffToken as String)
        Map line = (preview.result.plan.lines as List<Map>).find { it.ref == AUTH_REF }
        assertEquals("MAPPED", line.action)
        assertEquals(["password", "privateKeyPem"], line.secretsFilled)
        assertEquals(1, preview.result.plan.counts.CREDENTIALS_FILLED)
        assertNoErrors(importInto(DST, bundle, map, true, preview.result.planHash as String, handoff.handoffToken as String))
        def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", "XFER_DST_AUTH").disableAuthz().useCache(false).one()
        assertEquals(PASSWORD, auth.password)
        assertEquals(PEM, auth.privateKeyPem)
    }

    @Test
    void secretExportIsAuditLogged() {
        Map handoff = beginHandoff(DST)
        exportSealed(handoff.publicKey as String)
        List rows = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.TransferAdminServices.export#ConfigBundle")
                .disableAuthz().useCache(false).list()
        assertEquals(1, rows.size(), "one source row per export that carries secrets")
        assertEquals("admin.TransferAdminServices.export#ConfigBundle", rows[0].serviceName)
        assertEquals(SRC, rows[0].targetId)
        String detail = rows[0].detailText as String
        [AUTH_REF, "password", "privateKeyPem", handoff.keyFingerprint as String].each { assertTrue(detail.contains(it), "${it} in ${detail}") }
        [PASSWORD, PEM].each { assertFalse(detail.contains(it)) }
    }

    @Test
    void noSecretsWithoutKey() {
        Map bundle = exportSource()
        assertFalse(bundle.containsKey("secrets"))
        assertFalse(JsonOutput.toJson(bundle).contains("sealedSecrets"))
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
    }

    @Test
    void secondImportAfterCopyIsUnchanged() {
        Map handoff = beginHandoff(DST)
        Map bundle = exportSealed(handoff.publicKey as String)
        Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String)
        Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String, handoff.handoffToken as String)
        assertNoErrors(applied)

        Map again = beginHandoff(DST)
        Map rebundle = exportSealed(again.publicKey as String)
        Map plan = importInto(DST, rebundle, [(AUTH_REF): applied.result.idMap[AUTH_REF]], false, null, again.handoffToken as String)
                .result.plan as Map
        assertEquals([], plan.blocking)
        (plan.lines as List<Map>).each {
            assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
        }
        Map authLine = (plan.lines as List<Map>).find { it.ref == AUTH_REF }
        assertEquals([], authLine.secretsFilled)
        assertEquals(["password", "privateKeyPem"], authLine.secretsKept)
        assertEquals(0, plan.counts.CREDENTIALS_FILLED)
        assertFalse((plan.warnings as List<Map>).any { it.code == "NEEDS_CREDENTIALS" })
    }

    // ---------------------------------------------------------------- DAR-BE-188: automations and their chat spaces

    @Test
    void withoutIncludeAutomationsTheBundleIsUnchanged() {
        withAutomation {
            Map plain = exportSource()
            Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, auditCodes: ["month-close"],
                    sourceInstanceLabel: "local", includeAutomations: false])
            assertNoErrors(out)
            assertEquals(JsonOutput.toJson(plain), JsonOutput.toJson(out.result.bundle), "false is the default")
            ["automations", "chatSpaces", "lifecycles"].each { assertFalse(plain.containsKey(it), "${it} only travel when asked for") }
            assertFalse(JsonOutput.toJson(plain).contains("XFER_SPACE"))
        }
    }

    @Test
    void includeAutomationsCarriesTheAutomationAndItsChatSpaceSealed() {
        withAutomation {
            Map handoff = beginHandoff(DST)
            Map bundle = exportWhole(handoff.publicKey as String)
            String json = JsonOutput.toJson(bundle)
            [WEBHOOK, OLD_WEBHOOK, "hook-secret-xfer"].each { assertFalse(json.contains(it), "no webhook in clear: ${it}") }

            Map automation = (bundle.automations as List<Map>).find { it.ref == AUTO_REF }
            assertNotNull(automation, "the run's automation travels: ${bundle.automations}")
            assertEquals(SPACE_REF, automation.refs.chatSpaceId)
            assertEquals("RUN:XFER_RS", automation.refs.savedRunId)
            assertEquals("RUN:XFER_RS", automation.refs.ruleSetId)
            assertEquals("SCOPE:XFER_RS#0", automation.refs.compareScopeId, "its compare scope by run and position")
            ["chatSpaceId", "ruleSetId", "compareScopeId", "savedRunId", "isActive", "reconciliationRunId", "reconciliationMappingId"].each {
                assertFalse((automation.fields as Map).containsKey(it), "${it} is no plain value in a transfer")
            }

            Map space = (bundle.chatSpaces as List<Map>).find { it.ref == SPACE_REF }
            assertNotNull(space, "the space it posts to travels: ${bundle.chatSpaces}")
            assertEquals("Ops alerts", space.label)
            assertEquals(["chatProviderEnumId", "isActive", "slackChannelId", "slackChannelName", "spaceName"],
                    (space.fields as Map).keySet().collect { it as String }.sort())
            assertEquals(["googleChatWebhookUrl", "webhookUrl"], space.secretFields)
            assertEquals(["googleChatWebhookUrl", "webhookUrl"], (space.sealedSecrets as Map).keySet().collect { it as String }.sort())
            assertFalse(JsonOutput.toJson(bundle.chatSpaces).contains("XFER_OTHER_SPACE"), "another tenant's space never travels")

            String detail = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.TransferAdminServices.export#ConfigBundle")
                    .disableAuthz().useCache(false).one().detailText
            [AUTH_REF, SPACE_REF, "webhookUrl"].each { assertTrue(detail.contains(it), "${it} in ${detail}") }
            assertFalse(detail.contains("hook-secret-xfer"))

            Map unsealed = exportWhole()
            assertFalse(JsonOutput.toJson(unsealed).contains("hook-secret-xfer"), "without a key nothing secret is read")
            assertNull((unsealed.chatSpaces as List<Map>)[0].sealedSecrets)
        }
    }

    @Test
    void importCreatesTheSpaceWithItsWebhookAndThePausedAutomationPointsAtIt() {
        withAutomation {
            Map handoff = beginHandoff(DST)
            Map bundle = exportWhole(handoff.publicKey as String)
            Map map = [(AUTH_REF): "CREATE"]
            Map preview = importInto(DST, bundle, map, false, null, handoff.handoffToken as String)
            assertNoErrors(preview)
            Map plan = preview.result.plan as Map
            assertEquals([], plan.blocking)
            Map spaceLine = (plan.lines as List<Map>).find { it.ref == SPACE_REF }
            assertEquals("CHAT_SPACE", spaceLine?.kind, "${plan.lines}")
            assertEquals("CREATE", spaceLine.action)
            assertEquals(["googleChatWebhookUrl", "webhookUrl"], spaceLine.secretsFilled)
            assertEquals("CREATE", (plan.lines as List<Map>).find { it.ref == AUTO_REF }?.action)
            assertTrue((plan.lines as List<Map>)*.kind.indexOf("CHAT_SPACE") < (plan.lines as List<Map>)*.kind.indexOf("AUTOMATION"))

            Map applied = importInto(DST, bundle, map, true, preview.result.planHash as String, handoff.handoffToken as String)
            assertNoErrors(applied)
            Map idMap = applied.result.idMap as Map
            def space = ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", idMap[SPACE_REF])
                    .disableAuthz().useCache(false).one()
            assertNotEquals("XFER_SPACE", space.chatSpaceId)
            assertEquals(DST, space.companyUserGroupId)
            assertEquals("Ops alerts", space.spaceName)
            assertEquals(WEBHOOK, space.webhookUrl, "the webhook is filled from its sealed value")
            assertEquals(OLD_WEBHOOK, space.googleChatWebhookUrl)
            assertNull(space.lastBadgeGifDate, "instance-local columns stay behind")

            def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", idMap[AUTO_REF])
                    .disableAuthz().useCache(false).one()
            assertEquals(DST, automation.companyUserGroupId)
            assertEquals(space.chatSpaceId, automation.chatSpaceId, "the automation posts to the new space")
            assertEquals("N", automation.isActive, "an imported automation starts paused")
            assertEquals(idMap["RUN:XFER_RS"], automation.savedRunId)
            assertEquals(idMap["RUN:XFER_RS"], automation.ruleSetId)
            assertEquals(ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", idMap["RUN:XFER_RS"]).disableAuthz()
                    .useCache(false).one().compareScopeId, automation.compareScopeId, "and reads the new run's compare scope")
            def source = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", automation.automationId)
                    .disableAuthz().useCache(false).one()
            assertEquals(idMap["NETSUITE_SUITEQL:XFER_Q1"], (new JsonSlurper().parseText(source.safeMetadataJson as String) as Map).parameters.nsSuiteQlSourceQueryId)
            assertEquals(1L, ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter")
                    .condition("automationId", automation.automationId).disableAuthz().useCache(false).count())

            String everythingSaid = JsonOutput.toJson([preview.result, applied.result,
                    ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()*.getMap()])
            assertFalse(everythingSaid.contains("hook-secret-xfer"), "no webhook in a plan or the audit log")
        }
    }

    @Test
    void reimportAfterTheAutomationTransferIsAllUnchanged() {
        withAutomation {
            Map handoff = beginHandoff(DST)
            Map bundle = exportWhole(handoff.publicKey as String)
            Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String)
            Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String, handoff.handoffToken as String)
            assertNoErrors(applied)

            Map again = beginHandoff(DST)
            Map plan = importInto(DST, exportWhole(again.publicKey as String), [(AUTH_REF): applied.result.idMap[AUTH_REF]], false, null,
                    again.handoffToken as String).result.plan as Map
            assertEquals([], plan.blocking)
            (plan.lines as List<Map>).each {
                assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
            }
            Map spaceLine = (plan.lines as List<Map>).find { it.ref == SPACE_REF }
            assertEquals([], spaceLine.secretsFilled)
            assertEquals(["googleChatWebhookUrl", "webhookUrl"], spaceLine.secretsKept)
            assertEquals(0, plan.counts.CREDENTIALS_FILLED)
        }
    }

    @Test
    void anExistingSpacesWebhookIsKept() {
        withAutomation {
            // Matched by name whatever its case (spaceName is unique per tenant, case-insensitive); only its empty webhook is filled.
            create("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "XFER_DST_SPACE", companyUserGroupId: DST, spaceName: "ops ALERTS",
                    chatProviderEnumId: "CHAT_PROV_GOOGLE", webhookUrl: "https://chat.example/dst-own", isActive: "Y"])
            Map handoff = beginHandoff(DST)
            Map bundle = exportWhole(handoff.publicKey as String)
            Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String)
            assertNoErrors(preview)
            assertEquals([], preview.result.plan.blocking)
            Map line = (preview.result.plan.lines as List<Map>).find { it.ref == SPACE_REF }
            assertEquals("XFER_DST_SPACE", line.targetId)
            assertEquals(["googleChatWebhookUrl"], line.secretsFilled)
            assertEquals(["webhookUrl"], line.secretsKept)
            Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String, handoff.handoffToken as String)
            assertNoErrors(applied)
            def space = ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", "XFER_DST_SPACE").disableAuthz().useCache(false).one()
            assertEquals("https://chat.example/dst-own", space.webhookUrl, "a set webhook is never overwritten")
            assertEquals(OLD_WEBHOOK, space.googleChatWebhookUrl, "an empty one is filled")
            assertEquals(1L, ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("companyUserGroupId", DST).disableAuthz().useCache(false).count())
            assertEquals("XFER_DST_SPACE", ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", applied.result.idMap[AUTO_REF])
                    .disableAuthz().useCache(false).one().chatSpaceId)
        }
    }

    @Test
    void anUnsealedSpaceIsCreatedWithoutItsWebhook() {
        withAutomation {
            Map bundle = exportWhole()
            Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"])
            assertNoErrors(preview)
            Map plan = preview.result.plan as Map
            assertEquals([], plan.blocking)
            assertEquals("NEEDS_CREDENTIALS", (plan.lines as List<Map>).find { it.ref == SPACE_REF }.action)
            assertTrue((plan.warnings as List<Map>).any { it.code == "NEEDS_CREDENTIALS" && it.ref == SPACE_REF }, "${plan.warnings}")
            Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String)
            assertNoErrors(applied)
            def space = ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", applied.result.idMap[SPACE_REF])
                    .disableAuthz().useCache(false).one()
            assertNull(space.webhookUrl)
        }
    }

    @Test
    void aSlackSpaceArrivesWithoutItsWorkspaceInstall() {
        withAutomation {
            create("darpan.reconciliation.SlackWorkspaceInstall", [slackInstallId: "XFER_SLACK", companyUserGroupId: SRC, teamId: "T1",
                    teamName: "Xfer"])
            ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", "XFER_SPACE").disableAuthz().one()
                    .setAll([chatProviderEnumId: "CHAT_PROV_SLACK", slackInstallId: "XFER_SLACK", slackChannelId: "C123", slackChannelName: "ops"]).update()
            try {
                Map bundle = exportWhole()
                assertFalse(JsonOutput.toJson(bundle).contains("XFER_SLACK"), "the install is this instance's, not config")
                Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"])
                Map plan = preview.result.plan as Map
                assertEquals([], plan.blocking)
                assertTrue((plan.warnings as List<Map>).any { it.code == "SLACK_INSTALL_NOT_COPIED" && it.ref == SPACE_REF }, "${plan.warnings}")
                Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String)
                assertNoErrors(applied)
                def space = ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", applied.result.idMap[SPACE_REF])
                        .disableAuthz().useCache(false).one()
                assertNull(space.slackInstallId)
                assertEquals("C123", space.slackChannelId)
            } finally {
                ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", "XFER_SPACE").disableAuthz().one()
                        .setAll([chatProviderEnumId: "CHAT_PROV_GOOGLE", slackInstallId: null, slackChannelId: null, slackChannelName: null]).update()
                ec.entity.find("darpan.reconciliation.SlackWorkspaceInstall").condition("slackInstallId", "XFER_SLACK").disableAuthz().deleteAll()
            }
        }
    }

    @Test
    void aLifecycleItsOwnedRunAndItsAutomationTransferAndConverge() {
        withAutomation {
            create("darpan.reconciliation.Reconciliation", [reconciliationId: "XFER_LC", reconciliationName: "Sales orders",
                    companyUserGroupId: SRC, buildMode: "STAGES"])
            create("darpan.rule.RuleSet", [ruleSetId: "XFER_RS_LC", ruleSetName: "Sales orders: start", version: "1.0",
                    companyUserGroupId: SRC, ownerReconciliationId: "XFER_LC"])
            create("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: "XFER_LC_Q1", reconciliationId: "XFER_LC",
                    companyUserGroupId: SRC, runName: "Start", ruleSetId: "XFER_RS_LC", runSequence: 1L, questionRole: "START", isActive: "Y"])
            create("darpan.reconciliation.ReconciliationAutomation", [automationId: "XFER_LC_AUTO", automationName: "Sales orders every morning",
                    companyUserGroupId: SRC, inputModeEnumId: "AUT_IN_API_RANGE", savedRunType: "reconciliation", savedRunId: "XFER_LC",
                    scheduleExpr: "0 0 7 * * ?", windowTimeZone: "America/Los_Angeles", chatSpaceId: "XFER_SPACE", isActive: "Y"])
            try {
                Map bundle = exportWhole()
                assertEquals("LIFECYCLE:XFER_LC", (bundle.runs as List<Map>).find { it.ref == "RUN:XFER_RS_LC" }?.ownedBy)
                Map applied = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
                assertNoErrors(applied)
                Map idMap = applied.result.idMap as Map
                assertEquals(idMap["LIFECYCLE:XFER_LC"], ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", idMap["RUN:XFER_RS_LC"])
                        .disableAuthz().useCache(false).one().ownerReconciliationId, "the run is the new lifecycle's")
                def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", idMap["AUTOMATION:XFER_LC_AUTO"])
                        .disableAuthz().useCache(false).one()
                assertEquals(idMap["LIFECYCLE:XFER_LC"], automation.savedRunId)
                assertEquals(idMap[SPACE_REF], automation.chatSpaceId)
                assertEquals("N", automation.isActive)

                Map plan = importInto(DST, exportWhole(), [(AUTH_REF): idMap[AUTH_REF]]).result.plan as Map
                assertEquals([], plan.blocking, "a run the bundle's own lifecycle owns here is not someone else's")
                (plan.lines as List<Map>).each {
                    assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
                }
            } finally {
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "XFER_LC_AUTO").disableAuthz().deleteAll()
                ec.entity.find("darpan.reconciliation.ReconciliationRun").condition("reconciliationRunId", "XFER_LC_Q1").disableAuthz().deleteAll()
                ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "XFER_RS_LC").disableAuthz().deleteAll()
                ec.entity.find("darpan.reconciliation.Reconciliation").condition("reconciliationId", "XFER_LC").disableAuthz().deleteAll()
            }
        }
    }

    @Test
    void aLifecycleWhoseQuestionsBranchReimportsUnchanged() {
        // The shape a real stages lifecycle has: a START question, a child under it, and two grandchildren on its YES and
        // NO branches, run sequences with gaps, isActive left unset; each question's parent is a QUESTION ref.
        List<List> questions = [["XFER_BR_Q1", null, 1L, null, "START"], ["XFER_BR_Q2", "XFER_BR_Q1", 9L, "YES", null],
                                ["XFER_BR_Q3", "XFER_BR_Q2", 10L, "YES", "EXCUSED"], ["XFER_BR_Q4", "XFER_BR_Q2", 11L, "NO", null]]
        create("darpan.reconciliation.Reconciliation", [reconciliationId: "XFER_BR", reconciliationName: "Branching orders",
                companyUserGroupId: SRC, buildMode: "STAGES"])
        questions.each { List q ->
            create("darpan.rule.RuleSet", [ruleSetId: "RS_${q[0]}".toString(), ruleSetName: "Branching orders: ${q[0]}".toString(),
                    version: "1.0", companyUserGroupId: SRC, ownerReconciliationId: "XFER_BR"])
            create("darpan.reconciliation.ReconciliationRun", [reconciliationRunId: q[0], reconciliationId: "XFER_BR", companyUserGroupId: SRC,
                    runName: "Question ${q[0]}".toString(), ruleSetId: "RS_${q[0]}".toString(), parentReconciliationRunId: q[1],
                    runSequence: q[2], parentBranch: q[3], questionRole: q[4]])
        }
        try {
            Map bundle = exportWhole()
            Map lifecycle = (bundle.lifecycles as List<Map>).find { it.ref == "LIFECYCLE:XFER_BR" }
            assertEquals("QUESTION:XFER_BR_Q2", (lifecycle.children.ReconciliationRun as List<Map>)[3].refs.parentReconciliationRunId)
            Map applied = applyPlan(DST, bundle, [(AUTH_REF): "CREATE"])
            assertNoErrors(applied)
            Map idMap = applied.result.idMap as Map
            // The data: each question under the TARGET copy of its parent.
            questions.each { List q ->
                def row = ec.entity.find("darpan.reconciliation.ReconciliationRun").condition("reconciliationRunId", idMap["QUESTION:${q[0]}"])
                        .disableAuthz().useCache(false).one()
                assertEquals(idMap["LIFECYCLE:XFER_BR"], row.reconciliationId)
                assertEquals(q[1] ? idMap["QUESTION:${q[1]}"] : null, row.parentReconciliationRunId, "${q[0]}'s parent")
                assertEquals(idMap["RUN:RS_${q[0]}"], row.ruleSetId)
            }

            Map plan = importInto(DST, exportWhole(), [(AUTH_REF): idMap[AUTH_REF]]).result.plan as Map
            assertEquals([], plan.blocking)
            (plan.lines as List<Map>).each {
                assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
            }
        } finally {
            questions.reverse().each { List q ->
                ec.entity.find("darpan.reconciliation.ReconciliationRun").condition("reconciliationRunId", q[0]).disableAuthz().deleteAll()
                ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "RS_${q[0]}".toString()).disableAuthz().deleteAll()
            }
            ec.entity.find("darpan.reconciliation.Reconciliation").condition("reconciliationId", "XFER_BR").disableAuthz().deleteAll()
        }
    }

    @Test
    void aVersionKeepsTheAutomationsIdsPlainAndCarriesNoSpaces() {
        // A tenant config version restores into its own tenant: its pack (and so its hash) is what it was before DAR-BE-188.
        withAutomation {
            Map pack = ConfigTransferExporter.bundle(ec, SRC, ["month-close"], [], [], null, null, true)
            Map automation = (pack.automations as List<Map>).find { it.ref == AUTO_REF }
            assertEquals("XFER_SPACE", automation.fields.chatSpaceId)
            assertEquals("XFER_RS", automation.fields.ruleSetId)
            assertEquals("XFER_CS", automation.fields.compareScopeId)
            assertEquals(["savedRunId"], (automation.refs as Map).keySet() as List)
            assertFalse(pack.containsKey("chatSpaces"))
        }
    }

    @Test
    void importedChecksLinkToTheImportedAutomationSoTheScheduleMakesNoNewOne() {
        withAutomation {
            // A daily automation on the same run as the month-close one: only the export can say which one the checks use.
            create("darpan.reconciliation.ReconciliationAutomation", [automationId: "XFER_AUTO_DAILY", automationName: "Returns daily",
                    companyUserGroupId: SRC, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "XFER_RS", savedRunType: "ruleset",
                    ruleSetId: "XFER_RS", compareScopeId: "XFER_CS", scheduleExpr: "0 0 5 * * ?", isActive: "Y"])
            linkChecks("XFER_AUTO")
            try {
                Map handoff = beginHandoff(DST)
                Map bundle = exportWhole(handoff.publicKey as String)
                (bundle.checks as List<Map>).each { Map check ->
                    assertEquals(AUTO_REF, check.refs.automationId, "${check.ref} names the automation it runs under")
                    assertFalse((check.fields as Map).containsKey("automationId"))
                }
                Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, handoff.handoffToken as String)
                assertNoErrors(preview)
                assertEquals([], preview.result.plan.blocking)
                Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String, handoff.handoffToken as String)
                assertNoErrors(applied)
                Map idMap = applied.result.idMap as Map
                ["CHECK:XFER_CHK1", "CHECK:XFER_CHK2"].each { String ref ->
                    def check = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", idMap[ref]).disableAuthz().useCache(false).one()
                    assertEquals(idMap[AUTO_REF], check.automationId, "${ref} runs under the imported month-close automation")
                    assertNotEquals(idMap["AUTOMATION:XFER_AUTO_DAILY"], check.automationId)
                }

                List made = AuditSettingsSupport.ensureAutomations(ec, DST, [auditId: idMap["AUDIT:XFER_AUDIT"], auditCode: "month-close",
                        auditName: "Month close"])
                assertEquals([], made, "the audit schedule reuses the imported automation")
                assertEquals(2L, ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("companyUserGroupId", DST)
                        .disableAuthz().useCache(false).count())

                Map again = beginHandoff(DST)
                Map plan = importInto(DST, exportWhole(again.publicKey as String), [(AUTH_REF): idMap[AUTH_REF]], false, null,
                        again.handoffToken as String).result.plan as Map
                assertEquals([], plan.blocking)
                (plan.lines as List<Map>).each {
                    assertEquals(it.ref == AUTH_REF ? "MAPPED" : "UNCHANGED", it.action, "${it.ref}: ${it.changes}")
                }
            } finally {
                linkChecks(null)
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "XFER_AUTO_DAILY").disableAuthz().deleteAll()
            }
        }
    }

    @Test
    void aCheckWhoseAutomationIsNotCarriedArrivesUnlinked() {
        withAutomation {
            linkChecks("XFER_AUTO")
            try {
                Map bundle = copy(exportWhole())
                bundle.automations = []
                Map preview = importInto(DST, bundle, [(AUTH_REF): "CREATE"])
                assertNoErrors(preview)
                Map plan = preview.result.plan as Map
                assertEquals([], plan.blocking, "an automation left out unlinks its checks, it does not block them")
                assertEquals(["CHECK:XFER_CHK1", "CHECK:XFER_CHK2"], (plan.warnings as List<Map>)
                        .findAll { it.code == "CHECK_AUTOMATION_NOT_CARRIED" }*.ref.sort(), "${plan.warnings}")
                Map applied = importInto(DST, bundle, [(AUTH_REF): "CREATE"], true, preview.result.planHash as String)
                assertNoErrors(applied)
                assertNull(ec.entity.find("darpan.audit.AuditCheck").condition("checkId", applied.result.idMap["CHECK:XFER_CHK1"])
                        .disableAuthz().useCache(false).one().automationId)
            } finally {
                linkChecks(null)
            }
        }
    }

    @Test
    void aChecksAutomationTravelsOnlyWithAutomations() {
        withAutomation {
            linkChecks("XFER_AUTO")
            try {
                Map plain = exportSource()
                assertFalse(JsonOutput.toJson(plain).contains("AUTOMATION:"), "without includeAutomations a check names no automation")
                assertEquals(["savedRunId"], ((plain.checks as List<Map>)[0].refs as Map).keySet() as List)
                Map pack = ConfigTransferExporter.bundle(ec, SRC, ["month-close"], [], [], null, null, true)
                (pack.checks as List<Map>).each { Map check ->
                    assertEquals(["savedRunId"], (check.refs as Map).keySet() as List, "a version's checks are what they were")
                    assertFalse((check.fields as Map).containsKey("automationId"))
                }
            } finally {
                linkChecks(null)
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** Points SRC's two checks at an automation (null unlinks them), as the audit schedule does. */
    private void linkChecks(String automationId) {
        ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "in", ["XFER_CHK1", "XFER_CHK2"]).disableAuthz().list().each {
            it.set("automationId", automationId); it.update()
        }
    }

    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/XFER/messages?key=hook-secret-xfer"
    private static final String OLD_WEBHOOK = "https://chat.googleapis.com/v1/spaces/XFER/messages?key=old-hook-secret-xfer"
    private static final String SPACE_REF = "CHAT_SPACE:XFER_SPACE"
    private static final String AUTO_REF = "AUTOMATION:XFER_AUTO"

    /** SRC's run gets a morning automation posting to its Google Chat space (and OTHER a space of the same name). */
    private void withAutomation(Closure body) {
        create("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "XFER_SPACE", companyUserGroupId: SRC, spaceName: "Ops alerts",
                chatProviderEnumId: "CHAT_PROV_GOOGLE", webhookUrl: WEBHOOK, googleChatWebhookUrl: OLD_WEBHOOK, isActive: "Y",
                lastBadgeGifDate: T0, createdByUserId: "XFER_SUPER"])
        create("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "XFER_OTHER_SPACE", companyUserGroupId: OTHER, spaceName: "Ops alerts",
                chatProviderEnumId: "CHAT_PROV_GOOGLE", webhookUrl: "https://chat.example/other-hook", isActive: "Y"])
        create("darpan.reconciliation.ReconciliationAutomation", [automationId: "XFER_AUTO", automationName: "Returns every morning",
                companyUserGroupId: SRC, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "XFER_RS", savedRunType: "ruleset",
                ruleSetId: "XFER_RS", compareScopeId: "XFER_CS", scheduleExpr: "0 0 6 * * ?", windowTimeZone: "America/Los_Angeles",
                chatSpaceId: "XFER_SPACE", isActive: "Y"])
        create("darpan.reconciliation.ReconciliationAutomationSource", [automationId: "XFER_AUTO", fileSide: "FILE_1",
                companyUserGroupId: SRC, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"XFER_Q1"}}'])
        create("darpan.reconciliation.ReconciliationAutomationSourceFilter", [automationId: "XFER_AUTO", fileSide: "FILE_1",
                sequenceNum: 1L, companyUserGroupId: SRC, fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: '["Closed"]'])
        try {
            body()
        } finally {
            wipeTenant(DST)
            ec.entity.find("darpan.reconciliation.ReconciliationAutomationSourceFilter").condition("automationId", "XFER_AUTO").disableAuthz().deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", "XFER_AUTO").disableAuthz().deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "XFER_AUTO").disableAuthz().deleteAll()
            ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", "in", ["XFER_SPACE", "XFER_OTHER_SPACE"])
                    .disableAuthz().deleteAll()
        }
    }

    private Map exportWhole(String publicKey = null) {
        Map params = [tenantUserGroupId: SRC, auditCodes: ["month-close"], sourceInstanceLabel: "local", includeAutomations: true]
        if (publicKey) params.secretsPublicKey = publicKey
        Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", params)
        assertNoErrors(out)
        return out.result.bundle as Map
    }

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
                ruleName: "Status Sync", enabled: "Y", ruleType: "FIELD_COMPARISON", companyUserGroupId: SRC])
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
                conclusionEnumId: "CONC_XFER_SEED", outcome: "EXPECTED", companyUserGroupId: SRC])
        create("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "XFER_CS", sequenceNum: 10L, conditionSeq: 1L,
                subject: "FILE_1", fieldExpression: "status", operator: "IN", conditionValues: "A", checkLabel: "Status A",
                companyUserGroupId: SRC])

        create("darpan.audit.Audit", [auditId: "XFER_AUDIT", companyUserGroupId: SRC, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH", scheduleTime: "01:30", scheduleTimeZone: "America/Los_Angeles",
                scheduleActive: "Y"])
        create("darpan.audit.AuditStatement", [statementId: "XFER_AUDIT_R4", companyUserGroupId: SRC, auditId: "XFER_AUDIT",
                statementCode: "R4", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", statementText: "Returns have memos",
                isActive: "Y", sequenceNum: 1L])
        create("darpan.audit.AuditStatement", [statementId: "XFER_AUDIT_R8", companyUserGroupId: SRC, auditId: "XFER_AUDIT",
                statementCode: "R8", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_DERIVED", statementText: "Memos close",
                isActive: "Y", sequenceNum: 2L])
        create("darpan.audit.AuditCheck", [checkId: "XFER_CHK1", companyUserGroupId: SRC, checkCode: "returns-vs-memos",
                label: "Returns vs memos", savedRunId: "XFER_RS", otherSideDateField: "trandate",
                fixWhereEnumId: "NETSUITE", headlineTemplate: "Return {id} has no memo"])
        create("darpan.audit.AuditCheck", [checkId: "XFER_CHK2", companyUserGroupId: SRC, checkCode: "memos-open",
                label: "Memos left open", savedRunId: "XFER_RS"])
        create("darpan.audit.AuditStatementCheck", [statementId: "XFER_AUDIT_R4", checkId: "XFER_CHK1", companyUserGroupId: SRC,
                sequenceNum: 1L, diffTypes: "FIELD_MISMATCH", ruleIds: "XFER_RULE_B"])
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

    private Map importInto(String tenantId, Map bundle, Map connectionMap, boolean apply = false, String planHash = null,
                           String handoffToken = null) {
        Map params = [tenantUserGroupId: tenantId, bundle: bundle, connectionMap: connectionMap, apply: apply]
        if (planHash) params.planHash = planHash
        if (handoffToken) params.secretHandoffToken = handoffToken
        return callAs(SUPER_USERNAME, "${SERVICES}.import#ConfigBundle", params)
    }

    /** Preview, then apply with the preview's hash: what the admin app does. */
    private Map applyPlan(String tenantId, Map bundle, Map connectionMap) {
        Map preview = importInto(tenantId, bundle, connectionMap)
        assertNoErrors(preview)
        return importInto(tenantId, bundle, connectionMap, true, preview.result.planHash as String)
    }

    /** Writes an NsAuthConfig column as raw SQL, bypassing the entity layer's encryption. */
    private void rawColumn(String nsAuthConfigId, String field, String value) {
        def ed = ec.entity.getEntityDefinition("darpan.reconciliation.NsAuthConfig")
        def connection = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.reconciliation.NsAuthConfig"))
        try {
            def statement = connection.prepareStatement("UPDATE ${ed.getFullTableName()} SET ${ed.getColumnName(field)} = ? " +
                    "WHERE ${ed.getColumnName("nsAuthConfigId")} = ?")
            statement.setString(1, value)
            statement.setString(2, nsAuthConfigId)
            statement.executeUpdate()
            statement.close()
        } finally {
            connection.close()
        }
    }

    private Map beginHandoff(String tenantId) {
        Map out = callAs(SUPER_USERNAME, "${SERVICES}.begin#SecretHandoff", [tenantUserGroupId: tenantId])
        assertNoErrors(out)
        return out.result as Map
    }

    private Map exportSealed(String publicKey, Map extra = [:]) {
        Map out = callAs(SUPER_USERNAME, "${SERVICES}.export#ConfigBundle", [tenantUserGroupId: SRC, auditCodes: ["month-close"],
                sourceInstanceLabel: "local", secretsPublicKey: publicKey] + extra)
        assertNoErrors(out)
        return out.result.bundle as Map
    }

    private List<String> blockedCodes(Map bundle, String token) {
        Map out = importInto(DST, bundle, [(AUTH_REF): "CREATE"], false, null, token)
        assertNoErrors(out)
        return (out.result.plan.blocking as List<Map>)*.code.unique()
    }

    /** The refs a plan blocks with one code, sorted. */
    private List<String> blocked(Map bundle, String code, Map connectionMap = [(AUTH_REF): "CREATE"]) {
        Map out = importInto(DST, bundle, connectionMap)
        assertNoErrors(out)
        return (out.result.plan.blocking as List<Map>).findAll { it.code == code }*.ref.collect { it as String }.sort()
    }

    private static List<Map> sourcesOf(Map run) {
        return ((run.children.RuleSetCompareScope as List<Map>)[0].children.RuleSetCompareSource) as List<Map>
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
        // A question names its parent question: unhook them so one bulk delete never trips that key.
        ec.entity.find("darpan.reconciliation.ReconciliationRun").condition("companyUserGroupId", tenantId)
                .condition("parentReconciliationRunId", "is-not-null", null).disableAuthz().useCache(false).list()
                .each { it.set("parentReconciliationRunId", null); it.update() }
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
