package darpan.datasource

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAR-BE-176. Runs a data-defined source's steps and hands its records to a sink, batch by batch. Generic: every
 * system detail is in the definition (DataSourceDefinitionSupport); the transport is anything with
 * `Map execute(String document, Map variables, int reservation)` answering a query's data (GraphQlTransport, or a test's).
 *
 * The first step is a query read by the run's window or its lookup keys. Its records are taken in batches of
 * definition.batchRecords (default 1000); every later step runs on one batch at a time, so one batch's children are
 * fetched together and nothing holds a whole month in memory:
 *   query   a second read, by ids taken from an earlier step's records (`<step>.<path>`)
 *   unwrap  a field becomes the list at a path inside it (a nested connection's edges[*].node)
 *   join    attach one step's records to another's, at their own level or under a nested list (`at`), as a list (many)
 *           or by copying fields; a missing nested target can be created (missingAt)
 *   split   one record per leaf of a nested path, each holding every level by name (order, group, item)
 *   repeat  one record per unit of a quantity field
 *   number  a running number per partition in a declared order, optionally the partition's size and its collected values
 *   count   a named count of the records matching conditions, optionally of distinct keys
 *   derive  fields written by rows of data, fired as Drools rules (DeriveRules, DeriveEngine), on the current records or on an
 *           earlier step's (`on`)
 *   filter  the run's exclusion rules (SourceFilterSupport) on each finished record, with a level's plain fields laid under
 *           it (`overlay`); only the declared `fields` may be filtered on, and excluded records go to the sidecar collector
 *   drop    records the source itself leaves out (a definition condition), removed from their step in place so later reads
 *           never fetch their children; counted, and optionally kept as sidecar entries
 *
 * A query's selections are chosen once per run from the fields it keeps or filters on (request.keep + the rules' fields).
 * Records then carry record.fields (always, null when unset) and record.optionalFields (only when set), trimmed to the
 * run's keep fields (and their keepWith companions) when record.keepRecordFields is true.
 */
class DataSourceRunner {

    static final int DEFAULT_BATCH_RECORDS = 1000
    static final int DEFAULT_MAX_RECORDS_IN_MEMORY = 1_000_000
    private int heldCap = 0

    private final Map definition
    private final def transport
    private final Map<String, Map> stepsById
    // Shared by the parallel readers of the first step (bind.<mode>.parallel): each piece is safe to touch from many threads.
    private final Map<String, AtomicInteger> pageSizes = new java.util.concurrent.ConcurrentHashMap<>()
    private final Map<String, DeriveEngine> engines = new java.util.concurrent.ConcurrentHashMap<>()
    final Map<String, Long> counts = java.util.Collections.synchronizedMap(new LinkedHashMap<String, Long>())
    final Map<String, Integer> pagesByStep = java.util.Collections.synchronizedMap(new LinkedHashMap<String, Integer>())
    private final AtomicInteger rootRecordCount = new AtomicInteger()
    private final AtomicInteger recordCount = new AtomicInteger()
    private final AtomicInteger batchCount = new AtomicInteger()
    /** Filter results: excluded records (capped, ExcludedRecordsSidecar) and counts per rule sequence. */
    final Map excludedCollector = darpan.reconciliation.conclusion.ExcludedRecordsSidecar.newCollector()
    final Map<Integer, Integer> excludedByRule = java.util.Collections.synchronizedMap(new LinkedHashMap<Integer, Integer>())
    final Map<Integer, Integer> fieldAbsentByRule = java.util.Collections.synchronizedMap(new LinkedHashMap<Integer, Integer>())
    private List<Map<String, Object>> filterRules = []
    /** Per query step, this run's document, cost per record and fixed values (its selections). */
    private final Map<String, Map> plans = [:]
    /** Per drop sidecar suffix: [suffix, entries, truncated]. */
    private final Map<String, Map> sidecars = java.util.Collections.synchronizedMap(new LinkedHashMap<String, Map>())
    private Set<String> keepFields = null
    /** This run's options: the text each option's value fills its {name} with, and the fields put on every first-step record. */
    private Map<String, String> optionTexts = [:]
    private Map<String, Object> optionFields = [:]

    DataSourceRunner(Map definition, def transport) {
        this.definition = definition
        this.transport = transport
        this.stepsById = ((List<Map>) definition.steps).collectEntries { [(it.id): it] }
        ((List<Map>) definition.steps).findAll { it.kind in ["count", "drop"] }.each { counts.put((String) it.name, 0L) }
    }

