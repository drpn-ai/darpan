package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-088 — the audit-run vocabulary, at parse level (no Moqui boot).
 *
 * <p>The enum codes are the values the audit-run JSON emits, so they are pinned here exactly as the v1
 * contract lists them (docs/superpowers/specs/2026-10-21-audit-run-json-contract.md, "Enums", in the
 * wrapper repo). Within v1 a code may be added, never renamed or removed: a rename fails this test on
 * purpose. A tenant's statement catalog is tenant config (DAR-BE-160): it lives in that tenant's config pack, and
 * {@link darpan.security.TenantOwnedEntityFilterRuntimeTests} proves its rows are hidden from another tenant.</p>
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

    /**
     * The newest shipped pack holding any audit enum type: the release packs in version order, then the current one.
     * data/upgrade-data.xml is rebuilt each release from the previous tag, so after 2.1.0 the vocabulary lives in
     * releases/2.1.0, not in a later delta pack, until a release changes it and restates it.
     */
    private static Path vocabularyPack() {
        Path data = dataDir()
        List<Path> packs = Files.list(data.resolve("releases"))
                .filter { Path d -> d.fileName.toString() ==~ /\d+(\.\d+)*/ && Files.exists(d.resolve("upgrade-data.xml")) }
                .sorted({ Path a, Path b -> versionOf(a) <=> versionOf(b) } as Comparator<Path>)
                .collect { it.resolve("upgrade-data.xml") }
        packs << data.resolve("upgrade-data.xml")
        Path found = packs.findAll { Path pack ->
            packRows(pack, "moqui.basic.EnumerationType").any { CODES.containsKey(it.attribute("enumTypeId")) }
        }.with { it ? it.last() : null }
        assertTrue(found != null, "no shipped upgrade pack carries the audit vocabulary")
        return found
    }

    private static String versionOf(Path releaseDir) {
        return releaseDir.fileName.toString().tokenize(".").collect { (it as int).toString().padLeft(6, "0") }.join(".")
    }

    private static List<Node> packRows(Path pack, String elementName) {
        return new XmlParser(false, false).parse(pack.toFile()).children()
                .findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    @Test
    void theUpgradePackRestatesTheVocabularyVerbatim() {
        // An existing database only gets darpan-seed rows through the upgrade pack.
        Path vocabulary = vocabularyPack()
        ["moqui.basic.EnumerationType", "moqui.basic.Enumeration"].each { String element ->
            List<Map> seed = rows("AuditSeedData.xml", element)*.attributes()
            List<Map> pack = packRows(vocabulary, element)*.attributes()
            seed.each { Map row -> assertTrue(pack.contains(row), "${vocabulary} is missing ${element} ${row}") }
        }
        // Document order is load order: every type ahead of its first member.
        List<Node> pack = new XmlParser(false, false).parse(vocabulary.toFile())
                .children().findAll { it instanceof Node } as List<Node>
        CODES.keySet().each { String type ->
            int typeAt = pack.findIndexOf { it.name() == "moqui.basic.EnumerationType" && it.attribute("enumTypeId") == type }
            int firstMember = pack.findIndexOf { it.name() == "moqui.basic.Enumeration" && it.attribute("enumTypeId") == type }
            assertTrue(typeAt >= 0 && typeAt < firstMember, "${type} must precede its members in ${vocabulary}")
        }
    }

    @Test
    void noTenantsCatalogShipsInTheUpgradePack() {
        // An upgrade pack loads on every instance; a tenant's audits live in its config pack (DAR-BE-160), never here.
        assertEquals([], rows("upgrade-data.xml", "darpan.audit.AuditStatement"), "no catalog rows in upgrade-data.xml")
        assertEquals([], rows("upgrade-data.xml", "darpan.audit.Audit"), "no audit rows in upgrade-data.xml")
    }
}
