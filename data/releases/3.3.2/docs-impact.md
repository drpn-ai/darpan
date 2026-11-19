# Docs Impact For Darpan 3.3.2

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.3.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-321 | The Schedule page and job edits work on production databases | none: the Schedule page is the operator admin app, documented on developers.drpn.ai | none: no product reference covers jobs | none: the error is gone, no new message | none: no connection change |
| DAR-BE-322 | A daily job's time can be changed on a daylight-saving server clock | none: operator admin app only | none: no product reference covers jobs | none: no new message | none: no connection change |
| DAR-BE-319 | A disabled migration stays disabled after a deploy | none: operator admin app only | none: no product reference covers migrations | none: no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: no schema change in 3.3.2; prod re-check after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- Service catalog: every contract method documented, none phantom: yes, unchanged from 3.3.1 (no method or
  description changed; checked against the tag's `methods.txt`).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.3.2: yes.
- `api-reference/changelog.mdx` entry: yes, 3.3.2 listed under contract 4 with no method changes.

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged.
- Backend pages changed for new services, entities, engine or tenancy behaviour: backend/operations-and-validation.mdx
  (a cron's gap counts the wall clock, so a daily job can be re-timed on a daylight-saving clock).
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes: none: no config change.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, the 3.3 section is now `## v3.3.2` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.3 card names v3.3.2; v3.2.1 stays beside it.
