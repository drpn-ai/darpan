package darpan.contract

import groovy.json.JsonOutput
import groovy.xml.XmlParser

/**
 * Generates the Darpan facade API contract (OpenAPI 3.1) from the facade service XML —
 * the single source of truth for the remote surface (MACH roadmap P0, API management).
 *
 * <p>Every {@code allow-remote="true"} service under {@code service/facade/} of the darpan
 * component and its sibling integration components becomes one JSON-RPC operation. The
 * transport is modeled honestly: a single {@code POST /rpc/json} operation whose request body
 * is a discriminated union keyed by {@code method}; business/auth failures ride inside the
 * result envelope ({@code ok}/{@code errors}), while protocol-level failures use the JSON-RPC
 * error object with the standard -32xxx codes. HTTP status is 200 for both.</p>
 *
 * <p>Container parameters MUST declare an explicit {@code type="Map"} or {@code type="List"} —
 * the generator fails closed on ambiguity so the XML stays an unambiguous contract source.</p>
 *
 * <p>No Moqui imports on purpose: runnable standalone from Gradle
 * ({@code ./gradlew :runtime:component:darpan:generateApiContract}) and unit-testable.</p>
 */
class ApiContractGenerator {

    // Scanned service dirs and the method-name prefix each one mints. The prefix IS the
    // security namespace (facade.* is granted to every user; admin.* only to super admins)
    // — a wrong prefix here would misdocument the ACL, so it is explicit per dir.
    static final List<List<String>> SERVICE_DIRS = [
            ["service/facade", "facade"],
            ["../darpan-hotwax/service/facade", "facade"],
            ["../shopify-darpan/service/facade", "facade"],
            ["../netsuite-darpan/service/facade", "facade"],
            ["service/admin", "admin"],
    ]
    static final String SPEC_RELATIVE_PATH = "docs/api-contract/openapi.json"
    static final String METHODS_RELATIVE_PATH = "docs/api-contract/methods.txt"

    /**
     * Facade contract version (MACH P1, API management). Bump ONLY on a breaking change —
     * removed method, removed/renamed parameter or out-field, or a type change — together with
     * updating the compat baseline. Additive changes never bump it (additive-only policy).
     */
    // 3 (1.5.0): list#TenantChatSpaces dropped the out-field chatSpaces[].googleChatWebhookUrlMasked
    //            when chat webhooks moved to clear text; googleChatWebhookUrl replaces it.
    static final int CONTRACT_VERSION = 3

    static final Map<String, Map<String, Object>> SCALAR_TYPES = [
            String    : [type: "string"],
            Boolean   : [type: "boolean"],
            Integer   : [type: "integer"],
            Long      : [type: "integer"],
            BigInteger: [type: "integer"],
            BigDecimal: [type: "number"],
            Double    : [type: "number"],
            Float     : [type: "number"],
            Timestamp : [oneOf: [[type: "string"], [type: "number"]],
                         description: "Timestamp — ISO-8601 string or epoch milliseconds"],
            Date      : [type: "string", format: "date"],
            Time      : [type: "string", format: "time"],
    ] as Map

    static void main(String[] args) {
        File componentRoot = new File(args.length > 0 ? args[0] : ".").canonicalFile
        boolean checkMode = args.contains("--check")

        Map spec = generate(componentRoot)
        String specJson = JsonOutput.prettyPrint(JsonOutput.toJson(spec)) + "\n"
        String methodsText = extractMethodNames(spec).join("\n") + "\n"

        File specFile = new File(componentRoot, SPEC_RELATIVE_PATH)
        File methodsFile = new File(componentRoot, METHODS_RELATIVE_PATH)

        if (checkMode) {
            List<String> drifted = []
            if (!specFile.exists() || specFile.text != specJson) drifted << specFile.path
            if (!methodsFile.exists() || methodsFile.text != methodsText) drifted << methodsFile.path
            if (drifted) {
                System.err.println("API contract drift detected in: ${drifted.join(', ')}. " +
                        "The facade XML changed without regenerating the contract. Run " +
                        "./gradlew :runtime:component:darpan:generateApiContract and commit the result.")
                System.exit(1)
            }
            println "API contract is up to date (${extractMethodNames(spec).size()} methods)."
            return
        }

        specFile.parentFile.mkdirs()
        specFile.text = specJson
        methodsFile.text = methodsText
        println "Wrote ${specFile.path} and ${methodsFile.path} (${extractMethodNames(spec).size()} methods)."
    }

    static List<String> extractMethodNames(Map spec) {
        return new ArrayList<String>(((Map) spec."x-darpan-methods").keySet()).sort()
    }

