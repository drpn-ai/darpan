package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-099 — the public SDL (gql.QueryServices.get#Sdl, readable without a key) describes every type, field and
 * argument Darpan declares in moqui-gql's graphql/*.gql.xml. DAR-DOCS-003 generates the GraphQL reference from it, so
 * a missing description is a gap in the published docs: this test fails the build on one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphQlSchemaDescriptionTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "graphql-descriptions")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private def artifact() {
        def factory = ec.factory.getToolFactory("GraphQL")
        assert factory != null: "the moqui-gql component did not load"
        return factory.getInstance().artifact
    }

    @Test
    void theSdlPrintsTypeFieldAndArgumentDescriptions() {
        String sdl = ec.service.sync().name("gql.QueryServices.get#Sdl").call().sdl as String
        assertTrue(sdl.contains('"One audit\'s report for one morning'), "type description")
        assertTrue(sdl.contains('"The four categories, each holding its statements and their findings."'), "field description")
        assertTrue(sdl.contains('"The audit\'s code, e.g. month-close."'), "argument description")
    }

    @Test
    void everyDeclaredTypeFieldEdgeQueryAndArgumentIsDescribed() {
        def art = artifact()
        List<String> missing = []
        art.types.values().each { t ->
            if (!t.description) missing << "type ${t.name}"
            t.fields.values().each { f -> if (!f.description) missing << "field ${t.name}.${f.name}" }
            t.edges.values().each { e -> if (!e.description) missing << "edge ${t.name}.${e.name}" }
        }
        art.rootQueries.values().each { q ->
            if (!q.description) missing << "query ${q.name}"
            q.args.each { a -> if (!a.description) missing << "argument ${q.name}(${a.name})" }
        }
        assertTrue(art.types.size() > 30, "the audit types are declared (${art.types.size()})")
        assertEquals([], missing, "${missing.size()} declarations have no description")
    }
}
