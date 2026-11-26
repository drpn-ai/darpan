# Docs Impact For Darpan 3.5.5

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-383 | Runs and sync pulses no longer fail now and then with a missing-file error | none: no step changes | none: no field changed | none: no page lists the error | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.5
- `api-reference/changelog.mdx`: 3.5.5 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no method, schema or setting
- Self-host and deploy pages: self-host/production.mdx (3.5.5: darpan only, no upgrade data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.5
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.5. "Up next" unchanged.
