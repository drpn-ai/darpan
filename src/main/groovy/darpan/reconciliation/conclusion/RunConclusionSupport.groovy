package darpan.reconciliation.conclusion

import darpan.facade.reconciliation.DiffDetailClassifier
import darpan.facade.reconciliation.DiffDocumentStreamSupport
import groovy.json.JsonSlurper

/**
 * DAR-UI-044. The conclude pass: after the compare and the verify passes, every finding gets the
 * conclusion the scope's rules reach about it, and the summary gets a count per conclusion.
 *
 * Evidence for a finding comes from both sides: the side the record is present in carries it on the
 * row itself (`data`); the other side is looked up by compare key among that side's KEPT extract
 * records and among the records its source filters EXCLUDED (the sidecar). A side that had filters but
 * whose sidecar is missing or truncated cannot tell excluded from absent, so its presence is UNKNOWN,
 * which no rule accepts: the finding reads Unexplained rather than a false "Not found".
 *
 * Streams the result document (it reaches GB scale). A side's extract is parsed whole, so an extract
 * over {@link #maxIndexedExtractBytes} is not indexed at all and its side reads UNKNOWN; of a parsed
 * extract only records whose key a finding needs are kept in memory. A side whose key this pass cannot
 * rebuild the way the compare built it (spec.keyReliable false) is UNKNOWN for the same reason.
 */
class RunConclusionSupport {

    static final String TMP_SUFFIX = "conclude-tmp"
    /** Records kept per key: enough to judge "every record under this key", bounded for a hot key. */
    static final int MAX_RECORDS_PER_KEY = 1000
    /** A parsed JSON graph costs several times its file size; above this a side is not indexed. */
    static long maxIndexedExtractBytes = 200L * 1024 * 1024

    static Map concludeRun(Map args) {
        List<Map> rules = (List<Map>) (args.rules ?: [])
        if (!rules) return [ran: false]
        File diffFile = (File) args.diffFile
        String file1Label = (String) args.file1Label
        String file2Label = (String) args.file2Label
        boolean singleSided = args.singleSided == true
        Map sideSpecs = [FILE_1: (Map) args.file1, FILE_2: singleSided ? null : (Map) args.file2]

        // Pass 1: which keys do the findings need?
        Set<String> neededKeys = new HashSet<>()
        Set<String> neededFirstFields = new HashSet<>()
        DiffDocumentStreamSupport.forEachRow(diffFile) { Map row ->
            String key = row.get("primaryId")?.toString()
            if (key) {
                neededKeys.add(key)
                neededFirstFields.add(ConclusionKeySupport.firstField(key))
            }
        }

        Map<String, Map> index = [:]
        List<String> unknownSides = []
        sideSpecs.each { String side, Map spec ->
            if (spec == null) return
            Map sideIndex = indexSide(spec, neededKeys, neededFirstFields)
            index.put(side, sideIndex)
            if (sideIndex.unknownWhenAbsent) unknownSides.add(side)
        }

        Map<String, Integer> counts = new LinkedHashMap<>()
        Map<String, String> labels = new LinkedHashMap<>()
        rules.each { Map rule ->
            String code = rule.get("conclusionEnumId")?.toString()
            if (code && !counts.containsKey(code)) {
                counts.put(code, 0)
                labels.put(code, rule.get("label")?.toString() ?: code)
            }
        }
        counts.put(ConclusionRuleEvaluator.UNEXPLAINED, 0)
        labels.put(ConclusionRuleEvaluator.UNEXPLAINED, ConclusionRuleEvaluator.UNEXPLAINED_LABEL)

        JsonSlurper slurper = new JsonSlurper()
        DiffDocumentStreamSupport.rewriteDocument(diffFile, TMP_SUFFIX, { Map row ->
            String bucket = singleSided ? "FINDING" : bucketOf(row, file1Label, file2Label)
            String key = row.get("primaryId")?.toString()
            String presentSide = singleSided ? "FILE_1" : (bucket == "MISSING_FROM_FILE_1" ? "FILE_2" : "FILE_1")
            Map presentRecord = parseData(slurper, row.get("data"))

            Map sides = [:]
            sideSpecs.each { String side, Map spec ->
                if (spec == null) return
                if (side == presentSide) {
                    // The row carries ONE record per key; the side's index carries every record the
                    // compare collapsed into it, so field conditions can require all of them.
                    List keptUnderKey = (List) ((Map) ((Map) index.get(side))?.get("keptFull"))?.get(key)
                    sides.put(side, [presence: "KEPT", record: presentRecord, records: keptUnderKey ?: [presentRecord],
                                     firstFieldPresence: "KEPT", firstFieldRecord: presentRecord])
                } else {
                    sides.put(side, lookup((Map) index.get(side), key))
                }
            }
            Map conclusion = ConclusionRuleEvaluator.evaluate(rules, bucket, sides)
            String code = conclusion.code?.toString()
            if (!counts.containsKey(code)) {
                counts.put(code, 0)
                labels.put(code, conclusion.label?.toString() ?: code)
            }
            counts.put(code, counts.get(code) + 1)

            List systems = []
            sideSpecs.each { String side, Map spec ->
                if (spec == null) return
                systems.add(evidenceFor(side, (Map) sides.get(side), (Map) (spec.evidence ?: [:]),
                        side == "FILE_1" ? file1Label : file2Label))
            }
            return row + [conclusion: [code    : code,
                                       label   : conclusion.label,
                                       systems : systems,
                                       checks  : conclusion.checks,
                                       question: conclusion.question]]
        }, { Map summary ->
            return summary + [conclusions: [enabled: true,
                                            counts : counts.collect { String code, Integer count ->
                                                [code: code, label: labels.get(code), count: count]
                                            }]]
        })
        return [ran: true, counts: counts, unknownSides: unknownSides]
    }

