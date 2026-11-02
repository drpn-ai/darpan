package darpan.datasource

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * DAR-BE-176. The body of extract#DataDefinedSource: every data-defined endpoint's extract, for any system. The
 * endpoint is the caller's systemEnumId; its SourceSystemConnector row names the definition, the config entity, and
 * the names its config id, window and filters arrive under, so this class takes them as the row says and names no
 * system itself.
 *
 * Gate (as every coded extract): the config id is present, the config is the tenant's own or shared with it, the
 * endpoint is enabled for it, and it is not inactive. Then the definition runs (DataSourceRunner over GraphQlTransport)
 * by the run's lookup keys or its window, its records streamed into a work file that is moved into place only when the
 * whole read succeeded; excluded records go to the sidecar beside it, and each drop step's sidecar to
 * `<file>.<suffix>.json` ({manifest, truncated, sourceFileName}). Outputs are the extract contract's: fileName,
 * fileLocation, recordCount, dataAvailable, warnings, errors, requestMetadata.
 */
class DataDefinedExtract {

    static final String SERVICE_NAME = "reconciliation.DataDefinedExtractionServices.extract#DataDefinedSource"
    static final String DEFINITION_ENTITY = "darpan.datasource.DataSourceDefinition"
    static final long PROGRESS_MIN_INTERVAL_MS = 2000L

    /** How a run reaches the endpoint: the generic GraphQL transport. Replaced only by tests, to answer without a server. */
    static Closure transportFactory = { Map definition, Map connectionRecord -> new GraphQlTransport(definition, connectionRecord) }

