package darpan.reconciliation.support

import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult

import java.nio.file.Files
import java.nio.file.Path

/**
 * DAR-OPS-029 S99-200: every entity's primary key, read from the entity XML (framework/entity and each component's
 * entity/ directory), so {@link FakeEntityFacade} never relies on a hand-typed key. Keys are both the full name
 * (package.Entity) and, when unique, the bare entity name, as Moqui resolves both. A view-entity's key is the key fields
 * of its members that it aliases (alias-all, or an alias of a key field). Parsed once per JVM, no Moqui.
 */
class EntityPrimaryKeys {
    private static volatile Map<String, List<String>> byName

    /** The primary key of {@code entityName}, or null when no entity XML defines it. */
    static List<String> of(String entityName) { all().get(entityName) }

    static Map<String, List<String>> all() {
        if (byName == null) {
            synchronized (EntityPrimaryKeys) {
                if (byName == null) byName = load(ReconciliationSmokeTestSupport.resolveBackendRoot())
            }
        }
        return byName
    }

    private static Map<String, List<String>> load(Path backendRoot) {
        List<Path> files = []
        collect(backendRoot.resolve("framework/entity"), files)
        Path components = backendRoot.resolve("runtime/component")
        if (Files.isDirectory(components)) {
            Files.list(components).withCloseable { it.sorted().forEach { Path c -> collect(c.resolve("entity"), files) } }
        }
        Map<String, List<String>> entities = [:]
        List<GPathResult> views = []
        files.each { Path f ->
            GPathResult root = new XmlSlurper(false, false, true).parse(f.toFile())
            root.'entity'.each { GPathResult e ->
                List<String> pk = e.'field'.findAll { it.@'is-pk'.text() == "true" }.collect { it.@name.text() }
                if (pk) entities.put(fullName(e), pk)
            }
            root.'view-entity'.each { views.add(it as GPathResult) }
        }
        Map<String, List<String>> resolved = withBareNames(entities)
        views.each { GPathResult v ->
            List<String> pk = viewKey(v, resolved)
            if (pk) entities.put(fullName(v), pk)
        }
        return withBareNames(entities).asImmutable()
    }

    private static List<String> viewKey(GPathResult view, Map<String, List<String>> entities) {
        Map<String, List<String>> memberKeys = [:]
        view.'member-entity'.each { m -> memberKeys.put(m.@'entity-alias'.text(), entities.get(m.@'entity-name'.text()) ?: []) }
        view.'member-relationship'.each { m ->
            // A member reached by a relationship: its key is not known without the relationship, so it adds none.
            memberKeys.put(m.@'entity-alias'.text(), [])
        }
        LinkedHashSet<String> pk = new LinkedHashSet<>()
        view.'alias-all'.each { a ->
            Set<String> excluded = a.'exclude'.collect { it.@field.text() } as Set
            (memberKeys.get(a.@'entity-alias'.text()) ?: []).each { if (!(it in excluded)) pk.add(it) }
        }
        view.'alias'.each { a ->
            String field = a.@field.text() ?: a.@name.text()
            if (field in (memberKeys.get(a.@'entity-alias'.text()) ?: [])) pk.add(a.@name.text())
        }
        return pk as List<String>
    }

    private static Map<String, List<String>> withBareNames(Map<String, List<String>> full) {
        Map<String, List<String>> out = new LinkedHashMap<>(full)
        full.keySet().groupBy { it.substring(it.lastIndexOf('.') + 1) }.each { String bare, List<String> names ->
            if (names.size() == 1 && !out.containsKey(bare)) out.put(bare, full.get(names[0]))
        }
        return out
    }

    private static String fullName(GPathResult e) {
        String pkg = e.@package.text() ?: e.@'package-name'.text()
        String name = e.@'entity-name'.text()
        return pkg ? "${pkg}.${name}".toString() : name
    }

    private static void collect(Path dir, List<Path> into) {
        if (!Files.isDirectory(dir)) return
        Files.walk(dir).withCloseable { s -> s.filter { it.toString().endsWith(".xml") }.sorted().forEach { into.add(it) } }
    }
}
