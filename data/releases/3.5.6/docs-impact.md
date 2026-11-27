# Docs Impact For Darpan 3.5.6

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-384 | Chat cards, emails and search link a run's short page | none: no step changes (the link opens the same page) | none: no field a user sets | none: no error changed | none: no connection change |
| DAR-BE-383 follow-up | ID index temp copy is owner-only | none: internal | none | none | none |
| Mascot seeds | Hints for the short run page, Starting page, Pulses | none: mascot words are in-app | none | none | none |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed (`get#GeneratedOutputDifferences.metadata` is a free-form map)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.6
- `api-reference/changelog.mdx`: 3.5.6 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no method, schema or setting
- Self-host and deploy pages: self-host/production.mdx (3.5.6: darpan only, mascot seed upgrade data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.6
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.6. "Up next" unchanged.
