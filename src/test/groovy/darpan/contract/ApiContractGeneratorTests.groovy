package darpan.contract

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlParser
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Contract-generator invariants + the local drift gate: the committed openapi.json must
 * always match what the facade XML generates (same check CI runs via generateApiContract --check).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiContractGeneratorTests {

    private static File componentRoot
    private static Map spec

    @BeforeAll
    void setup() {
        componentRoot = resolveComponentRoot()
        spec = ApiContractGenerator.generate(componentRoot)
    }

    private static File resolveComponentRoot() {
        File cwd = new File("").absoluteFile
        List<File> candidates = [
                cwd,
                new File(cwd, "runtime/component/darpan"),
                new File(cwd, "darpan-backend/runtime/component/darpan"),
                new File(cwd, "../../../runtime/component/darpan"),
        ]
        for (File candidate in candidates) {
            if (new File(candidate, "service/facade/AuthFacadeServices.xml").exists()) return candidate.canonicalFile
        }
        throw new IllegalStateException("Cannot locate darpan component root from ${cwd}")
    }

    /** Independent XML count — every allow-remote facade service must appear in the contract. */
    @Test
    void generatesOneOperationPerAllowRemoteFacadeService() {
        int expected = 0
        for (String dir in ApiContractGenerator.FACADE_DIRS) {
            File facadeDir = new File(componentRoot, dir)
            if (!facadeDir.isDirectory()) continue
            facadeDir.listFiles({ File f -> f.name.endsWith(".xml") } as FileFilter).each { File xml ->
                expected += new XmlParser().parse(xml).service.count { it.@"allow-remote" == "true" }
            }
        }
        assertTrue(expected >= 60, "expected a substantial facade surface, found ${expected}")
        assertEquals(expected, ((Map) spec."x-darpan-methods").size())
    }

    @Test
    void coreMethodsFromAllComponentsArePresent() {
        Map methods = (Map) spec."x-darpan-methods"
        ["facade.AuthFacadeServices.login#Session",
         "facade.ReconciliationFacadeServices.list#Mappings",
         "facade.SettingsFacadeServices.list#EnumOptions",
         "facade.ShopifyFacadeServices.save#ShopifyAuthConfig",
         "facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig"].each { String method ->
            assertTrue(methods.containsKey(method), "missing ${method}")
        }
    }

    @Test
    void everyRequestSchemaCarriesItsMethodConstantAndDiscriminatorEntry() {
        Map schemas = (Map) ((Map) spec.components).schemas
        Map mapping = (Map) ((Map) ((Map) ((Map) ((Map) ((Map) spec.paths)."/rpc/json").post.requestBody.content)."application/json").schema).discriminator.mapping
        ((Map) spec."x-darpan-methods").each { String method, Object pascal ->
            Map request = (Map) schemas["${pascal}Request".toString()]
            assertNotNull(request, "missing request schema for ${method}")
            // NB: .get('properties') — plain property access on a Map hits bean getProperties() (Groovy 4)
            assertEquals(method, ((Map) ((Map) request.get('properties')).method).const)
            assertEquals("#/components/schemas/${pascal}Request".toString(), mapping[method])
        }
    }

    @Test
    void errorModelDocumentsJsonRpcProtocolCodesAndInEnvelopeBusinessErrors() {
        Map schemas = (Map) ((Map) spec.components).schemas
        Map error = (Map) schemas.JsonRpcError
        // Verified against Moqui ServiceJsonRpcDispatcher: -32xxx protocol codes plus the
        // dispatcher's 500 (service error / ec.message errors) and 403 (authz denial).
        assertEquals([-32700, -32600, -32601, -32602, -32603, 500, 403], ((Map) ((Map) error.get('properties')).code).enum)
        String desc = ((Map) schemas.JsonRpcErrorResponse).description as String
        assertTrue(desc.contains("ok=false"), "error response must document the in-envelope business-error model")
        // No fabricated HTTP 401/403 responses — status semantics are honest (200 + envelope).
        Map responses = (Map) ((Map) ((Map) spec.paths)."/rpc/json").post.responses
        assertEquals(["200"], new ArrayList(responses.keySet()))
    }

    @Test
    void requiredInputsAndContainerShapesSurviveGeneration() {
        Map schemas = (Map) ((Map) spec.components).schemas
        Map loginParams = (Map) schemas.LoginSessionParams
        assertEquals(["username", "password"], loginParams.required)
        // list-shaped out-param → array; object out-param → object with properties
        assertEquals("array", ((Map) ((Map) ((Map) schemas.ListMappingsResult).get('properties')).mappings).type)
        Map sessionInfo = (Map) ((Map) ((Map) schemas.LoginSessionResult).get('properties')).sessionInfo
        assertEquals("object", sessionInfo.type)
        assertEquals("array", ((Map) ((Map) sessionInfo.get('properties')).availableTenants).type)
    }

    /** The committed contract must match the XML — same gate CI enforces via --check. */
    @Test
    void committedContractMatchesGeneratedContract() {
        File specFile = new File(componentRoot, ApiContractGenerator.SPEC_RELATIVE_PATH)
        File methodsFile = new File(componentRoot, ApiContractGenerator.METHODS_RELATIVE_PATH)
        assertTrue(specFile.exists(), "committed contract missing — run ./gradlew :runtime:component:darpan:generateApiContract")
        String expected = JsonOutput.prettyPrint(JsonOutput.toJson(spec)) + "\n"
        assertEquals(expected, specFile.text,
                "openapi.json is stale — regenerate with ./gradlew :runtime:component:darpan:generateApiContract")
        assertEquals(ApiContractGenerator.extractMethodNames(spec).join("\n") + "\n", methodsFile.text,
                "methods.txt is stale — regenerate with ./gradlew :runtime:component:darpan:generateApiContract")
        // sanity: committed artifact is valid JSON
        assertNotNull(new JsonSlurper().parse(specFile))
    }
}
