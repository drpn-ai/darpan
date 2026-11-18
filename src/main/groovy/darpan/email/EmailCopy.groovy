package darpan.email

import groovy.xml.XmlSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
        def row = row(ec, emailTemplateId, blockId)
        String defaultText = defaultOf(ec, row, emailTemplateId, blockId)
        if (defaultText == null) return ""
        Set<String> allowed = variables(defaultText)
        String edit = row?.getString("copyText")
        if (edit?.trim()) {
            try {
                return apply(edit, vars, allowed)
            } catch (Throwable t) {
                logger.warn("Email copy {}/{}: the edit is not usable ({}); the default goes", emailTemplateId, blockId, t.message)
            }
        }
        return apply(defaultText, vars, allowed)
    }

    /**
     * The block's user guide links ([[label, docs page path], ...], one or two): the `guides` block's lines of
     * `Label|path`. An edit that EmailFrame.guides refuses (a bad path, a repeated page, none, three) falls back to the default.
     */
    static List<List<String>> guides(def ec, String emailTemplateId) {
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
        if (fromFile == null) logger.error("Email copy {}/{}: no EmailCopyBlock row and no seed default", emailTemplateId, blockId)
        else logger.warn("Email copy {}/{}: no EmailCopyBlock row yet; the seed file's default goes", emailTemplateId, blockId)
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
            logger.error("Email copy: could not read data/EmailCopySeedData.xml: {}", t.message)
            return found
        }
        seedDefaultsCache = found
        return found
    }

    private static def row(def ec, String emailTemplateId, String blockId) {
        return ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", emailTemplateId)
                .condition("blockId", blockId).useCache(false).one()
    }
}
