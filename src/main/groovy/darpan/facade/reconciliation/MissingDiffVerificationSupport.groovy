package darpan.facade.reconciliation

import groovy.json.JsonSlurper

/**
 * Verification pass over a ruleset diff document: rows reported missing in a lookup-capable side
 * (connector declares a lookupServiceName) are rechecked against that side's primary datastore via
 * a point lookup; rows the lookup confirms present are bulk-export index-skew false positives and
 * are removed, with summary counts adjusted and an audit note appended to the artifact's
 * processingWarnings.
 *
 * Failure posture is strictly conservative: a failed or skipped lookup never reclassifies anything —
 * the document is left untouched and the caller gets a warning. A row is only removed when the
 * source of record itself confirmed the id exists.
 *
 * Streams the {@link darpan.reconciliation.core.ReconciliationServices#writeDiffDatasetOutput}
 * line-oriented format (one JSON row per line inside {@code "differences":[}) instead of parsing the
 * whole document — diff files reach GB scale (see the OutputDescriptorSupport OOM fix), so only one
 * row is ever held at a time. {@code MissingDiffVerificationSupportTests} locks the format coupling.
 */
class MissingDiffVerificationSupport {

    /** Above this many missing rows per side the pass skips itself: a sync that broken is not
     *  index skew, and point-checking it would hammer the source API for no signal.
     *
     *  That reasoning holds only where the extract is expected to be near-complete. Where it is NOT —
     *  the returns pair enumerates through orders(query:) filtered on Shopify's updated_at, which is
     *  not reliably bumped, so its gap is the steady state rather than a symptom (601 of 903 records,
     *  gorjana prod 2026-08-18) — the side overrides this via sideMaxLookupIds. */
    static final int DEFAULT_MAX_LOOKUP_IDS = 1000

    /** Opening phrase of this pass's audit sentence. Classified at the notification layer
     *  (TenantNotificationSupport.partitionAuditNotes) so a successful verification is reported as a
     *  note, not as a run issue. This pass's real warnings open with "Verification lookup for …". */
    static final String AUDIT_NOTE_PREFIX = "Verification pass: "

    /** Joins a lookup result's errors for a warning, matching the failure branch's phrasing. */
    private static String lookupErrorsOf(Map lookupResult) {
        List errors = lookupResult?.errors instanceof List ? (List) lookupResult.errors : []
        return errors ? errors.join("; ") : ""
    }

    // The document's line shapes, and the read and rewrite passes over them, live in
    // DiffDocumentStreamSupport, shared with the return-presence and conclude passes.

