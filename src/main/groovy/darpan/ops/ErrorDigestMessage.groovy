package darpan.ops

import darpan.email.EmailCopyReader
import darpan.email.EmailFrame

import java.sql.Timestamp

import static darpan.email.EmailFrame.DIM
import static darpan.email.EmailFrame.F
import static darpan.email.EmailFrame.INK
import static darpan.email.EmailFrame.LINE_SOFT
import static darpan.email.EmailFrame.MUTED
import static darpan.email.EmailFrame.esc

/**
 * DAR-BE-015 (spec F). The daily Darpan error email's body and its one-line chat summary, from the digest
 * ErrorDigestSupport assembled (digestBody: instance, since, now, generatedAt, runErrors, watchdog, logErrors, counts,
 * tenantNames, automationNames, emptyStreakDays). Pure: every word is a DARPAN_ERROR_DIGEST copy block read through the
 * reader, every value is escaped here. Order: run errors (by tenant, then check), the watchdog, log errors, the footer.
 * Darpan staff only: it carries error text, stacks and system names, so it is never sent to a client. Its only links are
 * the user guide's; a run's address is shown as text. Times are UTC.
 */
class ErrorDigestMessage {
    static final String TEMPLATE = "DARPAN_ERROR_DIGEST"
    private static final String UTC = "UTC"

    static String emailHtml(Map body, EmailCopyReader copy) {
        Map counts = (body.counts ?: [:]) as Map
        StringBuilder out = new StringBuilder(EmailFrame.open("DARPAN · ERRORS"))
        out << p(empty(body) ? copy.text("none") : copy.text("intro", [checks: counts.checks ?: 0, logErrors: counts.logErrors ?: 0]))

        out << title(copy.text("runs.title"))
        List<Map> runs = list(body.runErrors)
        if (!runs) out << p(copy.text("runs.none"), DIM)
        runs.groupBy { it.tenant }.each { Object tenant, List<Map> rows ->
            out << EmailFrame.eyebrow(tenantLabel(body, tenant), 14)
            rows.each { Map r ->
                out << "<div style=\"${F}padding:10px 0;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.55;color:${INK};\">" <<
                        "<div>${esc(subjectLine(body, r))}</div>" <<
                        "<div style=\"color:${MUTED};\">${esc(kindLine(r, copy))}</div>"
                if (r.errorText) out << "<pre style=\"${F}margin:6px 0 0;white-space:pre-wrap;font-size:12px;line-height:1.5;color:${INK};\">${esc(r.errorText as String)}</pre>"
                String countsText = countsLine(r, copy)
                if (countsText) out << "<div style=\"color:${MUTED};\">${esc(countsText)}</div>"
                if (r.files) out << "<div style=\"color:${MUTED};\">${esc(copy.text('run.files', [files: (r.files as List).join(', ')]))}</div>"
                if (r.runUrl) out << "<div style=\"font-size:12px;color:${DIM};word-break:break-all;\">${esc(r.runUrl as String)}</div>"
                out << "</div>"
            }
        }

        out << title(copy.text("watchdog.title"))
        List<Map> watchdog = list(body.watchdog)
        if (!watchdog) out << p(copy.text("watchdog.none"), DIM)
        watchdog.each { Map w ->
            out << "<div style=\"${F}padding:10px 0;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.55;color:${INK};\">" <<
                    "<div>${esc(tenantLabel(body, w.tenant))} · ${esc(checkLabel(w))}</div>" <<
                    "<div style=\"color:${MUTED};\">${esc(w.kind as String)} · ${esc(watchdogLine(body, w, copy))}</div></div>"
        }

        out << title(copy.text("logs.title"))
        List<Map> logs = list(body.logErrors)
        if (!logs) out << p(copy.text("logs.none"), DIM)
        logs.each { Map l ->
            out << "<div style=\"${F}padding:10px 0;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.55;color:${INK};\">" <<
                    "<div style=\"word-break:break-all;\">${esc(l.fingerprint as String)}</div>" <<
                    "<div style=\"color:${MUTED};\">${esc(seenLine(body, l, copy))}</div>"
            if (l.sampleMessage) out << "<pre style=\"${F}margin:6px 0 0;white-space:pre-wrap;font-size:12px;line-height:1.5;color:${INK};\">${esc(l.sampleMessage as String)}</pre>"
            if (l.sampleStack) out << "<details><summary style=\"font-size:12px;color:${MUTED};cursor:pointer;\">${esc(copy.text('log.stack'))}</summary>" <<
                    "<pre style=\"${F}margin:6px 0 0;white-space:pre-wrap;font-size:11px;line-height:1.45;color:${MUTED};\">${esc(l.sampleStack as String)}</pre></details>"
            out << "</div>"
        }
        List<String> leftOut = ignoredLines(body, copy)
        if (leftOut) out << "<pre style=\"${F}margin:10px 0 14px;white-space:pre-wrap;word-break:break-all;font-size:12px;line-height:1.5;color:${DIM};\">${esc(leftOut.join('\n'))}</pre>"

        out << "<div class=\"dp-note\">${esc(footer(body, copy))}</div>" <<
                "<div class=\"dp-sign\">— Darpan</div>" <<
                darpan.email.EmailCopy.guidesHtml(copy.guides()) <<
                EmailFrame.close()
        return out.toString()
    }

