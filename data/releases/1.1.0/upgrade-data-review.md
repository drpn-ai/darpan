# Upgrade Data Review For Darpan 1.1.0

## Scope

- Backend compare range: `v1.0.0..main`
- Data directory reviewed: `data`
- This review keeps only the mandatory operator-facing records in the final release-scoped upgrade XML.
- Optional demo/UAT sample bundles remain in their source seed files and are intentionally excluded from the mandatory release-scoped upgrade load.

## Candidate records

### Final release-scoped load records from `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroup|artifactGroupId=DARPAN_AUTH_API`
- Element: `moqui.security.ArtifactGroup`

```xml
<moqui.security.ArtifactGroup artifactGroupId="DARPAN_AUTH_API" description="Darpan auth facade APIs"/>
```

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_AUTH_API|artifactTypeEnumId=AT_SERVICE|artifactName=facade\.AuthFacadeServices\..*`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_AUTH_API" artifactName="facade\.AuthFacadeServices\..*" artifactTypeEnumId="AT_SERVICE" nameIsPattern="Y" inheritAuthz="Y"/>
```

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_AUTH_API_ALL_USERS|userGroupId=ALL_USERS|artifactGroupId=DARPAN_AUTH_API|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_AUTH_API_ALL_USERS" userGroupId="ALL_USERS" artifactGroupId="DARPAN_AUTH_API" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_APP_ADMIN|userGroupId=ADMIN|artifactGroupId=DARPAN_APP|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_APP_ADMIN" userGroupId="ADMIN" artifactGroupId="DARPAN_APP" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

- Record: `moqui.basic.Enumeration|enumId=darpan.dashboard.pinnedReconciliationMappingIds|enumTypeId=UserPreferenceKey`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="darpan.dashboard.pinnedReconciliationMappingIds" description="Pinned dashboard reconciliation mappings" enumTypeId="UserPreferenceKey"/>
```

### Excluded from the mandatory release-scoped upgrade load

- `data/RunSystemInstanceSeedData.xml`, `data/RunSystemInstanceFourSystemsSeedData.xml`, and `data/RunSystemInstanceSystemsOnlySeedData.xml` are optional demo/UAT sample bundles for pilot finder surfaces and should remain opt-in.
- The duplicated `SystemMessageRemote` examples inside those sample bundles are intentionally excluded from the mandatory release-scoped load to avoid forcing sample endpoints into production environments.
- Root `entity-facade-xml type` changes on existing seed files (`seed` -> `darpan-seed` / `darpan-seed-initial`) are setup-flow metadata and should be handled by `./gradlew loadDarpanSetup`, not by the release-scoped upgrade XML.

## Recommended operator review

- Existing upgraded environments should load the curated `upgrade-data.xml` only if the auth facade exposure, admin authorization narrowing, or pinned-dashboard preference key is missing.
- Fresh or fully rebuilt environments should use `./gradlew loadDarpanSetup` so the new Darpan-specific seed readers execute in the intended order.
- Treat the `RunSystemInstance*` files as optional sample bundles and review them separately before loading them into any shared environment.
