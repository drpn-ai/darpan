package darpan.judge

import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ConclusionLookupSupport
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepStepSupport
import darpan.audit.AuditAutomationSupport
import darpan.common.FactText
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import groovy.json.JsonOutput
import org.apache.spark.sql.Dataset

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.regex.Pattern

/**
 * DAR-BE-318 plan 2, Task 8. The replay harness: a captured run's extracts through today's compare and conclude, and beside
 * them through the judge (spec §5, §6), so the two sets of findings can be compared before a tenant switches (spec §8, parity
 * gate). Groovy because it streams Spark frames and files and batches pairs across service calls; every entity read is in
 * judge.JudgeReplayServices.
 *
 * The judging reads today's matched and missing frames as pairs (ruling P2-15): a matched row is [compareId, file1, file2]; a
 * MISSING_IN_FILE_2 row is [compareId, file1: its data, file2: null] and MISSING_IN_FILE_1 the reverse. What a side set aside
 * comes from two places (DAR-BE-318 plan 3b-2b, Ruling 3b2b-3: the judge path gets Spark presence on every side): Spark's Keep
 * in this compare (setAsideBySide, and the set-aside records KeepArtifacts names beside the extract), and the side file an
 * extractor wrote (".excluded.json": an old capture's, or what a connector that still filters dropped). It reads two ways
 * (spec §8):
 *  - on a side whose filters are promises (a conclusion of the scope reads that side with presence EXCLUDED), the new config
 *    has no such Keep: those records are read back as the side's own records (KEPT), and one with no pair in the frames
 *    becomes a pair of its own;
 *  - on any other side they stay kept out by Keep: the compare id reads SET_ASIDE, with no fields (ruling D8). A side whose
 *    connector writes no side file (discovery risk 3) now reads SET_ASIDE too, from Spark.
 *
 * A replay never calls a system (ruling P2-24): every lookup the conclude pass asks and every ask the judge fetches is
 * answered by capturedFetcher, from the lookups captured in the run folder (lookups/lookup-<name>-<millis>.json, the name
 * today's lookup gives its extract), or not at all, with a warning that names it.
 *
 * Runs on the request thread as the tenant user with authz on (rulings P2-7, P2-9): no disableAuthz, RunAdmission or
 * withSystemTenant. It never writes into the captured run folder (ruling P2-21) and records no finding (ruling P2-18).
 */
class JudgeReplaySupport {

    static final String DIFF_FILE = "diff.json"
    static final String RESULT_FILE = "result.json"
    static final String JUDGED_FILE = "judged-findings.json"
    static final String REPLAYS_PATH = "judge-replays"
    static final String EXTRACTS_DIR = "extracts"
    static final String LOOKUPS_DIR = "lookups"
    static final Pattern CAPTURED_LOOKUP = ~/^lookup-([A-Za-z0-9_]+)-(\d+)\.json$/
    static final int BATCH_SIZE = 500
    static final List<String> SIDES = ["file1", "file2"].asImmutable()

    // ------------------------------------------------------------------------------------------------ the hook