    static String emailText(Map body, EmailCopyReader copy) {
        Map counts = (body.counts ?: [:]) as Map
        List<String> parts = [empty(body) ? copy.text("none") : copy.text("intro", [checks: counts.checks ?: 0, logErrors: counts.logErrors ?: 0])]

        List<String> runs = [copy.text("runs.title")]
        List<Map> runErrors = list(body.runErrors)
        if (!runErrors) runs << copy.text("runs.none")
        runErrors.groupBy { it.tenant }.each { Object tenant, List<Map> rows ->
            runs << tenantLabel(body, tenant)
            rows.each { Map r ->
                List<String> lines = ["- " + subjectLine(body, r), "  " + kindLine(r, copy)]
                if (r.errorText) lines.addAll((r.errorText as String).readLines().collect { "  " + it })
                String countsText = countsLine(r, copy)
                if (countsText) lines << "  " + countsText
                if (r.files) lines << "  " + copy.text("run.files", [files: (r.files as List).join(", ")])
                if (r.runUrl) lines << "  " + r.runUrl
                runs << lines.join("\n")
            }
        }
        parts << runs.join("\n\n")

        List<String> watch = [copy.text("watchdog.title")]
        List<Map> watchdog = list(body.watchdog)
        if (!watchdog) watch << copy.text("watchdog.none")
        watchdog.each { Map w ->
            watch << "- ${tenantLabel(body, w.tenant)} · ${checkLabel(w)}\n  ${w.kind} · ${watchdogLine(body, w, copy)}".toString()
        }
        parts << watch.join("\n\n")

        List<String> logs = [copy.text("logs.title")]
        List<Map> logErrors = list(body.logErrors)
        if (!logErrors) logs << copy.text("logs.none")
        logErrors.each { Map l ->
            List<String> lines = ["- " + l.fingerprint, "  " + seenLine(body, l, copy)]
            if (l.sampleMessage) lines << "  " + l.sampleMessage
            if (l.sampleStack) {
                lines << "  " + copy.text("log.stack") + ":"
                lines.addAll((l.sampleStack as String).readLines().collect { "    " + it })
            }
            logs << lines.join("\n")
        }
        List<String> leftOut = ignoredLines(body, copy)
        if (leftOut) logs << leftOut.join("\n")
        parts << logs.join("\n\n")

        parts << footer(body, copy)
        parts << "— Darpan"
        return EmailFrame.textOf(parts) + darpan.email.EmailCopy.guidesText(copy.guides())
    }

    /** The summary posted to errors.chat.webhookUrl: emailed (sent), no address (noemail), or the send failed (failed). */
    static String chatLine(Map counts, String addresses, String state, EmailCopyReader copy) {
        Map vars = [checks: counts?.checks ?: 0, logErrors: counts?.logErrors ?: 0, addresses: addresses ?: ""]
        switch (state) {
            case "sent": return copy.text("chat.sent", vars)
            case "failed": return copy.text("chat.failed", vars)
            default: return copy.text("chat.noemail", vars)
        }
    }

    private static boolean empty(Map body) {
        Map counts = (body.counts ?: [:]) as Map
        return !((counts.checks ?: 0) as int) && !((counts.logErrors ?: 0) as int)
    }

    private static List<Map> list(Object rows) { (rows ?: []) as List<Map> }

    private static String p(String text, String color = INK) {
        return "<div style=\"${F}margin:0 0 14px;font-size:14px;line-height:1.65;color:${color};\">${esc(text)}</div>".toString()
    }

