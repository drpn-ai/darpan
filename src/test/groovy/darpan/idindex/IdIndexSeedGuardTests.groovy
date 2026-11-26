package darpan.idindex

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-374: the ID-index rows each system repo ships. Every source has an OWN field, every product path is plain and no
 * customer detail, every REF and mapped kind is a known kind. A system repo that is not checked out next to darpan (or is at
 * a version without these rows) is simply not checked: system repos are optional.
 */
class IdIndexSeedGuardTests {

    static final Set<String> KINDS = ["ORDER", "ORDER_LINE", "PAYMENT", "SHIPMENT", "FULFILLMENT", "REFUND", "RETURN", "INVOICE",
                                      "RETURN_AUTHORIZATION", "CREDIT_MEMO"] as Set
    static final List<String> SEED_FILES = ["shopify-darpan/data/ShopifyIdIndexSeedData.xml", "darpan-hotwax/data/HotWaxIdIndexSeedData.xml",
                                            "netsuite-darpan/data/NetSuiteIdIndexSeedData.xml"]

    private static List<Map> rows(String entity) {
        File components = new File("..").canonicalFile
        List<Map> out = []
        SEED_FILES.each { String rel ->
            File f = new File(components, rel)
            if (!f.isFile()) return
            new XmlSlurper().parse(f).children().findAll { it.name() == entity }.each { node ->
                out << node.attributes().collectEntries { k, v -> [(k): v] } + [file: rel]
            }
        }
        return out
    }

    @Test
    void "every source has an OWN field, a key and a fixed or mapped kind"() {
        List<Map> fields = rows("darpan.idindex.IdIndexField")
        rows("darpan.idindex.IdIndexSource").each { Map s ->
            assertTrue(fields.any { it.systemEnumId == s.systemEnumId && it.role == "OWN" }, "${s.systemEnumId} has no OWN field")
            assertTrue((s.idField as String)?.length() > 0, "${s.systemEnumId} idField")
            assertTrue(s.recordKind in KINDS || ((s.recordKindField || s.kindHintEntity) && s.recordKindMapJson), "${s.systemEnumId} kind")
            if (s.recordKindMapJson) new groovy.json.JsonSlurper().parseText(s.recordKindMapJson as String).values().each {
                assertTrue(it in KINDS, "${s.systemEnumId} maps to ${it}") }
            assertTrue(s.systemCode in ["OMS", "SHOPIFY", "NETSUITE"], "${s.systemEnumId} systemCode")
        }
    }

    @Test
    void "no product field is a customer detail or an odd path"() {
        rows("darpan.idindex.IdIndexField").each { Map f ->
            assertNull(IdIndexCatalog.fieldPathRefusal(f.fieldPath as String, IdIndexCatalog.DEFAULT_CUSTOMER_PATTERNS), "${f.file} ${f.fieldId}")
            assertTrue(f.role in ["OWN", "REF"], "${f.fieldId} role")
            if (f.refRecordKind) assertTrue(f.refRecordKind in KINDS, "${f.fieldId} refRecordKind")
            if (f.recordKind) assertTrue(f.recordKind in KINDS, "${f.fieldId} recordKind")
        }
    }
}
