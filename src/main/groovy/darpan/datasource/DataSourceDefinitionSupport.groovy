package darpan.datasource

import groovy.json.JsonSlurper

/**
 * DAR-BE-176. Reads and checks a DataSourceDefinition's document: {connection, limits, steps, record, counts}.
 * Generic by rule: every value that belongs to a system (URLs, field names, error codes, response paths, id syntax)
 * comes from the document; this class knows only protocols and the behaviours core implements.
 *
 * check() answers words, one per problem, so a person saving a definition sees all of them at once; parse() throws
 * with the same words joined. Pure: no Moqui, so it runs on load, on save and in tests alike.
 *
 * A query step:
 *   {id, kind: "query", document, connectionAt (the root field), recordsAt, hasNextAt, cursorAt (paths under it),
 *    bind: {window: {variables, field, sliceHours, instantFormat, parallel},
 *           ids: {variables, from (LOOKUP_KEYS or <earlier step>.<field>), chunk, parallel, keyFields, idPattern, separator},
 *           all: {variables}: every record, fixed variables (no placeholders); on a later step, read once per batch},
 *    paging: {firstVariable, afterVariable, pageSize | {budget, costPerRecord, max}, maxPages}, reservation,
 *    selections: [{at, when, text, cost, set}]}
 * Window templates may use {from} {thru}; ids templates {ids} {keyField}. A document may hold slots ({{name}}), each
 * filled by the selections `at` it whose `when` holds for {wanted: [the fields the run keeps or filters on]}, their text
 * joined by spaces; an `on` selection adds its cost to costPerRecord and its `set` values to every record the step reads.
 * A selection's text has balanced brackets and no " # $ or {{, and the document is checked with every selection on,
 * with none on, and with each alone, so no document a run can send is unchecked. The other steps (DataSourceRunner):
 *   unwrap {of (an earlier step), field, path}: the field becomes the list at path inside it (a nested connection's
 *           edges[*].node)
 *   join   {into, from (earlier steps), on: [{into, from}], at: "list[*]", atOn: [{at, from}], many | copy {to: path} | as,
 *           missingAt: {create, set}, fromWhen (only joined records matching it), last (the last match wins, not the first)}
 *   split  {from, path: "a[*].b[*]", levels: [root, a, b], copy {to: "level.field"}}
 *   repeat {times (path), parse STRICT_INT|NUMERIC, nullAs, index}
 *   number {field, partitionBy: [paths], orderBy: [paths], sizeField (the partition's size), collect {field, as} (its
 *           values of field, in order, as a list)}
 *   count  {name, when (RecordConditions), distinct: [paths]}
 *   derive {rules: [{set, when, value}], on (an earlier step; default the current records)} (DeriveRules)
 *   filter {overlay (a level whose plain fields sit under the record), fields (what rules may read)}
 *   drop   {name, when, on (an earlier step; default the current records), sidecar {suffix, fields {name: value}, max}}:
 *          records the source itself leaves out, counted under name, never in the run-rule sidecar; with a sidecar, each
 *          one's fields (DeriveRules values) are written beside the extract, up to max, saying when it stopped
 * record: {fields, optionalFields, keepRecordFields (true: a run's keepRecordFields trims each record, and a field the
 * record cannot carry is refused), keepWith {field: [fields kept with it]}}.
 * Top level: wholeSet (true: every step sees the whole read as one batch; the first query reads with bind.all only, and
 * more than limits.maxRecordsInMemory records fail the run), batchRecords, metadata (fixed values a run reports, e.g. a count this source never has), and warnings
 * ({count, text}: a warning a run gives when that count is above 0; {n} is the count).
 */
class DataSourceDefinitionSupport {

    static final List<String> PROTOCOLS = ["GRAPHQL", "REST_JSON"].asImmutable()
    static final List<String> STATUSES = ["DRAFT", "ACTIVE", "RETIRED"].asImmutable()
    static final List<String> STEP_KINDS = ["query", "unwrap", "join", "split", "repeat", "number", "derive", "count", "filter", "drop"].asImmutable()
    static final List<String> REPEAT_PARSES = ["STRICT_INT", "NUMERIC"].asImmutable()
    /** The sign-in methods core implements; which one a connection uses, and from which of its fields, is data. */
    static final List<String> AUTH_KINDS = ["NONE", "BASIC", "BEARER", "HEADER"].asImmutable()
    static final List<String> INSTANT_FORMATS = ["ISO_INSTANT", "EPOCH_MILLIS"].asImmutable()
    static final String LOOKUP_KEYS = "LOOKUP_KEYS"