    private static String title(String text) { return EmailFrame.eyebrow(text, 22) }

    private static String tenantLabel(Map body, Object tenant) {
        String name = ((body.tenantNames ?: [:]) as Map)[tenant]
        return name && name != tenant ? "${name} (${tenant})".toString() : (tenant ?: "") as String
    }

    private static String checkLabel(Map row) {
        return [[row.auditName, row.statement].findAll { it }.join(" "), row.check].findAll { it }.join(" · ")
    }

    private static String subjectLine(Map body, Map r) {
        if (r.check) return checkLabel(r)
        String name = ((body.automationNames ?: [:]) as Map)[r.automationId]
        return (name ?: r.automationId ?: "") as String
    }

    private static String kindLine(Map r, EmailCopyReader copy) {
        List<String> bits = [r.kind as String]
        if (r.daysInARow != null && (r.daysInARow as int) >= 2) bits << copy.text("run.streak", [days: r.daysInARow])
        if (r.triggerType && r.triggerType != "scheduled") bits << (r.triggerType as String)
        if (r.completedDate) bits << EmailFrame.when(r.completedDate as Timestamp, UTC)
        return bits.join(" · ")
    }

    private static String countsLine(Map r, EmailCopyReader copy) {
        String read = sides(r.readCounts as Map), kept = sides(r.keptCounts as Map)
        if (!read && !kept) return null
        return copy.text("run.counts", [read: read ?: "-", kept: kept ?: "-"])
    }

    private static String sides(Map counts) {
        if (!counts) return null
        List<String> bits = []
        if (counts.file1 != null) bits << "side 1 ${String.format('%,d', counts.file1 as long)}".toString()
        if (counts.file2 != null) bits << "side 2 ${String.format('%,d', counts.file2 as long)}".toString()
        return bits ? bits.join(", ") : null
    }

    private static String watchdogLine(Map body, Map w, EmailCopyReader copy) {
        switch (w.kind) {
            case ErrorDigestAssembler.STALE:
                return w.lastSuccessDate ? copy.text("watchdog.stale", [last: EmailFrame.when(w.lastSuccessDate as Timestamp, UTC)]) : copy.text("watchdog.never")
            case ErrorDigestAssembler.NO_AUTOMATION: return copy.text("watchdog.noautomation")
            case ErrorDigestAssembler.EMPTY_STREAK: return copy.text("watchdog.emptystreak", [days: body.emptyStreakDays ?: 7])
            default: return ""
        }
    }

    private static String seenLine(Map body, Map l, EmailCopyReader copy) {
        // The row's count is its UTC day's count as of now (a day's row grows until the day ends), hence its day and "so far".
        String day = l.logDay?.toString() ?: (l.lastSeen ? (l.lastSeen as Timestamp).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() : "")
        String line = copy.text("log.seen", [count: l.count ?: 0, day: day, first: EmailFrame.when(l.firstSeen as Timestamp, UTC),
                                              last: EmailFrame.when(l.lastSeen as Timestamp, UTC)])
        return l.tenantId ? "${line} ${tenantLabel(body, l.tenantId)}".toString() : line
    }

    static final int IGNORED_SHOWN = 10

    /**
     * DAR-BE-396: how many lines errors.email.ignorePatterns left out, then each left-out fingerprint with its count (the
     * IGNORED_SHOWN biggest, then how many more), so a flood the list hides still shows. Empty when none: a quiet list
     * stays quiet.
     */
    private static List<String> ignoredLines(Map body, EmailCopyReader copy) {
        int n = (((body.counts ?: [:]) as Map).ignoredLogErrors ?: 0) as int
        if (n <= 0) return []
        List<Map> rows = list(body.ignoredLogErrors)
        List<String> out = [copy.text("logs.ignored", [count: n])]
        rows.take(IGNORED_SHOWN).each { Map r -> out << "  ${r.fingerprint} · ${r.count}".toString() }
        if (rows.size() > IGNORED_SHOWN) out << "  " + copy.text("logs.ignored.more", [count: rows.size() - IGNORED_SHOWN])
        return out
    }

    private static String footer(Map body, EmailCopyReader copy) {
        return copy.text("footer", [instance: body.instance ?: "", since: EmailFrame.when(body.since as Timestamp, UTC),
                                    now: EmailFrame.when(body.now as Timestamp, UTC),
                                    generatedAt: EmailFrame.when((body.generatedAt ?: body.now) as Timestamp, UTC)])
    }
}
