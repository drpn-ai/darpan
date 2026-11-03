package darpan.reconciliation.source

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-187: Moqui loads a component's data files in file-name order, and one FK failure skips the rest of
 * that file. A file of SourceSystemConnectorField rows that sorts BEFORE the file holding their
 * SourceSystemConnector parents loads nothing on an empty database (every fresh install), while databases
 * that already hold the parents never show it. Found on the first fresh boot of Darpan's own AWS prod.
 */
class ConnectorSeedLoadOrderContractTests {

    static final String CONNECTOR = 'darpan.reconciliation.SourceSystemConnector'
    static final String FIELD = 'darpan.reconciliation.SourceSystemConnectorField'

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
    void connectorFieldRowsLoadAfterTheirParentConnectors() {
        Path data = componentRoot().resolve("data")
        List<Path> files = Files.list(data).withCloseable { s ->
            s.filter { it.fileName.toString().endsWith(".xml") }.sorted().toList()
        }
        Map<String, String> parentFileByEnum = [:]
        Map<String, Set<String>> fieldEnumsByFile = [:]
        files.each { Path f ->
            def doc = new XmlSlurper().parse(f.toFile())
            doc.'**'.findAll { it.name() == CONNECTOR }.each { parentFileByEnum.putIfAbsent(it.@systemEnumId.toString(), f.fileName.toString()) }
            Set<String> enums = doc.'**'.findAll { it.name() == FIELD }.collect { it.@systemEnumId.toString() } as Set<String>
            if (enums) fieldEnumsByFile[f.fileName.toString()] = enums
        }
        assertTrue(!fieldEnumsByFile.isEmpty(), "no ${FIELD} rows found under ${data}: the scan is broken")

        List<String> wrong = []
        fieldEnumsByFile.each { String fieldFile, Set<String> enums ->
            enums.each { String e ->
                String parentFile = parentFileByEnum[e]
                if (parentFile != null && parentFile != fieldFile && fieldFile < parentFile) {
                    wrong << "${fieldFile} (${e}) loads before its parent file ${parentFile}"
                }
            }
        }
        assertTrue(wrong.isEmpty(), "rename so the field file sorts after its parent:\n" + wrong.unique().join("\n"))
    }
}
