package darpan.facade.common

/**
 * DAR-CLI-003 / DAR-CLI-008 — what a CLI session may call. A CLI session is a request signed in with a login key issued
 * through the CLI sign-in (CliAuthSupport.isCliSession). RpcMethodFence.enforce holds a CLI session to this list on the
 * server, so the key used directly (curl) gets no more than the CLI does.
 *
 * <p>ALLOWED (DAR-CLI-008, Aditi 2026-11-25: "everything"): every tenant-app method the user's role allows — reads, run
 * starters, tenant scripts and every tenant-app edit. The fence is not the role check: each service checks the caller's
 * tenant role itself, so a CLI key gets exactly what the same person gets in the app.
 *
 * <p>EXCLUDED: connection credentials (their saves, deletes, sharing, and any getter returning a password, token, key,
 * webhook URL, username, OAuth client id or cert id, or raw headers) — managed in the app, since a secret given to a
 * user's AI goes into its provider's transcript; sign-in, identity and CLI access; browser installs; every admin.* method
 * (the admin app and darpan-admin); and the embedded agent (a CLI session already is an agent).
 * CliMethodAllowlistTests fails when a published method is in neither list, so a new service is placed here in the
 * change that adds it. Specs: wrapper docs/superpowers/specs/2026-11-03-tenant-agent-cli-mcp-design.md 6.4 and
 * 2026-11-25-dar-cli-008-cli-user-friendly-design.md section 4.
 */
class CliMethodAllowlist {

    /** The only paths a CLI session may reach: JSON-RPC and the authenticated GraphQL endpoint, each exactly. */
    static final List<String> CLI_PATHS = ["/rpc/json", "/rest/s1/graphql"].asImmutable()