    /**
     * The judging behind reconcile#RuleSetCompareScope's judgeWithDecisions (judge.JudgeReplayServices.judge#ComparePairs).
     * args: checkId, tenantId, matchedPairDf, missingDiffDf (RULESET_DIFF_SCHEMA), extractFiles [file1, file2: File],
     * keySpecs [file1, file2: RunConclusionStep.keySpec], promiseSides (Set of file1/file2), systems [file1, file2: system enum
     * id], statementsByClaim, params, setAsideBySide [file1/file2: the compare ids Spark's Keep set aside, for a side that kept
     * in Spark]. Returns [findings, warnings, pairCounts: pairs judged from the matched frame, the missing frame and the
     * read-back records no row named]. Throws IllegalStateException, in words, when it cannot judge.
     */
    static Map judgeCompare(def ec, Map args) {
        String checkId = args.checkId as String
        String tenantId = args.tenantId as String
        List<String> warnings = []
        Map<String, Map<String, Map>> readBack = [:]
        Map<String, Collection<String>> setAside = [:]
        // A loop, not a closure: Groovy 5.0.3 fails a static call made inside a closure when an argument is an inline-cast null
        // ("argument type mismatch"), and a side with no Keep passes null here.
        for (String side : SIDES) {
            Map presence = sidePresence((File) ((Map) args.extractFiles)?.get(side), (Map) ((Map) args.keySpecs)?.get(side),
                    ((Collection) args.promiseSides ?: []).contains(side), side, (Collection<String>) ((Map) args.setAsideBySide)?.get(side), warnings)
            readBack.put(side, (Map<String, Map>) presence.records)
            setAside.put(side, (Collection<String>) presence.ids)
        }
        Dataset matched = (Dataset) args.matchedPairDf
        Dataset missing = (Dataset) args.missingDiffDf

        // Pass 1: every key each side holds, so the pair fact type names them all before a fact is built (ruling P2-16).
        Map<String, Set<String>> keys = [file1: new TreeSet<String>(), file2: new TreeSet<String>()]
        new PairRows(matched, missing, copyOf(readBack), [:], [:], [:]).each { Map row ->
            SIDES.each { String side -> if (row.get(side) != null) keys[side].addAll(((Map) row.get(side)).keySet()*.toString()) }
        }
        Map systems = (Map) (args.systems ?: [:])
        Map generated = call(ec, "judge.PairFactServices.generate#PairFactType", [checkId: checkId,
                sampleKeys: [file1: keys.file1 as List, file2: keys.file2 as List],
                file1SystemEnumId: systems.file1, file2SystemEnumId: systems.file2], "Judging check ${checkId}")
        List<String> prefixes = PairFactSupport.sidePrefixes(systems.file1 as String, systems.file2 as String)
        Map keyNames = (Map) (generated.keyNames ?: [:])
        Map sideState = [factTypeId: generated.factTypeId,
                         file1     : [prefix: prefixes[0], setAside: setAside.file1, keyNames: keyNames.file1],
                         file2     : [prefix: prefixes[1], setAside: setAside.file2, keyNames: keyNames.file2]]

        // Pass 2: the same pairs in batches, each batch's asks fetched once, then built and judged.
        Map<String, Integer> skipped = [:]
        Map<String, Integer> dropped = [:]
        Map<String, Integer> pairCounts = [matched: 0, missing: 0, readBack: 0]
        Iterator<List<Map>> pairBatches = PairFactSupport.batches(new PairRows(matched, missing, readBack, skipped, dropped, pairCounts), BATCH_SIZE)
        Map params = (Map) (args.params ?: [:])
        // Ruling P2-24: asks are answered from the replay's captured lookups only, never through AskSupport.fetcher.
        File extract1 = (File) ((Map) args.extractFiles)?.get("file1")
        Closure askFetcher = capturedFetcher(lookupsDirOf(extract1), "the replayed run", "Ask", new TreeSet<String>())
        // Every warning an ask gives, once each across batches (each starts "Ask "; the replay also lists them in replayWarnings).
        Set<String> askWarnings = new LinkedHashSet<>()
        Iterator<List<Map>> factBatches = new Iterator<List<Map>>() {
            boolean hasNext() { return pairBatches.hasNext() }

            List<Map> next() {
                List<Map> batch = pairBatches.next()
                List<String> batchWarnings = []
                Map<String, Map<String, Object>> asks = AskSupport.fetchAsks(ec, checkId, tenantId,
                        batch.collect { Map row -> [row.compareId, row.file1, row.file2] }, batchWarnings, askFetcher)
                askWarnings.addAll(batchWarnings)
                return batch.collect { Map row -> PairFactSupport.buildFact(row, sideState, params, asks.get(row.compareId as String)) }
            }
        }
        List<Map> findings = JudgeSupport.judge(ec, checkId, factBatches, (Map) (args.statementsByClaim ?: [:]), warnings)
        warnings.addAll(askWarnings)
        skipped.each { String type, Integer n -> warnings.add("${n} ${type} rows are not pairs and were not judged".toString()) }
        dropped.each { String side, Integer n ->
            warnings.add("${n} ${side} records set aside under a key the compare already holds (kept or duplicated) were not judged".toString())
        }
        return [findings: findings, warnings: warnings, pairCounts: pairCounts]
    }

    /**
     * One side's presence: [records: compareId -> a record read back as kept, ids: the compare ids that read SET_ASIDE].
     *  - Any side but a promise: ids = sparkIds (the compare's setAsideBySide for the side: Spark's own compare ids, already held
     *    by the compare, so nothing is copied unless a side file adds ids too) and the compare ids the extractor's side file names.
     *    An old capture, which has no Keep, reads its side file exactly as before.
     *  - A promise side: records = Spark's set-aside records, streamed from the extract through the rows file
     *    (KeepArtifacts.forEachSetAsideRecord), then the extractor's side file, at most ExcludedRecordsSidecar.MAX_ENTRIES; what
     *    passes the cap reads as absent and says so in a warning.
     * The cost bound: a non-promise side holds no more than the compare already does (setAsideBySide, ~10-15 MB at 125k ids),
     * plus a union only when a side file names ids as well; a promise side holds at most MAX_ENTRIES records, as the side file did.
     */
    static Map sidePresence(File extractFile, Map keySpec, boolean promiseSide, String side, Collection<String> sparkIds, List<String> warnings) {
        Map read = readSidecar(extractFile, keySpec, promiseSide, side, warnings, sparkIds != null)
        if (promiseSide || !sparkIds) return read
        Set<String> fromSideFile = (Set<String>) read.ids
        if (fromSideFile.isEmpty()) return [records: read.records, ids: sparkIds]
        fromSideFile.addAll(sparkIds)
        return read
    }

