package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test only. The made-up extracts of KeepLiveTestExtractionServices.xml: what a filtering extractor does today, over records a test
 * placed per config id. A record carrying {@code _builtInDrop} is dropped by the extractor itself (as NetSuite drops its unkeyed
 * payment lines) and set aside in its own side file; every other record is filtered by the sourceFilters it is sent
 * (SourceFilterSupport.evaluate, first rejecting rule). The side file is written only where the connector row says Y.
 * lookupKeys narrow the read to records whose lookupKeyField is listed.
 */
class KeepLiveTestExtracts {

    /** config id -> the records its source holds for the window. */
    static final Map<String, List<Map>> RECORDS = [:]
    /** Every call, its parameters as sent. */
    static final List<Map> CALLS = new CopyOnWriteArrayList<Map>()
    /** The rule the extractor's own drops are set aside under. */
    static final Map BUILT_IN_RULE = [sequenceNum: 0, fieldExpression: "built-in drop"]

    static Map extract(def ec, Map context) {
        Map params = new LinkedHashMap(context.findAll { k, v -> v != null && !(k in ["ec", "context", "result"]) })
        CALLS.add(params)
        String configId = context.projConfigId as String
        List<Map> source = (RECORDS.get(configId) ?: []).collect { new LinkedHashMap(it) }
        List keys = context.lookupKeys as List
        if (keys != null) {
            Set<String> wanted = keys.collect { it.toString() } as Set
            source = source.findAll { (it.get(context.lookupKeyField as String)?.toString()) in wanted }
        }
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(context.sourceFilters as List)
        Map collector = ExcludedRecordsSidecar.newCollector()
        Map<String, Integer> excluded = [:], absent = [:]
        List<Map> kept = []
        for (Map record : source) {
            if (record.remove("_builtInDrop") != null) {
                ExcludedRecordsSidecar.collect(collector, record, BUILT_IN_RULE)
                continue
            }
            Map verdict = rules ? SourceFilterSupport.evaluate(record, rules) : null
            if (verdict == null) {
                kept.add(record)
                continue
            }
            ExcludedRecordsSidecar.collect(collector, record, (Map) verdict.rule)
            String key = String.valueOf(((Map) verdict.rule).sequenceNum)
            Map<String, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.reason ? absent : excluded
            bucket.put(key, (bucket.get(key) ?: 0) + 1)
        }
        Map metadata = [systemEnumId: context.systemEnumId, windowStart: context.windowStart?.toString(), windowEnd: context.windowEnd?.toString()]
        if (rules) {
            metadata.filters = [configuredExclusions: rules.collect { Map rule ->
                String key = String.valueOf(rule.sequenceNum)
                [sequenceNum     : rule.sequenceNum, fieldExpression: rule.fieldExpression, operator: rule.operator,
                 values          : new ArrayList((List) rule.values), excludedCount: excluded.get(key) ?: 0,
                 fieldAbsentCount: absent.get(key) ?: 0]
            }]
        }
        String location = context.outputLocation as String
        String fileName = (context.fileName ?: "${configId}.json") as String
        File dir = DataManagerSupport.resolveDirectoryFile(ec, location, true)
        new File(dir, fileName).setText(JsonOutput.toJson([metadata: metadata, records: kept]), "UTF-8")
        String sidecar = SourceSystemConnectorSupport.resolve(ec, context.systemEnumId as String)?.excludedRecordsSidecar?.toString()
        try {
            if (sidecar?.toUpperCase() == "Y" && ((collector.total ?: 0) as int) > 0) {
                ExcludedRecordsSidecar.writeBeside(ec, collector, location, fileName, dir)
            }
        } finally {
            ExcludedRecordsSidecar.discard(collector)
        }
        return [fileLocation: DataManagerSupport.childLocation(location, fileName), fileName: fileName, recordCount: kept.size(), errors: []]
    }
}
