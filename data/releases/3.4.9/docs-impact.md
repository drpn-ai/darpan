# Docs Impact For Darpan 3.4.9

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. How-to = `getting-started/`, `guides/`; reference = `reference/`;
troubleshooting = `troubleshooting/`; setup = the connection and integration guides
(the guides whose names start with connect- or setup-). Grep every UI label you write against
`darpan-ui/src` (see `darpan-docs/AGENTS.md`).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-361 / DAR-UI-144 | Acknowledge an open finding with a required note and an optional reminder (default 5 days): the row moves to its own acknowledged pill, not closed; the pencil changes it, Reopen removes it. Feedback from the speech bubble reaches Darpan's chat space, the sender gets a thank-you email, and a Darpan admin answers by email | guides/audits.mdx ("Acknowledge a finding", "Send Darpan feedback on a finding", the pill in the toolbar table, the acknowledged line in Digests) | reference/glossary.mdx (Acknowledged); reference/auth-and-access.mdx (Company view only can acknowledge and send feedback) | troubleshooting/audits.mdx ("An acknowledged finding is back on open", "An acknowledgement is refused") | none: no connection or integration setup; the operator setting `feedback.chat.webhookUrl` is in darpan-developers self-host/production.mdx |
| DAR-BE-369 / DAR-UI-147 | Re-check all findings: a circular-arrow icon beside the audit's name (whole period) and at the head of the Findings column (the open statement); each check's open findings read once, by key; no check run; run access; app only | guides/audits.mdx ("Re-check all findings", with a results table) | reference/auth-and-access.mdx (Tenant user re-checks all findings) | troubleshooting/audits.mdx (re-check results table; "Play, Retry, Resolve or Re-check all is missing") | none: no connection or integration setup |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged (no moqui-gql change, still `v1.1.1`); `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed (follow-up: `FindingEvent.event`'s description in moqui-gql lists four events; it can now also return the three acknowledgement events)

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: the 7 new `facade.AuditFacadeServices` methods, `admin.FeedbackAdminServices` `get#FeedbackList` / `reply#Feedback`, and the changed `get#AuditReport`, `get#AuditFindingsByOrder`, `get#AuditFinding` rows (`check_service_catalog.py`: 328 of 328)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.9 (contract 5, 233 public methods)
- `api-reference/changelog.mdx`: a 3.4.9 entry under contract 5 (7 methods added, nothing removed)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged from 3.4.8
- Backend pages: backend/reconciliation-engine.mdx ("Finding acknowledgements, feedback and re-check all"); backend/operations-and-validation.mdx (retention deletes acknowledgements with their finding, keeps feedback)
- Self-host and deploy pages: self-host/production.mdx (3.4.9 under "Upgrading to 3.4": app v4.4.11, 63 upgrade records, two tables on boot, no migration, the three settings, webhook set in the admin app after deploy, rollback)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.9 and app v4.4.11, with three Audits bullets and a 3.4.9 Operators bullet
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.9 and app v4.4.11. "Up next" unchanged
