package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The data-defined source code in darpan core names no system (Aditi 2026-11-02: "make sure nothing in the
 * entity is system (ex Shopify) specific and is fully adaptable"; "anything that must be specific... can be added to the
 * system specific repo only"). A system belongs in a definition's data and on a connector row, never in this code.
 */
class DataSourceNoSystemNameTests {

    // "oms" is matched as its own token: at a non-letter edge (oms, OMS_GQL, omsUrl, hc-oms) or as a camelCase /
    // UPPER_CASE part (getOmsUrl, HC_OMS), never inside a word (atoms, customs). The other names are distinctive enough
    // to match anywhere, case-insensitively; "loop returns" also matches loopReturns / LOOP_RETURNS.
    private static final java.util.regex.Pattern SYSTEM_NAMES =
            ~/(?i:hotwax|shopify|netsuite|suiteql|moqui-gql|maarg|narvar|riskified|loop[\s_-]*returns|aftership)|(?<![A-Za-z])[oO]ms(?![a-z])|(?<=[a-z])Oms(?![a-z])|(?<![A-Z])OMS(?![A-Z])/

    /** file name -> exact line fragment allowed to match, each with the reason it is not a system dependency. */
    private static final Map<String, List<String>> ALLOWED = [
            // File-header comment: cites a shipped definition id as the example of the <FAMILY>_V<n> id format.
            // The id lives in data; no code path branches on it.
            "DataSourceDefinitionFacadeServices.xml": ["as the shipped OMS_ORDERS_GQL_V1"],
    ]

    private static File componentRoot() {
        // Gradle runs tests from the component directory.
        return new File(System.getProperty("user.dir"))
    }

    private static List<File> groovySources() {
        List<File> files = []
        new File(componentRoot(), "src/main/groovy/darpan/datasource").eachFileRecurse { File f -> if (f.name.endsWith(".groovy")) files << f }
        return files
    }

    private static List<File> sources() {
        File root = componentRoot()
        List<File> files = groovySources()
        files << new File(root, "entity/DataSourceEntities.xml")
        files << new File(root, "service/facade/DataSourceDefinitionFacadeServices.xml")
        return files
    }

    @Test
    void systemNamePatternCatchesTokensNotWordParts() {
        ["oms", "OMS_GQL", "omsUrl", "getOmsUrl", "HC_OMS", "hc-oms", "maargHost", "LoopReturns", "loop_returns", "Shopify"].each {
            assertTrue(SYSTEM_NAMES.matcher(it).find(), "should flag: ${it}")
        }
        ["atoms", "customs", "ATOMS", "Bottoms", "loop over returns"].each {
            assertTrue(!SYSTEM_NAMES.matcher(it).find(), "should not flag: ${it}")
        }
    }

    @Test
    void noDataSourceSourceFileNamesASystem() {
        assertTrue(groovySources().size() > 0, "no Groovy sources under src/main/groovy/darpan/datasource; wrong working directory?")
        List<File> files = sources()
        files.each { File f -> assertTrue(f.isFile(), "scanned file missing: ${f.path}") }
        List<String> hits = files.collectMany { File f ->
            List<String> allowed = ALLOWED.get(f.name) ?: []
            f.readLines().withIndex().findAll { String line, int i ->
                SYSTEM_NAMES.matcher(line).find() && !allowed.any { String fragment -> line.contains(fragment) }
            }.collect { String line, int i -> "${f.name}:${i + 1}: ${line.trim()}".toString() }
        }
        assertEquals([], hits)
    }
}
