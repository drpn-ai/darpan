# Darpan 3.5.1

Release date: 2026-11-26

## Scope note

- **A patch on 3.5.0, rolled with it as one restart** (Aditi, 2026-11-26 16:17 IST: hold the 3.5.0 roll, go straight to
  3.5.1; 16:55: cut from darpan main 6489d83b). It carries `v3.5.0` plus DAR-BE-377 (1c5be890), the sync pulse reading
  Shopify orders as a check's upstream side for Gorjana's Black Friday (Nov 27), and DAR-BE-378 (6489d83b), errors and
  warnings kept out of client chat rooms. DAR-BE-320 (daily NetSuite reads of only the new days) follows as 3.5.2.
- **Upgrade data**: 4 records (2 SourceSystemConnector rows, 2 mascot AppContentEntry rows), loaded on boot. No migration of
  its own; 3.5.0's `ACK_NOTE_NULLABLE` still runs after the deploy.
- Facade contract stays at **6** (337 methods): one service description changed, no field.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.0` (cut, not deployed; prod runs `v3.4.16`) | `v3.5.1` |
| `drpn-ai/darpan-docker-config` | `v3.5.0` | `v3.5.1` (DARPAN_REF only) |
| darpan-hotwax `v1.4.0`, shopify-darpan `v0.12.0`, netsuite-darpan `v0.8.0` and every other pin | 3.5.0's | unchanged |
| `drpn-ai/darpan-ui` | `v4.4.15` (3.5.0's pairing) | unchanged; the mascot words reach the app as data |

## User-visible changes

- **The sync pulse watches Shopify → HotWax orders** (DAR-BE-377). A sync pair whose upstream side is Shopify orders can now
  pulse every 1–6 hours like a HotWax one: it reads the recent Shopify orders, asks HotWax which it holds, and lists the
  ones past the allowance. Before, saving a pulse on such a pair said "A pulse needs HotWax as the upstream side."
- The refusal for any other upstream now reads "A pulse needs HotWax or Shopify orders as the upstream side."
- The mascot's Sync pulse and Upstream side words say the same.
- **Errors stay out of client chat rooms** (DAR-BE-378). Errors and warnings go to the Darpan team's alerts room, never a client chat room. The audit update's chat card and the digest's chat message no longer say a check did not run (the email still does). An automation's own chat space is no longer posted a run error (Notify me still is). And the update after a run goes out once a morning, after every due check has run, not again as late checks finish.

## Operator-visible changes

- `SourceSystemConnector` gains `pulseCreatedField` and `pulseNameField`: an endpoint can be a pulse's upstream side only
  when its connector sets them (HotWax orders `orderDate`/`orderName`, Shopify orders `createdAt`/`name`). Which systems a
  pulse reads is data; the `OMS_GQL` constant is gone.
- A Shopify-upstream pulse reads through the check's own dispatch: from the tenant's kept copy when it covers the window,
  else a live Bulk Operation (which may wait for the shop's bulk slot).

## Upgrade data

- `data/upgrade-data.xml`: SourceSystemConnector `OMS_GQL` and `SHOPIFY` (from `data/SourceSystemConnectorSeedData.xml`;
  create-or-update sets only the attributes present, so the system components' fields on those rows stay), AppContentEntry
  `MASCOT_GLOSSARY` `syncPulse` and `syncPairUpstream` (from `data/AppContentSeedData.xml`, same seed mark). The previous is
  archived at `data/releases/3.5.0/upgrade-data.xml` (equals the `v3.5.0` tag's root). The two new columns are added on boot.

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-BE-320 (daily audit runs read NetSuite only for the new days): 3.5.2.

## Rollback or fallback notes

- Roll back to `v3.5.0` (docker-config `v3.5.0`): the two columns stay, unread; a Shopify pair's pulse then skips silently,
  and saving one is refused again; run errors and warnings reach client chat rooms again. Further back, 3.5.0's own notes apply.
