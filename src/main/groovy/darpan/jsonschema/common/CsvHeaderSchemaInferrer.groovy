package jsonschema.common

import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import com.univocity.parsers.csv.UnescapedQuoteHandling

/**
 * Reads the header row of a CSV sample and turns it into a flat JSON Schema.
 *
 * The dialect here is NOT a free choice. ReconciliationServices.ingestFile reads CSV as
 * spark.read().option("header", ...).option("multiLine", "true").csv(path) with no delimiter
 * option, so the runtime parses with Spark's default comma. Anything this class accepts that
 * Spark would parse differently becomes a run that matches zero records with no error at all —
 * see CsvHeaderSparkDialectTests, which pins the two parsers together.
 */
class CsvHeaderSchemaInferrer {

    /** Head-slice size the browser sends. Kept here so both ends quote the same number. */
    static final int HEADER_SLICE_LIMIT = 65536

    private static final Map<String, String> RIVAL_DELIMITERS = [';': 'semicolons', '\t': 'tabs', '|': 'pipes']

    /** Refusal to infer. The message is user-facing and is surfaced verbatim in the wizard. */
    static class CsvHeaderException extends RuntimeException {
        CsvHeaderException(String message) { super(message) }
    }

    /**
     * @param csvText        head slice of the uploaded file, possibly the whole file
     * @param isCompleteFile true when csvText is the entire file rather than a slice
     * @return column names in file order
     */
    static List<String> parseHeader(String csvText, boolean isCompleteFile) {
        if (csvText == null || csvText.trim().isEmpty()) {
            throw new CsvHeaderException("The uploaded file is empty.")
        }

        String text = stripBom(csvText)

        // A single-line JSON document parses as a perfectly plausible two-column CSV
        // ({"orderId":"1001" / "status":"OPEN"}), so nothing downstream would catch it.
        String trimmedText = text.trim()
        if (trimmedText.startsWith('{') || trimmedText.startsWith('[')) {
            throw new CsvHeaderException("This file looks like JSON, not CSV. " +
                    "Choose the JSON file type for this source instead.")
        }

        // A severed header and a complete file with no trailing newline are byte-identical.
        // isCompleteFile is the only thing that separates them; without it this would happily
        // infer a schema from half a header row.
        if (!hasLineTerminatorOutsideQuotes(text) && !isCompleteFile) {
            throw new CsvHeaderException("The header row is longer than the ${HEADER_SLICE_LIMIT}-byte " +
                    "sample read from this file, so it cannot be read reliably.")
        }

        String[] parsed = firstRecord(text)
        if (parsed == null || parsed.length == 0) {
            throw new CsvHeaderException("No header row was found in the uploaded file.")
        }

        List<String> columns = parsed.collect { String cell -> cell == null ? "" : cell.trim() }

        int blankIndex = columns.findIndexOf { String column -> column.isEmpty() }
        if (blankIndex >= 0) {
            throw new CsvHeaderException("Column ${blankIndex + 1} in the header row has no name. " +
                    "Give every column a name and upload the file again.")
        }

        rejectRivalDelimiter(columns, text)

        // Schema properties are a Map, so duplicates would silently collapse and the field list
        // would misrepresent the file. Nothing here can know which of two identically named
        // columns the user meant to key on, so this refuses rather than guessing.
        List<String> duplicates = columns.countBy { it }.findAll { it.value > 1 }.keySet().sort()
        if (duplicates) {
            throw new CsvHeaderException("The header row repeats these column names: " +
                    "${duplicates.join(', ')}. Give each column a distinct name and upload the file again.")
        }

        return columns
    }

    /** Flat draft-07 object schema, every column typed string, column order preserved. */
    static Map<String, Object> buildSchemaMap(List<String> columns) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>()
        columns.each { String column -> properties.put(column, [type: 'string'] as Map<String, Object>) }

        Map<String, Object> schemaMap = new LinkedHashMap<String, Object>()
        schemaMap.put('$schema', 'http://json-schema.org/draft-07/schema#')
        schemaMap.put('type', 'object')
        // put(), never `.properties =` -- Groovy resolves `.properties` on a Map to the bean
        // getProperties(), so the assignment would land somewhere the flattener never looks.
        schemaMap.put('properties', properties)
        schemaMap.put('required', [])
        return schemaMap
    }

    /**
     * A comma-free header is only rejected on positive evidence of another delimiter. A genuine
     * single-column CSV is a valid reconciliation input -- a key with no other fields still
     * yields present/absent counts -- and rejecting every comma-free header would block it.
     */
    private static void rejectRivalDelimiter(List<String> columns, String text) {
        if (columns.size() > 1) return

        String headerLine = firstLine(text)
        if (headerLine.contains(',')) return

        Map.Entry<String, String> rival = RIVAL_DELIMITERS.find { headerLine.contains(it.key) }
        if (rival == null) return

        throw new CsvHeaderException("The header row has no commas but does contain ${rival.value}. " +
                "Reconciliation reads CSV files as comma-separated, so this file cannot be read as it stands.")
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text
    }

    private static String firstLine(String text) {
        // String args, not `as char`: Groovy boxes a char literal to Character, which matches
        // neither String.indexOf(int) nor String.indexOf(String) and throws MissingMethodException.
        List<Integer> breaks = [text.indexOf('\n'), text.indexOf('\r')].findAll { it >= 0 }
        return breaks.isEmpty() ? text : text.substring(0, breaks.min())
    }

    /** True when a bare CR or LF appears outside a quoted region. Doubled quotes toggle twice, which nets out correctly. */
    private static boolean hasLineTerminatorOutsideQuotes(String text) {
        boolean inQuotes = false
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index)
            if (character == ('"' as char)) {
                inQuotes = !inQuotes
            } else if (!inQuotes && (character == ('\n' as char) || character == ('\r' as char))) {
                return true
            }
        }
        return false
    }

    private static String[] firstRecord(String text) {
        CsvParser parser = new CsvParser(sparkDialectSettings())
        try {
            parser.beginParsing(new StringReader(text))
            return parser.parseNext()
        } finally {
            parser.stopParsing()
        }
    }

    /**
     * univocity configured to Spark's CSV defaults. If CsvHeaderSparkDialectTests fails, the fix
     * is to change THESE SETTINGS to match Spark -- never to relax the assertion. Spark is the
     * thing that reads the file at run time; this class only has to agree with it.
     */
    private static CsvParserSettings sparkDialectSettings() {
        CsvParserSettings settings = new CsvParserSettings()
        settings.format.setDelimiter(',' as char)
        settings.format.setQuote('"' as char)
        // Backslash, NOT the doubled quote a reader might expect. Spark's CSVOptions defaults
        // `escape` to '\' and passes it straight to univocity's quoteEscape, so `"say ""hi"""`
        // is NOT unescaped -- Spark names that column literally `"say ""hi"""`. Setting '"' here
        // made this class unescape to `say "hi"`, and CsvHeaderSparkDialectTests caught it.
        settings.format.setQuoteEscape('\\' as char)
        // Spark's default for a quote it cannot account for: keep the raw text to the delimiter.
        settings.setUnescapedQuoteHandling(UnescapedQuoteHandling.STOP_AT_DELIMITER)
        settings.setLineSeparatorDetectionEnabled(true)
        settings.setHeaderExtractionEnabled(false)
        settings.setMaxCharsPerColumn(-1)
        settings.setNullValue("")
        settings.setEmptyValue("")
        return settings
    }
}
