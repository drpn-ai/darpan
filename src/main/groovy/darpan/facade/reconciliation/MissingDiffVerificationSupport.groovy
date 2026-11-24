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

    /** DAR-BE-320 (Aditi 2026-11-19): a lookup is a last resort, justified only when roughly 1-5% of the
     *  records need a follow-up. A side is looked up only when the rows reported missing in it are at
     *  most this percent of the records checked against it (the other side's records); above that the
     *  read or the pairing is wrong, and looking up would only spend the source API's budget on it.
     *  A connector may set its own gate (SourceSystemConnector.lookupMaxShare). */
    static final int DEFAULT_MAX_LOOKUP_SHARE = 5

    /** Opening phrase of this pass's audit sentence. Classified at the notification layer
     *  (TenantNotificationSupport.partitionAuditNotes) so a successful verification is reported as a
     *  note, not as a run issue. This pass's real warnings open with "Verification lookup for …". */
    static final String AUDIT_NOTE_PREFIX = "Verification pass: "

    /** Joins a lookup result's errors for a warning, matching the failure branch's phrasing. */
    private static String lookupErrorsOf(Map lookupResult) {
        List errors = lookupResult?.errors instanceof List ? (List) lookupResult.errors : []
        return errors ? errors.join("; ") : ""
    }

    /** label -> positive number, keyed by the normalized side token; anything else is left out. */
    private static Map<String, Long> positiveNumbersByToken(Object raw) {
        Map<String, Long> out = [:]
        ((raw instanceof Map ? (Map) raw : [:]) as Map).each { Object label, Object value ->
            String token = DiffDetailClassifier.normalizeToken(label)
            if (token && value instanceof Number && ((Number) value).longValue() > 0L) out.put(token, ((Number) value).longValue())
        }
        return out
    }

    // The document's line shapes, and the read and rewrite passes over them, live in
    // DiffDocumentStreamSupport, shared with the return-presence and conclude passes.

    /**
     * args:
     *   diffFile     : File — diff document in writeDiffDatasetOutput format (required)
     *   file1Label   : String, file2Label : String — side labels as written into the document
     *   sideLookups  : Map side label -> Closure(List<String> ids) returning
     *                  [ok, foundIds, missingIds, errors] (the lookup service contract)
     *   sideCheckedCounts : Map side label -> how many records were checked against that side (the
     *                  other side's record count). A side without one is not looked up (fail closed).
     *   sideMaxShares : optional Map side label -> percent gate, default {@link #DEFAULT_MAX_LOOKUP_SHARE}.
     *                  A side over ITS OWN gate contributes nothing and must never disable a sibling
     *                  side that is under its.
     *   auditRun     : true for an audit's window (DAR-BE-351): only there does the gate skip a side. Any
     *                  other run looks up every row its read left missing and says the share as a note.
     *
     * returns [performed, rewritten, checkedCount, removedCount, confirmedMissingCount, unresolvedCount,
     *          removedMissingInFile1, removedMissingInFile2, warnings, auditNote]
     */
    static Map<String, Object> verifyMissingDiffs(Map<String, Object> args) {
        File diffFile = (File) args.diffFile
        String file1Token = DiffDetailClassifier.normalizeToken(args.file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(args.file2Label)
        Map<String, Long> checkedCountByToken = positiveNumbersByToken(args.sideCheckedCounts)
        Map<String, Long> maxShareByToken = positiveNumbersByToken(args.sideMaxShares)
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
        // missing -> false positives to remove. Anything else (failure, over its gate) removes nothing.
        boolean performed = false
        boolean lookupFailed = false
        int checkedCount = 0
        int confirmedMissingCount = 0
        int unresolvedCount = 0
        Map<String, Set<String>> removeIdsByToken = [:]
        Map<String, Integer> removedCountByToken = [:]
        // DAR-BE-320 phase 1b: what happened on each side, for the audit's read report (AuditSourceRead.lookupJson).
        Map<String, Map<String, Object>> sideOutcomes = [:]
        List<String> auditSentences = []
        sidesByToken.each { String token, Map<String, Object> side ->
            Set<String> candidates = candidateIdsByToken.containsKey(token) ? candidateIdsByToken.get(token) : (Set<String>) null
            if (!candidates) return
            // `return` skips THIS side only — a side over its own gate must not suppress a sibling that
            // is under its, since the two sides are different systems with different completeness guarantees.
            Long checked = checkedCountByToken.get(token)
            long gate = maxShareByToken.containsKey(token) ? maxShareByToken.get(token) : (long) DEFAULT_MAX_LOOKUP_SHARE
            Map<String, Object> outcome = [candidates: candidates.size(), checkedAgainst: checked, maxSharePercent: gate,
                                           lookedUp: 0, found: 0] as Map<String, Object>
            sideOutcomes.put(side.label as String, outcome)
            if (checked == null) {
                outcome.skipped = "NO_COUNT"
                warnings.add("Verification pass skipped for ${side.label}: the run did not say how many records were checked against it, so ${candidates.size()} missing difference(s) were not looked up.".toString())
                return
            }
            long maxShare = gate
            if (candidates.size() * 100L > maxShare * checked) {
                // DAR-BE-351 (Aditi): "The 5% rule is mainly for audits. Since they do large extracts". Any other run
                // clears its doubts by looking up, and still says the read left too much to look up.
                if (args.auditRun == true) {
                    outcome.skipped = "OVER_SHARE"
                    warnings.add("Verification pass skipped for ${side.label}: ${candidates.size()} of ${checked} records are reported missing there, above the ${maxShare}% a lookup is for. The read or the pairing needs fixing; nothing was looked up.".toString())
                    return
                }
                outcome.overShare = true
                auditSentences.add("${AUDIT_NOTE_PREFIX}the read left ${candidates.size()} of ${checked} records missing in ${side.label}, above the ${maxShare}% a read should leave; all were looked up. The read or the pairing needs fixing.".toString())
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
            outcome.lookedUp = ids.size()
            if (!(lookupResult?.ok)) {
                lookupFailed = true
                List errors = lookupResult?.errors instanceof List ? (List) lookupResult.errors : []
                warnings.add("Verification lookup for ${side.label} failed; ${ids.size()} missing differences left as reported. ${errors ? errors.join("; ") : ""}".toString().trim())
                outcome.failed = true
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
            outcome.found = removable.size()
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
                    lookupFailed: lookupFailed, warnings: warnings, auditNote: auditNote, sideOutcomes: sideOutcomes] as Map<String, Object>
        }

        // Pass 2 — stream-rewrite to a sibling temp file, then atomically replace the original.
        DiffDocumentStreamSupport.removeMissingRows(diffFile, ".verify-tmp", removeIdsByToken,
                removedCount, removedMissingInFile1, removedMissingInFile2, auditNote)

        return [performed: true, rewritten: true, checkedCount: checkedCount, removedCount: removedCount,
                confirmedMissingCount: confirmedMissingCount, unresolvedCount: unresolvedCount,
                removedMissingInFile1: removedMissingInFile1,
                removedMissingInFile2: removedMissingInFile2, lookupFailed: lookupFailed,
                warnings: warnings, auditNote: auditNote, sideOutcomes: sideOutcomes] as Map<String, Object>
    }
}
