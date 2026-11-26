# Docs Impact For Darpan 3.4.17

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-UI-152 (migration fix) | The note migration's MySQL statement keeps the column's size | none: the audit guide already says the note is optional (changed in 3.4.16) | none: no field changed | none: the failure was caught in a dry run | none: no connection change |
| DAR-BE-373 part (c) | `run#Audit` can play only the checks named | none: the play page has no per-check choice yet | none: no field changed | none: no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `run#Audit` takes `checks`
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.17
- `api-reference/changelog.mdx`: a 3.4.17 entry under contract 5 (243)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine change
- Self-host and deploy pages: self-host/production.mdx (3.4.17: no upgrade data; run the pending migration, then app v4.4.15)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.17
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.17. "Up next" unchanged.