    static final Set<String> ALLOWED = ([
            // The service itself requires tenant-admin; an admin's agent may read the fresh-query log.
            "facade.AgentFacadeServices.list#AgentQueryLogs",

            // DAR-BE-313: the mascot's edited and admin-made entries; global product copy, writes nothing, no credential.
            "facade.AppContentFacadeServices.get#AppContent",

            "facade.AuditFacadeServices.get#AuditCheckExtracts",
            "facade.AuditFacadeServices.get#AuditCheckOptions",
            "facade.AuditFacadeServices.get#AuditFinding",
            // DAR-BE-361: the team's recent notes to Darpan on a finding's statement and cause; writes nothing, no credential.
            "facade.AuditFacadeServices.get#AuditFindingFeedback",
            "facade.AuditFacadeServices.get#AuditFindingsByOrder",
            "facade.AuditFacadeServices.get#AuditProgress",
            "facade.AuditFacadeServices.get#AuditReadReport",
            "facade.AuditFacadeServices.get#AuditReport",
            "facade.AuditFacadeServices.get#AuditSettings",
            "facade.AuditFacadeServices.get#Audits",
            "facade.AuditFacadeServices.retry#AuditCheck",
            "facade.AuditFacadeServices.run#Audit",
            // DAR-CLI-004: the audit page's Re-check, a run starter like retry#AuditCheck (tenant write access, reads by key,
            // updates only the finding ledger); `darpan findings recheck`.
            "facade.AuditFacadeServices.verify#AuditFinding",

            "facade.AuthFacadeServices.get#SessionInfo",

            "facade.CliAuthFacadeServices.logout#CliSession",

            "facade.ConfigSharingFacadeServices.list#ConfigTenantAccess",

            "facade.HotWaxOmsFacadeServices.get#OmsGraphqlSchema",
            "facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs",
            "facade.HotWaxOmsFacadeServices.run#OmsGraphqlAgentQuery",

            "facade.JsonSchemaFacadeServices.get#JsonSchema",
            "facade.JsonSchemaFacadeServices.list#JsonSchemas",

            "facade.NetSuiteLifecycleFacadeServices.get#LifecycleOptions",
            "facade.NetSuiteLifecycleFacadeServices.get#LifecycleRuleOptions",

            "facade.NetSuiteQueryFacadeServices.get#NsSuiteQlQueryOptions",
            "facade.NetSuiteQueryFacadeServices.get#NsSuiteQlSourceQuery",
            "facade.NetSuiteQueryFacadeServices.list#NsAgentConnections",
            "facade.NetSuiteQueryFacadeServices.list#NsSuiteQlSourceQueries",
            "facade.NetSuiteQueryFacadeServices.preview#NsSuiteQlSourceQuery",
            "facade.NetSuiteQueryFacadeServices.run#NsSuiteQlAgentQuery",

            // DAR-BE-328: peak-period and sync-pulse reads; write nothing, return no credential (chat spaces by id only).
            "facade.PeakPeriodFacadeServices.get#PeakPeriod",
            "facade.PeakPeriodFacadeServices.list#PeakPeriods",

            "facade.SyncPulseFacadeServices.get#RunPulseFlags",
            "facade.SyncPulseFacadeServices.get#SyncPair",
            "facade.SyncPulseFacadeServices.list#SyncPairs",

            "facade.ReconciliationFacadeServices.cancel#ReconciliationRun",
            "facade.ReconciliationFacadeServices.get#ActiveRunResult",
            "facade.ReconciliationFacadeServices.get#Automation",
            "facade.ReconciliationFacadeServices.get#AutomationStreaks",
            "facade.ReconciliationFacadeServices.get#GeneratedOutput",
            "facade.ReconciliationFacadeServices.get#GeneratedOutputDifferences",
            "facade.ReconciliationFacadeServices.get#LifecyclePlan",
            "facade.ReconciliationFacadeServices.get#Mapping",
            "facade.ReconciliationFacadeServices.get#Reconciliation",
            "facade.ReconciliationFacadeServices.get#ReconciliationExecution",
            "facade.ReconciliationFacadeServices.get#ReconciliationRunStatus",
            "facade.ReconciliationFacadeServices.list#AutomationExecutions",
            "facade.ReconciliationFacadeServices.list#Automations",
            "facade.ReconciliationFacadeServices.list#GeneratedOutputs",
            "facade.ReconciliationFacadeServices.list#Mappings",
            "facade.ReconciliationFacadeServices.list#ReconciliationExecutions",
            "facade.ReconciliationFacadeServices.list#Reconciliations",
            "facade.ReconciliationFacadeServices.list#SavedRuns",
            "facade.ReconciliationFacadeServices.run#AutomationNow",
            "facade.ReconciliationFacadeServices.run#GenericDiff",
            "facade.ReconciliationFacadeServices.run#Reconciliation",
            "facade.ReconciliationFacadeServices.run#SavedRunDiff",
            // DAR-CLI-007 (Aditi 2026-11-21): re-derive an automation's sources and filters from its run; the service
            // requires tenant write access. `darpan automation sync` previews both sides' filters first (DAR-BE-152).
            "facade.ReconciliationFacadeServices.sync#Automation",

            // DAR-CLI-007: a run's conclusion tree and its tenant codes; both require tenant write access on the server.
            "facade.RunConclusionFacadeServices.create#RunConclusionCode",
            "facade.RunConclusionFacadeServices.get#RunConclusions",
            "facade.RunConclusionFacadeServices.save#RunConclusions",

            "facade.SearchFacadeServices.search#NavigationTargets",

            "facade.SettingsFacadeServices.get#LlmSettings",
            // DAR-UI-123: writes nothing; its spaces carry no webhook URL.
            "facade.SettingsFacadeServices.get#NotificationRoutes",
            "facade.SettingsFacadeServices.get#TenantSettings",
            "facade.SettingsFacadeServices.get#UserNotificationDefault",
            "facade.SettingsFacadeServices.list#EnumOptions",
            "facade.SettingsFacadeServices.test#SourceConnection",

            "facade.ShopifyFacadeServices.get#ShopifyAuthConfig",
            "facade.ShopifyFacadeServices.list#ShopifyAuthConfigs",
            "facade.ShopifyFacadeServices.get#ShopifyGraphqlSchema",
            "facade.ShopifyFacadeServices.run#ShopifyGraphqlAgentQuery",

            "facade.SlackFacadeServices.get#SlackInstall",
            "facade.SlackFacadeServices.list#SlackChannels",

            "facade.SourceEndpointFacadeServices.list#SourceConfigEndpoints",

            "facade.TenantConfigFacadeServices.get#TenantConfigVersions",

            // DAR-BE-334: tenant queries and their schedules (moqui-jexl)
            "facade.TenantScriptFacadeServices.create#TenantQueryProposal",
            "facade.TenantScriptFacadeServices.delete#TenantQuery",
            "facade.TenantScriptFacadeServices.delete#TenantQuerySchedule",
            "facade.TenantScriptFacadeServices.get#TenantQueryCatalog",
            "facade.TenantScriptFacadeServices.list#TenantQueries",
            "facade.TenantScriptFacadeServices.list#TenantQuerySchedules",
            "facade.TenantScriptFacadeServices.run#TenantQuery",
            // send=true delivers the schedule now, by hand its owner only (Aditi 2026-11-04: any script deployment via the CLI)
            "facade.TenantScriptFacadeServices.run#TenantQuerySchedule",
            "facade.TenantScriptFacadeServices.save#TenantQuery",
            "facade.TenantScriptFacadeServices.save#TenantQuerySchedule",
            // DAR-CLI-008 (Aditi 2026-11-25: "everything"): app parity. Every tenant-app edit the user's role allows; each
            // service checks the caller's role itself (XML or the Groovy it calls), and view-open ones (pins, own subscriptions,
            // acknowledge/feedback) are view-open in the app too. Credentials, sign-in, installs, admin.* and the embedded agent stay EXCLUDED.
            "facade.AgentChangeFacadeServices.apply#AuditChange",
            "facade.AgentChangeFacadeServices.list#BrainChangeTickets",
            "facade.AgentChangeFacadeServices.plan#AuditChange",
            "facade.AgentChangeFacadeServices.reject#AuditChange",
            "facade.AgentChangeFacadeServices.revert#AuditChange",
            "facade.AuditFacadeServices.acknowledge#AuditFinding",
            "facade.AuditFacadeServices.add#AuditStatement",
            "facade.AuditFacadeServices.create#Audit",
            "facade.AuditFacadeServices.delete#AuditFindingAcknowledgement",
            "facade.AuditFacadeServices.get#AuditRecheckProgress",
            // DAR-BE-395: removes a check from the active tenant's audit (write access); no credential.
            "facade.AuditFacadeServices.remove#AuditCheck",
            "facade.AuditFacadeServices.save#AuditCheck",
            "facade.AuditFacadeServices.save#AuditSettings",
            "facade.AuditFacadeServices.send#AuditFindingFeedback",
            "facade.AuditFacadeServices.update#AuditFindingAcknowledgement",
            "facade.AuditFacadeServices.verify#AuditFindings",
            "facade.AuthFacadeServices.save#UserSettings",
            "facade.JsonSchemaFacadeServices.delete#JsonSchema",
            "facade.JsonSchemaFacadeServices.flatten#JsonSchema",
            "facade.JsonSchemaFacadeServices.infer#JsonSchemaFromCsvText",
            "facade.JsonSchemaFacadeServices.infer#JsonSchemaFromText",
            "facade.JsonSchemaFacadeServices.save#JsonSchemaText",
            "facade.JsonSchemaFacadeServices.save#RefinedSchema",
            "facade.JsonSchemaFacadeServices.validate#JsonTextAgainstSchema",
            "facade.NetSuiteQueryFacadeServices.save#NsSuiteQlSourceQuery",
            "facade.PeakPeriodFacadeServices.delete#PeakPeriod",
            "facade.PeakPeriodFacadeServices.delete#PeakPeriodRunTime",
            "facade.PeakPeriodFacadeServices.delete#PeakPeriodSync",
            "facade.PeakPeriodFacadeServices.save#PeakPeriod",
            "facade.PeakPeriodFacadeServices.save#PeakPeriodRunTime",
            "facade.PeakPeriodFacadeServices.save#PeakPeriodSync",
            "facade.ReconciliationFacadeServices.archive#Automation",
            "facade.ReconciliationFacadeServices.archive#SavedRun",
            "facade.ReconciliationFacadeServices.create#CsvRun",
            "facade.ReconciliationFacadeServices.create#Mapping",
            "facade.ReconciliationFacadeServices.create#RuleSetRun",
            "facade.ReconciliationFacadeServices.delete#Automation",
            "facade.ReconciliationFacadeServices.delete#GeneratedOutput",
            "facade.ReconciliationFacadeServices.delete#LifecycleFinding",
            "facade.ReconciliationFacadeServices.delete#LifecycleRule",
            "facade.ReconciliationFacadeServices.delete#LifecycleStageException",
            "facade.ReconciliationFacadeServices.delete#ReconciliationQuestion",
            "facade.ReconciliationFacadeServices.delete#SavedRun",
            "facade.ReconciliationFacadeServices.disable#SavedRun",
            "facade.ReconciliationFacadeServices.enable#SavedRun",
            "facade.ReconciliationFacadeServices.import#NetSuiteChecksAsRuns",
            "facade.ReconciliationFacadeServices.pause#Automation",
            "facade.ReconciliationFacadeServices.repair#ConnectorRecordKeys",
            "facade.ReconciliationFacadeServices.reprocess#AutomationExecution",
            "facade.ReconciliationFacadeServices.restore#Automation",
            "facade.ReconciliationFacadeServices.restore#SavedRun",
            "facade.ReconciliationFacadeServices.resume#Automation",
            "facade.ReconciliationFacadeServices.retry#AutomationExecutionFromExtracts",
            "facade.ReconciliationFacadeServices.save#Automation",
            "facade.ReconciliationFacadeServices.save#DashboardPinnedMappings",
            "facade.ReconciliationFacadeServices.save#DashboardPinnedSavedRuns",
            "facade.ReconciliationFacadeServices.save#Lifecycle",
            "facade.ReconciliationFacadeServices.save#LifecycleConclusions",
            "facade.ReconciliationFacadeServices.save#LifecycleDetails",
            "facade.ReconciliationFacadeServices.save#LifecycleFinding",
            "facade.ReconciliationFacadeServices.save#LifecycleRule",
            "facade.ReconciliationFacadeServices.save#LifecycleStageException",
            "facade.ReconciliationFacadeServices.save#LifecycleStages",
            "facade.ReconciliationFacadeServices.save#Mapping",
            "facade.ReconciliationFacadeServices.save#Reconciliation",
            "facade.ReconciliationFacadeServices.save#ReconciliationQuestion",
            "facade.ReconciliationFacadeServices.save#RuleSetRun",
            "facade.ReconciliationFacadeServices.save#SavedRunName",
            "facade.ReconciliationFacadeServices.subscribe#RunNotification",
            "facade.ReconciliationFacadeServices.unsubscribe#RunNotification",
            "facade.SettingsFacadeServices.save#TenantSettings",
            "facade.SettingsFacadeServices.save#UserNotificationDefault",
            "facade.SettingsFacadeServices.send#ChatSpaceTest",
            "facade.SettingsFacadeServices.set#NotificationRoute",
            "facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess",
            "facade.SyncPulseFacadeServices.delete#SyncPair",
            "facade.SyncPulseFacadeServices.save#SyncPair",
            "facade.TenantConfigFacadeServices.preview#TenantConfigRestore",
            "facade.TenantConfigFacadeServices.restore#TenantConfigVersion",
            "facade.TenantConfigFacadeServices.snapshot#TenantConfig",
            "facade.TenantScriptFacadeServices.save#TenantQueryPins",
            // DAR-CLI-008 part 2: a person explains a finding or marks a cause expected through their own AI: proposes a change
            // (editor or admin; refused otherwise), reads it, reads a finding's look-alikes. Apply/revert were opened in part 1.
            "facade.AgentChangeFacadeServices.get#AuditChangeProposal",
            "facade.AgentChangeFacadeServices.list#AuditChangeProposals",
            "facade.AgentChangeFacadeServices.propose#AuditChange",
            "facade.AgentReadFacadeServices.find#AuditFindingLookalikes",
            // DAR-CLI-008 part 3 / DAR-BE-374: the order lookup, answered from the ID index and the tenant's findings.
            "facade.LookupFacadeServices.lookup#Order",
            // DAR-BE-374: the ID index — search and status (view), and the tenant's own ID fields (editor or admin; a path must be
            // plain and no customer detail).
            "facade.IdIndexFacadeServices.delete#TenantIdIndexField",
            "facade.IdIndexFacadeServices.get#IdIndexStatus",
            "facade.IdIndexFacadeServices.save#TenantIdIndexField",
            "facade.IdIndexFacadeServices.search#RecordIds",
    ] as LinkedHashSet<String>).asImmutable()