    static Map generate(File componentRoot) {
        List<Map> operations = []
        for (List<String> serviceDir in SERVICE_DIRS) {
            String dir = serviceDir[0]
            String prefix = serviceDir[1]
            File facadeDir = new File(componentRoot, dir)
            if (!facadeDir.isDirectory()) continue
            facadeDir.listFiles({ File f -> f.name.endsWith(".xml") } as FileFilter).sort { it.name }.each { File xml ->
                operations.addAll(parseServiceFile(xml, prefix))
            }
        }
        operations = operations.sort { it.method as String }
        if (!operations) throw new IllegalStateException("No allow-remote facade services found under ${componentRoot}")

        Map<String, Map> schemas = new LinkedHashMap<>()
        schemas["JsonRpcError"] = jsonRpcErrorSchema()
        schemas["JsonRpcErrorResponse"] = [
                type      : "object",
                description: "Dispatcher-level failure: protocol errors (-32xxx), unhandled service errors (code 500), " +
                        "or authorization denial (code 403). Facade business validation does NOT use this shape — " +
                        "it returns a success envelope with result.ok=false and result.errors[].",
                properties: [
                        jsonrpc: [type: "string", const: "2.0"],
                        id     : [oneOf: [[type: "string"], [type: "number"], [type: "null"]]],
                        error  : [$ref: "#/components/schemas/JsonRpcError"],
                ],
                required  : ["jsonrpc", "error"],
        ]

        Map<String, String> methodIndex = new LinkedHashMap<>()
        List<Map> requestRefs = []
        List<Map> responseRefs = [[$ref: "#/components/schemas/JsonRpcErrorResponse"]]
        Map<String, String> discriminatorMapping = new LinkedHashMap<>()

        for (Map op in operations) {
            String pascal = op.pascal as String
            if (schemas.containsKey("${pascal}Request".toString())) {
                throw new IllegalStateException("Schema name collision for ${pascal} (method ${op.method}); " +
                        "rename the verb#noun or extend the generator with component prefixes.")
            }
            schemas["${pascal}Params".toString()] = op.paramsSchema as Map
            schemas["${pascal}Result".toString()] = op.resultSchema as Map
            schemas["${pascal}Request".toString()] = [
                    type              : "object",
                    description       : "JSON-RPC request envelope for ${op.method}".toString(),
                    "x-darpan-method" : op.method,
                    properties        : [
                            jsonrpc: [type: "string", const: "2.0"],
                            id     : [oneOf: [[type: "string"], [type: "number"]]],
                            method : [type: "string", const: op.method],
                            params : [$ref: "#/components/schemas/${pascal}Params".toString()],
                    ],
                    required          : ["jsonrpc", "id", "method"],
            ]
            schemas["${pascal}Response".toString()] = [
                    type       : "object",
                    description: "JSON-RPC success envelope for ${op.method}. Check result.ok / result.errors for business failures.".toString(),
                    properties : [
                            jsonrpc: [type: "string", const: "2.0"],
                            id     : [oneOf: [[type: "string"], [type: "number"]]],
                            result : [$ref: "#/components/schemas/${pascal}Result".toString()],
                    ],
                    required   : ["jsonrpc", "result"],
            ]
            requestRefs << [$ref: "#/components/schemas/${pascal}Request".toString()]
            responseRefs << [$ref: "#/components/schemas/${pascal}Response".toString()]
            discriminatorMapping[op.method as String] = "#/components/schemas/${pascal}Request".toString()
            methodIndex[op.method as String] = pascal
        }

        String version = readComponentVersion(componentRoot)
        return [
                openapi           : "3.1.0",
                info              : [
                        title      : "Darpan Facade API",
                        version    : version,
                        // Contract version is decoupled from the component release version: it bumps ONLY
                        // on a breaking facade change (removed method, removed/renamed param or out-field,
                        // type change). Additive changes (new methods, new optional in-params, new
                        // out-fields) do NOT bump it — that is the additive-only deprecation policy.
                        // scripts/check_contract_compat.py enforces this in CI.
                        "x-darpan-contract-version": CONTRACT_VERSION,
                        description: "GENERATED from the facade service XML — do not edit by hand. Regenerate with " +
                                "./gradlew :runtime:component:darpan:generateApiContract.\n\n" +
                                "Transport: JSON-RPC 2.0 over POST /rpc/json. Auth channels: browser SPA uses the " +
                                "HttpOnly darpan_login_key cookie + CSRF token (canonical); integration callers and " +
                                "no-credentialed-CORS fallbacks use the login_key header (in-memory only, never query " +
                                "params). HTTP status is 200 for every dispatched call; " +
                                "distinguish outcomes by shape. JSON-RPC error object: protocol failures (-32700 parse, " +
                                "-32600 invalid request, -32601 method not found, -32602 invalid params), unhandled " +
                                "service errors (code 500), authorization denial (code 403). Facade business validation " +
                                "instead returns a success envelope with result.ok=false and result.errors[].",
                ],
                servers           : [[url: "/", description: "Same-origin Moqui backend (api.drpn.ai in production)"]],
                paths             : [
                        "/rpc/json": [
                                post: [
                                        operationId: "jsonRpcCall",
                                        summary    : "Single JSON-RPC endpoint for all Darpan facade methods",
                                        requestBody: [
                                                required: true,
                                                content : ["application/json": [schema: [
                                                        oneOf        : requestRefs,
                                                        discriminator: [propertyName: "method", mapping: discriminatorMapping],
                                                ]]],
                                        ],
                                        responses  : [
                                                "200": [
                                                        description: "JSON-RPC response — success envelope, or protocol error object",
                                                        content    : ["application/json": [schema: [oneOf: responseRefs]]],
                                                ],
                                        ],
                                ],
                        ],
                ],
                components        : [schemas: schemas],
                "x-darpan-methods": methodIndex,
        ]
    }

