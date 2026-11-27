# Darpan 3.5.8

Release date: 2026-11-27

## Scope note

- **Backend hotfix on Black Friday, deployed in place of 3.5.7** (cut and tagged at 17:02 IST, never deployed). Aditi
  17:04 IST: "if we still have time until the window to deploy, include anything that comes in"; 17:13 IST: one roll that
  waits for DAR-BE-395.
  3.5.8 = `v3.5.7` + DAR-BE-393, the DAR-BE-391 no-logo headers, and DAR-BE-395. Against prod (`v3.5.6`) it also carries all
  of 3.5.7: DAR-BE-386, DAR-BE-389, DAR-BE-388, DAR-BE-390, DAR-BE-391 and the DAR-UI-159/161/163 mascot seeds.
- **Upgrade data**: diffed from `v3.5.6` (the version live), so 3.5.7's records load: the mascot seed (2320 rows) and one email
  copy block (`DARPAN_ERROR_NOTICE` / `client.nothing`). **Migration**: none. **App**: unchanged.
- Facade contract stays at **6**: one additive method, `remove#AuditCheck` (338 methods).
- Deferred: DAR-BE-394 (send#AuditRunUpdate "argument type mismatch" in Gorjana's month-close window).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.6` (prod) | `v3.5.8` |
| `drpn-ai/darpan-docker-config` | `v3.5.6` (prod) | `v3.5.8` (DARPAN_REF only) |
| components, framework, `darpan-ui` | unchanged | unchanged |

## User-visible changes

- **A peak period's start in a client room is a card naming only the pairs checked in it** (DAR-BE-389, DAR-BE-388).
  "<name> started", "Today only" or "Until <day>", one line per pair that pulses in the period, repeats as "(N pairs)".
  Start-allowance-only pairs (month close) and the heavy-hours line stay out of client rooms. An end inside another period
  says only "<name> ended."
- **A quiet day says so** (DAR-BE-390, DAR-BE-393). A scheduled check with nothing to compare posts one line to the run's
  usual destinations: "Daily Return Reconciliation · Nov 26: nothing to compare (HotWax 0, Shopify 0)". A store-scoped check
  on a day its store sold nothing (one side read nothing, the other kept nothing) is quiet, not "did not run"; a side that
  keeps nothing is quiet the first day after a day it kept records and alerts from the second day in a row. A quiet day's
  audit checks close no finding.
- **Chat cards** (DAR-BE-391): an audit run posts its card alone; a pulse's past-allowance and caught-up posts are cards
  (the pair, how many are past, the oldest order in red, or what the episode closed) with "Open the pulse"; the hourly summary
  ends with that link. Card headers carry no logo (title and subtitle).
- **A pulse on a database endpoint reads its order times** (DAR-BE-386).
- **A check can be removed from an audit** (DAR-BE-395, `remove#AuditCheck`, API and CLI): the audit no longer runs or shows
  it, its automation stops unless another check runs through it, and its findings and history stay. Refused while it has
  open findings.

## Operator-visible changes

- Slack rooms get each card's words as text (pulse posts with the link). The wording `client.nothing` is editable in the
  admin email editor.

## Upgrade data

- `AppContentEntry` 617, `AppRouteAnchor` 1639, `AppRoute` 63, the seed mark (`data/AppContentSeedData.xml`) and `EmailCopyBlock`
  `DARPAN_ERROR_NOTICE`/`client.nothing` (`data/OpsNoticeSeedData.xml`), create-or-update only (admin edits stay). Previous
  archived at `data/releases/3.5.7/upgrade-data.xml` (equal to the `v3.5.7` root). Loaded at boot.

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-394; DAR-BE-385 after Black Friday.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.6` (docker-config `v3.5.6`, image `prod-v3.5.6-202611270551-7101621`). The seed rows and the
  copy block stay (3.5.6 reads the mascot rows the same way and never asks for `client.nothing`).
- Go/no-go: `darpan-admin --target prod live` shows no running run, clear of a pulse (:33 IST), and before Rails EU at 18:30
  IST or after it finishes.
