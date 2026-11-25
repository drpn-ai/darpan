# Docs Impact For Darpan 3.4.10

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
| DAR-UI-148 (app v4.4.12) | A run's page lists each side's read options under Filters, each with a switch that saves at once; before, options were picked only while creating a run | guides/audits.mdx (the Lines paragraph: switch Order lines / Order items on the run's page, then Sync); guides/returns-and-exchanges.mdx (Keep exchange orders can be switched on an existing run's page) | none: no reference page lists read options beyond the Keep table | none: no new message (a refused option names itself in words) | guides/setup-hotwax.mdx and guides/setup-shopify.mdx (the options can be switched later on the run's page) |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `save#RuleSetRun` takes `file1ExtractOptions` / `file2ExtractOptions` (omitted keeps, sent replaces, empty clears)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.10
- `api-reference/changelog.mdx`: a 3.4.10 entry under contract 5 (two optional parameters on `save#RuleSetRun`)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: backend/reconciliation-engine.mdx ("Order lines for audit findings": the options are switched on the run's page; save#RuleSetRun carries them)
- Self-host and deploy pages: self-host/production.mdx (3.4.10 under "Upgrading to 3.4": darpan only, app `v4.4.12`, no upgrade data, no migration)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.10 and app v4.4.12, with its item
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.10 and app v4.4.12. "Up next" unchanged.
