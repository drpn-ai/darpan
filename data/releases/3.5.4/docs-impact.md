# Docs Impact For Darpan 3.5.4

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-382 | A pulse that does not finish never posts to the client's chat space; the Darpan team hears of it | guides/peak-periods-and-sync-pulse.mdx (what the pulse posts) | none: no field changed | none: the client sees no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.4
- `api-reference/changelog.mdx`: 3.5.4 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no method, schema or setting
- Self-host and deploy pages: self-host/production.mdx (3.5.4 in place of 3.5.3; no upgrade data of its own)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.4
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.4. "Up next" unchanged.
