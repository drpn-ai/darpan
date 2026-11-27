# Darpan 3.5.6

Release date: 2026-11-27

## Scope note

- **Backend hotfix before Black Friday** (Aditi 11:16-11:18 IST: "deploy the backend", "Release 3.5.6 now", "do hotfix").
  3.5.6 = `v3.5.5` + darpan `main` (`34721b81`): DAR-BE-384, the DAR-BE-383 follow-up, three mascot-seed regenerations.
- **Upgrade data**: the regenerated mascot seed (`AppContentSeedData.xml`, 2382 rows). **Migration**: none.
  **App**: unchanged here (prod runs darpan-ui f800cfd; DAR-UI-158 and the app half of DAR-BE-384 wait for an app release).
- Facade contract stays at **6**.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.5` | `v3.5.6` |
| `drpn-ai/darpan-docker-config` | `v3.5.5` | `v3.5.6` (DARPAN_REF only) |
| components, framework, `darpan-ui` | unchanged | unchanged |

## User-visible changes

- **A run's link is short** (DAR-BE-384). Chat cards, emails and error cards link `/runs/<saved run>/<run>?tenantId=…`
  when the run is known (the long `/reconciliation/run-result/…` link only without a run id); search results for a run's
  result open there too. The app (since f800cfd) already opens those links.
- **An old long link turns short without a 10-20 s wait** once the app half ships (darpan-ui a5d7909): the result's first
  page now names its run (`metadata.reconciliationRunResultId`), so the app no longer lists every output to find it.
- **Mascot words for the short run page, the Starting page and the Pulses pages** (DAR-UI-154/156/157 seeds): admins can
  edit these hints on prod.

## Operator-visible changes

- The ID index's temp copy of a tenant's ids is owner-only (0600 on POSIX), per security review (DAR-BE-383 follow-up).
- Error cards for a run link its short page (the separate run-live link is gone).

## Upgrade data

- The mascot seed: `AppContentEntry`, `AppRoute`, `AppRouteAnchor` and the seed mark, from `data/AppContentSeedData.xml`.
  Create-or-update of `default*` fields only: admin edits stay. Previous archived at `data/releases/3.5.5/upgrade-data.xml`.

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-385 (list#GeneratedOutputs pages run rows in the database), after Black Friday by Aditi's choice.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.5` (docker-config `v3.5.5`, image `prod-v3.5.5-202611261441-021659a`). The seed rows stay
  (harmless to 3.5.5: it reads them the same way).
