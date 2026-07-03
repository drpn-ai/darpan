package darpan.facade.reconciliation

import groovy.json.JsonSlurper

/**
 * Audit 2026-06-11 #21 — server-side diff pagination.
 *
 * Authoritative port of the diff-detail classification, faceting, filtering, and paging that used to
 * live ONLY in darpan-ui (ReconciliationRunResultPage.vue / reconciliationDisplay.ts). Moving it here
 * is what lets the run-result page request a bounded page of differences instead of loading, parsing,
 * and holding the entire diff file in the browser heap — the prior design did
 * {@code JSON.parse(contentText)} on the whole file, which does not scale to enterprise-size runs.
 *
 * Behaviour is kept byte-faithful to the UI helpers; {@code DiffDetailClassifierTests} locks the
 * mapping. If the UI classification/labelling logic changes, re-check parity here.
 *
 * Pure: no EC, no IO. {@link ReconciliationOutputSupport#getGeneratedOutputDifferences} owns access
 * control + file reading and delegates the row math to this class.
 */
class DiffDetailClassifier {

    static final List<String> BUCKET_ORDER = ['file-1', 'file-2', 'rule']
    static final String ALL_RULE_FILTER_KEY = 'all'
    static final String BASE_RULE_FILTER_KEY = 'base-diff'

    // --- normalize helpers (mirror reconciliationDisplay.ts normalizeDisplayText / normalizeDisplayToken) ---

    static String normalizeText(Object value) {
        return (value instanceof CharSequence) ? value.toString().trim() : ''
    }

    static String normalizeToken(Object value) {
        return normalizeText(value)
                .toLowerCase()
                .replaceAll(/[^a-z0-9]+/, '_')
                .replaceAll(/^_+|_+$/, '')
    }

    // --- per-record classification (mirror ReconciliationRunResultPage.vue helpers) ---

    static String resolveDiffType(Map record) {
        return normalizeText(record.get('diffType') ?: record.get('type'))
    }

    static Object firstRecordValue(Object record, List<String> keys) {
        if (!(record instanceof Map)) return null
        Map source = (Map) record
        for (String key : keys) {
            Object value = source.get(key)
            if (value != null && value.toString().trim()) return value
        }
        return null
    }

    static Object parseDiffData(Object rawData) {
        if (!(rawData instanceof CharSequence)) return rawData
        try {
            return new JsonSlurper().parseText(rawData.toString())
        } catch (Exception ignored) {
            return rawData
        }
    }

    static String resolveDiffRecordId(Map record, int rowIndex) {
        Object id = record.get('id')
        if (id != null && id.toString().trim()) return id.toString().trim()
        Object primaryId = record.get('primaryId')
        if (primaryId != null && primaryId.toString().trim()) return primaryId.toString().trim()
        Object candidate = firstRecordValue(record.get('data'),
                ['primaryId', 'record_id', 'recordId', 'compare_id', 'compareId', 'id'])
        if (candidate != null) return candidate.toString().trim()
        return "row-${rowIndex + 1}".toString()
    }

    static String resolveMissingBucket(Map record, String file1Label, String file2Label) {
        String missingToken = normalizeToken(record.get('missingIn'))
        String file1Token = normalizeToken(file1Label)
        String file2Token = normalizeToken(file2Label)
        String typeToken = normalizeToken(resolveDiffType(record))
        String presentToken = normalizeToken(record.get('presentIn'))

        if (missingToken && missingToken == file1Token) return 'file-1'
        if (missingToken && missingToken == file2Token) return 'file-2'
        if (file1Token && typeToken.contains("missing_in_${file1Token}".toString())) return 'file-1'
        if (file2Token && typeToken.contains("missing_in_${file2Token}".toString())) return 'file-2'
        if (presentToken && presentToken == file1Token) return 'file-2'
        if (presentToken && presentToken == file2Token) return 'file-1'
        return 'rule'
    }

    static boolean isMissingDiffRecord(Map record) {
        String typeToken = normalizeToken(resolveDiffType(record))
        return (normalizeText(record.get('missingIn'))
                || normalizeText(record.get('presentIn'))
                || typeToken.startsWith('missing_in_'))
    }

    static String resolveDiffBucket(Map record, String file1Label, String file2Label) {
        if (isMissingDiffRecord(record)) return resolveMissingBucket(record, file1Label, file2Label)
        return 'rule'
    }

    static String humanizeRuleIdentifier(String ruleId) {
        String normalized = normalizeText(ruleId)
        if (!normalized) return 'Rule difference'
        return normalized
                .replaceAll(/[_-]+/, ' ')
                .replaceAll(/\s+/, ' ')
                .trim()
                .toLowerCase()
                .replaceAll(/\b\w/) { String match -> match.toUpperCase() }
    }

