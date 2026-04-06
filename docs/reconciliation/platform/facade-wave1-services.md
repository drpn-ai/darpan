# Wave 1 Facade Services (Auth, Settings, JSON Schema, Pilot Reconciliation)

This document defines backend facade APIs used by `darpan-ui` during the pilot rollout.

## Auth and Remote Access

- All Wave 1 facade services are in `service/facade/*.xml`.
- All services are `allow-remote="true"`.
- `facade.AuthFacadeServices.login#Session` uses `authenticate="anonymous-all"`.
- `facade.AuthFacadeServices.get#SessionInfo` uses `authenticate="anonymous-all"` so the backend can restore a missing session from the pilot login-key cookie.
- `facade.AuthFacadeServices.logout#Session` uses `authenticate="anonymous-all"` so logout can still clear the persistent cookie after session loss.
- Remaining services use `authenticate="true"`.
- Frontend calls are expected through authenticated remote service invocation with session credentials.

## Pilot Shared-Tenant Access Scope

- `facade.AuthFacadeServices.login#Session` and `facade.AuthFacadeServices.get#SessionInfo` now return scope metadata inside `sessionInfo`.
- `scopeType` is `GLOBAL` for super-admin sessions and `CUSTOMER` for normal pilot customer sessions.
- `customerScopeId` is the current `userId` for customer sessions in this release. This keeps one customer scope per pilot login without requiring separate tenants.
- `isSuperAdmin` is `true` only when the current user belongs to the Moqui `ADMIN` group.
- Current shared-tenant enforcement in this repo applies to:
  - Wave 1 settings facades: super-admin only
  - Wave 1 JSON schema facades: customer users only see schemas they created through the facade
  - Generic reconciliation outputs under `runtime://tmp/reconciliation/generic/**`: non-admin sessions use per-user temp/output folders
  - Legacy backend reconciliation and settings screens: authenticated super-admin only for the pilot release

## Artifact Authorization Requirement

Wave 1 facade methods require service artifact authorization in addition to session authentication.

`runtime/component/darpan/data/SecuritySeedData.xml` must include an `AT_SERVICE` artifact group member that matches facade services:

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP"
        artifactName="facade\..*" artifactTypeEnumId="AT_SERVICE" nameIsPattern="Y" inheritAuthz="Y"/>
```

Without this, authenticated users can still get errors like:
`User <id> is not authorized for All on Service facade.SettingsFacadeServices.list#SftpServers`.

## Service Groups

### `facade.AuthFacadeServices`
- `login#Session`
- `get#SessionInfo`
- `logout#Session`

## Pilot Persistent Login Behavior

- Successful `login#Session` calls issue a Moqui login key and return it only through an HTTP-only cookie named `darpan_pilot_login_key`.
- The cookie is scoped to `/`, uses `SameSite=Lax` for same-origin browser flows, upgrades to `SameSite=None` for secure cross-site hosted clients, and is marked `Secure` when the request arrives over HTTPS or a forwarded HTTPS proxy header.
- Cookie lifetime is aligned to `user-facade/login-key@expire-hours` from Moqui config. The default remains 144 hours.
- `login#Session` returns `authState`, `authSource`, and `persistentLoginIssued` as the explicit auth contract.
- `get#SessionInfo` restores the authenticated session from that cookie when the normal web session is missing, and now returns `authState`, `authSource`, and `sessionRestored`.
- `logout#Session` clears the persistent cookie, revokes the matching `moqui.security.UserLoginKey`, terminates the authenticated session, and returns `persistentLoginRevoked`.
- Explicit pilot logout in `darpan-ui` must call `logout#Session`. The legacy `/Login/logout` path is not the pilot logout contract because it does not clear the pilot login-key cookie by itself.
- Production hosted deployments must set `webapp_allow_origins` to the concrete frontend origin list instead of relying on a wildcard when credentialed cross-origin requests are expected.

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

- All settings facade methods now reject non-admin users with an authorization error because these records are global integration/system configuration, not customer-scoped pilot data.

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

- JSON schemas created through the facade are stamped with `ownerUserId`.
- Super-admin users can access all schema rows.
- Customer users can only list, load, update, validate against, flatten, and delete schemas whose `ownerUserId` matches the authenticated user.
- Legacy rows without `ownerUserId` are treated as super-admin only.

### `facade.ReconciliationFacadeServices`
- `list#PilotMappings`
- `run#PilotGenericDiff`
- `list#PilotGeneratedOutputs`
- `get#PilotGeneratedOutput`
- `delete#PilotGeneratedOutput`

Pilot release contract notes:

- The active pilot remains mapping-based for this release. The facade contract uses `reconciliationMappingId` rather than `ruleSetId`.
- `run#PilotGenericDiff` is JSON-RPC friendly for `darpan-ui`; it accepts `file1Name`/`file1Text` and `file2Name`/`file2Text` instead of raw multipart `FileItem` uploads.
- The underlying reconciliation engine still writes a scoped JSON diff file under `runtime://tmp/reconciliation/generic/**`.
- `get#PilotGeneratedOutput` can return that stored JSON directly or convert it to CSV on demand for the pilot UI download action.

Shared-tenant rule:

- Mapping configuration is readable by authenticated pilot users so they can choose an allowed reconciliation pair.
- Generated output storage remains customer-scoped for non-admin users through `PilotAccessSupport.resolveGenericOutputLocation(ec)`.
- Super-admin users can list and retrieve outputs across the shared tenant because they resolve to the unscoped generic output directory.

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

Persistent login bootstrap example:

```json
{
  "jsonrpc": "2.0",
  "id": 17100002,
  "method": "facade.AuthFacadeServices.get#SessionInfo",
  "params": {}
}
```

Expected success shape after session restoration:

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
      "scopeType": "CUSTOMER",
      "customerScopeId": "EXAMPLE_USER",
      "isSuperAdmin": false
    }
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

Pilot diff run example:

```json
{
  "jsonrpc": "2.0",
  "id": 17100003,
  "method": "facade.ReconciliationFacadeServices.run#PilotGenericDiff",
  "params": {
    "reconciliationMappingId": "OrderIdMap",
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
      "reconciliationMappingId": "OrderIdMap",
      "mappingName": "Order ID",
      "file1Name": "oms-orders.csv",
      "file2Name": "shopify-orders.csv",
      "file1SystemEnumId": "DarSysOms",
      "file1SystemLabel": "OMS",
      "file2SystemEnumId": "DarSysShopify",
      "file2SystemLabel": "SHOPIFY",
      "validationErrors": [],
      "processingWarnings": [],
      "generatedOutput": {
        "fileName": "Order-ID-diff-20260330-123000.json",
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
