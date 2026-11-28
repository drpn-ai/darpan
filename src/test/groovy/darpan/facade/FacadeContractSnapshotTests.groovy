package darpan.facade

import darpan.contract.ApiContractGenerator
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.fail

/**
 * Pure XML-parse contract snapshot test — no Moqui boot, no DB.
 *
 * Captures every service declared in service/facade/*.xml (allow-remote or not; the flag is
 * recorded per service) and compares it to a checked-in snapshot. Fails on any IN/OUT
 * parameter drift so that removing or renaming a facade parameter breaks CI instead of
 * breaking the SPA silently at runtime. Parameters a service inherits through
 * <implements service="..."/> are merged the way Moqui's ServiceDefinition merges them
 * (ApiContractGenerator.ServiceIndex: interface first, own attributes override, nested
 * parameters merge by name), so moving a shared parameter set into an interface service
 * leaves the snapshot unchanged.
 *
 * A missing snapshot fails the test; it is only written in update mode.
 *
 * To regenerate the snapshot after an intentional contract change:
 *   CONTRACT_SNAPSHOT_UPDATE=true ./gradlew :runtime:component:darpan:test \
 *       --tests "darpan.facade.FacadeContractSnapshotTests"
 * then commit the updated snapshot file.
 */
class FacadeContractSnapshotTests {

