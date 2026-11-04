package darpan.facade.common

/**
 * DAR-CLI-003 — what a CLI session may call. A CLI session is a request signed in with a login key issued through
 * the CLI sign-in (CliAuthSupport.isCliSession). Aditi's scope: run execution and data getters, plus tenant scripts;
 * nothing that saves, changes or deletes anything else. RpcMethodFence.enforce holds a CLI session to this list on
 * the server, so the key used directly (curl) gets no more than the CLI does.
 *
 * <p>ALLOWED: every facade get#/list#/search# that writes nothing and returns no credential, the run starters,
 * preview#NsSuiteQlSourceQuery and test#SourceConnection, every Scripts page service (deployments too, send-now
 * included: Aditi 2026-11-04), and the CLI's own logout (its start/poll sign-in runs anonymously). EXCLUDED: everything else in
 * docs/api-contract/methods.txt, with a note on each getter left out. CliMethodAllowlistTests fails when a published
 * method is in neither list, so a new service is placed here in the change that adds it.
 *
 * <p>A getter returning any credential value or login identity (password, token, key, webhook URL, username,
 * OAuth client id or cert id, raw headers) stays EXCLUDED even though it writes nothing. Spec: wrapper
 * docs/superpowers/specs/2026-11-03-tenant-agent-cli-mcp-design.md 6.4.
 */
class CliMethodAllowlist {

    /** The only paths a CLI session may reach: JSON-RPC and the authenticated GraphQL endpoint, each exactly. */
    static final List<String> CLI_PATHS = ["/rpc/json", "/rest/s1/graphql"].asImmutable()

    static final Set<String> ALLOWED = ([
            // The service itself requires tenant-admin; an admin's agent may read the fresh-query log.
            "facade.AgentFacadeServices.list#AgentQueryLogs",

            "facade.AuditFacadeServices.get#AuditCheckExtracts",
            "facade.AuditFacadeServices.get#AuditCheckOptions",
            "facade.AuditFacadeServices.get#AuditFinding",
            "facade.AuditFacadeServices.get#AuditFindingsByOrder",
            "facade.AuditFacadeServices.get#AuditProgress",
            "facade.AuditFacadeServices.get#AuditReport",
            "facade.AuditFacadeServices.get#AuditSettings",
            "facade.AuditFacadeServices.get#Audits",
            "facade.AuditFacadeServices.retry#AuditCheck",
            "facade.AuditFacadeServices.run#Audit",

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

            "facade.RunConclusionFacadeServices.get#RunConclusions",

            "facade.SearchFacadeServices.search#NavigationTargets",

            "facade.SettingsFacadeServices.get#LlmSettings",
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

            "facade.TenantScriptFacadeServices.delete#TenantScript",
            "facade.TenantScriptFacadeServices.delete#TenantScriptDeployment",
            "facade.TenantScriptFacadeServices.list#TenantScriptDeployments",
            "facade.TenantScriptFacadeServices.list#TenantScripts",
            "facade.TenantScriptFacadeServices.run#TenantScript",
            // send=true posts to the deployment's chat space now (Aditi 2026-11-04: any script deployment via the CLI)
            "facade.TenantScriptFacadeServices.run#TenantScriptDeployment",
            "facade.TenantScriptFacadeServices.save#TenantScript",
            "facade.TenantScriptFacadeServices.save#TenantScriptDeployment",
    ] as LinkedHashSet<String>).asImmutable()

