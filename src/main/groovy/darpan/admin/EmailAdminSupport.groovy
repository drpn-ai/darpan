package darpan.admin

import darpan.email.EmailCopy
import darpan.email.EmailSamples
import groovy.transform.CompileDynamic

/**
 * DAR-BE-303: the work behind admin.EmailAdminServices (list, show, preview, edit and reset an email's words and subject).
 * Every method returns a Map; a refusal is {error: "..."} and has saved nothing. Validation is EmailCopy.editProblem, the
 * same rules for preview and save; a preview renders the screen the way a send does with the draft standing in for the
 * stored edit (EmailCopy.withDraft) and writes nothing. The caller has checked super admin.
 */
@CompileDynamic
class EmailAdminSupport {
    private static final String TEMPLATE = "moqui.basic.email.EmailTemplate"
    private static final String BLOCK = "darpan.email.EmailCopyBlock"

    /** Templates Darpan ships (those with a seeded subject), one entry each: id, description, subject, editedBlockCount, subjectEdited. */
    static List<Map> list(def ec) {
        List<Map> out = []
        templates(ec).each { def t ->
            String id = t.getString("emailTemplateId")
            List rows = rows(ec, id)
            out << [emailTemplateId: id, description: t.getString("description"),
                    subject: EmailCopy.effectiveSubject(ec, id),
                    editedBlockCount: rows.count { it.getString("blockId") != EmailCopy.SUBJECT && it.getString("copyText")?.trim() },
                    subjectEdited: rows.any { it.getString("blockId") == EmailCopy.SUBJECT && it.getString("copyText")?.trim() }]
        }
        return out
    }

    /** One template: subject and its shipped default, every block with its default, edit and the variables an edit may use, and the sample. */
    static Map show(def ec, String emailTemplateId) {
        def template = template(ec, emailTemplateId)
        if (template == null) return [error: "No such email ${emailTemplateId}".toString()]
        List<String> variables = new ArrayList<String>(EmailCopy.templateVariables(ec, emailTemplateId)).sort()
        Map<String, Object> byId = [:]
        EmailCopy.seedBlockDefaults(ec).each { String key, String text ->
            if (key.startsWith(emailTemplateId + "/")) byId.put(key.substring(emailTemplateId.length() + 1), [defaultText: text])
        }
        rows(ec, emailTemplateId).each { def r ->
            String blockId = r.getString("blockId")
            if (blockId == EmailCopy.SUBJECT) return
            Map b = byId.get(blockId) ?: [:]
            b.defaultText = r.getString("defaultText") ?: b.defaultText
            b.copyText = r.getString("copyText")
            b.lastChangedByUserId = r.getString("lastChangedByUserId")
            b.lastChangedDate = r.get("lastChangedDate")
            byId.put(blockId, b)
        }
        List<Map> blocks = byId.keySet().sort().collect { String blockId ->
            Map b = byId.get(blockId)
            [blockId: blockId, defaultText: b.defaultText, copyText: b.copyText?.trim() ? b.copyText : null, variables: variables,
             lastChangedByUserId: b.lastChangedByUserId, lastChangedDate: b.lastChangedDate]
        }
        def subjectRow = row(ec, emailTemplateId, EmailCopy.SUBJECT)
        String edit = subjectRow?.getString("copyText")
        String screen = screenOf(template)
        return [emailTemplateId: emailTemplateId, description: template.getString("description"),
                subject: EmailCopy.effectiveSubject(ec, emailTemplateId), defaultSubject: EmailCopy.defaultSubject(ec, emailTemplateId),
                subjectEdited: edit?.trim() as boolean,
                subjectVariables: new ArrayList<String>(EmailCopy.variables(EmailCopy.defaultSubject(ec, emailTemplateId))),
                blocks: blocks, branches: EmailSamples.branches(screen), sample: EmailSamples.params(screen, null) ?: [:]]
    }

