package darpan.audit

/**
 * DAR-OPS-029 S99-066. A check run's status from its source reads: the one rule record#CheckRun writes and a verify
 * (AuditAutomationSupport.verifyStatus, DAR-BE-123) answers without writing a run. Pure: each caller reads the
 * Enumeration rows itself and passes them as systemEnums (systemEnumId to [enumTypeId, parentEnumId]).
 */
class AuditCheckStatus {

    static final List<String> READ_STATUSES = ["AUDSR_COMPLETE", "AUDSR_PARTIAL", "AUDSR_FAILED"].asImmutable()

    /**
     * [error] when a read names a system that is not a DarpanSystemSource or a status that is not an AuditSourceRead one
     * (validated before anything else, so a bad read writes nothing even beside a reason); else [checkStatusEnumId, reason]:
     * not run for a reason, no read, or every read failed (OC103); partial when any read is not complete; else ran.
     */
    static Map derive(List<Map> reads, String notRunReason, Map<String, Map> systemEnums, String checkId) {
        List<Map> all = reads ?: []
        List<String> badReads = []
        for (Map r in all) {
            Map system = systemEnums?.get(r.systemEnumId as String)
            if (system?.enumTypeId != "DarpanSystemSource")
                return [error: "Unknown system ${r.systemEnumId} on a source read of check ${checkId}".toString()]
            if (!(r.statusEnumId in READ_STATUSES))
                return [error: "Source read status ${r.statusEnumId} is not an AuditSourceRead status".toString()]
            if (r.statusEnumId != "AUDSR_COMPLETE") {
                String root = (system.parentEnumId ?: r.systemEnumId) as String
                badReads << ("The ${AuditRecordFacts.systemKey(root)} read ${r.statusEnumId == 'AUDSR_FAILED' ? 'failed' : 'was partial'}: " +
                        "${r.reason ?: 'no reason was given'}.").toString()
            }
        }
        if (notRunReason) return [checkStatusEnumId: "AUDCS_NOT_RUN", reason: notRunReason]
        if (!all) return [checkStatusEnumId: "AUDCS_NOT_RUN", reason: "The check recorded no source read."]
        if (all.every { it.statusEnumId == "AUDSR_FAILED" }) return [checkStatusEnumId: "AUDCS_NOT_RUN", reason: badReads.join(" ")]
        if (badReads) return [checkStatusEnumId: "AUDCS_PARTIAL", reason: badReads.join(" ")]
        return [checkStatusEnumId: "AUDCS_RAN", reason: null]
    }
}
