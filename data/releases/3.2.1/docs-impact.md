# Docs Impact For Darpan 3.2.1

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.2.

## Product docs (docs.drpn.ai)

Repo `darpan-docs` (commit b0c72a4).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-302 | Requiring two-factor for super admins or everyone needs the setter's second way back in | guides/manage-users-and-access.mdx | none: the reference does not state the dating rule | none: the refusal message explains itself | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.2.1; prod re-check after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- `graphql/changelog.mdx` entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: every contract method documented, none phantom: yes, 268 of 268; the
  update#TwoFactorPolicy row states the new rule (darpan-developers d946f25).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.2.1: yes (5594690).
- `api-reference/changelog.mdx` entry: yes, 3.2.1 listed under contract 4 with no method changes.

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: backend/operations-and-validation.mdx
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes:
  self-host/production.mdx
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.2 section is now `## v3.2.1` (one section per line).
- `releases/roadmap.mdx` "Recently shipped" rotated: yes, v3.2.1 and v3.1.0.
