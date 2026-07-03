# Darpan 1.0.2 Release Notes

Release date: `2026-07-03`

## Scope note

`v1.0.2` is the first release of `drpn-ai/darpan` as a standalone repository, and the
version production images pin to and build from. Repository history was reset to a
clean baseline on 2026-07-03 and versioning restarts here; the retired pre-reset tag
series (`v1.x`/`v2.x`) must never be compared against or rolled back to.

Because there is no previous release, the full baseline is the release content. The
interim `v1.0.0` and `v1.0.1` tags cut earlier the same day were folded into this
release and have no separate release pages; `v1.0.2` supersedes them (their in-repo
release packs remain under `data/releases/` for engineering history).

Deferred to later releases: composite-key reconciliation matching, run idempotency
keys, returns reconciliation, and multi-file-source stitching (see the public roadmap).

## Repo targets

- Backend repo: `drpn-ai/darpan` (`https://github.com/drpn-ai/darpan`)
- Branch: `main`; tag `v1.0.2` (exact commit in `release-checklist.md`)
- Compare range: `1d01750..v1.0.2` (baseline commit to tag)
- Companion component tags pinned by this release: `darpan-hotwax v0.3.0`,
  `shopify-darpan v0.3.0`, `netsuite-darpan v0.2.0` — all cut at the component mains
  that darpan CI proves against this code.
- UI: not included — `darpan-ui` versions and deploys independently.

## User-visible changes

This release establishes the platform baseline rather than a feature delta:

- Multi-system reconciliation of order and inventory data across connected source
  systems (HotWax OMS, NetSuite, Shopify via companion connector components), with
  Spark-based comparison runs, run scheduling, and run/system-instance management.
- Configurable reconciliation rule engine (Drools) with server-side rule generation,
  field-comparison rule types, and per-tenant rule scoping.
- Multi-tenant model with tenant-scoped data access and permission groups
  (`DARPAN_ADMIN`, `DARPAN_SUPER_ADMIN`).
- REST facade API with a versioned, snapshot-checked contract consumed by the Darpan UI.

## Operator-visible changes

- Production images build directly from this tag with no build-arg overrides:
  `DARPAN_REF=v1.0.2`, `DARPAN_HOTWAX_REF=v0.3.0`, `SHOPIFY_DARPAN_REF=v0.3.0`,
  `NETSUITE_DARPAN_REF=v0.2.0`. All product and framework refs are immutable tags or
  SHAs (single documented exception: `MOQUI_GQL_REF=main`, a phase-0 component not in
  the public tier).
- Runtime baseline: JDK 21, Moqui 4 (embedded Bitronix JTA), Gradle 9. Required JVM
  flags are exported by `docker/entrypoint.sh`; do not strip them.
- In-container data loads work from a plain exec shell:
  `java -jar moqui-plus-runtime.war load conf=$CONF_FILE types=darpan-seed`.
- Environments whose database predates the 2026-07-01 connector-registry seed must run
  that `types=darpan-seed` load once (then restart the pod so the entity cache picks up
  the `SourceSystemConnector` rows) before manual saved-run execution of API sources.
- CI security gate requires the `NVD_API_KEY` repository secret for OWASP
  dependency-check.

## Upgrade data

- No incremental upgrade records ship with this release; generic seed files under
  `data/*.xml` are the source of truth and `data/upgrade-data.xml` is an intentionally
  empty candidate. Pre-reset release packs are archived under
  `data/releases/pre-reset/` (retired numbering — see the README there).
- Fresh environments: run the standard full data load (`./gradlew loadDarpanData`).
- Existing environments: no data-load action beyond the connector-registry note above.
  `./gradlew loadDarpanUpgradeData` (idempotent `darpan-seed` upsert) is always safe to
  re-assert convergence.

## Verification

- Full backend test suite (445 tests) green in CI on the tagged commit, plus
  release-pack validation, XML well-formedness checks, `compileGroovy`, and the
  `checkApiContract` gate (65 methods).
- Conf-expansion fix verified with a Groovy 4 harness covering both fallback branches.
- Live-deploy smoke not run as part of the cut.

## Deferred items

- Composite-key reconciliation matching (multi-field record identity).
- Run idempotency keys.
- Returns reconciliation and multi-file-source stitching.
- Decision on retiring the old `darpan-ui` v1.x/v2.x tag series.

## Rollback or fallback notes

- No earlier deployable tag exists on the new baseline; if `v1.0.2` must be rolled
  back, rebuild with `--build-arg DARPAN_REF=<known-good SHA>`.
- Never fall back to pre-reset tags (`v1.x`/`v2.x`); they no longer exist on the remote.
