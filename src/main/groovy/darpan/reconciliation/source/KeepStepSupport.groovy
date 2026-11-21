package darpan.reconciliation.source

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.core.JsonRecordLines
import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel

import java.nio.file.Files
import java.nio.file.StandardCopyOption

import static darpan.common.ValueSupport.normalize
import static org.apache.spark.sql.functions.col

/**
 * DAR-BE-318 (plan 3b-2b, Task 10). The Keep step at ingest: Spark runs a side's Keep rules over its records file, splits the
 * records into kept and set aside, and writes the set-aside artifact (KeepArtifacts) beside the extract. The extract keeps
 * every record; the artifact says which were set aside, so downstream readers skip them (Ruling 3b2b-11).
 *
 * The artifact, for a side whose plan placed Keep in Spark (KeepPlanSupport.SPARK) with at least one rule:
 * <ul>
 * <li>{@code .set-aside.rows}: one row per set-aside record, ascending; none when nothing was set aside.</li>
 * <li>{@code .keep.json}: always, with configuredExclusions always a list (every TENANT rule, zero counts included; [] when the
 * side has none), since the conclude pass reads a summary as authoritative.</li>
 * <li>{@code .excluded.json}: only when the side's connector says excludedRecordsSidecar = Y (sideKeep.writeSidecar), which is
 * where the extractor writes one today (Ruling 3b2b-3), and only when something was set aside. A side file the extractor
 * already wrote is kept and Spark's records are merged into it (writeArtifact).</li>
 * </ul>
 * Set-aside records are the extract's own records, so they carry exactly the projection kept ones do. No system name here.
 */
class KeepStepSupport {

    /** The column holding a record's row when the rules name no field: only there so set-aside rows are numbered. */
    static final String ROW_ONLY_COLUMN = KeepRules.INJECTED_PREFIX + "row"
    /** The summary key a Keep that wrote the side file leaves: the extractor's part of it, [total, kept, truncated]. */
    static final String EXTRACTOR_SIDE_FILE = "extractorSideFile"

    private static final JsonFactory JSON = new JsonFactory()

    /** True when the side's plan places Keep in Spark and has rules to run; anything else is today's ingest. */
    static boolean applies(Map sideKeep) {
        return sideKeep != null && KeepPlanSupport.SPARK == sideKeep.get("placement") && (sideKeep.get("rules") as List)
    }

    /**
     * The Keep text columns the records file carries for these rules (KeepRules.injections). Rules that name no field (every
     * line judged on the driver, a constant) still need each record's row, which the records file writes only beside an
     * injection, so they get one row-only column that no rule reads.
     */
    static List<Map> injections(List<Map> rules) {
        List<Map> out = KeepRules.injections(rules)
        return out ?: [[column: ROW_ONLY_COLUMN, field: KeepRules.ROW_COLUMN, text: "GROOVY"] as Map]
    }

    /**
     * Runs the side's Keep rules over df (a records file read with injections(rules)) and writes the artifact beside extract.
     * Returns kept and aside without the injected, row and reason columns, the summary written, and the persisted frame for the
     * caller to unpersist. On a failure the persisted frame is released here.
     */
    static Map apply(Dataset df, Map sideKeep, File extract) {
        List<Map> rules = (List<Map>) sideKeep.get("rules")
        Map<String, String> ruleIdOf = ruleIdsByReason(rules)
        Map previous = KeepArtifacts.readSummary(extract)
        Dataset annotated = KeepColumnSupport.annotate(df, rules).persist(StorageLevel.DISK_ONLY())
        try {
            if (!(annotated.columns() as List).contains(KeepRules.ROW_COLUMN)) {
                throw new IllegalStateException("Keep on ${extract.name}: the records carry no ${KeepRules.ROW_COLUMN} column.")
            }
            Column reason = quoted(KeepRules.REASON_COLUMN)
            Iterator<Row> setAside = annotated.select(quoted(KeepRules.ROW_COLUMN), reason)
                    .where(reason.isNotNull()).orderBy(quoted(KeepRules.ROW_COLUMN)).toLocalIterator()
            KeepArtifacts.writeRows(extract, new Iterator<List>() {
                boolean hasNext() { return setAside.hasNext() }
                List next() {
                    Row row = setAside.next()
                    return [((Number) row.get(0)).longValue(), ruleIdOf.get(row.getString(1))]
                }
            })
            Map<String, Long> counts = [:]
            for (Row row : annotated.groupBy(reason).count().collectAsList()) {
                counts.put(row.isNullAt(0) ? null : row.getString(0), ((Number) row.get(1)).longValue())
            }
            Map summary = summary(extract, rules, counts)
            writeArtifact(extract, sideKeep, summary, previous)
            List<String> dropped = (annotated.columns() as List<String>).findAll { String name ->
                name.startsWith(KeepRules.INJECTED_PREFIX) || name == KeepRules.ROW_COLUMN || name == KeepRules.REASON_COLUMN
            }
            String[] drop = dropped as String[]
            return [kept     : annotated.where(reason.isNull()).drop(drop),
                    aside    : annotated.where(reason.isNotNull()).drop(drop),
                    summary  : summary,
                    persisted: [annotated]]
        } catch (Throwable failure) {
            annotated.unpersist()
            throw failure
        }
    }

