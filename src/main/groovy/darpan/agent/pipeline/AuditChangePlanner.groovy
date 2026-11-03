package darpan.agent.pipeline

import darpan.agent.AgentCallMarker
import darpan.config.TenantConfigVersionSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.conclusion.ConclusionCatalogSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * DAR-BE-159. An agent proposal from draft to live config, with no model in the loop: create (the agent's only write: a
 * DRAFT row, provenance, plan), plan (the change checked by the real save inside a transaction that is rolled back, and a
 * planHash over the change and the config it was planned against), apply (a person, write access, provenance PASS, the
 * same planHash, then a config version before and after the save), reject, and revert (a new planned proposal that puts
 * the moved lever back). Every read and write is the active tenant's; another tenant's proposal reads as not found.
 */
class AuditChangePlanner {

    static final String ENTITY = "darpan.agent.AuditChangeProposal"
    static final String CHANGE_SOURCE = "AGENT_APPLY"
    static final List<String> OPEN = ["DRAFT", "PLANNED", "STALE", "INVALID"].asImmutable()
    static final String STALE_MESSAGE = "The config changed since this was planned. Plan it again."
    private static final int VALIDATE_TIMEOUT_SECONDS = 120
    static final String EDITOR_MESSAGE = "An editor must plan and approve this change."
    /** text-intermediate columns (statusMessage, reasonText, blockedIds, targetJson) hold 1023 characters (R24). */
    private static final int INTERMEDIATE = 1023
    private static final String WHY = "agent proposal: tenant compared on the row"

    /** What the change would close and open, from landed data only; writes nothing (AuditChangeBacktest). */
    static Closure backtest = { def ec, String tenantId, String kind, Map target, Map merged, Map current ->
        AuditChangeBacktest.run(ec, tenantId, kind, target, merged, current)
    }

    // ---------------------------------------------------------------- create