    /**
     * request: [mode: "ids", keys: [..], keyField] or [mode: "window", from: epoch millis, thru: epoch millis (excluded)].
     * sink: called with each batch's finished records. progress (optional): called with the root records read so
     * far, always on the calling thread (a Moqui context is bound to its thread), at most every PROGRESS_EVERY_MS
     * while parallel readers run. Answers what the run did.
     */
    Map run(Map request, Closure sink, Closure progress = null) {
        Map first = ((List<Map>) definition.steps)[0]
        filterRules = (List<Map<String, Object>>) (request.filters ?: [])
        checkFilterFields()
        keepFields = keepFieldsOf(request.keep)
        resolveOptions((Map) request.options)
        List<String> wanted = (((request.keep ?: []) as List) + filterRules.collect { it.fieldExpression })
                .collect { it?.toString()?.trim() }.findAll { it }.unique()
        ((List<Map>) definition.steps).findAll { it.kind == "query" }.each { plans.put((String) it.id, planOf(it, wanted)) }
        // A whole-set read is one batch: every step sees every record (a catalogue's ranks and groups), held up to the cap.
        int batchSize = definition.wholeSet == true ? Integer.MAX_VALUE : ((definition.batchRecords ?: DEFAULT_BATCH_RECORDS) as Number).intValue()
        heldCap = definition.wholeSet == true ? (((Map) definition.limits)?.maxRecordsInMemory ?: DEFAULT_MAX_RECORDS_IN_MEMORY) as int : 0
        List<Map> pieces
        int parallel
        if (request.mode == "ids") { pieces = keyPieces(first, request); parallel = parallelOf((Map) ((Map) first.bind).ids) }
        else if (request.mode == "window") { pieces = windowPieces(first, request); parallel = parallelOf((Map) ((Map) first.bind).window) }
        else if (request.mode == "all") { pieces = allPieces(first); parallel = 1 }
        else throw new IllegalArgumentException("A run reads by ids, by window or all, not '${request.mode}'.")
        Closure safeSink = { List<Map> batch -> synchronized (sink) { sink.call(batch) } }
        try {
            readPieces(first, pieces, Math.max(1, Math.min(parallel, pieces.size())), batchSize, safeSink, progress)
        } finally {
            engines.values().each { it.close() }
            engines.clear()
        }
        return [rootRecords: rootRecordCount.get(), records: recordCount.get(), batches: batchCount.get(),
                counts: new LinkedHashMap(counts), pages: new LinkedHashMap(pagesByStep),
                excludedByRule: new LinkedHashMap(excludedByRule), fieldAbsentByRule: new LinkedHashMap(fieldAbsentByRule),
                metadata: new LinkedHashMap((Map) (definition.metadata ?: [:])),
                sidecars: sidecars.values().collect { Map sc -> [suffix: sc.suffix, entries: new ArrayList((List) sc.entries), truncated: sc.truncated] },
                pageSizes: pageSizes.collectEntries { k, v -> [(k): v.get()] }]
    }

    int getRootRecords() { return rootRecordCount.get() }

    int getRecords() { return recordCount.get() }

    int getBatches() { return batchCount.get() }

    /**
     * Reads the first step's pieces (window slices or key chunks) on `parallel` threads. Each thread keeps its own batch
     * and finishes it (the later steps) as soon as it fills, so batches of different pieces run side by side. The first
     * failure stops every thread before its next piece and is thrown; nothing after it is written.
     */
    static final long PROGRESS_EVERY_MS = 2000L

    private void readPieces(Map first, List<Map> pieces, int parallel, int batchSize, Closure sink, Closure progress) {
        java.util.concurrent.ConcurrentLinkedQueue<Map> queue = new java.util.concurrent.ConcurrentLinkedQueue<>(pieces)
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>()
        Closure worker = { ->
            List<Map> buffer = []
            Closure take = { List<Map> page ->
                buffer.addAll(page)
                rootRecordCount.addAndGet(page.size())
                if (heldCap > 0 && buffer.size() > heldCap) {
                    throw new DataSourceCallException("TOO_MANY_RECORDS", "Step '${first.id}' read more than ${heldCap} records, the most a whole-set read holds.".toString())
                }
                if (parallel == 1 && progress != null) progress.call(rootRecordCount.get())
                while (buffer.size() >= batchSize) {
                    List<Map> batch = new ArrayList<>(buffer.subList(0, batchSize))
                    buffer.subList(0, batchSize).clear()
                    sink.call(finish(first, batch))
                }
            }
            try {
                Map piece
                while (failure.get() == null && (piece = queue.poll()) != null) readPages(first, piece, take)
                if (failure.get() == null && buffer) sink.call(finish(first, buffer))
            } catch (Throwable t) {
                failure.compareAndSet(null, t)
            }
        }
        if (parallel == 1) {
            worker.call()
        } else {
            List<Thread> threads = (1..parallel).collect { int n ->
                Thread t = new Thread(worker as Runnable, "data-source-read-${n}")
                t.daemon = true
                t.start()
                t
            }
            try {
                while (threads.any { it.alive }) {
                    threads.find { it.alive }?.join(PROGRESS_EVERY_MS)
                    if (progress != null) progress.call(rootRecordCount.get())
                }
            } catch (Throwable waiting) {
                // The waiting thread was interrupted or failed: stop the readers instead of leaving them writing.
                failure.compareAndSet(null, waiting)
                threads.each { it.interrupt() }
                if (waiting instanceof InterruptedException) Thread.currentThread().interrupt()
            }
        }
        Throwable t = failure.get()
        if (t != null) throw stopped(t)
    }

