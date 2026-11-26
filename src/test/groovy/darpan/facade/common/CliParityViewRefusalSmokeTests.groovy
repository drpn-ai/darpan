package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-CLI-008 (spec 4.4): a CLI key reaches every tenant-app write the user's role allows, so the role check lives in
 * each service, not in the fence. This pins it for a spread of the writes the CLI opened, across every service file
 * that moved: a view-only member is refused by the service itself, with its "view access" / "read-only" wording,
 * whatever the parameters. A service that loses its check fails here by name.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliParityViewRefusalSmokeTests {

    private static final String TENANT = "KREWE"   // ReconciliationSmokeTestSupport's TEST_COMPANY_USER_GROUP_ID
    private static final Timestamp FROM = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final String F = "facade."

    /** method -> the smallest parameters that get past required-parameter validation into the service body. */
    private static final Map<String, Map> WRITES = [
            "ReconciliationFacadeServices.save#Automation"          : [automationName: "n", inputModeEnumId: "AUT_IN_API_RANGE", scheduleExpr: "0 0 1 * * ?"],
            "ReconciliationFacadeServices.delete#SavedRun"          : [savedRunId: "RS_NONE"],
            "ReconciliationFacadeServices.pause#Automation"         : [automationId: "AUT_NONE"],
            "ReconciliationFacadeServices.delete#Automation"        : [automationId: "AUT_NONE"],
            // save#RuleSetRun resolves the run (it needs a compare scope) before its write check; nothing is written before it. Not listed.
            "ReconciliationFacadeServices.import#NetSuiteChecksAsRuns": [:],
            // delete#GeneratedOutput finds the file before its write check (XML), so a missing file answers "not found"; not listed.
            "ReconciliationFacadeServices.save#Lifecycle"           : [nsAuthConfigId: "NS_NONE", recordType: "salesorder", stageRecordTypes: ["salesorder"]],
            "AuditFacadeServices.save#AuditSettings"                : [audit: "AUD_NONE"],
            "AuditFacadeServices.add#AuditStatement"                : [audit: "AUD_NONE", text: "t", category: "ORDERS"],
            "AuditFacadeServices.create#Audit"                      : [name: "n", period: "DAILY"],
            "AuditFacadeServices.save#AuditCheck"                   : [audit: "AUD_NONE", check: [id: "x-check", label: "x"]],
            "PeakPeriodFacadeServices.save#PeakPeriod"              : [:],
            "SyncPulseFacadeServices.save#SyncPair"                 : [:],
            "TenantConfigFacadeServices.restore#TenantConfigVersion": [id: "CV_NONE", planHash: "h"],
            "TenantConfigFacadeServices.snapshot#TenantConfig"      : [:],
            "AgentChangeFacadeServices.apply#AuditChange"           : [proposalId: "P_NONE", planHash: "h"],
            "AgentChangeFacadeServices.plan#AuditChange"            : [proposalId: "P_NONE"],
            "SettingsFacadeServices.save#TenantSettings"            : [:],
            "SettingsFacadeServices.set#NotificationRoute"          : [sourceType: "AUTOMATION", sourceId: "AUT_NONE"],
            "JsonSchemaFacadeServices.save#JsonSchemaText"          : [schemaText: "{}", schemaName: "s"],
            "JsonSchemaFacadeServices.delete#JsonSchema"            : [jsonSchemaId: "JS_VIEWTEST"],
    ]

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "cli-parity-view-refusal")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, description: "View only",
                         groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        // delete#JsonSchema finds the tenant's record before its write check: give it one.
        ec.entity.makeValue("darpan.reconciliation.JsonSchema").setAll([jsonSchemaId: "JS_VIEWTEST", companyUserGroupId: TENANT,
                schemaName: "view-test", schemaText: "{}"]).createOrUpdate()
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        ec.message.clearAll()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void aViewOnlyMemberIsRefusedByEachOpenedWrite() {
        List<String> notRefused = []
        WRITES.each { String method, Map parameters ->
            ec.message.clearAll()
            Map result = (Map) ec.service.sync().name(F + method).parameters(parameters).disableAuthz().call()
            List<String> errors = ((result?.errors ?: []) as List<String>) + (ec.message.errors as List<String>) +
                    ec.message.validationErrors.collect { it.toString() }
            ec.message.clearAll()
            if (!errors.any { String e -> e =~ /view access|read-only/ }) notRefused << "${method} -> ${errors}".toString()
        }
        assertTrue(notRefused.isEmpty(), "a view-only member must be refused by the service itself:\n${notRefused.join('\n')}")
    }

    /** The signed-in user's permission groups on KREWE, exactly these. */
    private void setPermissions(List<String> permissionGroupIds) {
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", TENANT)
                .condition("userId", ec.user.userId).disableAuthz().deleteAll()
        permissionGroupIds.each { String groupId ->
            ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                    .setAll([tenantUserGroupId: TENANT, userId: ec.user.userId, permissionUserGroupId: groupId, fromDate: FROM]).create()
        }
    }
}
