# Wave 1 Facade Services (Settings + JSON Schema)

This document defines Wave 1 backend facade APIs used by `darpan-ui`.

## Auth and Remote Access

- All Wave 1 facade services are in `service/facade/*.xml`.
- All services are `allow-remote="true"`.
- `facade.AuthFacadeServices.login#Session` uses `authenticate="anonymous-all"`.
- `facade.AuthFacadeServices.get#SessionInfo` uses `authenticate="anonymous-all"` so the backend can restore a missing session from the pilot login-key cookie.
- `facade.AuthFacadeServices.logout#Session` uses `authenticate="anonymous-all"` so logout can still clear the persistent cookie after session loss.
- Remaining services use `authenticate="true"`.
- Frontend calls are expected through authenticated remote service invocation with session credentials.

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
- The cookie is scoped to `/`, uses `SameSite=Lax`, and is marked `Secure` when the request arrives over HTTPS or a forwarded HTTPS proxy header.
- Cookie lifetime is aligned to `user-facade/login-key@expire-hours` from Moqui config. The default remains 144 hours.
- `get#SessionInfo` restores the authenticated session from that cookie when the normal web session is missing.
- `logout#Session` clears the persistent cookie, revokes the matching `moqui.security.UserLoginKey`, and terminates the authenticated session.

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

### `facade.JsonSchemaFacadeServices`
- `list#JsonSchemas`
- `get#JsonSchema`
- `save#JsonSchemaText`
- `infer#JsonSchemaFromText`
- `validate#JsonTextAgainstSchema`
- `flatten#JsonSchema`
- `save#RefinedSchema`
- `delete#JsonSchema`

## Response Envelope

Wave 1 facade services return these top-level keys:

- `ok` (`Boolean`)
- `messages` (`List<String>`)
- `errors` (`List<String>`)

Payload keys vary by service (`llmSettings`, `servers`, `authConfigs`, `schemas`, etc).

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
      "timeZone": "Asia/Kolkata"
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

## Secret Redaction Policy

Settings list/save payloads never expose raw stored secrets. They return flags such as:

- `hasPassword`
- `hasApiToken`
- `hasPrivateKey`
- `hasPrivateKeyPem`
- `hasStoredLlmApiKey`

## UI Migration Notes

- These facade services replace screen-transition dependency for Wave 1 UI pages.
- Existing backend screens remain active during phased parallel cutover.
- Backend UI surfaces (`screen/**`, `template/**`, `theme-library/**`) are not extended by this change.