    /**
     * A reader that stopped for any reason ends the run with a stated reason: an Error, an interrupt or a failure with no
     * message becomes a READ_STOPPED call failure that names what happened. Other failures pass through unchanged.
     */
    static RuntimeException stopped(Throwable t) {
        if (t instanceof RuntimeException && t.message) return (RuntimeException) t
        String what = t instanceof InterruptedException ? "The read was interrupted."
                : t instanceof Error ? "The read stopped on ${t.getClass().simpleName}${t.message ? ': ' + t.message : ''}.".toString()
                : "The read stopped on ${t.getClass().simpleName}.".toString()
        DataSourceCallException e = new DataSourceCallException("READ_STOPPED", what)
        e.initCause(t)
        return e
    }

    private static int parallelOf(Map bind) {
        return ((bind?.parallel ?: 1) as Number).intValue()
    }

    // ---------------------------------------------------------------- reading

    /** The first step's key chunks, each its query variables. */
    private List<Map> keyPieces(Map step, Map request) {
        Map b = (Map) ((Map) step.bind).ids
        if (b == null) throw new IllegalArgumentException("Step '${step.id}' cannot be read by ids.")
        Map keyFields = (Map) (b.keyFields ?: [:])
        String asked = request.keyField?.toString() ?: b.defaultKeyField?.toString()
        String filterField = asked == null ? null : (keyFields ? (String) keyFields.get(asked) : asked)
        if (keyFields && filterField == null) {
            throw new IllegalArgumentException("Step '${step.id}' is asked by ${keyFields.keySet().join(', ')}, not '${asked}'.")
        }
        List<String> keys = cleanIds((List) request.keys)
        java.util.regex.Pattern pattern = b.idPattern ? java.util.regex.Pattern.compile((String) b.idPattern) : null
        List<String> bad = pattern ? keys.findAll { !pattern.matcher(it).matches() } : []
        if (bad) throw new IllegalArgumentException("Step '${step.id}' refuses the key(s) ${bad.take(5).join(', ')}.")
        return keys.collate(((b.chunk ?: 100) as Number).intValue()).collect { List<String> chunk ->
            fill((Map) b.variables, [ids: chunk.join((b.separator ?: ",") as String), keyField: filterField ?: ""] + optionTexts)
        }
    }

    /** A read of every record: one piece, the step's fixed variables. */
    private List<Map> allPieces(Map step) {
        Map b = (Map) ((Map) step.bind).all
        if (b == null) throw new IllegalArgumentException("Step '${step.id}' cannot be read whole.")
        return [fill((Map) b.variables, optionTexts)]
    }

    /** The first step's window slices, each its query variables. */
    private List<Map> windowPieces(Map step, Map request) {
        Map b = (Map) ((Map) step.bind).window
        if (b == null) throw new IllegalArgumentException("Step '${step.id}' cannot be read by window.")
        long from = (request.from as Number).longValue(), thru = (request.thru as Number).longValue()
        long slice = ((b.sliceHours as Number).longValue()) * 3_600_000L
        List<Map> pieces = []
        for (long start = from; start < thru; start += slice) {
            long end = Math.min(thru, start + slice)
            pieces << fill((Map) b.variables, [from: instant(start, (String) b.instantFormat), thru: instant(end, (String) b.instantFormat)] + optionTexts)
        }
        return pieces
    }