    @Test
    void facadeContractMatchesSnapshot() {
        File facadeDir = locateFacadeDir()
        assertTrue(facadeDir.exists() && facadeDir.isDirectory(),
                "service/facade directory not found — searched from ${new File('.').absolutePath}")

        List<File> xmlFiles = facadeDir.listFiles({ File f -> f.name.endsWith('.xml') } as FileFilter)
                                       .toList()
                                       .sort { it.name }

        assertTrue(!xmlFiles.isEmpty(), "No XML files found in ${facadeDir.absolutePath}")

        // Resolves <implements> across this component and its siblings, as the API contract does.
        ApiContractGenerator.ServiceIndex serviceIndex = new ApiContractGenerator.ServiceIndex(facadeDir.canonicalFile.parentFile.parentFile)

        // Parse every service from every facade file.
        List<Map<String, Object>> services = []
        for (File xmlFile : xmlFiles) {
            Node root = new XmlParser().parse(xmlFile)
            root.children().each { Object child ->
                if (!(child instanceof Node)) return
                Node svcNode = child as Node
                if (svcNode.name() != 'service') return

                Map attrs = svcNode.attributes()
                String verb         = attrs.get('verb') ?: ''
                String noun         = attrs.get('noun') ?: ''
                String type         = attrs.get('type') ?: 'inline'
                String authenticate = attrs.get('authenticate') ?: 'true'
                String allowRemote  = attrs.get('allow-remote') ?: 'false'

                List<Map<String, String>> inParams  = collectParams(svcNode, 'in-parameters', serviceIndex)
                List<Map<String, String>> outParams = collectParams(svcNode, 'out-parameters', serviceIndex)

                services.add([
                        file        : xmlFile.name,
                        verb        : verb,
                        noun        : noun,
                        type        : type,
                        authenticate: authenticate,
                        allowRemote : allowRemote,
                        inParams    : inParams,
                        outParams   : outParams,
                ])
            }
        }

        // Sort deterministically: file → verb → noun.
        services.sort { Map a, Map b ->
            int c = (a.file as String).compareTo(b.file as String)
            if (c != 0) return c
            c = (a.verb as String).compareTo(b.verb as String)
            if (c != 0) return c
            return (a.noun as String).compareTo(b.noun as String)
        }

        String generated = render(services)

        File snapshotFile = locateSnapshotFile()
        boolean updateMode = System.getenv('CONTRACT_SNAPSHOT_UPDATE') == 'true'

        if (updateMode) {
            snapshotFile.parentFile.mkdirs()
            snapshotFile.setText(generated, 'UTF-8')
            return  // pass: snapshot refreshed on request
        }
        if (!snapshotFile.exists()) {
            fail("Facade contract snapshot missing at ${snapshotFile.absolutePath}. Generate it with " +
                    "CONTRACT_SNAPSHOT_UPDATE=true ./gradlew :runtime:component:darpan:test " +
                    "--tests darpan.facade.FacadeContractSnapshotTests and commit it.")
        }

        String committed = snapshotFile.getText('UTF-8')
        if (generated == committed) return  // pass

        // Build a diff-style message showing the first diverging line.
        List<String> genLines  = generated.split('\n', -1).toList()
        List<String> cmmLines  = committed.split('\n', -1).toList()
        int maxLen = Math.max(genLines.size(), cmmLines.size())
        StringBuilder diff = new StringBuilder()
        int shown = 0
        for (int i = 0; i < maxLen && shown < 10; i++) {
            String g = i < genLines.size()  ? genLines[i]  : '<missing>'
            String c = i < cmmLines.size()  ? cmmLines[i]  : '<missing>'
            if (g != c) {
                diff.append("Line ${i + 1}:\n  committed : ${c}\n  generated : ${g}\n")
                shown++
            }
        }

        fail("Facade contract changed. If intentional, regenerate with CONTRACT_SNAPSHOT_UPDATE=true and commit the updated snapshot.\n\nFirst differing line(s):\n${diff}")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Locate service/facade relative to the Gradle test working directory.
     * Gradle sets workingDir to the component project dir by default, so
     * new File("service/facade") is the happy path. If that fails we walk
     * upward from new File(".") to find the first ancestor that contains the
     * directory, making the test resilient to IDE runs from a different cwd.
     */
    private static File locateFacadeDir() {
        File direct = new File('service/facade')
        if (direct.exists() && direct.isDirectory()) return direct

        // Walk up from cwd looking for the component root.
        File cwd = new File('.').absoluteFile
        File candidate = cwd
        while (candidate != null) {
            File try1 = new File(candidate, 'service/facade')
            if (try1.exists() && try1.isDirectory()) return try1
            candidate = candidate.parentFile
        }

        // Last resort: search by known file name pattern.
        return direct  // will fail the assertTrue with a clear message
    }

    /**
     * Locate the snapshot resource file. We look for it next to the test class
     * resources so it is committed inside the component tree.
     */
    private static File locateSnapshotFile() {
        // Canonical path: src/test/resources/facade-contract.snapshot.txt
        // We anchor to the component dir the same way we found service/facade.
        File cwd = new File('.').absoluteFile
        File candidate = cwd
        while (candidate != null) {
            File try1 = new File(candidate, 'src/test/resources/facade-contract.snapshot.txt')
            if (try1.parentFile.exists()) return try1  // parent exists → write there
            candidate = candidate.parentFile
        }
        // Fallback: relative to cwd (will be created on update run).
        return new File('src/test/resources/facade-contract.snapshot.txt')
    }

    /**
     * Index every service definition in the component's service/** XML files, keyed both by
     * full name (path under service/ with '/' as '.', then ".verb#Noun", e.g.
     * "reconciliation.ReconciliationCoreServices.define#FileReconciliationOutcome") and by the
     * bare "verb#Noun". A bare name defined in more than one file keeps the first file in
     * path order; the full name is always unambiguous.
     */
    /**
     * The parameters of an in-parameters or out-parameters block, including those inherited through
     * <implements service="..."/>, merged as Moqui merges them (S99-055: a service that re-declares an inherited Map
     * only to give it its own description keeps the interface's nested fields). For each parameter: name, type
     * (defaulting to "String"), required (defaulting to "false"). One level of nested <parameter> children is listed as
     * "name.childName" entries. Sorted by name. A service with no <implements> renders exactly its own parameters.
     */
    private static List<Map<String, String>> collectParams(Node svcNode, String blockName,
            ApiContractGenerator.ServiceIndex serviceIndex) {
        if (implementsOf(svcNode).isEmpty()) {
            return sortByName(ownParams(svcNode, blockName).values().flatten() as List<Map<String, String>>)
        }
        List merged = serviceIndex.mergedParameters(svcNode, blockName, "${svcNode.attributes().get('verb')}#${svcNode.attributes().get('noun')}".toString())
        return sortByName(merged.collectMany { Object param -> entriesOf(param as Node) } as List<Map<String, String>>)
    }

    private static List<String> implementsOf(Node svcNode) {
        List<String> refs = []
        svcNode.children().each { Object child ->
            if (child instanceof Node && (child as Node).name() == 'implements') {
                String ref = (child as Node).attributes().get('service') ?: ''
                if (ref) refs.add(ref)
            }
        }
        return refs
    }

    /** Own parameters keyed by top-level name; each value is the parameter then its nested children. */
    private static Map<String, List<Map<String, String>>> ownParams(Node svcNode, String blockName) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>()
        int dupCount = 0
        svcNode.children().each { Object blockChild ->
            if (!(blockChild instanceof Node)) return
            Node blockNode = blockChild as Node
            if (blockNode.name() != blockName) return

            blockNode.children().each { Object paramChild ->
                if (!(paramChild instanceof Node)) return
                Node paramNode = paramChild as Node
                if (paramNode.name() != 'parameter') return

                String pName = paramNode.attributes().get('name') ?: ''
                List<Map<String, String>> entries = entriesOf(paramNode)
                // A duplicate name inside one block is kept (not collapsed) so the render stays faithful.
                String key = result.containsKey(pName) ? "${pName}#dup${dupCount++}".toString() : pName
                result.put(key, entries)
            }
        }
        return result
    }