    private static final String NAME = /[A-Za-z][A-Za-z0-9_]{0,59}/
    private static final String PATH = /[A-Za-z_][A-Za-z0-9_]*(\[\*\])?(\.[A-Za-z_][A-Za-z0-9_]*(\[\*\])?){0,7}/
    private static final java.util.regex.Pattern PLACEHOLDER = ~/\{([A-Za-z][A-Za-z0-9_]*)\}/
    private static final Set<String> WINDOW_PLACEHOLDERS = ["from", "thru"] as Set<String>
    private static final Set<String> IDS_PLACEHOLDERS = ["ids", "keyField"] as Set<String>
    static final java.util.regex.Pattern SLOT = ~/\{\{([A-Za-z][A-Za-z0-9_]*)\}\}/

    /** The definition as a Map, or IllegalArgumentException naming every problem. */
    static Map parse(String protocol, String json, Collection<String> connectionFields = null) {
        Map definition = null
        List<String> errors = []
        try {
            Object parsed = json?.trim() ? new JsonSlurper().parseText(json) : null
            if (parsed instanceof Map) definition = (Map) parsed
            else errors << "The definition must be a JSON object."
        } catch (Exception e) {
            errors << "The definition is not readable JSON: ${e.message?.readLines()?.find { it.trim() }}".toString()
        }
        if (definition != null) errors.addAll(check(protocol, definition, connectionFields))
        if (errors) throw new IllegalArgumentException(errors.join(" "))
        return definition
    }

    /**
     * Every problem with a definition, in words. connectionFields, when given, are the fields of the config record the
     * connector names: every connection template must use only those.
     */
    static List<String> check(String protocol, Map d, Collection<String> connectionFields = null) {
        List<String> errors = []
        if (!(protocol in PROTOCOLS)) errors << "The protocol is ${PROTOCOLS.join(' or ')}, not '${protocol}'.".toString()
        errors.addAll(checkConnection(d.connection, connectionFields))
        Map limits = checkLimits(d.limits, errors)
        errors.addAll(checkSteps(protocol, d.steps, limits))
        if (d.batchRecords != null && !inRange(d.batchRecords, 1, 100_000)) errors << "batchRecords is 1 to 100000."
        if (d.wholeSet != null && !(d.wholeSet instanceof Boolean)) errors << "wholeSet is true or false."
        if (d.wholeSet == true && d.steps instanceof List && ((List) d.steps)[0] instanceof Map) {
            Map bind = (((Map) ((List) d.steps)[0]).bind instanceof Map) ? (Map) ((Map) ((List) d.steps)[0]).bind : [:]
            if (!bind.all || bind.window || bind.ids) errors << "A wholeSet reads with bind.all only: no window, no ids."
        }
        if (d.warnings != null && !((d.warnings instanceof List) && ((List) d.warnings).every { Object w ->
            w instanceof Map && ((Map) w).count ==~ NAME && ((Map) w).text?.toString()?.trim() &&
                    ((List<Map>) (d.steps instanceof List ? d.steps : [])).any { it?.kind == "count" && it?.name == ((Map) w).count }
        })) {
            errors << "warnings is a list of {count, text}, each naming a count step; {n} in text is the count."
        }
        if (d.metadata != null && !((d.metadata instanceof Map) && ((Map) d.metadata).every { k, v -> k ==~ NAME && !(v instanceof Map) && !(v instanceof Collection) })) {
            errors << "metadata maps names to plain values."
        }
        errors.addAll(checkRecord(d.record))
        errors.addAll(checkCounts(d.counts))
        return errors
    }