    /** The email as a send renders it, with the draft subject and blocks in place and nothing saved: [html, text, subject]. */
    static Map preview(def ec, String emailTemplateId, String subject, Map blocks, String branch) {
        def template = template(ec, emailTemplateId)
        if (template == null) return [error: "No such email ${emailTemplateId}".toString()]
        String problem = draftProblem(ec, emailTemplateId, subject, blocks)
        if (problem != null) return [error: problem]
        String screen = screenOf(template)
        Map params = EmailSamples.params(screen, branch)
        if (params == null) return [error: "No such sample branch ${branch} for ${emailTemplateId}".toString()]
        Map<String, String> draft = [:]
        (blocks ?: [:]).each { k, v -> draft.put(emailTemplateId + "/" + k, String.valueOf(v)) }
        String location = template.getString("bodyScreenLocation")
        String webapp = template.getString("webappName") ?: "webroot"
        String subjectText = subject != null ? subject : EmailCopy.effectiveSubject(ec, emailTemplateId)
        Map out = [:]
        ec.context.push()
        try {
            ec.context.putAll(params)
            EmailCopy.withDraft(draft) {
                ["html", "text"].each { String mode ->
                    out.put(mode, ec.screen.makeRender().rootScreen(location).webappName(webapp).renderMode(mode).render())
                }
            }
            out.subject = ec.resource.expand(subjectText, "")
        } finally {
            ec.context.pop()
        }
        return out
    }

    /** Saves the subject and block edits, all or nothing: every one is validated first. Writes one AdminAuditLog row. */
    static Map update(def ec, String emailTemplateId, String subject, Map blocks) {
        def template = template(ec, emailTemplateId)
        if (template == null) return [error: "No such email ${emailTemplateId}".toString()]
        if (subject == null && !blocks) return [error: "Nothing to save: give a subject or blocks"]
        String problem = draftProblem(ec, emailTemplateId, subject, blocks)
        if (problem != null) return [error: problem]
        List<String> changed = []
        (blocks ?: [:]).each { k, v ->
            String blockId = k as String
            setCopy(ec, emailTemplateId, blockId, String.valueOf(v))
            changed << blockId
        }
        if (subject != null) {
            setCopy(ec, emailTemplateId, EmailCopy.SUBJECT, subject)
            template.set("subject", subject)
            template.update()
            changed << EmailCopy.SUBJECT
        }
        AdminAuditSupport.record(ec, "admin.EmailAdminServices.update#EmailTemplate", "EmailTemplate", emailTemplateId,
                "Edited ${changed.join(', ')}".toString())
        return [updated: changed]
    }

    /**
     * Puts the shipped words back: the named blocks (all blocks when neither blockIds nor subject is given) and the subject
     * (when subject is true, or when nothing is named). Writes one AdminAuditLog row.
     */
    static Map reset(def ec, String emailTemplateId, List blockIds, Boolean subject) {
        def template = template(ec, emailTemplateId)
        if (template == null) return [error: "No such email ${emailTemplateId}".toString()]
        boolean everything = !blockIds && subject == null
        Set<String> known = blockIdsOf(ec, emailTemplateId)
        List<String> ids = everything ? new ArrayList<String>(known).sort() : (blockIds ?: []).collect { it as String }
        List<String> unknown = ids.findAll { !known.contains(it) }
        if (unknown) return [error: "No such block ${unknown.join(', ')} in ${emailTemplateId}".toString()]
        List<String> reset = []
        ids.each { String blockId ->
            def r = row(ec, emailTemplateId, blockId)
            if (r != null && r.getString("copyText") != null) {
                setCopy(ec, emailTemplateId, blockId, null)
                reset << blockId
            }
        }
        if (everything || subject) {
            def r = row(ec, emailTemplateId, EmailCopy.SUBJECT)
            if (r != null && r.getString("copyText") != null) {
                setCopy(ec, emailTemplateId, EmailCopy.SUBJECT, null)
                reset << EmailCopy.SUBJECT
            }
            String shipped = EmailCopy.defaultSubject(ec, emailTemplateId)
            if (shipped != null && template.getString("subject") != shipped) {
                template.set("subject", shipped)
                template.update()
                if (!reset.contains(EmailCopy.SUBJECT)) reset << EmailCopy.SUBJECT
            }
        }
        AdminAuditSupport.record(ec, "admin.EmailAdminServices.reset#EmailTemplate", "EmailTemplate", emailTemplateId,
                reset ? "Reset ${reset.join(', ')} to the shipped words".toString() : "Reset: nothing was edited")
        return [reset: reset]
    }