    /** One parameter and its one level of nested parameters (Map/List children). */
    private static List<Map<String, String>> entriesOf(Node paramNode) {
        Map pAttrs = paramNode.attributes()
        String pName = pAttrs.get('name') ?: ''
        List<Map<String, String>> entries = [[name: pName, type: (pAttrs.get('type') ?: 'String') as String,
                                              required: (pAttrs.get('required') ?: 'false') as String]]
        paramNode.children().each { Object nestedChild ->
            if (!(nestedChild instanceof Node) || (nestedChild as Node).name() != 'parameter') return
            Map nAttrs = (nestedChild as Node).attributes()
            entries.add([name: "${pName}.${nAttrs.get('name') ?: ''}".toString(), type: (nAttrs.get('type') ?: 'String') as String,
                         required: (nAttrs.get('required') ?: 'false') as String])
        }
        return entries
    }

    private static List<Map<String, String>> sortByName(List<Map<String, String>> params) {
        return params.sort { Map a, Map b -> (a.name as String).compareTo(b.name as String) }
    }

    /**
     * Render the canonical, human-readable contract text.
     * Format is stable: sorted by (file, verb, noun), parameters sorted by name.
     */
    private static String render(List<Map<String, Object>> services) {
        StringBuilder sb = new StringBuilder()
        sb.append("# Darpan Facade Contract Snapshot\n")
        sb.append("# Auto-generated by FacadeContractSnapshotTests — do not hand-edit.\n")
        sb.append("# Regenerate: CONTRACT_SNAPSHOT_UPDATE=true ./gradlew :runtime:component:darpan:test --tests darpan.facade.FacadeContractSnapshotTests\n")
        sb.append("# Services: ${services.size()}\n")
        sb.append("\n")

        for (Map<String, Object> svc : services) {
            sb.append("SERVICE ${svc.verb} ${svc.noun}\n")
            sb.append("  file        : ${svc.file}\n")
            sb.append("  type        : ${svc.type}\n")
            sb.append("  authenticate: ${svc.authenticate}\n")
            sb.append("  allow-remote: ${svc.allowRemote}\n")

            List<Map<String, String>> inParams  = svc.inParams  as List<Map<String, String>>
            List<Map<String, String>> outParams = svc.outParams as List<Map<String, String>>

            if (inParams.isEmpty()) {
                sb.append("  IN: (none)\n")
            } else {
                sb.append("  IN:\n")
                for (Map<String, String> p : inParams) {
                    String req = p.required == 'true' ? ' [required]' : ''
                    sb.append("    ${p.name} : ${p.type}${req}\n")
                }
            }

            if (outParams.isEmpty()) {
                sb.append("  OUT: (none)\n")
            } else {
                sb.append("  OUT:\n")
                for (Map<String, String> p : outParams) {
                    String req = p.required == 'true' ? ' [required]' : ''
                    sb.append("    ${p.name} : ${p.type}${req}\n")
                }
            }

            sb.append("\n")
        }

        return sb.toString()
    }
}