    /**
     * What a side set aside, read from its files. A promise side: [records: compareId -> the record, read back as kept], Spark's
     * set-aside records first, then the side file's. Any other side: [ids: the compare ids the side file names] (Spark's ids come
     * from the compare, sidePresence). No side file and no Keep artifact means nothing was set aside.
     *
     * The side file is read as the extractor wrote it: when this extract's Keep merged its own records into the file (its
     * .keep.json carries KeepStepSupport.EXTRACTOR_SIDE_FILE), only the extractor's part, the first entries, is read, so Spark's
     * records are never read twice. A truncated side file, or a side whose key cannot be rebuilt from its records, proves
     * nothing and says so in a warning. sparkJudged: the side's Spark ids are judged anyway, so the warning names the side file.
     */
    static Map readSidecar(File extractFile, Map keySpec, boolean promiseSide, String side, List<String> warnings, boolean sparkJudged = false) {
        Map<String, Map> records = new LinkedHashMap<>()
        Set<String> ids = new HashSet<>()
        if (extractFile == null) return [records: records, ids: ids]
        if (keySpec == null || keySpec.keyReliable == false) {
            String what = sparkJudged && !promiseSide ? "nothing its side file set aside" : "nothing it set aside"
            warnings.add("The ${side} key cannot be rebuilt from its records, so ${what} is judged".toString())
            return [records: records, ids: ids]
        }
        List<String> keyFields = (List<String>) keySpec.keyFields
        String normalizer = keySpec.idNormalizer as String
        int repeated = 0
        int overCap = 0
        Closure take = { Map record ->
            Map copy = new LinkedHashMap(record)
            copy.remove("_excludedBy")
            String id = ConclusionKeySupport.compareKey(copy, keyFields, normalizer)
            if (!id) return
            if (!promiseSide) {
                ids.add(id)
            } else if (records.containsKey(id)) {
                repeated++
            } else if (records.size() >= ExcludedRecordsSidecar.MAX_ENTRIES) {
                overCap++
            } else {
                records.put(id, FactText.flat(copy))
            }
        }
        if (promiseSide) KeepArtifacts.forEachSetAsideRecord(extractFile) { Map record, String ruleId -> take(record) }
        Map mark = (Map) KeepArtifacts.readSummary(extractFile)?.get(KeepStepSupport.EXTRACTOR_SIDE_FILE)
        Integer extractorEntries = mark != null ? ((mark.kept ?: 0) as Integer) : null
        int seen = 0
        Map head = ExcludedRecordsSidecar.forEachRecord(extractFile) { Map record ->
            if (extractorEntries == null || seen++ < extractorEntries) take(record)
        }
        if (mark != null ? mark.truncated == true : head.truncated == true) {
            warnings.add("The ${side} sidecar is truncated: what it left out reads as absent".toString())
        }
        if (head.present != true && new File(extractFile.parentFile, ExcludedRecordsSidecar.fileNameFor(extractFile.name)).exists()) {
            warnings.add("The ${side} sidecar cannot be read: what it set aside reads as absent".toString())
        }
        if (repeated) warnings.add("${repeated} ${side} records set aside under a compare id already read back were not judged".toString())
        if (overCap) {
            warnings.add("${overCap} ${side} set-aside records past the first ${ExcludedRecordsSidecar.MAX_ENTRIES} were not read back: they read as absent".toString())
        }
        return [records: records, ids: ids]
    }

    // ------------------------------------------------------------------------------------------------ captured lookups (P2-24)

    /** <replay>/lookups for a copied extract at <replay>/extracts/<side>/<name>; null when the extract is not a replay's copy. */
    static File lookupsDirOf(File copiedExtract) {
        File extracts = copiedExtract?.parentFile?.parentFile
        return extracts?.name == EXTRACTS_DIR ? new File(extracts.parentFile, LOOKUPS_DIR) : null
    }

