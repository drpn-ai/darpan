# Docs Impact For Darpan 3.5.9

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-394 | An audit's run update is sent while another check runs | none: no step changes (a fix) | none: no field a user sets | none: the error never reached a user page | none |
| DAR-BE-396 | The Darpan team's error email leaves out deliberate refusals | none: an operator email, not a tenant feature | none | none | none |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.9
- `api-reference/changelog.mdx`: 3.5.9 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: backend/operations-and-validation.mdx (the error digest job row names `errors.email.ignorePatterns`)
- Self-host and deploy pages: self-host/production.mdx (3.5.9: darpan only, two copy blocks)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.9
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.9. "Up next" unchanged.
