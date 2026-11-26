# Docs Impact For Darpan 3.5.1

Each shipped item names the page changed in each surface, or none with a reason.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-377 | A sync pulse can watch a Shopify → HotWax check | guides/peak-periods-and-sync-pulse.mdx (requirements, what the pulse reads, the upstream side to pick) | none: no field or parameter a reader sets changed | none: the refusal names both systems itself | none: no connection change |
| DAR-BE-378 | Errors and warnings never reach a client chat room; one run update a morning | guides/run-completion-alerts.mdx (an automation's chat space is never posted a run error; the update's email names checks that did not run, its chat card does not); guides/audits.mdx (the update after each run goes out once a morning) | none: no field changed | troubleshooting/automations-and-alerts.mdx (the "did not run this morning" line reaches only a Notify me space) | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: save#SyncPair says a pulse needs HotWax or Shopify orders upstream
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.5.1
- `api-reference/changelog.mdx`: a 3.5.1 entry under contract 6 (242), no method added or removed

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: the connector fields are operator data, covered by the self-host note
- Self-host and deploy pages: self-host/production.mdx (3.5.1 under Upgrading to 3.5: darpan alone, 4 upgrade records, no migration of its own; DAR-BE-378 adds no data)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the v3.5.0 section becomes v3.5.1 (hotfix consolidation), with the pulse bullet and the operator line
- `releases/roadmap.mdx` "Recently shipped": the v3.5.0 card becomes v3.5.1