    /** A query step's pages for one set of variables, each page's records to `take`. */
    private void readPages(Map step, Map vars, Closure take) {
        Map paging = (Map) step.paging
        Map plan = plans.get((String) step.id) ?: [document: step.document, extraCost: 0d, set: [:]]
        Map set = (Map) plan.set
        AtomicInteger size = pageSizes.computeIfAbsent((String) step.id, { new AtomicInteger(initialPageSize(paging, (double) plan.extraCost)) })
        int maxPages = ((paging.maxPages ?: 20_000) as Number).intValue()
        String cursor = null
        Set<String> seen = [] as Set<String>
        int pages = 0
        while (true) {
            Map v = new LinkedHashMap(vars)
            int asked = size.get()
            v.put(paging.firstVariable, asked)
            if (cursor != null) v.put(paging.afterVariable, cursor)
            Map data
            try {
                data = (Map) transport.execute((String) plan.document, v, (step.reservation as Number).intValue())
            } catch (DataSourceCallException e) {
                // Judged by the size THIS call asked: parallel readers share the size, and another may already have halved
                // it, so a refusal at 3 arriving after the size reached 1 is retried at 1, not taken as "even 1 is refused".
                if (!e.costRefused || asked <= 1) throw e
                size.updateAndGet({ int current -> Math.min(current, Math.max(1, (int) (asked / 2))) } as java.util.function.IntUnaryOperator)
                continue
            }
            Object connection = data?.get(step.connectionAt)
            if (!(connection instanceof Map)) {
                throw new DataSourceCallException("NO_CONNECTION", "Step '${step.id}': the answer has no ${step.connectionAt}.".toString())
            }
            synchronized (pagesByStep) { pagesByStep.put((String) step.id, (pagesByStep.get(step.id) ?: 0) + 1) }
            List<Map> page = JsonPathValues.values(connection, (String) step.recordsAt).findAll { it instanceof Map } as List<Map>
            if (set) page.each { Map r -> r.putAll(set) }
            take.call(page)
            if (++pages >= maxPages) throw new DataSourceCallException("TOO_MANY_PAGES", "Step '${step.id}' passed ${maxPages} pages.".toString())
            if (JsonPathValues.value(connection, (String) step.hasNextAt) != true) return
            cursor = JsonPathValues.value(connection, (String) step.cursorAt)?.toString()
            if (!cursor || !seen.add(cursor)) {
                throw new DataSourceCallException("CURSOR_REPEATED", "Step '${step.id}': the next page has no new cursor.".toString())
            }
        }
    }

    private static int initialPageSize(Map paging, double extraCost) {
        if (paging.pageSize instanceof Number) return ((Number) paging.pageSize).intValue()
        Map p = (Map) paging.pageSize
        int size = Math.max(1, (int) Math.floor(((p.budget as Number).doubleValue()) / (((p.costPerRecord as Number).doubleValue()) + extraCost)))
        return p.max != null ? Math.min(size, ((Number) p.max).intValue()) : size
    }

    /** A query step's selections for this run: the ones whose when holds for {wanted}, their document, cost and values. */
    private static Map planOf(Map step, List<String> wanted) {
        List<Map> on = ((List<Map>) (step.selections ?: [])).findAll { RecordConditions.matches([wanted: wanted], it.when) }
        String document = step.selections != null ? DataSourceDefinitionSupport.fillSlots((String) step.document, on) : (String) step.document
        double extra = (on.collect { ((it.cost ?: 0) as Number).doubleValue() }.sum() ?: 0d) as double
        Map set = [:]
        on.each { if (it.set instanceof Map) set.putAll((Map) it.set) }
        return [document: document, extraCost: extra, set: set, selections: on.collect { it.at + ":" + it.text }]
    }

    /** The fields a run keeps (with their companions), or null for the whole record. A field the record cannot carry is refused. */
    private Set<String> keepFieldsOf(Object keep) {
        Map rec = (Map) definition.record
        if (rec.keepRecordFields != true || !(keep instanceof Collection) || ((Collection) keep).isEmpty()) return null
        Set<String> known = ((((List) rec.fields) ?: []) + (((List) rec.optionalFields) ?: [])).collect { it.toString() } as Set<String>
        Set<String> asked = ((Collection) keep).collect { it?.toString()?.trim() }.findAll { it } as LinkedHashSet<String>
        List<String> unknown = asked.findAll { !known.contains(it) }.sort()
        if (unknown) throw new IllegalArgumentException("This source cannot keep ${unknown}: its records do not carry them.")
        Map keepWith = (Map) (rec.keepWith ?: [:])
        Set<String> out = new LinkedHashSet<String>(asked)
        ((List) (rec.keepAlways ?: [])).each { out.add(it.toString()) }
        asked.each { String f -> ((List) (keepWith.get(f) ?: [])).each { out.add(it.toString()) } }
        return out
    }

    // ---------------------------------------------------------------- one batch

    private List<Map> finish(Map first, List<Map> roots) {
        batchCount.incrementAndGet()
        if (optionFields) roots.each { it.putAll(optionFields) }
        Map<String, List<Map>> ctx = [(first.id as String): roots]
        String current = (String) first.id
        for (Map step : ((List<Map>) definition.steps).drop(1)) {
            switch (step.kind) {
                case "query": ctx.put((String) step.id, readChildren(step, ctx)); break
                case "unwrap": unwrap(step, ctx.get((String) step.of)); break
                case "join": join(step, ctx); break
                case "split": ctx.put((String) step.id, split(step, ctx.get((step.from ?: current) as String))); current = (String) step.id; break
                case "repeat": ctx.put((String) step.id, repeat(step, ctx.get(current))); current = (String) step.id; break
                case "number": number(step, ctx.get((step.on ?: current) as String)); break
                case "count": count(step, ctx.get(current)); break
                case "derive":
                    engines.computeIfAbsent((String) step.id, { DeriveEngine.compile((List<Map>) step.rules) })
                            .derive(ctx.get((step.on ?: current) as String), "derive:${step.id}".toString())
                    break
                case "filter": ctx.put((String) step.id, filter(step, ctx.get(current))); current = (String) step.id; break
                case "drop": drop(step, ctx.get((step.on ?: current) as String)); break
                default: throw new UnsupportedOperationException("The ${step.kind} step is not built yet (step '${step.id}').")
            }
        }
        List<Map> out = ctx.get(current).collect { Map r ->
            Map p = project(r)
            keepFields == null ? p : p.findAll { k, v -> keepFields.contains(k) }
        }
        recordCount.addAndGet(out.size())
        return out
    }

