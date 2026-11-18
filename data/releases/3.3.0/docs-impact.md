# Docs Impact For Darpan 3.3.0

Every shipped item has a decision per surface: the page changed (a path in its docs repo) or `none: <reason>`.
UI labels were checked against `darpan-ui/src` at `ba4032c`: every bold label in darpan-docs that existed at app
`v4.2.0` and is gone at HEAD was listed, and each is handled below.

## Product docs (docs.drpn.ai)

darpan-docs commit `1e05972` (unpushed).

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-303 | Email words and subjects editable in the admin app | none: super-admin feature in the admin app, which docs.drpn.ai does not cover | none: not a tenant-facing contract | none: no new tenant-facing message | none: no connection change |
| DAR-BE-315 | Plain-text email parts carry every sentence and link | none: no page describes the text part | none: no reference content | none: no new message | none: no connection change |
| DAR-BE-304 | Settings store | none: admin app only | none: no shipped default changed | none: no new message | none: no connection change |
| DAR-BE-305 | Scheduled jobs editable | none: admin app only | none: not tenant-facing | none: no new message | none: no connection change |
| DAR-BE-306 | Retention and purge periods as settings, per tenant | none: the shipped periods are unchanged (extracts 7 days, results 15 days, retention off) and only a super admin changes them | none: the "deleted 7 days after the run" statements in the reference and glossary stay true by default | none: no new message | none: no connection change |
| DAR-BE-313 | Mascot words served from the backend | none: no page describes the mascot's words | none: no reference content | none: no new message | none: no connection change |
| DAR-BE-307 | Agent prompts, models, prices, texts and caps as data | none: admin app only; the darpan-agent README documents it (3f223d7) | none: no page names the agent properties | none: no new message | none: no connection change |
| DAR-UI-120 | Username-first sign-in; the standalone passkey button is gone | guides/two-factor.mdx | reference/auth-and-access.mdx | troubleshooting/login-and-session.mdx | none: no connection change |
| DAR-UI-121, 122, 125 to 129 | Settings pages as rows, popups, icon edit buttons | owed, see below | none: no reference content | owed, see below | none: no connection change |
| DAR-UI-123 | Notifications grid and Add space flow | owed, see below | none: no reference content | owed, see below | owed, see below |
| DAR-UI-124 | Scripts list and per-script pages | owed, see below | none: the functions reference is on the developers site (a03f05c) | none: no new message | none: no connection change |
| DAR-UI-131 | Drag the mascot | none: no page describes the mascot's position | none | none | none |

Owed (Aditi's call: a docs pass before the cut, or a named follow-up after it). These pages still name labels the
reworked settings pages no longer show; the gate below fails on each until it is rewritten from the new pages:

- guides/manage-tenant-settings.mdx: Tenant Context, Streaks space and Save Streaks Space, Darpan admin only, and the
  Scripts profile and deploy labels (DAR-UI-122, 123, 124).
- guides/run-completion-alerts.mdx: Add a chat space, Connect with a bot token, Replace bot token, Reconnect and
  Disconnect workspace, Streaks space (DAR-UI-123).
- troubleshooting/automations-and-alerts.mdx: Connect with a bot token (DAR-UI-123).
- getting-started/tenant-admin-quickstart.mdx and getting-started/core-concepts.mdx: Tenant Context, Streaks space,
  Darpan admin only (DAR-UI-121, 122).
- guides/configure-ai.mdx: Darpan admin only (the AI row is now hidden from tenant admins, DAR-UI-122).
- guides/audits.mdx and troubleshooting/audits.mdx: Save statement, Only tenant admins can change this (DAR-UI-127).
- guides/author-rules.mdx and guides/setup-reconciliation-run.mdx: Edit Rules (DAR-UI-128).
- guides/review-results.mdx: Move up, Move down, Save conclusions (DAR-UI-128).

## GraphQL reference (developers.drpn.ai)

- SDL compared against the local stack (`http://localhost:8080/rest/s1/graphql/sdl`, started 18:59 IST from the darpan
  working tree): "deployed SDL matches the snapshot; 55 generated pages and the navigation are current". moqui-gql has
  no commits since v1.1.0, and the release adds no GraphQL type. Prod re-check after deploy stays owed.
- SDL snapshot refreshed and pages regenerated: none: the schema is unchanged.
- GraphQL changelog: none: the snapshot is unchanged, so no `## Darpan 3.3.0` entry.
- Hand-written GraphQL pages: none: no GraphQL behaviour changed.

## Service catalog and public OpenAPI

darpan-developers commit `4edb896` (unpushed, with `e1a16b6`).

- api-reference/service-catalog.mdx: the 33 added methods documented (app content, notification routes, settings,
  emails, scheduled jobs, retention periods, mascot words, agent config); catalog at Darpan 3.3.0;
  `check_service_catalog.py` against the release `methods.txt`: 301 of 301 documented, none phantom.
- api-reference/openapi.json: regenerated with `scripts/public_openapi.py` from the release contract at 3.3.0
  (208 public methods, no `admin.*`).
- api-reference/changelog.mdx: "Added in 3.3.0", contract 4, 204 to 208 public methods.

## Engineering and self-host docs

- Runtime baseline: none: `build.gradle` is unchanged in the range (Spark 4.2.1, Drools 10.2.0, JDK 21, Moqui 4).
- backend/operations-and-validation.mdx (e1a16b6): retention periods and purge days are settings edited in the admin
  app; job edits survive deploys.
- backend/reconciliation-engine.mdx (1254683): the stuck-run reaper threshold is a setting.
- self-host/production.mdx (e1a16b6, 4edb896): retention periods, purge days and the retention batch size are
  settings, not JVM properties.
- Self-host for DAR-BE-307: none: no page names the author-model JVM properties that were removed.
- Engineering process pages: none: no process change.
- DAR-UI-124: tenant-scripts functions reference (a03f05c, already pushed).

## Release notes and roadmap

- releases/updates.mdx: one `## v3.3.0` section (darpan-docs 1e05972).
- releases/roadmap.mdx: "Recently shipped" is v3.3.0 and v3.2.1; "Up next" unchanged (fulfillment reconciliation is
  not shipped).
