# Docs Impact For Darpan 3.2.0

Every shipped item needs a decision in each column: the page path you changed
(a path under the docs repo), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

Repo `darpan-docs` (commits d0fc54c, 2576241, 1ee8202 and e41e6d7 since the 3.1.0 docs commit 764cbc0). How-to =
`getting-started/` and `guides/`; reference = `reference/`; troubleshooting = `troubleshooting/`; setup = the connect
and setup guides under `guides/`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-295 | Admin CLI signs in by browser approval (typed code, super admin with two-factor, 12 h) and keeps its session | none: the admin CLI is an operator tool, described in the reference | reference/auth-and-access.mdx | none: no new user-facing message | none: no connection change |
| DAR-BE-296 | A Darpan admin works in every tenant as tenant admin, with the CLI and AI | none: no workflow step changes | reference/auth-and-access.mdx | none: no new message | none: no connection change |
| DAR-BE-297 | Two-factor can be required for everyone; reminder emails | guides/two-factor.mdx | reference/auth-and-access.mdx | none: setup at sign-in is described in the guide | none: no connection change |
| DAR-BE-298 | Every Darpan email wears the app's look, with guide links and one short line | none: no page describes the emails | none: no reference content | none: no new message | none: no connection change |
| DAR-BE-299 | The run result rule picker shows the rule's name | guides/review-results.mdx | none: no reference content | none: no new message | none: no connection change |
| DAR-BE-300 | HotWax item quantity counts exchange orders with their original order | none: the rule is in the HotWax setup guide | none: no reference content | none: no new message | guides/setup-hotwax.mdx |
| DAR-BE-301 | Sign in with a passkey alone | guides/two-factor.mdx | reference/auth-and-access.mdx | troubleshooting/login-and-session.mdx | none: no connection change |
| DAR-UI-119 | The mascot wears the active tenant as a bowtie | none: no page describes the mascot | none: no reference content | none: no new message | none: no connection change |
| DAR-BE-292 | The tenant user role's description text | none: seed text only | none: the reference already describes the role | none: no new message | none: no connection change |

## GraphQL reference (developers.drpn.ai)

- SDL compared against (local stack at the release commit, then prod after deploy): none: the release adds facade
  RPC methods and entities that GraphQL does not expose; prod re-check after deploy stays owed.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- `graphql/changelog.mdx` entry `## Darpan 3.2.0`: added (no schema change), darpan-developers bbd92bc.
- Hand-written GraphQL pages (overview, tasks, search syntax, examples): none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: every contract method documented, none phantom: yes, 268 of 268
  (`check_service_catalog.py` against the release's committed methods.txt).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.2.0: yes, byte-identical
  to the script's output from the release's committed contract (no admin or CRM schemas).
- `api-reference/changelog.mdx` entry for added, changed or removed methods: yes (`begin#PasskeyFirstSignIn`,
  `finish#PasskeyFirstSignIn`, `start#CliLogin.scope`).

## Engineering and self-host docs

- Runtime baseline (JDK, Moqui, Spark, Drools, transaction manager, JVM flags) matches `build.gradle` in
  `backend/operations-and-validation.mdx` and `self-host/`: yes, unchanged since 3.1.0.
- Backend pages changed for new services, entities, engine or tenancy behaviour (`backend/`):
  backend/operations-and-validation.mdx (admin CLI sign-in, reminder job).
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes:
  self-host/production.mdx (reminder job on the
  scheduler node, UTC policy dates, passkey-only sign-in tables).
- Engineering process pages (`engineering/`): none: no process change.

## Release notes and roadmap

- `releases/updates.mdx` carries this release: yes, `## v3.2.0`, darpan-docs e41e6d7.
- `releases/roadmap.mdx` "Recently shipped" and "Up next" rotated: yes, recently shipped v3.2.0 and v3.1.0; "Up next"
  had no shipped item.
