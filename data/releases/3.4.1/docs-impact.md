# Docs Impact For Darpan 3.4.1

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-338 | Automations keep their own filters; Sync copies them | guides/setup-automation.mdx | reference/reconciliation-runs.mdx, reference/glossary.mdx | troubleshooting/automations-and-alerts.mdx (the AUTOMATION_FILTERS_DIFFER section is gone) | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.4.1; prod re-check after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- Service catalog: api-reference/service-catalog.mdx (restore and import no longer block with AUTOMATION_FILTERS_DIFFER).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.1: yes.
- `api-reference/changelog.mdx` entry: yes, a 3.4.1 entry under contract 5 (and the version table fixed: 3.4.0 is contract 5).

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: backend/migrations.mdx,
  backend/reconciliation-engine.mdx, backend/tenant-queries.mdx.
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes:
  self-host/production.mdx, self-host/troubleshooting.mdx.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.4 section is now `## v3.4.1` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.1.
