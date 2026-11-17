# Docs Impact For Darpan 3.1.0

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. How-to = `getting-started/`, `guides/`; reference = `reference/`;
troubleshooting = `troubleshooting/`; setup = the connection and integration guides
(the connect and setup guides under `guides/`). Grep every UI label you write against
`darpan-ui/src` (see `darpan-docs/AGENTS.md`).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-255 | DAR-BE-255, DAR-BE-257: a NetSuite auth config's stored secret is not reused after its ... | none: the change is described in the setup guides | none: no reference contract changed | troubleshooting/connections.mdx, troubleshooting/reconciliation-runs.mdx | guides/setup-shopify.mdx, guides/connect-netsuite.mdx |
| DAR-ADMIN-011 | DAR-ADMIN-011: the admin app sees and sets Darpan admins — get#UserList/get#UserDetail ... | guides/manage-users-and-access.mdx | reference/auth-and-access.mdx | none: admin-app only, its refusals say what to do | none: no connection setup |
| DAR-BE-261 | DAR-BE-261: a web sign-in ends after 60 idle minutes and at most 24 hours after sign-in... | quickstart.mdx, getting-started/set-up-darpan-cli.mdx | reference/auth-and-access.mdx | troubleshooting/login-and-session.mdx | none: no connection setup |
| DAR-BE-262 | DAR-BE-262: the outbound HTTP guard checks the address the client connects to (closes t... | none: outbound HTTP hardening, no workflow change | none: internal client | none: no new user-facing error | none: no connection settings changed |
| DAR-BE-263 | DAR-BE-263: the disableAuthz ratchet also counts darpan-agent (0 in every scope); the g... | none: CI ratchet | none: internal | none: internal | none: internal |
| DAR-BE-269 | DAR-BE-269: a result file stamped for another tenant is no longer shown to whichever ru... | none: tenant-isolation hardening | none: internal | none: internal | none: internal |
| DAR-BE-258 | DAR-BE-258: a NetSuite endpoint stays on its auth config's NetSuite account | none: the rule is in the setup guide | none: no reference contract changed | troubleshooting/connections.mdx | guides/connect-netsuite.mdx |
| DAR-BE-260 | DAR-BE-260: GraphQlDocumentCheck.fieldPaths — every field a document selects as a path ... | none: the change is in the setup guide | none: the field catalog is not documented field by field | troubleshooting/connections.mdx | guides/setup-shopify.mdx |
| DAR-BE-268 | DAR-BE-268: passwords need 12 characters (Darpan conf override of the framework's 8); t... | quickstart.mdx, guides/manage-users-and-access.mdx | reference/auth-and-access.mdx | none: the sign-in page lists the rules it checks | none: no connection setup |
| DAR-BE-267 | DAR-BE-267: moqui-gql joins the disableAuthz ratchet (Groovy 4, XML 4, disable-authz 0 ... | none: the app has no key screen; GraphQL keys are documented on developers.drpn.ai | reference/auth-and-access.mdx | none: no new user-facing error | none: no connection setup |
| DAR-BE-265 | DAR-BE-265: the admin audit trail covers every admin mutation and every download, and t... | none: operator and internal audit trail | none: internal | none: internal | none: internal |
| DAR-BE-266 | DAR-BE-266: per-table retention for findings, run results, AI transcripts and agent que... | none: admin-app only, off until a period is set | none: not user-facing | none: not user-facing | none: not user-facing |
| DAR-BE-264 | DAR-BE-264: two-factor data model and TOTP — darpan.auth TwoFactorFactor (encrypted sec... | guides/two-factor.mdx, guides/manage-users-and-access.mdx, getting-started/set-up-darpan-cli.mdx | reference/auth-and-access.mdx | troubleshooting/login-and-session.mdx | none: no connection setup |
| DAR-OPS-019 | DAR-OPS-019: admin.SecurityAdminServices.rotate#EncryptedFields — super-admin command t... | none: operator runbook (key rotation) | none: operator only | none: operator only | none: operator only |
| DAR-BE-270 | DAR-BE-270: passkey data model — webauthn4j-core 0.30.3 (Apache-2.0, the Jackson 2 main... | guides/two-factor.mdx | reference/auth-and-access.mdx, reference/glossary.mdx | troubleshooting/login-and-session.mdx | none: no connection setup |
| DAR-BE-275 | DAR-BE-275: tenant filter for darpan.shopify.ShopifyInstallState (pending next upgrade ... | none: no app UI yet | none: not user-facing yet | none: not user-facing yet | none: not user-facing yet |
| DAR-BE-271 | DAR-BE-271: raw source extracts deleted 7 days after their run — purge#ExtractFiles (in... | guides/review-results.mdx, guides/audits.mdx | reference/reconciliation-runs.mdx, reference/glossary.mdx | none: no new error | none: no connection setup |
| DAR-BE-279 | DAR-BE-279, DAR-BE-280: list#AutomationExecutions and generated outputs addressed by a ... | none: tenant-isolation hardening | none: internal | none: internal | none: internal |
| DAR-BE-281 | DAR-BE-281, DAR-BE-282: JSON schemas resolve per tenant; the tenant entity filter fails... | none: tenant-isolation hardening | none: internal | none: internal | none: internal |
| DAR-BE-284 | DAR-BE-284: each audit finding record says which connection read it — AuditFindingRecor... | none: internal field for Shopify erase, no UI | none: internal | none: internal | none: internal |
| DAR-BE-276 | DAR-BE-276: PasswordOnlyRouteFence refuses (401, signed out first) authUsername/authPas... | none: no workflow change | reference/auth-and-access.mdx | troubleshooting/login-and-session.mdx | none: no connection setup |
| DAR-BE-028 | DAR-BE-028/264: the per-IP password-reset limit fails closed (security review of c03f1f... | none: password-reset rate-limit hardening, no workflow change | none: internal | none: internal | none: internal |
| DAR-BE-290 | DAR-BE-290: tenant welcome email from support@drpn.ai on every tenant grant, and a re-send | guides/manage-users-and-access.mdx | none: no reference contract | none: the admin app shows refusals beside the member | none: no connection setup |
| DAR-BE-287 | DAR-BE-287: an agent's new check never takes a check id another audit uses — the label'... | guides/audits.mdx | none: no reference contract | none: no new error | none: no connection setup |
| DAR-BE-289 | DAR-BE-289: each rule set builds under its own Drools release id (rule set + signature)... | none: engine correctness fix, no workflow change (one line in release notes) | none: internal | none: internal | none: internal |
| DAR-BE-291 | DAR-BE-291: a check with no templates reads like a configured one — a mismatch headline... | guides/audits.mdx | none: no reference contract | none: no new error | none: no connection setup |
| DAR-BE-292 | DAR-BE-292: tenant users run and connect — play an audit, resolve a finding, run a save... | guides/manage-users-and-access.mdx, guides/audits.mdx, getting-started/ask-darpan-navigation.mdx | reference/auth-and-access.mdx | troubleshooting/audits.mdx, troubleshooting/connections.mdx | guides/setup-shopify.mdx, guides/setup-hotwax.mdx, guides/connect-netsuite.mdx, guides/connect-database.mdx |
| DAR-BE-293 | DAR-BE-293: the audit run update's report links (chat card, email, per-rule links) and ... | guides/audits.mdx | none: no reference contract | troubleshooting/audits.mdx | none: no connection setup |
| DAR-BE-294 | DAR-BE-294: a finding's fix and passes-when text fill {field} from the finding's own re... | guides/audits.mdx | none: no reference contract | none: no new error | none: no connection setup |
| DAR-UI-113 | DAR-UI-113/114/115, DAR-BE-256: drop the dead repeated-key card; Previous Week is seven... | guides/audits.mdx, guides/setup-reconciliation-run.mdx | reference/reconciliation-runs.mdx, reference/glossary.mdx | troubleshooting/audits.mdx | none: no connection setup |
| DAR-UI-116 | DAR-UI-116: CI hardening + the AuditReportPage Resolve flake | none: CI only | none: CI only | none: CI only | none: CI only |
| DAR-UI-117 | DAR-UI-117: agent drafts read cleanly — new statements numbered in approval order on th... | guides/audits.mdx | none: no reference contract | none: no new error | none: no connection setup |
| DAR-UI-118 | DAR-UI-118: an open order's detail opens its open line — the morning's report keeps a l... | guides/audits.mdx | none: no reference contract | none: no new error | none: no connection setup |

## GraphQL reference (developers.drpn.ai)

- SDL compared against (local stack at the release commit, then prod after deploy): `http://localhost:8080/rest/s1/graphql/sdl`, local backend built from the 3.1.0 working tree with moqui-gql at its 1.1.0 commits (2026-11-17); prod re-check owed after deploy
- Snapshot `graphql-reference/schema.graphql` refreshed and pages regenerated, or `none: schema unchanged`: refreshed and regenerated (55 pages); the only schema change since the last snapshot is 3.0.0's removal of `pending`/`pendingUntil`/`graceDays`
- `graphql/changelog.mdx` entry `## Darpan 3.1.0`, or `none: schema unchanged`: `## Darpan 3.1.0` (API key lifetime and owner, store scope narrows the tenant pin) and a catch-up `## Darpan 3.0.0`
- Hand-written pages touched (`graphql/overview.mdx`, `graphql/tasks/`, `search-syntax.mdx`, examples), or `none: <reason>`: graphql/authentication.mdx, graphql/overview.mdx, graphql/tasks/check-the-morning-audit.mdx, graphql/tasks/read-one-finding.mdx, graphql/tasks/list-open-findings.mdx, graphql/tasks/sync-findings.mdx, graphql-reference/examples/auditReport.graphql, graphql-reference/examples/auditReport.json, graphql-reference/examples/auditFinding.graphql, graphql-reference/examples/auditFinding.json

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: every contract method documented, none phantom: 266 of 266 (the 23 added in 3.1.0 plus 66 from 3.0.0 never documented before)
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.1.0: yes, 202 public methods, contract 4
- `api-reference/changelog.mdx` entry for added, changed or removed methods, or `none: <reason>`: "Contract version 4" section with Added in 3.1.0 / Added in 3.0.0 and changed behaviour (login#Session challenge, refusal reasons)

## Engineering and self-host docs

- Runtime baseline (JDK, Moqui, Spark, Drools, transaction manager, JVM flags) matches `build.gradle` in `backend/operations-and-validation.mdx` and `self-host/`: JDK 21, Moqui 4 fork (Bitronix, PBKDF2), Gradle 9, Spark 4.2.1, Drools 10.2.0 / MVEL 2.5.2.Final, webauthn4j-core 0.30.3 in backend/operations-and-validation.mdx and self-host/set-up-darpan-independently.mdx
- Backend pages changed for new services, entities, engine or tenancy behaviour (`backend/`), or `none: <reason>`: backend/operations-and-validation.mdx, backend/service-layer.mdx, backend/data-access-and-tenancy.mdx, backend/overview.mdx, backend/component-layout.mdx
- Self-host and deploy pages changed for new config, env vars, ports, components or failure modes (`self-host/production.mdx`, `self-host/troubleshooting.mdx`), or `none: <reason>`: self-host/production.mdx (pins, env vars, -D properties, non-root image, EFS run files, key rotation, Upgrading to 3.1.0), self-host/troubleshooting.mdx, self-host/environments.mdx, self-host/set-up-darpan-independently.mdx
- Engineering process pages (`engineering/`), or `none: <reason>`: engineering/release-discipline.mdx (current tag lines, docs gate step)

## Release notes and roadmap

- `releases/updates.mdx` carries this release (hotfixes fold into the release line): `## v3.1.0 — November 2026` (darpan-docs 764cbc0)
- `releases/roadmap.mdx` "Recently shipped" and "Up next" rotated: Recently shipped = 3.1.0 and 3.0.3; Up next unchanged (Fulfillment reconciliation not shipped)
