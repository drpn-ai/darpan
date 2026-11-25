package darpan.email

/**
 * DAR-BE-303: sample parameters for every email screen, the ones a real send passes as bodyParameters. The admin preview
 * renders a draft with them, and the render tests use the same maps, so a preview and a test show the same email. Sample
 * values are made up (Riya at Malbon); a branch is a named variation of the same screen (before/after the date, old/new
 * address, with/without the app address).
 */
class EmailSamples {
    static final String AT = "18 Nov 2026 at 10:53 IST"

    /** Security notices: no link of any kind, not even a stylesheet. Screen -> sample parameters. */
    static final Map<String, Map> SECURITY = [
            PasswordResetCode    : [firstName: "Riya", code: "042117", expiresMinutes: 15],
            PasswordChanged      : [firstName: "Riya", changedAt: AT],
            RecoveryEmailSet     : [firstName: "Riya", isOldAddress: false],
            TwoFactorPasskeyAdded: [firstName: "Riya", passkeyName: "Work laptop", at: AT],
            TwoFactorAppAdded    : [firstName: "Riya", at: AT],
            TwoFactorRecoveryUsed: [firstName: "Riya", at: AT, recoveryCodesLeft: 7],
            TwoFactorReset       : [firstName: "Riya", at: AT],
            TwoFactorCodesLocked : [firstName: "Riya", at: AT],
            TwoFactorReminder    : [firstName: "Riya", requiredDate: "2 December 2026", appUrl: "https://app.drpn.ai", past: false,
                                    subjectLine: "Set up two-factor before 2 December 2026"],
    ].asImmutable()

    static final Map<String, Map> OTHER = [
            TenantWelcome: [firstName: "Riya", tenantLabel: "Malbon", rolePhrase: "as an Editor", username: "riya.k",
                            appBaseUrl: "https://app.drpn.ai"],
            // The audit emails are built from their data by the screen (the same blocks a real send reads): see AuditEmailSamples.
            AuditDigest  : [digestReport: AuditEmailSamples.digestReport(), digestKind: "AUDDK_DAILY", digestFullList: false,
                            digestReportUrl: "https://app.drpn.ai/audits/month-close?date=2026-10-22&tenantId=GORJANA",
                            digestSubject: "Month close · 2026-10 · Thu 22 Oct"],
            AuditRun     : [runSubject: "Gorjana Month close, 1–10 November: 57 open findings", runFacts: AuditEmailSamples.runFacts(),
                            runWords: AuditEmailSamples.runWords(), runBase: AuditEmailSamples.BASE],
            // DAR-BE-015: the daily Darpan error email (Darpan staff only), built by the screen from the digest a send passes.
            ErrorDigest  : [digestSubject: "[app.drpn.ai] Darpan errors 2026-11-25: 2 checks, 37 log errors", digestBody: errorDigest()],
    ].asImmutable()

    private static java.sql.Timestamp at(String instant) { java.sql.Timestamp.from(java.time.Instant.parse(instant)) }

    /** A day with one dead-lettered check, one check with no automation and one log error (made-up tenant and values). */
    static Map errorDigest() {
        return [instance : "app.drpn.ai", since: at("2026-11-24T12:05:00Z"), now: at("2026-11-25T12:05:00Z"),
                generatedAt: at("2026-11-25T12:05:09Z"), emptyStreakDays: 7,
                counts   : [checks: 2, logErrors: 37],
                tenantNames: [MALBON: "Malbon"], automationNames: [:],
                runErrors: [[tenant: "MALBON", auditName: "Month close", statement: "R1", check: "completed-not-billed", automationId: "A1",
                             kind: "DEAD_LETTER", triggerType: "scheduled", daysInARow: 2,
                             errorText: "Keep runs on the extract's records array, and this side's key paths do not read it.",
                             readCounts: [file1: 6982, file2: 29963], keptCounts: [file1: 5525, file2: 0],
                             files: ["oms-order-lines.json", "netsuite-invoices.json"],
                             runUrl: "https://app.drpn.ai/reconciliation/run-live/SR1/RR1?tenantId=MALBON",
                             completedDate: at("2026-11-25T09:20:00Z")]],
                watchdog : [[tenant: "MALBON", auditName: "Month close", statement: "R4", check: "returns-vs-credit-memos",
                             kind: "NO_AUTOMATION", lastSuccessDate: null]],
                logErrors: [[fingerprint: "darpan.reconciliation.X Extract # failed", loggerName: "darpan.reconciliation.X", count: 37,
                             sampleMessage: "Extract 4711 failed", sampleStack: "java.lang.IllegalStateException: Extract 4711 failed\n\tat darpan.reconciliation.X.run(X.groovy:12)",
                             firstSeen: at("2026-11-25T01:00:00Z"), lastSeen: at("2026-11-25T02:00:00Z"), tenantId: "MALBON",
                             logDay: java.sql.Date.valueOf("2026-11-25")]]]
    }

    /** DAR-BE-015: chat lines kept as copy (never sent as email); the admin editor previews them through their screen. */
    static final Map<String, Map> CHAT = [
            ErrorNotice: [name: "Daily Order Reconciliation"],
    ].asImmutable()

    /** screen -> branch name -> how the base sample changes. The first branch of a screen is its default. */
    static final Map<String, Map<String, Closure<Map>>> BRANCHES = [
            RecoveryEmailSet: [new: { Map b -> b + [isOldAddress: false] }, old: { Map b -> b + [isOldAddress: true] }],
            TwoFactorReminder: [before: { Map b -> b + [past: false] }, after: { Map b -> b + [past: true] }],
            TenantWelcome: [withUrl: { Map b -> b }, withoutUrl: { Map b -> b - [appBaseUrl: b.appBaseUrl] }],
    ].asImmutable()

    static Map base(String screen) {
        Map found = SECURITY.get(screen) ?: OTHER.get(screen) ?: CHAT.get(screen)
        return found == null ? null : new LinkedHashMap(found)
    }

    /** The branch names of a screen, empty when it has one shape. */
    static List<String> branches(String screen) { return new ArrayList<String>((BRANCHES.get(screen) ?: [:]).keySet()) }

    /** The sample parameters for the screen and branch (blank = the screen's first branch); null for an unknown screen or branch. */
    static Map params(String screen, String branch) {
        Map base = base(screen)
        if (base == null) return null
        Map<String, Closure<Map>> options = BRANCHES.get(screen)
        if (!options) return branch ? null : base
        Closure<Map> change = options.get(branch ?: options.keySet().first())
        return change == null ? null : change.call(base)
    }
}
