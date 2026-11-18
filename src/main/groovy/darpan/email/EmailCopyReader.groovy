package darpan.email

/**
 * DAR-BE-303: one email template's words, for code that builds an email body itself (the audit run and digest). In a
 * send it reads EmailCopyBlock rows through EmailCopy (edit, else default, never throws); with no ec (unit tests of the
 * pure builders) it reads a map of shipped defaults, `block id -> text`, by the same substitution rules.
 */
class EmailCopyReader {
    private final def ec
    final String templateId
    private final Map<String, String> defaults

    EmailCopyReader(def ec, String templateId) {
        this.ec = ec
        this.templateId = templateId
        this.defaults = null
    }

    private EmailCopyReader(String templateId, Map<String, String> defaults) {
        this.ec = null
        this.templateId = templateId
        this.defaults = defaults
    }

    /** A reader over shipped defaults only (no database): `templateId/blockId -> text`, e.g. from the seed file. */
    static EmailCopyReader ofDefaults(String templateId, Map<String, String> seed) {
        Map<String, String> mine = [:]
        seed.each { String key, String text -> if (key.startsWith(templateId + "/")) mine.put(key.substring(templateId.length() + 1), text) }
        return new EmailCopyReader(templateId, mine)
    }

    /** The block's words with ${slots} filled from vars; "" for an unknown block. */
    String text(String blockId, Map vars = [:]) {
        if (ec != null) return EmailCopy.text(ec, templateId, blockId, vars)
        String text = defaults.get(blockId)
        if (text == null) return ""
        Set<String> allowed = new LinkedHashSet<>()
        defaults.values().each { allowed.addAll(EmailCopy.variables(it)) }
        return EmailCopy.apply(text, vars, allowed)
    }

    /** The user guide links, [[label, page path], ...]. */
    List<List<String>> guides() {
        if (ec != null) return EmailCopy.guides(ec, templateId)
        String text = defaults.get(EmailCopy.GUIDES)
        return text == null ? [] : EmailCopy.parseGuides(text)
    }
}
