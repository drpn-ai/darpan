package darpan.architecture

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * MACH P1 — entity ownership enforced by construction (build-time fitness function).
 *
 * <p>Two invariants over the four Darpan components (darpan, darpan-hotwax, shopify-darpan,
 * netsuite-darpan):</p>
 * <ol>
 *   <li><b>No duplicate entity definitions</b> (audit H12.1 root cause): the same fully-qualified
 *       entity defined in two components silently shadows depending on load order.</li>
 *   <li><b>Dependency direction ratchet:</b> integration components MAY reference core (darpan)
 *       entities — that is the sanctioned plugin→core direction. Core referencing an entity owned
 *       by an integration component is inverted ownership; the two existing debts are frozen in
 *       {@code CORE_TO_PLUGIN_BASELINE} and any NEW core→plugin reference fails the build. When a
 *       baseline pair is finally removed (owner-side read service published), shrink the baseline
 *       in the same change.</li>
 * </ol>
 *
 * <p>Same fail-mode ratchet convention as {@code DisableAuthzRatchetTest}.</p>
 */
class EntityOwnershipGuardTest {

    private static final List<String> COMPONENTS = ["darpan", "darpan-hotwax", "shopify-darpan", "netsuite-darpan"]

    /** Frozen inverted-ownership debt as of 2026-07-02 (see class Javadoc). */
    private static final Set<String> CORE_TO_PLUGIN_BASELINE = [
            "darpan -> darpan.hotwax.HotWaxOmsRestSourceConfig (owned by darpan-hotwax)",
            "darpan -> darpan.shopify.ShopifyAuthConfig (owned by shopify-darpan)",
    ] as Set

    private static final Pattern ENTITY_TOKEN = Pattern.compile(/darpan\.\w+\.\w+/)

    @Test
    void entityOwnershipInvariantsHold() {
        Path componentRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")

        // 1. Ownership map + duplicate-definition check (H12.1).
        Map<String, String> ownerByEntity = [:]
        List<String> duplicates = []
        COMPONENTS.each { String component ->
            Path entityDir = componentRoot.resolve(component).resolve("entity")
            if (!Files.isDirectory(entityDir)) return
            Files.list(entityDir).filter { it.toString().endsWith(".xml") }.forEach { Path xml ->
                def root = new XmlParser().parse(xml.toFile())
                (root.entity + root."view-entity").each { e ->
                    String full = "${e.@package}.${e.@"entity-name"}"
                    String existing = ownerByEntity[full]
                    if (existing != null && existing != component) {
                        duplicates << "${full} defined in both ${existing} and ${component}"
                    }
                    ownerByEntity[full] = component
                }
            }
        }
        assertTrue(duplicates.isEmpty(),
                "Duplicate entity definitions across components (load-order shadowing, H12.1): ${duplicates}")
        assertTrue(ownerByEntity.size() >= 20,
                "Expected the full 4-component entity surface; found only ${ownerByEntity.size()} — are sibling components checked out?")

        // 2. Direction ratchet: core must not reference plugin-owned entities beyond the frozen baseline.
        Set<String> coreToPlugin = [] as Set
        COMPONENTS.each { String component ->
            List<Path> sources = []
            Path groovyRoot = componentRoot.resolve(component).resolve("src/main/groovy")
            Path serviceRoot = componentRoot.resolve(component).resolve("service")
            [groovyRoot, serviceRoot].each { Path root ->
                if (Files.isDirectory(root)) {
                    Files.walk(root).filter { Path p ->
                        String s = p.toString(); s.endsWith(".groovy") || s.endsWith(".xml")
                    }.forEach { sources << it }
                }
            }
            sources.each { Path src ->
                String content = src.toFile().text
                Matcher m = ENTITY_TOKEN.matcher(content)
                Set<String> seen = [] as Set
                while (m.find()) seen << m.group()
                seen.each { String token ->
                    String owningComponent = ownerByEntity[token]
                    if (owningComponent == null || owningComponent == component) return
                    if (component == "darpan") {
                        coreToPlugin << "darpan -> ${token} (owned by ${owningComponent})".toString()
                    }
                    // plugin -> core (and plugin -> plugin via core-owned packages) is the sanctioned direction.
                }
            }
        }

        Set<String> newViolations = coreToPlugin - CORE_TO_PLUGIN_BASELINE
        Set<String> resolved = CORE_TO_PLUGIN_BASELINE - coreToPlugin
        System.out.println("[EntityOwnershipGuardTest] core->plugin refs: ${coreToPlugin.size()}/${CORE_TO_PLUGIN_BASELINE.size()} baseline")
        assertTrue(newViolations.isEmpty(),
                "NEW core->plugin entity references (inverted ownership — publish an owner-side service instead): ${newViolations}")
        assertTrue(resolved.isEmpty(),
                "Baseline core->plugin references no longer present — shrink CORE_TO_PLUGIN_BASELINE to lock in the win: ${resolved}")
    }
}
