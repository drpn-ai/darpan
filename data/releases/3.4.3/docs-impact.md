# Docs Impact For Darpan 3.4.3

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-UI-138 | Queries move to their own page (`/queries`, Dashboard action, Cmd+K) with per-user pins, tiles grouped by what they read; the query page reads Data -> Script -> Result with a Data popup picked by name | guides/queries.mdx, guides/manage-tenant-settings.mdx, getting-started/tenant-admin-quickstart.mdx, getting-started/ask-darpan-navigation.mdx, getting-started/overview.mdx | reference/queries.mdx, reference/glossary.mdx | troubleshooting/queries.mdx | none: no connection change |
| DAR-BE-344 | A NetSuite `SALES_LINES` query may carry `LINE_SUM` / `LINE_ITEMS` over the order's lines on every line | none: SuiteQL column expressions (`LINKED_RECORDS`, `LINE_LINKED`, `LINE_SUM`) are set in tenant config packs by Darpan, and no guide documents them | none: no status, field or term a user sees changed | none: no new message; an unsupported expression keeps its existing "must be bare header columns" refusal | none: the NetSuite connection and its credentials are unchanged |
| DAR-OPS-026 | `resetForTests` removed from three classes | none: test-only, no behaviour change | none: test-only | none: test-only | none: test-only |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.4.3 (no moqui-gql or entity change in `v3.4.2..v3.4.3`; the new
  facade method is JSON-RPC only and the SDL carries no query types); checked against prod's 3.4.2 SDL before the
  deploy, re-checked against prod after it.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- Service catalog: api-reference/service-catalog.mdx documents `save#TenantQueryPins` and `list#TenantQueries`'
  `pinnedQueryIds`.
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.3: yes.
- `api-reference/changelog.mdx` entry: yes, a 3.4.3 entry under contract 5 (one method added, 225 -> 226).

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: backend/tenant-queries.mdx (pins
  as a per-user, per-tenant preference).
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes: none: no config
  change; the netsuite-darpan pin is in `docker/prod/Dockerfile`, which the self-host production page already describes as
  naming the release's tags.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.4 section is now `## v3.4.3` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.3 and app v4.4.3.