    static String resolveRuleOptionLabel(String ruleId, int fallbackIndex) {
        String normalized = normalizeText(ruleId)
        def numbered = (normalized =~ /(?i)(?:^|[_\-\s])rule[_\-\s]*(\d+)$/)
        if (numbered.find()) return "Rule ${new BigInteger(numbered.group(1)).toString()}".toString()
        def trailing = (normalized =~ /(?:^|[_\-\s])(\d+)$/)
        if (trailing.find()) return "Rule ${new BigInteger(trailing.group(1)).toString()}".toString()
        return "Rule ${fallbackIndex}".toString()
    }

    static String resolveRuleRowDetail(Map record, String fallbackRuleId) {
        return normalizeText(record.get('ruleLabel')) ?:
                normalizeText(record.get('ruleName')) ?:
                        normalizeText(record.get('ruleDescription')) ?:
                                normalizeText(record.get('field')) ?:
                                        normalizeText(record.get('message')) ?:
                                                humanizeRuleIdentifier(fallbackRuleId)
    }

    static Map resolveRuleDescriptor(Map record, String bucket, int rowIndex) {
        if (bucket != 'rule') {
            return [ruleFilterKey: BASE_RULE_FILTER_KEY, ruleId: BASE_RULE_FILTER_KEY, ruleLabel: 'Base comparison']
        }
        String ruleId = normalizeText(record.get('ruleId')) ?:
                normalizeText(record.get('ruleName')) ?:
                        resolveDiffType(record) ?:
                                "rule-${rowIndex + 1}".toString()
        String ruleFilterKey = normalizeToken(ruleId) ?: "rule_${rowIndex + 1}".toString()
        return [ruleFilterKey: ruleFilterKey, ruleId: ruleId, ruleLabel: resolveRuleRowDetail(record, ruleId)]
    }

    /** Mirror of normalizeDiffDetailRows() per-row mapping (minus presentational detailValue, kept in the UI). */
    static Map classifyRow(Map record, int index, String file1Label, String file2Label) {
        Object parsedData = parseDiffData(record.get('data'))
        Map recordWithParsedData = new LinkedHashMap(record)
        recordWithParsedData.put('data', parsedData)
        String bucket = resolveDiffBucket(record, file1Label, file2Label)
        Map ruleDescriptor = resolveRuleDescriptor(record, bucket, index)
        String recordId = resolveDiffRecordId(recordWithParsedData, index)
        return [
                rowKey       : "${bucket}-${recordId}-${index}".toString(),
                recordId     : recordId,
                bucket       : bucket,
                ruleFilterKey: ruleDescriptor.ruleFilterKey,
                ruleId       : ruleDescriptor.ruleId,
                ruleLabel    : ruleDescriptor.ruleLabel,
        ]
    }

    // --- facets (mirror diffDetailBucketCounts + ruleSelectorOptions computeds) ---

    static Map<String, Integer> buildBucketCounts(List<Map> rows) {
        Map<String, Integer> counts = ['file-1': 0, 'file-2': 0, 'rule': 0]
        rows.each { Map row ->
            String bucket = (String) row.bucket
            if (counts.containsKey(bucket)) counts.put(bucket, counts.get(bucket) + 1)
        }
        return counts
    }

    static List<Map> buildRuleOptions(List<Map> rows) {
        List<Map> baseRows = rows.findAll { it.ruleFilterKey == BASE_RULE_FILTER_KEY }
        Map<String, Map> ruleEntries = new LinkedHashMap<>()
        rows.each { Map row ->
            if (row.ruleFilterKey == BASE_RULE_FILTER_KEY) return
            Map existing = ruleEntries.get(row.ruleFilterKey)
            if (existing != null) {
                existing.count = ((int) existing.count) + 1
                ((Set) existing.bucketKeys).add(row.bucket)
                if (!existing.detail && row.ruleLabel) existing.detail = row.ruleLabel
                return
            }
            ruleEntries.put((String) row.ruleFilterKey, [
                    key       : row.ruleFilterKey,
                    ruleId    : row.ruleId,
                    detail    : row.ruleLabel,
                    count     : 1,
                    bucketKeys: new LinkedHashSet([row.bucket]),
            ])
        }

        List<Map> options = []
        if (!baseRows.isEmpty()) {
            List<String> baseBuckets = BUCKET_ORDER.findAll { String bucket -> baseRows.any { it.bucket == bucket } }
            options.add([
                    key       : BASE_RULE_FILTER_KEY,
                    label     : 'Rule 0',
                    detail    : 'Base comparison',
                    count     : baseRows.size(),
                    bucketKeys: baseBuckets.isEmpty() ? ['file-1', 'file-2'] : baseBuckets,
            ])
        }
        int index = 0
        ruleEntries.values().each { Map entry ->
            options.add([
                    key       : entry.key,
                    label     : resolveRuleOptionLabel((String) entry.ruleId, index + 1),
                    detail    : entry.detail ?: humanizeRuleIdentifier((String) entry.ruleId),
                    count     : entry.count,
                    bucketKeys: BUCKET_ORDER.findAll { String bucket -> ((Set) entry.bucketKeys).contains(bucket) },
            ])
            index++
        }
        return options
    }

    // --- effective summary (mirror buildGeneratedOutputFromPayload summary derivation) ---

