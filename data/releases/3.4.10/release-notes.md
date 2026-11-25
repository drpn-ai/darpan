# Darpan 3.4.10

Release date: 2026-11-25

## Scope note

- **A backend and app hotfix on the 3.4 line** (Aditi, 2026-11-25 19:29 IST "yes" to: push, release and deploy darpan
  3.4.10 and the app, then turn the order-line options on for Rails R5). It carries everything on darpan `main` after
  `v3.4.9` (DAR-UI-148 only) and darpan-ui `main` after `v4.4.11`. Publicly it folds into the 3.4 notes.
- **Upgrade data**: none (0 records). No entity change, no migration.
- Facade contract stays at **5** (two optional parameters added to `save#RuleSetRun`). GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.9` | `v3.4.10` |
| `drpn-ai/darpan-ui` | `v4.4.11` | `v4.4.12` (DAR-UI-148 Read options) |
| `drpn-ai/darpan-docker-config` | `v3.4.9` | `v3.4.10` (DARPAN_REF move) |
| darpan-hotwax, shopify-darpan and every other component | unchanged | unchanged |

## User-visible changes

- **A run's read options can be changed after it is made** (DAR-UI-148). The run's page lists, under Filters, each option
  its sides' connectors declare (Keep exchange orders, Order items, Order lines, the returns switches) with a switch that
  saves at once. Before, options could only be picked while creating a run.

## Operator-visible changes

- `save#RuleSetRun` takes `file1ExtractOptions` / `file2ExtractOptions`: omitted keeps a side's stored options, sent
  replaces them, an empty map clears them; an undeclared name is refused before anything is written.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous archived at `data/releases/3.4.9/upgrade-data.xml` (equals
  the `v3.4.9` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- Turning Order lines / Order items on for Rails R5's five runs and syncing their automations, right after the deploy
  (DAR-BE-368).

## Rollback or fallback notes

- Roll back by redeploying `v3.4.9` (docker-config `v3.4.9`) and app `v4.4.11`. Options saved meanwhile stay stored and keep
  working; only the editor goes away.