    private static List<String> checkConnection(Object raw, Collection<String> connectionFields) {
        List<String> errors = []
        if (!(raw instanceof Map)) return ["The definition needs a connection: where to call and how to sign in."]
        Map c = (Map) raw
        String url = c.urlTemplate?.toString()?.trim()
        if (!url) errors << "The connection needs a urlTemplate."
        else if (!(url ==~ /(https:\/\/|\{[A-Za-z][A-Za-z0-9_]*\}).*/)) {
            errors << "The connection's urlTemplate starts with https:// or a connection field, e.g. {baseUrl}/graphql."
        }
        List<String> used = placeholders(url)
        Object auth = c.auth
        if (!(auth instanceof Map)) {
            errors << "The connection needs auth: the field that says how a connection signs in, and what each way reads."
        } else {
            Map a = (Map) auth
            if (a.kindField != null) {
                if (!(a.kindField ==~ NAME)) errors << "The auth kindField must be a field name."
                else used << (String) a.kindField
            }
            Object kinds = a.kinds
            if (!(kinds instanceof Map) || ((Map) kinds).isEmpty()) {
                errors << "The auth needs kinds: each value of the kind field mapped to one of ${AUTH_KINDS.join(', ')}."
            } else {
                ((Map) kinds).each { Object value, Object spec ->
                    if (!(spec instanceof Map)) { errors << "Auth kind '${value}' must be an object.".toString(); return }
                    Map k = (Map) spec
                    String method = k.method?.toString()
                    if (!(method in AUTH_KINDS)) {
                        errors << "Auth kind '${value}' uses ${AUTH_KINDS.join(', ')}, not '${method}'.".toString(); return
                    }
                    List<String> fields = []
                    if (method == "BASIC") fields = [k.userField, k.passwordField].collect { it?.toString() }
                    if (method in ["BEARER", "HEADER"]) fields = ((k.tokenFields instanceof List) ? (List) k.tokenFields : [null]).collect { it?.toString() }
                    if (method == "HEADER" && !(k.header?.toString() ==~ /[A-Za-z0-9_.-]{1,64}/)) {
                        errors << "Auth kind '${value}' needs the header it sends.".toString()
                    }
                    if (fields.any { !(it ==~ NAME) }) errors << "Auth kind '${value}' names a field that is not a field name.".toString()
                    used.addAll(fields.findAll { it })
                }
            }
            if (a.defaultKind != null && !((kinds instanceof Map) && ((Map) kinds).containsKey(a.defaultKind))) {
                errors << "The auth defaultKind must be one of its kinds.".toString()
            }
            if (a.headersJsonField != null) {
                if (!(a.headersJsonField ==~ NAME)) errors << "The auth headersJsonField must be a field name."
                else used << (String) a.headersJsonField
            }
        }
        if (c.timeoutSeconds != null && !inRange(c.timeoutSeconds, 1, 600)) errors << "The connection timeoutSeconds is 1 to 600."
        if (connectionFields != null) {
            Set<String> known = connectionFields as Set<String>
            List<String> unknown = used.findAll { !known.contains(it) }.unique()
            if (unknown) errors << "The connection names ${unknown.join(', ')}, which the connection record does not have.".toString()
        }
        return errors
    }

    private static Map checkLimits(Object raw, List<String> errors) {
        if (!(raw instanceof Map)) { errors << "The definition needs limits: at least maxCostPerCall."; return [:] }
        Map l = (Map) raw
        if (!inRange(l.maxCostPerCall, 1, 1_000_000)) errors << "The limits need maxCostPerCall, a whole number above 0."
        if (l.maxInFlight != null && !inRange(l.maxInFlight, 1, 16)) errors << "maxInFlight is 1 to 16."
        if (l.maxAttempts != null && !inRange(l.maxAttempts, 1, 10)) errors << "maxAttempts is 1 to 10."
        if (l.retryBackoffMillis != null && !inRange(l.retryBackoffMillis, 0, 60_000)) errors << "retryBackoffMillis is 0 to 60000."
        if (l.maxThrottleWaitSeconds != null && !inRange(l.maxThrottleWaitSeconds, 0, 3600)) errors << "maxThrottleWaitSeconds is 0 to 3600."
        if (l.maxRecordsInMemory != null && !inRange(l.maxRecordsInMemory, 1, 5_000_000)) errors << "maxRecordsInMemory is 1 to 5000000."
        ["errorCodeAt"].each { if (l[it] != null && !(l[it] ==~ PATH)) errors << "${it} must be a response path.".toString() }
        ["throttledCodes", "costRefusedCodes"].each { String k ->
            if (l[k] != null && !((l[k] instanceof List) && ((List) l[k]).every { it ==~ /[A-Za-z0-9_.-]{1,60}/ })) {
                errors << "${k} must be a list of error codes.".toString()
            }
        }
        if (l.retryStatuses != null && !((l.retryStatuses instanceof List) && ((List) l.retryStatuses).every { inRange(it, 400, 599) })) {
            errors << "retryStatuses must be a list of HTTP statuses 400 to 599."
        }
        if (l.costBucket != null) {
            Map b = l.costBucket instanceof Map ? (Map) l.costBucket : [:]
            if (!(b.at ==~ PATH) || !["available", "maximum", "restoreRate"].every { b[it] ==~ NAME }) {
                errors << "costBucket needs at (a response path) and the names of its available, maximum and restoreRate members."
            }
        }
        return l
    }

