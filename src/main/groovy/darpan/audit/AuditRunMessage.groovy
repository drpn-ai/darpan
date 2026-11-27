package darpan.audit

import darpan.email.EmailCopy
import darpan.email.EmailCopyReader
import darpan.email.EmailFrame

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle

/**
 * DAR-BE-207. The run update's words on the page, from the fact sheet (AuditRunFacts) and the optional writer's words
 * ({headline, fixes {"R10|cause": sentence}}; empty when no writer answered). Layout follows the reference design in the
 * wrapper's tools/audit-message/render.py: the email carries every detail, the chat card a summary and the link only
 * (Aditi, 2026-11-10). Inline styles for mail clients; no bold anywhere. Pure.
 *
 * The email wears the app's light theme (Aditi, 2026-11-12: "keep the white mode ... in the theme of the app design
 * system"): darpan-ui style.css :root[data-theme='light'] tokens, IBM Plex Mono at one weight, greys only, hierarchy
 * from 1px borders (solid ink = needs attention, dashed = empty). The chat card keeps Google Chat's own colours.
 */
class AuditRunMessage {
    /** Chat card colours (Google Chat renders these in its own UI). */
    static final String RED = "#b3261e", GREEN = "#1e7e34"
    /** Email: the theme every Darpan email shares (EmailFrame, DAR-BE-298). */
    private static final String INK = EmailFrame.INK, SOFT = EmailFrame.SOFT, MUTED = EmailFrame.MUTED, DIM = EmailFrame.DIM,
                                LINE = EmailFrame.LINE, LINE_SOFT = EmailFrame.LINE_SOFT, TILE = EmailFrame.TILE, ACCENT = EmailFrame.ACCENT
    private static final String F = EmailFrame.F
    private static final String LINK = "color:${INK};text-decoration:underline;text-decoration-color:${LINE};text-underline-offset:3px;"
    /**
     * DAR-BE-303: the words are EmailCopyBlock rows of DARPAN_AUDIT_RUN, read through copy (the user guide links too);
     * what stays here is the layout and the computed data (counts, finding rows, ids, links). The report link is built
     * here from the app's address and is never part of an editable block.
     */
    static final String TEMPLATE = "DARPAN_AUDIT_RUN"

    /** "Gorjana Month close, 1–10 November: 42 open findings". */
    static String subject(Map facts, EmailCopyReader copy) {
        return copy.text("subject.run", [tenantName: facts.tenant?.name, auditName: facts.audit?.name, span: span(facts.window as Map),
                                         open: totals(facts).open])
    }

    static String title(Map facts) { return "${facts.tenant?.name} · ${facts.audit?.name}".toString() }

    /** "Run of 10 Nov 2026 · 1–10 November" (the chat card's header shares it). */
    static String subtitle(Map facts, EmailCopyReader copy) {
        return copy.text("subtitle", [date: day(facts.date as String, true), span: span(facts.window as Map)])
    }

    /** The audit page for the run, or one rule of it (tab "agent" opens its Agent tab); null when the app's address is not configured. */
    static String reportUrl(String base, Map facts, String rule = null, String tab = null) {
        if (!base || !facts.audit?.id || !facts.date) return null
        String url = "${base}/audits/${enc(facts.audit.id as String)}?period=${enc(facts.period as String)}&date=${facts.date}"
        if (rule) url = "${url}&statement=${enc(rule)}"
        if (tab) url = "${url}&tab=${enc(tab)}"
        // DAR-BE-293: the reader may be working in another tenant, and the app switches only into a tenant the link
        // names (router's tenantId guard), as a run link does; last, so the other parameters keep their places.
        String tenant = facts.tenant?.id as String
        return (tenant ? "${url}&tenantId=${enc(tenant)}" : url).toString()
    }

    /** <tenant id lower>-<audit code>-<period>-<rule>.csv */
    static String csvName(Map facts, String rule) {
        return "${(facts.tenant?.id as String)?.toLowerCase()}-${facts.audit?.id}-${facts.period}-${rule}.csv".toString()
    }

