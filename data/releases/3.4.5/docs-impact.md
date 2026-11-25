# Docs Impact For Darpan 3.4.5

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. UI labels grep-checked against darpan-ui `src`; **Keep exchange orders** is the connector's own
option label (darpan-hotwax `extractOptionsJson`), which the Create Run options card renders as given.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-353 | Audit settings list, per statement, only the causes its checks can reach (a link's row kinds mapped to the engine's buckets); statements sharing one run no longer each list the whole tree, and a priority on a cause now applies. Limit: two rule checks on one run still share rule causes | guides/audits.mdx | none: no reference page lists audit causes; the how-to's settings table is the place | troubleshooting/audits.mdx ("A cause is not listed under a statement") | none: no connection change |
| DAR-BE-354 | A Shopify order HotWax already holds, under 24 h old when read, concludes **On its way to NetSuite** instead of held in HotWax (the brain ages Shopify orders from `createdAt`) | guides/audits.mdx ("Orders on their way to NetSuite") | none: brain causes are not listed in reference | none: no new message or failure; such findings simply stop opening | none: no new read |
| DAR-BE-345 option B (darpan-hotwax 1.3.1) | A HotWax orders run side can turn on **Keep exchange orders** (off by default), so exchange orders are compared instead of set aside | guides/returns-and-exchanges.mdx | reference/reconciliation-runs.mdx (set-aside table) | none: no new message; an existing side reads exactly as before | guides/setup-hotwax.mdx (Orders endpoint row) |
| DAR-BE-352 (darpan-hotwax 1.3.1) | The HotWax returns read can read by the return's Shopify order id, so an audit's daily re-check reaches older findings of a returns side keyed that way | none: the re-check is automatic; guides/audits.mdx already says each morning run re-checks open findings by key | none: no field, status or stage changed | troubleshooting/audits.mdx ("Older findings were not re-checked" under a check marked Partial) | none: no connection or credential change |
| DAR-UI-141 (backend half) | CORS preflight allows `X-Darpan-Tenant`; the app does not send it yet | none: nothing a user does changes | none: no user-facing term | none: no user-facing failure | none: operator-only; on the developers self-host page |
| DAR-UI-140 / DAR-UI-141 (mascot seed) | In-app help content regenerated (audit priority hints; the DAR-UI-141 tenant lines reverted with the app change) | none: mascot words are in-app help, not docs | none: same | none: same | none: same |
| backend 5832eb5c | 3.4.4 pack completed after its tag | none: release tooling only | none: release tooling only | none: release tooling only | none: release tooling only |
| Glossary text fix (b1c3ace1) | The mascot glossary's query data name text drops its angle brackets (data) | none: in-app help wording only | none: same | none: same | none: same |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged (moqui-gql stays `v1.1.1`, no GraphQL type or field touched between
  `v3.4.4` and `v3.4.5`); `verify_release_docs.py` compares the prod SDL with the snapshot after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: none: schema unchanged.
- Hand-written GraphQL pages: none: the narrowed audit causes are not exposed over GraphQL; no query, field or example
  changed.

## Service catalog and public OpenAPI

- Service catalog: api-reference/service-catalog.mdx (`get#AuditSettings` / `save#AuditSettings`: `reasons` are the
  conclusions a statement's checks can reach, from each link's row kinds). Every contract method documented, none
  phantom: `check_service_catalog.py` against the `v3.4.5` methods list, 319 of 319.
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.5: yes (from the component's
  `v3.4.5` contract; only the version and description changed, 226 public methods).
- `api-reference/changelog.mdx`: a 3.4.5 entry under contract 5 (no method, parameter or field changed; `reasons`
  narrowed, a level saved on a reason no longer reached still listed with `checks` 0).

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged from 3.4.4 (Spark 4.2.1, Drools 10.2.0, JDK 21, Bitronix,
  JVM flags); no baseline page needed a change.
- Backend pages: backend/reconciliation-engine.mdx (Keep: a built-in line's `appliesUnless` extract options, HotWax
  orders' `keepExchangeOrders`).
- Self-host and deploy pages: self-host/production.mdx (3.4.5 under "Upgrading to 3.4": image moves `darpan` and
  `darpan-hotwax` `v1.3.1`, 2,325 upgrade records, no migration; the `X-Darpan-Tenant` preflight header and a proxy that
  answers preflights itself; the Shopify in-flight grace; the returns re-check by Shopify order id). The self-host
  troubleshooting page: none: no new failure mode.
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section is `## v3.4.5` (one section per line), with 3.4.5 named in the intro, its
  items under Runs and results and Audits, and an Operators line.
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.5 and app v4.4.5. "Up next" unchanged (fulfillment
  reconciliation is not shipped).

Docs commits: darpan-docs `37c674f`, darpan-developers `9ec5a04`.