    private static List<String> checkSteps(String protocol, Object raw, Map limits) {
        if (!(raw instanceof List) || ((List) raw).isEmpty()) return ["The definition needs steps, the first a query."]
        List<String> errors = []
        Set<String> earlier = [] as Set<String>
        ((List) raw).eachWithIndex { Object o, int i ->
            String where = "Step ${i + 1}"
            if (!(o instanceof Map)) { errors << "${where} must be an object.".toString(); return }
            Map s = (Map) o
            String id = s.id?.toString()
            if (!(id ==~ NAME)) errors << "${where} needs an id, one word.".toString()
            else if (earlier.contains(id)) errors << "Two steps are named '${id}'.".toString()
            else where = "Step '${id}'"
            if (!(s.kind in STEP_KINDS)) errors << "${where} is one of ${STEP_KINDS.join(', ')}, not '${s.kind}'.".toString()
            if (i == 0 && s.kind != "query") errors << "The first step reads: it must be a query."
            if (s.kind == "query") errors.addAll(checkQueryStep(where, protocol, s, limits, earlier))
            else if (s.kind in ["unwrap", "join", "split", "repeat", "number", "count"]) errors.addAll(checkShapeStep(where, s, earlier))
            else if (s.kind == "derive") {
                errors.addAll(DeriveRules.check(where, s.rules))
                if (s.on != null && !earlier.contains(s.on)) errors << "${where}: on names an earlier step.".toString()
            } else if (s.kind == "drop") {
                errors.addAll(checkDropStep(where, s, earlier))
            } else if (s.kind == "filter") {
                if (s.overlay != null && !(s.overlay ==~ NAME)) errors << "${where}: overlay is a level name.".toString()
                if (!((s.fields instanceof List) && !((List) s.fields).isEmpty() && ((List) s.fields).every { it ==~ NAME })) {
                    errors << "${where}: fields lists the record fields exclusion rules may read.".toString()
                }
            }
            if (id ==~ NAME) earlier << id
        }
        return errors
    }

