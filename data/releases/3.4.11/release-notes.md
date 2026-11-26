# Darpan 3.4.11

Release date: 2026-11-26

## Scope note

- **A backend release on the 3.4 line** (Aditi, 2026-11-26 10:55 IST "deploy and release the cli", then "Release 3.4.11"
  over a new main-tracking tag). It carries everything on darpan `main` after `v3.4.10`: DAR-CLI-008 part 1 only. Ships
  with `darpan` CLI `0.3.0`. Publicly it folds into the 3.4 notes.
- **Upgrade data**: none (0 records). No entity change, no migration.
- Facade contract stays at **5** (no method added or removed). GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.10` | `v3.4.11` |
| `drpn-ai/darpan-docker-config` | `v3.4.10` | `v3.4.11` (DARPAN_REF move) |
| `drpn-ai/darpan-cli` | `v0.2.5` | `v0.3.0` |
| darpan-ui, darpan-hotwax, shopify-darpan and every other component | unchanged | unchanged |

## User-visible changes

- **The Darpan CLI can make any change your role allows in the app** (DAR-CLI-008), with CLI `0.3.0`: runs,
  automations, audits and their statements, peak periods, sync pairs, notification routes, queries and their schedules,
  finding acknowledgements, Config history restores and the audit agent's proposals. `darpan api` marks each method that
  changes something, and `darpan call` sends those only with `--yes`. `darpan config restore` previews a restore and
  restores exactly that plan with `--yes --plan <hash>`. Connection credentials, sign-in, two-factor, passkeys, CLI
  access and Shopify or Slack installs stay in the app.
- **A view-only member can no longer switch a connection's endpoints** (`store#SourceConfigEndpointAccess` had no role
  check; it now needs edit access, in the app as in the CLI).
- CLI `0.3.0` drops `darpan scripts`, `script run|save|delete`, `deployments` and `deploy*`: they called the tenant script
  methods 3.4.0 replaced with queries, so they no longer worked. `darpan call` reaches the query methods (DAR-CLI-009
  brings named commands back).

## Operator-visible changes

- `CliMethodAllowlist`: 80 tenant-app methods move from EXCLUDED to ALLOWED. Each service checks the caller's tenant
  role itself; a new smoke test pins that a view-only member is refused by 20 of them.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous archived at `data/releases/3.4.10/upgrade-data.xml`
  (equals the `v3.4.10` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-CLI-008 part 2 (explain a finding, mark a cause expected) and part 3 (follow-the-order lookup).
- DAR-CLI-009 (named query commands).

## Rollback or fallback notes

- Roll back by redeploying `v3.4.10` (docker-config `v3.4.10`). CLI writes then answer "not available to the Darpan CLI"
  (exit 7); CLI `0.3.0` keeps reading.
