import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.File

def logger = LoggerFactory.getLogger("darpan.reconciliation.view.MissingJsonList")

// --------------------------------------------------------------------------------
// Inputs
// --------------------------------------------------------------------------------
// outputFile (String): Path to the output file or directory
// pageIndex (String/Int): Page number (0-based)
// pageSize (String/Int): Page size
// missingType (String): Filter type (e.g., "missing_in_json1")

long viewMaxInMemoryBytes = 5 * 1024 * 1024 // 5MB limit for in-memory loading (legacy check)

// Helper: Parse safe int
def parseIntSafe = { val, defVal ->
    if (val == null) return defVal
    if (val instanceof Integer) return val
    try { return Integer.parseInt(val.toString()) } catch (Exception e) { return defVal }
}

def trimToNull = { str ->
    def s = str?.toString()?.trim()
    return (s && s.length() > 0) ? s : null
}

int index = parseIntSafe(resolveParamValue('pageIndex'), 0)
int size = parseIntSafe(resolveParamValue('pageSize'), 20)
String filterType = trimToNull(missingType)

// Validate inputs
if (!outputFile) {
    ec.message.addError("No output file specified")
    return
}

File targetFile = new File(outputFile)
if (!targetFile.exists()) {
    // try relative to runtime
    String rt = ec.factory.getRuntimePath()
    targetFile = new File(rt, outputFile)
    if (!targetFile.exists()) {
         ec.message.addError("Output file not found: ${outputFile}")
         return
    }
}

// --------------------------------------------------------------------------------
// Logic
// --------------------------------------------------------------------------------

def messagesList = []
long listCount = 0

// Metadata defaults
Map metadata = [:]
Map summary = [:]

// Detect mode: Directory (Spark) vs Single File (Legacy/Small)
boolean isDirectory = targetFile.isDirectory()
List<File> dataFiles = []

if (isDirectory) {
    // Spark Output: Directory of JSONL files
    // Metadata usually missing or in a specific file. 
    // We assume the caller knows the context or we try to find a metadata.json if we wrote one?
    // Our new Mixed service creates a directory but doesn't strictly separate metadata yet unless we check for it.
    // Actually, Mixed service writes ONE file or directory. 
    // Spark 'write.json' creates a directory of part-*.json files. 
    // Any metadata we wrote manually might be lost if we used Spark write directly.
    // Update: In reconcileMixedFiles.groovy, we decided to iterate and write a SINGLE JSON file manually 
    // if we wanted metadata wrapping (which avoids Spark directory structure for the final output).
    // WAIT. My plan changed to "Use Spark ... to write the output directly". 
    // If I use Spark write, I get a directory. I LOSE the "metadata" wrapper.
    // So the previous logic (metadata, summary, differences list) is valid for the LEGACY single file usage.
    // For Spark directory usage, the file content is purely JSONL.
    
    // Let's support both.
    
    targetFile.eachFile { f ->
        if ((f.name.startsWith("part-") || f.name.endsWith(".json")) && !f.name.endsWith(".crc") && !f.name.startsWith("_")) {
            dataFiles.add(f)
        }
    }
} else {
    dataFiles.add(targetFile)
}

// Check format. If single file, is it wrapped (metadata/differences array) or JSONL?
// Heuristic: Read first char. '{' = Wrapped Object (likely). 
// If it's a directory, assume JSONL.

boolean isWrappedFormat = !isDirectory
if (isWrappedFormat) {
    // It might still be JSONL if it's a single part file renamed.
    // Check start
    try {
        targetFile.withReader { r ->
             int c = r.read()
             if (c != 123) isWrappedFormat = false // '{'
             // If it starts with {, it could be JSON line too. 
             // But existing code uses { metadata: ... } wrapper.
        }
    } catch (Exception e) {}
}

// If wrapped format, we need the streaming parser from before (re-adding simplified version).
// If JSONL (Spark directory OR single file), we use line iteration.