    private static List<String> checkQueryStep(String where, String protocol, Map s, Map limits, Set<String> earlier) {
        List<String> errors = []
        Map doc = [variables: [:], rootFields: []]
        List<String> documents = [s.document?.toString()]
        if (s.selections != null || slots(s.document?.toString())) {
            List<String> selectionErrors = checkSelections(where, s)
            errors.addAll(selectionErrors)
            documents = selectionErrors ? [] : selectionDocuments(s)
        }
        if (protocol == "GRAPHQL" && documents) {
            documents.each { String text ->
                Map checked = GraphQlDocumentCheck.check(text)
                ((List<String>) checked.errors).each { String e -> if (!errors.contains("${where}: ${e}".toString())) errors << "${where}: ${e}".toString() }
                if (doc.rootFields == []) doc = checked   // the first document is the one with every selection on
            }
            if (!(s.connectionAt ==~ NAME)) errors << "${where} needs connectionAt, the root field its records come from.".toString()
            else if (!((List) (doc.rootFields ?: [])).contains(s.connectionAt)) {
                errors << "${where}: the query does not select '${s.connectionAt}' at its root.".toString()
            }
        }
        ["recordsAt", "hasNextAt", "cursorAt"].each { String k ->
            if (!(s[k] ==~ PATH)) errors << "${where} needs ${k}, a path in the answer.".toString()
        }
        Map bind = s.bind instanceof Map ? (Map) s.bind : [:]
        Map paging = s.paging instanceof Map ? (Map) s.paging : [:]
        if (!bind.window && !bind.ids && !bind.all) errors << "${where} needs bind.window, bind.ids or bind.all: how a run fills it.".toString()
        List<String> pagingVariables = [paging.firstVariable, paging.afterVariable].findAll { it != null }.collect { it.toString() }
        if (!(paging.firstVariable ==~ NAME) || !(paging.afterVariable ==~ NAME)) {
            errors << "${where} needs paging.firstVariable and paging.afterVariable.".toString()
        }
        boolean fixed = inRange(paging.pageSize, 1, 10_000)
        boolean budgeted = paging.pageSize instanceof Map && inRange(((Map) paging.pageSize).budget, 1, 1_000_000) &&
                isPositiveNumber(((Map) paging.pageSize).costPerRecord)
        if (!fixed && !budgeted) errors << "${where} needs paging.pageSize: a number, or {budget, costPerRecord}.".toString()
        if (budgeted && ((Map) paging.pageSize).max != null && !inRange(((Map) paging.pageSize).max, 1, 10_000)) {
            errors << "${where}: paging.pageSize.max is 1 to 10000.".toString()
        }
        if (paging.maxPages != null && !inRange(paging.maxPages, 1, 1_000_000)) errors << "${where}: paging.maxPages is above 0.".toString()
        if (!inRange(s.reservation, 1, 1_000_000)) errors << "${where} needs reservation, the cost held for one call.".toString()
        else if (inRange(limits.maxCostPerCall, 1, 1_000_000) && (s.reservation as long) > (limits.maxCostPerCall as long)) {
            errors << "${where}: its reservation ${s.reservation} is above maxCostPerCall ${limits.maxCostPerCall}.".toString()
        }
        Map<String, Map> declared = (Map<String, Map>) (doc.variables ?: [:])
        ["window", "ids", "all"].each { String mode ->
            if (!bind[mode]) return
            if (!(bind[mode] instanceof Map)) { errors << "${where}: bind.${mode} must be an object.".toString(); return }
            Map b = (Map) bind[mode]
            Map vars = b.variables instanceof Map ? (Map) b.variables : [:]
            // bind.all may fill nothing: an optional variable left out is null, as a sweep of everything sends it.
            if (!vars && mode != "all") errors << "${where}: bind.${mode} needs variables.".toString()
            Set<String> allowed = mode == "window" ? WINDOW_PLACEHOLDERS : mode == "ids" ? IDS_PLACEHOLDERS : ([] as Set<String>)
            vars.each { Object name, Object template ->
                if (protocol == "GRAPHQL" && !declared.containsKey(name)) {
                    errors << "${where}: bind.${mode} fills \$${name}, which the query does not declare.".toString()
                }
                List<String> bad = placeholders(template?.toString()).findAll { !allowed.contains(it) }
                if (bad && mode == "all") errors << "${where}: bind.all takes fixed variables, not ${bad.collect { '{' + it + '}' }.join(' ')}.".toString()
                else if (bad) errors << "${where}: bind.${mode} may use ${allowed.collect { '{' + it + '}' }.join(' ')}, not ${bad.collect { '{' + it + '}' }.join(' ')}.".toString()
            }
            if (protocol == "GRAPHQL") {
                Set<String> filled = (vars.keySet().collect { it.toString() } + pagingVariables) as Set<String>
                List<String> unfilled = declared.findAll { k, v -> v.required && !filled.contains(k) }.keySet().sort()
                if (unfilled) errors << "${where}: in ${mode} mode nothing fills the required \$${unfilled.join(', $')}.".toString()
            }
            if (mode == "window") {
                if (!(b.field ==~ NAME)) errors << "${where}: bind.window needs field, the record field the window is on.".toString()
                if (!inRange(b.sliceHours, 1, 24 * 31)) errors << "${where}: bind.window.sliceHours is 1 to 744.".toString()
                if (b.instantFormat != null && !(b.instantFormat in INSTANT_FORMATS)) {
                    errors << "${where}: bind.window.instantFormat is ${INSTANT_FORMATS.join(' or ')}.".toString()
                }
            } else if (mode == "ids") {
                String from = b.from?.toString()
                if (from != LOOKUP_KEYS) {
                    List<String> parts = from?.split(/\./, 2) as List<String>
                    if (parts?.size() != 2 || !earlier.contains(parts[0]) || !(parts[1] ==~ PATH)) {
                        errors << "${where}: bind.ids.from is ${LOOKUP_KEYS} or <an earlier step>.<field>, not '${from}'.".toString()
                    }
                }
                if (!inRange(b.chunk, 1, 1000)) errors << "${where}: bind.ids.chunk is 1 to 1000.".toString()
                if (b.idPattern != null) {
                    try { java.util.regex.Pattern.compile(b.idPattern.toString()) }
                    catch (Exception ignored) { errors << "${where}: bind.ids.idPattern is not a pattern.".toString() }
                }
                if (b.defaultKeyField != null && !(b.defaultKeyField ==~ NAME)) errors << "${where}: bind.ids.defaultKeyField is a field name.".toString()
                if (b.keyFields != null && !((b.keyFields instanceof Map) && ((Map) b.keyFields).every { k, v -> k ==~ NAME && v ==~ NAME })) {
                    errors << "${where}: bind.ids.keyFields maps each key field a run may ask by to the field the query filters on.".toString()
                }
            }
            if (b.parallel != null && !inRange(b.parallel, 1, 8)) errors << "${where}: bind.${mode}.parallel is 1 to 8.".toString()
        }
        return errors
    }