    /**
     * args:
     *   diffFile     : File — diff document in writeDiffDatasetOutput format (required)
     *   file1Label   : String, file2Label : String — side labels as written into the document
     *   sideLookups  : Map side label -> Closure(List<String> ids) returning
     *                  [ok, foundIds, missingIds, errors] (the lookup service contract)
     *   maxLookupIds : optional cap applied to any side with no entry in sideMaxLookupIds,
     *                  default {@link #DEFAULT_MAX_LOOKUP_IDS}
     *   sideMaxLookupIds : optional Map side label -> cap, letting each side carry the cap its own
     *                  connector declares. A side over ITS OWN cap contributes nothing and must never
     *                  disable a sibling side that is under its.
     *
     * returns [performed, rewritten, checkedCount, removedCount, confirmedMissingCount, unresolvedCount,
     *          removedMissingInFile1, removedMissingInFile2, warnings, auditNote]
     */
    static Map<String, Object> verifyMissingDiffs(Map<String, Object> args) {
        File diffFile = (File) args.diffFile
        String file1Token = DiffDetailClassifier.normalizeToken(args.file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(args.file2Label)
        int maxLookupIds = (args.maxLookupIds instanceof Number) ? ((Number) args.maxLookupIds).intValue() : DEFAULT_MAX_LOOKUP_IDS
        Map<String, Integer> maxLookupIdsByToken = [:]
        ((Map) (args.sideMaxLookupIds ?: [:])).each { Object label, Object cap ->
            String token = DiffDetailClassifier.normalizeToken(label)
            if (token && cap instanceof Number && ((Number) cap).intValue() > 0) {
                maxLookupIdsByToken.put(token, ((Number) cap).intValue())
            }
        }
        List<String> warnings = []
        Map<String, Object> inert = [performed: false, rewritten: false, checkedCount: 0, removedCount: 0,
                confirmedMissingCount: 0, unresolvedCount: 0, removedMissingInFile1: 0, removedMissingInFile2: 0,
                lookupFailed: false, warnings: warnings, auditNote: null] as Map<String, Object>

        Map<String, Map<String, Object>> sidesByToken = new LinkedHashMap<>()
        ((Map) (args.sideLookups ?: [:])).each { Object label, Object lookup ->
            String token = DiffDetailClassifier.normalizeToken(label)
            if (token && lookup instanceof Closure) sidesByToken.put(token, [label: label.toString(), lookup: lookup] as Map<String, Object>)
        }
        if (!sidesByToken || diffFile == null || !diffFile.isFile()) return inert

        // Pass 1 — collect candidate ids per lookup-capable side, one row in memory at a time.
        JsonSlurper slurper = new JsonSlurper()
        Map<String, Set<String>> candidateIdsByToken = [:].withDefault { new LinkedHashSet<String>() }
        boolean sawDifferencesHeader = DiffDocumentStreamSupport.forEachMissingRow(diffFile, slurper) { Map row ->
            String missingToken = DiffDetailClassifier.normalizeToken(row.get("missingIn"))
            String rowId = DiffDocumentStreamSupport.rowIdOf(row)
            if (rowId && sidesByToken.containsKey(missingToken)) candidateIdsByToken.get(missingToken).add(rowId)
        }
        if (!sawDifferencesHeader) {
            warnings.add("Verification pass skipped: diff document has no differences section.")
            return inert
        }

        // Dispatch the point lookups. foundIds = verified present in the side that reported them
        // missing -> false positives to remove. Anything else (failure, over cap) removes nothing.
        boolean performed = false
        boolean lookupFailed = false
        int checkedCount = 0
        int confirmedMissingCount = 0
        int unresolvedCount = 0
        Map<String, Set<String>> removeIdsByToken = [:]
        Map<String, Integer> removedCountByToken = [:]
        List<String> auditSentences = []
        sidesByToken.each { String token, Map<String, Object> side ->
            Set<String> candidates = candidateIdsByToken.containsKey(token) ? candidateIdsByToken.get(token) : (Set<String>) null
            if (!candidates) return
            int sideMaxLookupIds = maxLookupIdsByToken.containsKey(token) ? maxLookupIdsByToken.get(token) : maxLookupIds
            if (candidates.size() > sideMaxLookupIds) {
                // `return` skips THIS side only — a side over its own cap must not suppress a sibling
                // that is under its, since the two sides are different systems with different
                // completeness guarantees.
                warnings.add("Verification pass skipped for ${side.label}: ${candidates.size()} missing differences exceeds the ${sideMaxLookupIds}-id lookup cap for this side.".toString())
                return
            }
            List<String> ids = new ArrayList<>(candidates)
            Map lookupResult
            try {
                lookupResult = (Map) ((Closure) side.lookup).call(ids)
            } catch (Exception e) {
                lookupResult = [ok: false, errors: [e.message ?: e.class.simpleName]]
            }
            performed = true
            checkedCount += ids.size()
            if (!(lookupResult?.ok)) {
                lookupFailed = true
                List errors = lookupResult?.errors instanceof List ? (List) lookupResult.errors : []
                warnings.add("Verification lookup for ${side.label} failed; ${ids.size()} missing differences left as reported. ${errors ? errors.join("; ") : ""}".toString().trim())
                return
            }
            Set<String> found = new LinkedHashSet<String>()
            (lookupResult.foundIds instanceof List ? (List) lookupResult.foundIds : []).each { Object id ->
                String value = id?.toString()?.trim()
                if (value) found.add(value)
            }
            // DAR-BE-036: a lookup may now answer for only SOME of the ids it was handed — one
            // chunk can fail while its siblings succeed. Ids it never reached are UNKNOWN, and folding
            // them into confirmedMissing would report a blind spot as evidence of absence, which is
            // precisely the failure the pending-return probe caught in August. A lookup that does not
            // report unresolvedIds strands nothing, so this stays inert for every other side.
            Set<String> unresolved = new LinkedHashSet<String>()
            (lookupResult.unresolvedIds instanceof List ? (List) lookupResult.unresolvedIds : []).each { Object id ->
                String value = id?.toString()?.trim()
                if (value && ids.contains(value)) unresolved.add(value)
            }
            Set<String> removable = ids.findAll { found.contains(it) } as Set<String>
            unresolved.removeAll(removable)
            int confirmedMissingHere = ids.size() - removable.size() - unresolved.size()
            confirmedMissingCount += confirmedMissingHere
            unresolvedCount += unresolved.size()
            if (unresolved) {
                warnings.add("Verification lookup for ${side.label} could not be checked for ${unresolved.size()} of ${ids.size()} missing difference(s); those are left as reported. ${lookupErrorsOf(lookupResult)}".toString().trim())
            }
            if (removable) {
                removeIdsByToken.put(token, removable)
                removedCountByToken.put(token, removable.size())
                auditSentences.add("${AUDIT_NOTE_PREFIX}${removable.size()} of ${ids.size()} 'missing in ${side.label}' difference(s) confirmed present in ${side.label} by point lookup (bulk-export index gap) and removed; ${confirmedMissingHere} confirmed missing${unresolved ? ", ${unresolved.size()} unchecked" : ""}.".toString())
            }
        }

        int removedMissingInFile1 = removedCountByToken.get(file1Token) ?: 0
        int removedMissingInFile2 = removedCountByToken.get(file2Token) ?: 0
        int removedCount = (removedCountByToken.values().sum() ?: 0) as int
        String auditNote = auditSentences ? auditSentences.join(" ") : null
        if (!removedCount) {
            return [performed: performed, rewritten: false, checkedCount: checkedCount, removedCount: 0,
                    confirmedMissingCount: confirmedMissingCount, unresolvedCount: unresolvedCount,
                    removedMissingInFile1: 0, removedMissingInFile2: 0,
                    lookupFailed: lookupFailed, warnings: warnings, auditNote: auditNote] as Map<String, Object>
        }

        // Pass 2 — stream-rewrite to a sibling temp file, then atomically replace the original.
        DiffDocumentStreamSupport.removeMissingRows(diffFile, ".verify-tmp", removeIdsByToken,
                removedCount, removedMissingInFile1, removedMissingInFile2, auditNote)

        return [performed: true, rewritten: true, checkedCount: checkedCount, removedCount: removedCount,
                confirmedMissingCount: confirmedMissingCount, unresolvedCount: unresolvedCount,
                removedMissingInFile1: removedMissingInFile1,
                removedMissingInFile2: removedMissingInFile2, lookupFailed: lookupFailed,
                warnings: warnings, auditNote: auditNote] as Map<String, Object>
    }
}