    /**
     * DAR-BE-358. Darpan's own note after a run with Unexplained findings: {subject, html, text, fileName}; the CSV is
     * attached by the caller. Every Darpan email's shape (darpan-email skill): what happened, the fact, what next, the
     * sign-off and the run email's guides. Words are copy blocks subject.unexplained and unexplained.* (editable in production).
     */
    static Map unexplainedEmail(Map facts, int count, EmailCopyReader copy) {
        Map vars = [tenantName: facts.tenant?.name, auditName: facts.audit?.name, count: count, date: day(facts.date as String, true)]
        List<String> lines = ["unexplained.intro", "unexplained.body", "unexplained.next"].collect { copy.text(it, vars) }
        String subject = copy.text("subject.unexplained", vars)
        StringBuilder html = new StringBuilder(EmailFrame.open("DARPAN · AUDIT", subject))
        lines.each { html << "<div class=\"dp-p\">${esc(it)}</div>" }
        html << "<div class=\"dp-sign\">— Darpan</div>" << EmailCopy.guidesHtml(copy.guides()) << EmailFrame.close()
        return [subject : subject,
                html    : html.toString(),
                text    : EmailFrame.textOf(lines + ["— Darpan"]) + EmailCopy.guidesText(copy.guides()),
                fileName: "${(facts.tenant?.id as String)?.toLowerCase()}-${facts.audit?.id}-${facts.date}-unexplained.csv".toString()]
    }

    /** The fix line a group shows: the writer's sentence for it when there is one, else the cause's own fix. */
    static String fixLine(Map group, Map words) {
        Map fixes = (words?.fixes ?: [:]) as Map
        return (fixes.get("${group.rule}|${group.cause ?: ''}".toString()) ?: group.fixText) as String
    }

    /** The three largest groups that have a cause: what to act on first. */
    static List<Map> actOnFirst(Map facts) {
        return groups(facts).findAll { hasCause(it) }.sort(false) { Map a, Map b -> (b.count as int) <=> (a.count as int) }.take(3)
    }

    /**
     * DAR-BE-320. One line on what the play read and what it cost: "reads", "reads.calls" when calls were counted, and
     * "reads.flags" when any read could cost less; null when the facts carry no read summary.
     */
    static String readsLine(Map facts, EmailCopyReader copy) {
        Map r = facts?.reads as Map
        if (!r) return null
        String line = copy.text("reads", [reads: r.reads, connections: r.connections])
        String calls = ((r.calls ?: 0) as long) > 0 ? copy.text("reads.calls", [calls: r.calls]) : ""
        String flags = ((r.flags ?: 0) as int) > 0 ? copy.text("reads.flags", [flags: r.flags]) : ""
        return [line, calls, flags].findAll().join(" ") ?: null
    }

    /**
     * The one top notice (DAR-BE-015), null when every check ran: the rules wholly not run ("R1 did not run this morning."), the
     * rules partly run ("R2 partly ran this morning."), then the alerted sentence once. Three copy blocks: notrun, partly.notice, alerted.
     */
    static String notRunNotice(Map facts, EmailCopyReader copy) {
        List<Map> touched = rules(facts).findAll { it.checksNotRun }
        if (!touched) return null
        List<String> whole = touched.findAll { wholeNotRun(it) }*.id as List<String>
        List<String> partly = touched.findAll { !wholeNotRun(it) }*.id as List<String>
        List<String> parts = []
        if (whole) parts << copy.text("notrun", [rules: whole.join(", ")])
        if (partly) parts << copy.text("partly.notice", [rules: partly.join(", ")])
        parts << copy.text("alerted")
        return parts.findAll { it?.trim() }*.trim().join(" ")
    }

    /** No checksTotal means every check is counted as not run. */
    private static boolean wholeNotRun(Map rule) {
        int notRun = ((rule.checksNotRun ?: []) as List).size()
        return notRun > 0 && notRun >= ((rule.checksTotal ?: 0) as int)
    }

