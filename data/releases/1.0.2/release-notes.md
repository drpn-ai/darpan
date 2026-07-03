# Darpan 1.0.2 Release Notes

Release date: `2026-07-03`

## Scope note

Hotfix on top of `v1.0.1`. Two operator-facing fixes, no product code changes:

1. `docker/MoquiProductionConf.xml`: the `entity_ds_crypt_pass_old` default used shell
   `${var:-default}` syntax inside a Groovy-expanded attribute, crashing any process
   started outside the container entrypoint (e.g. `kubectl exec` data loads) with
   `MissingMethodException: No signature of method: $`. Replaced with the Groovy Elvis
   form (`${entity_ds_crypt_pass_old ?: entity_ds_crypt_pass}`) — same fallback
   semantics in steady state and during a crypt-key rotation window.
2. `NETSUITE_DARPAN_REF` pinned to the new `netsuite-darpan v0.2.0` (previously
   `v0.1.0`, which predated the JDK 21 / Moqui 4 migration and lagged its main by
   10 commits).

## Repo targets

- Backend repo: `drpn-ai/darpan`; branch `main`; tag `v1.0.2` on the release-prep commit
- Compare range: `v1.0.1..v1.0.2`
- Companion tag cut with this release: `netsuite-darpan v0.2.0`
- UI: not included.

## User-visible changes

- None.

## Operator-visible changes

- In-container data loads (`java -jar moqui-plus-runtime.war load conf=$CONF_FILE
  types=darpan-seed`) now work from a plain `kubectl exec` shell without manually
  exporting `entity_ds_crypt_pass_old` first.
- Prod image pins: `DARPAN_REF=v1.0.2`, `DARPAN_HOTWAX_REF=v0.3.0`,
  `SHOPIFY_DARPAN_REF=v0.3.0`, `NETSUITE_DARPAN_REF=v0.2.0`.
- Reminder from the v1.0.x line: environments whose database predates the 2026-07-01
  connector-registry seed must run the `types=darpan-seed` load (then restart the pod
  so the entity cache picks up the rows) before manual saved-run execution of API
  sources works.

## Upgrade data

- No new upgrade records in `v1.0.1..v1.0.2`; `data/upgrade-data.xml` remains an empty
  candidate and the `1.0.1` current file is archived under `data/releases/1.0.1/`.
- No operator data-load action required by this tag itself (see the reminder above for
  older databases).

## Verification

- Fast local gates passed: release pack validation, XML well-formedness on changed
  files, `compileGroovy`, `checkApiContract` (65 methods), plus a Groovy 4 harness
  reproducing the conf crash and proving both fallback branches of the fixed
  expression.
- Full backend test suite: NOT awaited before tagging — the release owner opted to
  tag immediately; CI runs on the pushed commit and any failure will be followed up.
- Live-deploy smoke: not run.

## Deferred items

- Unchanged from `v1.0.1` (composite-key matching, idempotency keys, returns
  reconciliation, multi-file-source stitching, `darpan-ui` old-tag decision).

## Rollback or fallback notes

- Product code is identical to `v1.0.0`/`v1.0.1`. Fallback: build with
  `DARPAN_REF=v1.0.1` and export `entity_ds_crypt_pass_old` manually before any
  exec'd data load.
