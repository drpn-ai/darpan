# Docs Impact For Darpan 3.5.7

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-UI-159 | Mascot seed for the new Tenant Settings | none: mascot words are in-app | none: no field a user sets | none: no error changed | none: no connection change |
| DAR-UI-163 | Mascot seed without the "• edited" anchors | none: mascot words are in-app | none | none | none |
| DAR-BE-386 | A pulse on a database endpoint reads its order times | none: no step changes (a fix) | none | none: no error message changed | none |
| DAR-BE-389 | A peak period's start post is one line per setting | guides/peak-periods-and-sync-pulse.mdx | none: no field a user sets | none | none |
| DAR-BE-388 | A peak period's start is a card naming the pairs checked in it | guides/peak-periods-and-sync-pulse.mdx | none | none | none |
| DAR-BE-390 | A quiet day posts "nothing to compare" | guides/run-completion-alerts.mdx | reference/reconciliation-runs.mdx | none: no error message changed | none |
| DAR-BE-391 | Audit update is its card alone; pulse posts are cards with Open the pulse | guides/peak-periods-and-sync-pulse.mdx | none: no field a user sets | none | none |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- Service catalog: none: no method changed
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.7
- `api-reference/changelog.mdx`: 3.5.7 under contract 6, no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no method, schema or setting
- Self-host and deploy pages: self-host/production.mdx (3.5.7: darpan only, mascot seed + one copy block upgrade data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.5 section names v3.5.7
- `releases/roadmap.mdx` "Recently shipped": the 3.5 card names v3.5.7. "Up next" unchanged.
