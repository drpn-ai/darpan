# Docs Impact For Darpan 3.4.8

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
| DAR-BE-368 (darpan-hotwax 1.3.4) | Order lines are a run side's read option: "Order lines" on a Shopify orders side, "Order items" on a HotWax orders side; ticked, an order finding lists its lines (3.4.7) | guides/audits.mdx (the Lines paragraph: the two options turn it on, set on the run's sides, then sync the automation) | none: no reference page lists read options beyond the Keep table | none: no new message | guides/setup-hotwax.mdx (Orders row: the Order items option); guides/setup-shopify.mdx (Orders: the Order lines option) |
| backend bba089c0 | 3.4.7 pack completed after its tag | none: release tooling only | none: release tooling only | none: release tooling only | none: release tooling only |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: none: no contract method or field changed (an option is connector data)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.8
- `api-reference/changelog.mdx`: a 3.4.8 line under contract 5 (no method added or removed)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged from 3.4.7
- Backend pages: backend/reconciliation-engine.mdx ("Order lines for audit findings": the two connector options, set on the run side, carried by sync)
- Self-host and deploy pages: self-host/production.mdx (3.4.8 under "Upgrading to 3.4": darpan-hotwax `v1.3.4`, 1 upgrade record, no migration; the per-source turn-on now via the run's options)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.8 (app stays v4.4.10), with its item
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.8. "Up next" unchanged.
