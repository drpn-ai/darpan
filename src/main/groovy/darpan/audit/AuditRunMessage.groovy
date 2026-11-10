package darpan.audit

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle

/**
 * DAR-BE-207. The run update's words on the page, from the fact sheet (AuditRunFacts) and the optional writer's words
 * ({headline, fixes {"R10|cause": sentence}}; empty when no writer answered). Layout follows the reference design in the
 * wrapper's tools/audit-message/render.py: the email carries every detail, the chat card a summary and the link only
 * (Aditi, 2026-11-10). Inline styles for mail clients; no bold anywhere. Pure.
 */
class AuditRunMessage {
    static final String RED = "#b3261e", AMBER = "#9a6700", GREEN = "#1e7e34", MUTED = "#5f6368", INK = "#202124"
    private static final String F = "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
    private static final String MONO = "font-family:SFMono-Regular,Menlo,Consolas,monospace;"
    private static final String NO_CAUSE = "No cause named yet"

    /** "Gorjana Month close, 1–10 November: 42 open findings". */
    static String subject(Map facts) {
        return "${facts.tenant?.name} ${facts.audit?.name}, ${span(facts.window as Map)}: ${totals(facts).open} open findings".toString()
    }

    static String title(Map facts) { return "${facts.tenant?.name} · ${facts.audit?.name}".toString() }

    static String subtitle(Map facts) { return "Run of ${day(facts.date as String, true)} · ${span(facts.window as Map)}".toString() }

    /** The audit page for the run, or one rule of it; null when the app's address is not configured. */
    static String reportUrl(String base, Map facts, String rule = null) {
        if (!base || !facts.audit?.id || !facts.date) return null
        String url = "${base}/audits/${enc(facts.audit.id as String)}?period=${enc(facts.period as String)}&date=${facts.date}"
        return rule ? "${url}&statement=${enc(rule)}".toString() : url.toString()
    }

    /** <tenant id lower>-<audit code>-<period>-<rule>.csv */
    static String csvName(Map facts, String rule) {
        return "${(facts.tenant?.id as String)?.toLowerCase()}-${facts.audit?.id}-${facts.period}-${rule}.csv".toString()
    }

    /** The fix line a group shows: the writer's sentence for it when there is one, else the cause's own fix. */
    static String fixLine(Map group, Map words) {
        Map fixes = (words?.fixes ?: [:]) as Map
        return (fixes.get("${group.rule}|${group.cause ?: ''}".toString()) ?: group.fixText) as String
    }

    /** The three largest groups that have a cause: what to act on first. */
    static List<Map> actOnFirst(Map facts) {
        return groups(facts).findAll { cause(it) != NO_CAUSE }.sort(false) { Map a, Map b -> (b.count as int) <=> (a.count as int) }.take(3)
    }

    static List<String> rulesNotRun(Map facts) {
        return rules(facts).findAll { it.checksNotRun }*.id as List<String>
    }

    /** Google Chat cardsV2: the counts, the writer's headline, what did not run, and the report's link. Nothing else. */
    static Map card(Map facts, Map words, String base) {
        Map t = totals(facts)
        List<Map> widgets = [[decoratedText: [text     : "<font color=\"${RED}\">${t.open} open</font>  ·  ${t.newToday} new  ·  " +
                "<font color=\"${GREEN}\">${t.fixedToday} fixed</font>".toString(),
                                              wrapText : true, topLabel: "Findings",
                                              startIcon: [materialIcon: [name: "fact_check"]]]]]
        if (words?.headline) widgets << [textParagraph: [text: esc(words.headline as String)]]
        List<String> notRun = rulesNotRun(facts)
        if (notRun) widgets << [decoratedText: [text     : "<font color=\"${AMBER}\">${esc(notRun.join(', '))} did not run</font>".toString(),
                                                wrapText : true, startIcon: [materialIcon: [name: "warning"]]]]
        String url = reportUrl(base, facts)
        if (url) widgets << [buttonList: [buttons: [[text: "Open the report", onClick: [openLink: [url: url]]]]]]
        return [text   : "${title(facts)}: ${t.open} open findings".toString(),
                cardsV2: [[cardId: "audit-run", card: [
                        header  : [title: title(facts), subtitle: subtitle(facts), imageUrl: "https://drpn.ai/favicon.png", imageType: "CIRCLE"],
                        sections: [[widgets: widgets]]]]]]
    }