    /** What a rule's row says: "Did not run this morning." (and its open count), the partly line and its counts, or the counts. */
    private static String ruleStatus(Map rule, EmailCopyReader copy) {
        int open = rule.open as int
        String counts = open ? copy.text("rule.open", [open: open]) : copy.text("rule.clean")
        if (wholeNotRun(rule)) return open ? "${copy.text('rule.notrun')} · ${counts}".toString() : copy.text("rule.notrun")
        if (!rule.checksNotRun) return counts
        return "${copy.text('rule.partly', [notRun: ((rule.checksNotRun ?: []) as List).size(), total: rule.checksTotal])} ${counts}".toString()
    }

    /**
     * Google Chat cardsV2 for the client's room: the counts, the writer's headline, the reads line and the report's link.
     * Never a warning (DAR-BE-378, Aditi 2026-11-26): what did not run goes to Darpan's alerts room (alertText), not here.
     */
    static Map card(Map facts, Map words, String base, EmailCopyReader copy) {
        Map t = totals(facts)
        List<Map> widgets = [[decoratedText: [text     : "<font color=\"${RED}\">${t.open} open</font>  ·  ${t.newToday} new  ·  " +
                "<font color=\"${GREEN}\">${t.fixedToday} fixed</font>".toString(),
                                              wrapText : true, topLabel: "Findings",
                                              startIcon: [materialIcon: [name: "fact_check"]]]]]
        if (words?.headline) widgets << [textParagraph: [text: esc(words.headline as String)]]
        String reads = readsLine(facts, copy)
        if (reads) widgets << [decoratedText: [text: esc(reads), wrapText: true, startIcon: [materialIcon: [name: "sync"]]]]
        String url = reportUrl(base, facts)
        if (url) widgets << [buttonList: [buttons: [[text: "Open the report", onClick: [openLink: [url: url]]]]]]
        // The card alone (Aditi 2026-11-27, DAR-BE-391): Chat shows a message's text above its card, so the one line
        // travels as fallbackText, which Chat shows only in notifications and a Slack room takes as its text.
        return [fallbackText: "${title(facts)}: ${t.open} open findings".toString(),
                cardsV2: [[cardId: "audit-run", card: [
                        header  : [title: title(facts), subtitle: subtitle(facts, copy), imageUrl: "https://docs.drpn.ai/logo/darpan-mark.png", imageType: "CIRCLE"],
                        sections: [[widgets: widgets]]]]]]
    }

    /**
     * DAR-BE-378. The alerts room's line for a run with a warning (a check that did not run or partly ran): the tenant and
     * audit, the notice, and the report's link. Null when every check ran, so a clean run alerts nobody.
     */
    static String alertText(Map facts, String base, EmailCopyReader copy) {
        String notice = notRunNotice(facts, copy)
        if (!notice) return null
        return [title(facts), subtitle(facts, copy), notice, reportUrl(base, facts)].findAll { it }.join("\n")
    }

    /** DAR-BE-361: "3 acknowledged" when the team set some findings aside today; null when none. */
    static String acknowledgedLine(Map facts, EmailCopyReader copy) {
        int n = (totals(facts).acknowledged ?: 0) as int
        return n > 0 ? copy.text("acknowledged", [count: n]) : null
    }

