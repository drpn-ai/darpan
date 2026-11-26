# Docs Impact For Darpan 3.4.16

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-UI-152 | Acknowledging a finding needs no note | guides/audits.mdx (Acknowledge a finding: the note is optional) | none: no status, field or term changed | none: no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `acknowledge#AuditFinding` note optional
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.16
- `api-reference/changelog.mdx`: a 3.4.16 entry under contract 5 (243)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine change
- Self-host and deploy pages: self-host/production.mdx (3.4.16: 1 upgrade record, run the pending migrations, then app v4.4.15)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.16 and app v4.4.15, with its item
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.16. "Up next" unchanged.