    private List<Map> readChildren(Map step, Map<String, List<Map>> ctx) {
        // A child read is held whole for its batch: a read of everything always, and every child read of a whole set, count
        // against the same cap as the first step, so no step can hold more than a whole-set read may.
        int cap = heldCap > 0 ? heldCap : DEFAULT_MAX_RECORDS_IN_MEMORY
        boolean capped = heldCap > 0 || (((Map) step.bind).all != null && ((Map) step.bind).ids == null)
        java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger()
        Closure<Void> hold = { List<Map> page ->
            if (capped && held.addAndGet(page.size()) > cap) {
                throw new DataSourceCallException("TOO_MANY_RECORDS", "Step '${step.id}' read more than ${cap} records, the most one step holds.".toString())
            }
        }
        if (((Map) step.bind).all != null && ((Map) step.bind).ids == null) {
            List<Map> every = []
            readPages(step, allPieces(step)[0], { List<Map> page -> hold.call(page); every.addAll(page) })
            return every
        }
        Map b = (Map) ((Map) step.bind).ids
        String[] from = ((String) b.from).split(/\./, 2)
        List<String> ids = cleanIds(((List<Map>) (ctx.get(from[0]) ?: [])).findAll { RecordConditions.matches(it, b.when) }
                .collectMany { JsonPathValues.values(it, from[1]) })
        List<List<String>> chunks = ids.collate(((b.chunk ?: 100) as Number).intValue())
        List<List<Map>> byChunk = chunks.collect { new ArrayList<Map>() }
        Closure readChunk = { int i ->
            readPages(step, fill((Map) b.variables, [ids: chunks[i].join((b.separator ?: ",") as String), keyField: ""] + optionTexts),
                    { List<Map> page -> hold.call(page); byChunk[i].addAll(page) })
        }
        int parallel = Math.max(1, Math.min(((b.parallel ?: 1) as Number).intValue(), chunks.size()))
        if (parallel == 1) {
            chunks.indices.each { readChunk.call(it) }
        } else {
            // A step's chunks for one batch side by side (bind.ids.parallel), each into its own list, so the records keep
            // chunk order whichever answers first; the first failure is thrown after the others stop.
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger()
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>()
            List<Thread> threads = (1..parallel).collect { int n ->
                Thread t = new Thread({ ->
                    int i
                    while (failure.get() == null && (i = next.getAndIncrement()) < chunks.size()) {
                        try { readChunk.call(i) } catch (Throwable e) { failure.compareAndSet(null, e) }
                    }
                } as Runnable, "data-source-${step.id}-${n}")
                t.daemon = true
                t.start()
                t
            }
            try {
                threads.each { it.join() }
            } catch (Throwable waiting) {
                failure.compareAndSet(null, waiting)
                threads.each { it.interrupt() }
                if (waiting instanceof InterruptedException) Thread.currentThread().interrupt()
            }
            if (failure.get() != null) throw stopped(failure.get())
        }
        return byChunk.flatten() as List<Map>
    }

    private static void unwrap(Map step, List<Map> input) {
        (input ?: []).each { Map r ->
            Object v = r.get(step.field)
            if (v != null) r.put((String) step.field, JsonPathValues.values(v, (String) step.path).findAll { it instanceof Map })
        }
    }