    /** The email body: header, Open/New/Fixed tiles, act on first, the rules, one section per rule with open findings. */
    static String emailHtml(Map facts, Map words, String base, EmailCopyReader copy) {
        Map t = totals(facts)
        String noCause = copy.text("cause.none")
        String url = reportUrl(base, facts)
        StringBuilder rows = new StringBuilder()
        rules(facts).each { Map r ->
            int open = r.open as int
            String cell = "${F}padding:9px 0;border-top:1px solid ${LINE_SOFT};vertical-align:top;"
            rows << "<tr><td style=\"${cell}font-size:12px;color:${MUTED};width:44px;\">${esc(r.id)}</td>" <<
                    "<td style=\"${cell}padding-right:12px;font-size:13px;line-height:1.55;color:${open ? INK : DIM};\">${esc(firstSentence(r.text as String))}</td>" <<
                    "<td style=\"${cell}font-size:13px;text-align:right;${r.checksNotRun ? 'max-width:260px;' : 'white-space:nowrap;'}color:${open ? INK : DIM};\">" <<
                    "${esc(ruleStatus(r, copy))}</td></tr>"
        }
        StringBuilder act = new StringBuilder()
        actOnFirst(facts).each { Map g ->
            act << "<tr><td style=\"${F}padding:9px 12px 9px 0;border-top:1px solid ${LINE_SOFT};font-size:20px;line-height:1.2;color:${INK};width:56px;vertical-align:top;\">${g.count}</td>" <<
                    "<td style=\"${F}padding:9px 0;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.55;color:${INK};vertical-align:top;\">${esc(cause(g, noCause))}" <<
                    "<div style=\"font-size:12px;color:${MUTED};\">${esc(g.rule)}</div></td></tr>"
        }
        StringBuilder blocks = new StringBuilder()
        rules(facts).findAll { (it.open as int) > 0 }.each { Map r ->
            StringBuilder items = new StringBuilder()
            // DAR-BE-252: findings with no cause are still findings; the reader is invited to explain them to the agent.
            String agentUrl = reportUrl(base, facts, r.id as String, "agent")
            groups(facts).findAll { it.rule == r.id }.each { Map g ->
                String fix = fixLine(g, words)
                String ids = g.ids ? "<div style=\"${F}font-size:12px;color:${DIM};margin-top:6px;line-height:1.6;\">${esc((g.ids as List).join(', '))}</div>" : ""
                String fx = fix ? "<div style=\"${F}font-size:12px;line-height:1.55;color:${MUTED};margin-top:2px;\">${esc(fix)}</div>" : ""
                String ask = agentUrl && !hasCause(g) ? "<div style=\"${F}font-size:12px;line-height:1.55;margin-top:2px;\">" +
                        "<a href=\"${esc(agentUrl)}\" style=\"${LINK}\">${esc(copy.text('cause.explain'))}</a></div>" : ""
                items << "<tr><td style=\"${F}padding:10px 12px 10px 0;border-top:1px solid ${LINE_SOFT};font-size:16px;line-height:1.3;color:${INK};width:48px;vertical-align:top;\">${g.count}</td>" <<
                        "<td style=\"${F}padding:10px 0;border-top:1px solid ${LINE_SOFT};vertical-align:top;\">" <<
                        "<div style=\"font-size:13px;line-height:1.55;color:${INK};\">${esc(cause(g, noCause))}</div>${fx}${ask}${ids}</td></tr>"
            }
            String ruleUrl = reportUrl(base, facts, r.id as String)
            String link = ruleUrl ? " · <a href=\"${esc(ruleUrl)}\" style=\"${LINK}\">${esc(copy.text('rule.link', [rule: r.id]))}</a>" : ""
            blocks << "<div style=\"margin-top:20px;padding:16px 18px;border:1px solid ${LINE};border-radius:10px;\">" <<
                    EmailFrame.eyebrow(copy.text("rule.head", [rule: r.id, open: r.open, new: r.newToday]).toUpperCase(Locale.ROOT), 0) <<
                    "<div style=\"${F}font-size:15px;line-height:1.45;letter-spacing:-.01em;color:${INK};margin:4px 0 10px;\">${esc(firstSentence(r.text as String))}</div>" <<
                    "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">${items}</table>" <<
                    "<div style=\"${F}font-size:12px;color:${MUTED};margin-top:10px;padding-top:10px;border-top:1px dashed ${LINE};\">${esc(copy.text('rule.all', [open: r.open, file: csvName(facts, r.id as String)]))}${link}</div></div>"
        }
        String headline = words?.headline ? "<p style=\"${F}font-size:14px;line-height:1.6;color:${SOFT};margin:18px 0 0;\">${esc(words.headline as String)}</p>" : ""
        String notRun = notRunNotice(facts, copy)
        String warn = notRun ? "<p style=\"${F}font-size:13px;line-height:1.5;color:${INK};margin:14px 0 0;padding:10px 12px;border:1px solid ${INK};border-radius:8px;\">" +
                "${esc(notRun)}</p>" : ""
        String actSection = act ? EmailFrame.eyebrow(copy.text("act.heading").toUpperCase(Locale.ROOT), 28) +
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;margin-top:8px;border-collapse:collapse;\">${act}</table>" : ""
        String button = url ? "<div style=\"margin-top:28px;\"><a href=\"${esc(url)}\" style=\"${F}display:inline-block;background:${ACCENT};color:#ffffff;" +
                "border:1px solid ${ACCENT};font-size:13px;padding:11px 20px;border-radius:8px;text-decoration:none;\">${esc(copy.text('button'))}</a></div>" : ""
        return """${EmailFrame.open("DARPAN · AUDIT", subject(facts, copy), true)}<div style="${F}font-size:20px;line-height:1.35;letter-spacing:-.015em;color:${INK};">${esc(title(facts))}</div>
<div style="${F}font-size:13px;color:${MUTED};margin-top:4px;">${esc(subtitle(facts, copy))}</div>
${headline}${warn}
<table role="presentation" cellpadding="0" cellspacing="8" style="width:100%;margin:18px -8px 0;border-collapse:separate;"><tr>
${tile(copy.text("tile.open"), t.open, true)}${tile(copy.text("tile.new"), t.newToday, false)}${tile(copy.text("tile.fixed"), t.fixedToday, false)}</tr></table>${acknowledgedLine(facts, copy) ? "<p style=\"${F}font-size:12px;color:${MUTED};margin:0;line-height:1.6;\">${esc(acknowledgedLine(facts, copy))}</p>" : ""}
${actSection}
${EmailFrame.eyebrow(copy.text("rules.heading").toUpperCase(Locale.ROOT), 28)}
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;margin-top:8px;border-collapse:collapse;">${rows}</table>
${blocks}
${button}
<p style="${F}font-size:12px;color:${DIM};margin:28px 0 0;padding-top:14px;border-top:1px solid ${LINE_SOFT};line-height:1.6;">${esc(copy.text("attachments"))}</p>${readsLine(facts, copy) ? "<p style=\"${F}font-size:12px;color:${DIM};margin:8px 0 0;line-height:1.6;\">${esc(readsLine(facts, copy))}</p>" : ""}
${EmailCopy.guidesHtml(copy.guides())}${EmailFrame.close()}""".toString()
    }

