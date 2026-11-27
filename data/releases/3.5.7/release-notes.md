# Darpan 3.5.7

Release date: 2026-11-27

## Scope note

- **Backend hotfix on Black Friday** (Aditi 16:47 IST: "check prod in detail and how pulses are working and see if you can
  safely deploy prod on a new hotfix"; 16:50 IST via the Tenant Settings session: the mascot seeds ship too).
  3.5.7 = `v3.5.6` + darpan `main` (`0b3af7d9`): DAR-BE-386, DAR-BE-389, DAR-BE-388, DAR-BE-390, DAR-BE-391, and the
  DAR-UI-159/161/163 mascot seeds.
- **Upgrade data**: the regenerated mascot seed (`AppContentSeedData.xml`, 2320 rows) and one email copy block
  (`DARPAN_ERROR_NOTICE` / `client.nothing`, `OpsNoticeSeedData.xml`). **Migration**: none.
  **App**: unchanged (the mascot seed's new anchors wait for the next app release; until then prod's AI settings section
  loses its mascot hints).
- Deferred: DAR-BE-394 (send#AuditRunUpdate "argument type mismatch" in Gorjana's month-close window; found during this
  release's prod check, not new in 3.5.6).
- Facade contract stays at **6**.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.6` | `v3.5.7` |
| `drpn-ai/darpan-docker-config` | `v3.5.6` | `v3.5.7` (DARPAN_REF only) |
| components, framework, `darpan-ui` | unchanged | unchanged |

## User-visible changes

- **A peak period's start in a client room is a card naming only the pairs checked in it** (DAR-BE-389, DAR-BE-388).
  "<name> started", "Today only" or "Until <day>", one line per pair that pulses in the period
  ("HotWax → NetSuite · hourly · late after 6h"), repeats folded as "(N pairs)". Pairs that only move a check's start (month
  close) and the heavy-hours line stay out of client rooms. An end inside another period says only "<name> ended."
- **A quiet day says so** (DAR-BE-390). A scheduled check whose sides both read nothing posts one line to the run's usual
  destinations: "Daily Return Reconciliation · Nov 26: nothing to compare (HotWax 0, Shopify 0)". One side empty is unchanged
  (the did-not-run path, alerts room).
- **Chat cards** (DAR-BE-391): an audit run posts its card alone (no line of text above it); a pulse's past-allowance and
  caught-up posts are cards (the pair, how many are past, the oldest order in red, or what the episode closed) with an
  "Open the pulse" button; the hourly summary ends with that link. Card headers use the live Darpan mark.
- **A pulse on a database endpoint reads its order times** (DAR-BE-386): `+0000` offsets parse, so such a pulse no longer
  skips every record and says all clear.

## Operator-visible changes

- Slack rooms get each card's words as text (pulse posts with the link).
- The new wording `client.nothing` is editable in the admin email editor.

## Upgrade data

- The mascot seed (`AppContentEntry` 617, `AppRouteAnchor` 1639, `AppRoute` 63, the seed mark) from `data/AppContentSeedData.xml`
  and `EmailCopyBlock` `DARPAN_ERROR_NOTICE`/`client.nothing` from `data/OpsNoticeSeedData.xml`. Create-or-update only: admin
  edits stay. Previous archived at `data/releases/3.5.6/upgrade-data.xml` (equal to the `v3.5.6` root). Loaded at boot by the
  image's upgrade-data load.

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-394 (see scope note). DAR-BE-385 stays after Black Friday.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.6` (docker-config `v3.5.6`, image `prod-v3.5.6-202611270551-7101621`). The seed rows and the
  copy block stay (3.5.6 reads the mascot rows the same way and never asks for `client.nothing`).
- Go/no-go for the roll: `darpan-admin --target prod live` shows no running run, not within 5 minutes of a pulse (:33 IST),
  before Rails EU at 18:30 IST.