    /**
     * The artifact for a side whose extract holds no record: a summary with zero counts, no rows file (a stale one is removed)
     * and no Spark side-file entries (an extractor's side file is left as the extractor wrote it). Returns the summary.
     */
    static Map applyToNoRecords(Map sideKeep, File extract) {
        Map previous = KeepArtifacts.readSummary(extract)
        Map summary = summary(extract, (List<Map>) sideKeep.get("rules"), [:])
        KeepArtifacts.writeRows(extract, Collections.<List> emptyIterator())
        writeArtifact(extract, sideKeep, summary, previous)
        return summary
    }

    /**
     * Writes the summary, then the side file (Ruling 3b2b-3, review I1). A side file the extractor wrote (a connector that still
     * drops records itself puts them there) holds records the extract lacks, so it is never replaced: Spark's set-aside records
     * are merged in after the extractor's own (ExcludedRecordsSidecar.merge keeps the cap, the true total and the truncation).
     *
     * Which part is the extractor's: the summary of a Keep that wrote the side file carries EXTRACTOR_SIDE_FILE, the extractor
     * part's [total, kept, truncated], whose entries come first in the file. With no such mark the whole file is the extractor's.
     * So a later Keep over the same extract (keptValues then ingest, a replay) takes back only that part: Spark's records are never
     * doubled, and a pass that now sets nothing aside puts the extractor's file back as it was (or removes a file only Keep wrote).
     */
    private static void writeArtifact(File extract, Map sideKeep, Map summary, Map previous) {
        boolean sparkWrites = sideKeep.get("writeSidecar") == true && KeepArtifacts.rowsFile(extract).isFile()
        Map ownedBefore = previous?.get(EXTRACTOR_SIDE_FILE) as Map
        Map part = (sparkWrites || ownedBefore != null) ? extractorPart(extract, ownedBefore) : null
        try {
            if (sparkWrites) summary.put(EXTRACTOR_SIDE_FILE, [total: part?.total ?: 0, kept: part?.kept ?: 0, truncated: part?.truncated == true])
            KeepArtifacts.writeSummary(extract, summary)
            if (!sparkWrites && ownedBefore == null) return
            Map merged = ExcludedRecordsSidecar.newCollector()
            try {
                if (part != null) ExcludedRecordsSidecar.merge(merged, part)
                if (sparkWrites) ExcludedRecordsSidecar.merge(merged, setAsideCollector(extract, summary))
                writeSideFile(extract, merged)
            } finally {
                ExcludedRecordsSidecar.discard(merged)
            }
        } finally {
            ExcludedRecordsSidecar.discard(part)
        }
    }