    /**
     * A lookup call with RunConclusionStep.fetchLookup's signature (ec, lookup, ids, tenantId, warnings) that never calls a
     * system: it answers the asked ids from the newest captured lookup-<name>-<millis>.json in lookupsDir, and when none is
     * captured it adds "<kind> <name> not captured in <where>; not replayed" to warnings and to notCaptured, and answers nothing.
     * A captured file that cannot be read is the same: "<kind> <name> not read from its capture: ..." and no answer (review
     * N-M2: one lookup's bad file must not end the whole conclude pass or judging).
     */
    static Closure capturedFetcher(File lookupsDir, String where, String kind, Set<String> notCaptured) {
        return { def ec, Map lookup, List<String> ids, String tenantId, List<String> warnings ->
            String name = lookup?.name as String
            // DAR-BE-318 P3a-4: a live pass writes one file per fetch batch (and one for store-served answers), so every
            // capture of the name is read, oldest to newest by millis; the newest wins per id, a `many` lookup keeps the union.
            List<File> files = capturedLookupFiles(lookupsDir, name)
            if (files.isEmpty()) {
                String warning = "${kind} ${name} not captured in ${where}; not replayed".toString()
                notCaptured.add(warning)
                warnings?.add(warning)
                return [:]
            }
            Set<String> asked = new HashSet<String>((ids ?: []).collect { it as String })
            Map<String, Object> found = [:]
            for (File file : files) {
                try {
                    Map<String, Object> fromFile = [:]
                    JsonRecordStream.forEachRecord(file, "records") { Map record -> ConclusionLookupSupport.collect(fromFile, lookup, record) }
                    fromFile.each { String key, Object value ->
                        if (lookup.many == true && found.get(key) instanceof List) {
                            List kept = (List) found.get(key)
                            ((List) value).each { Object r -> if (!kept.contains(r)) kept.add(r) }
                        } else {
                            found.put(key, value)
                        }
                    }
                } catch (Exception e) {
                    String warning = "${kind} ${name} not read from its capture ${file.name}: ${e.message ?: e.class.simpleName}".toString()
                    notCaptured.add(warning)
                    warnings?.add(warning)
                }
            }
            return found.findAll { Object key, Object value -> asked.contains(key as String) }
        }
    }

    /** Every capture of a lookup name in lookupsDir, oldest first by the millis in its name. */
    static List<File> capturedLookupFiles(File lookupsDir, String name) {
        if (lookupsDir == null || !name || !lookupsDir.isDirectory()) return []
        return (lookupsDir.listFiles() ?: []).findAll { File f ->
            def m = CAPTURED_LOOKUP.matcher(f.name)
            f.isFile() && m.matches() && m.group(1) == name
        }.sort { File f -> new BigInteger(CAPTURED_LOOKUP.matcher(f.name).with { it.matches(); it.group(2) }) }
    }

    private static Map<String, Map<String, Map>> copyOf(Map<String, Map<String, Map>> readBack) {
        return readBack.collectEntries { String side, Map<String, Map> records -> [(side): new LinkedHashMap(records)] }
                as Map<String, Map<String, Map>>
    }

    /**
     * Pair rows, lazily: the matched frame, then the missing frame, then the read-back records no row named. Every frame row
     * takes its key out of readBack on both sides, so no compare id is paired twice:
     *  - the side a row carries keeps the row's record; a read-back record under the same key there (kept and set aside under
     *    one key) is counted in dropped;
     *  - a missing row's absent side takes the read-back record, if any;
     *  - a duplicate row (DUPLICATE_IN_FILE_x) is no pair: today's compare reports it and leaves its key out on both sides, so
     *    the judging does too (skipped, and its read-back records counted in dropped), never as a read-back-only pair.
     */
    static class PairRows implements Iterator<Map> {
        private final Iterator<String> matched
        private final Iterator<String> missing
        private final Map<String, Map<String, Map>> readBack
        private final Map<String, Integer> skipped
        private final Map<String, Integer> dropped
        private final Map<String, Integer> counts
        private Iterator<String> leftover
        private Map pending

        PairRows(Dataset matchedDf, Dataset missingDf, Map<String, Map<String, Map>> readBack, Map<String, Integer> skipped,
                 Map<String, Integer> dropped, Map<String, Integer> counts) {
            this.matched = rowsOf(matchedDf)
            this.missing = rowsOf(missingDf)
            this.readBack = readBack
            this.skipped = skipped
            this.dropped = dropped
            this.counts = counts
        }

        boolean hasNext() {
            if (pending == null) pending = advance()
            return pending != null
        }

        Map next() {
            if (!hasNext()) throw new NoSuchElementException()
            Map row = pending
            pending = null
            return row
        }