    // ---------------------------------------------------------------- helpers

    /** The first reason a draft subject or any block of it may not be used, or null. */
    private static String draftProblem(def ec, String emailTemplateId, String subject, Map blocks) {
        Set<String> known = blockIdsOf(ec, emailTemplateId)
        if (subject != null) {
            String why = EmailCopy.subjectProblem(ec, emailTemplateId, subject)
            if (why != null) return "Subject: ${why}".toString()
        }
        for (def e : (blocks ?: [:]).entrySet()) {
            String blockId = e.key as String
            if (!known.contains(blockId)) return "No such block ${blockId} in ${emailTemplateId}".toString()
            if (!(e.value instanceof CharSequence)) return "Block ${blockId}: the text must be a string".toString()
            String why = EmailCopy.editProblem(ec, emailTemplateId, blockId, e.value as String)
            if (why != null) return "Block ${blockId}: ${why}".toString()
        }
        return null
    }

    /** Block ids an email has (every seeded block, plus any row), without the subject. */
    private static Set<String> blockIdsOf(def ec, String emailTemplateId) {
        Set<String> ids = new LinkedHashSet<>()
        EmailCopy.seedBlockDefaults(ec).keySet().each { String key ->
            if (key.startsWith(emailTemplateId + "/")) ids.add(key.substring(emailTemplateId.length() + 1))
        }
        rows(ec, emailTemplateId).each { ids.add(it.getString("blockId")) }
        ids.remove(EmailCopy.SUBJECT)
        return ids
    }

    private static void setCopy(def ec, String emailTemplateId, String blockId, String copyText) {
        def r = row(ec, emailTemplateId, blockId)
        if (r == null) {
            String shipped = blockId == EmailCopy.SUBJECT ? EmailCopy.defaultSubject(ec, emailTemplateId)
                    : EmailCopy.seedBlockDefaults(ec).get(emailTemplateId + "/" + blockId)
            r = ec.entity.makeValue(BLOCK)
            r.setAll([emailTemplateId: emailTemplateId, blockId: blockId, defaultText: shipped ?: ""])
            r.create()
        }
        r.set("copyText", copyText)
        r.set("lastChangedByUserId", ec.user.userId)
        r.set("lastChangedDate", ec.user.nowTimestamp)
        r.update()
    }

    private static String screenOf(def template) {
        String location = template.getString("bodyScreenLocation") ?: ""
        return location.substring(location.lastIndexOf("/") + 1).replace(".xml", "")
    }

    private static List templates(def ec) {
        Set<String> shipped = EmailCopy.seedSubjects(ec).keySet()
        return ec.entity.find(TEMPLATE).condition("emailTemplateId", org.moqui.entity.EntityCondition.IN, shipped)
                .orderBy("emailTemplateId").useCache(false).list()
    }

    private static def template(def ec, String emailTemplateId) {
        if (!EmailCopy.seedSubjects(ec).containsKey(emailTemplateId)) return null
        return ec.entity.find(TEMPLATE).condition("emailTemplateId", emailTemplateId).useCache(false).one()
    }

    private static List rows(def ec, String emailTemplateId) {
        return ec.entity.find(BLOCK).condition("emailTemplateId", emailTemplateId).useCache(false).list()
    }

    private static def row(def ec, String emailTemplateId, String blockId) {
        return ec.entity.find(BLOCK).condition("emailTemplateId", emailTemplateId).condition("blockId", blockId).useCache(false).one()
    }
}