    /** The email body: header, Open/New/Fixed tiles, act on first, the rules, one section per rule with open findings. */
    static String emailHtml(Map facts, Map words, String base) {
        Map t = totals(facts)
        String url = reportUrl(base, facts)
        StringBuilder rows = new StringBuilder()
        rules(facts).each { Map r ->
            int open = r.open as int
            rows << "<tr><td style=\"${F}padding:8px 0;border-top:1px solid #eceef1;font-size:13px;color:${MUTED};width:44px;\">${esc(r.id)}</td>" <<
                    "<td style=\"${F}padding:8px 8px;border-top:1px solid #eceef1;font-size:14px;color:${open ? INK : MUTED};\">${esc(firstSentence(r.text as String))}</td>" <<
                    "<td style=\"${F}padding:8px 0;border-top:1px solid #eceef1;font-size:14px;text-align:right;white-space:nowrap;color:${open ? RED : GREEN};\">" <<
                    "${open ? open + ' open' : 'clean'}</td></tr>"
        }
        StringBuilder act = new StringBuilder()
        actOnFirst(facts).each { Map g ->
            act << "<tr><td style=\"${F}padding:6px 10px 6px 0;font-size:20px;color:${RED};width:56px;vertical-align:top;\">${g.count}</td>" <<
                    "<td style=\"${F}padding:6px 0;font-size:14px;color:${INK};vertical-align:top;\">${esc(cause(g))}" <<
                    "<div style=\"font-size:12px;color:${MUTED};\">${esc(g.rule)}</div></td></tr>"
        }
        StringBuilder blocks = new StringBuilder()
        rules(facts).findAll { (it.open as int) > 0 }.each { Map r ->
            StringBuilder items = new StringBuilder()
            groups(facts).findAll { it.rule == r.id }.each { Map g ->
                String fix = fixLine(g, words)
                String ids = g.ids ? "<div style=\"${MONO}font-size:12px;color:${MUTED};margin-top:6px;line-height:1.6;\">${esc((g.ids as List).join(', '))}</div>" : ""
                String fx = fix ? "<div style=\"${F}font-size:13px;color:${MUTED};margin-top:2px;\">${esc(fix)}</div>" : ""
                items << "<tr><td style=\"${F}padding:10px 12px 10px 0;font-size:18px;color:${INK};width:48px;vertical-align:top;\">${g.count}</td>" <<
                        "<td style=\"${F}padding:10px 0;border-top:1px solid #eceef1;vertical-align:top;\">" <<
                        "<div style=\"font-size:14px;color:${INK};\">${esc(cause(g))}</div>${fx}${ids}</td></tr>"
            }
            String ruleUrl = reportUrl(base, facts, r.id as String)
            String link = ruleUrl ? " · <a href=\"${esc(ruleUrl)}\" style=\"color:#1a73e8;text-decoration:none;\">open ${esc(r.id)} in Darpan</a>" : ""
            blocks << "<div style=\"margin-top:28px;\">" <<
                    "<div style=\"${F}font-size:12px;color:${MUTED};letter-spacing:.04em;\">${esc(r.id)} · ${r.open} OPEN · ${r.newToday} NEW</div>" <<
                    "<div style=\"${F}font-size:16px;color:${INK};margin:2px 0 8px;\">${esc(firstSentence(r.text as String))}</div>" <<
                    "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">${items}</table>" <<
                    "<div style=\"${F}font-size:12px;color:${MUTED};margin-top:8px;\">All ${r.open} in <span style=\"${MONO}\">${esc(csvName(facts, r.id as String))}</span>${link}</div></div>"
        }
        String headline = words?.headline ? "<p style=\"${F}font-size:15px;line-height:1.5;color:${INK};margin:20px 0 0;\">${esc(words.headline as String)}</p>" : ""
        List<String> notRun = rulesNotRun(facts)
        String warn = notRun ? "<p style=\"${F}font-size:13px;color:${AMBER};margin:12px 0 0;\">${esc(notRun.join(', '))} did not run on this morning.</p>" : ""
        String actSection = act ? "<div style=\"${F}font-size:12px;color:${MUTED};letter-spacing:.04em;margin-top:24px;\">ACT ON FIRST</div>" +
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;margin-top:6px;\">${act}</table>" : ""
        String button = url ? "<div style=\"margin-top:28px;\"><a href=\"${esc(url)}\" style=\"${F}display:inline-block;background:#1a73e8;color:#fff;" +
                "font-size:14px;padding:10px 18px;border-radius:8px;text-decoration:none;\">Open the report in Darpan</a></div>" : ""
        return """<!doctype html><html><head><meta charset="utf-8"><title>${esc(subject(facts))}</title></head>
<body style="margin:0;background:#eef0f3;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#eef0f3;padding:24px 12px;"><tr><td align="center">
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;max-width:640px;background:#ffffff;border-radius:14px;"><tr><td style="padding:28px 28px 32px;">
<div style="${F}font-size:12px;color:${MUTED};letter-spacing:.04em;">DARPAN AUDIT</div>
<div style="${F}font-size:22px;color:${INK};margin-top:4px;">${esc(title(facts))}</div>
<div style="${F}font-size:14px;color:${MUTED};margin-top:2px;">${esc(subtitle(facts))}</div>
${headline}${warn}
<table role="presentation" cellpadding="0" cellspacing="8" style="width:100%;margin:16px -8px 0;border-collapse:separate;"><tr>
${tile("Open", t.open, RED)}${tile("New", t.newToday, INK)}${tile("Fixed", t.fixedToday, GREEN)}</tr></table>
${actSection}
<div style="${F}font-size:12px;color:${MUTED};letter-spacing:.04em;margin-top:24px;">RULES</div>
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;margin-top:6px;border-collapse:collapse;">${rows}</table>
${blocks}
${button}
<p style="${F}font-size:12px;color:${MUTED};margin:24px 0 0;line-height:1.5;">Each attachment has one row per open finding: its ids in HotWax, NetSuite and Shopify, when it was first found, and the fix. Ids are HotWax order ids (return ids for returns).</p>
</td></tr></table></td></tr></table></body></html>""".toString()
    }

