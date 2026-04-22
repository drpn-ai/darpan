import java.util.zip.CRC32

String normalize(Object value) {
    return value?.toString()?.trim()
}

boolean normalizeBool(Object value, boolean defaultValue) {
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (Boolean) value
    String normalized = normalize(value)?.toLowerCase()
    if (!normalized) return defaultValue
    return ["true", "y", "yes", "1", "on"].contains(normalized)
}

String sanitizeIdToken(Object rawValue, String fallbackPrefix) {
    String cleaned = normalize(rawValue)?.toUpperCase()?.replaceAll(/[^A-Z0-9_]/, "_")
    cleaned = cleaned?.replaceAll(/_+/, "_")?.replaceAll(/^_+|_+$/, "")
    if (!cleaned) cleaned = fallbackPrefix
    if (!(cleaned[0] ==~ /[A-Z]/)) cleaned = "${fallbackPrefix}_${cleaned}"
    return cleaned
}

String stableHash(String rawValue) {
    CRC32 crc = new CRC32()
    byte[] bytes = (rawValue ?: "").getBytes("UTF-8")
    crc.update(bytes, 0, bytes.length)
    return String.format("%08X", crc.value)
}

String buildDeterministicId(String prefix, String sourceId) {
    String cleanSource = sanitizeIdToken(sourceId, "MAP")
    String hash = stableHash(sourceId)
    int maxBaseLength = Math.max(1, 60 - prefix.length() - hash.length() - 2)
    if (cleanSource.length() > maxBaseLength) cleanSource = cleanSource.substring(0, maxBaseLength)
    return "${prefix}_${cleanSource}_${hash}"
}

String inferObjectType(def mapping, List members) {
    String combined = [
            normalize(mapping?.mappingName),
            normalize(mapping?.description),
            members.collect { normalize(it?.systemFieldName) }.findAll { it }.join(" ")
    ].findAll { it }.join(" ").toLowerCase()

    if (combined.contains("order line") || combined.contains("line item") || combined.contains("orderline")) return "ORDER_LINE"
    if (combined.contains("inventory") || combined.contains("stock")) return "INVENTORY_ITEM"
    if (combined.contains("product") || combined.contains("sku") || combined.contains("variant")) return "PRODUCT"
    if (combined.contains("shipment") || combined.contains("fulfillment")) return "SHIPMENT"
    if (combined.contains("customer")) return "CUSTOMER"
    if (combined.contains("payment")) return "PAYMENT"
    if (combined.contains("order")) return "ORDER"
    return "RECORD"
}

boolean valuesDiffer(Object left, Object right) {
    if (left == null && right == null) return false
    return normalize(left) != normalize(right)
}

Map<String, String> normalizeParamMap(Map rawMap) {
    Map<String, String> normalized = new LinkedHashMap<>()
    (rawMap ?: [:]).each { key, value ->
        String keyToken = normalize(key)
        String valueToken = normalize(value)
        if (keyToken && valueToken) normalized[keyToken] = valueToken
    }
    return normalized
}

Map<String, Object> upsertConfigEntity(def ec, boolean dryRunValue, String entityName, Map<String, Object> pkFields,
                                       Map<String, Object> desiredFields) {
    def existing = ec.entity.find(entityName)
            .condition(pkFields)
            .disableAuthz()
            .useCache(false)
            .one()
    if (existing == null) {
        if (!dryRunValue) {
            def value = ec.entity.makeValue(entityName)
            value.setAll(desiredFields.findAll { key, val -> val != null })
            value.create()
        }
        return [created: true, updated: false]
    }

    Map<String, Object> changedFields = [:]
    desiredFields.each { String fieldName, Object desiredValue ->
        if (valuesDiffer(existing.get(fieldName), desiredValue)) changedFields[fieldName] = desiredValue
    }
    if (changedFields && !dryRunValue) {
        existing.setAll(changedFields)
        existing.update()
    }
    return [created: false, updated: !changedFields.isEmpty()]
}