    private static void join(Map step, Map<String, List<Map>> ctx) {
        List<Map> into = ctx.get((String) step.into) ?: []
        List<Map> from = (ctx.get((String) step.from) ?: []).findAll { RecordConditions.matches(it, step.fromWhen) }
        if (step.sortBy) from = sortedBy(from, (List) step.sortBy)
        List<Map> on = (List<Map>) (step.on ?: [])
        List<Map> atOn = (List<Map>) (step.atOn ?: [])
        boolean many = step.many == true
        Map copy = (Map) step.copy
        String atList = step.at ? ((String) step.at).replace("[*]", "") : null
        Map missing = (Map) step.missingAt
        boolean place = many || missing?.create == true
        if (!place) {
            // Enrich: each target looks up its one match and takes its fields.
            Map<String, List<Map>> index = [:]
            boolean last = step.last == true
            List<Map> notOn = (List<Map>) (step.notOn ?: [])
            from.each { Map f -> index.computeIfAbsent(key(f, (on + atOn).collect { it.from as String }), { [] }).add(f) }
            into.each { Map r ->
                List<Map> targets = atList ? listAt(r, atList, false) : [r]
                targets.each { Map t ->
                    Map<String, Object> keyed = [:]
                    on.each { keyed.put((String) it.into, JsonPathValues.value(r, (String) it.into)) }
                    atOn.each { keyed.put((String) it.at, JsonPathValues.value(t, (String) it.at)) }
                    List<Map> candidates = index.get(keyOf(on.collect { keyed.get(it.into) } + atOn.collect { keyed.get(it.at) })) ?: []
                    Map match = firstAccepted(candidates, last, notOn.collect { keyOf([JsonPathValues.value(r, (String) it.into)]) },
                            notOn.collect { it.from as String })
                    if (match != null) attach(t, match, step, copy, false)
                }
            }
            return
        }
        // Place: each child goes under the target its keys name, in the order the children came.
        Map<String, List<Map>> byParent = [:]
        from.each { Map f -> byParent.computeIfAbsent(key(f, on.collect { it.from as String }), { [] }).add(f) }
        into.each { Map r ->
            List<Map> children = byParent.get(keyOf(on.collect { JsonPathValues.value(r, (String) it.into) })) ?: []
            children.each { Map f ->
                Map target = r
                if (atList) {
                    List<Map> targets = listAt(r, atList, true)
                    // Keys match as text, exactly ("00001" is not "1"), as the parent's own key would.
                    target = targets.find { Map t -> atOn.every { keyOf([JsonPathValues.value(t, (String) it.at)]) == keyOf([JsonPathValues.value(f, (String) it.from)]) } }
                    if (target == null) {
                        if (missing?.create != true) return
                        target = new LinkedHashMap()
                        atOn.each { target.put(it.at, f.get(it.from)) }
                        ((Map) (missing.set ?: [:])).each { k, v -> target.put(k, v) }
                        targets.add(target)
                    }
                }
                attach(target, f, step, copy, many)
            }
            if (many && step.always == true && !atList && r.get(step.as) == null) r.put((String) step.as, [])
        }
    }

    /**
     * The first candidate (the last, with last) whose notOn values all differ from the target's own, scanning only as far
     * as that one: with no notOn it is first or last outright, so a key many records share stays linear, not targets x candidates.
     */
    private static Map firstAccepted(List<Map> candidates, boolean last, List<String> own, List<String> notOnFrom) {
        int n = candidates.size()
        for (int i = 0; i < n; i++) {
            Map f = candidates.get(last ? n - 1 - i : i)
            boolean accepted = true
            for (int j = 0; j < own.size() && accepted; j++) accepted = own.get(j) != keyOf([JsonPathValues.value(f, notOnFrom.get(j))])
            if (accepted) return f
        }
        return null
    }

    /** Records in a declared order (paths, or {path, as: TEXT}), stably. */
    private static List<Map> sortedBy(List<Map> records, List order) {
        List<Map> orderBy = order.collect { it instanceof Map ? (Map) it : [path: it] }
        return records.sort(false) { Map a, Map b ->
            for (Map o : orderBy) {
                Object x = JsonPathValues.value(a, (String) o.path), y = JsonPathValues.value(b, (String) o.path)
                int c = o.as == "TEXT" ? ((x?.toString() ?: "") <=> (y?.toString() ?: "")) : compare(x, y)
                if (c != 0) return c
            }
            return 0
        }
    }

    /** The run's option values: declared ones only, true or false (or their text), else the default (else false). */
    private void resolveOptions(Map given) {
        List<Map> declared = (List<Map>) (definition.options ?: [])
        Set<String> names = declared.collect { it.name.toString() } as Set<String>
        List<String> unknown = ((given ?: [:]).keySet().collect { it.toString() }).findAll { !names.contains(it) }.sort()
        if (unknown) throw new IllegalArgumentException("This source takes no option ${unknown.join(', ')}.")
        declared.each { Map opt ->
            Object raw = given?.get(opt.name)
            Boolean value
            if (raw == null || raw.toString().trim().isEmpty()) value = (opt.default == true)
            else if (raw instanceof Boolean) value = (Boolean) raw
            else if (raw.toString().trim().toLowerCase() in ["true", "y"]) value = true
            else if (raw.toString().trim().toLowerCase() in ["false", "n"]) value = false
            else throw new IllegalArgumentException("The option ${opt.name} is true or false, not '${raw}'.")
            if (opt.text instanceof Map) optionTexts.put((String) opt.name, (String) ((Map) opt.text).get(value.toString()))
            if (opt.field) optionFields.put((String) opt.field, value)
        }
    }

