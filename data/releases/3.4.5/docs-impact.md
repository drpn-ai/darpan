# Docs Impact For Darpan 3.4.5

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. How-to = `getting-started/`, `guides/`; reference = `reference/`;
troubleshooting = `troubleshooting/`; setup = the connection and integration guides
(`guides/connect-*`, `guides/setup-*`). Grep every UI label you write against
`darpan-ui/src` (see `darpan-docs/AGENTS.md`).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| backend 5832eb5c | Release 3.4.4: docs impact, verification and deferred items in the pack (after the tag:... | TODO | TODO | TODO | TODO |
| DAR-UI-140 | DAR-UI-140: mascot seed and AppContentRulesTests sample table for the audit priority pa... | TODO | TODO | TODO | TODO |
| DAR-UI-141 | DAR-UI-141: mascot seed regenerated: the tenant lines say a switch moves this tab only | TODO | TODO | TODO | TODO |
| DAR-BE-354 | DAR-BE-354: age Shopify orders from createdAt so NS_IN_FLIGHT's 24 h grace reaches them | TODO | TODO | TODO | TODO |
| DAR-BE-353 | DAR-BE-353: audit settings list per statement only the causes its link's rows can reach | TODO | TODO | TODO | TODO |

## GraphQL reference (developers.drpn.ai)

- SDL compared against (local stack at the release commit, then prod after deploy): TODO
- Snapshot `graphql-reference/schema.graphql` refreshed and pages regenerated, or `none: schema unchanged`: TODO
- `graphql/changelog.mdx` entry `## Darpan 3.4.5`, or `none: schema unchanged`: TODO
- Hand-written pages touched (`graphql/overview.mdx`, `graphql/tasks/`, `search-syntax.mdx`, examples), or `none: <reason>`: TODO

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: every contract method documented, none phantom: TODO
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.5: TODO
- `api-reference/changelog.mdx` entry for added, changed or removed methods, or `none: <reason>`: TODO

## Engineering and self-host docs

- Runtime baseline (JDK, Moqui, Spark, Drools, transaction manager, JVM flags) matches `build.gradle` in `backend/operations-and-validation.mdx` and `self-host/`: TODO
- Backend pages changed for new services, entities, engine or tenancy behaviour (`backend/`), or `none: <reason>`: TODO
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes (`self-host/production.mdx`, `self-host/troubleshooting.mdx`), or `none: <reason>`: TODO
- Engineering process pages (`engineering/`), or `none: <reason>`: TODO

## Release notes and roadmap

- `releases/updates.mdx` carries this release (hotfixes fold into the release line): TODO
- `releases/roadmap.mdx` "Recently shipped" and "Up next" rotated: TODO
