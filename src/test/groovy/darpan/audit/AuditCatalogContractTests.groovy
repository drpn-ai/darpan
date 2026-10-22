package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-088 — the audit-run vocabulary and gorjana's statement catalog, at parse level (no Moqui boot).
 *
 * <p>The enum codes are the values the audit-run JSON emits, so they are pinned here exactly as the v1
 * contract lists them (docs/superpowers/specs/2026-10-21-audit-run-json-contract.md, "Enums", in the
 * wrapper repo). Within v1 a code may be added, never renamed or removed: a rename fails this test on
 * purpose. That the catalog's rows are hidden from another tenant is proven at runtime by
 * {@link darpan.security.TenantOwnedEntityFilterRuntimeTests}.</p>
 */
class AuditCatalogContractTests {

    /** enumTypeId -> JSON codes in sequence order. */
    static final Map<String, List<String>> CODES = [
            AuditCategory       : ["orders", "refundsAndCredits", "returns", "exchangesAndWarranty"],
            AuditStatementWeight: ["firm", "derived"],
            AuditFindingState   : ["open", "pending", "fixed"],
            AuditCheckStatus    : ["ran", "partial", "notRun"],
            AuditSourceRead     : ["complete", "partial", "failed"],
            AuditFindingEvent   : ["found", "stillOpen", "fixed", "reopened"],
            AuditActorType      : ["check", "user"],
            AuditPeriod         : ["day", "month", "quarter"],
            // DAR-BE-102: not in the JSON (the digest is rendered from it), but it rides the same seed and upgrade pack.
            AuditDigestKind     : ["daily", "weekly"],
    ].asImmutable()

    /** #483 open point 6: every statement not listed there as derived is firm. */
    static final List<String> GORJANA_FIRM = ["R1", "R2", "R5", "R7", "R9"].asImmutable()

    /** #483 "What Gorjana Accounting needs": statement numbers under each heading. */
    static final Map<String, List<Integer>> GORJANA_CATEGORIES = [
            AUDC_ORDERS            : [1, 2, 3, 18, 19],
            AUDC_REFUNDS_CREDITS   : [4, 5, 6, 7],
            AUDC_RETURNS           : [8, 9, 10, 11],
            AUDC_EXCHANGES_WARRANTY: [12, 13, 14, 15, 16, 17],
    ].asImmutable()

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static List<Node> rows(String file, String elementName) {
        Node root = new XmlParser(false, false).parse(dataDir().resolve(file).toFile())
        return root.children().findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    @Test
    void everyEnumCarriesExactlyTheContractCodesInOrder() {
        List<Node> enums = rows("AuditSeedData.xml", "moqui.basic.Enumeration")
        Set<String> types = rows("AuditSeedData.xml", "moqui.basic.EnumerationType")
                .collect { it.attribute("enumTypeId") as String } as Set<String>
        assertEquals(CODES.keySet(), types, "enum types")
        CODES.each { String type, List<String> codes ->
            List<String> seen = enums.findAll { it.attribute("enumTypeId") == type }
                    .sort { (it.attribute("sequenceNum") as String).toInteger() }
                    .collect { it.attribute("enumCode") as String }
            assertEquals(codes, seen, "${type} codes")
        }
    }

    @Test
    void theUpgradePackRestatesTheVocabularyVerbatim() {
        // An existing database only gets darpan-seed rows through the upgrade pack.
        ["moqui.basic.EnumerationType", "moqui.basic.Enumeration"].each { String element ->
            List<Map> seed = rows("AuditSeedData.xml", element)*.attributes()
            List<Map> pack = rows("upgrade-data.xml", element)*.attributes()
            seed.each { Map row -> assertTrue(pack.contains(row), "upgrade-data.xml is missing ${element} ${row}") }
        }
        // Document order is load order: every type ahead of its first member.
        List<Node> pack = new XmlParser(false, false).parse(dataDir().resolve("upgrade-data.xml").toFile())
                .children().findAll { it instanceof Node } as List<Node>
        CODES.keySet().each { String type ->
            int typeAt = pack.findIndexOf { it.name() == "moqui.basic.EnumerationType" && it.attribute("enumTypeId") == type }
            int firstMember = pack.findIndexOf { it.name() == "moqui.basic.Enumeration" && it.attribute("enumTypeId") == type }
            assertTrue(typeAt >= 0 && typeAt < firstMember, "${type} must precede its members in upgrade-data.xml")
        }
    }

    @Test
    void gorjanasCatalogIsNineteenStatementsInFourCategories() {
        List<Map> audits = rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.Audit")*.attributes()
        assertEquals([[auditId: "GORJANA_MONTH_CLOSE", companyUserGroupId: "GORJANA", auditCode: "month-close",
                       auditName: "Month close", periodEnumId: "AUDP_MONTH"]], audits, "gorjana's month close is one audit")
        List<Node> statements = rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement")
        // R18 (DAR-BE-111) and R19 (DAR-BE-113) are Aditi's, after the seventeen from #483.
        assertEquals((1..19).collect { "R${it}".toString() }, statements*.attribute("statementCode"), "R1-R19 in file order")
        statements.each { Node row ->
            String code = row.attribute("statementCode")
            int n = code.substring(1).toInteger()
            assertEquals("GORJANA", row.attribute("companyUserGroupId"), "${code} tenant")
            assertEquals("GORJANA_MONTH_CLOSE", row.attribute("auditId"), "${code} audit")
            assertEquals("GORJANA_${code}".toString(), row.attribute("statementId"), "${code} id")
            assertEquals(n, (row.attribute("sequenceNum") as String).toInteger(), "${code} sequence")
            String category = GORJANA_CATEGORIES.find { it.value.contains(n) }?.key
            assertEquals(category, row.attribute("categoryEnumId"), "${code} category")
            assertEquals(GORJANA_FIRM.contains(code) ? "AUDSW_FIRM" : "AUDSW_DERIVED", row.attribute("weightEnumId"), "${code} weight")
            assertTrue((row.attribute("statementText") as String)?.trim()?.length() > 20, "${code} needs its text")
        }
    }

    @Test
    void theCatalogIsTenantConfigNotSeed() {
        // An upgrade pack loads on every instance, sm-darpan included; gorjana's statements must not.
        String text = dataDir().resolve("GorjanaMonthCloseCatalogData.xml").toFile().text
        assertTrue(text.contains('<entity-facade-xml type="darpan-tenant-config">'), "catalog file type")
        assertEquals([], rows("upgrade-data.xml", "darpan.audit.AuditStatement"), "no catalog rows in upgrade-data.xml")
        assertEquals([], rows("upgrade-data.xml", "darpan.audit.Audit"), "no audit rows in upgrade-data.xml")
    }
}
