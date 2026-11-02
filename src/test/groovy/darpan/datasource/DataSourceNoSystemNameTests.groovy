package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * DAR-BE-176. The data-defined source code in darpan core names no system (Aditi 2026-11-02: "make sure nothing in the
 * entity is system (ex Shopify) specific and is fully adaptable"; "anything that must be specific... can be added to the
 * system specific repo only"). A system belongs in a definition's data and on a connector row, never in this code.
 */
class DataSourceNoSystemNameTests {

    private static final java.util.regex.Pattern SYSTEM_NAMES =
            ~/(?i)hotwax|shopify|netsuite|suiteql|\boms\b|moqui-gql|\bmaarg\b|narvar|riskified|loop returns|aftership/

    private static List<File> sources() {
        File root = new File(System.getProperty("user.dir"))
        // Gradle runs tests from the component directory.
        List<File> files = []
        new File(root, "src/main/groovy/darpan/datasource").eachFileRecurse { File f -> if (f.name.endsWith(".groovy")) files << f }
        files << new File(root, "entity/DataSourceEntities.xml")
        return files
    }

    @Test
    void noDataSourceSourceFileNamesASystem() {
        List<File> files = sources()
        assertFalse(files.isEmpty())
        List<String> hits = files.collectMany { File f ->
            f.readLines().withIndex().findAll { String line, int i -> SYSTEM_NAMES.matcher(line).find() }
                    .collect { String line, int i -> "${f.name}:${i + 1}: ${line.trim()}".toString() }
        }
        assertEquals([], hits)
    }
}