    private static String bucketOf(Map row, String file1Label, String file2Label) {
        String bucket = DiffDetailClassifier.resolveDiffBucket(row, file1Label, file2Label)
        if (bucket == "file-1") return "MISSING_FROM_FILE_1"
        if (bucket == "file-2") return "MISSING_FROM_FILE_2"
        return "RULE"
    }

    private static Map parseData(JsonSlurper slurper, Object data) {
        if (data instanceof Map) return (Map) data
        if (data == null) return [:]
        try {
            Object parsed = slurper.parseText(data.toString())
            return parsed instanceof Map ? (Map) parsed : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    /** KEPT and EXCLUDED records of one side, by full key and by first key field, for needed keys only. */
    private static Map indexSide(Map spec, Set<String> neededKeys, Set<String> neededFirstFields) {
        File extractFile = (File) spec.extractFile
        List<String> keyFields = (List<String>) (spec.keyFields ?: [])
        String normalizer = (String) spec.idNormalizer
        Map keptFull = [:], keptFirst = [:], excludedFull = [:], excludedFirst = [:]
        boolean readable = extractFile?.exists() && spec.keyReliable != false && extractFile.length() <= maxIndexedExtractBytes
        if (readable) {
            Object parsed = new JsonSlurper().parse(extractFile, "UTF-8")
            Map doc = parsed instanceof Map ? (Map) parsed : [records: parsed instanceof List ? parsed : []]
            List exclusions = configuredExclusions((Map) (doc.metadata ?: [:]))
            addAll(keptFull, keptFirst, (List) (doc.records ?: []), keyFields, normalizer, neededKeys, neededFirstFields)
            Map sidecar = ExcludedRecordsSidecar.read(extractFile)
            if (sidecar.present) {
                addAll(excludedFull, excludedFirst, (List) sidecar.records, keyFields, normalizer, neededKeys, neededFirstFields)
            }
            // Extractors write no sidecar when nothing was excluded, so a missing one is only unknown
            // when the side's own metadata does not prove that every rule dropped nothing.
            boolean unknown = exclusions && (sidecar.present ? sidecar.truncated == true : !excludedNothing(exclusions))
            return [keptFull: keptFull, keptFirst: keptFirst, excludedFull: excludedFull,
                    excludedFirst: excludedFirst, unknownWhenAbsent: unknown]
        }
        // No extract, one too large to parse, or a key this pass cannot rebuild: nothing can be claimed absent.
        return [keptFull: keptFull, keptFirst: keptFirst, excludedFull: excludedFull,
                excludedFirst: excludedFirst, unknownWhenAbsent: true]
    }

    private static List configuredExclusions(Map metadata) {
        Object direct = metadata.get("configuredExclusions")
        if (direct instanceof List && !((List) direct).isEmpty()) return (List) direct
        Object nested = (metadata.get("filters") instanceof Map) ? ((Map) metadata.get("filters")).get("configuredExclusions") : null
        return nested instanceof List ? (List) nested : []
    }

    /** True only when every rule REPORTS zero excluded and zero field-absent drops; a missing count proves nothing. */
    private static boolean excludedNothing(List exclusions) {
        return exclusions.every { Object rule ->
            rule instanceof Map && isZero(((Map) rule).get("excludedCount")) &&
                    (((Map) rule).get("fieldAbsentCount") == null || isZero(((Map) rule).get("fieldAbsentCount")))
        }
    }

    private static boolean isZero(Object value) {
        if (value == null) return false
        try {
            return new BigDecimal(value.toString().trim()) == BigDecimal.ZERO
        } catch (NumberFormatException ignored) {
            return false
        }
    }

    private static void addAll(Map full, Map first, List records, List<String> keyFields, String normalizer,
                               Set<String> neededKeys, Set<String> neededFirstFields) {
        for (Object raw : records) {
            if (!(raw instanceof Map)) continue
            String key = ConclusionKeySupport.compareKey((Map) raw, keyFields, normalizer)
            if (key == null) continue
            if (neededKeys.contains(key)) {
                List underKey = (List) full.computeIfAbsent(key) { [] }
                if (underKey.size() < MAX_RECORDS_PER_KEY) underKey.add(raw)
            }
            String firstField = ConclusionKeySupport.firstField(key)
            if (neededFirstFields.contains(firstField) && !first.containsKey(firstField)) first.put(firstField, raw)
        }
    }

    private static Map lookup(Map sideIndex, String key) {
        if (sideIndex == null || key == null) return [presence: "UNKNOWN", firstFieldPresence: "UNKNOWN"]
        String firstField = ConclusionKeySupport.firstField(key)
        String absent = sideIndex.unknownWhenAbsent ? "UNKNOWN" : "ABSENT"
        Map result = [:]
        if (((Map) sideIndex.keptFull).containsKey(key)) result.putAll([presence: "KEPT", record: ((List) ((Map) sideIndex.keptFull).get(key))[0]])
        else if (((Map) sideIndex.excludedFull).containsKey(key)) result.putAll([presence: "EXCLUDED", record: ((List) ((Map) sideIndex.excludedFull).get(key))[0]])
        else result.putAll([presence: absent, record: null])
        if (((Map) sideIndex.keptFirst).containsKey(firstField)) result.putAll([firstFieldPresence: "KEPT", firstFieldRecord: ((Map) sideIndex.keptFirst).get(firstField)])
        else if (((Map) sideIndex.excludedFirst).containsKey(firstField)) result.putAll([firstFieldPresence: "EXCLUDED", firstFieldRecord: ((Map) sideIndex.excludedFirst).get(firstField)])
        else result.putAll([firstFieldPresence: absent, firstFieldRecord: null])
        return result
    }

    private static Map evidenceFor(String side, Map sideState, Map evidence, String label) {
        Map record = (Map) sideState?.get("record")
        String presence = sideState?.get("presence")?.toString() ?: "UNKNOWN"
        String stateField = evidence.get("state")?.toString()
        String rawState = (record != null && stateField) ? record.get(stateField)?.toString() : null
        Map stateLabels = (Map) (evidence.get("stateLabels") ?: [:])
        List facts = record == null ? [] : ((List) (evidence.get("facts") ?: [])).collect { Object field ->
            record.get(field?.toString())?.toString()
        }.findAll { it }
        return [side    : side,
                system  : evidence.get("system")?.toString() ?: label,
                presence: presence,
                state   : rawState == null ? null : (stateLabels.get(rawState)?.toString() ?: rawState),
                facts   : facts]
    }
}