    /** The slots ({{name}}) a document holds, in order. */
    static List<String> slots(String document) {
        if (!document) return []
        List<String> out = []
        def m = SLOT.matcher(document)
        while (m.find()) out << m.group(1)
        return out
    }

    /** The document with the given selections on: each slot their texts joined by spaces, empty when none is on. */
    static String fillSlots(String document, List<Map> on) {
        return SLOT.matcher(document).replaceAll({ java.util.regex.MatchResult m ->
            java.util.regex.Matcher.quoteReplacement(on.findAll { it.at == m.group(1) }.collect { it.text.toString() }.join(" "))
        } as java.util.function.Function<java.util.regex.MatchResult, String>)
    }

    /** Every document the checks read: all selections on (first), none on, and each alone. */
    static List<String> selectionDocuments(Map s) {
        String document = s.document.toString()
        List<Map> all = (List<Map>) (s.selections ?: [])
        return ([fillSlots(document, all), fillSlots(document, [])] + all.collect { fillSlots(document, [it]) }).unique()
    }

    private static List<String> checkSelections(String where, Map s) {
        List<String> errors = []
        List<String> used = slots(s.document?.toString())
        if (!(s.selections instanceof List)) return ["${where}: selections is a list of {at, when, text, cost, set}.".toString()]
        List<Map> selections = []
        ((List) s.selections).eachWithIndex { Object o, int i ->
            String at = "${where}, selection ${i + 1}"
            if (!(o instanceof Map)) { errors << "${at} must be an object.".toString(); return }
            Map sel = (Map) o
            selections << sel
            if (!(sel.at ==~ NAME)) errors << "${at} needs at, the slot it fills.".toString()
            else if (!used.contains(sel.at)) errors << "${at}: the document has no {{${sel.at}}}.".toString()
            String text = sel.text?.toString()
            if (!text?.trim() || text.length() > 2000) errors << "${at} needs text, up to 2000 characters.".toString()
            else {
                if (text.contains('"') || text.contains("#") || text.contains('$') || text.contains("{{") || text.contains("}}")) {
                    errors << "${at}: text holds no \" # \$ {{ or }}.".toString()
                }
                if (!balanced(text)) errors << "${at}: text has balanced brackets: it stays inside its selection.".toString()
            }
            errors.addAll(RecordConditions.check(at, sel.when))
            if (sel.cost != null && !(sel.cost instanceof Number && ((Number) sel.cost).doubleValue() >= 0d)) {
                errors << "${at}: cost is a number from 0.".toString()
            }
            if (sel.set != null && !((sel.set instanceof Map) && ((Map) sel.set).every { k, v -> k ==~ NAME && !(v instanceof Map) && !(v instanceof Collection) })) {
                errors << "${at}: set maps field names to plain values.".toString()
            }
        }
        used.unique().each { String slot ->
            if (!selections.any { it.at == slot }) errors << "${where}: {{${slot}}} has no selection.".toString()
        }
        return errors
    }

    private static boolean balanced(String text) {
        Map<Character, Character> closes = [(')' as char): ('(' as char), ('}' as char): ('{' as char), (']' as char): ('[' as char)]
        Deque<Character> open = new ArrayDeque<>()
        for (char c : text.toCharArray()) {
            if (c in ['(' as char, '{' as char, '[' as char]) open.push(c)
            else if (closes.containsKey(c)) { if (open.isEmpty() || open.pop() != closes.get(c)) return false }
        }
        return open.isEmpty()
    }

