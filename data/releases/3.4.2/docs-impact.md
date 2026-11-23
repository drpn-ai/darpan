# Docs Impact For Darpan 3.4.2

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-339 | The exchange presence check looks up every unmatched exchange (no 200 cap, no "deferred") | guides/returns-and-exchanges.mdx | none: no status, field or term changed | none: the removed warning had no troubleshooting entry | none: no connection change |
| DAR-BE-341 | Run now reads its second side in the run's own tenant | none: Run now works as the guides already describe | none: no status, field or term changed | none: a defect fix with no new message or operator fix | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.4.2 (no moqui-gql, entity or service-definition change in
  `v3.4.1..v3.4.2`); compared with prod's 3.4.1 SDL before the deploy, re-checked against prod after it.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- Service catalog: none: no method added, removed or changed.
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.2: yes.
- `api-reference/changelog.mdx` entry: yes, a 3.4.2 entry under contract 5 (no method change).

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: none: the engine page already says
  exchange presence checks every candidate it is given, and the worker's tenant follows the documented request tenant.
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes: none: no config or
  failure-mode change.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.4 section is now `## v3.4.2` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.2.
