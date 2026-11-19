package darpan.schedule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-321: {@code <limit-range>} and {@code <limit-view>} in an entity-find compile to
 * {@code iterator().getPartialList()}, which jumps the JDBC cursor with {@code ResultSet.absolute()}.
 * H2 (the test database) allows that; production's driver opens forward-only result sets and refuses
 * it ("Error going to absolute row number 1"), so the find fails only in production. Paging goes in the
 * SQL instead: the entity-find {@code limit}/{@code offset} attributes.
 */
class EntityFindPagingGuardTests {

    @Test
    void noEntityFindPagesWithACursorJump() {
        Path componentsRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        List<String> offenders = []
        Files.list(componentsRoot).filter { Files.isDirectory(it) }.forEach { Path component ->
            ["service", "screen", "src/main"].each { String dir ->
                Path root = component.resolve(dir)
                if (!Files.isDirectory(root)) return
                Files.walk(root).filter { it.toString().endsWith(".xml") }.forEach { Path file ->
                    Files.readAllLines(file).eachWithIndex { String line, int i ->
                        if (line.contains("<limit-range") || line.contains("<limit-view")) {
                            offenders << "${componentsRoot.relativize(file)}:${i + 1}".toString()
                        }
                    }
                }
            }
        }
        assertEquals([], offenders, "use the entity-find limit/offset attributes (SQL LIMIT), not limit-range/limit-view")
    }
}
