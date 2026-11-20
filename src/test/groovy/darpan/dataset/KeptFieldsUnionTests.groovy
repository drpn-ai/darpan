package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (rulings 3b1-7, P3b1-9), on a made-up system only (KC_PROJ: its extract and projection service are
 * test-only, classpath://dataset/MadeUpProjectionConnector.xml): what a rolling dataset's read asks of its source.
 * DatasetSupport.unionFields is the union, over the tenant's current run sources on the dataset's system, connection and
 * options key, of their keep and filter fields (the copy keeps them, trimmed to what the system's read can keep) and their
 * read fields (sent apart, as a live read sends them); refresh#Dataset sends both, the connector's dataset read parameters,
 * and records them with the files the read wrote beside its records. Another tenant's sources never count (A7).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeptFieldsUnionTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String EXTRACT = "reconciliation.KeptCopyTestExtractionServices.extract#MadeUpRecords"
    private static final Instant READ_AT = Instant.parse("2026-11-20T00:00:00Z")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "kept-fields-union")
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(root.path.contains("test-data-manager"), "tests never write the dev datamanager: ${root}")
        new File(root, "datasets").deleteDir()
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DatasetSeedData.xml", "component://darpan/data/AutomationSeedData.xml")
        ec.entity.makeDataLoader().location("classpath://dataset/MadeUpProjectionConnector.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        Closure<String> scope = { String ruleSet, String scopeId, String tenant = A, String extra = "" ->
            """<darpan.rule.RuleSet ruleSetId="${ruleSet}" ruleSetName="${ruleSet}" companyUserGroupId="${tenant}" ${extra}/>
            <darpan.rule.RuleSetCompareScope compareScopeId="${scopeId}" ruleSetId="${ruleSet}" objectType="ORDER" companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> side = { String scopeId, String fileSide, String conn, String tenant = A ->
            """<darpan.rule.RuleSetCompareSource compareScopeId="${scopeId}" fileSide="${fileSide}" systemEnumId="KC_PROJ" fileTypeEnumId="DftJson"
                sourceConfigId="${conn}" sourceConfigType="KC_PROJ_TYPE" recordRootExpression="records" primaryIdExpression="id"
                companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> keyField = { String scopeId, String fileSide, String field, String tenant = A ->
            """<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="${scopeId}" fileSide="${fileSide}" sequenceNum="1"
                fieldExpression="${field}" companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> automation = { String id, String conn, String tenant, String extra = "" ->
            """<darpan.reconciliation.ReconciliationAutomation automationId="${id}" automationName="${id}" companyUserGroupId="${tenant}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="KU_RS" ${extra}/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${id}" fileSide="FILE_1" companyUserGroupId="${tenant}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="KC_PROJ"
                safeMetadataJson='{"parameters":{"projConfigId":"${conn}"}}'/>"""
        }
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            ${scope('KU_RS1', 'KU_S1')}
            ${side('KU_S1', 'FILE_1', 'ku-union')}
            ${keyField('KU_S1', 'FILE_1', 'a')}
            <darpan.rule.Rule ruleId="KU_RULE1" ruleSetId="KU_RS1" sequenceNum="1" ruleText="r1" enabled="Y"
                expression='{"file1FieldPath":"r1","file2FieldPath":"x","operator":"EQUALS"}'/>
            ${scope('KU_RS2', 'KU_S2')}
            ${side('KU_S2', 'FILE_2', 'ku-union')}
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="KU_S2" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="\$.records[*].f" operator="EXCLUDE_IN" filterValues="z" companyUserGroupId="${A}"/>
            ${scope('KU_RS3', 'KU_S3')}
            ${side('KU_S3', 'FILE_1', 'ku-elsewhere')}
            ${keyField('KU_S3', 'FILE_1', 'zz')}
            ${scope('KU_RS4', 'KU_S4', A, 'isArchived="Y"')}
            ${side('KU_S4', 'FILE_1', 'ku-union')}
            ${keyField('KU_S4', 'FILE_1', 'archivedField')}
            ${automation('KU_PAUSED', 'ku-union', A, 'isActive="N"')}
            ${automation('KU_ARCHIVED', 'ku-union', A, 'isArchived="Y"')}

            ${scope('KU_BRS', 'KU_BS', B)}
            ${side('KU_BS', 'FILE_1', 'ku-union', B)}
            ${keyField('KU_BS', 'FILE_1', 'bField', B)}
            ${automation('KU_BAUTO', 'ku-union', B)}

            ${scope('KU_RSU', 'KU_SU')}
            ${side('KU_SU', 'FILE_1', 'ku-unread')}
            ${keyField('KU_SU', 'FILE_1', 'unreadable')}

            ${scope('KU_RSW', 'KU_SW')}
            ${side('KU_SW', 'FILE_1', 'ku-whole')}
            <darpan.rule.Rule ruleId="KU_RAW" ruleSetId="KU_RSW" sequenceNum="1" ruleText="raw" expression="{}" enabled="Y"/>
            ${scope('KU_RSA', 'KU_SA')}
            ${side('KU_SA', 'FILE_1', 'ku-auto')}
            ${automation('KU_AUTO', 'ku-auto', A)}
        </entity-facade-xml>""").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    /**
     * Keep: each matching source's keep and filter fields, then the dataset's own; read: their read fields, apart. A source on
     * another connection, an archived rule set's source, and a paused or archived automation add nothing.
     */
    @Test
    void theUnionKeepsEveryCurrentSourcesKeepAndFilterFieldsAndReadsItsReadFieldsApart() {
        String id = rolling("ku-union")
        Map<String, Set<String>> union = DatasetSupport.unionFields(ec, row(id).getMap() as Map)
        assertNotNull(union, "a paused or archived automation does not make the copy whole")
        assertEquals(["id", "createdAt", "a", "r1", "f"], union.keep as List,
                "base and key field and rule field of one side, the filter field of the other; never zz, archivedField or bField")
        assertEquals(["r1"], union.read as List)
        assertEquals(union.keep, DatasetSupport.keptFields(ec, row(id).getMap() as Map))
    }

    /**
     * refresh#Dataset sends the keep list as keep fields, the read fields as readFields, and the connector's dataset read
     * parameters; the copy's manifest records both lists, the fields its records carry and the files the read wrote beside
     * its records, and the Dataset row the keep list.
     */
    @Test
    void aRollingReadSendsAndRecordsWhatItKeepsAndReads() {
        String id = rolling("ku-union")
        List<Map> calls = []
        Map res = atReadClock { refresh(id, stub(calls)) }
        assertEquals([], res.errors)
        Map p = calls[0].params
        assertEquals(EXTRACT, calls[0].service)
        assertEquals(["id", "createdAt", "a", "r1", "f"], p.keepFields)
        assertEquals(["r1"], p.readFields, "read fields go as a live read sends them")
        assertEquals(7, p.madeUpCap, "the connector's dataset read parameters")
        assertEquals("createdAt", p.windowFieldName)
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertEquals(p.keepFields, manifest.keptFields)
        assertEquals(["r1"], manifest.readFields)
        assertEquals(["a", "createdAt", "f", "id", "r1"], manifest.heldFields)
        assertEquals(["read-000001.madeup-side.json"], manifest.sideFiles)
        File kept = KeptCopyStore.sideFiles(DatasetRefreshSupport.datasetDir(ec, A, id), manifest.version as String)["read-000001.madeup-side.json"]
        assertEquals('{"side":true}', kept.text, "the read's side file is kept with its version")
        assertEquals(JsonOutput.toJson(p.keepFields), row(id).keptFieldsJson)
    }

    /** A source's field the system's read cannot keep is left out of the shared read, which still builds the copy. */
    @Test
    void aFieldTheSystemCannotKeepNeverFailsTheSharedRead() {
        String id = rolling("ku-unread")
        assertEquals(["id", "createdAt"], DatasetSupport.keptFields(ec, row(id).getMap() as Map) as List, "unreadable is left out")
        List<Map> calls = []
        assertEquals([], atReadClock { refresh(id, stub(calls)) }.errors)
        assertFalse((calls[0].params.keepFields as List).contains("unreadable"))
        assertNotNull(KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))?.version, "the copy builds")
    }

    /** A source that cannot project, or a current automation source (which keeps whole records), makes the copy whole. */
    @Test
    void aSourceThatCannotProjectOrACurrentAutomationMakesTheCopyWhole() {
        String raw = rolling("ku-whole")
        assertNull(DatasetSupport.unionFields(ec, row(raw).getMap() as Map), "a rule with no field path on either side")
        assertNull(DatasetSupport.keptFields(ec, row(rolling("ku-auto")).getMap() as Map), "a current automation source")
        List<Map> calls = []
        assertEquals([], atReadClock { refresh(raw, stub(calls)) }.errors)
        assertFalse(calls[0].params.containsKey("keepFields"))
        assertFalse(calls[0].params.containsKey("readFields"))
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, raw))
        assertNull(manifest.keptFields)
        assertNull(manifest.readFields)
        assertNull(row(raw).keptFieldsJson)
        assertNull(DatasetSupport.unionFields(ec, row(rolling("ku-nobody")).getMap() as Map), "no source reads it: whole records")
    }

    /** A7: tenant B's run and automation sources on a connection of the same name never touch tenant A's union. */
    @Test
    void anOtherTenantsSourcesNeverWidenTheUnion() {
        String id = rolling("ku-union")
        Set<String> kept = DatasetSupport.keptFields(ec, row(id).getMap() as Map)
        assertNotNull(kept, "B's automation source does not make A's copy whole")
        assertFalse(kept.contains("bField"), kept.toString())
        actAs(EDITOR_USERNAME, B)
        try {
            assertEquals(B, TenantAccessSupport.currentActiveTenantUserGroupId(ec), "test precondition: acting as B")
            assertNull(DatasetSupport.unionFields(ec, row(id).getMap() as Map), "as B, A's dataset has no union")
        } finally {
            actAs(EDITOR_USERNAME, A)
        }
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    private String rolling(String conn) {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "KC_PROJ", sourceConfigId: conn,
                options: [:], idField: "id", windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        return stored.out.datasetId as String
    }

    private def row(String id) {
        return ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
    }

    /** A read's extractor: two records trimmed to the keep fields it is sent, and a side file beside them. */
    private Closure stub(List<Map> calls) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            List<Map> rows = [[id: "k1", createdAt: "2026-11-10T09:00:00Z", a: 1, r1: 2, f: 3, other: 4],
                              [id: "k2", createdAt: "2026-11-11T09:00:00Z", a: 5, r1: 6, f: 7, other: 8]]
            List keep = p.keepFields as List
            rows = rows.collect { Map r -> keep ? r.findAll { k, v -> k in keep } : r }
            File dir = DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true)
            File out = new File(dir, p.fileName as String)
            out.setText(JsonOutput.toJson([records: rows]), "UTF-8")
            new File(dir, (p.fileName as String).replace(".json", ".madeup-side.json")).text = '{"side":true}'
            return [fileLocation: out.absolutePath]
        }
    }

    private Map atReadClock(Closure<Map> work) {
        ec.user.setEffectiveTime(Timestamp.from(READ_AT))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("dataset.DatasetServices.refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: false])
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