    static final Set<String> EXCLUDED = ([
            "admin.KnowledgeAdminServices.get#KnowledgeEntry",
            "admin.KnowledgeAdminServices.get#KnowledgeForReview",
            "admin.KnowledgeAdminServices.get#KnowledgeIdentifiers",
            "admin.KnowledgeAdminServices.retire#KnowledgeEntry",
            "admin.KnowledgeAdminServices.search#Knowledge",
            "admin.KnowledgeAdminServices.store#KnowledgeEntry",

            "admin.MembershipAdminServices.add#TenantMember",
            "admin.MembershipAdminServices.remove#TenantMember",
            "admin.MembershipAdminServices.update#TenantMemberRole",

            "admin.MigrationAdminServices.dryRun#Migration",
            "admin.MigrationAdminServices.dryRun#PendingMigrations",
            "admin.MigrationAdminServices.get#MigrationHistory",
            "admin.MigrationAdminServices.get#MigrationStatus",
            "admin.MigrationAdminServices.run#Migration",
            "admin.MigrationAdminServices.run#PendingMigrations",
            "admin.MigrationAdminServices.set#MigrationEnabled",

            "admin.ObserveAdminServices.get#AdminSessionInfo",
            "admin.ObserveAdminServices.get#LiveOperations",

            "admin.ScheduleAdminServices.get#ScheduleBoard",

            "admin.TenantAdminServices.create#Tenant",
            "admin.TenantAdminServices.deactivate#Tenant",
            "admin.TenantAdminServices.get#TenantDetail",
            "admin.TenantAdminServices.get#TenantList",
            "admin.TenantAdminServices.reactivate#Tenant",
            "admin.TenantAdminServices.update#Tenant",

            "admin.TransferAdminServices.begin#SecretHandoff",
            "admin.TransferAdminServices.export#ConfigBundle",
            "admin.TransferAdminServices.get#TransferCatalog",
            "admin.TransferAdminServices.import#ConfigBundle",

            "admin.UserAdminServices.create#UserAccount",
            "admin.UserAdminServices.disable#UserAccount",
            "admin.UserAdminServices.enable#UserAccount",
            "admin.UserAdminServices.get#UserDetail",
            "admin.UserAdminServices.get#UserList",
            "admin.UserAdminServices.reset#Password",
            "admin.UserAdminServices.update#UserAccount",

            "facade.AuditFacadeServices.add#AuditStatement",
            "facade.AuditFacadeServices.create#Audit",
            "facade.AuditFacadeServices.save#AuditCheck",
            "facade.AuditFacadeServices.save#AuditSettings",
            "facade.AuditFacadeServices.verify#AuditFinding",

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
            "facade.AuthFacadeServices.save#UserSettings",
            "facade.AuthFacadeServices.verify#OwnPassword",
            "facade.AuthFacadeServices.verify#PasswordResetCode",

            "facade.CliAuthFacadeServices.approve#CliLogin",
            "facade.CliAuthFacadeServices.deny#CliLogin",
            "facade.CliAuthFacadeServices.get#CliLoginRequest", // browser-only: the approval page reads it
            // Final review Important 2: a CLI session must never mint another CLI key (an agent could print it into its
            // transcript). The CLI's own sign-in calls these anonymously, before it has a key, so the fence never needs them.
            "facade.CliAuthFacadeServices.poll#CliLogin",
            "facade.CliAuthFacadeServices.start#CliLogin",
            "facade.CliAuthFacadeServices.list#CliSessions", // browser-only: listing and revoking CLI sessions is the app's
            "facade.CliAuthFacadeServices.revoke#CliSession",

            "facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess",
            "facade.ConfigSharingFacadeServices.revoke#ConfigTenantAccess",

            "facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig",
            "facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig",

            "facade.JsonSchemaFacadeServices.delete#JsonSchema",
            "facade.JsonSchemaFacadeServices.flatten#JsonSchema",
            "facade.JsonSchemaFacadeServices.infer#JsonSchemaFromCsvText",
            "facade.JsonSchemaFacadeServices.infer#JsonSchemaFromText",
            "facade.JsonSchemaFacadeServices.save#JsonSchemaText",
            "facade.JsonSchemaFacadeServices.save#RefinedSchema",
            "facade.JsonSchemaFacadeServices.validate#JsonTextAgainstSchema",

            "facade.NetSuiteQueryFacadeServices.save#NsSuiteQlSourceQuery",

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
            "facade.ReconciliationFacadeServices.list#AutomationSourceOptions", // sftpServers carry each SFTP login username
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
            "facade.ReconciliationFacadeServices.sync#Automation",
            "facade.ReconciliationFacadeServices.unsubscribe#RunNotification",

            "facade.RunConclusionFacadeServices.create#RunConclusionCode",
            "facade.RunConclusionFacadeServices.save#RunConclusions",

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
            "facade.SettingsFacadeServices.save#TenantSettings",
            "facade.SettingsFacadeServices.save#UserNotificationDefault",

            "facade.ShopifyFacadeServices.delete#ShopifyAuthConfig",
            "facade.ShopifyFacadeServices.save#ShopifyAuthConfig",

            "facade.SlackFacadeServices.begin#SlackInstall",
            "facade.SlackFacadeServices.disconnect#SlackWorkspace",
            "facade.SlackFacadeServices.save#SlackBotToken",

            "facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess",

            "facade.TenantConfigFacadeServices.get#TenantConfigVersion", // its pack carries connection usernames, clientIds and certIds
            "facade.TenantConfigFacadeServices.preview#TenantConfigRestore",
            "facade.TenantConfigFacadeServices.restore#TenantConfigVersion",
            "facade.TenantConfigFacadeServices.snapshot#TenantConfig",
    ] as LinkedHashSet<String>).asImmutable()
}
