package darpan.reconciliation.conclusion

import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * DAR-UI-044. The rows a source filter dropped, kept beside the extract so the conclude pass can prove
 * what the run itself screened out. Run C's "Backordered in NetSuite" is exactly such a row: the
 * NetSuite line the run's own "quantityShipped EXCLUDE_IN 0" rule removed.
 *
 * Capped like the OMS exchange manifest. A cap is reported, never silent, because the conclude pass
 * must not read a truncated sidecar as "this record was not excluded".
 */
class ExcludedRecordsSidecar {

    static final int MAX_ENTRIES = 50000

    static String fileNameFor(String extractFileName) {
        String name = extractFileName ?: "extract"
        return name.toLowerCase().endsWith(".json")
                ? name.substring(0, name.length() - ".json".length()) + ".excluded.json"
                : name + ".excluded.json"
    }

    static Map newCollector() {
        return [records: [], truncated: false, total: 0]
    }

    /** The record is copied, never mutated: callers still hold it. */
    static void collect(Map collector, Map record, Map rule) {
        collector.total = ((collector.total ?: 0) as int) + 1
        List records = (List) collector.records
        if (records.size() >= MAX_ENTRIES) {
            collector.truncated = true
            return
        }
        Map copy = new LinkedHashMap(record ?: [:])
        copy.put("_excludedBy", "${rule?.get('sequenceNum')}:${rule?.get('fieldExpression')}".toString())
        records.add(copy)
    }

    /** Folds a page's collector into a run's, keeping the cap and the true total. */
    static void merge(Map into, Map from) {
        if (!from) return
        List records = (List) into.records
        for (Object record : ((List) (from.records ?: []))) {
            if (records.size() >= MAX_ENTRIES) {
                into.truncated = true
                break
            }
            records.add(record)
        }
        into.total = ((into.total ?: 0) as int) + ((from.total ?: 0) as int)
        into.truncated = into.truncated == true || from.truncated == true || records.size() < ((into.total ?: 0) as int)
    }

    static String toJson(Map collector, String sourceFileName) {
        return JsonOutput.toJson([sourceFileName: sourceFileName,
                                  truncated     : collector.truncated == true,
                                  total         : collector.total ?: 0,
                                  records       : collector.records ?: []])
    }

    static void write(File target, Map collector, String sourceFileName) {
        target.setText(toJson(collector, sourceFileName), "UTF-8")
    }

    /**
     * Writes the collector beside an extract the way the OMS exchange manifest is written: a temp file
     * in the output directory, then moved into the data-manager location. Nothing is written when no
     * row was excluded. Advisory by design — callers turn a failure into a warning, never a failed
     * extract, because the extract itself is complete without it.
     *
     * @return the sidecar's location, or null when there was nothing to write
     */
    static String writeBeside(def ec, Map collector, String outputBaseLocation, String outputFileName, File outputDirectory) {
        if (!collector || ((collector.total ?: 0) as int) == 0) return null
        File work = outputDirectory != null
                ? File.createTempFile("excluded-", ".partial", outputDirectory)
                : File.createTempFile("excluded-", ".partial")
        try {
            write(work, collector, outputFileName)
            String location = DataManagerSupport.childLocation(outputBaseLocation, fileNameFor(outputFileName))
            DataManagerSupport.moveIntoLocation(ec, work, location as String)
            return location
        } finally {
            if (work.exists()) work.delete()
        }
    }

    /** present:false when there is no sidecar or it cannot be read — never an empty "nothing excluded". */
    static Map read(File extractFile) {
        File sidecar = new File(extractFile.getParentFile(), fileNameFor(extractFile.getName()))
        if (!sidecar.exists()) return [present: false, truncated: false, records: []]
        try {
            Object parsed = new JsonSlurper().parse(sidecar, "UTF-8")
            if (!(parsed instanceof Map)) return [present: false, truncated: false, records: []]
            Map doc = (Map) parsed
            return [present: true, truncated: doc.truncated == true, records: (List) (doc.records ?: [])]
        } catch (Exception ignored) {
            return [present: false, truncated: false, records: []]
        }
    }
}