    static List<Map> parseServiceFile(File xmlFile, String prefix) {
        def root = new XmlParser().parse(xmlFile)
        String fileBase = xmlFile.name.replace(".xml", "")
        List<Map> ops = []
        root.service.each { svc ->
            if (svc.@"allow-remote" != "true") return
            String verb = svc.@verb
            String noun = svc.@noun
            String method = "${prefix}.${fileBase}.${verb}#${noun}".toString()
            String pascal = "${pascalCase(verb)}${pascalCase(noun)}".toString()
            String description = svc.description ? svc.description[0].text().trim() : null

            Map paramsSchema = parametersToSchema(svc."in-parameters"[0], method, true)
            if (description) paramsSchema.description = description
            Map resultSchema = parametersToSchema(svc."out-parameters"[0], method, false)

            ops << [method: method, pascal: pascal, paramsSchema: paramsSchema, resultSchema: resultSchema]
        }
        return ops
    }

    static Map parametersToSchema(def parametersNode, String method, boolean trackRequired) {
        Map<String, Object> properties = new LinkedHashMap<>()
        List<String> required = []
        parametersNode?.parameter?.each { param ->
            properties[param.@name as String] = parameterToSchema(param, method)
            if (trackRequired && param.@required == "true") required << (param.@name as String)
        }
        Map schema = [type: "object", properties: properties]
        if (required) schema.required = required
        return schema
    }

    static Map parameterToSchema(def param, String method) {
        String name = param.@name
        String type = param.@type
        List children = param.parameter ?: []
        String description = param.description ? param.description[0].text().trim() : null

        Map schema
        if (children) {
            if (type == "List") {
                if (children.size() == 1 && !(children[0].parameter)) {
                    schema = [type: "array", items: scalarSchema(children[0].@type as String)]
                } else {
                    Map entryProps = new LinkedHashMap<>()
                    children.each { child -> entryProps[child.@name as String] = parameterToSchema(child, method) }
                    schema = [type: "array", items: [type: "object", properties: entryProps]]
                }
            } else if (type == "Map") {
                Map props = new LinkedHashMap<>()
                children.each { child -> props[child.@name as String] = parameterToSchema(child, method) }
                schema = [type: "object", properties: props]
            } else {
                // Fail closed: an untyped container is an ambiguous contract (Map vs List is
                // indistinguishable in Moqui XML) — annotate the service XML explicitly.
                throw new IllegalStateException("Parameter '${name}' of ${method} has child parameters but no " +
                        "explicit type=\"Map\" or type=\"List\" — annotate the facade XML so the contract is unambiguous.")
            }
        } else if (type == "List") {
            schema = [type: "array", items: [:]]
        } else if (type == "Map") {
            schema = [type: "object"]
        } else {
            schema = scalarSchema(type)
        }
        if (description) {
            schema = new LinkedHashMap(schema)
            schema.description = schema.description ? "${schema.description}. ${description}".toString() : description
        }
        return schema
    }

    static Map scalarSchema(String moquiType) {
        Map base = SCALAR_TYPES[moquiType ?: "String"]
        if (base == null) base = [type: "string", description: "Moqui type ${moquiType}".toString()]
        return new LinkedHashMap(base)
    }

    static Map jsonRpcErrorSchema() {
        // Codes verified against Moqui ServiceJsonRpcDispatcher.groovy: the -32xxx set is the
        // JSON-RPC 2.0 protocol level; 500/403 are emitted by the dispatcher's catch blocks
        // (service exception or ec.message errors -> 500, ArtifactAuthorizationException -> 403).
        return [
                type       : "object",
                description: "JSON-RPC error object as emitted by the Moqui dispatcher",
                properties : [
                        code   : [
                                type       : "integer",
                                enum       : [-32700, -32600, -32601, -32602, -32603, 500, 403],
                                description: "-32700 parse error; -32600 invalid request; -32601 method not found; " +
                                        "-32602 invalid params; -32603 internal error (defined, rarely emitted); " +
                                        "500 service error (exception or unhandled ec.message errors); " +
                                        "403 authorization denied (ArtifactAuthorizationException)",
                        ],
                        message: [type: "string", description: "Real error string from the backend"],
                        data   : [description: "Optional implementation-specific detail"],
                ],
                required   : ["code", "message"],
        ]
    }

    static String pascalCase(String value) {
        if (!value) return ""
        return value[0].toUpperCase() + value.substring(1)
    }

    static String readComponentVersion(File componentRoot) {
        File componentXml = new File(componentRoot, "component.xml")
        if (!componentXml.exists()) return "0.0.0"
        def root = new XmlParser().parse(componentXml)
        return (root.@version ?: "0.0.0") as String
    }
}
