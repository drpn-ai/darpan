package darpan.audit

import groovy.transform.CompileStatic

import java.sql.Timestamp
import java.time.LocalDate

/**
 * DAR-BE-361: the acknowledgement rules, pure (no Moqui). A report row reads open, acknowledged or reminded from the
 * acknowledgement rows of its open findings. The caller supplies today in the tenant's zone, so the remind day falls
 * on the tenant's calendar, not UTC. Rows carry java.sql.Date / Timestamp from Moqui; tests may pass LocalDate.
 */
@CompileStatic
class AcknowledgementSupport {

    /** The day the acknowledgement lapses: ackDay + remindDays when remind is on, else null (stays acknowledged). */
    static LocalDate remindDate(LocalDate ackDay, int remindDays, boolean remind) {
        remind ? ackDay.plusDays(remindDays) : null
    }

    /** An acknowledgement still holds: not voided by a later reopen, and before its remind day. */
    static boolean effective(Map ack, LocalDate today, Timestamp lastReopenStamp) {
        if (ack == null) return false
        if (!survivesReopen(ack, lastReopenStamp)) return false
        LocalDate remind = toLocalDate(ack.get('remindDate'))
        return remind == null || today.isBefore(remind)
    }

    /** "acknowledged" when every open finding holds an effective acknowledgement; "reminded" when all rows survive
     *  reopen but at least one is past its remind day; "open" when any finding has none, or one a reopen voided (also for
     *  an empty unit). */
    static String unitState(List<String> openFindingIds, Map<String, Map> acksByFinding,
                            Map<String, Timestamp> reopenByFinding, LocalDate today) {
        if (openFindingIds == null || openFindingIds.isEmpty()) return 'open'
        boolean allEffective = true
        for (String id : openFindingIds) {
            Map ack = acksByFinding?.get(id)
            Timestamp reopen = reopenByFinding?.get(id)
            if (ack == null || !survivesReopen(ack, reopen)) return 'open'
            // It survives the reopen, so not effective means past its remind day.
            if (!effective(ack, today, reopen)) allEffective = false
        }
        return allEffective ? 'acknowledged' : 'reminded'
    }

    /** The row's acknowledgement block: note, who, when (caller formats in the tenant zone), remind day, reminded flag. */
    static Map summary(Map ack, String state, Map<String, String> userLabels) {
        String by = ack.get('acknowledgedByUserId') as String
        LocalDate remind = toLocalDate(ack.get('remindDate'))
        return [note    : ack.get('note'),
                by      : [id: by, label: userLabels?.get(by)],
                at      : toTimestamp(ack.get('acknowledgedStamp')),
                remindOn: remind?.toString(),
                reminded: state == 'reminded'] as Map
    }

    private static boolean survivesReopen(Map ack, Timestamp lastReopenStamp) {
        Timestamp at = toTimestamp(ack.get('acknowledgedStamp'))
        return lastReopenStamp == null || (at != null && at.after(lastReopenStamp))
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null
        if (v instanceof LocalDate) return (LocalDate) v
        if (v instanceof java.sql.Date) return ((java.sql.Date) v).toLocalDate()
        if (v instanceof Date) return new java.sql.Date(((Date) v).time).toLocalDate()
        return LocalDate.parse(v.toString())
    }

    private static Timestamp toTimestamp(Object v) {
        if (v == null) return null
        if (v instanceof Timestamp) return (Timestamp) v
        if (v instanceof Date) return new Timestamp(((Date) v).time)
        if (v instanceof java.time.LocalDateTime) return Timestamp.valueOf((java.time.LocalDateTime) v)
        return Timestamp.valueOf(v.toString().replace('T', ' '))
    }
}
