package darpan.schedule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-321: {@code <limit-range>} and {@code <limit-view>} in an entity-find compile to
 * {@code iterator().getPartialList()}, which jumps the JDBC cursor with {@code ResultSet.absolute()}.
 * H2 (the test database) allows that; production's driver opens forward-only result sets and refuses
 * it ("Error going to absolute row number 1"), so the find fails only in production. Paging goes in the
 * SQL instead: the entity-find {@code limit}/{@code offset} attributes.
 */
class EntityFindPagingGuardTests {

    // Today: 142 XML files across every component checkout, 108 in darpan alone (system repos are optional
    // checkouts, so the floor sits below darpan's own count). A wrong root scans none and fails here.
    private static final int MIN_FILES_SCANNED = 50

    @Test
    void noEntityFindPagesWithACursorJump() {
        Path componentsRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        assertTrue(Files.isDirectory(componentsRoot.resolve("darpan")), "darpan component not found under ${componentsRoot}")
        List<String> offenders = []
        int scanned = 0
        List<Path> components = Files.list(componentsRoot).withCloseable { stream -> stream.filter { Path p -> Files.isDirectory(p) }.toList() }
        components.each { Path component ->
            ["service", "screen", "src/main"].each { String dir ->
                Path root = component.resolve(dir)
                if (!Files.isDirectory(root)) return
                List<Path> files = Files.walk(root).withCloseable { stream -> stream.filter { Path p -> p.toString().endsWith(".xml") }.toList() }
                files.each { Path file ->
                    scanned++
                    Files.readAllLines(file).eachWithIndex { String line, int i ->
                        if (line.contains("<limit-range") || line.contains("<limit-view")) {
                            offenders << "${componentsRoot.relativize(file)}:${i + 1}".toString()
                        }
                    }
                }
            }
        }
        assertTrue(scanned >= MIN_FILES_SCANNED, "scanned only ${scanned} XML files under ${componentsRoot}; expected at least ${MIN_FILES_SCANNED}")
        assertEquals([], offenders, "use the entity-find limit/offset attributes (SQL LIMIT), not limit-range/limit-view")
    }
}
