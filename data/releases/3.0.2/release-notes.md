# Darpan 3.0.2

Release date: 2026-11-12

## Scope note

- **A hotfix on the 3.0 line, cut from `main` in every repo that changed since 3.0.0.** Aditi, 2026-11-12: "create a
  hotfix and deploy it... include all changes and push anything on local". Every repo's local `main` was already on
  `origin/main`; nothing else was waiting to be pushed.
- **No darpan 3.0.1.** `v3.0.1` is darpan-docker-config's agent-only hotfix (darpan-agent v0.1.1, darpan stayed
  `v3.0.0`, image `prod-v3.0.1-…`). docker-config's tag must equal darpan's version (the pins gate and the CI jobs
  check it out by darpan's tag), so darpan goes to 3.0.2 and the two line up again.
- Facade contract unchanged (4); `component.xml` 3.0.0 -> 3.0.2.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.0.0` | `v3.0.2` |
| `drpn-ai/darpan-ui` | `v4.0.1` | `v4.0.2` |
| `drpn-ai/darpan-hotwax` | `v1.0.0` | `v1.0.1` |
| `drpn-ai/shopify-darpan` | `v0.9.0` | `v0.9.1` |
| `drpn-ai/netsuite-darpan` | `v0.5.0` | `v0.5.1` |
| `drpn-ai/darpan-agent` | `v0.1.1` | `v0.1.2` |
| `drpn-ai/darpan-docker-config` | `v3.0.1` | `v3.0.2` (darpan, hotwax, shopify, netsuite and agent pins move) |
| `drpn-ai/database-darpan`, `drpn-ai/moqui-gql` | `v0.3.0`, `v1.0.0` | not re-cut (no commits) |

## User-visible changes

### Audits

- **The earliest broken step wins.** An order with findings under several statements counts once, under its
  earliest statement; the later statements' findings on that order are listed under it as "Also on this order" and
  are not counted again. A statement's counts say how many of its findings sit under an earlier step. Applies to the
  report, the by-order list and the run email (DAR-BE-250).
- **Pick the chat space for the update after each run.** The audit's settings section is now "After each run": one
  switch for the email and the chat card, and a Chat space choice (None or one of the tenant's spaces). Only tenant
  admins change it (DAR-UI-111).
- **The run email looks like Darpan**: the app's light theme, IBM Plex Mono, greys only (DAR-BE-207).
- **Claude writes the run update's headline and fix lines** when the tenant has the agent on. Every number and id is
  still Darpan's; a sentence that names a number the run does not have is dropped, and with no answer the email goes
  out with the causes' own fix text (DAR-BE-207, darpan-agent v0.1.2).
- **Darpan admins have AI access in every tenant** without a grant; the tenant switch, daily budget and hourly limit
  still apply (DAR-BE-246).

### Reconciliation

- **Run notifications never print a rule's formula.** A named rule keeps its own row ("Fulfillment Sync Mismatch:
  14"); mismatches on unnamed rules pool into one "Field mismatches: N" line (DAR-BE-248).
- **NetSuite fulfillments counted against HotWax ship groups, per location.** A NetSuite order query can name its
  fulfillments-by-location field (`LINKED_LOCATIONS`), and each order gets `omsShipGroupCheck`: MATCHES, SPLIT (one
  fulfillment became several ship groups), HOTWAX_ONLY, NO_FULFILLMENT or NOT_IN_OMS (DAR-BE-245; darpan-hotwax v1.0.1,
  netsuite-darpan v0.5.1).
- **Item count on an order, Shopify vs HotWax.** Both order reads carry `itemQuantity` (Shopify: units ordered;
  HotWax: the order's item quantities summed), so a run can check them equal (DAR-BE-251; shopify-darpan v0.9.1,
  darpan-hotwax v1.0.1).

## Operator-visible changes

- **One new column**: `NsSuiteQlSourceQuery.omsShipGroupsField` (text-short, nullable). Moqui adds it on startup; no
  data load.
- **The run update calls Claude Haiku** (`write#AuditRunWords`, about $0.0001 a run) for every tenant with
  `agentEnabled=Y` while the deployment's agent switch is on. It authenticates as the agent does (workload identity
  federation on prod). Off: no call, and the email carries no headline.
- **`AgentEnabled` is a prod stack parameter** (docker-config `ef08069`, default `false`) feeding
  `DARPAN_AGENT_ENABLED`; `deploy.sh --set AgentEnabled=false` turns the agent off in one deployer run.
- **Deploy backend and UI together**: darpan-ui 4.0.2 reads DAR-BE-250's step fields and the run update's chat space.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records (no `data/` change in `v3.0.0..`); `loadDarpanUpgradeData` is a
  no-op for this release.
- The 3.0.0 load target stays archived, byte-identical to `v3.0.0`'s `data/upgrade-data.xml`, at
  `data/releases/3.0.0/upgrade-data.xml`.
- No migrations.

## Verification

- Focused tests on the range's changed classes, both contract tripwires, UI type-check, lint and specs; counts in
  `release-checklist.md`.
- Deploy pins: `verify_release_pins.py` exit 0 against the pushed tags.

## Deferred items

- After deploy (each on its own backlog item): DAR-BE-251 adds the item-count rule to the six RAILS and Dolls Kill
  order runs; DAR-BE-245 transfers the RAILS change pack; DAR-BE-250 applies its phase 3 bundle on gorjana; DAR-UI-111
  picks a space on gorjana Month close.
- DAR-OPS-015: the OWASP dependency scan on darpan `main` is still unread.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v3.0.1` (darpan v3.0.0, darpan-agent v0.1.1). The new column stays and
  3.0.0 ignores it; a run whose rule reads `itemQuantity` or `omsShipGroupCheck` then finds the field empty.