    static Map summarizeMissingCounts(List differences, String file1Label, String file2Label) {
        int onlyInFile1Count = 0
        int onlyInFile2Count = 0
        differences.each { Object raw ->
            Map record = (raw instanceof Map) ? (Map) raw : [:]
            String bucket = resolveMissingBucket(record, file1Label, file2Label)
            if (bucket == 'file-1') onlyInFile2Count++
            else if (bucket == 'file-2') onlyInFile1Count++
        }
        return [onlyInFile1Count: onlyInFile1Count, onlyInFile2Count: onlyInFile2Count]
    }

    static Map buildEffectiveSummary(Map document, String file1Label, String file2Label) {
        List differences = (document?.get('differences') instanceof List) ? (List) document.get('differences') : []
        Map fileSummary = (document?.get('summary') instanceof Map) ? (Map) document.get('summary') : [:]
        Map missingCounts = summarizeMissingCounts(differences, file1Label, file2Label)

        def totalDifferences = (fileSummary.get('totalDifferences') != null)
                ? fileSummary.get('totalDifferences') : differences.size()
        def onlyInFile1Count = (fileSummary.get('onlyInFile1Count') != null)
                ? fileSummary.get('onlyInFile1Count') : missingCounts.onlyInFile1Count
        def onlyInFile2Count = (fileSummary.get('onlyInFile2Count') != null)
                ? fileSummary.get('onlyInFile2Count') : missingCounts.onlyInFile2Count

        return [
                totalDifferences            : totalDifferences,
                onlyInFile1Count            : onlyInFile1Count,
                onlyInFile2Count            : onlyInFile2Count,
                ruleDifferenceCount         : fileSummary.get('ruleDifferenceCount'),
                missingObjectDifferenceCount: fileSummary.get('missingObjectDifferenceCount'),
        ]
    }

    // --- paging (mirror activeBucket/activeRule/filtered chain + useListPagination) ---

    static int pageCountFor(int totalItems, int pageSize) {
        int normalizedTotal = Math.max(0, totalItems)
        int normalizedSize = pageSize < 1 ? 1 : pageSize
        return Math.max(1, (int) Math.ceil(normalizedTotal / (double) normalizedSize))
    }

    static int clampPageIndex(int pageIndex, int pageCount) {
        int maxPageIndex = Math.max(0, pageCount - 1)
        int normalized = pageIndex < 0 ? 0 : pageIndex
        return Math.min(normalized, maxPageIndex)
    }

    /**
     * Classify every difference, apply the active bucket / rule / record-id-search filters, and return
     * the requested page plus (optionally) whole-document facets. Facets are computed over ALL rows
     * (unfiltered) to mirror the UI, where the selector counts never shrink with the active filter.
     */
    static Map buildDifferencesPage(Map document, String file1Label, String file2Label,
                                    List<String> requestedBuckets, String ruleFilterKey, String search,
                                    int pageIndex, int pageSize, boolean includeFacets) {
        List rawDifferences = (document?.get('differences') instanceof List) ? (List) document.get('differences') : []

        List<Map> classified = new ArrayList<>(rawDifferences.size())
        rawDifferences.eachWithIndex { Object rawRecord, int idx ->
            Map record = (rawRecord instanceof Map) ? (Map) rawRecord : [:]
            Map row = classifyRow(record, idx, file1Label, file2Label)
            row.put('record', record)
            classified.add(row)
        }

        List<String> activeBuckets = (requestedBuckets == null || requestedBuckets.isEmpty())
                ? new ArrayList<>(BUCKET_ORDER)
                : BUCKET_ORDER.findAll { requestedBuckets.contains(it) }
        String normalizedSearch = (search == null) ? '' : search.trim().toLowerCase()
        String effectiveRuleKey = normalizeText(ruleFilterKey) ?: ALL_RULE_FILTER_KEY

        List<Map> filtered = classified.findAll { Map row ->
            if (!activeBuckets.contains(row.bucket)) return false
            if (effectiveRuleKey != ALL_RULE_FILTER_KEY && row.ruleFilterKey != effectiveRuleKey) return false
            if (normalizedSearch && !((String) row.recordId).toLowerCase().contains(normalizedSearch)) return false
            return true
        }

        int total = classified.size()
        int totalFiltered = filtered.size()
        int normalizedSize = pageSize < 1 ? 1 : pageSize
        int pageCount = pageCountFor(totalFiltered, normalizedSize)
        int safePageIndex = clampPageIndex(pageIndex, pageCount)
        int start = safePageIndex * normalizedSize
        int end = Math.min(start + normalizedSize, totalFiltered)
        List<Map> pageRows = (start < end) ? new ArrayList<>(filtered.subList(start, end)) : []

        Map result = [
                differences     : pageRows,
                pageIndex       : safePageIndex,
                pageSize        : normalizedSize,
                pageCount       : pageCount,
                totalDifferences: total,
                totalFiltered   : totalFiltered,
        ]
        if (includeFacets) {
            result.put('bucketCounts', buildBucketCounts(classified))
            result.put('ruleOptions', buildRuleOptions(classified))
        }
        return result
    }
}