    /** The plain-text part: the same content, line by line. */
    static String emailText(Map facts, Map words, String base, EmailCopyReader copy) {
        Map t = totals(facts)
        String noCause = copy.text("cause.none")
        List<String> lines = [title(facts), subtitle(facts, copy)]
        if (words?.headline) lines += ["", words.headline as String]
        String notRun = notRunNotice(facts, copy)
        if (notRun) lines += ["", notRun]
        lines += ["", "${copy.text('tile.open')} ${t.open} · ${copy.text('tile.new')} ${t.newToday} · ${copy.text('tile.fixed')} ${t.fixedToday}".toString()]
        String acknowledged = acknowledgedLine(facts, copy)
        if (acknowledged) lines << acknowledged
        List<Map> act = actOnFirst(facts)
        if (act) {
            lines += ["", copy.text("act.heading")]
            act.each { Map g -> lines << "  ${g.count}  ${cause(g, noCause)} (${g.rule})".toString() }
        }
        lines += ["", copy.text("rules.heading")]
        rules(facts).each { Map r ->
            lines << "  ${r.id}  ${firstSentence(r.text as String)}: ${ruleStatus(r, copy)}".toString()
        }
        rules(facts).findAll { (it.open as int) > 0 }.each { Map r ->
            lines += ["", "${copy.text('rule.head', [rule: r.id, open: r.open, new: r.newToday])} · ${firstSentence(r.text as String)}".toString()]
            String agentUrl = reportUrl(base, facts, r.id as String, "agent")
            groups(facts).findAll { it.rule == r.id }.each { Map g ->
                lines << "  ${g.count}  ${cause(g, noCause)}".toString()
                String fix = fixLine(g, words)
                if (fix) lines << "      ${fix}".toString()
                if (agentUrl && !hasCause(g)) lines << "      ${copy.text('cause.explain')}: ${agentUrl}".toString()
                if (g.ids) lines << "      ${(g.ids as List).join(', ')}".toString()
            }
            String ruleUrl = reportUrl(base, facts, r.id as String)
            lines << "  ${copy.text('rule.all', [open: r.open, file: csvName(facts, r.id as String)])}${ruleUrl ? ' · ' + ruleUrl : ''}".toString()
        }
        String url = reportUrl(base, facts)
        if (url) lines += ["", "${copy.text('button')}: ${url}".toString()]
        String reads = readsLine(facts, copy)
        if (reads) lines += ["", reads]
        return lines.join("\n") + "\n" + EmailCopy.guidesText(copy.guides())
    }