        private Map advance() {
            while (matched.hasNext()) {
                Map row = parseRow(matched.next(), "matched pair")
                String id = row.primaryId as String
                SIDES.each { drop(it, id) }
                count("matched")
                return [compareId: id, file1: FactText.flat((Map) row.file1), file2: FactText.flat((Map) row.file2)]
            }
            while (missing.hasNext()) {
                Map row = parseRow(missing.next(), "missing pair")
                String type = row.diffType as String
                String id = row.primaryId as String
                Map data = row.data ? FactText.flat(parseRow(row.data as String, "missing pair " + row.primaryId + " data")) : [:]
                if (type == "MISSING_IN_FILE_2") {
                    drop("file1", id)
                    return count("missing") + [compareId: id, file1: data, file2: readBack.file2?.remove(id)]
                }
                if (type == "MISSING_IN_FILE_1") {
                    drop("file2", id)
                    return count("missing") + [compareId: id, file1: readBack.file1?.remove(id), file2: data]
                }
                SIDES.each { drop(it, id) }
                skipped.put(type, (skipped.get(type) ?: 0) + 1)
            }
            if (leftover == null) {
                leftover = new TreeSet<String>(SIDES.collectMany { (readBack[it] ?: [:]).keySet() }).iterator()
            }
            while (leftover.hasNext()) {
                String id = leftover.next()
                Map file1 = readBack.file1?.remove(id)
                Map file2 = readBack.file2?.remove(id)
                if (file1 != null || file2 != null) return count("readBack") + [compareId: id, file1: file1, file2: file2]
            }
            return null
        }

        /** Takes a read-back record out of the judging: its key already has a row on this side, or leaves the compare. */
        private void drop(String side, String id) {
            if (readBack[side]?.remove(id) != null) dropped.put(side, (dropped.get(side) ?: 0) + 1)
        }

        /** Counts a row of this kind; answers an empty map so a return can add the row to it. */
        private Map count(String kind) {
            counts.put(kind, (counts.get(kind) ?: 0) + 1)
            return [:]
        }

        /** One row's JSON; a line that is not JSON fails as an unchecked error naming the row, as the slurper did. */
        private static Map parseRow(String json, String what) {
            try {
                return (Map) JsonRecordStream.parseText(json)
            } catch (IOException e) {
                throw new IllegalStateException("A " + what + " row is not valid JSON: " + e.getMessage(), e)
            }
        }

        private static Iterator<String> rowsOf(Dataset df) {
            if (df == null) return Collections.<String>emptyIterator()
            return ReconciliationServices.boundedPartitions(df, ReconciliationServices.MATCHED_PAIRS_PER_PARTITION).toJSON().toLocalIterator()
        }
    }

    // ------------------------------------------------------------------------------------------------ the replay

