package darpan.facade

import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.fail

/**
 * Pure XML-parse contract snapshot test — no Moqui boot, no DB.
 *
 * Captures every allow-remote="true" service declared in service/facade/*.xml and compares
 * it to a checked-in snapshot. Fails on any IN/OUT parameter drift so that removing or
 * renaming a facade parameter breaks CI instead of breaking the SPA silently at runtime.
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

                List<Map<String, String>> inParams  = collectParams(svcNode, 'in-parameters')
                List<Map<String, String>> outParams = collectParams(svcNode, 'out-parameters')

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

        if (updateMode || !snapshotFile.exists()) {
            snapshotFile.parentFile.mkdirs()
            snapshotFile.setText(generated, 'UTF-8')
            System.out.println("Facade contract snapshot written to: ${snapshotFile.absolutePath}")
            System.out.println("Services captured: ${services.size()}")
            return  // pass — snapshot created or refreshed
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
     * Collect direct-child parameters from an in-parameters or out-parameters block.
     * For each parameter: name, type (defaulting to "String"), required (defaulting to "false").
     * Optionally captures one level of nested <parameter> children for composite params,
     * listed as "name.childName" entries — kept deterministic by sorting.
     */
    private static List<Map<String, String>> collectParams(Node svcNode, String blockName) {
        List<Map<String, String>> result = []
        svcNode.children().each { Object blockChild ->
            if (!(blockChild instanceof Node)) return
            Node blockNode = blockChild as Node
            if (blockNode.name() != blockName) return

            blockNode.children().each { Object paramChild ->
                if (!(paramChild instanceof Node)) return
                Node paramNode = paramChild as Node
                if (paramNode.name() != 'parameter') return

                Map pAttrs = paramNode.attributes()
                String pName     = pAttrs.get('name') ?: ''
                String pType     = pAttrs.get('type') ?: 'String'
                String pRequired = pAttrs.get('required') ?: 'false'

                result.add([name: pName, type: pType, required: pRequired])

                // One level of nested parameters (Map/List children).
                paramNode.children().each { Object nestedChild ->
                    if (!(nestedChild instanceof Node)) return
                    Node nestedNode = nestedChild as Node
                    if (nestedNode.name() != 'parameter') return

                    Map nAttrs = nestedNode.attributes()
                    String nName     = nAttrs.get('name') ?: ''
                    String nType     = nAttrs.get('type') ?: 'String'
                    String nRequired = nAttrs.get('required') ?: 'false'
                    result.add([name: "${pName}.${nName}", type: nType, required: nRequired])
                }
            }
        }
        return result.sort { Map a, Map b -> (a.name as String).compareTo(b.name as String) }
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
