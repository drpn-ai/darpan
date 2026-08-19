package darpan.jsonschema.common

import jsonschema.common.CsvHeaderSchemaInferrer
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class CsvHeaderSchemaInferrerTests {

    @Test
    void readsPlainHeaderInFileOrder() {
        List<String> columns = CsvHeaderSchemaInferrer.parseHeader("orderId,status,total\n1001,OPEN,5.00\n", true)
        assertEquals(["orderId", "status", "total"], columns)
    }

    @Test
    void stripsByteOrderMark() {
        List<String> columns = CsvHeaderSchemaInferrer.parseHeader("﻿orderId,status\n1001,OPEN\n", true)
        assertEquals(["orderId", "status"], columns)
    }

    @Test
    void handlesCrlfLineEndings() {
        List<String> columns = CsvHeaderSchemaInferrer.parseHeader("orderId,status\r\n1001,OPEN\r\n", true)
        assertEquals(["orderId", "status"], columns)
    }

    @Test
    void keepsCommasInsideQuotedHeaders() {
        List<String> columns = CsvHeaderSchemaInferrer.parseHeader('orderId,"city, state",total\n1001,"Austin, TX",5.00\n', true)
        assertEquals(["orderId", "city, state", "total"], columns)
    }

    @Test
    void acceptsHeaderOnlyFileWithTrailingNewline() {
        assertEquals(["orderId", "status"], CsvHeaderSchemaInferrer.parseHeader("orderId,status\n", true))
    }

    @Test
    void acceptsCompleteHeaderOnlyFileWithoutTrailingNewline() {
        assertEquals(["orderId", "status"], CsvHeaderSchemaInferrer.parseHeader("orderId,status", true))
    }

    @Test
    void rejectsTruncatedHeaderWhenSliceIsIncomplete() {
        // Byte-identical to the case above; only isCompleteFile separates them.
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("orderId,status", false)
        }
        assertTrue(failure.message.contains("longer than"), "message was: ${failure.message}")
    }

    @Test
    void rejectsEmptyText() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("", true)
        }
        assertTrue(failure.message.contains("empty"), "message was: ${failure.message}")
    }

    @Test
    void rejectsBlankColumnName() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("orderId,,total\n1001,x,5.00\n", true)
        }
        assertTrue(failure.message.contains("Column 2"), "message was: ${failure.message}")
    }

    @Test
    void rejectsDuplicateColumnNames() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("orderId,status,orderId\n1001,OPEN,1001\n", true)
        }
        assertTrue(failure.message.contains("orderId"), "message was: ${failure.message}")
    }

    @Test
    void acceptsGenuineSingleColumnFile() {
        assertEquals(["orderId"], CsvHeaderSchemaInferrer.parseHeader("orderId\n1001\n1002\n", true))
    }

    @Test
    void rejectsSemicolonDelimitedFile() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("orderId;status;total\n1001;OPEN;5.00\n", true)
        }
        assertTrue(failure.message.contains("comma"), "message was: ${failure.message}")
    }

    @Test
    void rejectsTabDelimitedFile() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader("orderId\tstatus\n1001\tOPEN\n", true)
        }
        assertTrue(failure.message.contains("comma"), "message was: ${failure.message}")
    }

    @Test
    void rejectsJsonFileMisfiledAsCsv() {
        def failure = assertThrows(CsvHeaderSchemaInferrer.CsvHeaderException) {
            CsvHeaderSchemaInferrer.parseHeader('{"orderId":"1001","status":"OPEN"}', true)
        }
        assertTrue(failure.message != null && !failure.message.isEmpty())
    }

    @Test
    void buildsFlatDraft07SchemaPreservingColumnOrder() {
        Map<String, Object> schemaMap = CsvHeaderSchemaInferrer.buildSchemaMap(["orderId", "status", "total"])

        assertEquals('http://json-schema.org/draft-07/schema#', schemaMap.get('$schema'))
        assertEquals('object', schemaMap.get('type'))
        // get(), not .properties -- Groovy would hand back the bean getProperties().
        Map properties = (Map) schemaMap.get('properties')
        assertEquals(["orderId", "status", "total"], properties.keySet().toList())
        assertEquals('string', ((Map) properties.get("orderId")).get('type'))
    }

    @Test
    void builtSchemaFlattensToFlatFieldRows() {
        Map<String, Object> schemaMap = CsvHeaderSchemaInferrer.buildSchemaMap(["orderId", "status"])
        List<Map<String, Object>> fieldList = jsonschema.common.SchemaFlattener.flatten(schemaMap)

        assertEquals(["orderId", "status"], fieldList.collect { it.fieldPath })
        assertTrue(fieldList.every { it.type == 'string' }, "every CSV column is a string")
        assertTrue(fieldList.every { !((String) it.fieldPath).contains('.') }, "no nested paths")
    }
}
