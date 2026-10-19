---
name: darpan-backend-component
description: Guide coding and verifying changes inside the Darpan Moqui component, following local conventions and safety rules.
---

# Skill: darpan-backend-component

## Use when
- Changing logic, services, entities or seed data in `runtime/component/darpan/**`.
- Reviewing backend changes in this component.
- Adding backend contracts consumed by `darpan-ui` or `darpan-admin-ui`.

## Don't use when
- The change belongs to the framework, another component, or a UI repo.

## Procedure
1. **Intake.** Classify the ask; for a feature or bug fix, update the wrapper `roadmap/BACKLOG.md`
   item first. Work on `main`.
2. **Scope.** Confirm the change is in this component. UI work goes to `darpan-ui` /
   `darpan-admin-ui`.
3. **Logic.** XML actions first; Groovy only for Spark, Drools, file streaming, HTTP clients,
   crypto, or non-trivial JSON/string work. Tenant reads in Groovy use `TenantScopedFinder`.
4. **Contract.** New remote services are `facade.*` or `admin.*` (`RpcMethodFence` refuses any
   `/rpc/json` method outside those and `gql.*` with 403); regenerate
   `docs/api-contract/` with `generateApiContract`.
5. **Data.** Run artifacts live under `runtime://datamanager/reconciliation-runs/**`; schemas are
   stored in the `JsonSchema` entity. Seed changes also go into the release's upgrade data;
   client configuration (`darpan-tenant-config`) does not.
6. **Verify.** Focused tests only (`scripts/focused-test.sh` or `test --tests <FQCN>`), on JDK 21.
   Note that Gradle does not rerun tests for a data-XML-only change; use `--rerun` after the task.
7. **Docs.** Update `docs/**` when behaviour changes.

## Guardrails
- Keep the Spark `--add-opens` JVM flags in `build.gradle`, `dev-stack.sh` and the
  `darpan-docker-config` entrypoints.
- No secrets in logs, responses, commits or test fixtures.
- Sanitize file names to `[A-Za-z0-9._-]`.
- No new custom UI under backend `screen/**`, `template/**` or `theme-library/**`.

## Failure handling
- Test failure: find the root cause before changing code; do not weaken the test.
- XML error: fix it before anything else; the component will not load.
