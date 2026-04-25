# Navigation Search Facade

`facade.SearchFacadeServices.search#NavigationTargets` is the backend search contract for Ask Darpan navigation.

The service searches approved Darpan record domains and returns normalized navigation targets. It is not a raw entity/table search API. Every result is already scoped to the active tenant and shaped so the UI can navigate without knowing the source entity.

## Request

- `query`: Free-text search text. Blank query returns no records.
- `types`: Optional list. Supported values are `schema`, `sftp-server`, `netsuite-auth`, `netsuite-endpoint`, `saved-run`, and `run-result`.
- `pageIndex`: Zero-based page index. Defaults to `0`.
- `pageSize`: Page size. Defaults to `20` and is capped at `50`.

## Response

Each result contains:

- `resultId`: Stable search result id.
- `type`: Search domain.
- `label`: User-facing command label.
- `description`: Short context for the command palette or Ask Darpan response.
- `routeName`: Vue route name.
- `routePath`: Fully resolved path.
- `routeParams`: Route params for clients that prefer named-route navigation.
- `routeQuery`: Optional route query params.
- `score`: Relative score for sorting.
- `sourceId`: Underlying source record id or output file name.
- `sourceType`: Underlying entity or generated-output source.

## Current Domains

- SFTP servers route to `settings-sftp-edit`.
- NetSuite auth configs route to `settings-netsuite-auth-edit`.
- NetSuite endpoint configs route to `settings-netsuite-endpoints-edit`.
- Active JSON schemas route to `schemas-editor`.
- Saved runs route to `reconciliation-run-history`.
- Generated run outputs route to `reconciliation-run-result`.

Generated outputs are read from the active tenant's scoped output directory. If an output file includes a `companyUserGroupId` metadata value that does not match the active tenant, the search result is not returned.
