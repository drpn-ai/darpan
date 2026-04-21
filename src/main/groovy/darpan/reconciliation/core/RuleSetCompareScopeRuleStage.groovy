package darpan.reconciliation.core

import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSetCompareScopeRuleStage {
    private static final Logger logger = LoggerFactory.getLogger(RuleSetCompareScopeRuleStage.class)
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final int DEFAULT_RULE_BATCH_SIZE = 500

    static Map<String, Object> reconcileRuleSetCompareScope(ExecutionContext ec) {
        Map<String, Object> context = (Map<String, Object>) ec.contextStack

        Map<String, Object> baseResult = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScopeBaseDiff")
                .parameters([
                        ruleSetId          : context.get("ruleSetId"),
                        compareScopeId     : context.get("compareScopeId"),
                        file1Location      : context.get("file1Location"),
                        file2Location      : context.get("file2Location"),
                        file1Name          : context.get("file1Name"),
                        file2Name          : context.get("file2Name"),
                        file1FileTypeEnumId: context.get("file1FileTypeEnumId"),
                        file2FileTypeEnumId: context.get("file2FileTypeEnumId"),
                        file1SchemaFileName: context.get("file1SchemaFileName"),
                        file2SchemaFileName: context.get("file2SchemaFileName"),
                        file1Label         : context.get("file1Label"),
                        file2Label         : context.get("file2Label"),
                        hasHeader          : context.get("hasHeader"),
                        sparkMaster        : context.get("sparkMaster"),
                        sparkAppName       : context.get("sparkAppName")
                ])
                .call()

        if (!baseResult || ec.message.hasError()) {
            return [:]
        }

        Dataset matchedPairDf = (Dataset) baseResult.matchedPairDf
        Dataset missingDiffDf = (Dataset) baseResult.missingDiffDf
        int ruleBatchSize = resolveRuleBatchSize(context.get("ruleBatchSize"))
        List<String> processingWarnings = toStringList(baseResult.processingWarnings)
        List<String> validationErrors = toStringList(baseResult.validationErrors)

        List<Map<String, Object>> ruleDiffRows = []
        int ruleCount = 0
        int firedRuleCount = 0
        String ruleSetId = ReconciliationServices.normalize(baseResult.ruleSetId)
        String compareScopeId = ReconciliationServices.normalize(baseResult.compareScopeId)
        String objectType = ReconciliationServices.normalize(baseResult.objectType)

        if (matchedPairDf != null && ((Number) baseResult.matchedPairCount).longValue() > 0L) {
            Iterator<String> rowIterator = matchedPairDf.toJSON().toLocalIterator()
            while (rowIterator.hasNext()) {
                List<Map<String, Object>> batch = nextBatch(rowIterator, ruleBatchSize)
                if (!batch) break

                Map<String, Object> ruleExec = ec.service.sync()
                        .name("reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs")
                        .parameters([
                                ruleSetId      : ruleSetId,
                                dataList       : batch,
                                returnAllFacts : false
                        ])
                        .call()

                processingWarnings.addAll(toStringList(ruleExec?.warnings))
                if (ruleExec?.error) {
                    processingWarnings.add(buildRuleStageWarning(ruleSetId, compareScopeId, batch, ruleExec.error))
                    logger.warn("RuleSet compare stage preserved base diffs after rule failure ruleSet={} compareScope={} error={}",
                            ruleSetId, compareScopeId, ruleExec.error)
                    break
                }

                ruleCount = Math.max(ruleCount, ((Number) (ruleExec?.ruleCount ?: 0)).intValue())
                firedRuleCount += ((Number) (ruleExec?.firedRuleCount ?: 0)).intValue()
                List<Map<String, Object>> batchDiffs = (List<Map<String, Object>>) (ruleExec?.diffResults ?: [])
                if (batchDiffs) ruleDiffRows.addAll(batchDiffs)
            }
        }

        Dataset referenceDf = matchedPairDf ?: missingDiffDf
        Dataset ruleDiffDf = ReconciliationServices.buildRuleSetDiffDataset(referenceDf, ruleDiffRows)
        Dataset normalizedMissingDiffDf = ReconciliationServices.convertMissingDiffToRuleSetDiffDataset(
                missingDiffDf,
                compareScopeId,
                objectType
        )
        if (normalizedMissingDiffDf == null) {
            normalizedMissingDiffDf = ReconciliationServices.emptyRuleSetDiffDataset(referenceDf)
        }
        Dataset diffDf = ReconciliationServices.unionByNameDatasets(normalizedMissingDiffDf, ruleDiffDf)
        long ruleDifferenceCount = ruleDiffRows.size()
        long missingObjectDifferenceCount = ((Number) baseResult.differenceCount).longValue()
        long totalDifferenceCount = missingObjectDifferenceCount + ruleDifferenceCount

        logger.info("Completed RuleSet compare stage: ruleSet={} compareScope={} missingDiffs={} ruleDiffs={} matchedPairs={} fired={}",
                ruleSetId, compareScopeId, missingObjectDifferenceCount, ruleDifferenceCount,
                ((Number) baseResult.matchedPairCount).longValue(), firedRuleCount)

        return [
                ruleSetId                 : ruleSetId,
                compareScopeId            : compareScopeId,
                objectType                : objectType,
                file1Type                 : baseResult.file1Type,
                file2Type                 : baseResult.file2Type,
                file1Label                : baseResult.file1Label,
                file2Label                : baseResult.file2Label,
                missingInFile1Count       : baseResult.missingInFile1Count,
                missingInFile2Count       : baseResult.missingInFile2Count,
                missingObjectDifferenceCount: missingObjectDifferenceCount,
                ruleDifferenceCount       : ruleDifferenceCount,
                differenceCount           : totalDifferenceCount,
                matchedPairCount          : baseResult.matchedPairCount,
                ruleCount                 : ruleCount,
                firedRuleCount            : firedRuleCount,
                diffDf                    : diffDf,
                missingDiffDf             : missingDiffDf,
                ruleDiffDf                : ruleDiffDf,
                matchedPairDf             : matchedPairDf,
                validationErrors          : validationErrors.unique(),
                processingWarnings        : processingWarnings.findAll { it }.unique()
        ]
    }

    private static List<Map<String, Object>> nextBatch(Iterator<String> rowIterator, int ruleBatchSize) {
        List<Map<String, Object>> batch = []
        while (rowIterator.hasNext() && batch.size() < ruleBatchSize) {
            String rowJson = rowIterator.next()
            batch.add((Map<String, Object>) JSON_SLURPER.parseText(rowJson))
        }
        return batch
    }

    private static int resolveRuleBatchSize(Object rawValue) {
        if (rawValue instanceof Number && ((Number) rawValue).intValue() > 0) {
            return ((Number) rawValue).intValue()
        }
        String normalized = ReconciliationServices.normalize(rawValue)
        if (!normalized) return DEFAULT_RULE_BATCH_SIZE
        try {
            int parsed = Integer.parseInt(normalized)
            return parsed > 0 ? parsed : DEFAULT_RULE_BATCH_SIZE
        } catch (Exception ignored) {
            return DEFAULT_RULE_BATCH_SIZE
        }
    }

    private static List<String> toStringList(Object rawValue) {
        if (!(rawValue instanceof List)) return []
        return ((List) rawValue).collect { Object value -> ReconciliationServices.normalize(value) }.findAll { it }
    }

    private static String buildRuleStageWarning(String ruleSetId, String compareScopeId, List<Map<String, Object>> batch, Object error) {
        String primaryIds = batch.collect { Map<String, Object> row -> ReconciliationServices.normalize(row.primaryId) }
                .findAll { it }
                .take(5)
                .join(", ")
        String batchToken = primaryIds ? " primaryIds=${primaryIds}" : ""
        return "RuleSet ${ruleSetId} compareScope ${compareScopeId} rule execution failed; preserved base missing-object diffs.${batchToken} Error: ${ReconciliationServices.normalize(error)}"
    }
}