if (isWrappedFormat) {
    // --- WRAPPED SINGLE FILE STRATEGY ---
    // (Re-using the streaming logic but simplified)
    // See previous implementation or legacy code.
    // For now, I will assume that if we refactored to Spark, we prefer JSONL.
    // But existing outputs might be wrapped.
    // I'll implement a robust JSON reader that handles both if possible, or branching.
    
    // Branch: If it looks like the legacy big file...
    // To be safe, let's treat the file as a source of "records".
    // If wrapped, we extract records from 'differences' array.
    // If JSONL, each line is a record.
    
    // NOTE: Parsing a wrapped file as JSONL will fail.
    // Parsing JSONL as wrapped will fail.
    
    // I'll implement the DIRECTORY (JSONL) strategy as primary since that's the refactor goal.
    // Fallback to legacy viewer for non-directory? 
    // Actually, the new service writes to a DIRECTORY.
    
    if (isDirectory) {
        // Spark JSONL Mode
        def objectMapper = new ObjectMapper()
        long totalMatched = 0
        int added = 0
        long skip = (long)index * (long)size
        
        dataFiles.each { File f ->
            f.withReader("UTF-8") { reader ->
                reader.eachLine { line ->
                    if (!line.trim()) return
                    // Optimization: Check type string before parsing
                    if (!filterType || line.contains("\"type\":\"${filterType}\"")) {
                        totalMatched++
                        if (totalMatched > skip && added < size) {
                             try {
                                 def row = objectMapper.readValue(line, Map.class)
                                 messagesList.add(row)
                                 added++
                             } catch (Exception e) { }
                        }
                    }
                }
            }
        }
        listCount = totalMatched
        
    } else {
        // Legacy Wrapped File Mode
        // Load legacy logic or fail over?
        // Since we are moving to Spark, new outputs are Directories. 
        // Old outputs are Files.
        // We should support old files.
        try {
            def objectMapper = new ObjectMapper()
            // Try reading as full object (small) first
            if (targetFile.length() < viewMaxInMemoryBytes) {
                def root = objectMapper.readTree(targetFile)
                if (root.has("metadata")) metadata = objectMapper.convertValue(root.get("metadata"), Map.class)
                def diffs = root.get("differences")
                if (diffs && diffs.isArray()) {
                    List<Map> all = []
                    diffs.each { n -> 
                        Map r = objectMapper.convertValue(n, Map.class)
                        if (!filterType || r.type == filterType) all.add(r)
                    }
                    listCount = all.size()
                    int start = index * size
                    int end = Math.min(start + size, (int)listCount)
                    if (start < listCount) messagesList = all.subList(start, end)
                }
            } else {
                // Large wrapped file? 
                // We're deprecating this path for new runs. 
                // Just error or show first chunk?
                ec.message.addMessage("File too large for legacy viewer. Please re-run reconciliation to get Spark output.")
            }
        } catch (Exception e) {
             logger.error("Failed to read legacy file", e)
        }
    }

} else {
    // Is directory but marked as not? (Shouldn't happen)
    // Or is single file JSONL?
    // Treat as JSONL
        def objectMapper = new ObjectMapper()
        long totalMatched = 0
        int added = 0
        long skip = (long)index * (long)size
        
        dataFiles.each { File f ->
            f.withReader("UTF-8") { reader ->
                reader.eachLine { line ->
                    if (!line.trim()) return
                    if (!filterType || line.contains("\"type\":\"${filterType}\"")) {
                        totalMatched++
                        if (totalMatched > skip && added < size) {
                             try {
                                 def row = objectMapper.readValue(line, Map.class)
                                 messagesList.add(row)
                                 added++
                             } catch (Exception e) { }
                        }
                    }
                }
            }
        }
        listCount = totalMatched
}

// --------------------------------------------------------------------------------
// Output to Context
// --------------------------------------------------------------------------------

ec.context.put("list", messagesList)
ec.context.put("listCount", listCount)
ec.context.put("listPageIndex", index)
ec.context.put("listPageSize", size)
ec.context.put("metadata", metadata)

def label1 = metadata?.json1Label ?: "File 1"
def label2 = metadata?.json2Label ?: "File 2"

if (filterType && filterType.contains("json1")) { // heuristic
    ec.context.put("missingListTitle", "Missing in ${label1}")
} else if (filterType && filterType.contains("json2")) {
    ec.context.put("missingListTitle", "Missing in ${label2}")
} else {
    ec.context.put("missingListTitle", "Differences")
}

ec.context.put("file1Label", label1)
ec.context.put("file2Label", label2)
