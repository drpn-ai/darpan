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
 *           ids: {variables, from (LOOKUP_KEYS or <earlier step>.<field>), chunk, parallel, keyFields, idPattern, separator}},
 *    paging: {firstVariable, afterVariable, pageSize | {budget, costPerRecord}, maxPages}, reservation}
 * Window templates may use {from} {thru}; ids templates {ids} {keyField}. The other steps (DataSourceRunner):
 *   unwrap {of (an earlier step), field, path}: the field becomes the list at path inside it (a nested connection's
 *           edges[*].node)
 *   join   {into, from (earlier steps), on: [{into, from}], at: "list[*]", atOn: [{at, from}], many | copy {to: path} | as,
 *           missingAt: {create, set}}
 *   split  {from, path: "a[*].b[*]", levels: [root, a, b], copy {to: "level.field"}}
 *   repeat {times (path), parse STRICT_INT|NUMERIC, nullAs, index}
 *   number {field, partitionBy: [paths], orderBy: [paths]}
 *   count  {name, when (RecordConditions), distinct: [paths]}
 * derive and filter are checked by their own steps when they are built.
 */
class DataSourceDefinitionSupport {

    static final List<String> PROTOCOLS = ["GRAPHQL", "REST_JSON"].asImmutable()
    static final List<String> STATUSES = ["DRAFT", "ACTIVE", "RETIRED"].asImmutable()
    static final List<String> STEP_KINDS = ["query", "unwrap", "join", "split", "repeat", "number", "derive", "count", "filter"].asImmutable()
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
                    if (method == "HEADER" && !(k.header?.toString() ==~ /[A-Za-z0-9-]{1,64}/)) {
                        errors << "Auth kind '${value}' needs the header it sends.".toString()
                    }
                    if (fields.any { !(it ==~ NAME) }) errors << "Auth kind '${value}' names a field that is not a field name.".toString()
                    used.addAll(fields.findAll { it })
                }
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
            if (id ==~ NAME) earlier << id
        }
        return errors
    }

    private static List<String> checkQueryStep(String where, String protocol, Map s, Map limits, Set<String> earlier) {
        List<String> errors = []
        Map doc = [variables: [:], rootFields: []]
        if (protocol == "GRAPHQL") {
            doc = GraphQlDocumentCheck.check(s.document?.toString())
            ((List<String>) doc.errors).each { errors << "${where}: ${it}".toString() }
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
        if (!bind.window && !bind.ids) errors << "${where} needs bind.window or bind.ids: how a run's window or ids fill it.".toString()
        List<String> pagingVariables = [paging.firstVariable, paging.afterVariable].findAll { it != null }.collect { it.toString() }
        if (!(paging.firstVariable ==~ NAME) || !(paging.afterVariable ==~ NAME)) {
            errors << "${where} needs paging.firstVariable and paging.afterVariable.".toString()
        }
        boolean fixed = inRange(paging.pageSize, 1, 10_000)
        boolean budgeted = paging.pageSize instanceof Map && inRange(((Map) paging.pageSize).budget, 1, 1_000_000) &&
                isPositiveNumber(((Map) paging.pageSize).costPerRecord)
        if (!fixed && !budgeted) errors << "${where} needs paging.pageSize: a number, or {budget, costPerRecord}.".toString()
        if (paging.maxPages != null && !inRange(paging.maxPages, 1, 1_000_000)) errors << "${where}: paging.maxPages is above 0.".toString()
        if (!inRange(s.reservation, 1, 1_000_000)) errors << "${where} needs reservation, the cost held for one call.".toString()
        else if (inRange(limits.maxCostPerCall, 1, 1_000_000) && (s.reservation as long) > (limits.maxCostPerCall as long)) {
            errors << "${where}: its reservation ${s.reservation} is above maxCostPerCall ${limits.maxCostPerCall}.".toString()
        }
        Map<String, Map> declared = (Map<String, Map>) (doc.variables ?: [:])
        ["window", "ids"].each { String mode ->
            if (!bind[mode]) return
            if (!(bind[mode] instanceof Map)) { errors << "${where}: bind.${mode} must be an object.".toString(); return }
            Map b = (Map) bind[mode]
            Map vars = b.variables instanceof Map ? (Map) b.variables : [:]
            if (!vars) errors << "${where}: bind.${mode} needs variables.".toString()
            Set<String> allowed = mode == "window" ? WINDOW_PLACEHOLDERS : IDS_PLACEHOLDERS
            vars.each { Object name, Object template ->
                if (protocol == "GRAPHQL" && !declared.containsKey(name)) {
                    errors << "${where}: bind.${mode} fills \$${name}, which the query does not declare.".toString()
                }
                List<String> bad = placeholders(template?.toString()).findAll { !allowed.contains(it) }
                if (bad) errors << "${where}: bind.${mode} may use ${allowed.collect { '{' + it + '}' }.join(' ')}, not ${bad.collect { '{' + it + '}' }.join(' ')}.".toString()
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
            } else {
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
                if (b.keyFields != null && !((b.keyFields instanceof Map) && ((Map) b.keyFields).every { k, v -> k ==~ NAME && v ==~ NAME })) {
                    errors << "${where}: bind.ids.keyFields maps each key field a run may ask by to the field the query filters on.".toString()
                }
            }
            if (b.parallel != null && !inRange(b.parallel, 1, 8)) errors << "${where}: bind.${mode}.parallel is 1 to 8.".toString()
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
                if (!paths(s.partitionBy) || !paths(s.orderBy)) errors << "${where}: partitionBy and orderBy are lists of fields.".toString()
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
