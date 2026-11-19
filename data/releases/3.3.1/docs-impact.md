# Docs Impact For Darpan 3.3.1

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.3.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-CLIENT-006 | A conclusion condition's field reaches the HotWax order read, whatever the run's filters | none: authoring a condition is unchanged | reference/reconciliation-runs.mdx | none: no new message or failure | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.3.1; prod re-check after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- Service catalog: every contract method documented, none phantom: yes, unchanged from 3.3.0 (no method or
  description changed; checked against the tag's `methods.txt`).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.3.1: yes.
- `api-reference/changelog.mdx` entry: yes, 3.3.1 listed under contract 4 with no method changes.

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: none: no service, entity or engine
  change; the read behaviour is stated in the product reference.
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes: none: no config change.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.3 section is now `## v3.3.1` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.3 card names v3.3.1; v3.2.1 stays beside it.
