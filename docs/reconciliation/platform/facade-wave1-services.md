# Wave 1 Facade Services (Auth, Settings, JSON Schema, Pilot Reconciliation)

This document defines backend facade APIs used by `darpan-ui` during the pilot rollout.

## Auth and Remote Access

- All Wave 1 facade services are in `service/facade/*.xml`.
- All services are `allow-remote="true"`.
- `facade.AuthFacadeServices.login#Session` uses `authenticate="anonymous-all"`.
- `facade.AuthFacadeServices.get#SessionInfo` uses `authenticate="anonymous-all"` so darpan-ui can verify the explicit auth token without depending on a browser session cookie.
- `facade.AuthFacadeServices.logout#Session` uses `authenticate="anonymous-all"` so darpan-ui can revoke the current token even after local auth state drift.
- Remaining services use `authenticate="true"`.
- Frontend calls are expected through authenticated remote service invocation using the `login_key` request header after login.
- These facade auth methods are thin adapters over Moqui's built-in login-key issuance and request authentication rather than a separate Darpan auth state machine.

## Pilot Shared-Tenant Access Scope

- `facade.AuthFacadeServices.login#Session` and `facade.AuthFacadeServices.get#SessionInfo` now return scope metadata inside `sessionInfo`.
- `scopeType` is `COMPANY` for authenticated sessions, including super-admin sessions.
- `activeCompanyUserGroupId` identifies the company group the user is currently working in.
- `activeCompanyLabel` is the display label for that active company.
- `availableCompanies[]` lists the user's switchable Darpan company groups.
- `customerScopeId` remains in the payload temporarily as a compatibility alias for `activeCompanyUserGroupId` while downstream company-scoped surfaces are migrated off the older field name.
- `isSuperAdmin` is `true` only when the current user belongs to the Moqui `ADMIN` group.
- The active company preference is stored in `UserPreference` under `darpan.auth.activeCompanyUserGroupId`.
- Only `UserGroup` rows tagged with the Darpan-specific `UgtDarpanCompany` group type are exposed as switchable companies.
- Current shared-tenant enforcement in this repo applies to:
  - company-owned facade entities are filtered through Moqui `ArtifactAuthzFilter` / `EntityFilterSet` records bound to the PWA service authz path
  - the active company is pushed into `ec.user.context.activeCompanyUserGroupId` during request/login setup so those filters apply without per-facade query conditions
  - Wave 1 settings facades remain split: company-owned records (`SFTP`, `NetSuite auth`, `NetSuite endpoint`) are company-scoped, while global settings (`LLM`, `HcReadDbConfig`, enum/global admin settings) stay super-admin only
  - Generic reconciliation outputs under `runtime://tmp/reconciliation/generic/**` are company-scoped when an active company exists
  - Legacy backend reconciliation and settings screens: authenticated super-admin only for the pilot release
- This auth contract is the foundation for later issues that move the remaining runs/results surfaces onto the same company ownership model.

## Artifact Authorization Requirement

Wave 1 facade methods require service artifact authorization in addition to session authentication.

`runtime/component/darpan/data/SecuritySeedData.xml` should keep the legacy backend screen group admin-only and add a dedicated facade artifact group for the PWA service path:

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_FACADE_APP"
        artifactName="facade\..*" artifactTypeEnumId="AT_SERVICE" nameIsPattern="Y" inheritAuthz="Y"/>
```

The recommended coarse access split is:

- `DARPAN_FACADE_APP_USER` on `DARPAN_FACADE_APP` for `DARPAN_USER`
- `DARPAN_FACADE_APP_ADMIN` on `DARPAN_FACADE_APP` for `ADMIN`
- `DARPAN_APP_ADMIN` on `DARPAN_APP` for the legacy backend screens
- both facade authz rows should attach the same `ArtifactAuthzFilter` so company-owned facade reads stay scoped to `ec.user.context.activeCompanyUserGroupId` for admins and non-admin users alike

Without the dedicated facade authz, authenticated users can still get errors like:
`User <id> is not authorized for All on Service facade.SettingsFacadeServices.list#SftpServers`.

## Service Groups

### `facade.AuthFacadeServices`
- `login#Session`
- `get#SessionInfo`
- `save#ActiveCompany`
- `logout#Session`