    /** The agent's draft, provenance-checked and planned. Null (with an error, nothing written) when it is off-contract. */
    static Map create(def ec, String conversationId, String kind, Map target, Map after, String reasonText, Object evidence) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        def conversation = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.agent.AgentConversation", "conversationId",
                conversationId, tenantId, WHY)
        if (conversation == null) { ec.message.addError("Conversation ${conversationId} was not found.".toString()); return null }
        try {
            AgentProvenance.validateShape(kind, after)
            AuditChangeLevers.validateTarget(kind, target)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
        String targetJson = JsonOutput.toJson(target)
        if (targetJson.length() > INTERMEDIATE) { ec.message.addError("The target is longer than ${INTERMEDIATE} characters.".toString()); return null }
        if (reasonText != null && reasonText.length() > INTERMEDIATE) { ec.message.addError("The reason is longer than ${INTERMEDIATE} characters; say it shorter.".toString()); return null }
        def row = ec.entity.makeValue(ENTITY)
        row.setAll([proposalId: TenantScopedFinder.mintId(ec, ENTITY, "proposalId"), companyUserGroupId: tenantId,
                    conversationId: conversationId, kind: kind, targetJson: targetJson, afterJson: JsonOutput.toJson(after),
                    evidenceJson: evidence == null ? null : JsonOutput.toJson(evidence), reasonText: reasonText, status: "DRAFT",
                    proposedForUserId: conversation.userId])
        row.create()

        Map current = AuditChangeLevers.current(ec, kind, target)
        // R44: an exclusion that names no field excludes by the run side's primary id expression. The resolved expression
        // is stored in afterJson, so the backtest, the plan and the person all see it before apply.
        boolean noPrimaryId = false
        if (kind == "EXCLUSION_FILTER" && current != null && after.get("fieldExpression") == null) {
            String primary = AuditChangeLevers.primaryIdExpressionOf(current, target.fileSide as String)
            if (primary) {
                after = new LinkedHashMap(after)
                after.put("fieldExpression", primary)
                row.afterJson = JsonOutput.toJson(after)
            } else {
                noPrimaryId = true
            }
        }
        Map provenance = AgentProvenance.check(kind, after, humanTexts(ec, tenantId, conversationId), openContext(ec, tenantId, conversation),
                AuditChangeLevers.configValues(kind, current))
        row.setAll([provenanceStatus: provenance.status, blockedIds: fittedList((List<String>) provenance.blockedIds)])
        // R27: anyone in the tenant may chat, but only an editor's session runs the validating save; a viewer's proposal
        // waits as a DRAFT for an editor to plan from the app.
        if (noPrimaryId) {
            ec.message.clearErrors()
            row.setAll([status: "INVALID", statusMessage: AuditChangeLevers.NO_PRIMARY_ID_MESSAGE, planHash: null])
            row.update()
            return [proposalId: row.proposalId, status: row.status, statusMessage: row.statusMessage, provenanceStatus: row.provenanceStatus,
                    blockedIds: provenance.blockedIds, backtest: null]
        }
        Map planned = current == null ? invalid(ec, row) :
                (TenantAccessSupport.hasActiveTenantWriteAccess(ec) ? planRow(ec, row, current) : draftForEditor(ec, row, current))
        return [proposalId: row.proposalId, status: row.status, statusMessage: row.statusMessage, provenanceStatus: row.provenanceStatus,
                blockedIds: provenance.blockedIds, backtest: planned.backtest]
    }

    /** Merged and backtested, not checked by a save: the row stays DRAFT until an editor plans it. */
    private static Map draftForEditor(def ec, def row, Map current) {
        Map merged
        try {
            merged = mergedFor(row, current)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return invalid(ec, row)
        }
        if (unsafeTree(ec, row)) return unsafe(row)
        Map tested = backtestOf(ec, row, merged, current)
        row.setAll([backtestJson: JsonOutput.toJson(tested), status: "DRAFT", statusMessage: EDITOR_MESSAGE])
        row.update()
        return [status: "DRAFT", statusMessage: EDITOR_MESSAGE, backtest: tested]
    }

    // ---------------------------------------------------------------- plan

    static Map plan(def ec, String proposalId) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access; an editor must plan this change.")) return null
        def row = proposalFor(ec, proposalId)
        if (row == null) return null
        if (!(row.status in OPEN)) { ec.message.addError("Proposal ${proposalId} is ${row.status}; it cannot be planned again.".toString()); return null }
        Map current = AuditChangeLevers.current(ec, row.kind as String, parse(row.targetJson))
        return current == null ? invalid(ec, row) : planRow(ec, row, current)
    }

    /** Merge, check by saving and rolling back, backtest and hash; the row ends PLANNED or INVALID. Never leaves an error. */
    private static Map planRow(def ec, def row, Map current) {
        String kind = row.kind as String
        Map merged
        try {
            merged = mergedFor(row, current)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return invalid(ec, row)
        }
        if (unsafeTree(ec, row)) return unsafe(row)
        if (kind == "STATEMENT_NARROWING" && !row.revertOfProposalId) {
            Set<String> catalog = ConclusionCatalogSupport.catalog(ec)*.id.collect { it as String } as Set<String>
            ((parse(row.afterJson).conclusionCodes ?: []) as List).findAll { !(it in catalog) }
                    .each { ec.message.addError("'${it}' is not one of this tenant's conclusion codes.".toString()) }
            if (ec.message.hasError()) return invalid(ec, row)
        }
        String refused = validateBySave(ec, kind, merged)
        if (refused) {
            ec.message.addError(refused)
            return invalid(ec, row)
        }
        Map tested = backtestOf(ec, row, merged, current)
        String hash = planHash(row.proposalId as String, merged, current)
        row.setAll([beforeJson: JsonOutput.toJson(current), backtestJson: JsonOutput.toJson(tested), planHash: hash, status: "PLANNED",
                    statusMessage: null])
        row.update()
        return [planHash: hash, before: current, after: merged, backtest: tested, status: "PLANNED"]
    }

    /**
     * Runs the lever's save in its own transaction and rolls it back, so the existing validators decide and nothing
     * persists; the config-change tracker marks a tenant pending only after a commit, so a rollback marks nothing.
     * Answers the refusal in words, or null when the save would succeed.
     */
    private static String validateBySave(def ec, String kind, Map merged) {
        String refused = null
        try {
            ec.transaction.runRequireNew(VALIDATE_TIMEOUT_SECONDS, "Agent proposal check") {
                try {
                    AuditChangeLevers.save(ec, kind, merged)
                    if (ec.message.hasError()) refused = ec.message.errorsString?.trim()
                } finally {
                    ec.transaction.rollback("Agent proposal check: validate only, nothing is kept", null)
                }
            }
        } catch (Throwable t) {
            refused = refused ?: rootMessage(t)
        }
        if (ec.message.hasError()) refused = refused ?: ec.message.errorsString?.trim()
        ec.message.clearErrors()
        return refused
    }

    // ---------------------------------------------------------------- apply

    /** A person applies a planned, provenance-PASS proposal whose plan still matches the config. Null (with an error) when refused. */
    static Map apply(def ec, String proposalId, String expectedHash) {
        if (refusedInAgentLoop(ec)) return null
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access, so it cannot apply a change.")) return null
        def row = proposalFor(ec, proposalId)
        if (row == null) return null
        if (row.status != "PLANNED") { ec.message.addError("Proposal ${proposalId} is ${row.status}; only a planned proposal can be applied.".toString()); return null }
        if (row.provenanceStatus != "PASS") {
            Object blocked = row.blockedIds ? new JsonSlurper().parseText(row.blockedIds as String) : []
            ec.message.addError("Proposal ${proposalId} is BLOCKED: ${blocked} did not come from you, the finding or the config. Confirm them in the conversation first.".toString())
            return null
        }
        String kind = row.kind as String
        String tenantId = row.companyUserGroupId as String
        // R29: the hash cannot see a HIDE or BRAIN row (the tree state holds neither), so this is checked again here.
        if (unsafeTree(ec, row)) return unsafe(row)
        Map current = AuditChangeLevers.current(ec, kind, parse(row.targetJson))
        Map merged = null
        if (current != null) {
            try { merged = mergedFor(row, current) } catch (IllegalArgumentException ignored) { merged = null }
        }
        // The hash must be the one the plan stored (what the person saw) and still match the config now.
        if (merged == null || expectedHash == null || expectedHash != row.planHash ||
                planHash(row.proposalId as String, merged, current) != expectedHash) {
            ec.message.clearErrors()
            row.setAll([status: "STALE", statusMessage: STALE_MESSAGE])
            row.update()
            return [status: "STALE", statusMessage: STALE_MESSAGE]
        }
        if (kind == "EXCLUSION_FILTER") {
            Map<String, List<String>> drifted = AuditChangeLevers.automationDrift(ec, current)
            if (drifted) {
                String fields = drifted.collect { String id, List<String> f -> "${id}: ${f.join(', ')}".toString() }.join("; ")
                ec.message.addError("AUTOMATION_DRIFT: ${drifted.keySet().join(', ')}. Syncing would also change ${fields}; sync or edit the automation first.".toString())
                return null
            }
        }
        String approver = TenantAccessSupport.currentUserId(ec)
        String reason = "Agent proposal ${row.proposalId} for ${row.proposedForUserId}, approved by ${approver}".toString()
        Map before = TenantConfigVersionSupport.snapshot(ec, tenantId, reason, CHANGE_SOURCE, approver)
        if (before == null || ec.message.hasError()) return null
        AuditChangeLevers.save(ec, kind, merged)
        if (ec.message.hasError()) return null
        Map after = TenantConfigVersionSupport.snapshot(ec, tenantId, reason, CHANGE_SOURCE, approver)
        if (after == null || ec.message.hasError()) return null
        row.setAll([status: "APPLIED", statusMessage: null, approvedByUserId: approver, approvedDate: ec.user.nowTimestamp,
                    beforeVersionId: before.version?.id, appliedVersionId: after.version?.id])
        row.update()
        if (row.revertOfProposalId) {
            def original = TenantScopedFinder.findOneOwnedByTenant(ec, ENTITY, "proposalId", row.revertOfProposalId, tenantId, WHY)
            if (original != null) { original.set("status", "REVERTED"); original.update() }
        }
        return [status: "APPLIED", appliedVersionId: row.appliedVersionId]
    }

    // ---------------------------------------------------------------- reject and revert

    static Map reject(def ec, String proposalId, String reason) {
        if (refusedInAgentLoop(ec)) return null
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access, so it cannot reject a change.")) return null
        def row = proposalFor(ec, proposalId)
        if (row == null) return null
        if (!(row.status in OPEN)) { ec.message.addError("Proposal ${proposalId} is ${row.status}; it cannot be rejected.".toString()); return null }
        row.setAll([status: "REJECTED", statusMessage: reason?.trim()?.take(INTERMEDIATE) ?: null])
        row.update()
        return [status: "REJECTED"]
    }

    /** A new planned proposal that puts back what an applied one moved; its values came from config, so provenance is PASS. */
    static Map revert(def ec, String proposalId) {
        if (refusedInAgentLoop(ec)) return null
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access, so it cannot revert a change.")) return null
        def row = proposalFor(ec, proposalId)
        if (row == null) return null
        if (row.status != "APPLIED") { ec.message.addError("Proposal ${proposalId} is ${row.status}; only an applied proposal can be reverted.".toString()); return null }
        String tenantId = row.companyUserGroupId as String
        boolean open = ec.entity.find(ENTITY).condition("companyUserGroupId", tenantId).condition("revertOfProposalId", row.proposalId)
                .condition("status", "in", OPEN + ["APPLIED"]).useCache(false).count() > 0
        if (open) { ec.message.addError("Proposal ${proposalId} already has a revert; plan, apply or reject that one.".toString()); return null }
        String kind = row.kind as String
        Map after = parse(row.afterJson)
        Map lever = AuditChangeLevers.leverOf(kind, parse(row.beforeJson), after)
        def revert = ec.entity.makeValue(ENTITY)
        revert.setAll([proposalId: TenantScopedFinder.mintId(ec, ENTITY, "proposalId"), companyUserGroupId: tenantId,
                       conversationId: row.conversationId, kind: kind, targetJson: row.targetJson, afterJson: JsonOutput.toJson(lever),
                       reasonText: "Revert of proposal ${row.proposalId}".toString(), provenanceStatus: "PASS", blockedIds: "[]",
                       status: "DRAFT", proposedForUserId: TenantAccessSupport.currentUserId(ec), revertOfProposalId: row.proposalId])
        revert.create()
        Map current = AuditChangeLevers.current(ec, kind, parse(row.targetJson))
        Map planned = current == null ? invalid(ec, revert) : planRow(ec, revert, current)
        return [newProposalId: revert.proposalId, status: revert.status, statusMessage: revert.statusMessage, planHash: planned.planHash]
    }

    // ---------------------------------------------------------------- provenance inputs (R21)

    /** The human's own words: text of the conversation's USER messages; tool results inside them are never text. */
    static List<String> humanTexts(def ec, String tenantId, String conversationId) {
        List<String> out = []
        ec.entity.find("darpan.agent.AgentMessage").condition("companyUserGroupId", tenantId).condition("conversationId", conversationId)
                .condition("kind", "USER").orderBy("sequenceNum").useCache(false).list().each { msg ->
            String raw = msg.contentJson as String
            if (!raw) return
            Object parsed
            try { parsed = new JsonSlurper().parseText(raw) } catch (Exception ignored) { out.add(raw); return }
            textsOf(parsed, out)
        }
        return out
    }

    private static void textsOf(Object node, List<String> out) {
        if (node instanceof String) { out.add((String) node); return }
        if (node instanceof List) { ((List) node).each { textsOf(it, out) }; return }
        if (node instanceof Map) {
            Map m = (Map) node
            Object type = m.get("type")
            if ((type == null || type == "text") && m.get("text") instanceof String) out.add((String) m.get("text"))
        }
    }

    /** The opened finding's structured fields only, never its records or record data. */
    static Map openContext(def ec, String tenantId, def conversation) {
        String findingId = conversation?.findingId as String
        if (!findingId) return [:]
        def f = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)
        if (f == null) return [:]
        def statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId", f.statementId, tenantId, WHY)
        def check = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditCheck", "checkId", f.checkId, tenantId, WHY)
        return [findingId: f.findingId, recordKey: f.recordKey, statementCode: statement?.statementCode, checkCode: check?.checkCode,
                conclusionCode: f.conclusionEnumId].findAll { it.value != null }
    }

    // ---------------------------------------------------------------- helpers

    /** sha256 of the proposal id, the merged state as JSON with sorted keys, and the sha256 of the current state's JSON. */
    static String planHash(String proposalId, Map merged, Map current) {
        String text = proposalId + "\n" + JsonOutput.toJson(TenantConfigVersionSupport.canonical(merged)) + "\n" +
                sha256(JsonOutput.toJson(TenantConfigVersionSupport.canonical(current)))
        return sha256(text)
    }

    /** An agent proposal merges its validated R18 after; a revert restores the lever it carries. */
    private static Map mergedFor(def row, Map current) {
        String kind = row.kind as String
        Map after = parse(row.afterJson)
        return row.revertOfProposalId ? AuditChangeLevers.restore(kind, current, after) : AuditChangeLevers.merge(kind, current, after)
    }

    /**
     * The backtest never fails a plan or a draft: whatever it throws, or any error it leaves on ec.message, is recorded as
     * BACKTEST_FAILED and cleared; errors already on ec.message before it ran are kept.
     */
    private static Map backtestOf(def ec, def row, Map merged, Map current) {
        List<String> prior = ec.message.hasError() ? new ArrayList<String>(ec.message.errors) : []
        ec.message.clearErrors()
        // R44: an exclusion's backtest names the field it excludes by (possibly the defaulted primary id), so the person sees it.
        Map shown = row.kind == "EXCLUSION_FILTER" ? [excludesBy: parse(row.afterJson).fieldExpression] : [:]
        try {
            Map tested = (Map) backtest.call(ec, row.companyUserGroupId as String, row.kind as String, parse(row.targetJson), merged, current)
            if (ec.message.hasError()) return backtestFailed(ec.message.errors[0] as String) + shown
            return tested == null ? null : tested + shown
        } catch (Exception e) {
            return backtestFailed(rootMessage(e) ?: e.class.simpleName) + shown
        } finally {
            ec.message.clearErrors()
            prior.each { ec.message.addError(it) }
        }
    }

    private static Map backtestFailed(String why) {
        return [supported: false, reason: "BACKTEST_FAILED", detail: why?.take(255), basisExecutionId: null, closed: [], opened: [],
                unchanged: 0, limits: []]
    }

    /** R29: FIX_TEXT and CONCLUSION_ROWS are refused while the run's tree holds a row the tree save cannot keep. */
    private static boolean unsafeTree(def ec, def row) {
        return (row.kind in ["FIX_TEXT", "CONCLUSION_ROWS"]) &&
                AuditChangeLevers.treeHasUnsavableRows(ec, parse(row.targetJson).savedRunId as String)
    }

    private static Map unsafe(def row) {
        row.setAll([status: "INVALID", statusMessage: AuditChangeLevers.UNSAFE_TREE_MESSAGE, planHash: null])
        row.update()
        return [status: "INVALID", statusMessage: AuditChangeLevers.UNSAFE_TREE_MESSAGE]
    }

    private static Map invalid(def ec, def row) {
        String why = ec.message.errorsString?.trim() ?: "The change cannot be planned."
        ec.message.clearErrors()
        row.setAll([status: "INVALID", statusMessage: why.take(INTERMEDIATE), planHash: null])
        row.update()
        return [status: "INVALID", statusMessage: why]
    }

    /** Defence in depth (R28): an agent tool call never approves, rejects or reverts, whatever the tool allow-list says. */
    private static boolean refusedInAgentLoop(def ec) {
        if (!AgentCallMarker.inAgentLoop()) return false
        ec.message.addError(AgentCallMarker.REFUSAL)
        return true
    }

    /** The blocked ids as a JSON list that fits its column: as many as fit, then "+N more". The service answers them all. */
    private static String fittedList(List<String> ids) {
        List<String> kept = new ArrayList<String>(ids ?: [])
        String json = JsonOutput.toJson(kept)
        while (json.length() > INTERMEDIATE && kept) {
            kept.remove(kept.size() - 1)
            json = JsonOutput.toJson(kept + ["+${ids.size() - kept.size()} more".toString()])
        }
        return json
    }

    private static def proposalFor(def ec, String proposalId) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, ENTITY, "proposalId", proposalId, tenantId, WHY)
        if (row == null) ec.message.addError("Proposal ${proposalId} was not found.".toString())
        return row
    }

    private static Map parse(Object json) {
        if (!json) return null
        Object v = new JsonSlurper().parseText(json as String)
        return v instanceof Map ? (Map) v : [list: v]
    }

    private static String sha256(String text) {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).collect { String.format("%02x", it) }.join()
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t
        while (cause.cause != null && cause.cause != cause) cause = cause.cause
        return (cause.message ?: t.message ?: t.class.simpleName).trim()
    }
}