    /**
     * replay#CheckJudgement after its reads: copies the captured extracts (files: FILE_1/FILE_2 -> [fileLocation, fileName])
     * into a new folder, runs the check's compare over the copies (with keepBySide, each side's Keep plan, so a side that keeps
     * in Spark writes its set-aside artifact beside its copy and the judge reads Spark presence), writes diff.json (today's document, concluded as today's conclude pass does) and result.json
     * (the run's counts) into a new folder, reads today's findings from it and, with the judge on, writes judged-findings.json.
     * The verify passes are not run: they read systems live, and a replay calls no system.
     */
    static Map replay(def ec, Map args) {
        String tenantId = args.tenantId as String
        Map check = (Map) args.check
        Map files = (Map) args.files
        boolean judging = args.judgeWithDecisions != false
        File folder = newReplayFolder(ec, check.checkId as String)
        String folderLocation = DataManagerSupport.relativeDataManagerPath(ec, folder)
        // The captured run is evidence (ruling P2-21): its extracts are copied in and only the copies are read, because the
        // compare writes a records file beside the extract it reads (JsonRecordLines).
        Map file1 = copyExtract(ec, (Map) files.FILE_1, folder, folderLocation, "FILE_1")
        Map file2 = copyExtract(ec, (Map) files.FILE_2, folder, folderLocation, "FILE_2")
        copyCapturedLookups((File) args.runDir, folder)

        Map compareParams = [ruleSetId     : args.ruleSetId, compareScopeId: args.compareScopeId,
                             file1Location : file1.fileLocation, file2Location: file2.fileLocation,
                             file1Name     : file1.fileName, file2Name: file2.fileName, sparkAppName: "JudgeReplay"]
        if (judging) compareParams.putAll([judgeWithDecisions: true, judgeCheckId: check.checkId])
        // DAR-BE-318 (plan 3b-2b): each side keeps in Spark as the scope says today, read for the replaying tenant.
        if (args.keepBySide) compareParams.keepBySide = args.keepBySide
        // Kept even when the compare fails, so its persisted frames are always released (review M4).
        Map compared = (ec.service.sync().name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters(compareParams).call() ?: [:]) as Map
        File diffFile
        try {
            if (ec.message.hasError()) throw new IllegalStateException("Replaying check ${check.checkId}: ${ec.message.errorsString}".toString())
            Map written = ReconciliationServices.writeDiffDatasetOutput(ec, (Dataset) compared.diffDf, folder.absolutePath, DIFF_FILE, DIFF_FILE,
                    [timestamp         : ReconciliationServices.formatMetadataTimestamp(ec.user.nowTimestamp),
                     file1Label        : compared.file1Label, file2Label: compared.file2Label,
                     file1Type         : compared.file1Type, file2Type: compared.file2Type, reconciliation: "RULESET",
                     companyUserGroupId: tenantId, savedRunId: check.savedRunId, ruleSetId: args.ruleSetId,
                     compareScopeId    : args.compareScopeId, compareScopeDescription: compared.compareScopeDescription,
                     replayOfRunFolder : args.runFolder],
                    [totalDifferences            : compared.differenceCount, onlyInFile1Count: compared.missingInFile2Count,
                     onlyInFile2Count            : compared.missingInFile1Count,
                     missingObjectDifferenceCount: compared.missingObjectDifferenceCount, ruleDifferenceCount: compared.ruleDifferenceCount],
                    (List) (compared.validationErrors ?: []), (List) (compared.processingWarnings ?: []))
            diffFile = new File(written.diffLocation as String)
        } finally {
            ReconciliationServices.unpersistDatasets((List) (compared.persistedSources ?: []))
        }

        // Ruling P2-24: the conclude pass's lookups (and a brain's questions) are answered from the captured lookups only.
        Set<String> notCaptured = new TreeSet<>()
        Closure lookupFetcher = capturedFetcher(new File(folder, LOOKUPS_DIR), "run ${args.runFolder}".toString(), "Lookup", notCaptured)
        Map concluded = RunConclusionStep.runIfConfigured([ec: ec, compareScopeId: args.compareScopeId, diffFile: diffFile,
                file1Source: null, file2Source: null, file1Result: [fileLocation: file1.fileLocation],
                file2Result: [fileLocation: file2.fileLocation], lookupFetcher: lookupFetcher]) ?: [:]
        Map counts = [differenceCount             : compared.differenceCount, missingInFile1Count: compared.missingInFile1Count,
                      missingInFile2Count         : compared.missingInFile2Count,
                      missingObjectDifferenceCount: compared.missingObjectDifferenceCount]
        RunConclusionSupport.applyHidden(counts, (Map) concluded.hiddenByBucket)
        Map result = [checkId                : check.checkId, savedRunId: check.savedRunId, ruleSetId: args.ruleSetId,
                      compareScopeId         : args.compareScopeId, compareScopeDescription: compared.compareScopeDescription,
                      replayOfRunFolder      : args.runFolder, file1Name: file1.fileName, file2Name: file2.fileName,
                      file1Label             : compared.file1Label, file2Label: compared.file2Label,
                      differenceCount        : counts.differenceCount, onlyInFile1Count: counts.missingInFile2Count,
                      onlyInFile2Count       : counts.missingInFile1Count,
                      missingObjectDifferenceCount: counts.missingObjectDifferenceCount,
                      duplicateDifferenceCount: compared.duplicateDifferenceCount, ruleDifferenceCount: compared.ruleDifferenceCount,
                      ruleDifferenceFields   : compared.ruleDifferenceFields ?: [], ruleExecutionFailed: compared.ruleExecutionFailed == true,
                      ruleErrors             : compared.ruleErrors ?: [], validationErrors: compared.validationErrors ?: [],
                      processingWarnings     : compared.processingWarnings ?: [], conclusionCounts: concluded.counts,
                      concludeWarning        : concluded.warning]
        new File(folder, RESULT_FILE).setText(JsonOutput.toJson(result), StandardCharsets.UTF_8.name())

        List<Map> oldFindings = AuditAutomationSupport.findingsFrom(ec, tenantId, check, diffFile,
                [systemEnumId: args.file1SystemEnumId], [systemEnumId: args.file2SystemEnumId], [:])
        Map out = [oldFindings: oldFindings, judgedFindings: [], judgeWarnings: [], judgedPairCounts: [:], replayFolder: folderLocation,
                   replayWarnings: replayWarnings(notCaptured, (Map) concluded.brain, [])]
        if (judging) {
            List<Map> judged = (List<Map>) (compared.judgedFindings ?: [])
            List<String> judgeWarnings = (List<String>) (compared.judgeWarnings ?: [])
            File judgedFile = new File(folder, JUDGED_FILE)
            Map pairCounts = (Map) (compared.judgedPairCounts ?: [:])
            judgedFile.setText(JsonOutput.toJson([checkId: check.checkId, pairs: pairCounts, warnings: judgeWarnings, findings: judged]),
                    StandardCharsets.UTF_8.name())
            out.replayWarnings = replayWarnings(notCaptured, (Map) concluded.brain, judgeWarnings)
            out.putAll([judgedFindings: judged, judgeWarnings: judgeWarnings, judgedPairCounts: pairCounts,
                        judgedPath    : DataManagerSupport.relativeDataManagerPath(ec, judgedFile)])
        }
        return out
    }

