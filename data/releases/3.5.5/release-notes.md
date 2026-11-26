# Darpan 3.5.5

Release date: 2026-11-26

## Scope note

- **A second prod roll tonight** (Aditi 20:12 via the coordinator: "Fix + deploy 3.5.5 tonight", before Black Friday 13:30
  IST). 3.5.5 = `v3.5.4` + DAR-BE-383 (1f57a936) only.
- **Upgrade data**: none. **Migration**: none. **App**: unchanged (`v4.4.17`).
- Facade contract stays at **6** (242).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.5.4` | `v3.5.5` |
| `drpn-ai/darpan-docker-config` | `v3.5.4` | `v3.5.5` (DARPAN_REF only) |
| components, framework, `darpan-ui` | unchanged | unchanged |

## User-visible changes

- **Runs and sync pulses no longer fail now and then with a missing-file error (FILE_NOT_EXIST)** (DAR-BE-383). Since 3.5.0
  the ID index's background read of a run's extract used the run's own working file (`<extract>.records.jsonl`) and removed
  it, racing the run's own read. It now reads through a temp file of its own. Seen on prod after the 3.5.1 roll (GORJANA,
  MALBON, RAILS compare stages; most recovered on retry) and on tonight's GORJANA test pulses.

## Operator-visible changes

- The ID index writes its working copy of an extract to the JVM temp directory and deletes it after indexing.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; the previous is archived at `data/releases/3.5.4/upgrade-data.xml`.

## Verification

- `release-checklist.md`.

## Deferred items

- None.

## Rollback or fallback notes

- Roll back by redeploying `v3.5.4` (docker-config `v3.5.4`, image `prod-v3.5.4-202611261338-2c25d88`).