    private static void attach(Map target, Map match, Map step, Map copy, boolean many) {
        if (many) {
            Map line = match
            if (step.fields != null || step.optionalFields != null) {
                line = new LinkedHashMap()
                ((List) (step.fields ?: [])).each { line.put(it, match.get(it)) }
                ((List) (step.optionalFields ?: [])).each { if (match.get(it) != null) line.put(it, match.get(it)) }
            }
            ((List) target.computeIfAbsent((String) step.as, { [] })).add(line)
            return
        }
        if (copy) {
            copy.each { String to, Object path ->
                Object v = JsonPathValues.value(match, (String) path)
                if (v != null && v.toString().trim()) target.put(to, v)
            }
        } else {
            target.put((String) step.as, match)
        }
    }

    /** The list at `name` on a record, made (and kept) when `create`. Elements are copied once into mutable maps. */
    private static List<Map> listAt(Map r, String name, boolean create) {
        Object v = r.get(name)
        if (!(v instanceof List)) {
            if (!create) return []
            v = []
            r.put(name, v)
        }
        return (List<Map>) v
    }

    private static List<Map> split(Map step, List<Map> roots) {
        List<String> segments = ((String) step.path).split(/\./).collect { it.replace("[*]", "") }
        List<String> levels = (List<String>) step.levels
        Map copy = (Map) (step.copy ?: [:])
        List<Map> out = []
        (roots ?: []).each { Map root -> descend(root, segments, 0, [(levels[0]): root], levels, copy, out) }
        return out
    }

    private static void descend(Map node, List<String> segments, int depth, Map record, List<String> levels, Map copy, List<Map> out) {
        if (depth == segments.size()) {
            Map r = new LinkedHashMap(record)
            copy.each { String to, Object path -> r.put(to, JsonPathValues.value(r, (String) path)) }
            out.add(r)
            return
        }
        Object list = node.get(segments[depth])
        if (!(list instanceof List)) return
        ((List) list).each { Object child ->
            if (!(child instanceof Map)) return
            Map next = new LinkedHashMap(record)
            next.put(levels[depth + 1], child)
            descend((Map) child, segments, depth + 1, next, levels, copy, out)
        }
    }

    private static List<Map> repeat(Map step, List<Map> input) {
        String index = (step.index ?: "unitIndex") as String
        int nullAs = ((step.nullAs != null ? step.nullAs : 1) as Number).intValue()
        List<Map> out = []
        (input ?: []).each { Map r ->
            int n = units(JsonPathValues.value(r, (String) step.times), (String) step.parse, nullAs)
            for (int i = 1; i <= n; i++) {
                Map copy = new LinkedHashMap(r)
                copy.put(index, i)
                out.add(copy)
            }
        }
        return out
    }

    /**
     * How many units a quantity gives. STRICT_INT: a number's whole part (never below 0); text must be a whole number
     * ("1.0" gives 0). NUMERIC: any number or numeric text, its whole part. Null or blank gives nullAs.
     */
    static int units(Object quantity, String parse, int nullAs) {
        if (quantity == null) return nullAs
        if (quantity instanceof Number) return Math.max(0, ((Number) quantity).intValue())
        String s = quantity.toString().trim()
        if (!s) return nullAs
        if (parse == "NUMERIC") {
            BigDecimal n = RecordConditions.number(s)
            return n == null ? 0 : Math.max(0, n.intValue())
        }
        try { return Math.max(0, Integer.parseInt(s)) } catch (NumberFormatException ignored) { return 0 }
    }

    private static void number(Map step, List<Map> input) {
        List<String> partitionBy = (List<String>) (step.partitionBy ?: [])
        List<Map> orderBy = ((List) (step.orderBy ?: [])).collect { it instanceof Map ? (Map) it : [path: it] }
        Map<String, List<Map>> groups = new LinkedHashMap<>()
        (input ?: []).each { Map r -> groups.computeIfAbsent(keyOf(partitionBy.collect { JsonPathValues.value(r, it) }), { [] }).add(r) }
        groups.values().each { List<Map> group ->
            List<Map> sorted = group.sort(false) { Map a, Map b ->
                for (Map o : orderBy) {
                    Object x = JsonPathValues.value(a, (String) o.path), y = JsonPathValues.value(b, (String) o.path)
                    int c = o.as == "TEXT" ? ((x?.toString() ?: "") <=> (y?.toString() ?: "")) : compare(x, y)
                    if (c != 0) return c
                }
                return 0
            }
            // One read-only list per partition, shared by its records: a copy each would be the partition's size squared.
            List<Object> collected = step.collect ? java.util.Collections.unmodifiableList(
                    sorted.collect { JsonPathValues.value(it, (String) ((Map) step.collect).field) }) : null
            sorted.eachWithIndex { Map r, int i ->
                r.put((String) step.field, i + 1)
                if (step.sizeField) r.put((String) step.sizeField, sorted.size())
                if (collected != null) r.put((String) ((Map) step.collect).as, collected)
            }
        }
    }