    private static List<String> checkDropStep(String where, Map s, Set<String> earlier) {
        List<String> errors = []
        if (!(s.name ==~ NAME)) errors << "${where}: a drop needs a name, one word: the count of what it left out.".toString()
        if (s.when == null) errors << "${where}: a drop needs when, the records it leaves out.".toString()
        else errors.addAll(RecordConditions.check(where, s.when))
        if (s.on != null && !earlier.contains(s.on)) errors << "${where}: on names an earlier step.".toString()
        if (s.sidecar != null) {
            Map sc = s.sidecar instanceof Map ? (Map) s.sidecar : [:]
            if (!(sc.suffix ==~ /[a-z][a-z0-9-]{0,39}/)) errors << "${where}: the sidecar suffix is lower-case words joined by -.".toString()
            if (!inRange(sc.max, 1, 10_000)) errors << "${where}: the sidecar max is 1 to 10000.".toString()
            if (!((sc.fields instanceof Map) && !((Map) sc.fields).isEmpty() && ((Map) sc.fields).keySet().every { it ==~ NAME })) {
                errors << "${where}: the sidecar fields map names to values.".toString()
            } else {
                ((Map) sc.fields).each { k, v -> errors.addAll(DeriveRules.checkValueSpec("${where}, sidecar ${k}".toString(), v)) }
            }
        }
        return errors
    }

    private static List<String> checkShapeStep(String where, Map s, Set<String> earlier) {
        List<String> errors = []
        Closure<Boolean> isPath = { Object p -> p ==~ PATH }
        Closure<Boolean> paths = { Object l -> l == null || ((l instanceof List) && ((List) l).every { isPath(it) }) }
        switch (s.kind) {
            case "unwrap":
                if (!earlier.contains(s.of)) errors << "${where}: of names an earlier step.".toString()
                if (!(s.field ==~ NAME)) errors << "${where}: field is the record field to unwrap.".toString()
                if (!isPath(s.path)) errors << "${where}: path is where the list sits inside the field, e.g. edges[*].node.".toString()
                break
            case "join":
                ["into", "from"].each { String k ->
                    if (!earlier.contains(s[k])) errors << "${where}: ${k} names an earlier step.".toString()
                }
                Closure<Boolean> pairs = { Object l, String a, String b ->
                    l == null || ((l instanceof List) && ((List) l).every { it instanceof Map && isPath(((Map) it)[a]) && isPath(((Map) it)[b]) })
                }
                if (!pairs(s.on, "into", "from")) errors << "${where}: on is a list of {into, from} fields.".toString()
                if (!pairs(s.atOn, "at", "from")) errors << "${where}: atOn is a list of {at, from} fields.".toString()
                if (s.at != null && !(s.at ==~ /[A-Za-z_][A-Za-z0-9_]*\[\*\]/)) errors << "${where}: at is one list on the record, e.g. groups[*].".toString()
                if ((s.atOn || s.missingAt) && s.at == null) errors << "${where}: atOn and missingAt need at.".toString()
                errors.addAll(RecordConditions.check(where, s.fromWhen))
                if (s.last != null && !(s.last instanceof Boolean)) errors << "${where}: last is true or false.".toString()
                if (!s.on && !s.atOn) errors << "${where}: a join needs on or atOn keys.".toString()
                boolean many = s.many == true
                if (many && s.copy != null) errors << "${where}: a many join attaches a list; it cannot copy fields.".toString()
                if (s.copy != null && !((s.copy instanceof Map) && ((Map) s.copy).every { k, v -> k ==~ NAME && isPath(v) })) {
                    errors << "${where}: copy maps a field name to a path in the joined record.".toString()
                }
                if (s.copy == null && !(s.as ==~ NAME)) errors << "${where}: a join without copy needs as, the field it attaches under.".toString()
                if (s.missingAt != null && !((s.missingAt instanceof Map) && (((Map) s.missingAt).set == null || ((Map) s.missingAt).set instanceof Map))) {
                    errors << "${where}: missingAt is {create, set}.".toString()
                }
                break
            case "split":
                if (s.from != null && !earlier.contains(s.from)) errors << "${where}: from names an earlier step.".toString()
                List<String> segments = (s.path instanceof String) ? ((String) s.path).split(/\./) as List<String> : []
                if (!segments || !segments.every { it ==~ /[A-Za-z_][A-Za-z0-9_]*\[\*\]/ }) {
                    errors << "${where}: path is lists to step into, e.g. groups[*].items[*].".toString()
                }
                List levels = s.levels instanceof List ? (List) s.levels : []
                if (levels.size() != segments.size() + 1 || !levels.every { it ==~ NAME } || levels.unique(false).size() != levels.size()) {
                    errors << "${where}: levels names the record and each list on the path, one word each.".toString()
                }
                if (s.copy != null && !((s.copy instanceof Map) && ((Map) s.copy).every { k, v ->
                    k ==~ NAME && isPath(v) && levels.contains(v.toString().split(/\./)[0]) && !levels.contains(k) })) {
                    errors << "${where}: copy maps a field name to <level>.<field>.".toString()
                }
                break
            case "repeat":
                if (!isPath(s.times)) errors << "${where}: times is the quantity field.".toString()
                if (!(s.parse in REPEAT_PARSES)) errors << "${where}: parse is ${REPEAT_PARSES.join(' or ')}.".toString()
                if (s.nullAs != null && !inRange(s.nullAs, 0, 10_000)) errors << "${where}: nullAs is a whole number from 0.".toString()
                if (s.index != null && !(s.index ==~ NAME)) errors << "${where}: index is a field name.".toString()
                break
            case "number":
                if (!(s.field ==~ NAME)) errors << "${where}: field is the name the number is written to.".toString()
                boolean orderOk = s.orderBy == null || ((s.orderBy instanceof List) && ((List) s.orderBy).every { Object o ->
                    isPath(o) || (o instanceof Map && isPath(((Map) o).path) && (((Map) o).as == null || ((Map) o).as in ["TEXT", "NATURAL"]))
                })
                if (!paths(s.partitionBy) || !orderOk) errors << "${where}: partitionBy and orderBy are lists of fields (orderBy may be {path, as: TEXT}).".toString()
                if (s.sizeField != null && !(s.sizeField ==~ NAME)) errors << "${where}: sizeField is a field name.".toString()
                if (s.collect != null && !((s.collect instanceof Map) && isPath(((Map) s.collect).field) && ((Map) s.collect).as ==~ NAME)) {
                    errors << "${where}: collect is {field, as}: the field gathered and the name the list is written to.".toString()
                }
                break
            case "count":
                if (!(s.name ==~ NAME)) errors << "${where}: a count needs a name, one word.".toString()
                errors.addAll(RecordConditions.check(where, s.when))
                if (!paths(s.distinct)) errors << "${where}: distinct is a list of fields.".toString()
                break
        }
        return errors
    }

