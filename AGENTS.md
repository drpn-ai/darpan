# Darpan AGENTS.md

Component-level guidance for `runtime/component/darpan`. Workspace rules (classification, backlog,
branching, closeout notes) live in the wrapper checkout's `AGENTS.md` and `BACKLOG_WORKFLOW.md`;
they win where this file is silent.

## Workflow
- Classify each ask as `feature`, `bug fix`, or `neither`. For a feature or bug fix, add or update
  the item in the wrapper `roadmap/BACKLOG.md` first; it is the source of truth for status (not
  GitHub Issues, not Linear).
- Work directly on `main`; no topic branches. Put the backlog ID (`DAR-<SURFACE>-<n>`) in commit
  messages.
- Agents stop before deploy, tag push, or any live write to a client system.
- Start with `SKILLS.md`, then read `docs/**` only for the feature being changed.

## Scope and ownership
- Keep changes in `runtime/component/darpan/**` unless the task names another component. Custom UI
  belongs in `darpan-ui` (tenant app) or `darpan-admin-ui` (operator app), never in backend
  `screen/**`, `template/**` or `theme-library/**`.
- Use generic, system-neutral names for entities, services and config.

## Services and XML
- XML actions are the default service body. Groovy is for Spark, Drools, streaming file I/O,
  HTTP/GraphQL clients, crypto, and non-trivial JSON or string work.
- Declare every `in-parameters` / `out-parameters` entry with type and description; a script
  service drops undeclared outputs.
- Tenant-scoped reads in Groovy go through `TenantScopedFinder`; do not add bare `disableAuthz`.
- A new remote service is `facade.*` (tenant app) or `admin.*` (super-admin only). `/rpc/json`
  refuses every other namespace. Regenerate `docs/api-contract/` with
  `./gradlew :runtime:component:darpan:generateApiContract` in the same change.
- Every entity with `companyUserGroupId` needs a `DARPAN_ACTIVE_COMPANY_SCOPE` filter row.
- Do not add backward-compatibility aliases unless asked.

## Reconciliation
- Validate inputs early and return structured errors.
- Use Spark for large transforms; avoid `collect()` on large datasets.
- Prefer configurable rules, connector registry rows and schema metadata over source-specific
  branches.
- Never log or return credentials; credentials live in encrypted entity fields.

## Verification
- Run only the tests that cover the change: `scripts/focused-test.sh`, or
  `./gradlew :runtime:component:darpan:test --tests <FQCN>`. Pools: `unitTest` (no Moqui boot),
  `smokeTest` (boots Moqui), `testAll` (both), `mysqlTest` (MySQL locking). See `README.md`.
- JDK 21 is required. Validate XML after every entity/service/screen edit.
- Report what was not run, and state runtime/browser gaps separately from test results.
- When behaviour changes, update the closest doc under `docs/**` in the same change.
