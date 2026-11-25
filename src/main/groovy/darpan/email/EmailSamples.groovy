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
    ].asImmutable()

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
