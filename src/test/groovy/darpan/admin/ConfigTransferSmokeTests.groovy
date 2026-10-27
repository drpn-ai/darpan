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
        create("darpan.rule.Rule", [ruleId: "XFER_RULE_A", ruleSetId: "XFER_RS", sequenceNum: 10L, ruleText: "a = b",
                enabled: "Y", ruleType: "FIELD_COMPARISON", companyUserGroupId: SRC])
        create("darpan.rule.Rule", [ruleId: "XFER_RULE_B", ruleSetId: "XFER_RS", sequenceNum: 20L, ruleText: "c = d",
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
