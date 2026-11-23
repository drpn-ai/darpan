# Darpan 3.4.2

Release date: 2026-11-23

## Scope note

- **A hotfix on the 3.4 line** (Aditi, 2026-11-23 16:40 IST: "do it all" — push, cut and deploy the DAR-BE-341 fix).
  Two backend fixes: DAR-BE-341 (a Run now under a requested tenant read its second side in the user's saved tenant)
  and DAR-BE-339 (the exchange presence check stopped at 200 unmatched exchanges). Publicly it folds into the 3.4 notes
  (one `updates.mdx` section for the line).
- Facade contract stays at **5**: no method, parameter or field changed. GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.1` | `v3.4.2` |
| `drpn-ai/darpan-docker-config` | `v3.4.1` | `v3.4.2` (only the darpan pin moves) |
| darpan-ui, darpan-cli, darpan-hotwax, netsuite-darpan, shopify-darpan, database-darpan, darpan-agent, moqui-rules, moqui-jexl, moqui-gql | unchanged | 3.4.1 pins |

## User-visible changes

- **Run now reads both sides in the run's own tenant.** A run started by a person (the app's Run now, or the CLI) reads
  its second side on a parallel worker. That worker took the person's saved tenant instead of the tenant the run was
  started in (the app's per-tab tenant, the CLI's `--tenant`). When they differed, the second side was read without the
  fields its rules need: on 2026-11-23 a HotWax side came back with no `itemQuantity` (every order an Item Sync
  mismatch) and a Shopify returns side lost its pairing fields (half the returns "missing from Shopify"). Scheduled
  runs were never affected: they have no user and run in the automation's tenant. (DAR-BE-341)
- **The exchange presence check looks up every unmatched exchange.** It looked up at most 200 per run and reported the
  rest as "deferred to a later run"; nothing ever checked them, so a weekly window (Gorjana: 566 candidates) left most
  exchanges unverified and a missing one could not become a finding. The cap, its warning, the audit-note clause and
  the `deferredLookupCount` step metric are gone; lookups still go 100 ids per call. (DAR-BE-339)

## Operator-visible changes

- None to configuration, jobs, migrations or settings. The `VERIFY_EXCHANGE` step no longer records
  `deferredLookupCount` (no UI or CLI reads it).

## Upgrade data

- None. `data/upgrade-data.xml` carries no records for 3.4.2 (no seed file changed in `v3.4.1..v3.4.2`); 3.4.1's file is
  archived under `data/releases/3.4.1/`. No data-load action.

## Verification

- `RequestedTenantSmokeTests` 9/9 (the DAR-BE-341 test was RED on the old code: the worker resolved `KREWE`, the saved
  tenant, not `REQ_TENANT_B`); `ScheduledAutomationTenantContextTests` 11/11 (the scheduled side-2 path unchanged);
  `ExchangePairVerificationSupportTests` (DAR-BE-339's 250-candidate test, RED on the old code); `ApiContractGeneratorTests`
  for the version line. See `release-checklist.md`.
- After deploy: re-run Malbon 100459, RAILS 100053 and Dolls Kill 100114/100115 for the 2026-11-23 15:30 IST slot by
  Run now (CLI) and compare with their recent days.

## Deferred items

- DAR-BE-340 (a MySQL deadlock leaves one pooled connection in ROLLBACK ONLY and stops the scheduler until restart) is
  not in this hotfix: its fix shape is Aditi's call.
- `run#AutomationNow` answers 500 when its run passes the 60-second request transaction, though the run itself commits
  (its reply re-reads the automation in the timed-out request transaction). Not filed yet.

## Rollback or fallback notes

- No data or schema change: rolling back to 3.4.1 (`--tag prod-v3.4.1-202611231005-73a966d`) needs nothing else. On
  3.4.1, start Run now only from a tab or CLI tenant equal to the person's saved tenant.
