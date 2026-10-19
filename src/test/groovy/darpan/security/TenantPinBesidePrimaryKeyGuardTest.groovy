package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-077 guard. When a find carries an entity's full primary key plus anything else, Moqui's
 * {@code one()} keeps ONLY the primary key (EntityFindBase "if over-constrained ... just use the full
 * PK"). So a tenant pin written as
 * {@code findTenantScoped(ec, X).condition(<pk>, id).one()} or
 * {@code find(X).condition(<pk>, id).condition("companyUserGroupId", t).one()}
 * reads like containment and contains nothing; that is how one tenant could point its automation at
 * another tenant's chat space. A by-id read must check the owner on the row instead:
 * {@code TenantScopedFinder.findOneOwnedByTenant} (or {@code findTenantScopedById*}).
 *
 * <p>This scans every Darpan component's Groovy and service XML for such chains and fails on any that
 * is not allowlisted below with the reason it is safe.</p>
 */
class TenantPinBesidePrimaryKeyGuardTest {
    private static final List<String> COMPONENTS =
            ["darpan", "darpan-hotwax", "shopify-darpan", "netsuite-darpan", "database-darpan"]

    /** "file:method-or-line-hint" -> why the dropped pin is harmless there. */
    private static final Map<String, String> ALLOWLIST = [
            "ReconciliationSavedRunSupport.groovy|reconciliationMappingId":
                    "findSavedRunById re-checks the row with canAccessTenantRecord in buildMappingSavedRunRow",
            "ReconciliationSavedRunSupport.groovy|ruleSetId":
                    "resolveRuleSetRun re-checks the row with canAccessTenantRecord before using it",
    ]

    private static final Pattern CHAIN = Pattern.compile(
            /(findTenantScoped\(\s*ec\s*,\s*|findGlobalUnscoped\(\s*ec\s*,\s*|\.find\(\s*)([^,)\n]+)((?:(?!\.one\(\)).){0,500}?)\.one\(\)/,
            Pattern.DOTALL)
    private static final Pattern CONDITION = Pattern.compile(/\.condition\(\s*["'](\w+)["']/)
    private static final Pattern NEW_FIND = Pattern.compile(/\.find\(|findTenantScoped\(|findGlobalUnscoped\(/)

    @Test
    void noTenantPinSitsBesideAFullPrimaryKeyOnOne() {
        Path componentRoot = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        Map<String, List<String>> primaryKeys = readPrimaryKeys(componentRoot)
        assertTrue(primaryKeys.containsKey("TenantChatSpace"), "entity PKs were not read; the guard would be hollow")
        Map<String, String> constants = readEntityConstants(componentRoot)

        List<String> violations = []
        int chainsSeen = 0
        COMPONENTS.each { String component ->
            Path root = componentRoot.resolve(component)
            [root.resolve("src/main/groovy"), root.resolve("service")].findAll { Files.exists(it) }.each { Path dir ->
                Files.walk(dir).filter { Path p -> p.toString().endsWith(".groovy") || p.toString().endsWith(".xml") }
                        .forEach { Path p ->
                            String text = unescapeXml(p.toFile().text)
                            List<Map<String, Object>> hits = findDroppedPins(text, primaryKeys, constants)
                            chainsSeen += hits.size()
                            hits.findAll { it.dropped }.each { Map<String, Object> hit ->
                                String key = "${p.fileName}|${(hit.pk as List).join(',')}"
                                if (!ALLOWLIST.containsKey(key)) {
                                    violations << "${componentRoot.relativize(p)}:${hit.line} ${hit.entity} pk=${hit.pk}"
                                }
                            }
                        }
            }
        }
        assertTrue(chainsSeen > 0, "no find...one() chains were seen at all; the scan is broken")
        assertTrue(violations.isEmpty(),
                "A tenant condition beside a full primary key is silently dropped by one(). Read by id with " +
                        "TenantScopedFinder.findOneOwnedByTenant (owner checked on the row) instead:\n  " +
                        violations.join("\n  "))
    }

    @Test
    void theDetectorCatchesTheShapesItGuards() {
        // Non-hollow check: both shapes that leaked must be flagged, and the safe shapes must not be.
        Map<String, List<String>> pks = [TenantChatSpace: ["chatSpaceId"], OrderLine: ["orderId", "lineSeqId"]]
        Map<String, String> consts = [TENANT_CHAT_SPACE: "TenantChatSpace"]
        String scoped = 'TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.TENANT_CHAT_SPACE)\n' +
                '    .condition("chatSpaceId", id).useCache(false).one()'
        String explicitPin = 'ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", id)' +
                '.condition("companyUserGroupId", tenantId).disableAuthz().one()'
        String partialPk = 'TenantScopedFinder.findTenantScoped(ec, "OrderLine").condition("orderId", id).one()'
        String list = 'TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.TENANT_CHAT_SPACE).condition("chatSpaceId", id).list()'

        assertEquals([true], findDroppedPins(scoped, pks, consts)*.dropped)
        assertEquals([true], findDroppedPins(explicitPin, pks, consts)*.dropped)
        assertEquals([false], findDroppedPins(partialPk, pks, consts)*.dropped)
        assertEquals([], findDroppedPins(list, pks, consts))
    }

    private static List<Map<String, Object>> findDroppedPins(String text, Map<String, List<String>> primaryKeys,
                                                             Map<String, String> constants) {
        List<Map<String, Object>> hits = []
        Matcher m = CHAIN.matcher(text)
        while (m.find()) {
            String chain = m.group(3)
            if (NEW_FIND.matcher(chain).find()) continue
            boolean tenantScopedFinder = m.group(1).startsWith("findTenantScoped")
            Set<String> conditions = [] as Set
            Matcher c = CONDITION.matcher(chain)
            while (c.find()) conditions << c.group(1)
            if (!tenantScopedFinder && !conditions.contains("companyUserGroupId")) continue

            String entityRef = m.group(2).trim().replaceAll(/^["']|["']$/, "")
            String simple = entityRef.tokenize(".").last()
            String entity = (constants[simple] ?: simple).tokenize(".").last()
            List<String> pk = primaryKeys[entity]
            if (!pk) continue
            boolean dropped = conditions.containsAll(pk) && !pk.contains("companyUserGroupId")
            hits << [entity: entity, pk: pk, dropped: dropped, line: text.substring(0, m.start()).count("\n") + 1]
        }
        return hits
    }

    private static Map<String, List<String>> readPrimaryKeys(Path componentRoot) {
        Map<String, List<String>> pks = [:]
        Pattern entity = Pattern.compile(/<entity\s+entity-name="([^"]+)"[^>]*>(.*?)<\/entity>/, Pattern.DOTALL)
        Pattern pkField = Pattern.compile(/<field\s+name="(\w+)"[^>]*is-pk="true"/)
        COMPONENTS.each { String component ->
            Path dir = componentRoot.resolve(component).resolve("entity")
            if (!Files.exists(dir)) return
            Files.walk(dir).filter { it.toString().endsWith(".xml") }.forEach { Path p ->
                Matcher m = entity.matcher(p.toFile().text)
                while (m.find()) {
                    List<String> fields = []
                    Matcher f = pkField.matcher(m.group(2))
                    while (f.find()) fields << f.group(1)
                    pks[m.group(1)] = fields
                }
            }
        }
        return pks
    }

    private static Map<String, String> readEntityConstants(Path componentRoot) {
        Path file = componentRoot.resolve("darpan/src/main/groovy/darpan/common/DarpanEntityConstants.groovy")
        Map<String, String> constants = [:]
        Matcher m = Pattern.compile(/(\w+)\s*=\s*"([\w.]+)"/).matcher(file.toFile().text)
        while (m.find()) constants[m.group(1)] = m.group(2)
        return constants
    }

    private static String unescapeXml(String text) {
        return text.replace("&quot;", '"').replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
