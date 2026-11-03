package darpan.datasource

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.NonNullType
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.language.SelectionSet
import graphql.language.VariableDefinition
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