    /**
     * A side's captured extract and its ".excluded.json" sidecar as the extractor wrote it (copyExtractorSideFile), copied into
     * <replay>/extracts/<side>/; answers the copy's [fileLocation, fileName]. The capture's Keep artifacts (.keep.json,
     * .set-aside.rows) are never copied: the replay's own compare writes them for a side it keeps in Spark, so a side it does not
     * keep never reads a capture's Keep as its own (Task 10 review M3). The exchange manifest is not copied: neither the compare,
     * the conclude pass nor the judge reads it.
     */
    static Map copyExtract(def ec, Map picked, File folder, String folderLocation, String side) {
        String fileName = picked.fileName as String
        File source = darpan.facade.reconciliation.RunVerificationSupport.resolveLocationFile(ec, picked.fileLocation as String)
        if (source == null || !source.isFile()) throw new IllegalStateException("The ${side} extract ${fileName} is not on disk".toString())
        File dir = new File(new File(folder, EXTRACTS_DIR), side)
        DataManagerSupport.ensureDirectory(dir, "judge replay")
        Files.copy(source.toPath(), new File(dir, fileName).toPath())
        copyExtractorSideFile(source, dir)
        String location = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec),
                "${folderLocation}/${EXTRACTS_DIR}/${side}/${fileName}".toString())
        return [fileLocation: location, fileName: fileName]
    }

    private static final JsonFactory JSON = new JsonFactory()


    /**
     * The extract's side file, as its extractor wrote it, into dir. A side file a Spark Keep merged its set-aside records into
     * says so in the extract's .keep.json (KeepStepSupport.EXTRACTOR_SIDE_FILE: the extractor's part is the first `kept` entries,
     * with its own total and truncation): only that part is copied, token by token so numbers keep their text, and a side file
     * that was Spark's alone is not copied. Otherwise the whole file is the extractor's and is copied byte for byte. Copying
     * Spark's entries too would double them when the replay's own Keep merges its records in again.
     */
    static void copyExtractorSideFile(File extract, File dir) {
        File side = new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
        if (!side.isFile()) return
        File target = new File(dir, side.name)
        Map mark = (Map) KeepArtifacts.readSummary(extract)?.get(KeepStepSupport.EXTRACTOR_SIDE_FILE)
        if (mark == null) {
            Files.copy(side.toPath(), target.toPath())
            return
        }
        int total = (mark.total ?: 0) as int
        if (total == 0) return
        int kept = (mark.kept ?: 0) as int
        JsonParser parser = JSON.createParser(side)
        try {
            target.withWriter("UTF-8") { Writer writer ->
                JsonGenerator out = JSON.createGenerator(writer)
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalStateException("Side file ${side.name} is not a JSON object.")
                out.writeStartObject()
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName()
                    JsonToken value = parser.nextToken()
                    if (name == "total") {
                        out.writeNumberField(name, total)
                    } else if (name == "truncated") {
                        out.writeBooleanField(name, mark.truncated == true)
                    } else if (name == "records" && value == JsonToken.START_ARRAY) {
                        out.writeArrayFieldStart(name)
                        int copied = 0
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (parser.currentToken() == JsonToken.START_OBJECT && copied < kept) {
                                copyExact(parser, out)
                                copied++
                            } else {
                                parser.skipChildren()
                            }
                        }
                        out.writeEndArray()
                    } else {
                        out.writeFieldName(name)
                        copyExact(parser, out)
                    }
                }
                out.writeEndObject()
                out.close()
            }
        } finally {
            parser.close()
        }
    }

    /** The value at the parser's current token, copied whole; a number keeps the text it was written with. */
    private static void copyExact(JsonParser parser, JsonGenerator out) {
        int depth = 0
        while (true) {
            JsonToken token = parser.currentToken()
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) out.writeNumber(parser.getText())
            else out.copyCurrentEvent(parser)
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
            else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
            if (depth == 0) return
            parser.nextToken()
        }
    }

    /**
     * The lookups captured in the run folder (<run>/lookups/lookup-<name>-<millis>.json), copied into <replay>/lookups/, the only
     * place a replay's lookups and asks are answered from. Today's live lookups do not write there yet, so a run captured before
     * they do replays every lookup as not captured.
     */
    static void copyCapturedLookups(File runDir, File folder) {
        File captured = runDir != null ? new File(runDir, LOOKUPS_DIR) : null
        File target = new File(folder, LOOKUPS_DIR)
        DataManagerSupport.ensureDirectory(target, "judge replay")
        (captured?.isDirectory() ? (captured.listFiles() ?: []) : []).each { File f ->
            if (f.isFile() && CAPTURED_LOOKUP.matcher(f.name).matches()) Files.copy(f.toPath(), new File(target, f.name).toPath())
        }
    }

    /**
     * What the replay could not answer, once each: the conclude pass's lookups not captured, every brain question that went
     * unanswered (a default-lookup question has no lookup in a replay, review N-M4), and every warning an ask gave.
     */
    static List<String> replayWarnings(Set<String> lookupsNotCaptured, Map brainSummary, List<String> judgeWarnings) {
        Set<String> out = new LinkedHashSet<>(lookupsNotCaptured ?: [])
        ((List) (brainSummary?.warnings ?: [])).each { out.add("Brain: ${it}".toString()) }
        (judgeWarnings ?: []).findAll { it?.startsWith("Ask ") }.each { out.add(it) }
        return out as List<String>
    }

    /** judge-replays/<checkId>/<stamp> under the data manager root, never an existing folder. */
    static File newReplayFolder(def ec, String checkId) {
        String base = DataManagerSupport.childLocation(DataManagerSupport.childLocation(
                DataManagerSupport.resolveDataManagerLocation(ec), REPLAYS_PATH), DataManagerSupport.safeToken(checkId, "check"))
        File parent = DataManagerSupport.resolveDirectoryFile(ec, base, true)
        String stamp = DataManagerSupport.formatRunTimestamp(ec)
        File folder = new File(parent, stamp)
        int n = 2
        while (!folder.mkdir()) {
            if (n > 1000) throw new IllegalStateException("No free replay folder under ${base}".toString())
            folder = new File(parent, "${stamp}-${n++}")
        }
        return folder
    }

    // ------------------------------------------------------------------------------------------------ the comparison

    /**
     * Old and judged findings keyed by statementCode + recordKey. same: the keys both hold. A record whose remaining findings
     * name other statements on each side is one differentStatement entry [recordKey, oldStatementCodes, judgedStatementCodes,
     * oldFindings, judgedFindings]; the rest are onlyOld and onlyJudged. Records in sorted order.
     */
    static Map compare(List<Map> oldFindings, List<Map> judgedFindings) {
        Closure<String> keyOf = { Map f -> "${f.statementCode}|${f.recordKey}".toString() }
        Set<String> judgedKeys = (judgedFindings ?: []).collect(keyOf) as Set<String>
        Set<String> oldKeys = (oldFindings ?: []).collect(keyOf) as Set<String>
        List<Map> oldLeft = (oldFindings ?: []).findAll { !judgedKeys.contains(keyOf(it)) }
        List<Map> judgedLeft = (judgedFindings ?: []).findAll { !oldKeys.contains(keyOf(it)) }
        int same = (oldKeys.findAll { judgedKeys.contains(it) }).size()
        Map<String, List<Map>> oldByRecord = new TreeMap<>(oldLeft.groupBy { it.recordKey as String })
        Map<String, List<Map>> judgedByRecord = new TreeMap<>(judgedLeft.groupBy { it.recordKey as String })
        List<Map> differentStatement = []
        List<Map> onlyOld = []
        List<Map> onlyJudged = []
        oldByRecord.each { String recordKey, List<Map> olds ->
            List<Map> news = judgedByRecord.get(recordKey)
            if (news) {
                differentStatement.add([recordKey: recordKey, oldStatementCodes: olds*.statementCode.unique().sort(),
                        judgedStatementCodes: news*.statementCode.unique().sort(), oldFindings: olds, judgedFindings: news])
            } else {
                onlyOld.addAll(olds)
            }
        }
        judgedByRecord.each { String recordKey, List<Map> news -> if (!oldByRecord.containsKey(recordKey)) onlyJudged.addAll(news) }
        return [same: same, onlyOld: onlyOld, onlyJudged: onlyJudged, differentStatement: differentStatement]
    }

    private static Map call(def ec, String serviceName, Map params, String what) {
        Map out = ec.service.sync().name(serviceName).parameters(params).call() as Map
        if (ec.message.hasError()) throw new IllegalStateException("${what}: ${ec.message.errorsString}".toString())
        return out ?: [:]
    }
}
