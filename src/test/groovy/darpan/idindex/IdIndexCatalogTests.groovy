package darpan.idindex

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: the ID fields a tenant's index reads (product rows merged with the tenant's) and the customer-field guard. */
class IdIndexCatalogTests {

    private static final List<Map> PRODUCT = [
            [systemEnumId: "SHOPIFY", fieldId: "ORDER_NAME", fieldPath: "name", role: "OWN"],
            [systemEnumId: "SHOPIFY", fieldId: "ORDER_ID", fieldPath: "id", role: "OWN"],
    ]

    @Test
    void "a tenant row overrides the product row with the same id, field by field"() {
        List<Map> merged = IdIndexCatalog.mergeFields(PRODUCT, [[fieldId: "ORDER_NAME", fieldPath: "customName", role: null]])
        assertEquals(["ORDER_NAME", "ORDER_ID"], merged*.fieldId)
        assertEquals("customName", merged[0].fieldPath)
        assertEquals("OWN", merged[0].role, "a blank tenant field keeps the product value")
    }

    @Test
    void "an inactive tenant row hides the product row"() {
        assertEquals(["ORDER_ID"], IdIndexCatalog.mergeFields(PRODUCT, [[fieldId: "ORDER_NAME", isActive: "N"]])*.fieldId)
    }

    @Test
    void "a tenant can add its own field"() {
        List<Map> merged = IdIndexCatalog.mergeFields(PRODUCT,
                [[fieldId: "RAILS_EXT", systemEnumId: "SHOPIFY", fieldPath: "custbody_rails_ext_order_id", role: "REF"]])
        assertEquals(["ORDER_NAME", "ORDER_ID", "RAILS_EXT"], merged*.fieldId)
    }

    @Test
    void "customer fields are refused"() {
        ["customer.email", "shippingAddress.address1", "billingAddress.phone", "email", "custbody_customer_phone",
         "buyerIdentity.id", "lines[*].contactName", "postalCode", "city", "note", "tags", "browserIp", "taxId", "company",
         "customerNote", "noteAttributes[*].value"].each { String p ->
            assertNotNull(IdIndexCatalog.customerFieldRefusal(p, IdIndexCatalog.DEFAULT_CUSTOMER_PATTERNS), "should refuse ${p}")
        }
    }

    @Test
    void "id fields are allowed"() {
        ["name", "orderName", "externalId", "custbody_rails_ext_order_id", "lineItems[*].id", "shipmentMethodTypeId", "tranid",
         "refunds[*].id"].each { String p ->
            assertNull(IdIndexCatalog.customerFieldRefusal(p, IdIndexCatalog.DEFAULT_CUSTOMER_PATTERNS), "should allow ${p}")
        }
    }

    @Test
    void "a broken pattern in the setting refuses nothing by itself but the others still apply"() {
        assertNotNull(IdIndexCatalog.customerFieldRefusal("email", ["(unclosed", "(?i).*email.*"]))
        assertNull(IdIndexCatalog.customerFieldRefusal("orderName", ["(unclosed"]))
    }

    @Test
    void "a path outside the plain dotted shape is refused, so no spelling slips a customer field past the guard"() {
        ['$..email', "customer['email']", 'a..b', '*.x', 'lines[?(@.x)].id', 'x.', '.x', 'a b', 'lines[0].id', '$.name'].each { String p ->
            assertNotNull(IdIndexCatalog.fieldPathRefusal(p), "should refuse ${p}")
        }
        ["name", "lineItems[*].id", "custbody_rails_ext_order_id", "shipGroups[*].shipmentId"].each { String p ->
            assertNull(IdIndexCatalog.fieldPathRefusal(p), "should allow ${p}")
        }
    }

    @Test
    void "underscores, dashes and case do not hide a customer field"() {
        ["e_mail", "E-Mail", "customer_email", "Phone_Number", "ship_to_address.line1", "first_name", "zip_code"].each { String p ->
            assertNotNull(IdIndexCatalog.fieldPathRefusal(p), "should refuse ${p}")
        }
    }
}
