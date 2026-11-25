# Docs Impact For Darpan 3.4.7

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. How-to = `getting-started/`, `guides/`; reference = `reference/`;
troubleshooting = `troubleshooting/`; setup = the connection and integration guides
(`guides/connect-*`, `guides/setup-*`). Grep every UI label you write against
`darpan-ui/src` (see `darpan-docs/AGENTS.md`).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-368 (darpan-hotwax 1.3.3, shopify-darpan 0.11.2, app v4.4.9) | An order finding lists its lines under Order: SKU, quantity and each system's word ("Shopify Unfulfilled · HotWax Completed"), a Shopify line and its HotWax item as one row; on per check (Shopify `withLineItems`, HotWax `readFields: ["orderItems"]`) | guides/audits.mdx (Read the audit page: the Lines under Order) | none: no reference page lists a finding's record facts | none: no new message; a check that does not ask for lines shows none | none: the options are set by Darpan per audit check, not in a connection a user sets up |
| DAR-BE-362 | A failed interactive saved run tells its notify-me subscribers only the neutral line; the error goes to Darpan's error space; a run cancelled meanwhile is not told | guides/run-completion-alerts.mdx (a saved run started by hand that fails) | none: no status or term changed | troubleshooting/automations-and-alerts.mdx ("did not finish" row) | none: no connection change |
| DAR-BE-363 | The client's audit digest shows the neutral not-run notice, never the read-failure text | guides/audits.mdx (the daily digest line for a check that did not run) | none: same | none: no new message (the neutral line is already documented) | none: same |
| DAR-BE-366 | `audit.unexplained.email.to` is refused on save unless it is a list of email addresses | none: a Darpan-only setting, never a tenant's | none: same | none: same | none: the setting is on the developers self-host page |
| backend 9b85d3be | 3.4.6 pack completed after its tag | none: release tooling only | none: release tooling only | none: release tooling only | none: release tooling only |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged (moqui-gql stays `v1.1.1`; no GraphQL file touched in the ranges); `verify_release_docs.py` compares the prod SDL with the snapshot after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `get#AuditFinding` records may carry `lines`; no method added or removed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.7
- `api-reference/changelog.mdx`: a 3.4.7 entry under contract 5 (finding records' `lines`; no method added or removed)

## Engineering and self-host docs

- Runtime baseline (JDK, Moqui, Spark, Drools, transaction manager, JVM flags) matches `build.gradle`: yes, unchanged from 3.4.6 (`build.gradle` not touched in the range)
- Backend pages: backend/reconciliation-engine.mdx (a source's own readFields ride beside the rules'; HotWax `orderItems`, Shopify `withLineItems`; the saved-run error path)
- Self-host and deploy pages: self-host/production.mdx (3.4.7 under "Upgrading to 3.4": darpan-hotwax `v1.3.3`, shopify-darpan `v0.11.2`, app `v4.4.9`, 1 upgrade record, no migration)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.7 and app v4.4.9, with its items
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.7 and app v4.4.9. "Up next" unchanged.
