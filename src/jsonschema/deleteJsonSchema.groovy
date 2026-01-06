import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference

ExecutionContext ec = context.ec

// 1. Validate inputs
if (!schemaFileName) {
    ec.message.addError("Schema filename is required")
    return
}

// 2. Resolve file path
// Use the same standard location as saveJsonSchema: runtime/schemas/json
ResourceReference schemaBaseDirRef = ec.resource.getLocationReference("runtime://schemas/json")

if (schemaBaseDirRef == null || !schemaBaseDirRef.getExists()) {
    ec.message.addError("Schema directory not found")
    return
}

ResourceReference fileRef = schemaBaseDirRef.getChild(schemaFileName)

if (fileRef == null || !fileRef.getExists()) {
    ec.message.addError("Schema file not found: ${schemaFileName}")
    return
}

// 3. Delete file
try {
    fileRef.delete()
    ec.message.addMessage("Deleted schema: ${schemaFileName}")
} catch (Exception e) {
    ec.message.addError("Error deleting file: ${e.message}")
}