    /**
     * The extractor's part of the side file as a collector (one record per spill line, copied token by token so numbers keep their
     * text), or null when there is no side file. ownedBefore (the earlier Keep's mark) limits it to the first entries and gives its
     * total and truncation; without it the file's own are used. An unreadable side file throws, naming it.
     */
    private static Map extractorPart(File extract, Map ownedBefore) {
        File side = new File(extract.getParentFile(), ExcludedRecordsSidecar.fileNameFor(extract.getName()))
        if (!side.isFile()) return null
        Integer limit = ownedBefore != null ? ((ownedBefore.get("kept") ?: 0) as Integer) : null
        File spill = Files.createTempFile("keep-side-", ".jsonl").toFile()
        spill.deleteOnExit()
        int copied = 0
        Integer total = null
        boolean truncated = false
        JsonParser parser = JSON.createParser(side)
        try {
            spill.withWriter("UTF-8") { Writer out ->
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalStateException("Side file ${side.name} is not a JSON object.")
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName()
                    JsonToken value = parser.nextToken()
                    if (name == "records" && value == JsonToken.START_ARRAY) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (parser.currentToken() != JsonToken.START_OBJECT || (limit != null && copied >= limit)) {
                                parser.skipChildren()
                                continue
                            }
                            out.write(copyObject(parser))
                            out.write("\n")
                            copied++
                        }
                    } else if (name == "total" && value.isNumeric()) {
                        total = parser.getIntValue()
                    } else if (name == "truncated") {
                        truncated = value == JsonToken.VALUE_TRUE
                    } else {
                        parser.skipChildren()
                    }
                }
            }
        } catch (Throwable failure) {
            spill.delete()
            throw new IllegalStateException("Side file ${side.name} could not be read to keep its records: ${failure.message}", failure)
        } finally {
            parser.close()
        }
        return [total    : ownedBefore != null ? ((ownedBefore.get("total") ?: 0) as int) : (total != null ? total : copied),
                kept     : copied,
                truncated: ownedBefore != null ? ownedBefore.get("truncated") == true : truncated,
                spill    : spill,
                writer   : null]
    }

    /** One object, from its START_OBJECT, as JSON text; a number keeps the text it was written with. */
    private static String copyObject(JsonParser parser) {
        StringWriter text = new StringWriter()
        JsonGenerator generator = JSON.createGenerator(text)
        int depth = 0
        while (true) {
            JsonToken token = parser.currentToken()
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) generator.writeNumber(parser.getText())
            else generator.copyCurrentEvent(parser)
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
            else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
            if (depth == 0) break
            parser.nextToken()
        }
        generator.close()
        return text.toString()
    }

    /** Spark's set-aside records as side-file entries, _excludedBy "sequenceNum:label" from the summary's rules. */
    private static Map setAsideCollector(File extract, Map summary) {
        Map<String, Map> byId = [:]
        for (Object rule : (List) summary.get("rules")) byId.put(((Map) rule).get("id") as String, (Map) rule)
        Map collector = ExcludedRecordsSidecar.newCollector()
        try {
            KeepArtifacts.forEachSetAsideRecord(extract) { Map record, String ruleId ->
                Map rule = byId.get(ruleId)
                if (rule == null) throw new IllegalStateException("Set-aside row names unknown rule ${ruleId}: it is not in the summary's rules.")
                ExcludedRecordsSidecar.collect(collector, record, [sequenceNum: rule.get("sequenceNum"), fieldExpression: rule.get("label")])
            }
            return collector
        } catch (Throwable failure) {
            ExcludedRecordsSidecar.discard(collector)
            throw failure
        }
    }

    /** The merged entries as the side file, through a temp file and an atomic move; no entry at all removes the file. */
    private static void writeSideFile(File extract, Map merged) {
        File target = new File(extract.getParentFile(), ExcludedRecordsSidecar.fileNameFor(extract.getName()))
        if (((merged.get("total") ?: 0) as int) == 0) {
            target.delete()
            return
        }
        File work = File.createTempFile("excluded-", ".partial", extract.getParentFile())
        try {
            ExcludedRecordsSidecar.write(work, merged, extract.getName())
            Files.move(work.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            work.delete()
        }
    }

    /** True when the extract's records array holds at least one record; stops at the first. */
    static boolean holdsRecords(File extract) {
        try {
            JsonRecordStream.forEachRecord(extract, "records") { Map record -> throw new FirstRecord() }
            return false
        } catch (FirstRecord ignored) {
            return true
        }
    }

    /**
     * The trimmed, non-blank values of a top-level field on the extract's kept records, each once, in record order. With Spark
     * Keep the step runs first on its own session (the compare scope adapter's settings) and writes the artifact; the values
     * are then read from the extract the way the key readers read it, skipping the set-aside rows. With no Keep, every record.
     */
    static Set<String> keptValues(def ec, File extract, Map sideKeep, String field) {
        Set<String> values = new LinkedHashSet<String>()
        Closure collect = { Map record ->
            String value = normalize(record.get(field))
            if (value) values.add(value)
        }
        if (!applies(sideKeep)) {
            JsonRecordStream.forEachRecord(extract, "records", collect)
            return values
        }
        List<Map> rules = (List<Map>) sideKeep.get("rules")
        List<Map> pathInfos = [[needsExplode: true, arrayPath: "records", fieldPath: field] as Map]
        File lines = JsonRecordLines.forRecordArray(ec, extract.absolutePath, pathInfos, injections(rules))
        if (lines == null) {
            if (holdsRecords(extract)) throw new IllegalArgumentException("Keep on ${extract.name}: the extract's records array could not be read.")
            applyToNoRecords(sideKeep, extract)
            return values
        }
        Map applied = apply(session().read().json(lines.absolutePath), sideKeep, extract)
        try {
            KeepArtifacts.forEachKeptRecord(extract, collect)
        } finally {
            ((List<Dataset>) applied.persisted).each { Dataset frame -> frame.unpersist() }
        }
        return values
    }

    /** The embedded session, built as RuleSetCompareScopeAdapter builds it (getOrCreate returns the JVM's one session). */
    private static SparkSession session() {
        SparkSession spark = SparkSession.builder()
                .appName("KeepStep")
                .master("local[*]")
                .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                .config("spark.sql.adaptive.enabled", "true")
                .config("spark.ui.enabled", "false") // embedded batch use; Spark UI needs legacy javax.servlet, absent under Moqui 4 jakarta
                .getOrCreate()
        spark.conf().set("spark.sql.shuffle.partitions", (System.getProperty("darpan.reconciliation.spark.shufflePartitions") ?: "16"))
        return spark
    }

    /** "<rule id>|<reason>" to the rule id, for every test of every rule: the reason column split without guessing at a "|". */
    private static Map<String, String> ruleIdsByReason(List<Map> rules) {
        Map<String, String> out = [:]
        for (Map rule : rules) {
            for (Object test : (List) rule.get("tests")) out.put("${rule.get('id')}|${((Map) test).get('reason')}".toString(), rule.get("id") as String)
        }
        return out
    }

    private static Map summary(File extract, List<Map> rules, Map<String, Long> counts) {
        Map<String, Long> byRule = [:]
        Map<String, String> ruleIdOf = ruleIdsByReason(rules)
        long setAside = 0
        counts.each { String reason, Long count ->
            if (reason == null) return
            setAside += count
            String id = ruleIdOf.get(reason)
            byRule.put(id, (byRule.get(id) ?: 0L) + count)
        }
        Closure<Long> countOf = { String reason -> counts.get(reason) ?: 0L }
        return [sourceFileName      : extract.name,
                rules               : rules.collect { Map rule ->
                    [id: rule.get("id"), sequenceNum: rule.get("sequenceNum"), label: rule.get("label"), source: rule.get("source")]
                },
                configuredExclusions: rules.findAll { it.get("source") == "TENANT" }.collect { Map rule ->
                    [sequenceNum     : rule.get("sequenceNum"),
                     fieldExpression : rule.get("label"),
                     operator        : rule.get("operator"),
                     values          : new ArrayList((List) rule.get("values")),
                     excludedCount   : countOf("${rule.get('id')}|${SourceFilterSupport.REASON_VALUE}".toString()),
                     fieldAbsentCount: countOf("${rule.get('id')}|${SourceFilterSupport.REASON_FIELD_ABSENT}".toString())]
                },
                builtIn             : rules.findAll { it.get("source") == "BUILT_IN" }.collect { Map rule ->
                    [sequenceNum: rule.get("sequenceNum"), label: rule.get("label"), setAsideCount: byRule.get(rule.get("id") as String) ?: 0L]
                },
                keptCount           : counts.get(null) ?: 0L,
                setAsideCount       : setAside]
    }

    private static Column quoted(String name) {
        return col("`" + name + "`")
    }

    private static class FirstRecord extends RuntimeException {
        FirstRecord() { super(null, null, false, false) }
    }
}