## Pilot Stateless Auth Behavior

- Successful `login#Session` calls issue a Moqui login key and return it explicitly in the JSON-RPC result as `authToken`.
- The frontend must send that token on later requests through the `login_key` request header.
- Token lifetime is aligned to `user-facade/login-key@expire-hours` from Moqui config. The default remains 144 hours, exposed as `authTokenExpiresInSeconds`.
- `get#SessionInfo` authenticates from the `login_key` header when present and does not depend on `/Login`, `moquiSessionToken`, or browser session-cookie bootstrap.
- `save#ActiveCompany` requires an authenticated request, validates that the requested company belongs to the current user, persists that selection in `UserPreference`, and returns refreshed `sessionInfo`.
- `logout#Session` revokes the matching `moqui.security.UserLoginKey` and terminates the authenticated session.
- The UI contract is intentionally small: `authenticated`, `sessionInfo`, and the explicit token fields returned from `login#Session`.

### `facade.SettingsFacadeServices`
- `list#EnumOptions`
- `get#LlmSettings`
- `save#LlmSettings`
- `list#SftpServers`
- `save#SftpServer`
- `list#NsAuthConfigs`
- `save#NsAuthConfig`
- `list#NsRestletConfigs`
- `save#NsRestletConfig`
- `list#HcReadDbConfigs`
- `save#HcReadDbConfig`

Shared-tenant rule:

- `list#SftpServers`, `save#SftpServer`, `list#NsAuthConfigs`, `save#NsAuthConfig`, `list#NsRestletConfigs`, and `save#NsRestletConfig` are company-scoped through `companyUserGroupId`.
- `list#EnumOptions`, `get#LlmSettings`, `save#LlmSettings`, `list#HcReadDbConfigs`, and `save#HcReadDbConfig` remain super-admin only.

### `facade.JsonSchemaFacadeServices`
- `list#JsonSchemas`
- `get#JsonSchema`
- `save#JsonSchemaText`
- `infer#JsonSchemaFromText`
- `validate#JsonTextAgainstSchema`
- `flatten#JsonSchema`
- `save#RefinedSchema`
- `delete#JsonSchema`

Shared-tenant rule:

- JSON schemas created through the facade are stamped with `companyUserGroupId` and `createdByUserId`.
- Schema payloads now expose `systemEnumId`/`systemLabel` directly from `DarpanSystemSource`.
- Authenticated users, including super-admin users, can only list, load, update, validate against, flatten, and delete schemas in their active company.
- Legacy rows without `companyUserGroupId` remain effectively admin-only until they are assigned.

### `facade.ReconciliationFacadeServices`
- `create#PilotMapping`
- `list#PilotMappings`
- `list#PilotRuleSetCompareScopes`
- `get#PilotMapping`
- `save#PilotMapping`
- `save#DashboardPinnedMappings`
- `run#PilotGenericDiff`
- `list#PilotGeneratedOutputs`
- `get#PilotGeneratedOutput`
- `delete#PilotGeneratedOutput`

Pilot release contract notes:

- `create#PilotMapping` accepts two saved schema IDs plus selected field paths and persists a JSON-backed pilot mapping using `ReconciliationMapping` and `ReconciliationMappingMember`.
- `create#PilotMapping` normalizes schema-flattener field paths into pilot-safe JSON ID expressions so newly-created mappings remain visible in `list#PilotMappings` and executable by the mapping-backed run flow.
- `get#PilotMapping` only returns saved mappings whose members still resolve to saved schemas, and includes the editable schema IDs/names needed by the PWA runs settings workflow.
- `save#PilotMapping` updates an existing two-source mapping from the PWA runs settings workflow and preserves the existing mapping/member record IDs while refreshing schema and field selections.
- The active Generic pilot workflow now defaults to RuleSet compare scopes. The facade keeps `reconciliationMappingId` only as a temporary bridge for older launch points while the rest of the cutover lands.
- `list#PilotMappings` now also returns `pinnedReconciliationMappingIds`, a user-scoped ordered list of saved dashboard pins backed by Moqui `UserPreference`.
- `list#PilotRuleSetCompareScopes` returns the RuleSet, compare-scope, object-type, file-side system labels, and primary-ID expressions the PWA needs to drive the RuleSet selector flow.
- `save#DashboardPinnedMappings` accepts `pinnedReconciliationMappingIds` and persists that ordered list for the authenticated user, so pinned runs survive browser/profile/origin changes after login.
- `run#PilotGenericDiff` is JSON-RPC friendly for `darpan-ui`; it accepts `file1Name`/`file1Text` and `file2Name`/`file2Text`, and now supports either `ruleSetId` plus optional `compareScopeId` or legacy `reconciliationMappingId`.
- `list#PilotGeneratedOutputs` accepts optional `reconciliationMappingId`, `ruleSetId`, and `compareScopeId`, and filters saved outputs against the stored metadata for that run scope.
- The underlying reconciliation engine still writes a scoped JSON diff file under `runtime://tmp/reconciliation/generic/**`.
- `get#PilotGeneratedOutput` can return that stored JSON directly or convert it to CSV on demand for the pilot UI download action, and now also returns a stable file-backed `createdDate` so the PWA result page does not depend on parsing raw `Timestamp.toString()` metadata.