Map<String, Object> buildDesiredJobParams(Map<String, String> currentParams, Map<String, Object> migrationPlan) {
    String mappingIdValue = normalize(currentParams.reconciliationMappingId)
    if (!mappingIdValue) {
        return [skipReason: "Job is missing reconciliationMappingId and cannot be migrated."]
    }

    String scopeFile1System = normalize(migrationPlan.file1SystemEnumId)
    String scopeFile2System = normalize(migrationPlan.file2SystemEnumId)
    String currentFile1System = normalize(currentParams.file1SystemEnumId)
    String currentFile2System = normalize(currentParams.file2SystemEnumId)
    if (!currentFile1System || !currentFile2System) {
        return [skipReason: "Job is missing file1SystemEnumId/file2SystemEnumId and cannot be safely rewritten."]
    }

    boolean swapSides
    if (currentFile1System == scopeFile1System && currentFile2System == scopeFile2System) {
        swapSides = false
    } else if (currentFile1System == scopeFile2System && currentFile2System == scopeFile1System) {
        swapSides = true
    } else {
        return [skipReason: "Job systems ${currentFile1System}/${currentFile2System} do not match migrated compare-scope systems ${scopeFile1System}/${scopeFile2System}."]
    }

    Map<String, String> desiredParams = new LinkedHashMap<>(currentParams)
    [
            ["file1SftpServerId", "file2SftpServerId"],
            ["file1RemotePath", "file2RemotePath"],
            ["file1FileTypeEnumId", "file2FileTypeEnumId"],
            ["file1SchemaFileName", "file2SchemaFileName"]
    ].each { List<String> pair ->
        String leftKey = pair[0]
        String rightKey = pair[1]
        String leftValue = normalize(currentParams[leftKey])
        String rightValue = normalize(currentParams[rightKey])
        if (swapSides) {
            if (rightValue) desiredParams[leftKey] = rightValue
            else desiredParams.remove(leftKey)
            if (leftValue) desiredParams[rightKey] = leftValue
            else desiredParams.remove(rightKey)
        } else {
            if (leftValue) desiredParams[leftKey] = leftValue
            else desiredParams.remove(leftKey)
            if (rightValue) desiredParams[rightKey] = rightValue
            else desiredParams.remove(rightKey)
        }
    }

    desiredParams.remove("reconciliationMappingId")
    desiredParams.ruleSetId = normalize(migrationPlan.ruleSetId)
    desiredParams.compareScopeId = normalize(migrationPlan.compareScopeId)
    desiredParams.file1SystemEnumId = scopeFile1System
    desiredParams.file2SystemEnumId = scopeFile2System

    return [
            desiredParams: normalizeParamMap(desiredParams),
            swapSides    : swapSides
    ]
}

Map<String, String> currentJobParamMap(def ec, String jobName) {
    Map<String, String> paramMap = new LinkedHashMap<>()
    ec.entity.find("moqui.service.job.ServiceJobParameter")
            .condition("jobName", jobName)
            .disableAuthz()
            .useCache(false)
            .list()
            ?.each { paramValue ->
                String name = normalize(paramValue.parameterName)
                String value = normalize(paramValue.parameterValue)
                if (name && value) paramMap[name] = value
            }
    return paramMap
}

void replaceJobParams(def ec, String jobName, Map<String, String> desiredParams) {
    ec.entity.find("moqui.service.job.ServiceJobParameter")
            .condition("jobName", jobName)
            .disableAuthz()
            .useCache(false)
            .list()
            ?.each { existingParam -> existingParam.delete() }

    desiredParams.each { String parameterName, String parameterValue ->
        def newValue = ec.entity.makeValue("moqui.service.job.ServiceJobParameter")
        newValue.set("jobName", jobName)
        newValue.set("parameterName", parameterName)
        newValue.set("parameterValue", parameterValue)
        newValue.create()
    }
}

boolean dryRunValue = normalizeBool(dryRun, true)
boolean rewriteJobsValue = normalizeBool(rewriteSftpJobParams, true)
String requestedMappingId = normalize(reconciliationMappingId)
List<String> warningsList = []

List mappings = ec.entity.find("darpan.mapping.ReconciliationMapping")
        .disableAuthz()
        .useCache(false)
        .orderBy("reconciliationMappingId")
        .list() ?: []
if (requestedMappingId) {
    mappings = mappings.findAll { normalize(it.reconciliationMappingId) == requestedMappingId }
    if (mappings.isEmpty()) {
        ec.message.addError("Mapping ${requestedMappingId} was not found.")
        return
    }
}

int mappingsScannedCount = 0
int ruleSetsCreatedCount = 0
int compareScopesCreatedCount = 0
int sourcesCreatedCount = 0
int jobsUpdatedCount = 0
Map<String, Map<String, Object>> migrationPlanByMappingId = new LinkedHashMap<>()

