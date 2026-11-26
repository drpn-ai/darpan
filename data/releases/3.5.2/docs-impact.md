# Docs Impact For Darpan 3.5.2

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-UI-152 (migration fix) | The note migration runs outside the caller's transaction | none: the audit guide already says the note is optional (changed in 3.4.16) | none: no field changed | none: the failure changed nothing and showed no user message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.2
- `api-reference/changelog.mdx`: 3.5.2 named under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine change
- Self-host and deploy pages: self-host/production.mdx (3.5.2: no upgrade data; run the pending migration, then app v4.4.15)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.2
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.2. "Up next" unchanged.