Shared-tenant rule:

- Mapping configuration is readable by authenticated pilot users so they can choose an allowed reconciliation pair.
- `create#PilotMapping` lets authenticated pilot users create a new JSON-backed mapping from schemas they can access through the facade.
- All pilot mappings must keep saved schemas on every member; mappings that lose those saved-schema references are excluded from list/edit/run flows until fixed.
- `list#PilotMappings` tolerates legacy display-format JSON field paths already saved by the wizard so existing mappings are not hidden from the dashboard.
- Generated output storage resolves through the active company so list/get/delete only touch the current company-scoped output directory.
- Super-admin sessions still retain admin capabilities, but company-sensitive output access now follows the same active-company selection when company memberships exist.

## Response Envelope

Facade services return these top-level keys:

- `ok` (`Boolean`)
- `messages` (`List<String>`)
- `errors` (`List<String>`)

Payload keys vary by service (`llmSettings`, `servers`, `authConfigs`, `schemas`, `mappings`, `runResult`, `generatedOutputs`, etc).

## Concrete Example (JSON-RPC)

`darpan-ui` calls facade services through `/rpc/json`:

```json
{
  "jsonrpc": "2.0",
  "id": 17100001,
  "method": "facade.SettingsFacadeServices.list#SftpServers",
  "params": {
    "pageIndex": 0,
    "pageSize": 10,
    "query": "prod"
  }
}
```

Stateless session check example:

```json
{
  "jsonrpc": "2.0",
  "id": 17100002,
  "method": "facade.AuthFacadeServices.get#SessionInfo",
  "params": {}
}
```

Settings list success shape:

```json
{
  "jsonrpc": "2.0",
  "id": 17100002,
  "result": {
    "ok": true,
    "messages": [],
    "errors": [],
    "authenticated": true,
    "sessionInfo": {
      "userId": "EXAMPLE_USER",
      "username": "pilot.user",
      "locale": "en-US",
      "timeZone": "Asia/Kolkata",
      "scopeType": "COMPANY",
      "customerScopeId": "KREWE",
      "activeCompanyUserGroupId": "KREWE",
      "activeCompanyLabel": "Krewe",
      "availableCompanies": [
        {
          "userGroupId": "ACME",
          "label": "Acme"
        },
        {
          "userGroupId": "KREWE",
          "label": "Krewe"
        }
      ],
      "isSuperAdmin": false
    }
  }
}
```

Login response example with explicit auth token:

```json
{
  "jsonrpc": "2.0",
  "id": 17100004,
  "result": {
    "ok": true,
    "messages": [],
    "errors": [],
    "authenticated": true,
    "sessionInfo": {
      "userId": "EXAMPLE_USER",
      "username": "pilot.user",
      "scopeType": "COMPANY",
      "customerScopeId": "KREWE",
      "activeCompanyUserGroupId": "KREWE",
      "activeCompanyLabel": "Krewe"
    },
    "authToken": "plain-login-key-value",
    "authTokenType": "LOGIN_KEY",
    "authTokenHeaderName": "login_key",
    "authTokenExpiresInSeconds": 518400
  }
}
```

Expected success shape:

```json
{
  "jsonrpc": "2.0",
  "id": 17100001,
  "result": {
    "ok": true,
    "messages": [],
    "errors": [],
    "pagination": {
      "pageIndex": 0,
      "pageSize": 10,
      "totalCount": 1,
      "pageCount": 1
    },
    "servers": [
      {
        "sftpServerId": "prod_sftp",
        "description": "Production SFTP",
        "host": "sftp.example.com",
        "port": 22,
        "username": "integration",
        "remoteAttributes": "Y",
        "hasPassword": true,
        "hasPrivateKey": false
      }
    ]
  }
}
```

Pilot mappings list success shape:

```json
{
  "jsonrpc": "2.0",
  "id": 17100006,
  "result": {
    "ok": true,
    "messages": [],
    "errors": [],
    "pagination": {
      "pageIndex": 0,
      "pageSize": 12,
      "totalCount": 1,
      "pageCount": 1
    },
    "pinnedReconciliationMappingIds": [
      "OmsVsShopifyOrders-260421120000"
    ],
    "mappings": [
      {
        "reconciliationMappingId": "OmsVsShopifyOrders-260421120000",
        "mappingName": "OMS vs Shopify Orders",
        "requiresSystemSelection": false,
        "defaultFile1SystemEnumId": "OMS",
        "defaultFile2SystemEnumId": "SHOPIFY",
        "systemOptions": [
          {
            "enumId": "OMS",
            "label": "OMS"
          },
          {
            "enumId": "SHOPIFY",
            "label": "SHOPIFY"
          }
        ]
      }
    ]
  }
}
```

Dashboard pin persistence example:

```json
{
  "jsonrpc": "2.0",
  "id": 17100005,
  "method": "facade.ReconciliationFacadeServices.save#DashboardPinnedMappings",
  "params": {
    "pinnedReconciliationMappingIds": [
      "OmsVsShopifyOrders-260421120000",
      "InventoryDriftMap"
    ]
  }
}
```

Pilot diff run example:

```json
{
  "jsonrpc": "2.0",
  "id": 17100003,
  "method": "facade.ReconciliationFacadeServices.run#PilotGenericDiff",
  "params": {
    "reconciliationMappingId": "OmsVsShopifyOrders-260421120000",
    "file1Name": "oms-orders.csv",
    "file1Text": "order_id\n1001\n1002\n",
    "file2Name": "shopify-orders.csv",
    "file2Text": "order_id\n1002\n1003\n"
  }
}
```

Expected success shape:

```json
{
  "jsonrpc": "2.0",
  "id": 17100003,
  "result": {
    "ok": true,
    "messages": [],
    "errors": [],
    "runResult": {
      "reconciliationMappingId": "OmsVsShopifyOrders-260421120000",
      "mappingName": "OMS vs Shopify Orders",
      "file1Name": "oms-orders.csv",
      "file2Name": "shopify-orders.csv",
      "file1SystemEnumId": "OMS",
      "file1SystemLabel": "OMS",
      "file2SystemEnumId": "SHOPIFY",
      "file2SystemLabel": "SHOPIFY",
      "validationErrors": [],
      "processingWarnings": [],
      "generatedOutput": {
        "fileName": "OMS-vs-Shopify-Orders-diff-20260330-123000.json",
        "sourceFormat": "json",
        "availableFormats": ["json", "csv"],
        "preferredDownloadFormat": "csv",
        "reconciliationType": "CSV",
        "totalDifferences": 2,
        "onlyInFile1Count": 1,
        "onlyInFile2Count": 1
      }
    }
  }
}
```

## Secret Redaction Policy

Settings list/save payloads never expose raw stored secrets. They return flags such as:

- `hasPassword`
- `hasApiToken`
- `hasPrivateKey`
- `hasPrivateKeyPem`
- `hasStoredLlmApiKey`

## UI Migration Notes

- These facade services replace screen-transition dependency for Wave 1 UI pages.
- Existing backend screens remain active during phased parallel cutover, but the legacy reconciliation/settings screen tree is now restricted to authenticated super-admin users so it cannot bypass pilot customer scoping.
- Backend UI surfaces (`screen/**`, `template/**`, `theme-library/**`) are not extended by this change.