    private static List<String> checkRecord(Object raw) {
        if (!(raw instanceof Map)) return ["The definition needs record: the fields each record carries."]
        Map r = (Map) raw
        List<String> errors = []
        List fields = r.fields instanceof List ? (List) r.fields : null
        List optional = r.optionalFields instanceof List ? (List) r.optionalFields : []
        if (!fields) errors << "record.fields lists the fields every record carries."
        List all = (fields ?: []) + optional
        if (all.any { !(it ==~ NAME) }) errors << "record.fields and record.optionalFields hold field names only."
        List dupes = all.countBy { it }.findAll { k, v -> v > 1 }.keySet() as List
        if (dupes) errors << "record lists ${dupes.join(', ')} twice.".toString()
        if (r.keepRecordFields != null && !(r.keepRecordFields instanceof Boolean)) errors << "record.keepRecordFields is true or false."
        if (r.keepWith != null && !((r.keepWith instanceof Map) && ((Map) r.keepWith).every { k, v ->
            all.contains(k) && (v instanceof List) && ((List) v).every { all.contains(it) } })) {
            errors << "record.keepWith maps a record field to the record fields kept with it."
        }
        return errors
    }

    private static List<String> checkCounts(Object raw) {
        if (raw == null) return []
        if (!(raw instanceof List)) return ["counts must be a list of {name, when}."]
        List<String> errors = []
        ((List) raw).each { Object o ->
            if (!(o instanceof Map) || !(((Map) o).name ==~ NAME)) errors << "Each count needs a name, one word."
        }
        return errors
    }

    static List<String> placeholders(String template) {
        if (!template) return []
        List<String> out = []
        def m = PLACEHOLDER.matcher(template)
        while (m.find()) out << m.group(1)
        return out
    }

    private static boolean inRange(Object v, long min, long max) {
        if (!(v instanceof Number)) return false
        Number n = (Number) v
        return n.doubleValue() == Math.floor(n.doubleValue()) && n.longValue() >= min && n.longValue() <= max
    }

    private static boolean isPositiveNumber(Object v) {
        return v instanceof Number && ((Number) v).doubleValue() > 0d
    }
}