    static void run(def ec, Map context) {
        context.put("warnings", [])
        context.put("errors", [])
        context.put("dataAvailable", false)
        context.put("recordCount", 0)
        Map prepared = prepare(ec, context)
        if (prepared.errors) { context.put("errors", prepared.errors); return }

        Map connector = (Map) prepared.connector
        Map definitionRow = (Map) prepared.definitionRow
        Map definition = (Map) prepared.definition
        String configId = (String) prepared.configId
        String timestamp = DataManagerSupport.formatRunTimestamp(ec)
        String outputBaseLocation = context.get("outputLocation") ?: DataManagerSupport.resolveReconciliationRunLocation(
                ec, context.get("automationExecutionId") ?: configId, timestamp)
        File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
        File workFile = outputDirectory != null ? File.createTempFile("data-defined-extract-", ".partial", outputDirectory)
                : File.createTempFile("data-defined-extract-", ".partial")
        DataSourceRunner runner = new DataSourceRunner(definition, transportFactory.call(definition, (Map) prepared.connectionRecord))
        try {
            Map result
            int written = 0
            workFile.withWriter("UTF-8") { Writer w ->
                w.write('{"records":[')
                result = runner.run((Map) prepared.request, { List<Map> batch ->
                    batch.each { Map r ->
                        if (written++ > 0) w.write(",")
                        w.write(JsonOutput.toJson(r))
                    }
                }, progressListener(ec, context))
                Map metadata = requestMetadata(connector, definitionRow, definition, prepared, result, written)
                context.put("requestMetadata", metadata)
                w.write('],"metadata":')
                w.write(JsonOutput.toJson(metadata))
                w.write("}")
            }
            context.put("recordCount", written)
            context.put("dataAvailable", true)
            context.put("warnings", warningsOf(definition, (Map) result.counts))
            String fileName = safeFileName(context.get("fileName"),
                    ((Map) prepared.request).mode == "all" ? "${(connector.systemEnumId as String).toLowerCase()}.json".toString() :
                    "${(connector.systemEnumId as String).toLowerCase()}-${prepared.fromMillis ?: 'keys'}-${prepared.thruMillis ?: 'keys'}.json")
            String fileLocation = DataManagerSupport.childLocation(outputBaseLocation, fileName)
            DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation)
            context.put("fileName", fileName)
            context.put("fileLocation", fileLocation)
            try {
                ExcludedRecordsSidecar.writeBeside(ec, runner.excludedCollector, outputBaseLocation, fileName, outputDirectory)
            } catch (Exception sidecarError) {
                context.put("warnings", ((List) context.get("warnings")) + ["Excluded-records sidecar not written: ${sidecarError.message}".toString()])
            }
            writeDropSidecars(ec, context, (List<Map>) (result.sidecars ?: []), outputBaseLocation, fileName, outputDirectory)
        } catch (Exception e) {
            String code = e instanceof DataSourceCallException ? "[${((DataSourceCallException) e).code}] " : ""
            context.put("errors", ["${connector.systemEnumId} read failed: ${code}${e.message}".toString()])
            context.put("dataAvailable", false)
            context.put("recordCount", 0)
            context.put("fileName", null)
            context.put("fileLocation", null)
        } finally {
            if (workFile.exists()) workFile.delete()
            // The excluded-records spill holds customer records: gone whether the sidecar was written or not.
            ExcludedRecordsSidecar.discard(runner.excludedCollector)
        }
    }

    /**
     * Each drop step's sidecar beside the extract, as `<file without .json>.<suffix>.json`. Advisory, as the coded sidecars
     * are: the extract has already succeeded, so a failure is a warning. A drop that left nothing out writes nothing.
     */
    private static void writeDropSidecars(def ec, Map context, List<Map> sidecars, String outputBaseLocation, String fileName,
                                          File outputDirectory) {
        sidecars.findAll { it.entries }.each { Map sc ->
            File work = null
            try {
                String name = fileName.replaceAll(/(?i)\.json$/, "") + ".${sc.suffix}.json"
                work = outputDirectory != null ? File.createTempFile("data-defined-sidecar-", ".partial", outputDirectory)
                        : File.createTempFile("data-defined-sidecar-", ".partial")
                work.setText(JsonOutput.toJson([manifest: sc.entries, truncated: sc.truncated == true, sourceFileName: fileName]), "UTF-8")
                DataManagerSupport.moveIntoLocation(ec, work, DataManagerSupport.childLocation(outputBaseLocation, name))
            } catch (Exception e) {
                context.put("warnings", ((List) context.get("warnings")) + ["The ${sc.suffix} sidecar was not written: ${e.message}".toString()])
            } finally {
                if (work != null && work.exists()) work.delete()
            }
        }
    }

    /** Everything checked before any call: [errors] or the connector, definition, config record and run request. */
    static Map prepare(def ec, Map context) {
        String systemEnumId = context.get("systemEnumId")?.toString()?.trim()
        if (!systemEnumId) return [errors: ["A data-defined extract needs the systemEnumId it reads."]]
        Map connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        String definitionId = connector?.dataSourceDefinitionId as String
        if (connector == null || !definitionId || connector.extractServiceName != SERVICE_NAME) {
            return [errors: ["${systemEnumId} is not a data-defined endpoint.".toString()]]
        }
        def row = ec.entity.find(DEFINITION_ENTITY).condition("dataSourceDefinitionId", definitionId).useCache(false).one()
        if (row == null || row.statusId == "RETIRED") return [errors: ["The definition ${definitionId} is missing or retired.".toString()]]

        String configEntity = connector.configEntityName as String
        Map type = SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY.find { k, v -> v.entityName == configEntity }?.with { [id: it.key] + it.value }
        if (type == null) return [errors: ["${systemEnumId} names a config entity Darpan does not know.".toString()]]
        String configParam = (connector.configParameterName ?: type.pkField) as String
        String configId = (context.get(configParam) ?: context.get("sourceConfigId"))?.toString()?.trim()
        if (!configId) return [errors: ["${systemEnumId} needs ${configParam}.".toString()]]
        def config = TenantScopedFinder.findGlobalUnscoped(ec, configEntity,
                "config named by explicit PK for a data-defined extract; owner-or-shared checked next (DAR-BE-005)")
                .condition((String) type.pkField, configId).useCache(false).one()
        String tenant = context.get("companyUserGroupId")?.toString()?.trim()
        boolean usable = tenant ? SharedConfigAccessSupport.canTenantUseConfig(ec, (String) type.id, config, tenant)
                : SharedConfigAccessSupport.canActiveTenantUseConfig(ec, (String) type.id, config)
        if (!usable) return [errors: ["${type.label} ${configId} not found.".toString()]]
        if (!darpan.reconciliation.automation.SourceEndpointAccessSupport.isEndpointEnabled(ec, (String) type.id, configId, systemEnumId)) {
            return [errors: ["${type.label} ${configId} is not enabled for ${systemEnumId}.".toString()]]
        }
        def configDefinition = ec.entity.getEntityDefinition(configEntity)
        if (configDefinition.isField("isActive") && "N".equalsIgnoreCase(config.get("isActive")?.toString())) {
            return [errors: ["${type.label} ${configId} is inactive.".toString()]]
        }

        Map definition
        try {
            definition = DataSourceDefinitionSupport.parse((String) row.protocol, (String) row.definitionJson,
                    configDefinition.getAllFieldNames())
        } catch (IllegalArgumentException e) {
            return [errors: ["The definition ${definitionId} cannot run: ${e.message}".toString()]]
        }

        List<String> errors = []
        Map request
        Long fromMillis = null, thruMillis = null
        Object keys = context.get("lookupKeys")
        List<String> optionErrors = optionMismatch(definition, connector.extractOptionsJson as String)
        if (optionErrors) return [errors: optionErrors]
        Map options = ((List<Map>) (definition.options ?: [])).collectEntries { Map o -> [(o.name): context.get(o.name as String)] }
                .findAll { k, v -> v != null }
        Object keep = context.get("keepRecordFields")
        List keepList = keep instanceof Collection ? new ArrayList((Collection) keep) : null
        List<Map<String, Object>> filters
        try {
            Object rawFilters = connector.filterParameterName ? context.get(connector.filterParameterName) : null
            filters = SourceFilterSupport.parseRules(rawFilters instanceof Collection ? rawFilters : null)
        } catch (IllegalArgumentException e) {
            return [errors: [e.message]]
        }
        String mode = modeFor(definition, keys instanceof Collection && !((Collection) keys).isEmpty(),
                context.get((connector.dateFromParameterName ?: "windowStart") as String) != null)
        if (mode == "all") {
            // A whole read: no window, no keys (the coded whole-catalogue reads ignore both too; requestMetadata says so).
            request = [mode: "all", filters: filters, keep: keepList, options: options]
        } else if (mode == "ids") {
            request = [mode: "ids", keys: new ArrayList((Collection) keys), keyField: context.get("lookupKeyField"), filters: filters, keep: keepList, options: options]
        } else {
            fromMillis = windowMillis(context.get((connector.dateFromParameterName ?: "windowStart") as String), "The window start", errors)
            thruMillis = windowMillis(context.get((connector.dateToParameterName ?: "windowEnd") as String), "The window end", errors)
            if (errors) return [errors: errors]
            if (thruMillis <= fromMillis) return [errors: ["The window ends before it starts."]]
            request = [mode: "window", from: fromMillis, thru: thruMillis, filters: filters, keep: keepList, options: options]
        }
        Map record = [:]
        configDefinition.getAllFieldNames().each { String f -> record.put(f, config.get(f)) }
        return [connector: connector, definitionRow: [id: definitionId, version: row.definitionVersion, protocol: row.protocol],
                definition: definition, configId: configId, connectionRecord: record, request: request,
                fromMillis: fromMillis, thruMillis: thruMillis]
    }

    /**
     * The definition's options must be the connector's extractOptionsJson (the switches a caller is offered): same names,
     * each a boolean. Words for a mismatch, else none.
     */
    static List<String> optionMismatch(Map definition, String extractOptionsJson) {
        Map<String, String> declared = ((List<Map>) (definition.options ?: [])).collectEntries { [(it.name.toString()): it.type?.toString()] }
        Map<String, String> offered = [:]
        if (extractOptionsJson?.trim()) {
            Object parsed
            try { parsed = new groovy.json.JsonSlurper().parseText(extractOptionsJson) } catch (Exception ignored) { parsed = null }
            if (!(parsed instanceof List)) return ["The connector's extractOptionsJson is not a list of options."]
            ((List) parsed).each { if (it instanceof Map && ((Map) it).name) offered.put(((Map) it).name.toString(), ((Map) it).type?.toString()) }
        }
        if (declared.keySet() != offered.keySet() || offered.values().any { it?.toLowerCase() != "boolean" } ) {
            return ["The definition's options ${declared.keySet().sort()} are not the connector's ${offered.keySet().sort()} (each a boolean).".toString()]
        }
        return []
    }

    /**
     * How a run reads, from what the first step allows: by ids when keys are given and it reads by ids; by window when it
     * reads by window (the window check then asks for one); else whole when it reads with bind.all; else by ids.
     */
    static String modeFor(Map definition, boolean hasKeys, boolean hasWindow) {
        Map bind = (Map) (((Map) ((List) definition.steps)[0]).bind ?: [:])
        if (hasKeys && bind.ids) return "ids"
        if (bind.window) return "window"
        if (bind.all) return "all"
        return "ids"
    }

    private static Map requestMetadata(Map connector, Map definitionRow, Map definition, Map prepared, Map result, int written) {
        Map request = (Map) prepared.request
        List<Map<String, Object>> rules = (List<Map<String, Object>>) (request.filters ?: [])
        Map filters = [:]
        if (rules) {
            filters.configuredExclusions = rules.collect { Map<String, Object> rule -> [
                    sequenceNum     : rule.sequenceNum,
                    fieldExpression : rule.fieldExpression,
                    operator        : rule.operator,
                    values          : new ArrayList<String>((List) (rule.values ?: [])),
                    excludedCount   : (((Map) result.excludedByRule).get(rule.sequenceNum) ?: 0),
                    fieldAbsentCount: (((Map) result.fieldAbsentByRule).get(rule.sequenceNum) ?: 0)] }
        }
        Map metadata = [
                transport              : definitionRow.protocol,
                sourceType             : "DATA_DEFINED",
                dataSourceDefinitionId : definitionRow.id,
                definitionVersion      : definitionRow.version,
                systemEnumId           : connector.systemEnumId,
                sourceConfigId         : prepared.configId,
                windowStartEpochMillis : prepared.fromMillis,
                windowEndEpochMillis   : prepared.thruMillis,
                lookupKeyCount         : request.mode == "ids" ? ((List) request.keys).size() : null,
                extractedRecordCount   : written,
                pagination             : [strategy: "DATA_DEFINED", rootRecords: result.rootRecords, batches: result.batches,
                                          pages: result.pages, pageSizes: result.pageSizes],
                filters                : filters,
        ]
        // The definition's fixed values, then its counts, under their own names (the coded extract's keys).
        metadata.putAll((Map) (result.metadata ?: [:]))
        metadata.putAll((Map) (result.counts ?: [:]))
        return metadata
    }

    static List<String> warningsOf(Map definition, Map counts) {
        return ((List<Map>) (definition.warnings ?: [])).findAll { ((counts?.get(it.count) ?: 0) as long) > 0 }
                .collect { Map w -> w.text.toString().replace("{n}", counts.get(w.count).toString()) }
    }

    private static Closure progressListener(def ec, Map context) {
        String runId = context.get("reconciliationRunResultId")?.toString()?.trim()
        if (!runId) return null
        String stage = context.get("progressStageCode")?.toString()?.trim() ?: RunObservability.STAGE_EXTRACT_FILE2
        Integer expected = null
        try { expected = context.get("expectedRecordCount") != null ? (context.get("expectedRecordCount") as Integer) : null } catch (Exception ignored) { }
        long last = 0L
        return { Object count ->
            long now = System.currentTimeMillis()
            if (now - last < PROGRESS_MIN_INTERVAL_MS) return
            last = now
            RunObservability.heartbeatStageProgress(ec, runId, stage, count, expected != null && expected > 0 ? expected : null)
        }
    }

    static Long windowMillis(Object raw, String label, List<String> errors) {
        if (raw instanceof Number) return ((Number) raw).longValue()
        if (raw instanceof Date) return ((Date) raw).time
        if (raw instanceof Instant) return ((Instant) raw).toEpochMilli()
        if (raw instanceof OffsetDateTime) return ((OffsetDateTime) raw).toInstant().toEpochMilli()
        if (raw instanceof LocalDateTime) return ((LocalDateTime) raw).toInstant(ZoneOffset.UTC).toEpochMilli()
        if (raw instanceof LocalDate) return ((LocalDate) raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        String s = raw?.toString()?.trim()
        if (!s) { errors << "${label} is required.".toString(); return null }
        if (s ==~ /-?\d+/) return Long.parseLong(s)
        List<Closure<Long>> parsers = [
                { String v -> Instant.parse(v).toEpochMilli() },
                { String v -> OffsetDateTime.parse(v).toInstant().toEpochMilli() },
                { String v -> Timestamp.valueOf(v).time },
                { String v -> LocalDateTime.parse(v).toInstant(ZoneOffset.UTC).toEpochMilli() },
                { String v -> LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
        ]
        for (Closure<Long> p : parsers) { try { return p.call(s) } catch (Exception ignored) { } }
        errors << "${label} must be a timestamp, an ISO-8601 value or epoch milliseconds.".toString()
        return null
    }

    static String safeFileName(Object raw, String fallback) {
        String name = raw?.toString()?.trim()
        name = name ? name.tokenize("/\\").last() : fallback
        name = name.replaceAll(/[^A-Za-z0-9._-]/, "_").replaceAll(/^\.+/, "")
        if (!name) name = fallback
        return name.toLowerCase().endsWith(".json") ? name : "${name}.json"
    }
}
