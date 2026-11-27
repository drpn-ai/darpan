# Darpan 3.5.9

Release date: 2026-11-27

## Scope note

- **Backend hotfix the same evening as 3.5.8** (Aditi 18:07 IST: "cut 3.5.9 and roll it tonight"). 3.5.9 = `v3.5.8` +
  DAR-BE-394 (audit run updates, audit progress and the read report no longer fail "argument type mismatch") + DAR-BE-396
  (the daily error email leaves out deliberate refusals).
- **Upgrade data**: two email copy blocks (`DARPAN_ERROR_DIGEST` `logs.ignored`, `logs.ignored.more`). **Migration**: none.
  **App**: unchanged (4.4.20 is live).
- Facade contract stays at **6**.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.8` | `v3.5.9` |
| `drpn-ai/darpan-docker-config` | `v3.5.8` | `v3.5.9` (DARPAN_REF only) |
| components, framework, `darpan-ui` | unchanged | unchanged |

## User-visible changes

- **An audit's run update arrives even while another of its checks is still running** (DAR-BE-394). Groovy 5 failed a static
  call from a closure when an inline-cast argument was null: an automation whose last good run had a read with no progress
  metrics (a Shopify bulk read) made the audit's progress throw while it ran, so the "is the audit idle" check failed and the
  update was not sent (seen for GORJANA's month-close window, 15:00-19:00 IST, since 2026-11-26). The audit's progress and
  read report open again for such reads.

## Operator-visible changes

- **The daily error email leaves out deliberate refusals** (DAR-BE-396): setting `errors.email.ignorePatterns` (matched against
  each line's masked fingerprint, anchored) drops the `/Login` fence 404s, wrong passwords, signed-out calls, the Elastic boot
  line and CLI input refusals, and the email says how many lines it left out.

## Upgrade data

- `EmailCopyBlock` `DARPAN_ERROR_DIGEST`/`logs.ignored` and `logs.ignored.more` from `data/OpsSeedData.xml`. Previous archived at
  `data/releases/3.5.8/upgrade-data.xml` (equal to the `v3.5.8` root). Loaded at boot.

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-385 after Black Friday.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.8` (image `prod-v3.5.8-202611271149-3283244`). The copy blocks stay (unused by 3.5.8).
- Go/no-go: `darpan-admin --target prod live` shows no running run, clear of a pulse (:33 IST).