    private List<Map> filter(Map step, List<Map> input) {
        if (!filterRules) return input
        List<Map> kept = []
        (input ?: []).each { Map r ->
            Map projected = project(r)
            Map view = new LinkedHashMap()
            Object under = step.overlay ? r.get(step.overlay) : null
            if (under instanceof Map) ((Map) under).each { k, v -> if (!(v instanceof Map) && !(v instanceof Collection)) view.put(k?.toString(), v) }
            view.putAll(projected)
            Map<String, Object> verdict = darpan.reconciliation.source.SourceFilterSupport.evaluate(view, filterRules)
            if (verdict == null) { kept.add(r); return }
            Map rule = (Map) verdict.rule
            synchronized (excludedCollector) { darpan.reconciliation.conclusion.ExcludedRecordsSidecar.collect(excludedCollector, projected, rule) }
            Map<Integer, Integer> bucket = darpan.reconciliation.source.SourceFilterSupport.REASON_FIELD_ABSENT == verdict.reason ? fieldAbsentByRule : excludedByRule
            Integer seq = (Integer) rule.sequenceNum
            synchronized (bucket) { bucket.put(seq, (bucket.get(seq) ?: 0) + 1) }
        }
        return kept
    }

    /** A rule on a field the filter step does not offer would silently exclude nothing: refused before any read. */
    private void checkFilterFields() {
        if (!filterRules) return
        Map step = ((List<Map>) definition.steps).find { it.kind == "filter" }
        if (step == null) throw new IllegalArgumentException("This source takes no exclusion rules.")
        Set<String> offered = ((step.fields ?: []) as List).collect { it.toString() } as Set<String>
        List<String> unknown = filterRules.collect { (String) it.fieldExpression }.findAll { !offered.contains(it) }.unique().sort()
        if (unknown) throw new IllegalArgumentException("This source cannot filter on ${unknown}: its records do not carry them.")
    }

    private void drop(Map step, List<Map> input) {
        if (!input) return
        Map sc = (Map) step.sidecar
        List<Map> entries = []
        long n = 0
        Iterator<Map> it = input.iterator()
        while (it.hasNext()) {
            Map r = it.next()
            if (!RecordConditions.matches(r, step.when)) continue
            it.remove()
            n++
            if (sc) entries << ((Map) sc.fields).collectEntries { k, v -> [(k): DeriveRules.valueOf(r, (Map) v)] }
        }
        synchronized (counts) { counts.put((String) step.name, (counts.get(step.name) ?: 0L) + n) }
        if (sc && entries) {
            synchronized (sidecars) {
                Map held = sidecars.computeIfAbsent((String) sc.suffix, { [suffix: sc.suffix, entries: [], truncated: false] })
                int room = Math.max(0, ((sc.max as Number).intValue()) - ((List) held.entries).size())
                ((List) held.entries).addAll(entries.take(room))
                if (entries.size() > room) held.truncated = true
            }
        }
    }

    private void count(Map step, List<Map> input) {
        List<String> distinct = (List<String>) step.distinct
        Collection<Map> matched = (input ?: []).findAll { RecordConditions.matches(it, step.when) }
        long n = distinct ? matched.collect { Map r -> keyOf(distinct.collect { JsonPathValues.value(r, it) }) }.unique().size() : matched.size()
        synchronized (counts) { counts.put((String) step.name, (counts.get(step.name) ?: 0L) + n) }
    }

    private Map project(Map r) {
        Map rec = (Map) definition.record
        Map out = new LinkedHashMap()
        ((List<String>) rec.fields).each { out.put(it, r.get(it)) }
        ((List<String>) (rec.optionalFields ?: [])).each { if (r.get(it) != null) out.put(it, r.get(it)) }
        return out
    }

    // ---------------------------------------------------------------- helpers

    static int compare(Object a, Object b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1
        BigDecimal x = RecordConditions.number(a), y = RecordConditions.number(b)
        if (x != null && y != null) return x <=> y
        return a.toString() <=> b.toString()
    }

    private static String key(Map record, List<String> paths) {
        return keyOf(paths.collect { JsonPathValues.value(record, it) })
    }

    private static String keyOf(List values) {
        return values.collect { it == null ? "\u0000" : it.toString() }.join("\u0001")
    }

    private static List<String> cleanIds(List raw) {
        return ((raw ?: []) as List).collect { it?.toString()?.trim() }.findAll { it }.unique()
    }

    private static Map fill(Map templates, Map<String, String> values) {
        return (templates ?: [:]).collectEntries { k, v ->
            String s = v?.toString() ?: ""
            values.each { String name, String value -> s = s.replace("{${name}}".toString(), value ?: "") }
            [(k): s]
        }
    }

    private static String instant(long millis, String format) {
        return format == "EPOCH_MILLIS" ? Long.toString(millis) : Instant.ofEpochMilli(millis).toString()
    }
}