    static final Set<String> EXCLUDED = ([
            // DAR-BE-159 / DAR-BE-194: the embedded agent's staff tickets and the per-user AI access grants are admin app only.
            "admin.AgentAccessAdminServices.create#AgentAccessGrant",
            "admin.AgentAccessAdminServices.get#AgentUsage",
            "admin.AgentAccessAdminServices.list#AgentAccessGrants",
            "admin.AgentAccessAdminServices.pause#AgentAccessGrant",
            "admin.AgentAccessAdminServices.revoke#AgentAccessGrant",
            "admin.AgentAccessAdminServices.unpause#AgentAccessGrant",
            "admin.AgentAccessAdminServices.update#AgentAccessGrant",
            "admin.AgentTicketAdminServices.list#AllBrainChangeTickets",
            "admin.AgentTicketAdminServices.update#BrainChangeTicket",
            "admin.FeedbackAdminServices.get#FeedbackList",
            "admin.FeedbackAdminServices.reply#Feedback",
            // DAR-BE-307: the agent's prompts, role models, prices, texts and per-tenant caps are edited by a super admin in the admin app.
            "admin.AgentConfigAdminServices.get#AgentConfig",
            "admin.AgentConfigAdminServices.get#AgentPrompt",
            "admin.AgentConfigAdminServices.reset#AgentConfigItem",
            "admin.AgentConfigAdminServices.update#AgentModelPrice",
            "admin.AgentConfigAdminServices.update#AgentPrompt",
            "admin.AgentConfigAdminServices.update#AgentRoleModel",
            "admin.AgentConfigAdminServices.update#AgentTenantDailyCap",
            "admin.AgentConfigAdminServices.update#AgentText",
            // DAR-BE-313: the mascot's entries are edited by a super admin in the admin app; a tenant's CLI token never writes them.
            "admin.AppContentAdminServices.create#AppContentEntry",
            "admin.AppContentAdminServices.delete#AppContentEntry",
            "admin.AppContentAdminServices.get#AppContentVocabulary",
            "admin.AppContentAdminServices.list#AppContent",
            "admin.AppContentAdminServices.reset#AppContentEntry",
            "admin.AppContentAdminServices.update#AppContentEntry",
            // DAR-BE-195: a CLI session never manages CLI access.
            "admin.CliAccessAdminServices.create#CliAccessGrant",
            "admin.CliAccessAdminServices.list#CliAccessGrants",
            "admin.CliAccessAdminServices.pause#CliAccessGrant",
            "admin.CliAccessAdminServices.revoke#CliAccessGrant",
            "admin.CliAccessAdminServices.unpause#CliAccessGrant",
            "admin.CliAccessAdminServices.update#CliAccessGrant",
            // DAR-BE-209: a CLI session never asks for CLI access or answers a request.
            "admin.CliAccessAdminServices.approve#CliAccessRequest",
            "admin.CliAccessAdminServices.decline#CliAccessRequest",
            "admin.CliAccessAdminServices.list#CliAccessRequests",
            "facade.CliAccessFacadeServices.get#MyCliAccess",
            "facade.CliAccessFacadeServices.request#CliAccess",

            // DAR-BE-159/223 (darpan-agent): the embedded agent is a web panel; a CLI session already is an agent and never
            // runs a turn of this one or reads its panel.
            "facade.AgentTurnFacadeServices.get#AgentConversation",
            "facade.AgentTurnFacadeServices.get#AgentStatus",
            "facade.AgentTurnFacadeServices.send#AgentQueryTurn",   // DAR-BE-334: the queries page's agent, same reason
            "facade.AgentTurnFacadeServices.send#AgentTurn",
            // DAR-BE-235: authoring data-defined reads is a super admin's, in the admin app; never a CLI session's.
            "facade.DataSourceDefinitionFacadeServices.create#DataSourceDefinition",
            "facade.DataSourceDefinitionFacadeServices.retire#DataSourceDefinition",
            "facade.DataSourceDefinitionFacadeServices.validate#DataSourceDefinition",
            // DAR-BE-215: what one finding's audit tells the embedded agent; the agent reads it, not a CLI session.
            "facade.AgentReadFacadeServices.get#AgentAuditScope",

            // DAR-BE-303: the words and subjects of Darpan's own emails are edited by a super admin in the admin app (and the
            // darpan-admin CLI), never by a tenant CLI session; the getters and the preview stay out with the writers.
            "admin.EmailAdminServices.get#EmailTemplate",
            "admin.EmailAdminServices.get#EmailTemplates",
            "admin.EmailAdminServices.preview#EmailTemplate",
            "admin.EmailAdminServices.reset#EmailTemplate",
            "admin.EmailAdminServices.update#EmailTemplate",
            // DAR-BE-304: instance settings are admin app only.
            "admin.SettingAdminServices.get#Setting",
            "admin.SettingAdminServices.get#Settings",
            "admin.SettingAdminServices.reset#Setting",
            "admin.SettingAdminServices.update#Setting",

            "admin.KnowledgeAdminServices.get#KnowledgeEntry",
            "admin.KnowledgeAdminServices.get#KnowledgeForReview",
            "admin.KnowledgeAdminServices.get#KnowledgeIdentifiers",
            "admin.KnowledgeAdminServices.retire#KnowledgeEntry",
            "admin.KnowledgeAdminServices.search#Knowledge",
            "admin.KnowledgeAdminServices.store#KnowledgeEntry",

            "admin.MembershipAdminServices.add#TenantMember",
            "admin.MembershipAdminServices.remove#TenantMember",
            "admin.MembershipAdminServices.send#TenantWelcome",
            "admin.MembershipAdminServices.update#TenantMemberRole",

            "admin.MigrationAdminServices.dryRun#Migration",
            "admin.MigrationAdminServices.dryRun#PendingMigrations",
            "admin.MigrationAdminServices.get#MigrationHistory",
            "admin.MigrationAdminServices.get#MigrationStatus",
            "admin.MigrationAdminServices.run#Migration",
            "admin.MigrationAdminServices.run#PendingMigrations",
            "admin.MigrationAdminServices.set#MigrationEnabled",

            // DAR-BE-266: the retention preview is a Darpan admin's, in the admin app.
            "admin.RetentionAdminServices.dryRun#Retention",
            "admin.RetentionAdminServices.get#RetentionPeriods",
            "admin.RetentionAdminServices.update#RetentionPeriods",

            "admin.ObserveAdminServices.get#AdminSessionInfo",
            "admin.ObserveAdminServices.get#LiveOperations",

            "admin.ScheduleAdminServices.get#ScheduleBoard",
            // DAR-BE-305: a Darpan admin's, in the admin app: scheduled jobs are edited there, not by a tenant's CLI token.
            "admin.ScheduleAdminServices.get#ServiceJobs",
            "admin.ScheduleAdminServices.preview#JobSchedule",
            "admin.ScheduleAdminServices.reset#ServiceJob",
            "admin.ScheduleAdminServices.update#ServiceJob",

            "admin.TenantAdminServices.create#Tenant",
            "admin.TenantAdminServices.deactivate#Tenant",
            "admin.TenantAdminServices.get#TenantDetail",
            "admin.TenantAdminServices.get#TenantList",
            "admin.TenantAdminServices.reactivate#Tenant",
            "admin.TenantAdminServices.update#Tenant",

            // DAR-BE-264: the two-factor policy and its reset are the admin app's; sign-in and credentials are never a CLI session's.
            "admin.SecurityAdminServices.get#TwoFactorPolicies",
            // DAR-OPS-019: the field-key rotation is a super admin's, with their own two-factor code; never a CLI session's.
            "admin.SecurityAdminServices.rotate#EncryptedFields",
            "admin.SecurityAdminServices.update#TwoFactorPolicy",

            "admin.TransferAdminServices.begin#SecretHandoff",
            "admin.ShopifyAppAdminServices.list#ShopifyApps",
            "admin.ShopifyAppAdminServices.save#ShopifyApp",

            "admin.TransferAdminServices.export#ConfigBundle",
            "admin.TransferAdminServices.get#TransferCatalog",
            "admin.TransferAdminServices.import#ConfigBundle",

            // DAR-ADMIN-011: setting Darpan admins is the admin app's (left unlisted when it shipped; CliMethodAllowlistTests was red).
            "admin.UserAdminServices.add#DarpanAdmin",
            "admin.UserAdminServices.remove#DarpanAdmin",
            "admin.UserAdminServices.create#UserAccount",
            "admin.UserAdminServices.disable#UserAccount",
            "admin.UserAdminServices.enable#UserAccount",
            "admin.UserAdminServices.get#UserDetail",
            "admin.UserAdminServices.get#UserList",
            "admin.UserAdminServices.reset#Password",
            "admin.UserAdminServices.reset#TwoFactor", // DAR-BE-264
            "admin.UserAdminServices.update#UserAccount",

            "facade.AuthFacadeServices.change#ExpiredPassword",
            "facade.AuthFacadeServices.change#OwnPassword",
            "facade.AuthFacadeServices.complete#PasswordReset",
            "facade.AuthFacadeServices.get#PasswordResetStatus", // password recovery: never on the CLI list
            "facade.AuthFacadeServices.login#Session",
            "facade.AuthFacadeServices.logout#AllSessions",
            "facade.AuthFacadeServices.logout#Session",
            "facade.AuthFacadeServices.request#PasswordReset",
            "facade.AuthFacadeServices.save#ActiveTenant",
            "facade.AuthFacadeServices.save#RecoveryEmail",
            "facade.AuthFacadeServices.verify#OwnPassword",
            "facade.AuthFacadeServices.verify#PasswordResetCode",
            // DAR-BE-264: two-factor sign-in, setup and recovery codes: credentials, never on the CLI list (get#TwoFactorStatus too).
            "facade.AuthFacadeServices.begin#TwoFactorSetup",
            "facade.AuthFacadeServices.confirm#TwoFactorSetup",
            "facade.AuthFacadeServices.create#RecoveryCodes",
            "facade.AuthFacadeServices.disable#TwoFactor",
            "facade.AuthFacadeServices.get#TwoFactorStatus",
            "facade.AuthFacadeServices.verify#SignInCode",
            // DAR-BE-270: passkey sign-in, proof, registration and management: credentials, never on the CLI list.
            "facade.AuthFacadeServices.begin#PasskeyProof",
            "facade.AuthFacadeServices.begin#PasskeyRegistration",
            "facade.AuthFacadeServices.begin#PasskeySignIn",
            "facade.AuthFacadeServices.delete#Passkey",
            "facade.AuthFacadeServices.finish#PasskeyRegistration",
            "facade.AuthFacadeServices.rename#Passkey",
            // DAR-BE-301: browser sign-in with a passkey alone; a CLI session signs in through start/poll#CliLogin.
            "facade.AuthFacadeServices.begin#PasskeyFirstSignIn",
            "facade.AuthFacadeServices.finish#PasskeyFirstSignIn",

            "facade.CliAuthFacadeServices.approve#CliLogin",
            "facade.CliAuthFacadeServices.deny#CliLogin",
            "facade.CliAuthFacadeServices.get#CliLoginRequest", // browser-only: the approval page reads it
            // Final review Important 2: a CLI session must never mint another CLI key (an agent could print it into its
            // transcript). The CLI's own sign-in calls these anonymously, before it has a key, so the fence never needs them.
            "facade.CliAuthFacadeServices.poll#CliLogin",
            "facade.CliAuthFacadeServices.start#CliLogin",
            "facade.CliAuthFacadeServices.list#CliSessions", // browser-only: listing and revoking CLI sessions is the app's
            "facade.CliAuthFacadeServices.revoke#CliSession",

            // DAR-CLI-008: sharing widens another tenant's access to a config's credentials; credentials are managed in the app.
            "facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess",
            "facade.ConfigSharingFacadeServices.revoke#ConfigTenantAccess",

            // DAR-CLI-008 (Aditi 2026-11-25): connection credentials — save, delete and the getters that return them — are
            // managed in the app: a secret given to a user's AI goes into its provider's transcript.
            "facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig",
            "facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig",

            "facade.ReconciliationFacadeServices.list#AutomationSourceOptions", // sftpServers carry each SFTP login username

            "facade.SettingsFacadeServices.delete#NsAuthConfig",
            "facade.SettingsFacadeServices.delete#TenantChatSpace",
            "facade.SettingsFacadeServices.list#NsAuthConfigs", // returns the NetSuite login username, clientId and certId
            "facade.SettingsFacadeServices.list#NsRestletConfigs", // returns raw headersJson, which may carry an auth header
            "facade.SettingsFacadeServices.list#SftpServers", // returns the SFTP login username
            "facade.SettingsFacadeServices.list#TenantChatSpaces", // returns each webhook URL in clear text
            "facade.SettingsFacadeServices.save#LlmSettings",
            "facade.SettingsFacadeServices.save#NsAuthConfig",
            "facade.SettingsFacadeServices.save#NsRestletConfig",
            "facade.SettingsFacadeServices.save#SftpServer",
            "facade.SettingsFacadeServices.save#TenantChatSpace",

            // DAR-BE-275: starts an install that stores a new connection; a browser flow, not a CLI one.
            "facade.ShopifyFacadeServices.begin#ShopifyInstall",
            "facade.ShopifyFacadeServices.delete#ShopifyAuthConfig",
            "facade.ShopifyFacadeServices.save#ShopifyAuthConfig",

            "facade.SlackFacadeServices.begin#SlackInstall",
            "facade.SlackFacadeServices.disconnect#SlackWorkspace",
            "facade.SlackFacadeServices.save#SlackBotToken",

            "facade.TenantConfigFacadeServices.get#TenantConfigVersion", // its pack carries connection usernames, clientIds and certIds
    ] as LinkedHashSet<String>).asImmutable()
}
