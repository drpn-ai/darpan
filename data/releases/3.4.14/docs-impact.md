# Docs Impact For Darpan 3.4.14

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-CLI-008 (CLI 0.5.0 + findings fix) | `darpan lookup` follows an order through Shopify, HotWax and NetSuite and lists all its open findings | getting-started/set-up-darpan-cli.mdx (Look up any order) | none: no reference page lists lookup behaviour | none: no new message | none: connections unchanged |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: none: no method added, removed or changed in contract
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.14
- `api-reference/changelog.mdx`: 3.4.14 named among the releases that added or removed no methods

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine or data change
- Self-host and deploy pages: self-host/production.mdx (3.4.14 under "Upgrading to 3.4": darpan only, no upgrade data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.14 and CLI 0.5.0
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.14
