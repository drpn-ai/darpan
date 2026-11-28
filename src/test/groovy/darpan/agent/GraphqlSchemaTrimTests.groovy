package darpan.agent

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** S99-213: the schema an agent reads is the Query root's reachable types, compact. Pure: no Moqui, no system. */
class GraphqlSchemaTrimTests {

    private static Map named(String kind, String name) { [kind: kind, name: name] }
    private static Map nonNull(Map of) { [kind: "NON_NULL", name: null, ofType: of] }
    private static Map list(Map of) { [kind: "LIST", name: null, ofType: of] }

    private static Map introspection(List<Map> extraTypes = []) {
        [__schema: [queryType: [name: "Query"], types: [
                [kind: "OBJECT", name: "Query", fields: [
                        [name: "orders", description: "Orders by filter", type: nonNull(list(nonNull(named("OBJECT", "Order")))),
                         args: [[name: "filter", type: named("INPUT_OBJECT", "OrderFilter"), defaultValue: null],
                                [name: "first", type: named("SCALAR", "Int"), defaultValue: "10"]]],
                        [name: "node", type: named("INTERFACE", "Node")]]],
                [kind: "OBJECT", name: "Order", interfaces: [named("INTERFACE", "Node")], fields: [
                        [name: "id", type: nonNull(named("SCALAR", "ID"))],
                        [name: "status", type: named("ENUM", "OrderStatus")]]],
                [kind: "INTERFACE", name: "Node", fields: [[name: "id", type: nonNull(named("SCALAR", "ID"))]],
                 possibleTypes: [named("OBJECT", "Order"), named("OBJECT", "Shipment")]],
                [kind: "OBJECT", name: "Shipment", fields: [[name: "id", type: nonNull(named("SCALAR", "ID"))]]],
                [kind: "INPUT_OBJECT", name: "OrderFilter", inputFields: [[name: "since", type: named("SCALAR", "DateTime")]]],
                [kind: "ENUM", name: "OrderStatus", enumValues: [[name: "OPEN", description: "Not shipped"], [name: "SHIPPED"]]],
                [kind: "SCALAR", name: "ID"], [kind: "SCALAR", name: "Int"], [kind: "SCALAR", name: "DateTime"],
                [kind: "SCALAR", name: "String"],
                [kind: "OBJECT", name: "Mutation", fields: [[name: "cancelOrder", type: named("OBJECT", "CancelResult")]]],
                [kind: "OBJECT", name: "CancelResult", fields: [[name: "ok", type: named("SCALAR", "Boolean")]]],
                [kind: "SCALAR", name: "Boolean"],
        ] + extraTypes]]
    }

    @Test
    void onlyTypesReachableFromTheQueryRootAreKept() {
        Map trimmed = GraphqlSchemaTrim.trim(introspection())
        assertEquals("Query", trimmed.queryType)
        List<String> names = ((List<Map>) trimmed.types)*.name
        // through field types, argument types, input fields, interfaces and possible types
        assertEquals(["Query", "Order", "OrderFilter", "Int", "Node", "ID", "OrderStatus", "DateTime", "Shipment"] as Set, names as Set)
        ["Mutation", "CancelResult", "Boolean", "String"].each { assertFalse(names.contains(it), "${it} is not reachable from Query") }
    }

    @Test
    void typesAreCompactAndWrappedTypesReadAsTheyAreWritten() {
        Map byName = ((List<Map>) GraphqlSchemaTrim.trim(introspection()).types).collectEntries { [(it.name): it] }
        Map orders = ((List<Map>) byName.Query.fields).find { it.name == "orders" }
        assertEquals("[Order!]!", orders.type)
        assertEquals("Orders by filter", orders.description)
        assertEquals([[name: "filter", type: "OrderFilter"], [name: "first", type: "Int", defaultValue: "10"]], orders.args)
        assertEquals(["Node"], byName.Order.interfaces)
        assertEquals(["Order", "Shipment"], byName.Node.possibleTypes)
        assertEquals([[name: "OPEN", description: "Not shipped"], [name: "SHIPPED"]], byName.OrderStatus.enumValues)
        assertEquals([name: "ID", kind: "SCALAR"], byName.ID, "nothing empty is carried")
    }

    @Test
    void anAnswerWithoutASchemaIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException) { GraphqlSchemaTrim.trim([data: [:]]) }
        assertTrue(e.message.contains("without a schema"))
    }

    @Test
    void theCapAppliesToTheTrimmedSchemaNotTheRawAnswer() {
        String big = "x" * (AgentQuerySupport.MAX_BODY_BYTES as int)
        // A huge type nobody reaches is dropped, so the answer passes.
        Map passes = GraphqlSchemaTrim.trimWithinCap(introspection([[kind: "OBJECT", name: "Unreached", description: big]]))
        assertFalse(((List<Map>) passes.types)*.name.contains("Unreached"))
        // A huge description on a reachable type is refused.
        Map reachable = introspection()
        ((List<Map>) reachable.__schema.types).find { it.name == "Order" }.description = big
        AgentQuerySupport.AgentQueryRefused refused = assertThrows(AgentQuerySupport.AgentQueryRefused) { GraphqlSchemaTrim.trimWithinCap(reachable) }
        assertTrue(refused.message.contains("larger than 5 MB"), refused.message)
    }
}
