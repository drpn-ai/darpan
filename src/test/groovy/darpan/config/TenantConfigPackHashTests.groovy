package darpan.config

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-160. A version's content hash says whether a tenant's config changed, so it must ignore what is not config
 * (the source block: instance label, Darpan version, tenant) and the order map keys happen to arrive in.
 */
class TenantConfigPackHashTests {

    private static Map pack(Map overrides = [:]) {
        return [bundleVersion: 1,
                source       : [instanceLabel: null, darpanVersion: "2.1.4", tenantUserGroupId: "KREWE"],
                audits       : [[ref: "AUDIT:A1", label: "month-close", fields: [auditCode: "month-close", auditName: "Month close"]]],
                statements   : [[ref: "STATEMENT:S1", fields: [statementCode: "R2", statementText: "Every invoice carries the Etail id."]]]] + overrides
    }

    @Test
    void theSameConfigHashesTheSameWhateverTheKeyOrder() {
        Map reordered = [statements   : pack().statements, audits: [[fields: [auditName: "Month close", auditCode: "month-close"],
                         label: "month-close", ref: "AUDIT:A1"]], bundleVersion: 1, source: pack().source]
        assertEquals(TenantConfigVersionSupport.contentHash(pack()), TenantConfigVersionSupport.contentHash(reordered))
    }

    @Test
    void theSourceBlockIsNotConfig() {
        Map afterADeploy = pack(source: [instanceLabel: "prod", darpanVersion: "2.1.5", tenantUserGroupId: "KREWE"])
        assertEquals(TenantConfigVersionSupport.contentHash(pack()), TenantConfigVersionSupport.contentHash(afterADeploy))
    }

    @Test
    void aChangedValueChangesTheHash() {
        Map edited = pack(statements: [[ref: "STATEMENT:S1", fields: [statementCode: "R2", statementText: "Every invoice has an Etail id."]]])
        assertNotEquals(TenantConfigVersionSupport.contentHash(pack()), TenantConfigVersionSupport.contentHash(edited))
    }

    @Test
    void listOrderIsConfig() {
        Map one = pack(statements: [[ref: "STATEMENT:S1"], [ref: "STATEMENT:S2"]])
        Map other = pack(statements: [[ref: "STATEMENT:S2"], [ref: "STATEMENT:S1"]])
        assertNotEquals(TenantConfigVersionSupport.contentHash(one), TenantConfigVersionSupport.contentHash(other),
                "the exporter sorts every list, so a different order is a different config")
    }

    @Test
    void theHashIsHexSha256() {
        assertTrue(TenantConfigVersionSupport.contentHash(pack()) ==~ /[0-9a-f]{64}/)
    }
}
