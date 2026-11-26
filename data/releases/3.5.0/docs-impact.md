# Docs Impact For Darpan 3.5.0

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-374 | darpan lookup answers from an ID index of what Darpan reads | none: CLI 0.6.0 follows the deploy; its session writes the lookup page then | none: CLI 0.6.0 follows the deploy | none: CLI 0.6.0 follows the deploy | none: no connection change |
| DAR-UI-152 | Acknowledging a finding needs no note | none: the audit guide already says so (changed in 3.4.16) | none: no field changed | none: no new message | none: no connection change |
| DAR-BE-373 part (c) | A play can run only the checks named | none: the play page has no per-check choice yet | none: no field changed | none: no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: ID index methods added; the lookup catalog methods removed; lookup#Order output
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.0
- `api-reference/changelog.mdx`: contract 6 (3.5.0), with the removals

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: the ID index is documented by the service catalog section
- Self-host and deploy pages: self-host/production.mdx (Upgrading to 3.5: four pins, 4 upgrade records, the pending migration, app v4.4.15)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: a v3.5.0 section
- `releases/roadmap.mdx` "Recently shipped": a v3.5.0 card
