/*
 * Service to reconstruct a JSON Schema from a flattened list of fields (from Wizard UI) 
 * and save it to a file.
 */

import groovy.json.JsonOutput
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.SaveRefined")

if (!schemaName) {
    ec.message.addError("Schema Name is required")
    return
}
if (!fieldList) {
    ec.message.addError("No fields provided")
    return
}

// fieldList is List<Map> from the form-list. 
// Keys: fieldPath, type, required (boolean or "true" string)

// -----------------------------------------------------
// 1. RECONSTRUCT LOGIC
// -----------------------------------------------------

Map rootSchema = [type: 'object', properties: [:], required: []]

// Helper to find or create node at path
def getNode
getNode = { Map current, List<String> pathParts ->
    if (pathParts.isEmpty()) return current
    
    String key = pathParts[0]
    
    // Handle Array Items "[0]"
    if (key == "[0]") {
        if (!current.items) current.items = [type: 'object', properties: [:], required: []]
        return getNode(current.items, pathParts.drop(1))
    }
    
    if (!current.properties) current.properties = [:]
    
    if (!current.properties[key]) {
        // Default placeholder, will be filled when we hit the leaf entry
        current.properties[key] = [type: 'object', properties: [:], required: []]
    }
    
    return getNode(current.properties[key], pathParts.drop(1))
}

// Sort by path length to ensure parents exist? 
// DFS traversal order from input is usually safe, but let's be robust.
// Actually, simple path splitting works if we process carefully.

fieldList.each { field ->
    String path = field.fieldPath
    String type = field.type
    boolean req = field.required == "true" || field.required == true
    
    // cleanup path
    if (path.startsWith(".")) path = path.substring(1)
    
    List<String> parts = path.split(/\./).toList()
    String name = parts.last()
    
    // Parent node
    List<String> parentPath = parts.dropRight(1)
    Map parentNode = getNode(rootSchema, parentPath)
    
    // If name is "[0]", we are defining the array Item itself
    if (name == "[0]") {
        // The node IS 'items'
        // If the array itself was defined as type 'array', parentNode is that array node.
        // Wait, getNode returns the container. 
        // If parent is array, it has 'items'.
        if (parentNode.type == 'array') {
             if (!parentNode.items) parentNode.items = [:]
             parentNode.items.type = type
             // If object, prep props
             if (type == 'object' && !parentNode.items.properties) {
                 parentNode.items.properties = [:]
                 parentNode.items.required = []
             }
        }
    } else {
        // Regular property on Object
        // define property
        if (!parentNode.properties) parentNode.properties = [:]
        
        // Check if existing (created by child traversal)
        if (!parentNode.properties[name]) {
            parentNode.properties[name] = [:]
        }
        
        parentNode.properties[name].type = type
        if (type == 'object' && !parentNode.properties[name].properties) {
             parentNode.properties[name].properties = [:]
             parentNode.properties[name].required = []
        }
         if (type == 'array' && !parentNode.properties[name].items) {
             parentNode.properties[name].items = [:]
        }
        
        // Handle Required
        if (req) {
            if (parentNode.required == null) parentNode.required = []
            if (!parentNode.required.contains(name)) parentNode.required.add(name)
        }
    }
}

// Cleanup: Remove empty 'properties' and 'required' from leaf nodes or non-objects
// Valid JSON Schema shouldn't have empty 'properties' map if strict? No, it's allowed but useless.
// Let's do a recursive cleanup
def cleanup
cleanup = { Map node ->
    if (node.type != 'object') {
        node.remove('properties')
        node.remove('required')
    } else {
        if (node.properties && node.properties.isEmpty()) node.remove('properties')
        if (node.required && node.required.isEmpty()) node.remove('required')
    }
    
    if (node.type != 'array') {
        node.remove('items')
    }
    
    // Recurse
    if (node.properties) node.properties.each { k, v -> cleanup(v) }
    if (node.items) cleanup(node.items)
}

cleanup(rootSchema)

// -----------------------------------------------------
// 2. SAVE LOGIC
// -----------------------------------------------------

// Externalize path
def schemaBaseLocation = ec.resource.properties['reconciliation.schema.location'] ?: "runtime://schemas"
def baseDirRef = ec.resource.getLocationReference(schemaBaseLocation)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory")
}
def schemaDirRef = baseDirRef.makeDirectory("json")

String fileName = schemaName.trim()
if (!fileName.endsWith(".json")) fileName += ".json"
def fileRef = schemaDirRef.getChild(fileName)

fileRef.putText(JsonOutput.toJson(rootSchema))

schemaFileName = fileName
