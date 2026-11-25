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
    /** The subject's own row in EmailCopyBlock (blockId `subject`): created by the first edit, never seeded, never read by a screen. */
    static final String SUBJECT = "subject"
    static final int MAX_CHARS = 2000
    static final int MAX_SUBJECT_CHARS = 200

    private static final ThreadLocal<Map<String, String>> DRAFT = new ThreadLocal<>()

    /**
     * Preview: runs body with `templateId/blockId -> text` standing in for those blocks' production edits, on this thread
     * only, and always clears them. Nothing is saved. Screens read through text()/guides() exactly as in a real send, so
     * the preview is the real render path with a draft in place of the stored edit.
     */
    static <T> T withDraft(Map<String, String> draft, Closure<T> body) {
        Map<String, String> before = DRAFT.get()
        DRAFT.set(draft)
        try { return body.call() } finally {
            if (before == null) DRAFT.remove() else DRAFT.set(before)
        }
    }

    /** True while a preview renders: the live agent line is not asked for (the draft's own words show). */
    static boolean previewing() { return DRAFT.get() != null }

    private static String draftOr(String emailTemplateId, String blockId, String stored) {
        Map<String, String> draft = DRAFT.get()
        String key = emailTemplateId + "/" + blockId
        return draft != null && draft.containsKey(key) ? draft.get(key) : stored
    }

    /** Templates whose blocks are each built by code with only their own variables (AuditRunMessage, AuditDigestSupport, the run error notice, the daily error email). */
    static final Set<String> OWN_VARIABLE_TEMPLATES = ["DARPAN_AUDIT_RUN", "DARPAN_AUDIT_DIGEST", "DARPAN_ERROR_NOTICE", "DARPAN_ERROR_DIGEST"] as Set<String>
    /** Prefix of the blocks that code turns into an email subject (DARPAN_2FA_REMINDER subject.before, DARPAN_AUDIT_RUN subject.run). */
    static final String SUBJECT_BLOCK_PREFIX = "subject."

    static boolean isSubjectBlock(String blockId) { return blockId != null && blockId.startsWith(SUBJECT_BLOCK_PREFIX) }

    /** True when a block may use only the variables its own shipped words use (audit emails, subject blocks), not the whole template's. */
    static boolean ownVariablesOnly(String emailTemplateId, String blockId) {
        return OWN_VARIABLE_TEMPLATES.contains(emailTemplateId) || isSubjectBlock(blockId)
    }

    /** The variables an edit of this block may use: its own default's (audit emails, subject blocks), else every one the template has. */
    static Set<String> blockVariables(def ec, String emailTemplateId, String blockId) {
        if (ownVariablesOnly(emailTemplateId, blockId)) {
            String defaultText = defaultOf(ec, row(ec, emailTemplateId, blockId), emailTemplateId, blockId)
            return new LinkedHashSet<String>(variables(defaultText))
        }
        return templateVariables(ec, emailTemplateId)
    }

    /** The block's words with its variables filled in: the edit when it is valid, else the default. Never throws. */
    static String text(def ec, String emailTemplateId, String blockId, Map vars) {
        try {
            def row = row(ec, emailTemplateId, blockId)
            String defaultText = defaultOf(ec, row, emailTemplateId, blockId)
            if (defaultText == null) return ""
            Set<String> allowed = new LinkedHashSet<String>(blockVariables(ec, emailTemplateId, blockId))
            allowed.addAll(variables(defaultText))
            String edit = draftOr(emailTemplateId, blockId, row?.getString("copyText"))
            if (edit?.trim()) {
                try {
                    String why = problem(emailTemplateId, blockId, edit)
                    if (why != null) throw new IllegalArgumentException(why)
                    // An edit may use a variable this caller does not pass (the whole template's set is allowed on a screen, but a
                    // builder passes each block only its own): the words would render with a hole, so the default goes instead.
                    Set<String> lacking = variables(edit).findAll { String name -> !(vars ?: [:]).containsKey(name.split("\\.")[0]) } as Set<String>
                    if (lacking) {
                        logOnce(emailTemplateId, blockId, "the edit uses ${lacking.collect { '${' + it + '}' }.join(', ')}, which this email does not pass for this block; the default goes")
                    } else {
                        return apply(edit, vars, allowed)
                    }
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

    /** A web address, a host of any ending, or an e-mail address, anywhere outside a ${...} slot (a mail client auto-links all of them). */
    private static final String URL_LIKE = /(?i)(:\/\/|www\.|@|\b[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}\b)/

    /**
     * Why this text may not be saved as the block's edit, or null. Pure. Copy is words only: outside its ${...} slots it
     * may not carry a web address or host (a mail client would auto-link it: the phishing shape in a security email),
     * and a smile block must pass EmailSmile.ok. The guides block is checked by EmailFrame.guides instead.
     */
    static String problem(String emailTemplateId, String blockId, String text) {
        if (!text?.trim()) return "The text is empty"
        // Every render path checks this, not only the save: a row written another way (data load, transfer) never injects markup
        // (or a chat mention such as <users/all>) into an email or the chat text.
        if (text =~ /[<>]/) return "Plain words only: no < or > (no markup)"
        if (blockId == GUIDES) return null
        // A slot becomes a letter, so `${x}.ru` is read as the host it would render as.
        String outside = SLOT.matcher(text).replaceAll("x")
        if (outside =~ URL_LIKE) return "The text carries a web address or host; links go only through the user guide block"
        if (isSubjectBlock(blockId)) {
            String shape = subjectShapeProblem(text)
            if (shape != null) return shape
        }
        if (blockId?.startsWith("smile") && !EmailSmile.ok(text)) return "A smile line is one short plain line (no digits, address, markup or \"!\")"
        return null
    }

    /**
     * Why this text may not be saved as the block's edit, or null: problem() plus the save rules. At most 2,000
     * characters, no `<` or `>`, only variables the template has (templateVariables), and for the guides block lines
     * of `Label|page` that EmailFrame.guides accepts. The subject has its own rules (subjectProblem). Pure but for reads.
     */
    static String editProblem(def ec, String emailTemplateId, String blockId, String text) {
        if (blockId == SUBJECT) return subjectProblem(ec, emailTemplateId, text)
        String why = problem(emailTemplateId, blockId, text)
        if (why != null) return why
        if (text.length() > MAX_CHARS) return "The text is longer than ${MAX_CHARS} characters"
        if (isSubjectBlock(blockId) && text.length() > MAX_SUBJECT_CHARS) return "The subject text is longer than ${MAX_SUBJECT_CHARS} characters"
        if (blockId == GUIDES) {
            try {
                List<List<String>> parsed = parseGuides(text)
                parsed.each { List<String> link -> String bad = guideLabelProblem(link[0]); if (bad != null) throw new IllegalArgumentException(bad) }
                EmailFrame.guides(parsed)
            } catch (IllegalArgumentException e) { return "Guide links: " + e.message }
            return null
        }
        Set<String> unknown = variables(text) - blockVariables(ec, emailTemplateId, blockId)
        if (unknown) return "Unknown variable ${unknown.collect { '${' + it + '}' }.join(', ')}: this ${ownVariablesOnly(emailTemplateId, blockId) ? 'block' : 'email'} does not have it"
        return null
    }

    /** Why this guide link label may not be used, or null: a label is plain words, never a variable, a host or an address. Pure. */
    static String guideLabelProblem(String label) {
        if (!label?.trim()) return "A guide label is empty"
        if (SLOT.matcher(label).find()) return "A guide label takes no variables: ${label}"
        if (label =~ /[<>]/) return "A guide label is plain words: no < or >"
        if (label =~ URL_LIKE) return "A guide label carries a web address or host: ${label}"
        return null
    }

    private static final Pattern SUBJECT_SLOT = Pattern.compile(/\$\{[A-Za-z][A-Za-z0-9_]*\}/)
    private static final Pattern SUBJECT_PLAIN = Pattern.compile(/[\p{L}\p{N} .,:;!?\-'()&\/%+\u2014\u2013\u2019]*/)

    /**
     * Why this subject may not be saved, or null. Moqui expands a subject by compiling it as a Groovy string, so a
     * subject is an ALLOWLIST: outside `${name}` slots only letters, digits, spaces and . , : ; ! ? - ' ( ) & / % + and
     * the dashes and curly apostrophe; a slot is `${name}` with a plain name the shipped subject also has. Nothing else
     * (no quote, backslash, backtick, brace or `$` outside a slot, no line break, no < >). At most 200 characters.
     * The preview substitutes the slots itself (apply), and the real send's expansion of such text is the same result.
     */
    static String subjectProblem(def ec, String emailTemplateId, String text) {
        String defaultSubject = defaultSubject(ec, emailTemplateId)
        if (defaultSubject == null) return "Unknown email ${emailTemplateId}"
        if (!text?.trim()) return "The subject is empty"
        if (text.length() > MAX_SUBJECT_CHARS) return "The subject is longer than ${MAX_SUBJECT_CHARS} characters"
        String shape = subjectShapeProblem(text)
        if (shape != null) return shape
        Set<String> unknown = variables(text) - variables(defaultSubject)
        if (unknown) return "Unknown variable ${unknown.collect { '${' + it + '}' }.join(', ')}: the shipped subject does not have it"
        return null
    }

    /**
     * Why this text may not be a subject (or a subject block), or null: outside `${name}` slots only the allowlisted plain
     * characters, and no host or address. Pure. Shared by the subject edit and the subject blocks code builds a subject from.
     */
    static String subjectShapeProblem(String text) {
        String outside = SUBJECT_SLOT.matcher(text).replaceAll("x")
        if (!SUBJECT_PLAIN.matcher(outside).matches()) {
            return 'The subject may use letters, digits, spaces and basic punctuation, and variables written as ${name}.'
        }
        if (outside =~ URL_LIKE) return "The subject carries a web address or host"
        return null
    }

    /** The body screen's name for a template (EmailSamples key), from its seed-file EmailTemplate row's location in the database. */
    static String screenFor(def ec, String emailTemplateId) {
        String location = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", emailTemplateId).useCache(false).one()?.getString("bodyScreenLocation") ?: ""
        return location.substring(location.lastIndexOf("/") + 1).replace(".xml", "")
    }

    /** The subject as the preview shows it: the slots filled from vars by pure substitution, never the template engine. */
    static String previewSubject(def ec, String emailTemplateId, String text, Map vars) {
        return apply(text, vars, variables(defaultSubject(ec, emailTemplateId)))
    }

    /** The subject the release ships for a template (from the seed files that define its EmailTemplate row), or null. */
    static String defaultSubject(def ec, String emailTemplateId) { return seedSubjects(ec).get(emailTemplateId) }

    /** The subject sends use: a valid edit (the `subject` row's copyText), else the shipped default. Null for an unknown template. */
    static String effectiveSubject(def ec, String emailTemplateId) {
        String defaultSubject = defaultSubject(ec, emailTemplateId)
        if (defaultSubject == null) return null
        String edit = row(ec, emailTemplateId, SUBJECT)?.getString("copyText")
        if (edit?.trim() && subjectProblem(ec, emailTemplateId, edit) == null) return edit
        return defaultSubject
    }

    /**
     * Before every send (service/EmailCopy.secas.xml): when the template has a valid subject edit, EmailTemplate.subject
     * is set to it. A release's seed or upgrade-data reload rewrites EmailTemplate.subject to the shipped default; the
     * edit lives in its own EmailCopyBlock row that no release touches, so the next send puts it back. Never throws.
     */
    static void applySubject(def ec, String emailTemplateId) {
        try {
            String edit = row(ec, emailTemplateId, SUBJECT)?.getString("copyText")
            if (!edit?.trim()) return
            if (subjectProblem(ec, emailTemplateId, edit) != null) {
                logOnce(emailTemplateId, SUBJECT, "the subject edit is not usable; the template's subject goes")
                return
            }
            // Its own transaction: the send's is not held (row lock across SMTP) and a failed write cannot abort it.
            ec.transaction.runRequireNew(null, "Applying the subject edit failed", {
                unguarded(ec) {
                    def template = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", emailTemplateId).useCache(false).one()
                    if (template != null && template.getString("subject") != edit) {
                        template.set("subject", edit)
                        template.update()
                    }
                }
            })
        } catch (Throwable t) {
            logOnce(emailTemplateId, SUBJECT, "applying the subject edit failed: ${t.class.simpleName} ${t.message}")
        }
    }

    /** Every ${variable} any block of the template uses: what an edit to any block of it may use. Rows and seed file. */
    static Set<String> templateVariables(def ec, String emailTemplateId) {
        Set<String> names = new LinkedHashSet<>()
        seedDefaults(ec).each { String key, String text -> if (key.startsWith(emailTemplateId + "/") && !key.endsWith("/" + SUBJECT)) names.addAll(variables(text)) }
        try {
            unguarded(ec) { ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", emailTemplateId).useCache(false).list() }
                    .each { if (it.getString("blockId") != SUBJECT) names.addAll(variables(it.getString("defaultText"))) }
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
            String edit = draftOr(emailTemplateId, GUIDES, row?.getString("copyText"))
            if (edit?.trim()) {
                try {
                    List<List<String>> parsed = parseGuides(edit)
                    parsed.each { List<String> link -> String bad = guideLabelProblem(link[0]); if (bad != null) throw new IllegalArgumentException(bad) }
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
     * or upgrade data load, a test database), the same words straight from the seed files (COPY_SEED_FILES), so an email
     * never goes out empty. Null (logged) only for a block the seed does not have either.
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

    /** The seed files that hold EmailCopyBlock rows. DAR-BE-015: the run error notice (OpsNoticeSeedData) and the daily error email (OpsSeedData) ship their own. */
    private static final List<String> COPY_SEED_FILES = ["EmailCopySeedData", "OpsNoticeSeedData", "OpsSeedData"]

    private static Map<String, String> seedDefaults(def ec) {
        Map<String, String> cached = seedDefaultsCache
        if (cached != null) return cached
        Map<String, String> found = new HashMap<>()
        boolean allParsed = true
        COPY_SEED_FILES.each { String file ->
            try {
                ec.resource.getLocationReference("component://darpan/data/${file}.xml".toString()).openStream().withCloseable { InputStream is ->
                    def root = new XmlSlurper().parse(is)
                    root.children().each { node ->
                        if (node.name() == "darpan.email.EmailCopyBlock") {
                            found.put(node.@emailTemplateId.text() + "/" + node.@blockId.text(), node.@defaultText.text())
                        }
                    }
                }
            } catch (Throwable t) {
                allParsed = false
                logOnce("*", file, "could not read data/${file}.xml: ${t.message}")
            }
        }
        // A failed read is not kept (as seedSubjects): one context that cannot resolve component:// must not empty the defaults for the JVM.
        if (allParsed) seedDefaultsCache = found
        return found
    }

    private static final List<String> SUBJECT_SEED_FILES = ["PasswordResetEmailSeedData", "TwoFactorSeedData",
            "TenantWelcomeEmailSeedData", "AuditDigestEmailSeedData", "OpsNoticeSeedData", "OpsSeedData", "FeedbackEmailSeedData"]
    private static volatile Map<String, String> seedSubjectsCache

    /** Test-only: drops the seed caches and the log-once memory so the next call re-reads. Idempotent, never throws. */
    static void resetForTests() {
        try {
            seedDefaultsCache = null
            seedSubjectsCache = null
            LOGGED.clear()
            DRAFT.remove()
        } catch (Throwable ignored) { }
    }

    /** emailTemplateId -> the subject its seed file ships. */
    static Map<String, String> seedSubjects(def ec) {
        Map<String, String> cached = seedSubjectsCache
        if (cached != null) return cached
        Map<String, String> found = new HashMap<>()
        boolean allParsed = true
        SUBJECT_SEED_FILES.each { String file ->
            try {
                ec.resource.getLocationReference("component://darpan/data/${file}.xml".toString()).openStream().withCloseable { InputStream is ->
                    new XmlSlurper().parse(is).children().each { node ->
                        if (node.name() == "moqui.basic.email.EmailTemplate") found.put(node.@emailTemplateId.text(), node.@subject.text())
                    }
                }
            } catch (Throwable t) {
                allParsed = false
                logOnce("*", file, "could not read data/${file}.xml: ${t.message}")
            }
        }
        if (allParsed) seedSubjectsCache = found
        return found
    }

    /** emailTemplateId/blockId -> shipped default text, every block the seed has. */
    static Map<String, String> seedBlockDefaults(def ec) { return seedDefaults(ec) }

    /**
     * Email copy is Darpan's own configuration, read and applied for whoever sends: a password reset goes out with no one
     * signed in, and that caller has no authz on EmailCopyBlock or EmailTemplate (without this the edit would silently
     * not apply and the default would go out). Authz is off only inside body, then restored whatever happens.
     */
    private static <T> T unguarded(def ec, Closure<T> body) {
        def artifactExecution = ec.artifactExecution
        boolean wasDisabled = artifactExecution != null ? artifactExecution.disableAuthz() : true
        try { return body.call() } finally { if (artifactExecution != null && !wasDisabled) artifactExecution.enableAuthz() }
    }

    static def row(def ec, String emailTemplateId, String blockId) {
        try {
            return unguarded(ec) {
                ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", emailTemplateId)
                        .condition("blockId", blockId).useCache(false).one()
            }
        } catch (Throwable t) {
            logOnce(emailTemplateId, blockId, "the row read failed (${t.class.simpleName}); the seed default goes")
            return null
        }
    }
}