    /** "1–10 November" within a month, "28 Oct – 3 Nov" across two. */
    static String span(Map window) {
        try {
            LocalDate a = LocalDate.parse(window?.from as String), b = LocalDate.parse(window?.thru as String)
            return a.month == b.month && a.year == b.year ?
                    "${a.dayOfMonth}–${b.dayOfMonth} ${b.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}".toString() :
                    "${day(window.from as String, false)} – ${day(window.thru as String, false)}".toString()
        } catch (DateTimeParseException | NullPointerException ignored) {
            return ""
        }
    }

    /** "10 Nov 2026", or "10 Nov". */
    static String day(String iso, boolean year) {
        try {
            LocalDate d = LocalDate.parse(iso)
            return "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}${year ? ' ' + d.year : ''}".toString()
        } catch (DateTimeParseException | NullPointerException ignored) {
            return iso ?: ""
        }
    }

    static String esc(Object value) {
        return (value ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace('"', "&quot;").replace("'", "&#39;")
    }

    private static boolean hasCause(Map group) { return group.cause && group.cause != "Unexplained" }

    private static String cause(Map group, String noCause) { return hasCause(group) ? group.cause as String : noCause }

    private static String firstSentence(String text) {
        return (text ?: "").split(/\. /)[0].replaceAll(/\.$/, "")
    }

    /** The app's summary tile: a zero reads as empty (dashed, dim); a count that needs acting on gets the ink border. */
    private static String tile(String label, Object value, boolean attention) {
        boolean empty = (value ?: 0) as int == 0
        String border = empty ? "1px dashed ${LINE}" : attention ? "1px solid ${INK}" : "1px solid ${LINE}"
        return "<td style=\"padding:14px 16px;background:${empty ? '#ffffff' : TILE};border:${border};border-radius:8px;width:33%;\">" +
                "<div style=\"${F}font-size:11px;color:${MUTED};letter-spacing:.06em;text-transform:uppercase;\">${esc(label)}</div>" +
                "<div style=\"${F}font-size:26px;line-height:1.2;letter-spacing:-.015em;color:${empty ? DIM : INK};margin-top:4px;\">${value}</div></td>"
    }


    private static String enc(String value) { return URLEncoder.encode(value ?: "", "UTF-8") }

    private static Map totals(Map facts) { return (facts.totals ?: [open: 0, newToday: 0, fixedToday: 0]) as Map }

    private static List<Map> rules(Map facts) { return (facts.rules ?: []) as List<Map> }

    private static List<Map> groups(Map facts) { return (facts.groups ?: []) as List<Map> }
}
