package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSetCompareScopeDiffStage {
    private static final Logger logger = LoggerFactory.getLogger(RuleSetCompareScopeDiffStage.class)

    static Map<String, Object> reconcileRuleSetCompareScopeBaseDiff(ExecutionContext ec) {
        Map<String, Object> context = (Map<String, Object>) ec.contextStack

        Map<String, Object> prepared = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope")
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

        Dataset file1IdDf = (Dataset) prepared.file1IdDf
        Dataset file2IdDf = (Dataset) prepared.file2IdDf
        Dataset file1DataDf = (Dataset) prepared.file1DataDf
        Dataset file2DataDf = (Dataset) prepared.file2DataDf

        Map<String, Object> idCompare = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#IdDataFrames")
                .parameters([
                        df1         : file1IdDf,
                        df2         : file2IdDf,
                        idColumnName: "compare_id",
                        df1Label    : prepared.file1Label,
                        df2Label    : prepared.file2Label
                ])
                .call()

        Dataset onlyInFile1Df = (Dataset) idCompare.onlyInDf1
        Dataset onlyInFile2Df = (Dataset) idCompare.onlyInDf2
        long onlyInFile1Count = ((Number) idCompare.onlyInDf1Count).longValue()
        long onlyInFile2Count = ((Number) idCompare.onlyInDf2Count).longValue()
        long differenceCount = ((Number) idCompare.differenceCount).longValue()

        String file1Label = ReconciliationServices.normalize(prepared.file1Label) ?: "File 1"
        String file2Label = ReconciliationServices.normalize(prepared.file2Label) ?: "File 2"
        String compareScopeId = ReconciliationServices.normalize(prepared.compareScopeId)
        String objectType = ReconciliationServices.normalize(prepared.objectType)

        Dataset missingInFile1DiffDf = ReconciliationServices.buildMissingDiffRows(
                file2DataDf,
                onlyInFile2Df,
                "MISSING_IN_FILE_1",
                file2Label,
                file1Label,
                "Present in ${file2Label}, missing in ${file1Label}"
        )
        Dataset missingInFile2DiffDf = ReconciliationServices.buildMissingDiffRows(
                file1DataDf,
                onlyInFile1Df,
                "MISSING_IN_FILE_2",
                file1Label,
                file2Label,
                "Present in ${file1Label}, missing in ${file2Label}"
        )
        Dataset missingDiffDf = ReconciliationServices.unionDatasets(missingInFile1DiffDf, missingInFile2DiffDf)
        if (missingDiffDf == null) {
            missingDiffDf = ReconciliationServices.emptyMissingDiffDataset(file1DataDf ?: file2DataDf)
        }

        Dataset matchedIdDf = file1IdDf.join(file2IdDf, "compare_id", "inner")
                .select("compare_id")
                .distinct()
        Dataset matchedPairDf = ReconciliationServices.buildMatchedPairDataset(
                file1DataDf,
                file2DataDf,
                matchedIdDf,
                compareScopeId,
                objectType
        )
        long matchedPairCount = matchedPairDf.count()

        logger.info("Built RuleSet base diff stage: ruleSet={} compareScope={} missingInFile1={} missingInFile2={} matchedPairs={}",
                prepared.ruleSetId, compareScopeId, onlyInFile2Count, onlyInFile1Count, matchedPairCount)

        return [
                ruleSetId          : prepared.ruleSetId,
                compareScopeId     : compareScopeId,
                objectType         : objectType,
                file1Type          : prepared.file1Type,
                file2Type          : prepared.file2Type,
                file1Label         : file1Label,
                file2Label         : file2Label,
                missingInFile1Count: onlyInFile2Count,
                missingInFile2Count: onlyInFile1Count,
                differenceCount    : differenceCount,
                matchedPairCount   : matchedPairCount,
                missingDiffDf      : missingDiffDf,
                matchedPairDf      : matchedPairDf,
                validationErrors   : prepared.validationErrors ?: [],
                processingWarnings : prepared.processingWarnings ?: []
        ]
    }
}
