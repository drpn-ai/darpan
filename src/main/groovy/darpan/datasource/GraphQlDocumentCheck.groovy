package darpan.datasource

import graphql.language.Argument
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.NonNullType
import graphql.language.NullValue
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.StringValue
import graphql.language.VariableDefinition
import graphql.language.VariableReference
import graphql.parser.Parser

/**
 * DAR-BE-176. What a data-defined source's GraphQL document may be, checked with graphql-java's own parser (the same
 * grammar the server uses, so the check and the server cannot read a document two ways): exactly one operation, a
 * query, no introspection, and the root fields it selects. Returns words, never throws on bad input. Pure: no Moqui.
 */
class GraphQlDocumentCheck {

    /** Introspection fields: a stored read has no reason to ask the server about its schema. __typename is allowed. */
    static final Set<String> REFUSED_FIELDS = ["__schema", "__type"].asImmutable() as Set<String>

    /**
     * [errors: [..], operationName, rootFields: [name or alias], variables: [name: [required: bool]]].
     * errors is empty when the document may be stored.
     */
    static Map check(String document) {
        return check(document, false)
    }

    /**
     * Same check; allowIntrospection true lets __schema and __type through (DAR-CLI-003: an agent exploring a tenant's
     * own GraphQL schema). Still exactly one query: a mutation or subscription is refused either way.
     */
    static Map check(String document, boolean allowIntrospection) {
        List<String> errors = []
        if (!document?.trim()) return [errors: ["The query is empty."]]
        Document doc
        try {
            doc = Parser.parse(document)
        } catch (Exception e) {
            return [errors: ["The query does not parse: ${firstLine(e.message)}".toString()]]
        }
        List<OperationDefinition> operations = doc.getDefinitionsOfType(OperationDefinition)
        Map<String, FragmentDefinition> fragments = doc.getDefinitionsOfType(FragmentDefinition).collectEntries { [(it.name): it] }
        int other = doc.definitions.size() - operations.size() - fragments.size()
        if (other > 0) errors << "The document may hold only one query and its fragments."
        if (operations.size() != 1) {
            errors << "The document must hold exactly one operation, found ${operations.size()}.".toString()
            return [errors: errors]
        }
        OperationDefinition op = operations[0]
        if (op.operation != OperationDefinition.Operation.QUERY) {
            errors << "Only a query may be stored, not a ${op.operation.name().toLowerCase()}.".toString()
        }
        Set<String> refused = [] as Set<String>
        collectFields(op.selectionSet, fragments, [] as Set<String>, refused)
        if (refused && !allowIntrospection) errors << "The query may not ask for ${refused.sort().join(', ')} (introspection).".toString()

        Map<String, Map> variables = (op.variableDefinitions ?: []).collectEntries { VariableDefinition v ->
            [(v.name): [required: (v.type instanceof NonNullType) && v.defaultValue == null]]
        }
        List<String> rootFields = (op.selectionSet?.selections ?: []).findAll { it instanceof Field }
                .collect { Field f -> f.alias ?: f.name }
        return [errors: errors, operationName: op.name, rootFields: rootFields, variables: variables]
    }

    /**
     * DAR-BE-260. What a document asks for, for a caller that must police it: operations (each one's type: query,
     * mutation, subscription) and fields — every field it selects, in every operation, as [path, arguments]. path is the
     * list of real field names from the operation's root (an alias is ignored, so it cannot hide a field); an inline
     * fragment or a spread fragment's type condition joins the path as "on:Type". arguments maps each argument name to
     * [kind: "string", value] for a string literal, [kind: "variable", name] for a $variable, [kind: "null"] or
     * [kind: "other"]. A list, not a map keyed by name, so two fields of one name (aliased, or in two operations) are
     * both reported. Plain lists and maps, so a component without graphql-java can use it. Never throws: errors is
     * non-empty when the document can't be read.
     */
    static Map fieldPaths(String document) {
        if (!document?.trim()) return [errors: ["The query is empty."], operations: [], fields: []]
        Document doc
        try {
            doc = Parser.parse(document)
        } catch (Exception e) {
            return [errors: ["The query does not parse: ${firstLine(e.message)}".toString()], operations: [], fields: []]
        }
        Map<String, FragmentDefinition> fragments = doc.getDefinitionsOfType(FragmentDefinition).collectEntries { [(it.name): it] }
        List<OperationDefinition> ops = doc.getDefinitionsOfType(OperationDefinition)
        List<Map> fields = []
        ops.each { OperationDefinition op -> walkPaths(op.selectionSet, fragments, [], [] as Set<String>, fields) }
        List<String> errors = []
        if (doc.definitions.size() != ops.size() + fragments.size()) errors << "The document holds something other than operations and fragments."
        return [errors: errors, operations: ops.collect { it.operation.name().toLowerCase() }, fields: fields]
    }

    private static Map<String, Map> argumentsOf(Field f) {
        return (f.arguments ?: []).collectEntries { Argument a ->
            def v = a.value
            [(a.name): v instanceof StringValue ? [kind: "string", value: ((StringValue) v).value] :
                    v instanceof VariableReference ? [kind: "variable", name: ((VariableReference) v).name] :
                            v instanceof NullValue ? [kind: "null"] : [kind: "other"]]
        } as Map<String, Map>
    }

    private static void walkPaths(SelectionSet set, Map<String, FragmentDefinition> fragments, List<String> prefix,
            Set<String> fragmentsOnPath, List<Map> fields) {
        for (Selection s : (set?.selections ?: [])) {
            if (s instanceof Field) {
                Field f = (Field) s
                List<String> path = prefix + [f.name]
                fields << [path: path, arguments: argumentsOf(f)]
                walkPaths(f.selectionSet, fragments, path, fragmentsOnPath, fields)
            } else if (s instanceof InlineFragment) {
                InlineFragment inline = (InlineFragment) s
                String type = inline.typeCondition?.name
                walkPaths(inline.selectionSet, fragments, type ? prefix + ["on:${type}".toString()] : prefix, fragmentsOnPath, fields)
            } else if (s instanceof FragmentSpread) {
                String name = ((FragmentSpread) s).name
                FragmentDefinition fragment = fragments.get(name)
                // Once per path, so a fragment that spreads itself cannot loop; a fragment reused elsewhere is walked again there.
                if (fragment == null || fragmentsOnPath.contains(name)) continue
                walkPaths(fragment.selectionSet, fragments, prefix + ["on:${fragment.typeCondition?.name}".toString()],
                        fragmentsOnPath + [name] as Set<String>, fields)
            }
        }
    }

    /** Walks every selection, through fragments (each once, so a cycle cannot loop), noting refused field names. */
    private static void collectFields(SelectionSet set, Map<String, FragmentDefinition> fragments, Set<String> seenFragments,
            Set<String> refused) {
        for (Selection s : (set?.selections ?: [])) {
            if (s instanceof Field) {
                Field f = (Field) s
                if (REFUSED_FIELDS.contains(f.name)) refused << f.name
                collectFields(f.selectionSet, fragments, seenFragments, refused)
            } else if (s instanceof InlineFragment) {
                collectFields(((InlineFragment) s).selectionSet, fragments, seenFragments, refused)
            } else if (s instanceof FragmentSpread) {
                String name = ((FragmentSpread) s).name
                if (seenFragments.add(name)) collectFields(fragments.get(name)?.selectionSet, fragments, seenFragments, refused)
            }
        }
    }

    private static String firstLine(String message) {
        return (message ?: "").readLines().find { it.trim() } ?: "unreadable"
    }
}
