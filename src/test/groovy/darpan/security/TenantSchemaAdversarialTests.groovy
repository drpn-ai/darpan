package darpan.security

import darpan.facade.common.DataManagerSupport
import jsonschema.common.JsonSchemaUtil

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-277 / DAR-BE-281 — JSON schemas are unique per tenant by name, but the file and the run-time lookup are by
 * name alone. Each test asserts the SECURE outcome: red = a confirmed hole, green = the boundary held.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSchemaAdversarialTests {

    static final String A = TenantAttackHarness.TENANT_A
    static final String B = TenantAttackHarness.TENANT_B

    TenantAttackHarness h

    @BeforeAll
    void setup() { h = new TenantAttackHarness("tenant-schema-adversarial") }

    @AfterAll
    void cleanup() { h.close() }

    @BeforeEach
    void attacker() { h.asAttacker() }

    /** B's schema file wherever B saves it today. */
    @Test
    void saveJsonSchemaCannotOverwriteForeignSchemaFile() {
        String name = "adv_shared_schema.json"
        String bText = '{"type":"object","required":["tenantBField"]}'
        h.actAs(TenantAttackHarness.VICTIM, B)
        String location = JsonSchemaUtil.persistSchemaText(h.ec, name, bText)
        h.asAttacker()
        assertEquals(bText, h.ec.resource.getLocationReference(location).getText(), "test precondition: B's schema file")
        h.insert("darpan.reconciliation.JsonSchema", [jsonSchemaId: "ADV_SCHEMA_B", schemaName: name, companyUserGroupId: B,
                schemaText: bText])
        Map out = h.call("facade.JsonSchemaFacadeServices.save#JsonSchemaText", [schemaName: name,
                schemaText: '{"type":"object","required":["attackerField"]}'])
        assertEquals(bText, h.ec.resource.getLocationReference(location).getText(), "tenant A overwrote tenant B's schema file: ${out}")
    }

    /** Where every tenant's save wrote before DAR-BE-281: schemas/json/<name>.json, no tenant folder. */
    private String sharedLegacyLocation(String name) {
        return DataManagerSupport.childLocation(DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(h.ec),
                DataManagerSupport.SCHEMA_PATH), DataManagerSupport.schemaFileName(name))
    }

    /** A legacy file in the shared folder (written before files moved per tenant) is never overwritten. */
    @Test
    void saveJsonSchemaCannotOverwriteLegacySharedSchemaFile() {
        String name = "adv_legacy_schema.json"
        String bText = '{"type":"object","required":["legacyField"]}'
        String shared = sharedLegacyLocation(name)
        DataManagerSupport.writeText(h.ec, shared, bText)
        h.insert("darpan.reconciliation.JsonSchema", [jsonSchemaId: "ADV_SCHEMA_LEGACY_B", schemaName: name, companyUserGroupId: B,
                schemaText: bText])
        Map out = h.call("facade.JsonSchemaFacadeServices.save#JsonSchemaText", [schemaName: name,
                schemaText: '{"type":"object","required":["attackerField"]}'])
        assertEquals(bText, h.ec.resource.getLocationReference(shared).getText(), "tenant A overwrote the shared legacy schema file: ${out}")
    }

    /** A tenant with no saved schema of that name must not read another tenant's legacy shared file. */
    @Test
    void schemaLookupSkipsSharedFileWhenAnotherTenantOwnsTheName() {
        String name = "adv_b_only_schema.json"
        DataManagerSupport.writeText(h.ec, sharedLegacyLocation(name), '{"title":"B legacy"}')
        h.insert("darpan.reconciliation.JsonSchema", [jsonSchemaId: "ADV_SCHEMA_B_ONLY", schemaName: name, companyUserGroupId: B,
                schemaText: '{"title":"B"}'])
        assertEquals(null, JsonSchemaUtil.loadSchemaText(h.ec, null, name), "tenant A read a schema only tenant B owns")
        assertEquals(null, JsonSchemaUtil.loadSchemaText(h.ec, "ADV_SCHEMA_B_ONLY", null), "tenant A read tenant B's schema by id")
        // Under a facade grant the entity filter hides B's row, so nothing may conclude "no one owns this name".
        assertEquals(null, h.underFacadeGrant { JsonSchemaUtil.loadSchemaText(h.ec, null, name) },
                "with the entity filter on, tenant A read tenant B's legacy shared file")
        // Same file reached by the name without its extension (the file name is normalized, the row name is not).
        assertEquals(null, JsonSchemaUtil.loadSchemaText(h.ec, null, "adv_b_only_schema"),
                "tenant A read tenant B's legacy shared file through the extension-less name")
    }

    /** A run resolves its schema by name with authz disabled (runSavedRunDiff -> runInternalService). */
    @Test
    void runTimeSchemaLookupByNameReturnsTheRunningTenantsSchema() {
        String name = "adv_dup_schema.json"
        h.insert("darpan.reconciliation.JsonSchema", [jsonSchemaId: "ADV_SCHEMA_DUP_A", schemaName: name, companyUserGroupId: A,
                schemaText: '{"title":"A"}'])
        h.insert("darpan.reconciliation.JsonSchema", [jsonSchemaId: "ADV_SCHEMA_DUP_B", schemaName: name, companyUserGroupId: B,
                schemaText: '{"title":"B"}'])
        h.actAs(TenantAttackHarness.VICTIM, B)
        String text = JsonSchemaUtil.loadSchemaText(h.ec, null, name)   // harness frame: authz disabled
        h.asAttacker()
        assertEquals('{"title":"B"}', text, "tenant B's run loaded tenant A's schema by name")
    }

    /** A legacy shared file left behind after its schema was deleted is no one's to read. */
    @Test
    void schemaLookupNeverReadsAnOrphanedSharedFile() {
        String name = "adv_orphan_schema.json"
        DataManagerSupport.writeText(h.ec, sharedLegacyLocation(name), '{"title":"orphan"}')
        assertEquals(null, JsonSchemaUtil.loadSchemaText(h.ec, null, name), "read an orphaned shared schema file")
    }

    /** The tenant's own file (no saved row needed) is still found. */
    @Test
    void schemaLookupReadsTheTenantsOwnFile() {
        String name = "adv_own_file_schema.json"
        JsonSchemaUtil.persistSchemaText(h.ec, name, '{"title":"A own"}')
        assertEquals('{"title":"A own"}', JsonSchemaUtil.loadSchemaText(h.ec, null, name))
    }
}
