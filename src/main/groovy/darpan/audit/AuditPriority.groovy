package darpan.audit

import groovy.transform.CompileStatic

/**
 * Audit priority resolution (DAR-BE-348), pure. A finding's level is its reason's override for the conclusion, else
 * the statement's level, else P2. An order unit takes its most urgent line.
 */
@CompileStatic
class AuditPriority {
    static final List<String> LEVELS = ['P0', 'P1', 'P2', 'KNOWN']
    static final String DEFAULT_LEVEL = 'P2'

    static Map resolve(String statementLevel, Map<String, String> overridesByConclusion, List<String> lineConclusionIds) {
        String base = statementLevel ?: DEFAULT_LEVEL
        List<String> lines = lineConclusionIds ?: [(String) null]
        String bestLevel = null
        String bestConclusion = null
        boolean bestFromReason = false
        for (String conclusionId : lines) {
            String override = conclusionId != null ? overridesByConclusion?.get(conclusionId) : null
            String level = override ?: base
            if (bestLevel == null || LEVELS.indexOf(level) < LEVELS.indexOf(bestLevel)) {
                bestLevel = level
                bestConclusion = conclusionId
                bestFromReason = override != null
            }
        }
        return [level: bestLevel, source: bestFromReason ? 'reason' : 'statement', conclusionEnumId: bestConclusion] as Map
    }

    static Map<String, Integer> zeroCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>()
        for (String level : LEVELS) counts.put(level, 0)
        return counts
    }
}