mappings.each { mapping ->
    String mappingIdValue = normalize(mapping.reconciliationMappingId)
    mappingsScannedCount++

    List members = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mappingIdValue)
            .disableAuthz()
            .useCache(false)
            .orderBy("mappingMemberId")
            .list() ?: []

    if (members.size() != 2) {
        warningsList.add("Mapping ${mappingIdValue} has ${members.size()} members and cannot be migrated to a two-sided compare scope.")
        return
    }
    if (members.any { !normalize(it.idFieldExpression) }) {
        warningsList.add("Mapping ${mappingIdValue} is missing idFieldExpression on one or more members and was not migrated.")
        return
    }

    String ruleSetIdValue = buildDeterministicId("RSMIG", mappingIdValue)
    String compareScopeIdValue = buildDeterministicId("CMPMIG", mappingIdValue)
    String objectTypeValue = inferObjectType(mapping, members)
    if (objectTypeValue == "RECORD") {
        warningsList.add("Mapping ${mappingIdValue} did not expose a clear object type; migrated compare scope uses safe default RECORD.")
    }

    String mappingNameValue = normalize(mapping.mappingName) ?: mappingIdValue
    String mappingDescription = normalize(mapping.description)
    String ruleSetDescription = "Migrated from Mapping ${mappingIdValue}" + (mappingDescription ? ": ${mappingDescription}" : "")
    String compareScopeDescription = mappingDescription ?: "Migrated compare scope for ${mappingNameValue}"

    Map<String, Object> ruleSetFields = [
            ruleSetId   : ruleSetIdValue,
            ruleSetName : mappingNameValue,
            description : ruleSetDescription,
            version     : "1.0",
            createdDate : mapping.createdDate
    ]
    Map<String, Object> ruleSetResult = upsertConfigEntity(ec, dryRunValue, "darpan.rule.RuleSet",
            [ruleSetId: ruleSetIdValue], ruleSetFields)
    if (ruleSetResult.created) ruleSetsCreatedCount++

    Map<String, Object> compareScopeFields = [
            compareScopeId: compareScopeIdValue,
            ruleSetId     : ruleSetIdValue,
            objectType    : objectTypeValue,
            description   : compareScopeDescription,
            createdDate   : mapping.createdDate
    ]
    Map<String, Object> compareScopeResult = upsertConfigEntity(ec, dryRunValue, "darpan.rule.RuleSetCompareScope",
            [compareScopeId: compareScopeIdValue], compareScopeFields)
    if (compareScopeResult.created) compareScopesCreatedCount++

    Map<String, Object> sourceBySide = [:]
    ["FILE_1", "FILE_2"].eachWithIndex { String fileSide, int index ->
        def member = members[index]
        Map<String, Object> sourceFields = [
                compareScopeId      : compareScopeIdValue,
                fileSide            : fileSide,
                systemEnumId        : normalize(member.systemEnumId),
                fileTypeEnumId      : normalize(member.fileTypeEnumId),
                schemaFileName      : normalize(member.schemaFileName),
                primaryIdExpression : normalize(member.idFieldExpression),
                idValueNormalizer   : normalize(member.idValueNormalizer),
                createdDate         : member.createdDate
        ]
        Map<String, Object> sourceResult = upsertConfigEntity(ec, dryRunValue, "darpan.rule.RuleSetCompareSource",
                [compareScopeId: compareScopeIdValue, fileSide: fileSide], sourceFields)
        if (sourceResult.created) sourcesCreatedCount++
        sourceBySide[fileSide] = sourceFields
    }

    migrationPlanByMappingId[mappingIdValue] = [
            mappingId         : mappingIdValue,
            mappingName       : mappingNameValue,
            ruleSetId         : ruleSetIdValue,
            compareScopeId    : compareScopeIdValue,
            objectType        : objectTypeValue,
            file1SystemEnumId : normalize(sourceBySide.FILE_1.systemEnumId),
            file2SystemEnumId : normalize(sourceBySide.FILE_2.systemEnumId)
    ]
}

if (rewriteJobsValue && !migrationPlanByMappingId.isEmpty()) {
    List sftpJobs = ec.entity.find("moqui.service.job.ServiceJob")
            .condition("serviceName", "reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile")
            .disableAuthz()
            .useCache(false)
            .list() ?: []

    sftpJobs.each { job ->
        String jobName = normalize(job.jobName)
        Map<String, String> currentParams = currentJobParamMap(ec, jobName)
        String mappingIdValue = normalize(currentParams.reconciliationMappingId)
        if (!mappingIdValue) return
        Map<String, Object> migrationPlan = migrationPlanByMappingId[mappingIdValue]
        if (migrationPlan == null) return

        Map<String, Object> desiredResult = buildDesiredJobParams(currentParams, migrationPlan)
        String skipReason = normalize(desiredResult.skipReason)
        if (skipReason) {
            warningsList.add("Job ${jobName} for mapping ${mappingIdValue} was not rewritten. ${skipReason}")
            return
        }

        Map<String, String> desiredParams = (Map<String, String>) desiredResult.desiredParams
        Map<String, String> normalizedCurrentParams = normalizeParamMap(currentParams)
        if (normalizedCurrentParams == desiredParams) return

        jobsUpdatedCount++
        if (!dryRunValue) replaceJobParams(ec, jobName, desiredParams)
    }
}

applied = !dryRunValue
mappingsScanned = mappingsScannedCount
ruleSetsCreated = ruleSetsCreatedCount
compareScopesCreated = compareScopesCreatedCount
sourcesCreated = sourcesCreatedCount
jobsUpdated = jobsUpdatedCount
warnings = warningsList.unique()
