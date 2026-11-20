package darpan.reconciliation.source

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063 §1.5: a connector that declares filterParameterName filters through SourceFilterSupport, and the conclude pass
 * reads the records it dropped from the excluded-records side file: a filter that hides what it dropped makes the other side
 * of every finding UNKNOWN. Whether an endpoint's extract writes that file is declared on its connector row,
 * excludedRecordsSidecar Y or N, by the system's own component (DAR-BE-318 ruling P3b1-9); a kept-copy slice follows it.
 *
 * A tripwire over every component in this checkout: core declares it on no row (system knowledge stays in system
 * components, each of which ties its declarations to its extracts), every declaration is Y or N on a filtering row, and every
 * filtering row whose extract service is installed here is declared. A NEW filtering connector fails here until its author
 * decides. A row whose extract service's component is absent is not held to it: core runs without any system component.
 */
class FilteringConnectorSidecarContractTests {

    static final String ROW = 'darpan.reconciliation.SourceSystemConnector'

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        return p
    }

    /** Every other component's data files, by component. */
    private static Map<String, List<File>> otherComponentsData() {
        File components = componentRoot().parent.toFile()
        return (components.listFiles() ?: []).findAll { it.isDirectory() && it.name != "darpan" && new File(it, "data").isDirectory() }
                .collectEntries { File c -> [(c.name): new File(c, "data").listFiles().findAll { it.name.endsWith(".xml") } as List<File>] }
    }

    private static List rows(File f) {
        return new XmlSlurper().parse(f).'**'.findAll { it.name() == ROW } as List
    }

    /** Whether a service's definition file is present in some component of this checkout. */
    private static boolean installed(String serviceName) {
        if (!serviceName?.contains("#")) return false
        String path = serviceName.substring(0, serviceName.indexOf("#"))
        path = path.substring(0, path.lastIndexOf("."))
        String rel = "service/" + path.replace(".", "/") + ".xml"
        return (componentRoot().parent.toFile().listFiles() ?: []).any { new File(it, rel).isFile() }
    }

    @Test
    void everyFilteringConnectorDeclaresItsSideFile() {
        List core = rows(componentRoot().resolve("data/SourceSystemConnectorSeedData.xml").toFile())
        assertEquals([], core.findAll { it.@excludedRecordsSidecar.toString() }.collect { it.@systemEnumId.toString() },
                "core declares no system's side file: its component does")

        Map<String, Map<String, String>> merged = [:]
        core.each { merged.put(it.@systemEnumId.toString(), [filter: it.@filterParameterName.toString(), service: it.@extractServiceName.toString()]) }
        Map<String, String> declaredBy = [:], declared = [:]
        otherComponentsData().each { String component, List<File> files ->
            files.each { File f ->
                rows(f).each { row ->
                    String id = row.@systemEnumId.toString()
                    Map<String, String> m = merged.computeIfAbsent(id) { [:] }
                    if (row.@filterParameterName.toString()) m.filter = row.@filterParameterName.toString()
                    if (row.@extractServiceName.toString()) m.service = row.@extractServiceName.toString()
                    String value = row.@excludedRecordsSidecar.toString()
                    if (!value) return
                    assertTrue(value in ["Y", "N"], "${component} declares ${id} ${value}: Y or N")
                    assertTrue(!declaredBy.containsKey(id), "${id} is declared by ${declaredBy[id]} and ${component}")
                    declaredBy.put(id, component)
                    declared.put(id, value)
                }
            }
        }
        declared.keySet().each { String id -> assertTrue(merged[id]?.filter as boolean, "${id} declares a side file but does not filter") }
        List<String> undeclared = merged.findAll { String id, Map m -> m.filter && !declared.containsKey(id) && installed(m.service) }
                .keySet().sort() as List<String>
        assertEquals([], undeclared, "filtering connectors whose component does not declare excludedRecordsSidecar")
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
