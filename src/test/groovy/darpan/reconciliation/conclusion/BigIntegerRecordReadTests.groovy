package darpan.reconciliation.conclusion

import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-BE-318. Records the groovy-json behaviour for an integer beyond the Long range, per parser type. */
class BigIntegerRecordReadTests {

    static final String DOC = '{"n":123456789012345678901,"d":1.5,"s":"x"}'

    @Test
    void recordsWhatEachJsonSlurperParserTypeReturns() {
        println "groovy-json: ${JsonSlurper.package.implementationVersion ?: GroovySystem.version}"
        Map<String, String> seen = new LinkedHashMap<>()
        for (JsonParserType type : JsonParserType.values()) {
            Object parsed = new JsonSlurper().setType(type).parseText(DOC)
            Object n = ((Map) parsed).get("n")
            seen.put(type.name(), n == null ? "null" : "${n.getClass().simpleName}:${n}")
        }
        println "BIGINT-REPRO ${seen}"
        // The point of this class: no parser type reads the value back exactly as a BigInteger.
        seen.each { String type, String text -> assertEquals(false, text == "BigInteger:123456789012345678901", type) }
    }
}
