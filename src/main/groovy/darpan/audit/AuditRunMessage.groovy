package darpan.audit

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
    static final String RED = "#b3261e", AMBER = "#9a6700", GREEN = "#1e7e34"
    /** Email: darpan-ui light theme, --text, --text-soft, --text-muted, --text-dim, --border, --border-soft, --surface-2, --accent. */
    private static final String INK = "#141414", SOFT = "#383838", MUTED = "#5f5f5f", DIM = "#7f7f7f",
                                LINE = "#d4d4d4", LINE_SOFT = "#e1e1e1", TILE = "#f5f5f5", ACCENT = "#1c1c1c"
    private static final String F = "font-family:'IBM Plex Mono',SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;"
    private static final String LINK = "color:${INK};text-decoration:underline;text-decoration-color:${LINE};text-underline-offset:3px;"
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
            String cell = "${F}padding:9px 0;border-top:1px solid ${LINE_SOFT};vertical-align:top;"
            rows << "<tr><td style=\"${cell}font-size:12px;color:${MUTED};width:44px;\">${esc(r.id)}</td>" <<
                    "<td style=\"${cell}padding-right:12px;font-size:13px;line-height:1.55;color:${open ? INK : DIM};\">${esc(firstSentence(r.text as String))}</td>" <<
                    "<td style=\"${cell}font-size:13px;text-align:right;white-space:nowrap;color:${open ? INK : DIM};\">" <<
                    "${open ? open + ' open' : 'clean'}</td></tr>"
        }
        StringBuilder act = new StringBuilder()
        actOnFirst(facts).each { Map g ->
            act << "<tr><td style=\"${F}padding:9px 12px 9px 0;border-top:1px solid ${LINE_SOFT};font-size:20px;line-height:1.2;color:${INK};width:56px;vertical-align:top;\">${g.count}</td>" <<
                    "<td style=\"${F}padding:9px 0;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.55;color:${INK};vertical-align:top;\">${esc(cause(g))}" <<
                    "<div style=\"font-size:12px;color:${MUTED};\">${esc(g.rule)}</div></td></tr>"
        }
        StringBuilder blocks = new StringBuilder()
        rules(facts).findAll { (it.open as int) > 0 }.each { Map r ->
            StringBuilder items = new StringBuilder()
            groups(facts).findAll { it.rule == r.id }.each { Map g ->
                String fix = fixLine(g, words)
                String ids = g.ids ? "<div style=\"${F}font-size:12px;color:${DIM};margin-top:6px;line-height:1.6;\">${esc((g.ids as List).join(', '))}</div>" : ""
                String fx = fix ? "<div style=\"${F}font-size:12px;line-height:1.55;color:${MUTED};margin-top:2px;\">${esc(fix)}</div>" : ""
                items << "<tr><td style=\"${F}padding:10px 12px 10px 0;border-top:1px solid ${LINE_SOFT};font-size:16px;line-height:1.3;color:${INK};width:48px;vertical-align:top;\">${g.count}</td>" <<
                        "<td style=\"${F}padding:10px 0;border-top:1px solid ${LINE_SOFT};vertical-align:top;\">" <<
                        "<div style=\"font-size:13px;line-height:1.55;color:${INK};\">${esc(cause(g))}</div>${fx}${ids}</td></tr>"
            }
            String ruleUrl = reportUrl(base, facts, r.id as String)
            String link = ruleUrl ? " · <a href=\"${esc(ruleUrl)}\" style=\"${LINK}\">open ${esc(r.id)} in Darpan</a>" : ""
            blocks << "<div style=\"margin-top:20px;padding:16px 18px;border:1px solid ${LINE};border-radius:10px;\">" <<
                    eyebrow("${esc(r.id)} · ${r.open} OPEN · ${r.newToday} NEW", 0) <<
                    "<div style=\"${F}font-size:15px;line-height:1.45;letter-spacing:-.01em;color:${INK};margin:4px 0 10px;\">${esc(firstSentence(r.text as String))}</div>" <<
                    "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">${items}</table>" <<
                    "<div style=\"${F}font-size:12px;color:${MUTED};margin-top:10px;padding-top:10px;border-top:1px dashed ${LINE};\">All ${r.open} in ${esc(csvName(facts, r.id as String))}${link}</div></div>"
        }
        String headline = words?.headline ? "<p style=\"${F}font-size:14px;line-height:1.6;color:${SOFT};margin:18px 0 0;\">${esc(words.headline as String)}</p>" : ""
        List<String> notRun = rulesNotRun(facts)
        String warn = notRun ? "<p style=\"${F}font-size:13px;line-height:1.5;color:${INK};margin:14px 0 0;padding:10px 12px;border:1px solid ${INK};border-radius:8px;\">" +
                "${esc(notRun.join(', '))} did not run on this morning.</p>" : ""
        String actSection = act ? eyebrow("ACT ON FIRST", 28) +
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;margin-top:8px;border-collapse:collapse;\">${act}</table>" : ""
        String button = url ? "<div style=\"margin-top:28px;\"><a href=\"${esc(url)}\" style=\"${F}display:inline-block;background:${ACCENT};color:#ffffff;" +
                "border:1px solid ${ACCENT};font-size:13px;padding:11px 20px;border-radius:8px;text-decoration:none;\">Open the report in Darpan</a></div>" : ""
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light only"><meta name="supported-color-schemes" content="light only">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&amp;display=swap" rel="stylesheet">
<title>${esc(subject(facts))}</title></head>
<body style="margin:0;background:${TILE};">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${TILE};padding:28px 12px;"><tr><td align="center">
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;max-width:640px;background:#ffffff;border:1px solid ${LINE};border-radius:10px;"><tr><td style="padding:28px 28px 30px;">
${eyebrow("DARPAN · AUDIT", 0)}
<div style="${F}font-size:20px;line-height:1.35;letter-spacing:-.015em;color:${INK};margin-top:8px;">${esc(title(facts))}</div>
<div style="${F}font-size:13px;color:${MUTED};margin-top:4px;">${esc(subtitle(facts))}</div>
${headline}${warn}
<table role="presentation" cellpadding="0" cellspacing="8" style="width:100%;margin:18px -8px 0;border-collapse:separate;"><tr>
${tile("Open", t.open, true)}${tile("New", t.newToday, false)}${tile("Fixed", t.fixedToday, false)}</tr></table>
${actSection}
${eyebrow("RULES", 28)}
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;margin-top:8px;border-collapse:collapse;">${rows}</table>
${blocks}
${button}
<p style="${F}font-size:12px;color:${DIM};margin:28px 0 0;padding-top:14px;border-top:1px solid ${LINE_SOFT};line-height:1.6;">Each attachment has one row per open finding: its ids in HotWax, NetSuite and Shopify, when it was first found, and the fix. Ids are HotWax order ids (return ids for returns).</p>
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

    /** The app's summary tile: a zero reads as empty (dashed, dim); a count that needs acting on gets the ink border. */
    private static String tile(String label, Object value, boolean attention) {
        boolean empty = (value ?: 0) as int == 0
        String border = empty ? "1px dashed ${LINE}" : attention ? "1px solid ${INK}" : "1px solid ${LINE}"
        return "<td style=\"padding:14px 16px;background:${empty ? '#ffffff' : TILE};border:${border};border-radius:8px;width:33%;\">" +
                "<div style=\"${F}font-size:11px;color:${MUTED};letter-spacing:.06em;text-transform:uppercase;\">${label}</div>" +
                "<div style=\"${F}font-size:26px;line-height:1.2;letter-spacing:-.015em;color:${empty ? DIM : INK};margin-top:4px;\">${value}</div></td>"
    }

    /** The app's eyebrow label: small, muted, widely tracked caps. */
    private static String eyebrow(String text, int marginTop) {
        return "<div style=\"${F}font-size:11px;color:${MUTED};letter-spacing:.13em;text-transform:uppercase;margin-top:${marginTop}px;\">${text}</div>"
    }

    private static String enc(String value) { return URLEncoder.encode(value ?: "", "UTF-8") }

    private static Map totals(Map facts) { return (facts.totals ?: [open: 0, newToday: 0, fixedToday: 0]) as Map }

    private static List<Map> rules(Map facts) { return (facts.rules ?: []) as List<Map> }

    private static List<Map> groups(Map facts) { return (facts.groups ?: []) as List<Map> }
}