    /** The plain-text part: the same content, line by line. */
    static String emailText(Map facts, Map words, String base) {
        Map t = totals(facts)
        List<String> lines = [title(facts), subtitle(facts)]
        if (words?.headline) lines += ["", words.headline as String]
        List<String> notRun = rulesNotRun(facts)
        if (notRun) lines += ["", "${notRun.join(', ')} did not run on this morning.".toString()]
        lines += ["", "Open ${t.open} · New ${t.newToday} · Fixed ${t.fixedToday}".toString()]
        List<Map> act = actOnFirst(facts)
        if (act) {
            lines += ["", "Act on first"]
            act.each { Map g -> lines << "  ${g.count}  ${cause(g)} (${g.rule})".toString() }
        }
        lines += ["", "Rules"]
        rules(facts).each { Map r ->
            lines << "  ${r.id}  ${firstSentence(r.text as String)}: ${(r.open as int) ? r.open + ' open' : 'clean'}".toString()
        }
        rules(facts).findAll { (it.open as int) > 0 }.each { Map r ->
            lines += ["", "${r.id} · ${r.open} open · ${r.newToday} new · ${firstSentence(r.text as String)}".toString()]
            groups(facts).findAll { it.rule == r.id }.each { Map g ->
                lines << "  ${g.count}  ${cause(g)}".toString()
                String fix = fixLine(g, words)
                if (fix) lines << "      ${fix}".toString()
                if (g.ids) lines << "      ${(g.ids as List).join(', ')}".toString()
            }
            String ruleUrl = reportUrl(base, facts, r.id as String)
            lines << "  All ${r.open} in ${csvName(facts, r.id as String)}${ruleUrl ? ' · ' + ruleUrl : ''}".toString()
        }
        String url = reportUrl(base, facts)
        if (url) lines += ["", "Open the report in Darpan: ${url}".toString()]
        return lines.join("\n") + "\n"
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

    private static String cause(Map group) {
        String c = group.cause as String
        return !c || c == "Unexplained" ? NO_CAUSE : c
    }

    private static String firstSentence(String text) {
        return (text ?: "").split(/\. /)[0].replaceAll(/\.$/, "")
    }

    private static String tile(String label, Object value, String color) {
        return "<td style=\"padding:14px 16px;background:#f6f7f9;border-radius:10px;width:33%;\">" +
                "<div style=\"${F}font-size:12px;color:${MUTED};letter-spacing:.02em;\">${label}</div>" +
                "<div style=\"${F}font-size:28px;color:${color};margin-top:2px;\">${value}</div></td>"
    }

    private static String enc(String value) { return URLEncoder.encode(value ?: "", "UTF-8") }

    private static Map totals(Map facts) { return (facts.totals ?: [open: 0, newToday: 0, fixedToday: 0]) as Map }

    private static List<Map> rules(Map facts) { return (facts.rules ?: []) as List<Map> }

    private static List<Map> groups(Map facts) { return (facts.groups ?: []) as List<Map> }
}
