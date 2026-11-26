# Darpan 3.4.12

Release date: 2026-11-26

## Scope note

- **A backend release on the 3.4 line, cut with app `v4.4.14`** (Aditi, 2026-11-26: "Let that session release", then
  "yes" to cutting 3.4.12 + app 4.4.14 with the backend deployed first, a browser look, then the app). It carries
  everything on darpan `main` after `v3.4.11`: DAR-BE-371, DAR-BE-372 and DAR-CLI-008 part 2. Publicly it folds into
  the 3.4 notes.
- **Upgrade data**: none (0 records). No entity change, no migration.
- Facade contract stays at **5**: three methods added, one made remote, one optional parameter and one response field
  added (additive). GraphQL schema unchanged (`StatementCheck` declares its fields; `systems` is not on it).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.11` | `v3.4.12` |
| `drpn-ai/darpan-docker-config` | `v3.4.11` | `v3.4.12` (DARPAN_REF move) |
| `drpn-ai/darpan-ui` | `v4.4.13` | `v4.4.14` |
| darpan-hotwax, shopify-darpan, netsuite-darpan, database-darpan, darpan-agent, moqui-rules | unchanged | unchanged (each `main` equals its pin) |

## User-visible changes

- **An audit finding speaks only of the systems its check compares** (DAR-BE-371, app `v4.4.14`). A HotWax-to-NetSuite
  finding shows HotWax and NetSuite, never the Shopify record beside them, on a morning the check did not run too. The
  report names each check's systems from its label, else its statement, else its automation's sources.
- **The finding card follows seven written rules** (DAR-BE-372, app `v4.4.14`): only the compared systems; basics from
  the read (HotWax's status carried on a NetSuite line, NetSuite's `tranDate` as the day, a line's amount as **Line
  total**); the same rows per kind; the order name and HotWax order id as the reference; records named by their business
  id, never the finding id; no filler; a system with no record reads **Missing**, never a dash.
- **A finding keeps the record its check's conclusion lookup read** (DAR-BE-372): R13 (cancelled in Shopify, not in
  HotWax) keeps the HotWax order, so its card says HotWax's status.
- **Every statement shows its count on a morning its check did not run** (DAR-UI-149, app `v4.4.14`); the per-system
  cards under the fix are gone (DAR-UI-150); every finding of a kind lists the same detail rows (DAR-UI-151).
- **Propose an audit change yourself** (DAR-CLI-008 part 2): the methods behind CLI `0.4.0`'s cause and change commands.
  A person's words vouch for nothing but the finding they name and the config; every other value is shown and must be
  named back (`confirmedIds`) before it applies. CLI `0.4.0` is released after this deploy.

## Operator-visible changes

- `RunConclusionSupport` writes each conclusion lookup's answer onto its result row (`lookedUp`), and the audit keeps it
  as that system's record on the finding.
- `CliMethodAllowlist`: `propose#AuditChange`, `get#AuditChangeProposal`, `list#AuditChangeProposals` and
  `find#AuditFindingLookalikes` reach the CLI.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous is archived at `data/releases/3.4.11/upgrade-data.xml`
  (equals the `v3.4.11` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-CLI-008 part 3 (follow-the-order lookup); DAR-CLI-009 (named query commands); DAR-BE-370 (a finding's Shopify
  status from the morning's other Shopify reads; a month-wide Shopify read decision).

## Rollback or fallback notes

- Roll back by redeploying `v3.4.11` (docker-config `v3.4.11`) and app `v4.4.13`. App `v4.4.14` against a 3.4.11
  backend shows records' own systems where a check has not run (no `systems`), and R13 cards read HotWax **Missing**:
  roll back both together.
