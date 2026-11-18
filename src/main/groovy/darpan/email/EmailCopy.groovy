package darpan.email

import groovy.xml.XmlSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-303: the words of an email, read from EmailCopyBlock rows so a wording fix is an edit in production, not a
 * release. A screen asks for a block with its variables; the layout stays in the screen.
 *
 * A production edit (copyText) may use only the ${variables} that appear in the block's defaultText. At render, an edit
 * that breaks any rule (unknown variable, empty, any exception) is ignored and defaultText is used, with a warning in
 * the log: an email never fails to send because of its words. Copy is plain text; the caller's label encodes it, and
 * the guide links go only through {@link EmailFrame#guides} (docs.drpn.ai page paths), so an edit cannot add a link.
 */
class EmailCopy {
    private static final Logger logger = LoggerFactory.getLogger(EmailCopy.class)
    private static final Pattern SLOT = Pattern.compile(/\$\{([^}]*)\}/)

    static final String GUIDES = "guides"

    /** The block's words with its variables filled in: the edit when it is valid, else the default. Never throws. */
    static String text(def ec, String emailTemplateId, String blockId, Map vars) {
        try {
            def row = row(ec, emailTemplateId, blockId)
            String defaultText = defaultOf(ec, row, emailTemplateId, blockId)
            if (defaultText == null) return ""
            Set<String> allowed = templateVariables(ec, emailTemplateId)
            allowed.addAll(variables(defaultText))
            String edit = row?.getString("copyText")
            if (edit?.trim()) {
                try {
                    String why = problem(emailTemplateId, blockId, edit)
                    if (why != null) throw new IllegalArgumentException(why)
                    return apply(edit, vars, allowed)
                } catch (Throwable t) {
                    logger.warn("Email copy {}/{}: the edit is not usable ({}); the default goes", emailTemplateId, blockId, t.message)
                }
            }
            return apply(defaultText, vars, allowed)
        } catch (Throwable t) {
            logOnce(emailTemplateId, blockId, "failed: ${t.class.simpleName} ${t.message}")
            return ""
        }
    }

    private static final String URL_LIKE = /(?i)(:\/\/|www\.|\b[a-z0-9-]+\.(ai|com|io|net|org|co|app|dev|xyz|in|us|uk)\b)/

    /**
     * Why this text may not be saved as the block's edit, or null. Pure. Copy is words only: outside its ${...} slots it
     * may not carry a web address or host (a mail client would auto-link it: the phishing shape in a security email),
     * and a smile block must pass EmailSmile.ok. The guides block is checked by EmailFrame.guides instead.
     */
    static String problem(String emailTemplateId, String blockId, String text) {
        if (!text?.trim()) return "The text is empty"
        if (blockId == GUIDES) return null
        String outside = SLOT.matcher(text).replaceAll(" ")
        if (outside =~ URL_LIKE) return "The text carries a web address or host; links go only through the user guide block"
        if (blockId?.startsWith("smile") && !EmailSmile.ok(text)) return "A smile line is one short plain line (no digits, address, markup or \"!\")"
        return null
    }

    /** Every ${variable} any block of the template uses: what an edit to any block of it may use. Rows and seed file. */
    static Set<String> templateVariables(def ec, String emailTemplateId) {
        Set<String> names = new LinkedHashSet<>()
        seedDefaults(ec).each { String key, String text -> if (key.startsWith(emailTemplateId + "/")) names.addAll(variables(text)) }
        try {
            ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", emailTemplateId).useCache(false).list()
                    .each { names.addAll(variables(it.getString("defaultText"))) }
        } catch (Throwable t) {
            logOnce(emailTemplateId, "*", "variables read failed: ${t.message}")
        }
        return names
    }

    /**
     * The block's user guide links ([[label, docs page path], ...], one or two): the `guides` block's lines of
     * `Label|path`. An edit that EmailFrame.guides refuses (a bad path, a repeated page, none, three) falls back to the
     * default; with no usable default at all the list is empty and guidesHtml/guidesText give "" (the email still sends).
     */
    static List<List<String>> guides(def ec, String emailTemplateId) {
        try {
            def row = row(ec, emailTemplateId, GUIDES)
            String defaultText = defaultOf(ec, row, emailTemplateId, GUIDES)
            if (defaultText == null) return []
            String edit = row?.getString("copyText")
            if (edit?.trim()) {
                try {
                    List<List<String>> parsed = parseGuides(edit)
                    EmailFrame.guides(parsed)
                    return parsed
                } catch (Throwable t) {
                    logger.warn("Email copy {}/{}: the edit is not usable ({}); the default goes", emailTemplateId, GUIDES, t.message)
                }
            }
            return parseGuides(defaultText)
        } catch (Throwable t) {
            logOnce(emailTemplateId, GUIDES, "failed: ${t.class.simpleName} ${t.message}")
            return []
        }
    }

    /** The guide links as html, or "" when there are none or they cannot be built: a send never fails on its links. */
    static String guidesHtml(List<List<String>> links) {
        try { return links ? EmailFrame.guides(links) : "" } catch (Throwable t) { return "" }
    }

    /** The plain-text guide links, or "" likewise. */
    static String guidesText(List<List<String>> links) {
        try { return links ? EmailFrame.guidesText(links) : "" } catch (Throwable t) { return "" }
    }

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet()

    private static void logOnce(String emailTemplateId, String blockId, String what) {
        if (LOGGED.add(emailTemplateId + "/" + blockId + what)) logger.error("Email copy {}/{}: {}", emailTemplateId, blockId, what)
    }

    /** Lines of `Label|path` to [[label, path], ...]; blank lines are skipped. Pure. */
    static List<List<String>> parseGuides(String text) {
        return (text ?: "").readLines().collect { it.trim() }.findAll { it }.collect { String line ->
            int bar = line.lastIndexOf("|")
            if (bar < 1) throw new IllegalArgumentException("A guide line is Label|page path: ${line}")
            [line.substring(0, bar).trim(), line.substring(bar + 1).trim()] as List<String>
        }
    }

    /** The names of the ${...} slots in text (`a`, `b.c`), in first-seen order. Pure. */
    static Set<String> variables(String text) {
        Set<String> names = new LinkedHashSet<>()
        Matcher m = SLOT.matcher(text ?: "")
        while (m.find()) names.add(m.group(1).trim())
        return names
    }

    /** text with every ${name} replaced from vars (dotted names walk maps; a missing value is empty). Throws on a name not in allowed. Pure. */
    static String apply(String text, Map vars, Set<String> allowed) {
        Matcher m = SLOT.matcher(text ?: "")
        StringBuffer out = new StringBuffer()
        while (m.find()) {
            String name = m.group(1).trim()
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown variable \${${name}}")
            Object value = lookup(vars, name)
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)))
        }
        m.appendTail(out)
        return out.toString()
    }

    private static Object lookup(Map vars, String name) {
        Object current = vars
        for (String part : name.split("\\.")) {
            if (!(current instanceof Map)) return null
            current = ((Map) current).get(part)
        }
        return current
    }

    /**
     * The block's released words: its row's defaultText, or, while the row is not loaded yet (a deploy before the seed
     * or upgrade data load, a test database), the same words straight from data/EmailCopySeedData.xml, so an email never
     * goes out empty. Null (logged) only for a block the seed does not have either.
     */
    private static String defaultOf(def ec, def row, String emailTemplateId, String blockId) {
        String fromRow = row?.getString("defaultText")
        if (fromRow != null) return fromRow
        String fromFile = seedDefaults(ec).get(emailTemplateId + "/" + blockId)
        if (fromFile == null) logOnce(emailTemplateId, blockId, "no EmailCopyBlock row and no seed default")
        else logOnce(emailTemplateId, blockId, "no EmailCopyBlock row yet; the seed file's default goes")
        return fromFile
    }

    private static volatile Map<String, String> seedDefaultsCache

    private static Map<String, String> seedDefaults(def ec) {
        Map<String, String> cached = seedDefaultsCache
        if (cached != null) return cached
        Map<String, String> found = new HashMap<>()
        try {
            ec.resource.getLocationReference("component://darpan/data/EmailCopySeedData.xml").openStream().withCloseable { InputStream is ->
                def root = new XmlSlurper().parse(is)
                root.children().each { node ->
                    if (node.name() == "darpan.email.EmailCopyBlock") {
                        found.put(node.@emailTemplateId.text() + "/" + node.@blockId.text(), node.@defaultText.text())
                    }
                }
            }
        } catch (Throwable t) {
            logOnce("*", "*", "could not read data/EmailCopySeedData.xml: ${t.message}")
        }
        seedDefaultsCache = found
        return found
    }

    private static def row(def ec, String emailTemplateId, String blockId) {
        try {
            return ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", emailTemplateId)
                    .condition("blockId", blockId).useCache(false).one()
        } catch (Throwable t) {
            logOnce(emailTemplateId, blockId, "the row read failed (${t.class.simpleName}); the seed default goes")
            return null
        }
    }
}
