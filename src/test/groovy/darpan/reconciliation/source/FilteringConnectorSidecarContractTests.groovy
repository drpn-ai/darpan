package darpan.reconciliation.source

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063 §1.5: a connector that declares filterParameterName filters through SourceFilterSupport
 * AND writes the excluded sidecar — the conclude pass reads the sidecar, and a filter that hides what it
 * dropped makes the other side of every finding UNKNOWN.
 *
 * A tripwire, not a proof: every filtering connector must be listed in exactly one set. Moving one to
 * WRITES_SIDECAR requires a test in its own component that proves the sidecar is written. A NEW
 * filtering connector fails here until its author decides.
 */
class FilteringConnectorSidecarContractTests {

    // The two sets live in main code (ExcludedRecordsSidecar), the one declaration a kept-copy slice also reads
    // (DAR-BE-318 ruling P3b1-7); see there for why each connector sits where it does.
    static final Set<String> WRITES_SIDECAR = ExcludedRecordsSidecar.WRITTEN_BY
    /** Filter without a verified sidecar today. Conclusions on these sides read UNKNOWN when they filter. */
    static final Set<String> NOT_YET = ExcludedRecordsSidecar.NOT_WRITTEN_BY

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        return p
    }

    @Test
    void everyFilteringConnectorIsClassified() {
        def doc = new XmlSlurper().parse(componentRoot().resolve("data/SourceSystemConnectorSeedData.xml").toFile())
        Set<String> filtering = doc.'**'.findAll {
            it.name() == 'darpan.reconciliation.SourceSystemConnector' && it.@filterParameterName.toString()
        }.collect { it.@systemEnumId.toString() } as Set
        assertEquals([] as Set, WRITES_SIDECAR.intersect(NOT_YET), "a connector is in both sets")
        assertEquals(filtering, WRITES_SIDECAR + NOT_YET,
                "unclassified or stale: ${(filtering - WRITES_SIDECAR - NOT_YET) + ((WRITES_SIDECAR + NOT_YET) - filtering)}")
    }

    @Test
    void shopifyOrdersCarryAnEvidenceProjection() {
        def doc = new XmlSlurper().parse(componentRoot().resolve("data/SourceSystemConnectorSeedData.xml").toFile())
        def row = doc.'**'.find { it.name() == 'darpan.reconciliation.SourceSystemConnector' && it.@systemEnumId == 'SHOPIFY' }
        String json = row.@evidenceFieldsJson.toString()
        assertTrue(json.contains('"state":"displayFulfillmentStatus"'), json)
        assertTrue(json.contains('"displayFinancialStatus"'), json)
    }
}
