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
     * Fonts; security notices pass false, so they carry no outside address at all (the fallback stack is monospace).
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
