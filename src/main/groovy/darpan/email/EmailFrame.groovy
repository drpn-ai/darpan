package darpan.email

import java.sql.Timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DAR-BE-298: the frame every Darpan email wears, so a notice looks like the audit email and the app: the darpan-ui
 * light theme (style.css :root[data-theme='light']), IBM Plex Mono at one weight, greys only, a white card with a 1px
 * border on the --surface-2 tile, the eyebrow label, hierarchy from borders (dashed = the quiet line).
 *
 * A notice screen (screen/email/*.xml) opens with {@link #open}, writes its copy as containers whose style names one
 * of the classes below, and ends with {@link #close}. The classes live in the head's style block because Moqui
 * renders a container's style as a class; the card's td carries the font and colour inline, so a client that drops
 * the style block still shows the theme's type. The audit email builds its own inner rows and shares this shell.
 *
 * Every email ends with one or two user guide links ({@link #guides}, docs.drpn.ai pages only, address shown).
 *
 * Classes: dp-p (a paragraph), dp-code (a code to type, shown as the app's tile), dp-note (the not-you line, behind
 * a dashed rule), dp-sign (the sign-off), dp-pre (preformatted digest text).
 */
class EmailFrame {
    /** darpan-ui light theme: --text, --text-soft, --text-muted, --text-dim, --border, --border-soft, --surface-2, --accent. */
    static final String INK = "#141414", SOFT = "#383838", MUTED = "#5f5f5f", DIM = "#7f7f7f",
                        LINE = "#d4d4d4", LINE_SOFT = "#e1e1e1", TILE = "#f5f5f5", ACCENT = "#1c1c1c"
    static final String F = "font-family:'IBM Plex Mono',SFMono-Regular,Menlo,Monaco,Consolas,'Liberation Mono','Courier New',monospace;"

    private static final String STYLE = """<style>
.dp-p{margin:0 0 14px;font-size:14px;line-height:1.65;color:${INK};}
.dp-code{display:inline-block;margin:2px 0 16px;padding:14px 18px;background:${TILE};border:1px solid ${LINE};border-radius:8px;font-size:26px;line-height:1.2;letter-spacing:.18em;color:${INK};}
.dp-note{margin:22px 0 0;padding-top:14px;border-top:1px dashed ${LINE};font-size:13px;line-height:1.6;color:${SOFT};}
.dp-sign{margin:18px 0 0;font-size:13px;color:${MUTED};}
.dp-pre{margin:0;white-space:pre-wrap;font-size:13px;line-height:1.6;color:${INK};${F}}
</style>"""

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm zzz", Locale.ENGLISH)

    /**
     * Everything before the email's own content: head, tile, card, eyebrow. webFont loads IBM Plex Mono from Google
     * Fonts; notices pass false (the fallback stack is monospace), so their only outside addresses are the guide links.
     */
    static String open(String eyebrowText, String title = null, boolean webFont = false) {
        String font = webFont ? """<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&amp;display=swap" rel="stylesheet">\n""" : ""
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light only"><meta name="supported-color-schemes" content="light only">
${font}${title ? "<title>${esc(title)}</title>" : ""}${STYLE}</head>
<body style="margin:0;background:${TILE};">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${TILE};padding:28px 12px;"><tr><td align="center">
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;max-width:640px;background:#ffffff;border:1px solid ${LINE};border-radius:10px;"><tr><td class="dp-card" style="${F}padding:28px 28px 30px;font-size:14px;line-height:1.65;color:${INK};text-align:left;">
${eyebrow(eyebrowText, 0)}<div style="height:14px;line-height:14px;font-size:1px;">&nbsp;</div>
""".toString()
    }

    static String close() { return "</td></tr></table></td></tr></table></body></html>" }

    /** The user guide every link goes to; a template names a page path only, so no email can link anywhere else. */
    static final String DOCS = "https://docs.drpn.ai"
    private static final String LINK = "color:${INK};text-decoration:underline;text-decoration-color:${LINE};text-underline-offset:3px;"

    /**
     * DAR-BE-298 (Aditi 2026-11-18: "include 1-2 user guide links always", "dont send the same link twice in one
     * email": two links are two different pages, whatever their anchors): the email's guide links, after the
     * sign-off. guides is [[label, page path], ...], one or two of them; each shows its address beside it, so a reader
     * sees where it goes before clicking, and the path is a docs.drpn.ai page (never a sign-in or action page).
     */
    static String guides(List<List<String>> guides) {
        String rows = checked(guides).collect { List<String> g ->
            "<div style=\"margin-top:6px;\"><a href=\"${esc(url(g[1]))}\" style=\"${LINK}\">${esc(g[0])}</a>" +
                    " <span style=\"font-size:12px;color:${DIM};\">${esc(shown(g[1]))}</span></div>"
        }.join("")
        return "<div style=\"${F}margin-top:26px;padding-top:14px;border-top:1px solid ${LINE_SOFT};font-size:13px;line-height:1.6;color:${INK};\">" +
                "${eyebrow("User guide", 0)}${rows}</div>"
    }

    /** The same links for the plain-text part: label, then the full address. */
    static String guidesText(List<List<String>> guides) {
        return "\nUser guide\n" + checked(guides).collect { List<String> g -> "${g[0]}: ${url(g[1])}" }.join("\n") + "\n"
    }

    private static List<List<String>> checked(List<List<String>> guides) {
        if (!guides || guides.size() > 2) throw new IllegalArgumentException("An email carries one or two user guide links, not ${guides?.size() ?: 0}")
        guides.each { List<String> g ->
            if (g?.size() != 2 || !g[0] || !(g[1] ==~ /[a-z0-9\-\/]+(#[a-z0-9\-]+)?/)) {
                throw new IllegalArgumentException("A guide link is [label, docs page path], e.g. ['Two-factor sign-in', 'guides/two-factor']: ${g}")
            }
        }
        List<String> pages = guides.collect { (it[1] as String).split('#')[0] }
        if (pages.unique(false).size() != pages.size()) {
            throw new IllegalArgumentException("Two guide links to the same page (${pages}); an email links each page once")
        }
        return guides
    }

    private static String url(String path) { return "${DOCS}/${path}".toString() }

    /** What the reader sees beside the link: the page's address, without the section anchor. */
    private static String shown(String path) { return "docs.drpn.ai/${path.split('#')[0]}".toString() }

    /** The app's eyebrow label: small, muted, widely tracked caps. */
    static String eyebrow(String text, int marginTop) {
        return "<div style=\"${F}font-size:11px;color:${MUTED};letter-spacing:.13em;text-transform:uppercase;margin-top:${marginTop}px;\">${esc(text)}</div>".toString()
    }

    /**
     * A moment as the reader reads it: "18 Nov 2026 at 10:53 IST", in their own timezone (User Settings) when set,
     * else UTC, and always naming the zone. The server runs on UTC, so a bare time read as a five-hour error.
     */
    static String when(Timestamp at, String zoneId) {
        if (at == null) return ""
        ZoneId zone
        try { zone = zoneId?.trim() ? ZoneId.of(zoneId.trim()) : ZoneId.of("UTC") } catch (Exception ignored) { zone = ZoneId.of("UTC") }
        return WHEN.format(at.toInstant().atZone(zone))
    }

    static String esc(String value) {
        return (value ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
