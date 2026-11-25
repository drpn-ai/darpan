# Darpan 3.4.9

Release date: 2026-11-25

## Scope note

- **A feature release on the 3.4 line** (Aditi, 2026-11-25 19:00 IST "cut the release and deploy", then "Ship all"). It
  carries everything on darpan `main` after `v3.4.8`: acknowledging a finding with a note and sending feedback to Darpan
  from it (DAR-BE-361, app DAR-UI-144), and Re-check all (DAR-BE-369, app DAR-UI-147). Paired with app `v4.4.11`.
  Publicly it folds into the 3.4 notes.
- **Upgrade data**: 63 records, loaded on boot as `darpan-seed`. The whole audit vocabulary is restated (3 new finding
  events), plus 2 email templates, 16 email copy blocks and 2 tenant entity filters. The 2 new tables
  (`AuditFindingAcknowledgement`, `AuditFindingFeedback`) are created by Moqui on boot. No migration.
- Facade contract stays at **5** (9 methods added, none removed or changed). GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.8` | `v3.4.9` |
| `drpn-ai/darpan-ui` | `v4.4.10` | `v4.4.11` |
| `drpn-ai/darpan-docker-config` | `v3.4.8` | `v3.4.9` (DARPAN_REF moves) |
| darpan-hotwax, shopify-darpan, netsuite-darpan, database-darpan, darpan-agent, moqui-gql, moqui-rules, moqui-jexl, darpan-cli | unchanged | unchanged (darpan-hotwax `v1.3.4`, shopify-darpan `v0.11.2`) |

## User-visible changes

- **Acknowledge a finding** (DAR-BE-361, DAR-UI-144). An open finding row takes a note that says the problem is known;
  the row leaves Open for Acknowledged until it is fixed or, when a reminder is on, until its remind day (default 5
  days, setting `audit.acknowledge.remindDays`). The note can be changed or the row reopened. The audit emails count
  acknowledged findings apart.
- **Feedback to Darpan from a finding** (DAR-BE-361, DAR-UI-144). A finding's feedback bubble sends a note to Darpan's
  own chat space; the sender gets a thank-you email, and a Darpan admin can answer by email from the admin app.
- **Re-check all** (DAR-BE-369, DAR-UI-147). A re-check icon on the audit header (every open finding of the period) and
  on the Findings column (the open statement's). Each check's open findings are read again by key, once per check, and
  what was fixed since the morning closes; the page says how many closed. Run access only; no check run is written, so
  check health and the next morning's read are unchanged.

## Operator-visible changes

- Upgrade data loads on boot. New tables are created on boot.
- New settings (admin app): `audit.acknowledge.remindDays` (5, 1-60), `audit.feedback.shownDays` (14, 1-90),
  `feedback.chat.webhookUrl` (blank: feedback is saved and the email still goes, nothing is posted to chat).
- New methods: `acknowledge#AuditFinding`, `update#AuditFindingAcknowledgement`, `delete#AuditFindingAcknowledgement`,
  `send#AuditFindingFeedback`, `get#AuditFindingFeedback`, `verify#AuditFindings`, `get#AuditRecheckProgress` (facade);
  `get#FeedbackList`, `reply#Feedback` (admin). The feedback, re-check-all and admin methods are not on the CLI allowlist.

## Upgrade data

- Current upgrade file: `data/upgrade-data.xml` (63 records), mirrored in this pack. Order: the audit vocabulary (each
  type ahead of its members), email templates, copy blocks, entity filters.
- Previous upgrade data archived: `data/releases/3.4.8/upgrade-data.xml` (equals the `v3.4.8` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- Set `feedback.chat.webhookUrl` in the admin app, read both feedback emails' previews, and send a first real feedback:
  DAR-BE-361 prod-owed.
- A Re-check all that closes findings on prod (RAILS R10): DAR-BE-369 / DAR-UI-147 prod-owed.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.8` (docker-config `v3.4.8`) and app `v4.4.10`. The new tables and rows stay and are
  ignored by `v3.4.8`; an acknowledged finding then shows as open again.
